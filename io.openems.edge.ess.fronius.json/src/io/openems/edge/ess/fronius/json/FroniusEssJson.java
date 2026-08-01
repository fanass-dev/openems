package io.openems.edge.ess.fronius.json;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.Level;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.BooleanReadChannel;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.channel.StateChannel;
import io.openems.edge.common.channel.StringReadChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.ess.api.SymmetricEss;

/**
 * Marker interface for a Fronius Hybrid Inverter's battery storage, read via
 * the Fronius Solar API (JSON/HTTP). SOC, Active-Power, Capacity and
 * Min/Max-Cell-Voltage/Temperature are already covered by
 * {@link SymmetricEss}. This interface adds a communication-failure
 * indicator plus a few extra diagnostic values delivered by
 * {@code GetStorageRealtimeData.cgi} that have no dedicated
 * {@link SymmetricEss} Channel.
 *
 * <p>
 * Extends {@link ManagedSymmetricEss} so that - if {@code ControlMode} is set
 * to anything other than {@code READ_ONLY} in the Config - standard OpenEMS
 * Ess-Controllers (Balancing, FixActivePower, GridOptimizedCharge, ...) can
 * issue Power-Setpoints. These are relayed to the device via the
 * inofficial/undocumented Fronius Web-Config-API (Digest-Auth), see readme.
 * When {@code ControlMode == READ_ONLY} (the default), {@link #applyPower}
 * simply does nothing - the Component then behaves exactly like a plain,
 * read-only {@link SymmetricEss}.
 *
 * <p>
 * Not every field below is available on every battery/firmware combination -
 * Fronius explicitly documents that "inactive channels are not included in
 * the response and may vary depending on used battery and software
 * version". All Channels are therefore filled null-safe; a missing field
 * simply results in an "undefined" Channel value instead of an error.
 */
public interface FroniusEssJson extends ManagedSymmetricEss, OpenemsComponent {

	public static enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		/**
		 * Set if the last HTTP/JSON request to the storage controller failed or
		 * returned no usable data (e.g. unsupported battery type, see readme).
		 */
		SLAVE_COMMUNICATION_FAILED(Doc.of(Level.FAULT)),

		/** Is the battery/BMS enabled ({@code Enable} field), i.e. not disconnected. */
		BATTERY_ENABLED(Doc.of(OpenemsType.BOOLEAN)),

		/**
		 * Raw {@code Status_BatteryCell} value - meaning is manufacturer-specific,
		 * see readme.adoc (e.g. BYD: 0=Standby, 3=Active, 4=Fault; LG-Chem:
		 * 1=Standby, 3=Enabled, 5=Faulted, 10=Sleep).
		 */
		STATUS_BATTERY_CELL(Doc.of(OpenemsType.INTEGER)),

		/**
		 * Maximum designed (nameplate) capacity, as opposed to
		 * {@link SymmetricEss.ChannelId#CAPACITY} which reflects the current,
		 * possibly aged, maximum capacity ({@code Capacity_Maximum}).
		 */
		DESIGNED_CAPACITY(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT_HOURS)),

		/** DC voltage measured at the battery terminals ({@code Voltage_DC}). */
		DC_VOLTAGE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.MILLIVOLT)),

		/**
		 * DC current measured at the battery terminals ({@code Current_DC}); Fronius
		 * convention: positive = charging.
		 */
		DC_CURRENT(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.MILLIAMPERE)),

		/** {@code Details.Manufacturer} of the battery, e.g. "BYD", "Fronius". */
		BATTERY_MANUFACTURER(Doc.of(OpenemsType.STRING)),

		/** {@code Details.Model} of the battery, e.g. "BYD Battery-Box Premium HV". */
		BATTERY_MODEL(Doc.of(OpenemsType.STRING)),

		/** {@code Details.Serial} of the battery. */
		BATTERY_SERIAL(Doc.of(OpenemsType.STRING)),

		/**
		 * Detected GEN24 firmware version string (from {@code /api/status/version}),
		 * only populated once the Digest-Auth control path (ControlMode != READ_ONLY)
		 * has run at least once - determines which endpoint paths/hash algorithm are
		 * used, see readme.
		 */
		FIRMWARE_VERSION(Doc.of(OpenemsType.STRING)),

		/**
		 * Human-readable description of the last control write that was actually
		 * sent to the device (e.g. "SCHEDULE_BASED: CHARGE_MIN 1500 W"), for
		 * diagnostics/Grafana. Empty as long as ControlMode == READ_ONLY.
		 */
		LAST_CONTROL_ACTION(Doc.of(OpenemsType.STRING)),

		/**
		 * Writable at runtime (e.g. from the UI or a Controller): sets the
		 * device-side maximum State-of-Charge (0-100 %) up to which the battery may
		 * charge, via {@code BAT_M0_SOC_MAX}/{@code BAT_M0_SOC_MODE=manual} on the
		 * same {@code /api/config/batteries} endpoint already used for
		 * {@code HYB_EM_POWER}/{@code HYB_EVU_CHARGEFROMGRID}. Only takes effect
		 * when {@code ControlMode != READ_ONLY} (same as all other writes) -
		 * ignored otherwise. Independent of the schedule/grid-power write path
		 * (not throttled by {@code writeDeadbandWatt}/{@code minWriteIntervalSeconds},
		 * since it is a deliberate, infrequent user action, not a continuous
		 * regulation value).
		 */
		SET_MAX_SOC(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.PERCENT) //
				.accessMode(AccessMode.READ_WRITE) //
				.text("Maximaler Lade-SoC (0-100 %), device-seitig ueber BAT_M0_SOC_MAX durchgesetzt"));

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
	 * Gets the Channel for {@link ChannelId#BATTERY_ENABLED}.
	 *
	 * @return the Channel
	 */
	public default BooleanReadChannel getBatteryEnabledChannel() {
		return this.channel(ChannelId.BATTERY_ENABLED);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#BATTERY_ENABLED}.
	 *
	 * @param value the next value
	 */
	public default void _setBatteryEnabled(Boolean value) {
		this.getBatteryEnabledChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#STATUS_BATTERY_CELL}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getStatusBatteryCellChannel() {
		return this.channel(ChannelId.STATUS_BATTERY_CELL);
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#STATUS_BATTERY_CELL}.
	 *
	 * @param value the next value
	 */
	public default void _setStatusBatteryCell(Integer value) {
		this.getStatusBatteryCellChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#DESIGNED_CAPACITY}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getDesignedCapacityChannel() {
		return this.channel(ChannelId.DESIGNED_CAPACITY);
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#DESIGNED_CAPACITY}.
	 *
	 * @param value the next value
	 */
	public default void _setDesignedCapacity(Integer value) {
		this.getDesignedCapacityChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#DC_VOLTAGE}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getDcVoltageChannel() {
		return this.channel(ChannelId.DC_VOLTAGE);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#DC_VOLTAGE}.
	 *
	 * @param value the next value
	 */
	public default void _setDcVoltage(Integer value) {
		this.getDcVoltageChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#DC_CURRENT}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getDcCurrentChannel() {
		return this.channel(ChannelId.DC_CURRENT);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#DC_CURRENT}.
	 *
	 * @param value the next value
	 */
	public default void _setDcCurrent(Integer value) {
		this.getDcCurrentChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#BATTERY_MANUFACTURER}.
	 *
	 * @return the Channel
	 */
	public default StringReadChannel getBatteryManufacturerChannel() {
		return this.channel(ChannelId.BATTERY_MANUFACTURER);
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#BATTERY_MANUFACTURER}.
	 *
	 * @param value the next value
	 */
	public default void _setBatteryManufacturer(String value) {
		this.getBatteryManufacturerChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#BATTERY_MODEL}.
	 *
	 * @return the Channel
	 */
	public default StringReadChannel getBatteryModelChannel() {
		return this.channel(ChannelId.BATTERY_MODEL);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#BATTERY_MODEL}.
	 *
	 * @param value the next value
	 */
	public default void _setBatteryModel(String value) {
		this.getBatteryModelChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#BATTERY_SERIAL}.
	 *
	 * @return the Channel
	 */
	public default StringReadChannel getBatterySerialChannel() {
		return this.channel(ChannelId.BATTERY_SERIAL);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#BATTERY_SERIAL}.
	 *
	 * @param value the next value
	 */
	public default void _setBatterySerial(String value) {
		this.getBatterySerialChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#FIRMWARE_VERSION}.
	 *
	 * @return the Channel
	 */
	public default StringReadChannel getFirmwareVersionChannel() {
		return this.channel(ChannelId.FIRMWARE_VERSION);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#FIRMWARE_VERSION}.
	 *
	 * @param value the next value
	 */
	public default void _setFirmwareVersion(String value) {
		this.getFirmwareVersionChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#LAST_CONTROL_ACTION}.
	 *
	 * @return the Channel
	 */
	public default StringReadChannel getLastControlActionChannel() {
		return this.channel(ChannelId.LAST_CONTROL_ACTION);
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#LAST_CONTROL_ACTION}.
	 *
	 * @param value the next value
	 */
	public default void _setLastControlAction(String value) {
		this.getLastControlActionChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#SET_MAX_SOC}.
	 *
	 * @return the Channel
	 */
	public default IntegerWriteChannel getSetMaxSocChannel() {
		return this.channel(ChannelId.SET_MAX_SOC);
	}

	/**
	 * Gets the last confirmed max-SoC setpoint. See {@link ChannelId#SET_MAX_SOC}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getSetMaxSoc() {
		return this.getSetMaxSocChannel().value();
	}

	/**
	 * Sets a new max-SoC setpoint (0-100 %) to be written to the device. See
	 * {@link ChannelId#SET_MAX_SOC}.
	 *
	 * @param value the next write value
	 * @throws io.openems.common.exceptions.OpenemsError.OpenemsNamedException on
	 *                                                                             error
	 */
	public default void setMaxSoc(Integer value) throws io.openems.common.exceptions.OpenemsError.OpenemsNamedException {
		this.getSetMaxSocChannel().setNextWriteValue(value);
	}
}
