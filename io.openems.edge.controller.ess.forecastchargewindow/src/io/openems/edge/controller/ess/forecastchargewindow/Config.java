package io.openems.edge.controller.ess.forecastchargewindow;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
		name = "Controller Ess Forecast Charge Window", //
		description = "Hebt den morgendlichen Ladeblock eines Controller.Symmetric.LimitActivePower fuer zwei "
				+ "unabhaengige Gruende auf: (1) taeglich einmal geprueft, PV-Prognose ab einer konfigurierten "
				+ "Uhrzeit (z. B. 12 Uhr) laesst nur noch wenig Ertrag erwarten - Aufhebung gilt dann fuer den "
				+ "Rest des Tages; (2) fortlaufend (jeder Cycle) geprueft, aktueller Boersen-Verkaufspreis "
				+ "(TariffManager/TariffGridSell, z. B. Tariff.Manual.EEG2025.GridSell) ist negativ - Aufhebung "
				+ "gilt nur, solange der Preis negativ bleibt, und wird automatisch wieder zurueckgenommen, "
				+ "sobald der Preis zurueck ins Positive dreht (ausser Grund 1 greift zu diesem Zeitpunkt "
				+ "ebenfalls). Ohne belastbare Prognose bzw. ohne konfigurierten Preis-Provider passiert fuer "
				+ "den jeweiligen Grund nichts, der Block bleibt in diesem Fall wie gewohnt aktiv (fail-safe).")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "ctrlForecastChargeWindow0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Ziel-Controller-ID", //
			description = "Component-ID des Controller.Symmetric.LimitActivePower, dessen 'Max. Ladeleistung' "
					+ "dieser Controller verwaltet.")
	String targetControllerId() default "ctrlLimitActivePower0";

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
			description = "Wert, der als 'Max. Ladeleistung' des Ziel-Controllers gesetzt wird, wenn weder die "
					+ "Prognose noch ein negativer Boersenpreis eine Aufhebung rechtfertigen (Normalzustand). "
					+ "Entspricht dem bisherigen Wert des Ziel-Controllers, typischerweise 0.")
	int blockedMaxChargePower() default 0;

	@AttributeDefinition(name = "Ladeleistung nach Aufhebung [W]", //
			description = "Wert, der als 'Max. Ladeleistung' des Ziel-Controllers gesetzt wird, sobald einer der "
					+ "beiden Gruende (Prognose oder negativer Preis) zutrifft. Sollte deutlich ueber der "
					+ "tatsaechlichen maximalen Ladeleistung des Speichers liegen, damit er faktisch unbegrenzt "
					+ "laedt.")
	int unblockedMaxChargePower() default 1000000;

	@AttributeDefinition(name = "Schwellwert Restertrag [Wh]", //
			description = "Liegt die aufsummierte PV-Prognose ab 'Beginn Nachmittagsfenster' bis Tagesende "
					+ "unter diesem Wert, gilt der Nachmittag als 'schlecht' und der Ladeblock wird fuer den "
					+ "Rest des Tages aufgehoben. Muss an die eigene Anlagengroesse angepasst werden.")
	int minRemainingProductionWh() default 5000;

	String webconsole_configurationFactory_nameHint() default "Controller Ess Forecast Charge Window [{id}]";
}
