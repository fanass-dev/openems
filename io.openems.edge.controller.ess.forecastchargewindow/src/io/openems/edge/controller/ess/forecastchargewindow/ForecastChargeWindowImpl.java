package io.openems.edge.controller.ess.forecastchargewindow;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonPrimitive;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.jsonrpc.request.UpdateComponentConfigRequest.Property;
import io.openems.common.jsonrpc.type.UpdateComponentConfig;
import io.openems.common.types.ChannelAddress;
import io.openems.common.utils.DateUtils;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;
import io.openems.edge.predictor.api.manager.PredictorManager;
import io.openems.edge.timeofusetariff.api.TariffManager;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Controller.Ess.ForecastChargeWindow", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class ForecastChargeWindowImpl extends AbstractOpenemsComponent
		implements ForecastChargeWindow, Controller, OpenemsComponent {

	private static final LocalTime DEFAULT_AFTERNOON_WINDOW_START = LocalTime.of(12, 0);
	private static final LocalTime DEFAULT_CHECK_TIME = LocalTime.of(8, 0);

	private final Logger log = LoggerFactory.getLogger(ForecastChargeWindowImpl.class);

	@Reference
	private ComponentManager componentManager;

	@Reference
	private PredictorManager predictorManager;

	@Reference
	private TariffManager tariffManager;

	private Config config;
	private ChannelAddress productionChannelAddress;
	private LocalTime afternoonWindowStart;
	private LocalTime checkTime;

	private LocalDate lastProcessedDate = null;
	private boolean forecastCheckDoneToday = false;
	private boolean forecastLiftedToday = false;

	/**
	 * The block state last successfully written to the target Controller - null
	 * until the first successful write, so an Edge restart always re-applies the
	 * currently correct state instead of assuming it is already in place.
	 */
	private Boolean lastAppliedUnblocked = null;

	public ForecastChargeWindowImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				Controller.ChannelId.values(), //
				ForecastChargeWindow.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.config = config;
		this.productionChannelAddress = new ChannelAddress("_sum", config.productionChannelId());
		this.afternoonWindowStart = this.parseTimeOrFallback(config.afternoonWindowStart(),
				DEFAULT_AFTERNOON_WINDOW_START, "Beginn Nachmittagsfenster");
		this.checkTime = this.parseTimeOrFallback(config.checkTime(), DEFAULT_CHECK_TIME, "Prognose-Pruefzeit");
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	private LocalTime parseTimeOrFallback(String value, LocalTime fallback, String fieldName) {
		var parsed = DateUtils.parseLocalTimeOrNull(value);
		if (parsed == null) {
			this.logWarn(this.log, "Ungueltiger Wert '" + value + "' fuer '" + fieldName + "' - verwende Standard "
					+ fallback);
			return fallback;
		}
		return parsed;
	}

	@Override
	public void run() throws OpenemsNamedException {
		var now = ZonedDateTime.now(this.componentManager.getClock());
		var today = now.toLocalDate();

		if (!today.equals(this.lastProcessedDate)) {
			// New day: the forecast-based lift is a fresh decision every day, made once
			// at 'checkTime'. The price-based lift needs no reset - it is re-evaluated
			// every Cycle below and naturally reflects "no longer negative" once the
			// price rises again.
			this.lastProcessedDate = today;
			this.forecastCheckDoneToday = false;
			this.forecastLiftedToday = false;
		}

		if (!this.forecastCheckDoneToday && !now.toLocalTime().isBefore(this.checkTime)) {
			this.evaluateForecast(now);
			this.forecastCheckDoneToday = true;
		}

		var priceNegative = this.isPriceCurrentlyNegative(now);
		this._setPriceCurrentlyNegative(priceNegative);
		this._setForecastLiftedToday(this.forecastLiftedToday);

		this.applyState(this.forecastLiftedToday, priceNegative);
		this._setLastDecision(this.buildStatusText(priceNegative));
	}

	/**
	 * Builds a full, current status text distinguishing "no forecast data" from
	 * "forecast data available, does not justify lifting" - unlike
	 * {@link #applyState}, which only writes a message when the applied Config
	 * value actually changes, this is (re-)written every Cycle so the Channel
	 * always reflects the current situation, not just the last transition.
	 *
	 * @param priceNegative whether the current grid-sell price is negative
	 * @return the status text
	 */
	private String buildStatusText(boolean priceNegative) {
		String forecastText;
		if (!this.forecastCheckDoneToday) {
			forecastText = "Prognose: heute noch nicht geprueft (vor " + this.config.checkTime() + ")";
		} else {
			var forecastedWh = this.getForecastedAfternoonProductionChannel().value().get();
			if (forecastedWh == null) {
				forecastText = "Prognose: keine Daten verfuegbar (" + this.productionChannelAddress + ")";
			} else {
				forecastText = "Prognose: " + forecastedWh + " Wh ab " + this.config.afternoonWindowStart()
						+ " (Schwelle " + this.config.minRemainingProductionWh() + " Wh)"
						+ (this.forecastLiftedToday ? " -> hebt Block auf" : " -> reicht aus, Block bleibt aktiv");
			}
		}

		var priceText = priceNegative //
				? "Preis: aktuell negativ -> hebt Block auf" //
				: "Preis: aktuell nicht negativ";

		var result = (this.forecastLiftedToday || priceNegative) ? "Ergebnis: Block aufgehoben" : "Ergebnis: Block aktiv";

		return forecastText + "; " + priceText + "; " + result;
	}

	/**
	 * Sums the production forecast from {@link #afternoonWindowStart} until the
	 * end of the current day and, if it falls below the configured threshold,
	 * sets {@link #forecastLiftedToday} - which then lifts the block for the
	 * remainder of the day (see {@link #applyState}).
	 *
	 * @param now the current {@link ZonedDateTime}
	 */
	private void evaluateForecast(ZonedDateTime now) {
		var afternoonStart = now.toLocalDate().atTime(this.afternoonWindowStart).atZone(now.getZone());
		var tomorrowMidnight = now.toLocalDate().plusDays(1).atStartOfDay(now.getZone());

		var prediction = this.predictorManager.getPrediction(this.productionChannelAddress);
		if (prediction.isEmpty()) {
			this._setForecastedAfternoonProduction(null);
			this.logInfo(this.log,
					"Keine Prognose verfuegbar (" + this.productionChannelAddress + ") - Block bleibt aktiv");
			return;
		}

		var values = prediction.getBetweenExclusive(afternoonStart, tomorrowMidnight).toList();
		if (values.isEmpty()) {
			this._setForecastedAfternoonProduction(null);
			this.logInfo(this.log, "Prognose vorhanden, aber keine Werte im Nachmittagsfenster - Block bleibt aktiv");
			return;
		}

		var forecastedWh = Math.round(values.stream().mapToInt(Integer::intValue).sum() * 0.25f);
		this._setForecastedAfternoonProduction(forecastedWh);

		if (forecastedWh < this.config.minRemainingProductionWh()) {
			this.forecastLiftedToday = true;
			this.logInfo(this.log, "PV-Prognose ab " + this.config.afternoonWindowStart() + " nur " + forecastedWh
					+ " Wh (< " + this.config.minRemainingProductionWh() + " Wh) - Ladeblock fuer heute aufgehoben");
		} else {
			this.logInfo(this.log, "PV-Prognose ab " + this.config.afternoonWindowStart() + ": " + forecastedWh
					+ " Wh (>= " + this.config.minRemainingProductionWh() + " Wh) - Block bleibt aktiv");
		}
	}

	/**
	 * Gets the grid-sell price for the current quarter-hour from
	 * {@link TariffManager} and returns whether it is negative.
	 *
	 * @param now the current {@link ZonedDateTime}
	 * @return true if a price is known and it is negative; false if it is
	 *         positive/zero or no price is known (fail-safe)
	 */
	private boolean isPriceCurrentlyNegative(ZonedDateTime now) {
		var price = this.tariffManager.getGridSellDayAheadPrices().getAt(now);
		return price != null && price < 0;
	}

	/**
	 * Computes the target block state from both triggers and, if it differs from
	 * {@link #lastAppliedUnblocked}, writes it to the target Controller. Writing
	 * only on change avoids reconfiguring (and thereby reactivating) the target
	 * Controller every Cycle.
	 *
	 * @param forecastLifted whether today's forecast check lifted the block
	 * @param priceNegative  whether the current grid-sell price is negative
	 */
	private void applyState(boolean forecastLifted, boolean priceNegative) {
		var shouldBeUnblocked = forecastLifted || priceNegative;
		if (Boolean.valueOf(shouldBeUnblocked).equals(this.lastAppliedUnblocked)) {
			return;
		}

		var reason = shouldBeUnblocked //
				? "Block aufgehoben (Grund: " + describeReason(forecastLifted, priceNegative) + ")" //
				: "Block aktiviert (weder Prognose noch negativer Boersenpreis rechtfertigen aktuell eine Aufhebung)";
		var watts = shouldBeUnblocked ? this.config.unblockedMaxChargePower() : this.config.blockedMaxChargePower();

		if (this.applyMaxChargePower(watts, reason)) {
			this.lastAppliedUnblocked = shouldBeUnblocked;
			this._setCurrentlyBlocked(!shouldBeUnblocked);
		}
	}

	private static String describeReason(boolean forecastLifted, boolean priceNegative) {
		if (forecastLifted && priceNegative) {
			return "PV-Prognose und negativer Boersenpreis";
		} else if (forecastLifted) {
			return "PV-Prognose";
		} else {
			return "negativer Boersenpreis";
		}
	}

	/**
	 * Writes the given value as 'maxChargePower' Config property of
	 * {@link Config#targetControllerId()}, triggering its normal
	 * deactivate/activate reconfiguration cycle. The human-readable reason is
	 * only logged to the Edge log (audit trail of actual changes) -
	 * {@link ForecastChargeWindow.ChannelId#LAST_DECISION} is refreshed
	 * separately every Cycle by {@link #buildStatusText}, independent of
	 * whether a change was actually applied this Cycle.
	 *
	 * @param watts  the value to write [W]
	 * @param reason human-readable reason, logged on success
	 * @return true on success; false on failure (caller retries on the next
	 *         Cycle, since {@link #lastAppliedUnblocked} is only updated on
	 *         success)
	 */
	private boolean applyMaxChargePower(int watts, String reason) {
		try {
			this.componentManager.handleUpdateComponentConfigRequest(null,
					new UpdateComponentConfig.Request(this.config.targetControllerId(),
							List.of(new Property("maxChargePower", new JsonPrimitive(watts)))));
			this.logInfo(this.log, reason);
			return true;
		} catch (OpenemsNamedException e) {
			this.logWarn(this.log, "Konnte 'Max. Ladeleistung' von [" + this.config.targetControllerId()
					+ "] nicht auf " + watts + " W setzen, versuche es im naechsten Cycle erneut: " + e.getMessage());
			return false;
		}
	}
}
