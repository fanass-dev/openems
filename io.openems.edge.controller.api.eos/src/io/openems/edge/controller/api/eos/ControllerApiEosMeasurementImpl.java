package io.openems.edge.controller.api.eos;

import java.time.Instant;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.bridge.eos.BridgeEos;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.sum.Sum;
import io.openems.edge.controller.api.Controller;

/**
 * Reads the four cumulated Energy channels from {@link Sum} and pushes them
 * as Energy-Meter-Reading (EMR) measurements to an Akkudoktor-EOS system via
 * a referenced {@link BridgeEos} instance - see readme.adoc for why this is
 * deliberately split into a "dumb" bridge and this "intelligent" mapping
 * component.
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Controller.Api.Eos.Measurement", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class ControllerApiEosMeasurementImpl extends AbstractOpenemsComponent
		implements Controller, OpenemsComponent {

	private final Logger log = LoggerFactory.getLogger(ControllerApiEosMeasurementImpl.class);

	@Reference
	private Sum sum;

	@Reference
	private ComponentManager componentManager;

	@Reference
	private BridgeEos bridgeEos;

	@Reference
	private ConfigurationAdmin cm;

	private Config config;
	private int cyclesSinceLastPush = 0;

	public ControllerApiEosMeasurementImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				Controller.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.updateConfig(config);
	}

	@Modified
	private void modified(ComponentContext context, Config config) {
		super.modified(context, config.id(), config.alias(), config.enabled());
		this.updateConfig(config);
	}

	private void updateConfig(Config config) {
		this.config = config;
		// force an immediate push after activation/reconfiguration, instead of
		// waiting a full pushEveryCycles interval
		this.cyclesSinceLastPush = config.pushEveryCycles();
		if (OpenemsComponent.updateReferenceFilter(this.cm, this.servicePid(), "bridgeEos", config.bridgeEos_id())) {
			return;
		}
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public void run() throws OpenemsNamedException {
		this.cyclesSinceLastPush++;
		if (this.cyclesSinceLastPush < this.config.pushEveryCycles()) {
			return;
		}
		this.cyclesSinceLastPush = 0;

		var now = Instant.now(this.componentManager.getClock());

		// cumulated Energy-Meter-Reading keys - Wh_Sigma -> kWh
		this.pushKwh(this.config.pvProductionKey(), this.sum.getProductionActiveEnergy().get(), now);
		this.pushKwh(this.config.consumptionKey(), this.sum.getConsumptionActiveEnergy().get(), now);
		this.pushKwh(this.config.gridImportKey(), this.sum.getGridBuyActiveEnergy().get(), now);
		this.pushKwh(this.config.gridExportKey(), this.sum.getGridSellActiveEnergy().get(), now);

		// instantaneous battery power [W] - passed through as-is; note the sign
		// convention caveat in readme.adoc
		this.pushRaw(this.config.batteryPowerL1Key(), this.sum.getEssActivePowerL1().get(), now);
		this.pushRaw(this.config.batteryPowerL2Key(), this.sum.getEssActivePowerL2().get(), now);
		this.pushRaw(this.config.batteryPowerL3Key(), this.sum.getEssActivePowerL3().get(), now);
		this.pushRaw(this.config.batteryPowerSymKey(), this.sum.getEssActivePower().get(), now);

		// battery SoC - OpenEMS 0..100 % -> EOS factor 0.0..1.0
		var soc = this.sum.getEssSoc().get();
		if (soc != null) {
			this.pushValue(this.config.batterySocKey(), soc / 100.0, now);
		}
	}

	/**
	 * Pushes a single cumulated Wh_&Sigma; value to EOS, converted to kWh.
	 *
	 * @param key         the EOS measurement key
	 * @param cumulatedWh the cumulated value in Wh_&Sigma;, possibly null if not
	 *                    yet available
	 * @param now         the current point in time
	 */
	private void pushKwh(String key, Long cumulatedWh, Instant now) {
		if (cumulatedWh == null) {
			return;
		}
		this.pushValue(key, cumulatedWh / 1000.0, now);
	}

	/**
	 * Pushes a single value to EOS unconverted (e.g. Watt power values, which EOS
	 * expects as-is, unlike the kWh Energy-Meter-Reading keys).
	 *
	 * @param key   the EOS measurement key
	 * @param value the value, possibly null if not yet available
	 * @param now   the current point in time
	 */
	private void pushRaw(String key, Integer value, Instant now) {
		if (value == null) {
			return;
		}
		this.pushValue(key, value, now);
	}

	/**
	 * Pushes a single measurement value to EOS - fires asynchronously
	 * (fire-and-forget, like the other HTTP-based bundles in this installation);
	 * a failure only sets {@link BridgeEos.ChannelId#LAST_REQUEST_FAILED} (via the
	 * Bridge) and is logged here, the next scheduled push tries again with
	 * then-current values.
	 *
	 * @param key   the EOS measurement key
	 * @param value the value, already in the unit EOS expects for this key
	 * @param now   the current point in time
	 */
	private void pushValue(String key, double value, Instant now) {
		this.bridgeEos.putMeasurementValue(key, now, value).exceptionally(e -> {
			this.logWarn(this.log, "EOS-Push von '" + key + "' = " + value + " fehlgeschlagen: " + e.getMessage());
			return null;
		});
	}
}
