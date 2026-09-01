package io.openems.edge.controller.ess.forecastchargewindow;

import static io.openems.common.utils.IntUtils.fitWithin;
import static io.openems.edge.common.type.Phase.SingleOrAllPhase.ALL;
import static io.openems.edge.ess.power.api.Pwr.ACTIVE;
import static io.openems.edge.ess.power.api.Relationship.GREATER_OR_EQUALS;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import io.openems.common.timedata.Resolution;
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
import io.openems.edge.timeofusetariff.api.TimeOfUsePrices;
import io.openems.edge.timeofusetariff.api.TimeOfUseTariff;

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

	/**
	 * Human-readable display names for known {@code TimeOfUseTariff} Factory-IDs
	 * (see {@link OpenemsComponent#serviceFactoryPid()}), used by
	 * {@link #resolvePricesWithProvider()} to show a recognizable provider name
	 * in the Live-view instead of a Component-ID/alias that might just be the
	 * default "timeOfUseTariffX". Deliberately keyed by Factory-ID, not by
	 * concrete implementation class, so this bundle does not need a compile-time
	 * dependency on any specific provider bundle (matches the reasoning for
	 * depending on io.openems.edge.timeofusetariff.api only, see bnd.bnd).
	 */
	private static final Map<String, String> PROVIDER_DISPLAY_NAMES = Map.of(//
			"TimeOfUseTariff.ENTSO-E", "ENTSO-E", //
			"TimeOfUseTariff.EnergyCharts", "Energy Charts", //
			"TimeOfUseTariff.Awattar", "aWATTar");

	private final Logger log = LoggerFactory.getLogger(ForecastChargeWindowImpl.class);

	@Reference
	private ComponentManager componentManager;

	@Reference
	private PredictorManager predictorManager;

	@Reference(policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY, //
			cardinality = ReferenceCardinality.OPTIONAL)
	private volatile Timedata timedata = null;

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
		this.blockWindow = JSCalendar.Tasks.fromStringOrEmpty(this.componentManager.getClock(), config.jsCalendar());
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

	/**
	 * Pairs the resolved {@link TimeOfUsePrices} with a human-readable display
	 * name of the Component that supplied them (see
	 * {@link #providerDisplayName(OpenemsComponent)}), so the Live-view can show
	 * which of the configured providers is currently active - see
	 * {@link #resolvePricesWithProvider()}.
	 */
	private record ResolvedPrices(TimeOfUsePrices prices, String providerDisplayName) {
		private static final ResolvedPrices EMPTY = new ResolvedPrices(TimeOfUsePrices.EMPTY_PRICES, null);
	}

	/**
	 * Resolves day-ahead prices from the configured {@link Config#priceProviderIds()},
	 * trying each Component-ID in order and using the first one that returns
	 * non-empty prices - so a temporarily unavailable/misconfigured provider (e.g.
	 * ENTSO-E during a maintenance window, see readme.adoc) automatically falls
	 * back to the next configured provider instead of losing price data entirely.
	 * Resolved fresh on every call (not cached at {@link #activate}) since a
	 * provider Component may not yet be available right after an Edge restart
	 * and could become available later - same reasoning as
	 * {@link #applyEssConstraint} resolving {@link Config#ess_id()} fresh each
	 * Cycle instead of once.
	 *
	 * @return the resolved prices together with the providing Component's alias,
	 *         or {@link ResolvedPrices#EMPTY} if none of the configured providers
	 *         currently has data
	 */
	private ResolvedPrices resolvePricesWithProvider() {
		for (var providerId : this.config.priceProviderIds()) {
			if (providerId == null || providerId.isBlank()) {
				continue;
			}
			try {
				OpenemsComponent component = this.componentManager.getComponent(providerId);
				if (!(component instanceof TimeOfUseTariff provider)) {
					this.logWarn(this.log,
							"Preis-Anbieter '" + providerId + "' implementiert kein TimeOfUseTariff - wird ignoriert.");
					continue;
				}
				var prices = provider.getPrices();
				if (!prices.isEmpty()) {
					return new ResolvedPrices(prices, providerDisplayName(component));
				}
			} catch (OpenemsNamedException e) {
				this.logWarn(this.log, "Preis-Anbieter '" + providerId + "' nicht gefunden: " + e.getMessage());
			}
		}
		return ResolvedPrices.EMPTY;
	}

	/**
	 * Picks a human-readable display name for the given price-provider
	 * Component - a hardcoded name for recognized {@code TimeOfUseTariff}
	 * Factory-IDs (see {@link #PROVIDER_DISPLAY_NAMES}), falling back to the
	 * Component's alias if one was actually configured (i.e. differs from its
	 * Component-ID - an unset alias defaults to the Component-ID), and to the
	 * Component-ID itself as the last resort.
	 *
	 * @param component the resolved price-provider Component
	 * @return the display name to show in the Live-view
	 */
	private static String providerDisplayName(OpenemsComponent component) {
		var knownName = PROVIDER_DISPLAY_NAMES.get(component.serviceFactoryPid());
		if (knownName != null) {
			return knownName;
		}
		var alias = component.alias();
		if (alias != null && !alias.isBlank() && !alias.equals(component.id())) {
			return alias;
		}
		return component.id();
	}

	/**
	 * Resolves day-ahead prices, discarding the provider alias.
	 *
	 * @return the resolved {@link TimeOfUsePrices} - see
	 *         {@link #resolvePricesWithProvider()}.
	 */
	private TimeOfUsePrices resolvePrices() {
		return this.resolvePricesWithProvider().prices();
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
			// Only counts as "done for today" if a Prediction was actually available -
			// otherwise (e.g. PredictorManager not yet bound/trained right after an Edge
			// restart, see readme.adoc) retry on the next Cycle instead of losing the
			// day's only forecast-based unblock opportunity to a brief startup race.
			this.forecastCheckDoneToday = this.evaluateForecast(now);
		}

		var resolved = this.resolvePricesWithProvider();
		var prices = resolved.prices();
		Double currentPrice = prices.isEmpty() ? null : prices.getAtOrElse(now, null);
		var priceNegative = currentPrice != null && currentPrice < 0;
		this._setCurrentGridSellPrice(currentPrice);
		this._setCurrentGridSellPriceProvider(currentPrice != null ? resolved.providerDisplayName() : null);
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
	 * <p>
	 * Returns whether a Prediction was actually available - {@link #run()} only
	 * marks the day's check as done if this returns {@code true}, so a brief
	 * startup race (e.g. {@code PredictorManager} not yet bound, or the model
	 * not yet trained, right after an Edge restart - both observed live, see
	 * readme.adoc) is retried on the next Cycle instead of silently losing the
	 * entire day's forecast-based unblock opportunity to a single bad-timed
	 * first attempt.
	 *
	 * @param now the current {@link ZonedDateTime}
	 * @return {@code true} if a Prediction was available and evaluated,
	 *         {@code false} if it should be retried on the next Cycle
	 */
	private boolean evaluateForecast(ZonedDateTime now) {
		var afternoonStart = now.toLocalDate().atTime(this.afternoonWindowStart).atZone(now.getZone());
		var tomorrowMidnight = now.toLocalDate().plusDays(1).atStartOfDay(now.getZone());

		var prediction = this.predictorManager.getPrediction(this.productionChannelAddress);
		if (prediction.isEmpty()) {
			this._setForecastedAfternoonProduction(null);
			this.logInfo(this.log, "Keine Prognose verfuegbar (" + this.productionChannelAddress
					+ ") - erneuter Versuch im naechsten Zyklus");
			return false;
		}

		var values = prediction.getBetweenExclusive(afternoonStart, tomorrowMidnight).toList();
		if (values.isEmpty()) {
			this._setForecastedAfternoonProduction(null);
			this.logInfo(this.log,
					"Prognose vorhanden, aber keine Werte im Nachmittagsfenster - Zeitfenster entscheidet");
			return true;
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
		return true;
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
			var now = ZonedDateTime.now(this.componentManager.getClock());
			var points = new ArrayList<PricePoint>();

			// Past (already-elapsed today): TimeOfUseTariff.getPrices() only ever
			// returns 'now onward' (that is the convention of every implementation of
			// this generic interface, e.g. TimeOfUseTariff.Awattar/ENTSO-E) - so the
			// already-elapsed part of the chart has to come from the historized
			// CurrentGridSellPrice Channel instead.
			if (this.timedata != null) {
				try {
					var todayMidnight = now.toLocalDate().atStartOfDay(now.getZone());
					var currentGridSellPriceAddress = new ChannelAddress(this.id(), "CurrentGridSellPrice");
					var historicData = this.timedata.queryHistoricData(null, todayMidnight, now,
							Set.of(currentGridSellPriceAddress), new Resolution(15, ChronoUnit.MINUTES));
					for (var entry : historicData.entrySet()) {
						var value = entry.getValue().get(currentGridSellPriceAddress);
						if (value != null && !value.isJsonNull()) {
							points.add(new PricePoint(entry.getKey().toInstant(), value.getAsDouble()));
						}
					}
				} catch (OpenemsNamedException e) {
					this.logWarn(this.log, "Konnte historische Day-Ahead-Preise nicht laden: " + e.getMessage());
				}
			}

			// Future (now onward): from the currently resolved price provider.
			this.resolvePrices().toMap().entrySet().stream() //
					.map(e -> new PricePoint(e.getKey(), e.getValue())) //
					.forEach(points::add);

			return new Response(points, "EUR/MWh");
		});
	}
}
