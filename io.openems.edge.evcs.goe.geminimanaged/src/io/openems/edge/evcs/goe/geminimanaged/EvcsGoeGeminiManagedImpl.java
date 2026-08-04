package io.openems.edge.evcs.goe.geminimanaged;

import static io.openems.common.bridge.http.api.BridgeHttp.DEFAULT_CONNECT_TIMEOUT;
import static io.openems.common.bridge.http.api.BridgeHttp.DEFAULT_READ_TIMEOUT;
import static io.openems.common.bridge.http.api.HttpMethod.GET;
import static io.openems.common.utils.JsonUtils.parseToJsonObject;
import static java.util.Collections.emptyMap;

import java.net.UnknownHostException;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.propertytypes.EventTopics;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

import io.openems.common.bridge.http.api.BridgeHttp;
import io.openems.common.bridge.http.api.BridgeHttpFactory;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.types.MeterType;
import io.openems.common.utils.JsonUtils;
import io.openems.edge.bridge.http.cycle.HttpBridgeCycleService;
import io.openems.edge.bridge.http.cycle.HttpBridgeCycleServiceDefinition;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.evcs.api.AbstractManagedEvcsComponent;
import io.openems.edge.evcs.api.ChargingType;
import io.openems.edge.evcs.api.Evcs;
import io.openems.edge.evcs.api.EvcsPower;
import io.openems.edge.evcs.api.EvcsUtils;
import io.openems.edge.evcs.api.ManagedEvcs;
import io.openems.edge.evcs.api.Phases;
import io.openems.edge.evcs.api.Status;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.meter.api.PhaseRotation;

/**
 * Managed EVCS implementation for go-e Gemini charge points, using the
 * public go-e API v2 (https://github.com/goecharger/go-eCharger-API-v2,
 * endpoints {@code /api/status} and {@code /api/set}). Deliberately a
 * separate bundle from {@code io.openems.edge.evcs.goe} (which does not
 * implement {@link ManagedEvcs} for the Gemini line) - see readme.adoc for
 * why.
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Evcs.Goe.Gemini.Managed", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE, //
		EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE //
})
public class EvcsGoeGeminiManagedImpl extends AbstractManagedEvcsComponent
		implements EvcsGoeGeminiManaged, ManagedEvcs, Evcs, OpenemsComponent, EventHandler {

	private static final String STATUS_FILTER = "amp,car,err,nrg,alw,fwv";

	private final Logger log = LoggerFactory.getLogger(EvcsGoeGeminiManagedImpl.class);

	@Reference
	private EvcsPower evcsPower;

	@Reference
	private BridgeHttpFactory httpBridgeFactory;
	@Reference
	private HttpBridgeCycleServiceDefinition httpBridgeCycleServiceDefinition;
	private BridgeHttp httpBridge;
	private HttpBridgeCycleService cycleService;

	protected Config config;

	public EvcsGoeGeminiManagedImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				ManagedEvcs.ChannelId.values(), //
				Evcs.ChannelId.values(), //
				EvcsGoeGeminiManaged.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws UnknownHostException {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.config = config;

		this._setChargingType(ChargingType.AC);
		this._setPowerPrecision(230);
		this._setFixedMinimumHardwarePower(
				Math.round(config.minHwCurrent() / 1000f) * Evcs.DEFAULT_VOLTAGE * Phases.THREE_PHASE.getValue());
		this._setFixedMaximumHardwarePower(
				Math.round(config.maxHwCurrent() / 1000f) * Evcs.DEFAULT_VOLTAGE * Phases.THREE_PHASE.getValue());

		this.httpBridge = this.httpBridgeFactory.get();
		this.cycleService = this.httpBridge.createService(this.httpBridgeCycleServiceDefinition);
		this.cycleService.subscribeCycle(config.pollEveryCycles(), //
				new BridgeHttp.Endpoint(this.statusUrl(), GET, DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT, "",
						emptyMap()), //
				t -> this.handleStatusResponse(parseToJsonObject(t.data())), //
				t -> this._setChargingstationCommunicationFailed(true));
	}

	@Override
	@Deactivate
	protected void deactivate() {
		this.httpBridgeFactory.unget(this.httpBridge);
		this.httpBridge = null;
		super.deactivate();
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
		super.handleEvent(event);
	}

	private String statusUrl() {
		return "http://" + this.config.ip() + "/api/status?filter=" + STATUS_FILTER;
	}

	private void handleStatusResponse(JsonObject json) {
		if (json == null) {
			this._setChargingstationCommunicationFailed(true);
			return;
		}
		try {
			var carState = JsonUtils.getAsInt(json, "car");
			this._setGoeCarState(carState);
			this._setStatus(this.convertGoeStatus(carState));

			var errorCode = JsonUtils.getAsOptionalInt(json, "err").orElse(0);
			this._setGoeError(errorCode);

			this._setAllowedToCharge(JsonUtils.getAsOptionalBoolean(json, "alw").orElse(null));

			var currentAmpere = JsonUtils.getAsInt(json, "amp");
			this._setCurrUser(currentAmpere * 1000);

			var nrg = JsonUtils.getAsJsonArray(json, "nrg");
			this._setVoltageL1(JsonUtils.getAsInt(nrg, 0) * 1000);
			this._setVoltageL2(JsonUtils.getAsInt(nrg, 1) * 1000);
			this._setVoltageL3(JsonUtils.getAsInt(nrg, 2) * 1000);
			this._setCurrentL1(JsonUtils.getAsInt(nrg, 4) * 1000);
			this._setCurrentL2(JsonUtils.getAsInt(nrg, 5) * 1000);
			this._setCurrentL3(JsonUtils.getAsInt(nrg, 6) * 1000);
			// Unlike the legacy v1 API (where nrg[11] is in 0.1 kW steps), the v2 API
			// used here already reports total power in Watt directly.
			this._setActivePower(JsonUtils.getAsInt(nrg, 11));

			JsonUtils.getAsOptionalString(json, "fwv").ifPresent(this::_setFirmwareVersion);

			this._setChargingstationCommunicationFailed(false);
		} catch (Exception e) {
			this._setChargingstationCommunicationFailed(true);
			this.logDebug(this.log, "Konnte go-e Status nicht auswerten: " + e.getMessage());
		}
	}

	/**
	 * Maps the go-e API v2 {@code car} field to the OpenEMS {@link Status}.
	 *
	 * <p>
	 * 0=Unknown/Error, 1=Idle, 2=Charging, 3=WaitCar, 4=Complete, 5=Error,
	 * 6=Initializing - see
	 * https://github.com/goecharger/go-eCharger-API-v2/tree/main/API_KEYS_FIRMWARE
	 *
	 * @param carState the raw {@code car} value
	 * @return the mapped {@link Status}
	 */
	private Status convertGoeStatus(int carState) {
		return switch (carState) {
		case 1 -> Status.NOT_READY_FOR_CHARGING;
		case 2 -> Status.CHARGING;
		case 3 -> Status.READY_FOR_CHARGING;
		case 4 -> Status.CHARGING_REJECTED;
		case 6 -> Status.STARTING;
		case 0, 5 -> Status.ERROR;
		default -> Status.UNDEFINED;
		};
	}

	@Override
	public boolean applyChargePowerLimit(int power) throws OpenemsException {
		var phases = this.getPhasesAsInt();
		var current = EvcsUtils.wattToAmpere(power, phases == 0 ? 3 : phases);
		var minCurrent = this.config.minHwCurrent() / 1000;
		var maxCurrent = this.config.maxHwCurrent() / 1000;
		current = Math.max(minCurrent, Math.min(maxCurrent, current));
		this.sendSetRequest("amp=" + current + "&frc=2");
		return true;
	}

	@Override
	public boolean pauseChargeProcess() throws OpenemsException {
		this.sendSetRequest("frc=1");
		return true;
	}

	/**
	 * Sends a write command to {@code /api/set} asynchronously (fire-and-forget,
	 * via {@link BridgeHttp#request}) - deliberately not blocking, since this is
	 * called from the Cycle's write phase and a slow/unresponsive charge point
	 * must not stall the whole Cycle.
	 *
	 * @param queryParams the go-e API v2 query parameters, e.g. {@code "amp=16&frc=2"}
	 */
	private void sendSetRequest(String queryParams) {
		var url = "http://" + this.config.ip() + "/api/set?" + queryParams;
		var endpoint = new BridgeHttp.Endpoint(url, GET, DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT, "",
				emptyMap());
		this.logDebug(this.log, "go-e Schreibbefehl: " + queryParams);
		this.httpBridge.request(endpoint) //
				.thenAccept(response -> this._setLastWriteFailed(false)) //
				.exceptionally(e -> {
					this._setLastWriteFailed(true);
					this.logWarn(this.log, "go-e Schreibbefehl [" + queryParams + "] fehlgeschlagen: "
							+ e.getMessage());
					return null;
				});
	}

	@Override
	public boolean applyDisplayText(String text) throws OpenemsException {
		// go-e Gemini API v2 does not expose a settable display text field.
		return false;
	}

	@Override
	public int getMinimumTimeTillChargingLimitTaken() {
		return 30;
	}

	@Override
	public int getWriteInterval() {
		return this.config.writeIntervalSeconds();
	}

	@Override
	public PhaseRotation getPhaseRotation() {
		return PhaseRotation.L1_L2_L3;
	}

	@Override
	public MeterType getMeterType() {
		return this.config.readOnly() //
				? MeterType.CONSUMPTION_METERED //
				: MeterType.MANAGED_CONSUMPTION_METERED;
	}

	@Override
	public boolean isReadOnly() {
		return this.config.readOnly();
	}

	@Override
	public boolean getConfiguredDebugMode() {
		return this.config.debugMode();
	}

	@Override
	public int getConfiguredMinimumHardwarePower() {
		return Math.round(this.config.minHwCurrent() / 1000f) * Evcs.DEFAULT_VOLTAGE * Phases.THREE_PHASE.getValue();
	}

	@Override
	public int getConfiguredMaximumHardwarePower() {
		return Math.round(this.config.maxHwCurrent() / 1000f) * Evcs.DEFAULT_VOLTAGE * Phases.THREE_PHASE.getValue();
	}

	@Override
	public EvcsPower getEvcsPower() {
		return this.evcsPower;
	}

	@Override
	public void logDebug(String message) {
		if (this.config.debugMode()) {
			this.logInfo(this.log, message);
		}
	}
}
