package io.openems.edge.pvinverter.hoymiles.opendtu;

import static org.osgi.service.component.annotations.ConfigurationPolicy.REQUIRE;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

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
import io.openems.common.bridge.http.api.BridgeHttp.Endpoint;
import io.openems.common.bridge.http.api.BridgeHttpFactory;
import io.openems.common.bridge.http.api.HttpError;
import io.openems.common.bridge.http.api.HttpResponse;
import io.openems.common.types.MeterType;
import io.openems.common.utils.JsonUtils;
import io.openems.edge.bridge.http.cycle.HttpBridgeCycleServiceDefinition;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.meter.api.ElectricityMeter;

/**
 * Reads one or all Hoymiles microinverter(s) behind an OpenDTU device (ESP32-
 * based open-source DTU replacement) via its JSON/HTTP web API - i.e. without
 * Modbus. A single endpoint is polled, {@code /api/livedata/status}, in one
 * of two modes depending on the {@code inverterSerial} Config attribute:
 *
 * <ul>
 * <li><b>Aggregate mode</b> ({@code inverterSerial} empty):
 * {@code /api/livedata/status} - the response's {@code total} object gives
 * the sum of Power/YieldDay/YieldTotal across ALL inverters connected to this
 * OpenDTU. No AC Voltage/Current/Frequency are available here - OpenDTU
 * simply does not report them at this aggregation level.
 * <li><b>Single-inverter mode</b> ({@code inverterSerial} set):
 * {@code /api/livedata/status?inv=<serial>} - in addition to the same
 * top-level structure, the response contains an {@code AC["0"]} object
 * (Power/Voltage/Current/Frequency for that one inverter) and an
 * {@code INV["0"]} object (Power DC/YieldDay/YieldTotal/Temperature/
 * Efficiency for that one inverter). Only phase/string index {@code "0"} is
 * read - on 3-phase Hoymiles models (e.g. HMT) additional AC phases would be
 * under {@code AC["1"]}/{@code AC["2"]}, which this Component does not
 * currently read (documented limitation).
 * </ul>
 *
 * <p>
 * Auth: by default, none of OpenDTU's read endpoints require authentication
 * ({@code /api/livedata/status} is public as long as OpenDTU's Settings ->
 * Security -> "Enable read-only access" stays enabled, which is the OpenDTU
 * default). If that setting is disabled, ALL endpoints - including this one
 * - require HTTP Basic-Auth (default credentials {@code admin} /
 * {@code openDTU42}, unless changed). The {@code username}/{@code password}
 * Config attributes are therefore optional and empty by default; if set,
 * a {@code Basic} {@code Authorization} header is added to every request.
 *
 * <p>
 * This is implemented as a read-only {@link ElectricityMeter} with
 * {@link MeterType#PRODUCTION} (not as a controllable/curtailable inverter),
 * since the requirement is monitoring only - same rationale as
 * {@code io.openems.edge.pvinverter.fronius.json.FroniusPvInverterJsonImpl}.
 *
 * <p>
 * All field mappings are taken from the official OpenDTU Web API
 * documentation: https://www.opendtu.solar/firmware/web_api/
 *
 * <p>
 * Pattern (BridgeHttp / BridgeHttpFactory / HttpBridgeCycleServiceDefinition)
 * copied from {@code FroniusPvInverterJsonImpl}. Unlike that Component, the
 * raw-String {@code subscribeCycle(...)} overload is used here (instead of
 * {@code subscribeJsonCycle(...)}) because JSON parsing is done manually
 * after adding the optional Basic-Auth header via a custom {@link Endpoint} -
 * the {@code HttpBridgeCycleService} interface only offers
 * {@code subscribeJsonCycle(...)} overloads that take a plain URL String,
 * with no way to attach custom headers.
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "PvInverter.Hoymiles.OpenDtu", //
		immediate = true, //
		configurationPolicy = REQUIRE //
)
public class OpenDtuPvInverterJsonImpl extends AbstractOpenemsComponent
		implements OpenDtuPvInverterJson, ElectricityMeter, OpenemsComponent {

	private final Logger log = LoggerFactory.getLogger(OpenDtuPvInverterJsonImpl.class);

	private int pollEveryCycles = 3;
	private MeterType type = MeterType.PRODUCTION;
	private String url;
	private boolean singleInverterMode;

	@Reference
	private BridgeHttpFactory httpBridgeFactory;
	@Reference
	private HttpBridgeCycleServiceDefinition httpBridgeCycleServiceDefinition;
	private BridgeHttp httpBridge;

	public OpenDtuPvInverterJsonImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				OpenDtuPvInverterJson.ChannelId.values() //
		);
		// OpenDTU/Hoymiles inverters only ever report a single, symmetric
		// sum/inverter value (no per-phase L1/L2/L3 split in the web API) - same
		// situation as the Fronius PV-Inverter bundle, so L1/L2/L3 are derived from
		// the sum values instead of duplicating the logic below.
		ElectricityMeter.calculatePhasesFromActivePower(this);
		ElectricityMeter.calculatePhasesFromVoltage(this);
	}

	@Activate
	protected void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.pollEveryCycles = Math.max(1, config.pollEveryCycles());
		this.type = config.type();
		this.singleInverterMode = config.inverterSerial() != null && !config.inverterSerial().isBlank();
		this.url = "http://" + config.ip() + "/api/livedata/status"
				+ (this.singleInverterMode ? "?inv=" + config.inverterSerial().trim() : "");

		if (!this.isEnabled()) {
			return;
		}

		this.httpBridge = this.httpBridgeFactory.get();
		final var cycleService = this.httpBridge.createService(this.httpBridgeCycleServiceDefinition);

		final var endpointBuilder = BridgeHttp.create(this.url);
		final var username = config.username();
		final var password = config.password();
		if (username != null && !username.isBlank()) {
			final var token = Base64.getEncoder()
					.encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
			endpointBuilder.setHeader("Authorization", "Basic " + token);
		}
		final var endpoint = endpointBuilder.build();

		// Poll every N Cycles instead of every single Cycle - see
		// FroniusPvInverterJsonImpl/FroniusSmartMeterJsonImpl for the rationale.
		cycleService.subscribeCycle(this.pollEveryCycles, endpoint, this::processHttpResult, this::processHttpError);
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
		// Read from the persisted Config attribute (see Config#type()), NOT a
		// hardcoded Java constant - identical rationale as in
		// FroniusPvInverterJsonImpl#getMeterType() (UI isProducer() detection).
		return this.type;
	}

	private void processHttpError(HttpError error) {
		this._setSlaveCommunicationFailed(true);
		this.logDebug(this.log, error != null ? error.getMessage() : "no result");
	}

	private void processHttpResult(HttpResponse<String> result) {
		JsonElement root;
		try {
			root = JsonUtils.parse(result.data());
		} catch (Exception e) {
			this._setSlaveCommunicationFailed(true);
			this.logDebug(this.log, "Invalid JSON from OpenDTU: " + e.getMessage());
			return;
		}

		// --- Diagnostic hints (present in both aggregate and single-inverter mode) --
		this._setRadioProblem(getBool(root, "hints", "radio_problem").orElse(false));
		this._setDefaultPasswordActive(getBool(root, "hints", "default_password").orElse(false));

		if (this.singleInverterMode) {
			this.processSingleInverter(root);
		} else {
			this.processAggregate(root);
		}
	}

	/**
	 * Aggregate mode: sum of ALL inverters connected to this OpenDTU, from the
	 * response's {@code total} object. No AC Voltage/Current/Frequency are
	 * available at this level.
	 *
	 * @param root the root {@link JsonElement} of the OpenDTU status response
	 */
	private void processAggregate(JsonElement root) {
		var power = getFloat(root, "total", "Power", "v");
		if (power.isEmpty()) {
			// "total" missing entirely -> OpenDTU did not return the expected
			// structure (e.g. empty/garbled response).
			this._setSlaveCommunicationFailed(true);
			this.logDebug(this.log, "OpenDTU response has no 'total' object");
			return;
		}
		this._setActivePower(toIntRounded(power, 1f));
		this._setDayEnergy(toLongRounded(getFloat(root, "total", "YieldDay", "v"), 1f));
		// YieldTotal is in kWh -> OpenEMS ACTIVE_PRODUCTION_ENERGY wants Wh.
		this._setActiveProductionEnergy(toLongRounded(getFloat(root, "total", "YieldTotal", "v"), 1000f));
		// Not reported by OpenDTU in aggregate mode.
		this._setVoltage(null);
		this._setCurrent(null);
		this._setFrequency(null);
		this._setSlaveCommunicationFailed(false);
	}

	/**
	 * Single-inverter mode ({@code ?inv=<serial>}): AC electrical values from
	 * {@code AC["0"]}, energy counters from {@code INV["0"]} (the per-inverter
	 * totals, as opposed to the per-DC-string counters under {@code DC["0"]}
	 * .. {@code DC["3"]} which this Component does not read). Only phase/string
	 * index {@code "0"} is read - see class Javadoc.
	 *
	 * @param root the root {@link JsonElement} of the OpenDTU status response
	 */
	private void processSingleInverter(JsonElement root) {
		var acPower = getFloat(root, "AC", "0", "Power", "v");
		if (acPower.isEmpty() && getObj(root, "INV", "0").isEmpty()) {
			// Neither AC nor INV present -> the configured serial was very likely not
			// found among this OpenDTU's connected inverters (typo, wrong device, or
			// the inverter has never been seen since OpenDTU last booted).
			this._setSlaveCommunicationFailed(true);
			this.logDebug(this.log, "OpenDTU response has no AC/INV data for the configured Inverter-Serial - "
					+ "check that the serial number is correct and the inverter has been reachable at least once");
			return;
		}
		this._setActivePower(toIntRounded(acPower, 1f));
		this._setVoltage(toIntRounded(getFloat(root, "AC", "0", "Voltage", "v"), 1000f));
		this._setCurrent(toIntRounded(getFloat(root, "AC", "0", "Current", "v"), 1000f));
		this._setFrequency(toIntRounded(getFloat(root, "AC", "0", "Frequency", "v"), 1000f));
		this._setDayEnergy(toLongRounded(getFloat(root, "INV", "0", "YieldDay", "v"), 1f));
		// YieldTotal is in kWh -> OpenEMS ACTIVE_PRODUCTION_ENERGY wants Wh.
		this._setActiveProductionEnergy(toLongRounded(getFloat(root, "INV", "0", "YieldTotal", "v"), 1000f));
		this._setSlaveCommunicationFailed(false);
	}

	/**
	 * Walks a chain of nested JSON objects, returning the last JsonObject on the
	 * path (or the root itself if it already is one and {@code path} is empty).
	 * Returns {@link Optional#empty()} at any point where a member is missing,
	 * not an object, or JSON-null - never throws.
	 *
	 * @param root the root {@link JsonElement} to start walking from
	 * @param path the chain of member names to walk down
	 * @return the resolved {@link JsonObject}, or {@link Optional#empty()}
	 */
	private static Optional<JsonObject> getObj(JsonElement root, String... path) {
		var current = JsonUtils.getAsOptionalJsonObject(root);
		for (final var member : path) {
			current = current.flatMap(o -> JsonUtils.getAsOptionalJsonObject(o, member));
		}
		return current;
	}

	/**
	 * Walks a chain of nested JSON objects and reads the final member as an
	 * {@link Optional} {@link Float}. E.g.
	 * {@code getFloat(root, "total", "Power", "v")} reads
	 * {@code root.total.Power.v}. Returns {@link Optional#empty()} at any point
	 * where a member is missing, not an object (except the last step), or
	 * JSON-null - never throws.
	 *
	 * @param root the root {@link JsonElement} to start walking from
	 * @param path the chain of member names to walk down, e.g.
	 *             {@code "total", "Power", "v"}
	 * @return the resolved value, or {@link Optional#empty()}
	 */
	private static Optional<Float> getFloat(JsonElement root, String... path) {
		if (path.length == 0) {
			return Optional.empty();
		}
		final var lastMember = path[path.length - 1];
		final var parentPath = java.util.Arrays.copyOf(path, path.length - 1);
		return getObj(root, parentPath).flatMap(o -> JsonUtils.getAsOptionalFloat(o, lastMember));
	}

	/**
	 * Same as {@link #getFloat}, but for a Boolean leaf member.
	 *
	 * @param root the root {@link JsonElement} to start walking from
	 * @param path the chain of member names to walk down
	 * @return the resolved value, or {@link Optional#empty()}
	 */
	private static Optional<Boolean> getBool(JsonElement root, String... path) {
		if (path.length == 0) {
			return Optional.empty();
		}
		final var lastMember = path[path.length - 1];
		final var parentPath = java.util.Arrays.copyOf(path, path.length - 1);
		return getObj(root, parentPath).flatMap(o -> JsonUtils.getAsOptionalBoolean(o, lastMember));
	}

	private static Integer toIntRounded(Optional<Float> value, float factor) {
		return value.map(v -> Math.round(v * factor)).orElse(null);
	}

	private static Long toLongRounded(Optional<Float> value, float factor) {
		return value.map(v -> Math.round((double) (v * factor))).orElse(null);
	}

	@Override
	public String debugLog() {
		return "P:" + this.getActivePower().asString();
	}
}
