package io.openems.edge.meter.fronius.smartmeter.json;

import static io.openems.common.utils.JsonUtils.getAsFloat;
import static io.openems.common.utils.JsonUtils.getAsJsonObject;
import static org.osgi.service.component.annotations.ConfigurationPolicy.REQUIRE;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
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

/**
 * Reads a Fronius Smart Meter - attached to / read out through a GEN24 - via
 * the Fronius Solar API v1 ({@code GetMeterRealtimeData.cgi}) over plain
 * JSON/HTTP, i.e. without using Modbus/SunSpec at all.
 *
 * <p>
 * Unlike {@code GetPowerFlowRealtimeData.fcgi} (which only gives the site
 * totals P_PV/P_Grid/P_Akku), this endpoint returns full per-phase data
 * measured by the Smart Meter itself: voltage, current, active/reactive
 * power per phase, frequency and cumulative energy counters - everything
 * needed to fill a real, asymmetric {@link ElectricityMeter}.
 *
 * <p>
 * Test the endpoint in your browser first, e.g.:
 * {@code http://<gen24-ip>/solar_api/v1/GetMeterRealtimeData.cgi?Scope=Device&DeviceId=0}
 *
 * <p>
 * Sign convention as delivered by Fronius matches OpenEMS
 * {@link MeterType#GRID}: positive = buy-from-grid, negative =
 * feed-to-grid. If your installation reports it the other way round, enable
 * {@code invert} in the config.
 *
 * <p>
 * Pattern (BridgeHttp / subscribeJsonEveryCycle) copied from
 * {@code io.openems.edge.io.shelly.shellyplus1pm.IoShellyPlus1PmImpl}.
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Meter.Fronius.SmartMeterJson", //
		immediate = true, //
		configurationPolicy = REQUIRE //
)
public class FroniusSmartMeterJsonImpl extends AbstractOpenemsComponent
		implements FroniusSmartMeterJson, ElectricityMeter, OpenemsComponent {

	private final Logger log = LoggerFactory.getLogger(FroniusSmartMeterJsonImpl.class);

	private MeterType meterType = MeterType.GRID;
	private boolean invert = false;
	private String url;
	private int pollEveryCycles = 3;

	@Reference
	private BridgeHttpFactory httpBridgeFactory;
	@Reference
	private HttpBridgeCycleServiceDefinition httpBridgeCycleServiceDefinition;
	private BridgeHttp httpBridge;

	public FroniusSmartMeterJsonImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				FroniusSmartMeterJson.ChannelId.values() //
		);
	}

	@Activate
	protected void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.meterType = config.type();
		this.invert = config.invert();
		this.pollEveryCycles = Math.max(1, config.pollEveryCycles());
		this.url = "http://" + config.ip() //
				+ "/solar_api/v1/GetMeterRealtimeData.cgi?Scope=Device&DeviceId=" + config.deviceId();

		if (!this.isEnabled()) {
			return;
		}

		this.httpBridge = this.httpBridgeFactory.get();
		final var cycleService = this.httpBridge.createService(this.httpBridgeCycleServiceDefinition);
		// Poll every N Cycles instead of every single Cycle: the Fronius GEN24 web
		// server can be slower to respond than the OpenEMS Cycle-Time (usually 1s),
		// in which case polling every Cycle only produces harmless but noisy
		// "Task is not queued twice" log messages without any additional benefit.
		cycleService.subscribeJsonCycle(this.pollEveryCycles, this.url, this::processHttpResult);
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
	public MeterType getMeterType() {
		return this.meterType;
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

			final float sign = this.invert ? -1f : 1f;

			// --- Active Power [W] ----------------------------------------------
			this._setActivePower(scaleToInt(data, "PowerReal_P_Sum", sign));
			this._setActivePowerL1(scaleToInt(data, "PowerReal_P_Phase_1", sign));
			this._setActivePowerL2(scaleToInt(data, "PowerReal_P_Phase_2", sign));
			this._setActivePowerL3(scaleToInt(data, "PowerReal_P_Phase_3", sign));

			// --- Reactive Power [var] -------------------------------------------
			this._setReactivePower(scaleToInt(data, "PowerReactive_Q_Sum", sign));
			this._setReactivePowerL1(scaleToInt(data, "PowerReactive_Q_Phase_1", sign));
			this._setReactivePowerL2(scaleToInt(data, "PowerReactive_Q_Phase_2", sign));
			this._setReactivePowerL3(scaleToInt(data, "PowerReactive_Q_Phase_3", sign));

			// --- Voltage: Fronius delivers Volt -> OpenEMS wants Millivolt ------
			var voltageL1 = scaleToInt(data, "Voltage_AC_Phase_1", 1000f);
			var voltageL2 = scaleToInt(data, "Voltage_AC_Phase_2", 1000f);
			var voltageL3 = scaleToInt(data, "Voltage_AC_Phase_3", 1000f);
			this._setVoltageL1(voltageL1);
			this._setVoltageL2(voltageL2);
			this._setVoltageL3(voltageL3);
			this._setVoltage(average(voltageL1, voltageL2, voltageL3));

			// --- Current: Fronius delivers Ampere -> OpenEMS wants Milliampere --
			this._setCurrentL1(scaleToInt(data, "Current_AC_Phase_1", 1000f * sign));
			this._setCurrentL2(scaleToInt(data, "Current_AC_Phase_2", 1000f * sign));
			this._setCurrentL3(scaleToInt(data, "Current_AC_Phase_3", 1000f * sign));
			this._setCurrent(scaleToInt(data, "Current_AC_Sum", 1000f * sign));

			// --- Frequency: Fronius delivers Hertz -> OpenEMS wants Millihertz --
			this._setFrequency(scaleToInt(data, "Frequency_Phase_Average", 1000f));

			// --- Energy: Fronius already delivers absolute Wh counters ---------
			// IMPORTANT: Fronius' own field names ("Consumed"/"Produced") do NOT map
			// 1:1 by name to OpenEMS' ActiveConsumptionEnergy/ActiveProductionEnergy!
			// OpenEMS defines these purely by ActivePower sign (see ElectricityMeter
			// Javadoc): ActiveProductionEnergy = integral over POSITIVE ActivePower,
			// ActiveConsumptionEnergy = integral over NEGATIVE ActivePower. For
			// MeterType.GRID, positive = buy-from-grid. Fronius' "Consumed" field is
			// the cumulative buy-from-grid energy (positive direction) -> must go to
			// ActiveProductionEnergy. Fronius' "Produced" field is the cumulative
			// feed-to-grid energy (negative direction) -> must go to
			// ActiveConsumptionEnergy. Mapping them the "obvious" way round (as an
			// earlier version of this bundle did) silently swaps Netzbezug and
			// Einspeisung in the OpenEMS UI/History, even though ActivePower (and
			// therefore the live view) stays correct. If "invert" is enabled (CT
			// wired the other way round), the buy/feed direction flips together
			// with ActivePower, so the two Fronius fields must swap accordingly.
			var gridBuyEnergy = scaleToLong(data, this.invert ? "EnergyReal_WAC_Sum_Produced" : "EnergyReal_WAC_Sum_Consumed");
			var gridFeedEnergy = scaleToLong(data, this.invert ? "EnergyReal_WAC_Sum_Consumed" : "EnergyReal_WAC_Sum_Produced");
			this._setActiveProductionEnergy(gridBuyEnergy);
			this._setActiveConsumptionEnergy(gridFeedEnergy);

			this._setSlaveCommunicationFailed(false);

		} catch (OpenemsNamedException e) {
			this._setSlaveCommunicationFailed(true);
			this.logDebug(this.log, e.getMessage());
		}
	}

	/**
	 * Reads a member as Float, multiplies it by {@code factor} and rounds to
	 * Integer. Returns {@code null} if the member is missing or JSON-null (which
	 * Fronius does for channels that are not applicable, e.g. on single-phase
	 * meters Phase_2/Phase_3 might be absent).
	 *
	 * @param data   the JSON object to read from
	 * @param member the member name to read
	 * @param factor the factor to multiply the read value with
	 * @return the scaled value, or {@code null} if the member is missing
	 */
	private static Integer scaleToInt(JsonObject data, String member, float factor) {
		var value = getFloatOrNull(data, member);
		return value == null ? null : Math.round(value * factor);
	}

	private static Long scaleToLong(JsonObject data, String member) {
		var value = getFloatOrNull(data, member);
		return value == null ? null : Math.round((double) value);
	}

	private static Float getFloatOrNull(JsonObject data, String member) {
		if (data == null || !data.has(member) || data.get(member).isJsonNull()) {
			return null;
		}
		try {
			return getAsFloat(data, member);
		} catch (OpenemsNamedException e) {
			return null;
		}
	}

	private static Integer average(Integer... values) {
		int sum = 0;
		int count = 0;
		for (var value : values) {
			if (value != null) {
				sum += value;
				count++;
			}
		}
		return count == 0 ? null : sum / count;
	}

	@Override
	public String debugLog() {
		// Buy/Feed added temporarily to make the Netzbezug/Einspeisung cumulative
		// energy counters directly visible in Controller.Debug.Log, so the
		// ACTIVE_PRODUCTION_ENERGY (= Netzbezug-kumuliert bei GRID) /
		// ACTIVE_CONSUMPTION_ENERGY (= Einspeisung-kumuliert bei GRID) mapping
		// can be sanity-checked cycle-by-cycle without waiting for the UI's
		// day-boundary History aggregation.
		return "L:" + this.getActivePower().asString() //
				+ "|Buy:" + this.getActiveProductionEnergy().asString() //
				+ "|Feed:" + this.getActiveConsumptionEnergy().asString();
	}
}
