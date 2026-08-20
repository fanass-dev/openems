package io.openems.edge.controller.ess.topofcharge;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
		name = "Controller Ess Top Of Charge", //
		description = "Begrenzt den Speicher-SOC auf einen konfigurierten Hoechstwert (Top-Of-Charge), indem "
				+ "die Ladeleistung per Power-Constraint auf 0 gesetzt wird, sobald der SOC diesen Wert "
				+ "erreicht/ueberschreitet - Entladen bleibt dabei jederzeit unbegrenzt moeglich. Erste, feste "
				+ "Variante ohne zeitliche Steuerung (z. B. nur in bestimmten Monaten geltend); das ist als "
				+ "spaetere Erweiterung vorgesehen, aber hier noch nicht umgesetzt.")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "ctrlTopOfCharge0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Ess-ID", //
			description = "Component-ID des Speichers (ManagedSymmetricEss), dessen SOC begrenzt wird.")
	String ess_id();

	@AttributeDefinition(name = "Max-SOC [%]", //
			description = "Sobald der SOC diesen Wert erreicht/ueberschreitet, wird die Ladeleistung per "
					+ "Power-Constraint auf 0 begrenzt (Entladen bleibt frei). Default 100 % - wirkt dann faktisch "
					+ "nie, damit eine neu angelegte Instanz ohne explizit gesetzten Wert nicht ungewollt "
					+ "einschraenkt.")
	int maxSoc() default 100;

	String webconsole_configurationFactory_nameHint() default "Controller Ess Top Of Charge [{id}]";
}
