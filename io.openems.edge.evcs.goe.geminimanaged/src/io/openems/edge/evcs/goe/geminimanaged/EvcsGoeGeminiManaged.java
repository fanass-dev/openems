package io.openems.edge.evcs.goe.geminimanaged;

import org.osgi.service.event.EventHandler;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.channel.StringReadChannel;
import io.openems.edge.common.channel.WriteChannel;
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
				.text("true, wenn der letzte Schreibbefehl (amp/frc) fehlgeschlagen ist")), //
		MODEL_STATUS(Doc.of(OpenemsType.INTEGER) //
				.text("Rohwert des go-e-Feldes 'modelStatus' - Grund, warum aktuell (nicht) geladen wird, "
						+ "z. B. 3=ChargingBecauseForceStateOn, 24=NotChargingBecauseMinPauseDuration "
						+ "(waehrend/nach einem Phasenwechsel) - vollstaendige Liste in der go-e API-v2-Doku")), //
		PHASE_SWITCH_MODE(Doc.of(OpenemsType.INTEGER) //
				.text("Vom Geraet zuletzt bestaetigter Wert des go-e-Feldes 'psm' (0=Automatisch, "
						+ "1=Einphasig erzwungen, 2=Dreiphasig erzwungen)")), //
		SET_PHASE_SWITCH_MODE(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_WRITE) //
				.text("Schreibt das go-e-Feld 'psm' (0=Automatisch, 1=Einphasig erzwingen, "
						+ "2=Dreiphasig erzwingen) - die Geraet uebernimmt die noetige Ladepause/Wartezeit "
						+ "beim Umschalten selbststaendig, siehe readme.adoc")), //
		PHASE_CONTROL_MODE(Doc.of(PhaseControlMode.values()) //
				.text("Aktueller OpenEMS-seitiger Phasen-Steuerungsmodus - 'Automatic' bedeutet die eigene "
						+ "PV-Ueberschuss-/Zeit-Hysterese-Logik dieser Komponente, NICHT den geraeteeigenen "
						+ "psm=0-Automatikmodus")), //
		SET_PHASE_CONTROL_MODE(Doc.of(PhaseControlMode.values()) //
				.accessMode(AccessMode.READ_WRITE) //
				.text("Setzt den Phasen-Steuerungsmodus. Diese Komponente ist die einzige Stelle, die "
						+ "tatsaechlich SET_PHASE_SWITCH_MODE beschreibt - bei 'Force 1-phase'/'Force 3-phase' "
						+ "wird das sofort einmalig umgesetzt, bei 'Automatic' jeden Cycle neu anhand von "
						+ "PV-Ueberschuss und den konfigurierten Hysterese-Zeiten entschieden.")), //
		PHASE_AUTOMATIC_LAST_DECISION(Doc.of(OpenemsType.STRING) //
				.text("Nur im Modus 'Automatic' aktuell: Klartext-Beschreibung des zuletzt berechneten "
						+ "Ueberschusses (inkl. verwendeter Prioritaets-Formel) und der Hysterese-Entscheidung"));

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

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#MODEL_STATUS}.
	 *
	 * @param value the next value
	 */
	public default void _setModelStatus(Integer value) {
		this.channel(ChannelId.MODEL_STATUS).setNextValue(value);
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#PHASE_SWITCH_MODE}.
	 *
	 * @param value the next value
	 */
	public default void _setPhaseSwitchMode(Integer value) {
		this.channel(ChannelId.PHASE_SWITCH_MODE).setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#SET_PHASE_SWITCH_MODE}.
	 *
	 * @return the Channel
	 */
	public default IntegerWriteChannel getSetPhaseSwitchModeChannel() {
		return this.channel(ChannelId.SET_PHASE_SWITCH_MODE);
	}

	/**
	 * Gets the Channel for {@link ChannelId#PHASE_CONTROL_MODE}.
	 *
	 * @return the Channel
	 */
	public default Channel<PhaseControlMode> getPhaseControlModeChannel() {
		return this.channel(ChannelId.PHASE_CONTROL_MODE);
	}

	/**
	 * Gets the current {@link ChannelId#PHASE_CONTROL_MODE}.
	 *
	 * @return the value
	 */
	public default PhaseControlMode getPhaseControlMode() {
		return this.getPhaseControlModeChannel().value().asEnum();
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#PHASE_CONTROL_MODE}.
	 *
	 * @param value the next value
	 */
	public default void _setPhaseControlMode(PhaseControlMode value) {
		this.getPhaseControlModeChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#SET_PHASE_CONTROL_MODE}.
	 *
	 * <p>
	 * Deliberately {@code WriteChannel<Integer>}, not
	 * {@code WriteChannel<PhaseControlMode>} or the concrete
	 * {@code IntegerWriteChannel}/{@code EnumWriteChannel} classes:
	 * {@link EnumDoc#createChannelInstance} actually instantiates
	 * {@code EnumWriteChannel implements WriteChannel<Integer>} for a
	 * read-write enum Doc - casting the return of {@link #channel} to a
	 * concrete class that does not match (e.g. {@code IntegerWriteChannel})
	 * throws a {@link ClassCastException} at the cast itself (erasure makes
	 * that check happen for concrete classes, not for generic interface type
	 * parameters). Using the generic {@code WriteChannel<Integer>} interface
	 * here avoids that entirely - {@link WriteChannel#getNextWriteValue()}
	 * genuinely returns a raw {@link Integer} (not auto-converted, unlike the
	 * read side's {@code Value#asEnum()}, see {@link #getPhaseControlMode()}).
	 * Callers must convert explicitly, e.g. via
	 * {@code OptionsEnum.getOptionOrUndefined(PhaseControlMode.class, value)}.
	 *
	 * @return the Channel
	 */
	public default WriteChannel<Integer> getSetPhaseControlModeChannel() {
		return this.channel(ChannelId.SET_PHASE_CONTROL_MODE);
	}

	/**
	 * Gets the Channel for {@link ChannelId#PHASE_AUTOMATIC_LAST_DECISION}.
	 *
	 * @return the Channel
	 */
	public default StringReadChannel getPhaseAutomaticLastDecisionChannel() {
		return this.channel(ChannelId.PHASE_AUTOMATIC_LAST_DECISION);
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#PHASE_AUTOMATIC_LAST_DECISION}.
	 *
	 * @param value the next value
	 */
	public default void _setPhaseAutomaticLastDecision(String value) {
		this.getPhaseAutomaticLastDecisionChannel().setNextValue(value);
	}
}
