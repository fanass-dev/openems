package io.openems.edge.ess.fronius.json;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
		name = "Fronius Battery Storage (JSON / Solar API)", //
		description = "Liest den Batteriespeicher eines Fronius Hybrid-Wechselrichters (GEN24, Symo Hybrid) "
				+ "ueber die Fronius Solar API (GetStorageRealtimeData.cgi) per JSON/HTTP aus - ohne Modbus/"
				+ "SunSpec. Optional (ControlMode != READ_ONLY): steuert den Speicher zusaetzlich ueber die "
				+ "inoffizielle, undokumentierte Fronius Web-Config-API (Digest-Auth). Diese Schreibfunktion "
				+ "ist experimentell, nicht von Fronius dokumentiert und kann sich mit Firmware-Updates aendern "
				+ "- vor Produktivbetrieb sorgfaeltig testen.")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "ess0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "IP-Address", //
			description = "IP address of the Fronius Hybrid Inverter (GEN24 or Symo Hybrid Datamanager), e.g. 192.168.188.10")
	String ip();

	@AttributeDefinition(name = "Device-ID", //
			description = "Solar API DeviceId of the storage controller (usually \"0\"). Test in the browser: "
					+ "http://<ip>/solar_api/v1/GetStorageRealtimeData.cgi?Scope=Device&DeviceId=0")
	String deviceId() default "0";

	@AttributeDefinition(name = "Poll every N Cycles", //
			description = "Wie oft (in Vielfachen der OpenEMS-Cycle-Time) der Speicher-Zustand (SoC, Leistung, "
					+ "Zellwerte) gelesen wird. Betrifft nur das Lesen, nicht die Steuerung. 1 = jeden Cycle.")
	int pollEveryCycles() default 3;

	@AttributeDefinition(name = "Steuerungsmodus", //
			description = "READ_ONLY: keine Schreibzugriffe, reine Beobachtung (sicherer Standard). "
					+ "SCHEDULE_BASED: steuert ueber Fronius Time-of-Use-Zeitplaene (CHARGE_MIN erzwingt "
					+ "Netzladen, DISCHARGE_MAX begrenzt/sperrt Entladen) - naeher am Fronius-Design, empfohlen "
					+ "fuer normale Ess-Controller. GRID_POWER_TARGET: setzt einen Netz-Leistungssollwert "
					+ "(HYB_EM_POWER) fuer die GESAMTE Anlage (PV+Speicher+Netz), nicht nur die Batterie - der "
					+ "ActivePower-Sollwert wird dabei 1:1 als Netz-Zielwert interpretiert; nur fuer erfahrene "
					+ "Nutzer mit eigenem, dafuer ausgelegtem Controller.")
	ControlMode controlMode() default ControlMode.READ_ONLY;

	@AttributeDefinition(name = "Benutzername (Service-Account)", //
			description = "Login fuer die GEN24-Weboberflaeche, nur fuer Schreibzugriffe (Steuerung) benoetigt. "
					+ "Standard-Vorgabe von Fronius ist 'customer'.")
	String username() default "customer";

	@AttributeDefinition(name = "Passwort", //
			description = "Passwort des Service-Accounts. Nur fuer Schreibzugriffe (Steuerung) benoetigt.", //
			type = AttributeType.PASSWORD)
	String password() default "";

	@AttributeDefinition(name = "Netzladen erlauben (nur SCHEDULE_BASED)", //
			description = "Steuert unabhaengig vom sonstigen Sollwert das Fronius-Flag HYB_EVU_CHARGEFROMGRID. "
					+ "false (Standard, z. B. fuer den Sommer/Fruehjahr-Herbst-Betrieb mit Controller.Ess.GridOptimizedCharge): "
					+ "CHARGE_MIN wirkt nur als Kappung des PV-Ueberschusses, es wird NIE zusaetzlich aus dem Netz geladen, "
					+ "auch wenn der berechnete Sollwert kurzfristig nicht allein aus PV gedeckt werden kann. true (z. B. fuer "
					+ "den Winterbetrieb mit Controller.Ess.Time-Of-Use-Tariff im Modus CHARGE_CONSUMPTION): CHARGE_MIN darf "
					+ "den Sollwert auch durch Netzbezug erzwingen (z. B. nachts bei guenstigem Tarif ohne PV). Diese beiden "
					+ "Betriebsarten aktuell manuell umschalten (z. B. per Config-Wechsel im Fruehjahr/Herbst); eine "
					+ "automatische, jahreszeit- oder KI-basierte Umschaltung ist ein moeglicher spaeterer Ausbauschritt.")
	boolean allowGridCharging() default false;

	@AttributeDefinition(name = "Totzone fuer Schreibvorgaenge [W]", //
			description = "Erst wenn sich der angeforderte Sollwert um mehr als diesen Betrag gegenueber dem "
					+ "zuletzt geschriebenen Wert aendert, wird ein neuer Schreibbefehl gesendet.")
	int writeDeadbandWatt() default 100;

	@AttributeDefinition(name = "Mindestabstand zwischen Schreibvorgaengen [s]", //
			description = "Auch bei staendig wechselndem Sollwert wird hoechstens alle X Sekunden tatsaechlich "
					+ "an den Wechselrichter geschrieben (schont die Weboberflaeche des GEN24).")
	int minWriteIntervalSeconds() default 15;

	@AttributeDefinition(name = "Batterie: max. Ladeleistung [W]", //
			description = "Wird als AllowedChargePower-Kanal an den OpenEMS-Power-Solver gemeldet. OHNE diesen "
					+ "Wert (bzw. bei 0) zwingt der Solver den Sollwert fuer diesen Speicher IMMER auf 0 W, "
					+ "unabhaengig davon, was ein Controller (z. B. Balancing) eigentlich anfordert - siehe "
					+ "readme.adoc. Vorzeichen wird intern erzwungen (Laden = negativ), es kann also ein "
					+ "positiver oder negativer Betrag eingetragen werden. Richtwert lt. Fronius-Datenblatt "
					+ "'Symo GEN24 12.0 Plus SC' (Feld 'Max. AC charging power, depending on connected "
					+ "battery'): 11682 W - das ist bei einer BYD Battery-Box Premium HVM/HVS die tatsaechlich "
					+ "bindende Grenze (der Wechselrichter begrenzt staerker als die Batterie selbst, deren "
					+ "Strombegrenzung von 50 A bei ca. 307 V nominal eher rund 15 kW zulaesst). Unbedingt am "
					+ "eigenen Typenschild/Datenblatt pruefen und bei Abweichung anpassen - NICHT ungeprueft "
					+ "uebernehmen.")
	int batteryMaxChargePowerWatt() default 11682;

	@AttributeDefinition(name = "Batterie: max. Entladeleistung [W]", //
			description = "Wird als AllowedDischargePower-Kanal an den OpenEMS-Power-Solver gemeldet - siehe "
					+ "Beschreibung bei 'max. Ladeleistung' fuer die Begruendung, warum dieser Wert zwingend "
					+ "erforderlich ist. Richtwert lt. Fronius-Datenblatt 'Symo GEN24 12.0 Plus SC' (Feld 'Max. "
					+ "output power (Pac max)'): 12000 W. Unbedingt am eigenen Typenschild/Datenblatt pruefen "
					+ "und bei Abweichung anpassen - NICHT ungeprueft uebernehmen.")
	int batteryMaxDischargePowerWatt() default 12000;

	@AttributeDefinition(name = "Wechselrichter: max. Scheinleistung [VA]", //
			description = "Wird als MaxApparentPower-Kanal an den OpenEMS-Power-Solver gemeldet (weiterer, "
					+ "vom Solver benoetigter Grenzwert). Richtwert lt. Fronius-Datenblatt 'Symo GEN24 12.0 Plus "
					+ "SC' (Feld 'Max. output power'): 12000 VA. Unbedingt am eigenen Typenschild/Datenblatt "
					+ "pruefen und bei Abweichung anpassen.")
	int maxApparentPowerVoltAmpere() default 12000;

	String webconsole_configurationFactory_nameHint() default "Fronius Battery Storage (JSON) [{id}]";
}
