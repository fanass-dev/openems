package io.openems.edge.controller.ess.forecastchargewindow;

import static io.openems.common.utils.IntUtils.fitWithin;
import static io.openems.edge.common.type.Phase.SingleOrAllPhase.ALL;
import static io.openems.edge.ess.power.api.Pwr.ACTIVE;
import static io.openems.edge.ess.power.api.Relationship.GREATER_OR_EQUALS;

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
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.jscalendar.JSCalendar;
import io.openems.common.session.Role;
import io.openems.common.types.ChannelAddress;
import io.openems.common.utils.DateUtils;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.jsonapi.ComponentJsonApi;
import io.openems.edge.common.jsonapi.EdgeGuards;
import io.openems.edge.common.jsonapi.JsonApiBuilder;
import io.openems.edge.controller.api.Controller;
import io.openems.edge.controller.ess.forecastchargewindow.jsonrpc.GetDayAheadGridSellPricesEndpoint;
import io.openems.edge.controller.ess.forecastchargewindow.jsonrpc.GetDayAheadGridSellPricesEndpoint.Response;
import io.openems.edge.controller.ess.forecastchargewindow.jsonrpc.GetDayAheadGridSellPricesEndpoint.Response.PricePoint;
import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.predictor.api.manager.PredictorManager;
import io.openems.edge.timedata.api.Timedata;
import io.openems.edge.timedata.api.TimedataProvider;
import io.openems.edge.timedata.api.utils.CalculateActiveTime;
import io.openems.edge.timeofusetariff.entsoe.priceprovider.EntsoeConfiguration;
import io.openems.edge.timeofusetariff.entsoe.priceprovider.EntsoeMarketPriceProvider;
import io.openems.edge.timeofusetariff.entsoe.priceprovider.EntsoeMarketPriceProviderPool;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Controller.Ess.ForecastChargeWindow", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class ForecastChargeWindowImpl extends AbstractOpenemsComponent
		implements ForecastChargeWindow, Controller, OpenemsComponent, TimedataProvider, ComponentJsonApi {

	private static final LocalTime DEFAULT_AFTERNOON_WINDOW_START = LocalTime.of(12, 0);
	private static final LocalTime DEFAULT_CHECK_TIME = LocalTime.of(8, 0);
	private static final String CONSTRAINT_ID = "ForecastChargeWindow";

	private final Logger log = LoggerFactory.getLogger(ForecastChargeWindowImpl.class);

	@Reference
	private ComponentManager componentManager;

	@Reference
	private PredictorManager predictorManager;

	@Reference
	private EntsoeMarketPriceProviderPool marketPriceProviderPool;

	@Reference(policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY, //
			cardinality = ReferenceCardinality.OPTIONAL)
	private volatile Timedata timedata = null;

	/**
	 * Obtained from {@link #marketPriceProviderPool} in {@link #activate}, using
	 * the configured Bidding Zone/Security Token - pooled by that same
	 * (Zone, Token) pair, so a Tariff.Manual component configured with the same
	 * credentials shares the underlying ENTSO-E fetch instead of duplicating it.
	 */
	private EntsoeMarketPriceProvider marketPriceProvider;

	private final CalculateActiveTime negativePriceDuration = new CalculateActiveTime(this,
			ForecastChargeWindow.ChannelId.NEGATIVE_PRICE_DURATION);

	private Config config;
	private ChannelAddress productionChannelAddress;
	private LocalTime afternoonWindowStart;
	private LocalTime checkTime;

	/**
	 * Parsed from {@link Config#jsCalendar()} - determines the time window(s)
	 * during which charging is blocked by default (subject to being lifted by
	 * forecast or price, see {@link #run()}). Reuses the exact same JSCalendar
	 * engine as {@code Scheduler.JSCalendar}, just evaluated locally here
	 * instead of externally controlling a second Controller.
	 */
	private JSCalendar.Tasks<Void> blockWindow = JSCalendar.Tasks.empty();

	private LocalDate lastProcessedDate = null;
	private boolean forecastCheckDoneToday = false;
	private boolean forecastLiftedToday = false;

	/**
	 * Last logged overall result - used only to avoid logging the same line
	 * every Cycle, see {@link #logOnChange}.
	 */
	private Boolean lastLoggedUnblocked = null;

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
		this.marketPriceProvider = this.marketPriceProviderPool
				.get(new EntsoeConfiguration(config.biddingZone(), config.securityToken()));
		this.blockWindow = JSCalendar.Tasks.fromStringOrEmpty(this.componentManager.getClock(), config.jsCalendar());
	}

	@Override
	@Deactivate
	protected void deactivate() {
		if (this.marketPriceProvider != null) {
			this.marketPriceProviderPool.unget(this.marketPriceProvider);
			this.marketPriceProvider = null;
		}
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

		var marketPriceData = this.marketPriceProvider.getMarketPrices().getValue();
		var currentPrice = marketPriceData == null ? null : marketPriceData.getValues().getAt(now);
		var priceNegative = currentPrice != null && currentPrice < 0;
		this._setCurrentGridSellPrice(currentPrice);
		this._setPriceCurrentlyNegative(priceNegative);
		this._setForecastLiftedToday(this.forecastLiftedToday);
		this.negativePriceDuration.update(priceNegative);

		// Outside the configured time window, there is never a default block -
		// only within it can the forecast/price triggers even matter.
		var withinTimeWindow = this.blockWindow.getActiveOneTask() != null;
		this._setWithinTimeWindow(withinTimeWindow);

		var shouldBeUnblocked = !withinTimeWindow || this.forecastLiftedToday || priceNegative;
		this._setCurrentlyBlocked(!shouldBeUnblocked);
		this.logOnChange(withinTimeWindow, this.forecastLiftedToday, priceNegative, shouldBeUnblocked);
		this.applyEssConstraint(shouldBeUnblocked);

		this._setLastDecision(this.buildStatusText(withinTimeWindow, priceNegative, shouldBeUnblocked));
	}

	/**
	 * Builds a full, current status text distinguishing "no forecast data" from
	 * "forecast data available, does not justify lifting" - refreshed every
	 * Cycle so the Channel always reflects the current situation, not just the
	 * last transition.
	 *
	 * @param withinTimeWindow whether 'now' is within a configured block window
	 * @param priceNegative    whether the current grid-sell price is negative
	 * @param shouldBeUnblocked the overall result
	 * @return the status text
	 */
	private String buildStatusText(boolean withinTimeWindow, boolean priceNegative, boolean shouldBeUnblocked) {
		var windowText = withinTimeWindow //
				? "Zeitfenster: aktiv" //
				: "Zeitfenster: nicht aktiv -> hebt Block auf";

		String forecastText;
		if (!this.forecastCheckDoneToday) {
			forecastText = "Prognose: heute noch nicht geprueft (vor " + this.config.checkTime() + ")";
		} else {
			var forecastedWh = this.getForecastedAfternoonProductionChannel().value().get();
			if (forecastedWh == null) {
				forecastText = "Prognose: keine Daten verfuegbar (" + this.productionChannelAddress
						+ ") -> Zeitfenster entscheidet";
			} else {
				forecastText = "Prognose: " + forecastedWh + " Wh ab " + this.config.afternoonWindowStart()
						+ " (Schwelle " + this.config.minRemainingProductionWh() + " Wh)"
						+ (this.forecastLiftedToday ? " -> hebt Block auf" : " -> reicht aus");
			}
		}

		var priceText = priceNegative //
				? "Preis: aktuell negativ -> hebt Block auf" //
				: "Preis: aktuell nicht negativ";

		var result = shouldBeUnblocked ? "Ergebnis: Block aufgehoben" : "Ergebnis: Block aktiv";

		return windowText + "; " + forecastText + "; " + priceText + "; " + result;
	}

	/**
	 * Sums the production forecast from {@link #afternoonWindowStart} until the
	 * end of the current day and, if it falls below the configured threshold,
	 * sets {@link #forecastLiftedToday} - which then lifts the block for the
	 * remainder of the day. If no forecast is available at all (e.g. because of
	 * a lost internet connection, or no Predictor configured for
	 * {@link Config#productionChannelId()}), {@link #forecastLiftedToday} is
	 * deliberately left untouched - the forecast trigger simply does not
	 * contribute an opinion, and the block/unblock decision falls back to the
	 * time window (see {@link #run()}). This is safe because the block is
	 * already bounded by that window (e.g. only until noon), never indefinite -
	 * unlike an earlier version of this method, which force-unblocked on
	 * missing data to avoid an indefinite block under the pre-time-window
	 * design, but that made a merely temporarily unavailable forecast silently
	 * defeat the window every time it triggered.
	 *
	 * @param now the current {@link ZonedDateTime}
	 */
	private void evaluateForecast(ZonedDateTime now) {
		var afternoonStart = now.toLocalDate().atTime(this.afternoonWindowStart).atZone(now.getZone());
		var tomorrowMidnight = now.toLocalDate().plusDays(1).atStartOfDay(now.getZone());

		var prediction = this.predictorManager.getPrediction(this.productionChannelAddress);
		if (prediction.isEmpty()) {
			this._setForecastedAfternoonProduction(null);
			this.logInfo(this.log, "Keine Prognose verfuegbar (" + this.productionChannelAddress
					+ ") - Zeitfenster entscheidet");
			return;
		}

		var values = prediction.getBetweenExclusive(afternoonStart, tomorrowMidnight).toList();
		if (values.isEmpty()) {
			this._setForecastedAfternoonProduction(null);
			this.logInfo(this.log,
					"Prognose vorhanden, aber keine Werte im Nachmittagsfenster - Zeitfenster entscheidet");
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
	 * Logs a human-readable line only when the overall result actually changes -
	 * {@link #applyEssConstraint(boolean)} itself must run every Cycle
	 * unconditionally (it is a live Power-Constraint, not a Config write), but
	 * logging every Cycle would spam the log.
	 *
	 * @param withinTimeWindow  whether 'now' is within a configured block window
	 * @param forecastLifted    whether today's forecast check lifted the block
	 * @param priceNegative     whether the current grid-sell price is negative
	 * @param shouldBeUnblocked the overall result
	 */
	private void logOnChange(boolean withinTimeWindow, boolean forecastLifted, boolean priceNegative,
			boolean shouldBeUnblocked) {
		if (Boolean.valueOf(shouldBeUnblocked).equals(this.lastLoggedUnblocked)) {
			return;
		}
		this.lastLoggedUnblocked = shouldBeUnblocked;
		this.logInfo(this.log, shouldBeUnblocked //
				? "Block aufgehoben (Grund: " + describeReason(withinTimeWindow, forecastLifted, priceNegative)
						+ ")" //
				: "Block aktiviert (Zeitfenster aktiv, weder Prognose noch negativer Boersenpreis rechtfertigen "
						+ "aktuell eine Aufhebung)");
	}

	private static String describeReason(boolean withinTimeWindow, boolean forecastLifted, boolean priceNegative) {
		if (!withinTimeWindow) {
			return "ausserhalb des konfigurierten Zeitfensters";
		} else if (forecastLifted && priceNegative) {
			return "PV-Prognose und negativer Boersenpreis";
		} else if (forecastLifted) {
			return "PV-Prognose";
		} else {
			return "negativer Boersenpreis";
		}
	}

	/**
	 * Sets the Max-Charge-Power limit directly as a Power-Constraint on
	 * {@link Config#ess_id()} - every Cycle, unconditionally, like any other
	 * live-constraint Controller (e.g. Controller.Symmetric.LimitActivePower,
	 * whose pattern this mirrors). No second Controller is written to/steered
	 * by this one.
	 *
	 * @param unblocked whether charging should currently be unblocked
	 * @throws OpenemsNamedException on error, e.g. if {@link Config#ess_id()}
	 *                                does not resolve to a Component
	 */
	private void applyEssConstraint(boolean unblocked) throws OpenemsNamedException {
		ManagedSymmetricEss ess = this.componentManager.getComponent(this.config.ess_id());
		var maxChargePower = (unblocked ? this.config.unblockedMaxChargePower() : this.config.blockedMaxChargePower())
				* -1;

		if (this.config.validatePowerConstraints()) {
			var maxPower = ess.getPower().getMaxPower(ess, ALL, ACTIVE);
			var minPower = ess.getPower().getMinPower(ess, ALL, ACTIVE);
			var calculatedMaxChargePower = fitWithin(minPower, maxPower, maxChargePower);
			ess.addPowerConstraintAndValidate(CONSTRAINT_ID, ALL, ACTIVE, GREATER_OR_EQUALS, calculatedMaxChargePower);
		} else {
			ess.addPowerConstraint(CONSTRAINT_ID, ALL, ACTIVE, GREATER_OR_EQUALS, maxChargePower);
		}
	}

	@Override
	public Timedata getTimedata() {
		return this.timedata;
	}

	@Override
	public void buildJsonApiRoutes(JsonApiBuilder builder) {
		builder.handleRequest(new GetDayAheadGridSellPricesEndpoint(), endpoint -> {
			endpoint.setGuards(EdgeGuards.roleIsAtleast(Role.GUEST));
		}, call -> {
			var marketPriceData = this.marketPriceProvider.getMarketPrices().getValue();
			var points = marketPriceData == null //
					? List.<PricePoint>of() //
					: marketPriceData.getValues().toMap().entrySet().stream() //
							.map(e -> new PricePoint(e.getKey(), e.getValue())) //
							.toList();
			return new Response(points, "EUR/MWh");
		});
	}
}
