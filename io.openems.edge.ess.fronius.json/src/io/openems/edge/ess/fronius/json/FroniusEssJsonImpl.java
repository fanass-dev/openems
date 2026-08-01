package io.openems.edge.ess.fronius.json;

import static io.openems.common.utils.JsonUtils.getAsJsonObject;
import static org.osgi.service.component.annotations.ConfigurationPolicy.REQUIRE;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.openems.common.bridge.http.api.BridgeHttp;
import io.openems.common.bridge.http.api.BridgeHttpFactory;
import io.openems.common.bridge.http.api.HttpResponse;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.bridge.http.cycle.HttpBridgeCycleServiceDefinition;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.ess.api.SymmetricEss;
import io.openems.edge.ess.power.api.Power;
import io.openems.edge.timedata.api.Timedata;
import io.openems.edge.timedata.api.TimedataProvider;
import io.openems.edge.timedata.api.utils.CalculateEnergyFromPower;

/**
 * Reads the battery storage of a Fronius Hybrid Inverter (GEN24 or Symo
 * Hybrid) via the Fronius Solar API v1 ({@code GetStorageRealtimeData.cgi})
 * over plain JSON/HTTP, i.e. without Modbus/SunSpec.
 *
 * <p>
 * With {@code Scope=Device} the JSON is flat (not wrapped in
 * {@code {Value,Unit}} like {@code GetInverterRealtimeData.cgi}):
 * {@code Body.Data.Controller.<field>}, e.g. {@code Body.Data.Controller.
 * StateOfCharge_Relative}.
 *
 * <p>
 * Test the endpoint in your browser first, e.g.:
 * {@code http://<inverter-ip>/solar_api/v1/GetStorageRealtimeData.cgi?Scope=Device&DeviceId=0}
 *
 * <p>
 * Reading is done via the official, read-only Fronius Solar API v1 (JSON over
 * plain HTTP). Optionally (Config {@code ControlMode != READ_ONLY}), this
 * Component ALSO implements {@link ManagedSymmetricEss} and relays
 * {@link #applyPower} setpoints to the device via the inofficial,
 * undocumented Fronius Web-Config-API (Digest-Auth over HTTP) - see
 * {@link FroniusControlClient} and readme.adoc for the full mechanism,
 * caveats and safety warnings. With the default {@code ControlMode ==
 * READ_ONLY}, no write ever happens and the Component behaves exactly like a
 * plain, read-only Ess.
 *
 * <p>
 * There is no combined "PV+Battery" AC power here on purpose: the PV side of
 * the same GEN24 is already covered by the separate
 * {@code io.openems.edge.pvinverter.fronius.json} Component, and the grid
 * connection by {@code io.openems.edge.meter.fronius.smartmeter.json} - this
 * mirrors the standard OpenEMS modelling of Meter + PV-Inverter + Ess as
 * three independent Components even on a combined hybrid device.
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Ess.Fronius.Json", //
		immediate = true, //
		configurationPolicy = REQUIRE //
)
public class FroniusEssJsonImpl extends AbstractOpenemsComponent
		implements FroniusEssJson, ManagedSymmetricEss, SymmetricEss, OpenemsComponent, TimedataProvider {

	private final Logger log = LoggerFactory.getLogger(FroniusEssJsonImpl.class);

	private String url;
	private int pollEveryCycles = 3;

	@Reference
	private BridgeHttpFactory httpBridgeFactory;
	@Reference
	private HttpBridgeCycleServiceDefinition httpBridgeCycleServiceDefinition;
	private BridgeHttp httpBridge;

	@Reference
	private Power power;

	// Optional Timedata reference so CalculateEnergyFromPower can look up the
	// last known cumulative value across an Edge restart instead of always
	// starting the accumulation over at zero.
	@Reference(policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY, //
			cardinality = ReferenceCardinality.OPTIONAL)
	private volatile Timedata timedata = null;

	// ACTIVE_CHARGE_ENERGY/ACTIVE_DISCHARGE_ENERGY are plain Channels - unlike
	// e.g. ElectricityMeter's Active-Production-/Active-Consumption-Energy they
	// are NOT integrated automatically from ActivePower by the framework. Each
	// Ess implementation has to accumulate them itself - this mirrors the
	// pattern used e.g. in the built-in Ess.Samsung bundle.
	private final CalculateEnergyFromPower calculateChargeEnergy = new CalculateEnergyFromPower(this,
			SymmetricEss.ChannelId.ACTIVE_CHARGE_ENERGY);
	private final CalculateEnergyFromPower calculateDischargeEnergy = new CalculateEnergyFromPower(this,
			SymmetricEss.ChannelId.ACTIVE_DISCHARGE_ENERGY);

	// --- Control path (ControlMode != READ_ONLY only) -------------------------
	private ControlMode controlMode = ControlMode.READ_ONLY;
	private FroniusControlClient controlClient;
	private ScheduledExecutorService controlExecutor;
	private final AtomicInteger desiredActivePower = new AtomicInteger(0);
	private final AtomicBoolean hasDesiredActivePower = new AtomicBoolean(false);
	private volatile int lastWrittenActivePower = Integer.MIN_VALUE;
	private volatile long lastWriteTimeMillis = 0L;
	private int writeDeadbandWatt = 100;
	private long minWriteIntervalMillis = 15_000L;
	private boolean allowGridCharging = false;
	private volatile boolean gridChargeFlagSynced = false;
	private final AtomicBoolean timeOfUseBackedUp = new AtomicBoolean(false);
	// Jeweils eigener Backoff pro Schreibpfad, damit ein dauerhaft fehlschlagender
	// Endpunkt (z. B. weil Fronius' eigene Login-Sperre aktiv ist, siehe
	// https://github.com/muexxl/batcontrol/issues/125) nicht weiterhin jeden
	// einzelnen 5-Sekunden-Zyklus erneut angefragt wird - das wuerde eine
	// bestehende Sperre nur verlaengern bzw. eine neue erst auslösen.
	private final RetryBackoff timeOfUseBackupBackoff = new RetryBackoff();
	private final RetryBackoff gridChargeFlagBackoff = new RetryBackoff();
	private final RetryBackoff applyPowerBackoff = new RetryBackoff();

	public FroniusEssJsonImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				SymmetricEss.ChannelId.values(), //
				ManagedSymmetricEss.ChannelId.values(), //
				FroniusEssJson.ChannelId.values() //
		);
	}

	@Activate
	protected void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.pollEveryCycles = Math.max(1, config.pollEveryCycles());
		this.url = "http://" + config.ip() //
				+ "/solar_api/v1/GetStorageRealtimeData.cgi?Scope=Device&DeviceId=" + config.deviceId();

		this.controlMode = config.controlMode();
		this.writeDeadbandWatt = Math.max(0, config.writeDeadbandWatt());
		this.minWriteIntervalMillis = Math.max(1, config.minWriteIntervalSeconds()) * 1000L;
		this.allowGridCharging = config.allowGridCharging();
		this.gridChargeFlagSynced = false;

		// --- Static solver constraints (AllowedCharge/DischargePower, MaxApparentPower) ---
		// The OpenEMS Power solver (io.openems.edge.ess.core.power.v1.data.ConstraintUtil.
		// createGenericEssConstraints()) builds a "GREATER_OR_EQUALS AllowedChargePower" and a
		// "LESS_OR_EQUALS AllowedDischargePower" constraint for EVERY ManagedSymmetricEss on
		// EVERY cycle, using getAllowedChargePower().orElse(0) / getAllowedDischargePower()
		// .orElse(0) if the channel was never set. Without setting these channels, the solver
		// therefore mathematically forces this Ess's ActivePower to exactly 0 W on every cycle,
		// regardless of what any Controller (e.g. Balancing) actually requests via applyPower().
		// This is not telemetry - it is a fixed nameplate/config value - so it only needs to be
		// set once here; OpenEMS channels keep their last value until explicitly overwritten
		// again (see AbstractReadChannel.nextProcessImage()).
		// Anders als ALLOWED_DISCHARGE_POWER und MAX_APPARENT_POWER bietet
		// ManagedSymmetricEss fuer ALLOWED_CHARGE_POWER KEINE _setAllowedChargePower()
		// Komfortmethode (Inkonsistenz im OpenEMS-Interface selbst, siehe
		// io.openems.edge.ess.api.ManagedSymmetricEss) - daher direkt ueber den Channel.
		this.getAllowedChargePowerChannel().setNextValue(-Math.abs(config.batteryMaxChargePowerWatt()));
		this._setAllowedDischargePower(Math.abs(config.batteryMaxDischargePowerWatt()));
		this._setMaxApparentPower(Math.abs(config.maxApparentPowerVoltAmpere()));
		this.logInfo(this.log, "Solver-Grenzwerte gesetzt: AllowedChargePower="
				+ this.getAllowedChargePower().get() + " W, AllowedDischargePower="
				+ this.getAllowedDischargePower().get() + " W, MaxApparentPower="
				+ this.getMaxApparentPower().get() + " VA (aus Config, nicht aus Telemetrie).");

		if (!this.isEnabled()) {
			return;
		}

		this.httpBridge = this.httpBridgeFactory.get();
		final var cycleService = this.httpBridge.createService(this.httpBridgeCycleServiceDefinition);
		// Poll every N Cycles instead of every single Cycle - see
		// FroniusSmartMeterJsonImpl for the rationale ("Task is not queued twice").
		cycleService.subscribeJsonCycle(this.pollEveryCycles, this.url, this::processHttpResult);

		// --- Control path: separate background thread, NOT the BridgeHttp cycle ---
		// service above (that one is GET-only declarative JSON polling, unsuitable
		// for the imperative Digest-Auth POST handshake needed here; applyPower()
		// itself must stay fast/non-blocking per the ManagedSymmetricEss contract).
		if (this.controlMode != ControlMode.READ_ONLY) {
			this.controlClient = new FroniusControlClient(config.ip(), config.username(), config.password());
			this.controlExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
				var thread = new Thread(runnable, "Fronius.Ess.Control-" + this.id());
				thread.setDaemon(true);
				return thread;
			});
			this.controlExecutor.scheduleWithFixedDelay(this::runControlLoop, 5, 5, TimeUnit.SECONDS);
			this.logInfo(this.log,
					"Steuerungsmodus '" + this.controlMode.getName() + "' aktiv - schreibt als Benutzer '"
							+ config.username() + "' auf " + config.ip() + ". Inoffizielle/undokumentierte "
							+ "Fronius-API, siehe readme.adoc fuer Details und Sicherheitshinweise.");
		}
	}

	@Override
	@Deactivate
	protected void deactivate() {
		if (this.controlExecutor != null) {
			this.controlExecutor.shutdownNow();
			this.controlExecutor = null;
			var timeOfUseRestored = this.restoreTimeOfUseConfig();
			this.resetToAutomatic(timeOfUseRestored);
		}
		if (this.httpBridge != null) {
			this.httpBridgeFactory.unget(this.httpBridge);
			this.httpBridge = null;
		}
		super.deactivate();
	}

	private void processHttpResult(HttpResponse<JsonElement> result, Throwable error) {
		if (error != null || result == null) {
			this._setSlaveCommunicationFailed(true);
			this.logDebug(this.log, error != null ? error.getMessage() : "no result");
			return;
		}

		try {
			var body = getAsJsonObject(result.data());
			var data = getAsJsonObject(getAsJsonObject(body, "Body"), "Data");

			if (data == null || !data.has("Controller") || !data.get("Controller").isJsonObject()) {
				// Happens e.g. when the connected battery type is not supported by the
				// Solar API version - Fronius then returns "Body":{"Data":{}}.
				this._setSlaveCommunicationFailed(true);
				this.logDebug(this.log, "GetStorageRealtimeData.cgi returned no 'Controller' object - "
						+ "battery type possibly unsupported by this Solar API version, see readme.adoc");
				return;
			}
			var controller = data.getAsJsonObject("Controller");

			// --- State of Charge [%] ----------------------------------------------
			this._setSoc(roundToInt(getFloatOrNull(controller, "StateOfCharge_Relative")));

			// --- Capacity: Fronius already delivers Wh directly --------------------
			this._setCapacity(roundToInt(getFloatOrNull(controller, "Capacity_Maximum")));
			this._setDesignedCapacity(roundToInt(getFloatOrNull(controller, "DesignedCapacity")));

			// --- DC Voltage/Current at the battery terminals -----------------------
			var voltageDc = getFloatOrNull(controller, "Voltage_DC");
			var currentDc = getFloatOrNull(controller, "Current_DC");
			this._setDcVoltage(scaleToInt(voltageDc, 1000f));
			this._setDcCurrent(scaleToInt(currentDc, 1000f));

			// --- Active Power: derived from DC Voltage * DC Current ----------------
			// Fronius convention for Current_DC is "+ charging"; OpenEMS convention for
			// SymmetricEss.ACTIVE_POWER is "negative = charge, positive = discharge" -
			// so the sign has to be flipped. This is the DC power at the battery
			// terminals, i.e. it does not include inverter conversion losses and will
			// therefore differ slightly from the AC power at the grid connection point.
			Integer activePower;
			if (voltageDc != null && currentDc != null) {
				activePower = -Math.round(voltageDc * currentDc);
			} else {
				activePower = null;
			}
			this._setActivePower(activePower);
			// Accumulate ACTIVE_CHARGE_ENERGY / ACTIVE_DISCHARGE_ENERGY from the
			// ActivePower we just determined - see field Javadoc above for why this
			// cannot be left to the framework.
			this.calculateEnergy(activePower);

			// --- Cell health diagnostics --------------------------------------------
			this._setMinCellVoltage(scaleToInt(getFloatOrNull(controller, "Voltage_DC_Minimum_Cell"), 1000f));
			this._setMaxCellVoltage(scaleToInt(getFloatOrNull(controller, "Voltage_DC_Maximum_Cell"), 1000f));
			this._setMinCellTemperature(roundToInt(getFloatOrNull(controller, "Temperature_Cell_Minimum")));
			this._setMaxCellTemperature(roundToInt(getFloatOrNull(controller, "Temperature_Cell_Maximum")));

			// --- Status / diagnostics -----------------------------------------------
			var enable = getFloatOrNull(controller, "Enable");
			this._setBatteryEnabled(enable == null ? null : enable != 0f);
			this._setStatusBatteryCell(roundToInt(getFloatOrNull(controller, "Status_BatteryCell")));

			if (controller.has("Details") && controller.get("Details").isJsonObject()) {
				var details = controller.getAsJsonObject("Details");
				this._setBatteryManufacturer(getStringOrNull(details, "Manufacturer"));
				this._setBatteryModel(getStringOrNull(details, "Model"));
				this._setBatterySerial(getStringOrNull(details, "Serial"));
			}

			this._setSlaveCommunicationFailed(false);

		} catch (OpenemsNamedException e) {
			this._setSlaveCommunicationFailed(true);
			this.logDebug(this.log, e.getMessage());
		}
	}

	private static Integer scaleToInt(Float value, float factor) {
		return value == null ? null : Math.round(value * factor);
	}

	private static Integer roundToInt(Float value) {
		return value == null ? null : Math.round(value);
	}

	/**
	 * Reads a flat numeric member (not wrapped in {@code {Value,Unit}}, unlike
	 * {@code GetInverterRealtimeData.cgi}). Returns {@code null} if the member is
	 * missing or JSON-null - Fronius explicitly documents that "inactive
	 * channels are not included in the response" depending on battery/firmware.
	 *
	 * @param object the JSON object to read from
	 * @param member the member name to read
	 * @return the parsed value, or {@code null} if missing/JSON-null/unparsable
	 */
	private static Float getFloatOrNull(JsonObject object, String member) {
		if (object == null || !object.has(member) || object.get(member).isJsonNull()) {
			return null;
		}
		try {
			return object.get(member).getAsFloat();
		} catch (NumberFormatException | UnsupportedOperationException e) {
			return null;
		}
	}

	private static String getStringOrNull(JsonObject object, String member) {
		if (object == null || !object.has(member) || object.get(member).isJsonNull()) {
			return null;
		}
		try {
			return object.get(member).getAsString();
		} catch (UnsupportedOperationException e) {
			return null;
		}
	}

	// -------------------------------------------------------------------------
	// ManagedSymmetricEss
	// -------------------------------------------------------------------------

	@Override
	public Power getPower() {
		return this.power;
	}

	@Override
	public Timedata getTimedata() {
		return this.timedata;
	}

	/**
	 * Accumulates {@link SymmetricEss.ChannelId#ACTIVE_CHARGE_ENERGY} /
	 * {@link SymmetricEss.ChannelId#ACTIVE_DISCHARGE_ENERGY} from the current
	 * ActivePower value, following the OpenEMS sign convention "negative =
	 * charge, positive = discharge".
	 *
	 * @param activePower the current ActivePower in [W], or null if unknown
	 */
	private void calculateEnergy(Integer activePower) {
		if (activePower == null) {
			this.calculateChargeEnergy.update(null);
			this.calculateDischargeEnergy.update(null);
		} else if (activePower > 0) {
			// Discharge
			this.calculateChargeEnergy.update(0);
			this.calculateDischargeEnergy.update(activePower);
		} else {
			// Charge
			this.calculateChargeEnergy.update(activePower * -1);
			this.calculateDischargeEnergy.update(0);
		}
	}

	@Override
	public int getPowerPrecision() {
		// The Fronius Web-Config-API accepts arbitrary integer Watt values (no fixed
		// register step, unlike Modbus/SunSpec) - the battery/inverter's own control
		// loop may still round internally, but there is no documented granularity.
		return 1;
	}

	@Override
	public void applyPower(int activePower, int reactivePower) throws OpenemsNamedException {
		if (this.controlMode == ControlMode.READ_ONLY) {
			// Default/safe behaviour: never write anything - identical to a plain,
			// read-only SymmetricEss. (The Component should not even be usable as a
			// target for Ess-Controllers in this case, but if it is wired up anyway,
			// this guard prevents any write.)
			return;
		}
		// No "is a Controller actually active" guard here (an earlier version had
		// one, checking the SetActivePowerEquals/-GreaterOrEquals/-LessOrEquals
		// Write-Channels) - that check only detected Controllers using those
		// specific convenience methods (e.g. Controller.Ess.Balancing). Any
		// Controller calling PowerConstraint.apply()/addPowerConstraint() directly
		// instead (e.g. Controller.Ess.FixActivePower, Controller.Symmetric
		// .LimitActivePower, Controller.Ess.Peakshaving, ...) never touches those
		// Channels, so the guard silently dropped every write for them - see
		// readme.adoc for how this was found. Without any Controller managing this
		// Ess at all, the Solver's neutral default (0 W) is written as-is, which
		// simply means "do not charge/discharge" - a safe default, not a harmful
		// one, and the pre-existing schedule the user may have had before
		// activating this Component is separately protected by
		// backupTimeOfUseConfig()/restoreTimeOfUseConfig(), independent of this
		// method.
		if (reactivePower != 0) {
			this.logDebug(this.log, "Fronius unterstuetzt keine Blindleistungssteuerung ueber diese API - "
					+ "reactivePower (" + reactivePower + " var) wird ignoriert.");
		}
		// Just store the value - fast/non-blocking, as required by the
		// ManagedSymmetricEss#applyPower Javadoc. The actual (slow) HTTP write
		// happens asynchronously in runControlLoop() on the dedicated executor.
		this.desiredActivePower.set(activePower);
		this.hasDesiredActivePower.set(true);
	}

	// -------------------------------------------------------------------------
	// Control loop (background thread, only running when ControlMode != READ_ONLY)
	// -------------------------------------------------------------------------

	/**
	 * Runs periodically on {@link #controlExecutor}. Checks whether the most
	 * recently requested {@code activePower} setpoint differs enough from the
	 * last value actually written (deadband) and enough time has passed since
	 * the last write (rate limit) - and if so, performs the (slow) Digest-Auth
	 * HTTP write via {@link #controlClient}.
	 */
	private void runControlLoop() {
		if (this.controlClient == null) {
			return;
		}
		if (this.controlMode == ControlMode.SCHEDULE_BASED && !this.timeOfUseBackedUp.get()
				&& this.timeOfUseBackupBackoff.isDue()) {
			// Must happen BEFORE the first writeTimeOfUse() call ever overwrites the
			// device's schedule - otherwise we would back up our own rule instead of
			// whatever the user had configured beforehand.
			this.backupTimeOfUseConfig();
		}
		if (this.controlMode == ControlMode.SCHEDULE_BASED && !this.gridChargeFlagSynced
				&& this.gridChargeFlagBackoff.isDue()) {
			this.syncGridChargeFlag();
		}
		if (!this.hasDesiredActivePower.get()) {
			return;
		}
		var target = this.desiredActivePower.get();
		var now = System.currentTimeMillis();
		var isFirstWrite = this.lastWrittenActivePower == Integer.MIN_VALUE;
		var withinDeadband = !isFirstWrite && Math.abs(target - this.lastWrittenActivePower) < this.writeDeadbandWatt;
		var tooSoon = !isFirstWrite && (now - this.lastWriteTimeMillis) < this.minWriteIntervalMillis;
		if (withinDeadband || tooSoon) {
			return;
		}
		if (!this.applyPowerBackoff.isDue()) {
			// Voriger Versuch ist erst kuerzlich gescheitert - noch nicht erneut
			// versuchen, um ein moeglicherweise gerade gesperrtes Geraet nicht
			// weiter zu befeuern (siehe RetryBackoff).
			return;
		}
		try {
			String description;
			if (this.controlMode == ControlMode.SCHEDULE_BASED) {
				description = this.applyScheduleBased(target);
			} else {
				description = this.applyGridPowerTarget(target);
			}
			this.lastWrittenActivePower = target;
			this.lastWriteTimeMillis = now;
			this._setApplyPowerFailed(false);
			this._setLastControlAction(description);
			this.applyPowerBackoff.recordSuccess();
			var firmwareVersion = this.controlClient.getFirmwareVersion();
			if (firmwareVersion != null) {
				this._setFirmwareVersion(firmwareVersion);
			}
		} catch (Exception e) {
			this._setApplyPowerFailed(true);
			this._setLastControlAction("FEHLER: " + e.getMessage());
			this.logWarn(this.log, "Fronius-Steuerbefehl fehlgeschlagen: " + e.getMessage());
			this.applyPowerBackoff.recordFailure();
		}
	}

	/**
	 * SCHEDULE_BASED only: reads whatever Time-of-Use schedule is currently
	 * configured on the device and saves it to a local backup file, but only if
	 * no such backup file already exists. This mirrors the reference
	 * implementation's ({@code batcontrol}) {@code backup_time_of_use()} /
	 * {@code restore_time_of_use_config()} pair, which this bundle previously
	 * lacked entirely - meaning any Time-of-Use rules the user had configured
	 * manually before activating this Component were silently and permanently
	 * overwritten by {@link FroniusControlClient#writeTimeOfUse}, with no way to
	 * get them back. Retries on the next control loop tick if the read fails.
	 * The "only if it does not exist yet" check also makes this safe across
	 * Edge restarts: if a backup from an earlier run is still pending (Edge
	 * crashed before {@link #restoreTimeOfUseConfig()} could run on
	 * deactivate), we must not overwrite it with our own already-active rule.
	 */
	private void backupTimeOfUseConfig() {
		var backupFile = this.timeOfUseBackupFile();
		try {
			if (java.nio.file.Files.exists(backupFile)) {
				this.timeOfUseBackedUp.set(true);
				return;
			}
			var current = this.controlClient.getTimeOfUse();
			java.nio.file.Files.writeString(backupFile, current.toString());
			this.timeOfUseBackedUp.set(true);
			this.timeOfUseBackupBackoff.recordSuccess();
			this.logInfo(this.log,
					"Bestehende Fronius-Zeitsteuerung gesichert nach " + backupFile.toAbsolutePath() + " ("
							+ current.size() + " Regel(n)) - wird beim Deaktivieren dieser Komponente automatisch "
							+ "wiederhergestellt.");
		} catch (Exception e) {
			this.timeOfUseBackupBackoff.recordFailure();
			this.logWarn(this.log, "Konnte bestehende Fronius-Zeitsteuerung nicht sichern, versuche es mit "
					+ "steigendem Backoff erneut (bis dahin wird NICHTS geschrieben, um nichts unwiderruflich zu "
					+ "ueberschreiben): " + e.getMessage());
		}
	}

	/**
	 * SCHEDULE_BASED only: restores the Time-of-Use schedule saved by
	 * {@link #backupTimeOfUseConfig()}, then deletes the backup file - mirroring
	 * {@code restore_time_of_use_config()} in the reference implementation.
	 * Called from {@link #deactivate()} BEFORE {@link #resetToAutomatic()},
	 * which would otherwise just deactivate our own last rule instead of
	 * bringing back the user's original one(s). Best-effort: if reading/parsing
	 * the backup or writing it back fails, the backup file is deliberately left
	 * in place (not deleted) so nothing is lost and the user can restore it
	 * manually via the Fronius Webinterface.
	 *
	 * @return {@code true} if the user's original schedule was found and
	 *             successfully restored - in that case {@link #resetToAutomatic}
	 *             must NOT additionally write its own "DISCHARGE_MAX 0
	 *             (inactive)" fallback rule afterwards, or it would immediately
	 *             overwrite what was just restored. {@code false} if there was
	 *             no backup to restore (nothing to do) or the restore attempt
	 *             itself failed (fallback should still run in that case).
	 */
	private boolean restoreTimeOfUseConfig() {
		if (this.controlClient == null || this.controlMode != ControlMode.SCHEDULE_BASED) {
			return false;
		}
		var backupFile = this.timeOfUseBackupFile();
		if (!java.nio.file.Files.exists(backupFile)) {
			return false;
		}
		try {
			var json = java.nio.file.Files.readString(backupFile);
			var restored = com.google.gson.JsonParser.parseString(json).getAsJsonArray();
			this.controlClient.setTimeOfUse(restored);
			java.nio.file.Files.delete(backupFile);
			this.logInfo(this.log, "Urspruengliche Fronius-Zeitsteuerung wiederhergestellt (" + restored.size()
					+ " Regel(n)) und Backup-Datei geloescht.");
			return true;
		} catch (Exception e) {
			this.logWarn(this.log,
					"Konnte urspruengliche Fronius-Zeitsteuerung NICHT automatisch wiederherstellen: " + e.getMessage()
							+ " - Backup liegt weiterhin unveraendert in " + backupFile.toAbsolutePath()
							+ ", bitte manuell im Fronius Webinterface (Batterie / Zeitsteuerung) pruefen und die "
							+ "dort enthaltenen Regeln von Hand wiederherstellen.");
			return false;
		}
	}

	/**
	 * Path of the local backup file for this Component's Time-of-Use schedule
	 * backup - includes the Component-ID so multiple Fronius-Ess Components
	 * (e.g. Anlage 1 + Anlage 2) never collide on the same file.
	 *
	 * @return the backup file path
	 */
	private java.nio.file.Path timeOfUseBackupFile() {
		return java.nio.file.Path.of("fronius-ess-" + this.id() + "-timeofuse-backup.json");
	}

	/**
	 * SCHEDULE_BASED only: writes {@code HYB_EVU_CHARGEFROMGRID} once so that
	 * {@code CHARGE_MIN} either stays a pure PV-surplus cap (grid charging
	 * forbidden, {@code allowGridCharging == false} - e.g. for
	 * {@code Controller.Ess.GridOptimizedCharge} in summer) or may genuinely pull
	 * from the grid ({@code allowGridCharging == true} - e.g. for
	 * {@code Controller.Ess.Time-Of-Use-Tariff} / CHARGE_CONSUMPTION in winter).
	 * Retries on the next control loop tick if the write fails; does not touch
	 * {@link #hasDesiredActivePower} or the write-rate-limit state, since this is
	 * independent of the actual power setpoint.
	 */
	private void syncGridChargeFlag() {
		try {
			var settings = new JsonObject();
			settings.addProperty("HYB_EVU_CHARGEFROMGRID", this.allowGridCharging);
			this.controlClient.writeBatteryConfig(settings);
			this.gridChargeFlagSynced = true;
			this.gridChargeFlagBackoff.recordSuccess();
			this.logInfo(this.log, "HYB_EVU_CHARGEFROMGRID auf " + this.allowGridCharging + " gesetzt (Netzladen "
					+ (this.allowGridCharging ? "erlaubt" : "gesperrt - CHARGE_MIN wirkt nur als PV-Kappung") + ").");
		} catch (Exception e) {
			this.gridChargeFlagBackoff.recordFailure();
			this.logWarn(this.log,
					"Konnte HYB_EVU_CHARGEFROMGRID nicht setzen, versuche es mit steigendem Backoff erneut: "
							+ e.getMessage());
		}
	}

	/**
	 * SCHEDULE_BASED translation of an OpenEMS ActivePower setpoint (negative =
	 * charge, positive = discharge) into Fronius Time-of-Use rules, written via
	 * {@link FroniusControlClient#writeTimeOfUseForced(java.util.List)} (always
	 * deactivate-then-reactivate, always a fresh Digest-Auth nonce per request -
	 * see its Javadoc and readme.adoc for why).
	 *
	 * <p>
	 * Both the charge and discharge side are derived directly from
	 * {@code activePower} itself - the one value the Solver already resolved
	 * for this Cycle, already correctly respecting every Controller's
	 * constraints (provided any limiting Controller runs before any
	 * EQUALS-setpoint Controller in the Scheduler - see readme.adoc). Nothing
	 * else is consulted: not {@code Power#getMinPower()}/{@code #getMaxPower()},
	 * not a {@code ComponentManager} lookup into a specific Controller type, not
	 * a separate Config field on this Component - see readme.adoc for the
	 * earlier, rejected attempts and why.
	 *
	 * <p>
	 * Whichever direction is NOT currently desired is capped to 0 via
	 * {@code CHARGE_MAX}/{@code DISCHARGE_MAX} (a ceiling: the device still only
	 * charges/discharges as much as PV surplus/house load actually allow -
	 * writing a ceiling that happens to be unneeded right now is harmless). The
	 * charge side additionally *forces* a floor via {@code CHARGE_MIN} when
	 * charging is desired (the device charges AT LEAST this much, from the grid
	 * if necessary). The discharge side deliberately does NOT have a symmetric
	 * forced floor ({@code DISCHARGE_MIN}) - see the comment on the
	 * {@code activePower >= 50} branch below for why: combined with
	 * {@code Controller.Ess.Balancing} (a closed control loop reacting to the
	 * actual resulting grid flow), a forced discharge floor that overshoots
	 * actual house consumption was observed to cause a sustained
	 * charge/discharge oscillation on a live system - forcing charging does not
	 * have this problem, since overshooting a charge floor aligns with, rather
	 * than opposes, Balancing's own goal. This is an accepted, documented
	 * precision limitation for Controllers that want a genuinely forced
	 * discharge value (e.g. {@code Controller.Ess.FixActivePower}/{@code Cycle})
	 * - see readme.adoc.
	 *
	 * <p>
	 * The {@code CHARGE_MIN} floor gets rewritten every time {@code activePower}
	 * changes by more than {@code writeDeadbandWatt} (checked at most every 5 s
	 * in {@link #runControlLoop}), so a floor that is no longer wanted is
	 * corrected within a few seconds, not indefinitely - the same bounded lag
	 * inherent to the whole SCHEDULE_BASED mechanism (see readme.adoc "Bekannte
	 * Einschraenkungen"), not something introduced here.
	 *
	 * @param activePower the Solver-resolved ActivePower setpoint in [W]
	 *                        (negative = charge, positive = discharge)
	 * @return a human-readable description of the action taken, for
	 *         {@link #_setLastControlAction}
	 * @throws Exception on any communication, authentication or verification
	 *                        error
	 */
	private String applyScheduleBased(int activePower) throws Exception {
		var chargeCeilingWatt = Math.max(0, -activePower);
		var chargeMax = new FroniusControlClient.TimeOfUseRule("CHARGE_MAX", chargeCeilingWatt, true);
		if (activePower <= -50) {
			// Charging desired: forces at least this much charge power. Whether this can
			// actually reach into the grid (or is hard-capped by available PV surplus)
			// depends on the separately synced HYB_EVU_CHARGEFROMGRID flag - see
			// syncGridChargeFlag() / Config#allowGridCharging(). CHARGE_MAX is omitted
			// here (deliberately not "erzwungener Momentanwert" - the device may still
			// charge more than this floor from PV surplus, same as before).
			var chargeWatt = Math.abs(activePower);
			var chargeMin = new FroniusControlClient.TimeOfUseRule("CHARGE_MIN", chargeWatt, true);
			this.controlClient.writeTimeOfUseForced(java.util.List.of(chargeMin));
			return "SCHEDULE_BASED: CHARGE_MIN " + chargeWatt + " W (Netzladen "
					+ (this.allowGridCharging ? "erlaubt" : "gesperrt, reine PV-Kappung") + ")";
		} else if (activePower >= 50) {
			// Discharging desired: cap discharge at this ceiling - deliberately NOT
			// DISCHARGE_MIN (forced floor). An earlier version used DISCHARGE_MIN here,
			// which caused a real oscillation with Controller.Ess.Balancing: Balancing
			// is a CLOSED control loop that reacts to the actual resulting grid flow -
			// if a forced discharge floor overshoots actual house consumption (device
			// discharges more than needed, exporting the surplus), Balancing sees that
			// export on its NEXT cycle and corrects in the opposite direction (reduces
			// discharge, possibly even requests charging) - which can itself overshoot,
			// driving a sustained charge/discharge oscillation. CHARGE_MIN (forced
			// charge floor) does not have this problem: overshooting it (charging more
			// than the floor from PV surplus) aligns with, rather than opposes,
			// Balancing's own goal, so no corrective swing results. CHARGE_MAX 0 blocks
			// charging in parallel, since we are not currently asking to charge.
			var dischargeMax = new FroniusControlClient.TimeOfUseRule("DISCHARGE_MAX", activePower, true);
			this.controlClient.writeTimeOfUseForced(java.util.List.of(dischargeMax, chargeMax));
			return "SCHEDULE_BASED: DISCHARGE_MAX " + activePower + " W (Obergrenze, kein erzwungener Wert), "
					+ "CHARGE_MAX " + chargeCeilingWatt + " W";
		} else {
			// Near zero: no strong signal either way - cap both directions at 0
			// (ceilings, not forced values: harmless if actually unneeded).
			var dischargeMax = new FroniusControlClient.TimeOfUseRule("DISCHARGE_MAX", 0, true);
			this.controlClient.writeTimeOfUseForced(java.util.List.of(dischargeMax, chargeMax));
			return "SCHEDULE_BASED: DISCHARGE_MAX 0 W, CHARGE_MAX " + chargeCeilingWatt + " W";
		}
	}

	/**
	 * GRID_POWER_TARGET translation: passes {@code activePower} straight through
	 * as {@code HYB_EM_POWER}, i.e. as a target power AT THE GRID CONNECTION
	 * POINT for the whole system (PV+Battery+Grid), NOT as battery power. This
	 * deliberately reinterprets the setpoint's meaning - see readme.adoc.
	 *
	 * @param activePower the Solver-resolved ActivePower setpoint in [W],
	 *                        written through 1:1 as the grid target
	 * @return a human-readable description of the action taken, for
	 *         {@link #_setLastControlAction}
	 * @throws Exception on any communication, authentication or verification
	 *                        error
	 */
	private String applyGridPowerTarget(int activePower) throws Exception {
		var settings = new JsonObject();
		settings.addProperty("HYB_EM_MODE", 1); // 1 = manual/adjustable, 0 = automatic
		settings.addProperty("HYB_EVU_CHARGEFROMGRID", true); // allow the manual target to draw from grid if needed
		settings.addProperty("HYB_EM_POWER", activePower);
		this.controlClient.writeBatteryConfig(settings);
		return "GRID_POWER_TARGET: HYB_EM_POWER " + activePower + " W (Netz-Sollwert, NICHT Batterieleistung!)";
	}

	/**
	 * Best-effort: on shutdown, hand control back to the device's own automatic
	 * logic (deactivate any Time-of-Use rule we wrote / reset HYB_EM_MODE to
	 * automatic) - so a stopped/removed OpenEMS Component does not leave the
	 * battery stuck in a manual state. Errors are only logged, never thrown,
	 * since this runs during deactivate().
	 *
	 * @param timeOfUseAlreadyRestored if {@code true} (see
	 *                                     {@link #restoreTimeOfUseConfig()}),
	 *                                     the user's original schedule was just
	 *                                     written back - the SCHEDULE_BASED
	 *                                     branch below must then be skipped, or
	 *                                     it would immediately overwrite that
	 *                                     restored schedule with our own
	 *                                     "DISCHARGE_MAX 0 (inactive)" rule again
	 */
	private void resetToAutomatic(boolean timeOfUseAlreadyRestored) {
		if (this.controlClient == null) {
			return;
		}
		try {
			if (this.controlMode == ControlMode.SCHEDULE_BASED) {
				if (!timeOfUseAlreadyRestored) {
					this.controlClient.writeTimeOfUse("DISCHARGE_MAX", 0, false);
				}
			} else if (this.controlMode == ControlMode.GRID_POWER_TARGET) {
				var settings = new JsonObject();
				settings.addProperty("HYB_EM_MODE", 0);
				this.controlClient.writeBatteryConfig(settings);
			}
		} catch (Exception e) {
			this.logWarn(this.log, "Konnte Fronius beim Beenden nicht auf automatischen Modus zuruecksetzen: "
					+ e.getMessage() + " - bitte im Fronius Webinterface (Batterie-Einstellungen / Zeitsteuerung) "
					+ "pruefen und ggf. manuell zuruecksetzen.");
		}
	}

	@Override
	public String debugLog() {
		return "SoC:" + this.getSoc().asString() + "|P:" + this.getActivePower().asString();
	}

	/**
	 * Simple exponential backoff, used to throttle retries of a specific
	 * Fronius write/read path across control-loop ticks. Without this, a
	 * persistently failing endpoint would be re-attempted every single tick
	 * (default every 5 s) forever - which, given Fronius' own undocumented
	 * behaviour of temporarily locking out authentication after repeated
	 * failed logins (observed in practice; also reported upstream at
	 * {@code https://github.com/muexxl/batcontrol/issues/125}), risks
	 * triggering or prolonging exactly that lockout instead of giving the
	 * device a chance to recover. Starts at the control loop's own tick
	 * interval and doubles on every consecutive failure, capped at
	 * {@value #MAX_MILLIS} ms (5 min, matching the known lockout window).
	 * Resets to the initial delay as soon as the operation succeeds again.
	 */
	private static final class RetryBackoff {
		private static final long INITIAL_MILLIS = 5_000L;
		private static final long MAX_MILLIS = 300_000L;

		private long nextAttemptAtMillis = 0L;
		private long currentDelayMillis = INITIAL_MILLIS;

		/**
		 * Checks whether enough time has passed since the last failure to retry now.
		 *
		 * @return {@code true} if a retry may be attempted now
		 */
		boolean isDue() {
			return System.currentTimeMillis() >= this.nextAttemptAtMillis;
		}

		/** Call after a failed attempt - schedules the next allowed retry and doubles the delay. */
		void recordFailure() {
			this.nextAttemptAtMillis = System.currentTimeMillis() + this.currentDelayMillis;
			this.currentDelayMillis = Math.min(this.currentDelayMillis * 2, MAX_MILLIS);
		}

		/** Call after a successful attempt - resets the backoff back to its initial delay. */
		void recordSuccess() {
			this.nextAttemptAtMillis = 0L;
			this.currentDelayMillis = INITIAL_MILLIS;
		}
	}
}
