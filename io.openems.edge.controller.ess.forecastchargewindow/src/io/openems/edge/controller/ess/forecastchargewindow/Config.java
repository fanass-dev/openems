package io.openems.edge.controller.ess.forecastchargewindow;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import io.openems.common.types.EntsoeBiddingZone;

@ObjectClassDefinition(//
		name = "Controller Ess Forecast Charge Window", //
		description = "Blockiert die Ladeleistung eines Speichers waehrend eines konfigurierbaren Zeitfensters "
				+ "(JSCalendar, z. B. 'Sommermonate vor 12 Uhr'), ausser einer von zwei unabhaengigen Gruenden "
				+ "hebt die Blockade vorzeitig auf: (1) taeglich einmal geprueft, PV-Prognose ab einer "
				+ "konfigurierten Uhrzeit laesst nur noch wenig Ertrag erwarten - Aufhebung gilt dann fuer den "
				+ "Rest des Tages; (2) fortlaufend (jeder Cycle) geprueft, aktueller Day-Ahead-Boersenpreis "
				+ "(direkt von der ENTSO-E Transparency Platform, unabhaengig von TariffManager/TariffGridSell) "
				+ "ist negativ - Aufhebung gilt nur, solange der Preis negativ bleibt, und wird automatisch "
				+ "wieder zurueckgenommen, sobald der Preis zurueck ins Positive dreht (ausser Grund 1 greift zu "
				+ "diesem Zeitpunkt ebenfalls). Ausserhalb des konfigurierten Zeitfensters ist grundsaetzlich "
				+ "nicht blockiert. Ohne belastbare Prognose bzw. ohne gueltige ENTSO-E-Zugangsdaten wird davon "
				+ "ausgegangen, dass geladen werden soll (fail-open) - ein Ausfall der Internetverbindung darf "
				+ "das Laden nicht unterbinden. Setzt die Ladebegrenzung direkt als Power-Constraint auf den "
				+ "konfigurierten Speicher, ohne einen zweiten Controller zu benoetigen/zu steuern.")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "ctrlForecastChargeWindow0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Ess-ID", description = "Component-ID des Speichers (ManagedSymmetricEss), dessen "
			+ "Ladeleistung begrenzt wird.")
	String ess_id();

	@AttributeDefinition(name = "Blockier-Zeitfenster (JSCalendar)", //
			description = "JSON-Array im JSCalendar-Format (wie beim Scheduler.JSCalendar), das definiert, "
					+ "wann die Ladeleistung ueberhaupt begrenzt werden soll (z. B. taeglich 00:00-12:00 Uhr, "
					+ "nur Mai bis September). Ausserhalb der hier definierten Zeitfenster wird nie blockiert, "
					+ "unabhaengig von Prognose oder Preis. Leeres Array ('[]') bedeutet: nie blockieren.")
	String jsCalendar() default "[]";

	@AttributeDefinition(name = "Validate applied power Constraints", //
			description = "If this property is 'false' the limitation is not validated. Only disable if you "
					+ "know what you are doing. This can break the system!")
	boolean validatePowerConstraints() default true;

	@AttributeDefinition(name = "Produktions-Prognose-Channel", //
			description = "Channel-ID auf '_sum', unter der die Produktionsprognose abgefragt wird - muss mit "
					+ "dem 'Source Channel' des verwendeten Predictors uebereinstimmen (z. B. "
					+ "Predictor.Production.LinearModel), sonst wird keine Prognose gefunden.")
	String productionChannelId() default "UnmanagedProductionActivePower";

	@AttributeDefinition(name = "Beginn Nachmittagsfenster", //
			description = "Uhrzeit (HH:mm), ab der die verbleibende PV-Prognose des Tages aufsummiert und "
					+ "bewertet wird - typischerweise das Ende des bisherigen Blockierungsfensters.")
	String afternoonWindowStart() default "12:00";

	@AttributeDefinition(name = "Prognose-Pruefzeit", //
			description = "Uhrzeit (HH:mm), zu der die Prognose einmal taeglich geprueft wird. Fruh genug, um "
					+ "bei schlechter Nachmittagsprognose noch nennenswert Vormittagsertrag nutzen zu koennen, "
					+ "spaet genug fuer eine einigermassen verlaessliche Tagesprognose.")
	String checkTime() default "08:00";

	@AttributeDefinition(name = "Ladeleistung waehrend Blockierung [W]", //
			description = "Max. Ladeleistung, die als Power-Constraint gesetzt wird, solange das Zeitfenster "
					+ "aktiv ist und weder Prognose noch ein negativer Boersenpreis eine Aufhebung rechtfertigen "
					+ "(Normalzustand innerhalb des Zeitfensters). Typischerweise 0.")
	int blockedMaxChargePower() default 0;

	@AttributeDefinition(name = "Ladeleistung nach Aufhebung [W]", //
			description = "Max. Ladeleistung, die als Power-Constraint gesetzt wird, sobald wir uns ausserhalb "
					+ "des Zeitfensters befinden oder einer der beiden Aufhebungsgruende (Prognose oder "
					+ "negativer Preis) zutrifft. Sollte deutlich ueber der tatsaechlichen maximalen "
					+ "Ladeleistung des Speichers liegen, damit er faktisch unbegrenzt laedt.")
	int unblockedMaxChargePower() default 1000000;

	@AttributeDefinition(name = "Schwellwert Restertrag [Wh]", //
			description = "Liegt die aufsummierte PV-Prognose ab 'Beginn Nachmittagsfenster' bis Tagesende "
					+ "unter diesem Wert, gilt der Nachmittag als 'schlecht' und der Ladeblock wird fuer den "
					+ "Rest des Tages aufgehoben. Muss an die eigene Anlagengroesse angepasst werden.")
	int minRemainingProductionWh() default 5000;

	@AttributeDefinition(name = "Bidding Zone", //
			description = "ENTSO-E-Gebotszone, fuer die der Day-Ahead-Preis abgefragt wird.")
	EntsoeBiddingZone biddingZone();

	@AttributeDefinition(name = "Security Token", //
			description = "Security-Token fuer die ENTSO-E Transparency Platform. Bei gleicher Bidding Zone und "
					+ "gleichem Token wie z. B. bei einer bereits konfigurierten Tariff.Manual-Komponente wird "
					+ "intern dieselbe, bereits laufende Abfrage wiederverwendet (kein doppelter API-Zugriff).", //
			type = AttributeType.PASSWORD, required = false)
	String securityToken() default "";

	String webconsole_configurationFactory_nameHint() default "Controller Ess Forecast Charge Window [{id}]";
}
