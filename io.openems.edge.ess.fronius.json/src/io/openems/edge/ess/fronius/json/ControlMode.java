package io.openems.edge.ess.fronius.json;

import io.openems.common.types.OptionsEnum;

/**
 * Selects which (inoffizielle, undokumentierte) Fronius-Mechanismus benutzt
 * wird, um {@link io.openems.edge.ess.api.ManagedSymmetricEss#applyPower}
 * tatsächlich auf das Gerät zu schreiben.
 */
public enum ControlMode implements OptionsEnum {
	/**
	 * Sicherer Standard: keine Schreibzugriffe. Das Bundle verhält sich wie ein
	 * reines {@code SymmetricEss} (nur Beobachtung).
	 */
	READ_ONLY(0, "Nur beobachten (kein Schreibzugriff)"),

	/**
	 * Übersetzt die Leistungsvorgabe in Fronius Time-of-Use-Regeln
	 * ({@code CHARGE_MIN}/{@code DISCHARGE_MAX} über
	 * {@code /api/config/timeofuse}) - eine Ober-/Untergrenze, kein exakter
	 * Momentanwert. Näher am eigentlichen Fronius-Design, empfohlen.
	 */
	SCHEDULE_BASED(1, "Zeitplan-basiert (Time-of-Use)"),

	/**
	 * Schreibt einen Netz-Leistungssollwert ({@code HYB_EM_POWER} über
	 * {@code /api/config/batteries}) für die GESAMTE Anlage (PV+Speicher+Netz),
	 * nicht für die Batterie allein. Der von openEMS vorgegebene
	 * {@code activePower}-Wert wird dabei 1:1 als Netz-Sollwert interpretiert -
	 * nur für erfahrene Nutzer mit eigenem, dafür ausgelegtem Controller.
	 */
	GRID_POWER_TARGET(2, "Netz-Leistungssollwert (HYB_EM_POWER)");

	private final int value;
	private final String name;

	private ControlMode(int value, String name) {
		this.value = value;
		this.name = name;
	}

	@Override
	public int getValue() {
		return this.value;
	}

	@Override
	public String getName() {
		return this.name;
	}

	@Override
	public OptionsEnum getUndefined() {
		return READ_ONLY;
	}
}
