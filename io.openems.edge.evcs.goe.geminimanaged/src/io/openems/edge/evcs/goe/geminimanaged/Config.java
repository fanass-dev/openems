package io.openems.edge.evcs.goe.geminimanaged;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(name = "EVCS go-e Gemini Managed (JSON API v2)", //
		description = "Steuert einen go-e Gemini Ladepunkt aktiv ueber die inoffizielle, aber "
				+ "oeffentlich dokumentierte go-e-API-v2 (https://github.com/goecharger/go-eCharger-API-v2, "
				+ "Endpunkte /api/status und /api/set). Eigenstaendiges Bundle, unabhaengig vom "
				+ "Nur-Lese-Bundle 'Evcs.Goe.Http' (io.openems.edge.evcs.goe), das fuer die Gemini-Baureihe "
				+ "kein ManagedEvcs implementiert. Schreibzugriffe sind bewusst gedrosselt "
				+ "('Mindestabstand zwischen Schreibvorgaengen'), da es Berichte gibt, dass aeltere "
				+ "go-e-Firmwareversionen nicht fuer dauerhaftes, hochfrequentes Beschreiben ausgelegt waren.")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "evcs1";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Debug Mode", description = "Activates the debug mode")
	boolean debugMode() default false;

	@AttributeDefinition(name = "IP-Address", description = "Die IP-Adresse des go-e Gemini Ladepunkts.", required = true)
	String ip();

	@AttributeDefinition(name = "Read only", //
			description = "Solange aktiviert (Standard), werden Status/Messwerte gelesen, aber niemals "
					+ "Schreibbefehle (amp/frc) an die Wallbox gesendet - sicherer Standard, um die Komponente "
					+ "erst beobachten zu koennen, bevor sie tatsaechlich steuert. Muss bewusst auf 'false' "
					+ "gesetzt werden, damit PV-Ueberschussladen ueber Controller.Evcs tatsaechlich wirkt.")
	boolean readOnly() default true;

	@AttributeDefinition(name = "Minimaler Ladestrom [mA]", //
			description = "Minimaler Ladestrom, den die Wallbox/das Fahrzeug unterstuetzt. Unterhalb dieses "
					+ "Werts wird komplett pausiert statt mit sehr geringem Strom geladen.")
	int minHwCurrent() default 6000;

	@AttributeDefinition(name = "Maximaler Ladestrom [mA]", //
			description = "Maximaler Ladestrom, den die Wallbox/Hausanschluss/Fahrzeug unterstuetzt.")
	int maxHwCurrent() default 32000;

	@AttributeDefinition(name = "Status abfragen alle N Cycles", //
			description = "Wie oft (in Vielfachen der OpenEMS-Cycle-Time) der Ladepunkt-Status per HTTP GET "
					+ "abgefragt wird. Betrifft nur das Lesen, nicht das Schreiben.")
	int pollEveryCycles() default 3;

	@AttributeDefinition(name = "Mindestabstand zwischen Schreibvorgaengen [s]", //
			description = "Auch bei staendig wechselndem Sollwert wird hoechstens alle X Sekunden tatsaechlich "
					+ "ein Schreibbefehl an die Wallbox gesendet, solange sich der Sollwert nicht wesentlich "
					+ "aendert - schont die Hardware (siehe Beschreibung oben zu Firmware-Berichten).")
	int writeIntervalSeconds() default 30;

	@AttributeDefinition(name = "Automatik: Umschalt-Schwellwert [W]", //
			description = "Nur relevant im Phasen-Steuerungsmodus 'Automatic'. Ab diesem PV-Ueberschuss wird "
					+ "(nach Ablauf der Hoch-/Runterschalt-Dauer) auf 3-phasig geschaltet bzw. bei "
					+ "Unterschreitung zurueck auf 1-phasig - derselbe Wert fuer beide Richtungen.")
	int phaseSwitchThresholdWatt() default 5000;

	@AttributeDefinition(name = "Automatik: Hochschalt-Dauer [s]", //
			description = "Nur relevant im Phasen-Steuerungsmodus 'Automatic'. Der PV-Ueberschuss muss diese "
					+ "Dauer lang durchgehend ueber dem Schwellwert liegen, bevor tatsaechlich auf 3-phasig "
					+ "umgeschaltet wird.")
	int phaseSwitchUpDurationSeconds() default 300;

	@AttributeDefinition(name = "Automatik: Runterschalt-Dauer [s]", //
			description = "Nur relevant im Phasen-Steuerungsmodus 'Automatic'. Der PV-Ueberschuss muss diese "
					+ "Dauer lang durchgehend unter dem Schwellwert liegen, bevor zurueck auf 1-phasig "
					+ "umgeschaltet wird. Bewusst laenger als die Hochschalt-Dauer, damit kurze Wolkenschatten "
					+ "nicht sofort zum Zurueckschalten fuehren.")
	int phaseSwitchDownDurationSeconds() default 600;

	String webconsole_configurationFactory_nameHint() default "EVCS go-e Gemini Managed [{id}]";
}
