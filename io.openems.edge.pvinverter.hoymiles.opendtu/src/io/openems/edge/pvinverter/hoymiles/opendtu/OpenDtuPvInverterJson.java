package io.openems.edge.pvinverter.hoymiles.opendtu;

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
 * Marker interface for one or more Hoymiles microinverters read via OpenDTU
 * (ESP32-based open-source DTU replacement, JSON/HTTP web API - no Modbus).
 * Power, voltage, current, frequency and the lifetime production counter are
 * already covered by {@link ElectricityMeter} (as {@code ACTIVE_POWER} /
 * {@code VOLTAGE} / {@code CURRENT} / {@code FREQUENCY} /
 * {@code ACTIVE_PRODUCTION_ENERGY}). This interface adds one extra counter
 * ({@code DAY_ENERGY}, delivered by OpenDTU but with no dedicated
 * {@link ElectricityMeter} Channel), a communication-failure indicator, and
 * two diagnostic State-Channels mirrored 1:1 from OpenDTU's own
 * {@code hints} object.
 *
 * <p>
 * Note on Voltage/Current/Frequency: these are only available when this
 * Component is configured for a single inverter (Config attribute
 * {@code inverterSerial} set) - OpenDTU's aggregate endpoint (no serial
 * configured, i.e. sum of all connected inverters) only ever reports
 * Power/YieldDay/YieldTotal, never AC electrical values. See
 * {@code OpenDtuPvInverterJsonImpl} Javadoc for details.
 */
public interface OpenDtuPvInverterJson extends ElectricityMeter, OpenemsComponent {

	public static enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		/**
		 * Set if the last HTTP/JSON request to OpenDTU failed, returned no usable
		 * data, or (in single-inverter mode) the configured serial number was not
		 * found in the response.
		 */
		SLAVE_COMMUNICATION_FAILED(Doc.of(Level.FAULT)),

		/**
		 * Energy generated on the current day.
		 *
		 * <ul>
		 * <li>Type: Long
		 * <li>Unit: {@link Unit#CUMULATED_WATT_HOURS}
		 * <li>Source: OpenDTU {@code total.YieldDay} (aggregate mode) or
		 * {@code INV["0"].YieldDay} (single-inverter mode).
		 * </ul>
		 */
		DAY_ENERGY(Doc.of(OpenemsType.LONG) //
				.unit(Unit.CUMULATED_WATT_HOURS)),

		/**
		 * Mirrors OpenDTU's {@code hints.radio_problem} flag: set when OpenDTU
		 * detects a radio (nRF24/CMT) communication problem with at least one
		 * connected inverter. Confirmed semantics (true = problem present) from the
		 * OpenDTU v23.4.15 changelog ("Adjusted radio problem hint... to detect
		 * problems of nrf and cmt radios").
		 */
		RADIO_PROBLEM(Doc.of(Level.WARNING)),

		/**
		 * Mirrors OpenDTU's {@code hints.default_password} flag: set when the
		 * OpenDTU device is still using its factory-default admin password
		 * ({@code admin} / {@code openDTU42}) - a security hint, not a
		 * communication problem.
		 */
		DEFAULT_PASSWORD_ACTIVE(Doc.of(Level.WARNING));

		// Deliberately NOT mapped: OpenDTU's hints.time_sync flag. Its exact
		// polarity (does "true" mean "time IS synced" or "time sync PROBLEM"?)
		// could not be confirmed against OpenDTU firmware source in this session
		// (GitHub rate-limited the source fetch) - per the project's verification
		// rule, an unconfirmed boolean polarity is not mapped to a Channel rather
		// than guessed. Add it yourself once you have confirmed the polarity
		// against your own OpenDTU instance or its source
		// (WebApi_ws_live.cpp/WebApi_status.cpp).

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
	 * Gets the Channel for {@link ChannelId#RADIO_PROBLEM}.
	 *
	 * @return the Channel
	 */
	public default StateChannel getRadioProblemChannel() {
		return this.channel(ChannelId.RADIO_PROBLEM);
	}

	/**
	 * Gets the Radio Problem State. See {@link ChannelId#RADIO_PROBLEM}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Boolean> getRadioProblem() {
		return this.getRadioProblemChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#RADIO_PROBLEM}.
	 *
	 * @param value the next value
	 */
	public default void _setRadioProblem(boolean value) {
		this.getRadioProblemChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#DEFAULT_PASSWORD_ACTIVE}.
	 *
	 * @return the Channel
	 */
	public default StateChannel getDefaultPasswordActiveChannel() {
		return this.channel(ChannelId.DEFAULT_PASSWORD_ACTIVE);
	}

	/**
	 * Gets the Default Password Active State. See
	 * {@link ChannelId#DEFAULT_PASSWORD_ACTIVE}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Boolean> getDefaultPasswordActive() {
		return this.getDefaultPasswordActiveChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#DEFAULT_PASSWORD_ACTIVE}.
	 *
	 * @param value the next value
	 */
	public default void _setDefaultPasswordActive(boolean value) {
		this.getDefaultPasswordActiveChannel().setNextValue(value);
	}
}
