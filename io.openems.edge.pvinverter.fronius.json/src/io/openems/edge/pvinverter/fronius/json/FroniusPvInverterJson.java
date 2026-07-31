package io.openems.edge.pvinverter.fronius.json;

import io.openems.common.channel.Level;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.LongReadChannel;
import io.openems.edge.common.channel.StateChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.meter.api.ElectricityMeter;

/**
 * Marker interface for a Fronius PV-Inverter (GEN24 or Symo) read via the
 * Fronius Solar API (JSON/HTTP). Power, voltage, current and frequency are
 * already covered by {@link ElectricityMeter} (as {@code ACTIVE_POWER} /
 * {@code VOLTAGE} / {@code CURRENT} / {@code FREQUENCY}). This interface adds
 * two extra counters delivered by {@code CommonInverterData}
 * ({@code DAY_ENERGY}, {@code YEAR_ENERGY}) that have no dedicated
 * {@link ElectricityMeter} Channel, a diagnostic copy of Fronius' own lifetime
 * production counter ({@code FRONIUS_TOTAL_ENERGY}, see below), plus a
 * communication-failure indicator.
 *
 * <p>
 * Note: on GEN24 devices the Fronius Solar API always reports
 * {@code DAY_ENERGY} and {@code YEAR_ENERGY} as {@code null} (a known Fronius
 * limitation, confirmed for firmware &gt;= 1.14) - only {@code TOTAL_ENERGY}
 * is reliable there. On a Symo (non-hybrid) both counters are normally
 * populated.
 */
public interface FroniusPvInverterJson extends ElectricityMeter, OpenemsComponent {

	public static enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		/**
		 * Set if the last HTTP/JSON request to the inverter failed or returned no
		 * usable data.
		 */
		SLAVE_COMMUNICATION_FAILED(Doc.of(Level.FAULT)),

		/**
		 * Energy generated on the current day.
		 *
		 * <ul>
		 * <li>Type: Long
		 * <li>Unit: {@link Unit#CUMULATED_WATT_HOURS}
		 * <li>Note: always {@code null} on GEN24 devices.
		 * </ul>
		 */
		DAY_ENERGY(Doc.of(OpenemsType.LONG) //
				.unit(Unit.CUMULATED_WATT_HOURS)),

		/**
		 * Energy generated in the current year.
		 *
		 * <ul>
		 * <li>Type: Long
		 * <li>Unit: {@link Unit#CUMULATED_WATT_HOURS}
		 * <li>Note: always {@code null} on GEN24 devices.
		 * </ul>
		 */
		YEAR_ENERGY(Doc.of(OpenemsType.LONG) //
				.unit(Unit.CUMULATED_WATT_HOURS)),

		/**
		 * Fronius' own AC-side lifetime production counter ({@code TOTAL_ENERGY}
		 * from {@code CommonInverterData}) - informational/diagnostic only.
		 *
		 * <p>
		 * NOT used for {@link ElectricityMeter.ChannelId#ACTIVE_PRODUCTION_ENERGY}:
		 * on a hybrid inverter with a DC-coupled battery, this is the same AC-output
		 * counter as {@code PAC}, which nets out DC-side battery charging - the same
		 * bias that {@code PAC} had for the instantaneous power (see class Javadoc of
		 * {@code FroniusPvInverterJsonImpl}). {@code ACTIVE_PRODUCTION_ENERGY} is
		 * instead integrated locally from the corrected {@code ActivePower}
		 * ({@code Site.P_PV}). This channel is kept only so the two can be compared
		 * for diagnostic purposes.
		 *
		 * <ul>
		 * <li>Type: Long
		 * <li>Unit: {@link Unit#CUMULATED_WATT_HOURS}
		 * </ul>
		 */
		FRONIUS_TOTAL_ENERGY(Doc.of(OpenemsType.LONG) //
				.unit(Unit.CUMULATED_WATT_HOURS));

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
	 * Gets the Channel for {@link ChannelId#SLAVE_COMMUNICATION_FAILED}.
	 *
	 * @return the Channel
	 */
	public default StateChannel getSlaveCommunicationFailedChannel() {
		return this.channel(ChannelId.SLAVE_COMMUNICATION_FAILED);
	}

	/**
	 * Gets the Slave Communication Failed State. See
	 * {@link ChannelId#SLAVE_COMMUNICATION_FAILED}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Boolean> getSlaveCommunicationFailed() {
		return this.getSlaveCommunicationFailedChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#SLAVE_COMMUNICATION_FAILED}.
	 *
	 * @param value the next value
	 */
	public default void _setSlaveCommunicationFailed(boolean value) {
		this.getSlaveCommunicationFailedChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#DAY_ENERGY}.
	 *
	 * @return the Channel
	 */
	public default LongReadChannel getDayEnergyChannel() {
		return this.channel(ChannelId.DAY_ENERGY);
	}

	/**
	 * Gets the Value of {@link ChannelId#DAY_ENERGY}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Long> getDayEnergy() {
		return this.getDayEnergyChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#DAY_ENERGY}.
	 *
	 * @param value the next value
	 */
	public default void _setDayEnergy(Long value) {
		this.getDayEnergyChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#YEAR_ENERGY}.
	 *
	 * @return the Channel
	 */
	public default LongReadChannel getYearEnergyChannel() {
		return this.channel(ChannelId.YEAR_ENERGY);
	}

	/**
	 * Gets the Value of {@link ChannelId#YEAR_ENERGY}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Long> getYearEnergy() {
		return this.getYearEnergyChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#YEAR_ENERGY}.
	 *
	 * @param value the next value
	 */
	public default void _setYearEnergy(Long value) {
		this.getYearEnergyChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#FRONIUS_TOTAL_ENERGY}.
	 *
	 * @return the Channel
	 */
	public default LongReadChannel getFroniusTotalEnergyChannel() {
		return this.channel(ChannelId.FRONIUS_TOTAL_ENERGY);
	}

	/**
	 * Gets the Value of {@link ChannelId#FRONIUS_TOTAL_ENERGY}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Long> getFroniusTotalEnergy() {
		return this.getFroniusTotalEnergyChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#FRONIUS_TOTAL_ENERGY}.
	 *
	 * @param value the next value
	 */
	public default void _setFroniusTotalEnergy(Long value) {
		this.getFroniusTotalEnergyChannel().setNextValue(value);
	}
}
