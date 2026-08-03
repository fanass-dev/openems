package io.openems.edge.evcs.goe.geminimanaged;

import org.osgi.service.event.EventHandler;

import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.StringReadChannel;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.evcs.api.Evcs;
import io.openems.edge.evcs.api.ManagedEvcs;

public interface EvcsGoeGeminiManaged extends ManagedEvcs, Evcs, OpenemsComponent, EventHandler {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		FIRMWARE_VERSION(Doc.of(OpenemsType.STRING) //
				.text("Firmware-Version des go-e Gemini (Feld 'fwv')")), //
		CURR_USER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.MILLIAMPERE) //
				.text("Zuletzt vom Geraet bestaetigter Ladestrom (Feld 'amp')")), //
		GOE_CAR_STATE(Doc.of(OpenemsType.INTEGER) //
				.text("Rohwert des go-e-Feldes 'car' (0=Unknown/Error, 1=Idle, 2=Charging, 3=WaitCar, "
						+ "4=Complete, 5=Error, 6=Initializing)")), //
		GOE_ERROR(Doc.of(OpenemsType.INTEGER) //
				.text("Rohwert des go-e-Feldes 'err' (Fehlercode, 0/undefined = kein Fehler)")), //
		ALLOWED_TO_CHARGE(Doc.of(OpenemsType.BOOLEAN) //
				.text("Geraeteseitiger Status, ob gerade geladen werden darf (Feld 'alw')")), //
		LAST_WRITE_FAILED(Doc.of(OpenemsType.BOOLEAN) //
				.text("true, wenn der letzte Schreibbefehl (amp/frc) fehlgeschlagen ist"));

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
	 * Gets the Channel for {@link ChannelId#CURR_USER}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getCurrUserChannel() {
		return this.channel(ChannelId.CURR_USER);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#CURR_USER}.
	 *
	 * @param value the next value
	 */
	public default void _setCurrUser(Integer value) {
		this.getCurrUserChannel().setNextValue(value);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#GOE_CAR_STATE}.
	 *
	 * @param value the next value
	 */
	public default void _setGoeCarState(Integer value) {
		this.channel(ChannelId.GOE_CAR_STATE).setNextValue(value);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#GOE_ERROR}.
	 *
	 * @param value the next value
	 */
	public default void _setGoeError(Integer value) {
		this.channel(ChannelId.GOE_ERROR).setNextValue(value);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#ALLOWED_TO_CHARGE}.
	 *
	 * @param value the next value
	 */
	public default void _setAllowedToCharge(Boolean value) {
		this.channel(ChannelId.ALLOWED_TO_CHARGE).setNextValue(value);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#LAST_WRITE_FAILED}.
	 *
	 * @param value the next value
	 */
	public default void _setLastWriteFailed(boolean value) {
		this.channel(ChannelId.LAST_WRITE_FAILED).setNextValue(value);
	}
}
