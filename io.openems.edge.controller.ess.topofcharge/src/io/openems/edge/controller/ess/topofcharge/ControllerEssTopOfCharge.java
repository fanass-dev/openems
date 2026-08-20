package io.openems.edge.controller.ess.topofcharge;

import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.BooleanReadChannel;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.StringReadChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;

public interface ControllerEssTopOfCharge extends Controller, OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		LAST_DECISION(Doc.of(OpenemsType.STRING) //
				.text("Klartext-Beschreibung der zuletzt getroffenen Entscheidung")), //
		ACTIVE_MAX_SOC(Doc.of(OpenemsType.INTEGER) //
				.text("Der laut Zeitplan aktuell geltende Max-SOC-Wert - null, wenn kein Task den aktuellen "
						+ "Zeitpunkt abdeckt (dann gilt kein Limit)")), //
		CURRENTLY_LIMITED(Doc.of(OpenemsType.BOOLEAN) //
				.text("true, wenn aktuell die Ladeleistung per Power-Constraint auf 0 begrenzt ist, weil der "
						+ "SOC den aktuell geltenden Max-SOC erreicht/ueberschritten hat"));

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
	 * Gets the Channel for {@link ChannelId#ACTIVE_MAX_SOC}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getActiveMaxSocChannel() {
		return this.channel(ChannelId.ACTIVE_MAX_SOC);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#ACTIVE_MAX_SOC}.
	 *
	 * @param value the next value
	 */
	public default void _setActiveMaxSoc(Integer value) {
		this.getActiveMaxSocChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#CURRENTLY_LIMITED}.
	 *
	 * @return the Channel
	 */
	public default BooleanReadChannel getCurrentlyLimitedChannel() {
		return this.channel(ChannelId.CURRENTLY_LIMITED);
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#CURRENTLY_LIMITED}.
	 *
	 * @param value the next value
	 */
	public default void _setCurrentlyLimited(boolean value) {
		this.getCurrentlyLimitedChannel().setNextValue(value);
	}
}
