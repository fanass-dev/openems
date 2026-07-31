package io.openems.edge.pvinverter.fronius.json;

import static io.openems.common.utils.JsonUtils.getAsFloat;
import static io.openems.common.utils.JsonUtils.getAsJsonObject;
import static org.osgi.service.component.annotations.ConfigurationPolicy.REQUIRE;

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
import io.openems.common.types.MeterType;
import io.openems.edge.bridge.http.cycle.HttpBridgeCycleServiceDefinition;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.timedata.api.Timedata;
import io.openems.edge.timedata.api.TimedataProvider;
import io.openems.edge.timedata.api.utils.CalculateEnergyFromPower;

/**
 * Reads a single Fronius PV-Inverter - GEN24 or Symo - via the Fronius Solar
 * API v1 over plain JSON/HTTP, i.e. without Modbus/SunSpec. Two endpoints are
 * polled:
 *
 * <ul>
 * <li>{@code GetInverterRealtimeData.cgi}, {@code DataCollection=
 * CommonInverterData} - for Voltage/Current/Frequency and the cumulative
 * energy counters ({@code TOTAL_ENERGY}/{@code DAY_ENERGY}/
 * {@code YEAR_ENERGY}). {@code TOTAL_ENERGY} is kept only as the diagnostic
 * {@code FRONIUS_TOTAL_ENERGY} Channel - see below for why it is NOT used for
 * {@code ACTIVE_PRODUCTION_ENERGY}.
 * <li>{@code GetPowerFlowRealtimeData.fcgi} - for {@code ActivePower}, read
 * from {@code Body.Data.Site.P_PV}, which is also integrated locally into
 * {@code ACTIVE_PRODUCTION_ENERGY} (see {@link #calculateProductionEnergy}).
 * </ul>
 *
 * <p>
 * <b>Why not just use {@code PAC} from {@code CommonInverterData} for
 * ActivePower, like an earlier version of this Component did?</b> On a
 * hybrid inverter (GEN24 + battery) {@code PAC} is the inverter's <i>AC
 * output</i> at the grid connection point, which nets out any DC-side battery
 * charging - e.g. 3000 W PV yield with the battery charging at 2500 W DC
 * shows up as {@code PAC} &asymp; 500 W, not 3000 W. {@code Site.P_PV} from
 * {@code GetPowerFlowRealtimeData.fcgi} is the true DC-side PV generation,
 * independent of what the battery is doing - this is the correct basis for
 * OpenEMS's {@code SymmetricEss}/{@code ElectricityMeter} energy-flow model,
 * where {@code ConsumptionActivePower = Production + Ess + Grid} expects
 * {@code Production} to be the actual PV yield (see {@code SumImpl.java}) and
 * the battery's own contribution to already show up separately via the ESS
 * Component's {@code ActivePower}. Confirmed against real GEN24-with-battery
 * API output shared in the community, e.g.
 * https://forum.pvoutput.org/t/fronius-gen24-with-battery-api-values/7790
 * ("p = ... AC Output" vs. "P_PV = ... generation (DC Side)"). On a plain
 * (non-hybrid) Symo without a battery, {@code P_PV} and {@code PAC} are
 * numerically identical anyway, so switching does not change anything there.
 *
 * <p>
 * It is a per-device (well, per-Datamanager - each Anlage here has only one
 * inverter, so this is equivalent) endpoint identical on GEN24 and Symo. This
 * means the very same Component can be used for both installations: create
 * two Component instances (two different Component-IDs), one pointing at the
 * GEN24-IP for Anlage 1 and one at the Symo-IP for Anlage 2.
 *
 * <p>
 * Test the endpoints in your browser first, e.g.:
 * {@code http://<inverter-ip>/solar_api/v1/GetInverterRealtimeData.cgi?Scope=Device&DeviceId=1&DataCollection=CommonInverterData}
 * and
 * {@code http://<inverter-ip>/solar_api/v1/GetPowerFlowRealtimeData.fcgi}
 *
 * <p>
 * Known Fronius quirk: on GEN24 devices {@code DAY_ENERGY} and
 * {@code YEAR_ENERGY} always come back as JSON {@code null} - only
 * {@code TOTAL_ENERGY} is reliable there. On a Symo both counters are
 * normally populated. This Component handles {@code null} gracefully for
 * every field.
 *
 * <p>
 * <b>Why {@code ACTIVE_PRODUCTION_ENERGY} is integrated locally instead of
 * using {@code TOTAL_ENERGY} directly, unlike an earlier version of this
 * Component:</b> {@code TOTAL_ENERGY} is Fronius' own AC-side lifetime output
 * counter from {@code CommonInverterData} - the very same counter family as
 * {@code PAC}, which (see above) nets out DC-side battery charging on a
 * hybrid inverter. A day with significant battery charging therefore made
 * {@code TOTAL_ENERGY} undercount true PV production by roughly the charged
 * amount, which in turn made {@code Core.Sum}'s derived
 * {@code _sum/ConsumptionActiveEnergy} come out implausibly low (confirmed in
 * practice: it dropped below the sum of individually metered loads on a day
 * with several kWh of battery charging). {@link #calculateProductionEnergy}
 * instead integrates {@code ACTIVE_PRODUCTION_ENERGY} from the
 * already-corrected {@code ActivePower} ({@code Site.P_PV}), following the
 * same pattern the sibling {@code io.openems.edge.ess.fronius.json} bundle
 * already uses for its charge/discharge energy. The raw Fronius counter
 * remains available for comparison via the diagnostic
 * {@code FRONIUS_TOTAL_ENERGY} Channel.
 *
 * <p>
 * This is implemented as a read-only {@link ElectricityMeter} with
 * {@link MeterType#PRODUCTION} (not as a controllable/curtailable
 * {@code ManagedSymmetricPvInverter}), since the requirement is monitoring
 * only.
 *
 * <p>
 * Pattern (BridgeHttp / subscribeJsonCycle) copied from
 * {@code io.openems.edge.meter.fronius.smartmeter.json.FroniusSmartMeterJsonImpl}
 * / {@code io.openems.edge.io.shelly.shellyplus1pm.IoShellyPlus1PmImpl}.
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "PvInverter.Fronius.Json", //
		immediate = true, //
		configurationPolicy = REQUIRE //
)
public class FroniusPvInverterJsonImpl extends AbstractOpenemsComponent
		implements FroniusPvInverterJson, ElectricityMeter, OpenemsComponent, TimedataProvider {

	private final Logger log = LoggerFactory.getLogger(FroniusPvInverterJsonImpl.class);

	private String url;
	private String powerFlowUrl;
	private int pollEveryCycles = 3;
	private MeterType type = MeterType.PRODUCTION;

	@Reference
	private BridgeHttpFactory httpBridgeFactory;
	@Reference
	private HttpBridgeCycleServiceDefinition httpBridgeCycleServiceDefinition;
	private BridgeHttp httpBridge;

	// Optional Timedata reference so CalculateEnergyFromPower can look up the
	// last known cumulative value across an Edge restart instead of always
	// starting the accumulation over at zero.
	@Reference(policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY, //
			cardinality = ReferenceCardinality.OPTIONAL)
	private volatile Timedata timedata = null;

	// ACTIVE_PRODUCTION_ENERGY is NOT read from Fronius' own TOTAL_ENERGY
	// counter (see class Javadoc for why) - it is integrated here from the
	// already-corrected ActivePower (Site.P_PV) instead, mirroring the pattern
	// used by the sibling Ess bundle (FroniusEssJsonImpl#calculateChargeEnergy).
	private final CalculateEnergyFromPower calculateProductionEnergy = new CalculateEnergyFromPower(this,
			ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY);

	public FroniusPvInverterJsonImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				FroniusPvInverterJson.ChannelId.values() //
		);
		// This is a PV-Inverter reading only site values - Fronius only ever reports
		// generation (sum) values, so it is fine to treat it as a symmetric meter and
		// derive L1/L2/L3 from the sum values instead of duplicating the logic below.
		ElectricityMeter.calculatePhasesFromActivePower(this);
		ElectricityMeter.calculatePhasesFromVoltage(this);
	}

	@Activate
	protected void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.pollEveryCycles = Math.max(1, config.pollEveryCycles());
		this.type = config.type();
		this.url = "http://" + config.ip() //
				+ "/solar_api/v1/GetInverterRealtimeData.cgi?Scope=Device&DeviceId=" + config.deviceId()
				+ "&DataCollection=CommonInverterData";
		// Only used for ActivePower (Site.P_PV) - see class Javadoc for why PAC
		// from CommonInverterData above is NOT usable for that on a hybrid
		// inverter with a DC-coupled battery.
		this.powerFlowUrl = "http://" + config.ip() + "/solar_api/v1/GetPowerFlowRealtimeData.fcgi";

		if (!this.isEnabled()) {
			return;
		}

		this.httpBridge = this.httpBridgeFactory.get();
		final var cycleService = this.httpBridge.createService(this.httpBridgeCycleServiceDefinition);
		// Poll every N Cycles instead of every single Cycle - see
		// FroniusSmartMeterJsonImpl for the rationale ("Task is not queued twice").
		cycleService.subscribeJsonCycle(this.pollEveryCycles, this.url, this::processHttpResult);
		cycleService.subscribeJsonCycle(this.pollEveryCycles, this.powerFlowUrl, this::processPowerFlowResult);
	}

	@Override
	@Deactivate
	protected void deactivate() {
		if (this.httpBridge != null) {
			this.httpBridgeFactory.unget(this.httpBridge);
			this.httpBridge = null;
		}
		super.deactivate();
	}

	@Override
	public Timedata getTimedata() {
		return this.timedata;
	}

	@Override
	public MeterType getMeterType() {
		// Read from the persisted Config attribute (see Config#type()), NOT a
		// hardcoded Java constant: the OpenEMS UI's isProducer()/hasProducer() logic
		// (ui/src/app/shared/edge/edgeconfig.ts) determines the Energiemonitor
		// "Produktion"-widget from the EdgeConfig-transmitted Component property
		// "type", which is only reliably populated from a real Metatype Config
		// attribute - a value only ever returned at runtime from this method call
		// (without a matching Config attribute) is invisible to the UI's widget
		// detection, even though Controller.Debug.Log/Core.Sum see it correctly.
		return this.type;
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

			// Active Power is NOT taken from PAC here - see processPowerFlowResult()
			// and the class Javadoc for why.

			// --- Voltage: Fronius delivers Volt -> OpenEMS wants Millivolt -------
			this._setVoltage(scaleToInt(data, "UAC", 1000f));

			// --- Current: Fronius delivers Ampere -> OpenEMS wants Milliampere ---
			this._setCurrent(scaleToInt(data, "IAC", 1000f));

			// --- Frequency: Fronius delivers Hertz -> OpenEMS wants Millihertz ---
			this._setFrequency(scaleToInt(data, "FAC", 1000f));

			// --- Energy: Fronius already delivers absolute Wh counters -----------
			// TOTAL_ENERGY is the only counter that is reliable on GEN24 (DAY_ENERGY
			// and YEAR_ENERGY are always null there); on a Symo all three are
			// normally populated. TOTAL_ENERGY is kept only as the diagnostic
			// FRONIUS_TOTAL_ENERGY Channel, NOT as ACTIVE_PRODUCTION_ENERGY - see
			// class Javadoc / calculateProductionEnergy for why.
			this._setFroniusTotalEnergy(scaleToLong(data, "TOTAL_ENERGY"));
			this._setDayEnergy(scaleToLong(data, "DAY_ENERGY"));
			this._setYearEnergy(scaleToLong(data, "YEAR_ENERGY"));

			this._setSlaveCommunicationFailed(false);

		} catch (OpenemsNamedException e) {
			this._setSlaveCommunicationFailed(true);
			this.logDebug(this.log, e.getMessage());
		}
	}

	/**
	 * Handles the {@code GetPowerFlowRealtimeData.fcgi} response - the only
	 * field used from it is the flat (not nested {@code {"Value":...}}) member
	 * {@code Body.Data.Site.P_PV}, the true DC-side PV generation. See the class
	 * Javadoc for why this - and not {@code PAC} from {@code CommonInverterData}
	 * - is the correct source for {@code ActivePower} on a hybrid inverter with
	 * a DC-coupled battery. The same value also feeds
	 * {@link #calculateProductionEnergy} - see class Javadoc for why
	 * {@code ACTIVE_PRODUCTION_ENERGY} is integrated from it instead of using
	 * Fronius' own {@code TOTAL_ENERGY} counter.
	 *
	 * @param result the HTTP/JSON response, or {@code null} if the request failed
	 * @param error  the error, if the request failed; {@code null} otherwise
	 */
	private void processPowerFlowResult(HttpResponse<JsonElement> result, Throwable error) {
		Integer activePower = null;
		if (error != null || result == null) {
			// CommonInverterData above already flags SlaveCommunicationFailed for a
			// dead/offline inverter (e.g. at night) - avoid double-logging here.
			this.logDebug(this.log, error != null ? error.getMessage() : "no result");
		} else {
			try {
				var body = getAsJsonObject(result.data());
				var site = getAsJsonObject(getAsJsonObject(getAsJsonObject(body, "Body"), "Data"), "Site");
				activePower = scaleToIntFlat(site, "P_PV", 1f);
			} catch (OpenemsNamedException e) {
				this.logDebug(this.log, e.getMessage());
			}
		}
		this._setActivePower(activePower);
		// Called unconditionally (even with null on error/no-data) so the
		// integrator's internal duration tracking stays consistent - it simply
		// skips accumulating for this update if the power value is null.
		this.calculateProductionEnergy.update(activePower);
	}

	/**
	 * Reads a flat Fronius member (plain number, not nested in
	 * {@code {"Value":...}} like {@code CommonInverterData}) as Float,
	 * multiplies it by {@code factor} and rounds to Integer. Returns
	 * {@code null} if the member is missing or JSON-null (e.g. {@code P_PV} is
	 * {@code null} while the inverter is in nighttime standby).
	 *
	 * @param data   the parent JSON object
	 * @param member the member name to read
	 * @param factor the scaling factor to apply
	 * @return the scaled value, or {@code null} if missing/JSON-null
	 */
	private static Integer scaleToIntFlat(JsonObject data, String member, float factor) {
		if (data == null || !data.has(member) || data.get(member).isJsonNull()) {
			return null;
		}
		try {
			return Math.round(getAsFloat(data, member) * factor);
		} catch (OpenemsNamedException e) {
			return null;
		}
	}

	/**
	 * Reads a nested Fronius {@code {"Value": ..., "Unit": ...}} member as Float,
	 * multiplies it by {@code factor} and rounds to Integer. Returns
	 * {@code null} if the member is missing, not an object, or its
	 * {@code Value} is JSON-null (e.g. {@code DAY_ENERGY}/{@code YEAR_ENERGY} on
	 * a GEN24).
	 *
	 * @param data   the parent JSON object
	 * @param member the member name to read
	 * @param factor the scaling factor to apply
	 * @return the scaled value, or {@code null} if missing/JSON-null
	 */
	private static Integer scaleToInt(JsonObject data, String member, float factor) {
		var value = getValueOrNull(data, member);
		return value == null ? null : Math.round(value * factor);
	}

	private static Long scaleToLong(JsonObject data, String member) {
		var value = getValueOrNull(data, member);
		return value == null ? null : Math.round((double) value);
	}

	private static Float getValueOrNull(JsonObject data, String member) {
		if (data == null || !data.has(member) || !data.get(member).isJsonObject()) {
			return null;
		}
		var valueObject = data.getAsJsonObject(member);
		var value = valueObject.get("Value");
		if (value == null || value.isJsonNull()) {
			return null;
		}
		try {
			return value.getAsFloat();
		} catch (NumberFormatException | UnsupportedOperationException e) {
			return null;
		}
	}

	@Override
	public String debugLog() {
		return "P:" + this.getActivePower().asString();
	}
}
