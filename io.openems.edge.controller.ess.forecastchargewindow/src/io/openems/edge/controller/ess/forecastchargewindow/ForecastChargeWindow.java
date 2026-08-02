package io.openems.edge.controller.ess.forecastchargewindow;

import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.BooleanReadChannel;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.StringReadChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;

public interface ForecastChargeWindow extends Controller, OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		LAST_DECISION(Doc.of(OpenemsType.STRING) //
				.text("Klartext-Beschreibung der zuletzt getroffenen Entscheidung")), //
		FORECASTED_AFTERNOON_PRODUCTION(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT_HOURS) //
				.text("Zuletzt berechnete PV-Prognosesumme ab 'Beginn Nachmittagsfenster' bis Tagesende")), //
		FORECAST_LIFTED_TODAY(Doc.of(OpenemsType.BOOLEAN) //
				.text("true, wenn die heutige Prognose-Pruefung den Block fuer den Rest des Tages aufgehoben hat")), //
		PRICE_CURRENTLY_NEGATIVE(Doc.of(OpenemsType.BOOLEAN) //
				.text("true, wenn der aktuelle Boersen-Verkaufspreis (TariffManager) negativ ist")), //
		CURRENTLY_BLOCKED(Doc.of(OpenemsType.BOOLEAN) //
				.text("true, wenn der Ziel-Controller aktuell auf 'Ladeleistung waehrend Blockierung' steht")); //

		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}

	/**
	 * Gets the Channel for {@link ChannelId#LAST_DECISION}.
	 *
	 * @return the Channel
	 */
	public default StringReadChannel getLastDecisionChannel() {
		return this.channel(ChannelId.LAST_DECISION);
	}

	/**
	 * Gets the value of {@link ChannelId#LAST_DECISION}.
	 *
	 * @return the Value
	 */
	public default Value<String> getLastDecision() {
		return this.getLastDecisionChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#LAST_DECISION}.
	 *
	 * @param value the next value
	 */
	public default void _setLastDecision(String value) {
		this.getLastDecisionChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#FORECASTED_AFTERNOON_PRODUCTION}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getForecastedAfternoonProductionChannel() {
		return this.channel(ChannelId.FORECASTED_AFTERNOON_PRODUCTION);
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#FORECASTED_AFTERNOON_PRODUCTION}.
	 *
	 * @param value the next value
	 */
	public default void _setForecastedAfternoonProduction(Integer value) {
		this.getForecastedAfternoonProductionChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#FORECAST_LIFTED_TODAY}.
	 *
	 * @return the Channel
	 */
	public default BooleanReadChannel getForecastLiftedTodayChannel() {
		return this.channel(ChannelId.FORECAST_LIFTED_TODAY);
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#FORECAST_LIFTED_TODAY}.
	 *
	 * @param value the next value
	 */
	public default void _setForecastLiftedToday(boolean value) {
		this.getForecastLiftedTodayChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#PRICE_CURRENTLY_NEGATIVE}.
	 *
	 * @return the Channel
	 */
	public default BooleanReadChannel getPriceCurrentlyNegativeChannel() {
		return this.channel(ChannelId.PRICE_CURRENTLY_NEGATIVE);
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#PRICE_CURRENTLY_NEGATIVE}.
	 *
	 * @param value the next value
	 */
	public default void _setPriceCurrentlyNegative(boolean value) {
		this.getPriceCurrentlyNegativeChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#CURRENTLY_BLOCKED}.
	 *
	 * @return the Channel
	 */
	public default BooleanReadChannel getCurrentlyBlockedChannel() {
		return this.channel(ChannelId.CURRENTLY_BLOCKED);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#CURRENTLY_BLOCKED}.
	 *
	 * @param value the next value
	 */
	public default void _setCurrentlyBlocked(boolean value) {
		this.getCurrentlyBlockedChannel().setNextValue(value);
	}
}
