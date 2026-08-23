package io.openems.edge.controller.ess.topofcharge;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
		name = "Controller Ess Top Of Charge", //
		description = "Begrenzt den Speicher-SOC auf einen konfigurierten Hoechstwert (Top-Of-Charge), indem "
				+ "die Ladeleistung per Power-Constraint auf 0 gesetzt wird, sobald der SOC diesen Wert "
				+ "erreicht/ueberschreitet - Entladen bleibt dabei jederzeit unbegrenzt moeglich. Der Max-SOC-Wert "
				+ "kommt aus einem JSCalendar-Zeitplan, dessen Tasks jeweils ihren eigenen Wert als Payload "
				+ "tragen (z. B. ganzjaehrig 100 % - kein Limit -, aber 80 % waehrend der Sommermonate). Deckt "
				+ "kein Task den aktuellen Zeitpunkt ab (z. B. leeres Array), wird bewusst NICHT begrenzt - "
				+ "sicherer Default, analog zu den anderen Controllern hier.")
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

	@AttributeDefinition(name = "Max-SOC-Zeitplan (JSCalendar)", //
			description = "JSON-Array im JSCalendar-Format (wie beim Scheduler.JSCalendar). Jeder Task traegt "
					+ "seinen geltenden Max-SOC-Wert (0-100) als 'openems.io:payload', z. B. "
					+ "'[{\"@type\":\"Task\",\"start\":\"2026-05-01T00:00:00\",\"duration\":\"P1D\","
					+ "\"recurrenceRules\":[{\"frequency\":\"daily\",\"until\":\"2026-09-30\"}],"
					+ "\"openems.io:payload\":80}]'. Ausserhalb aller konfigurierten Zeitraeume (oder bei leerem "
					+ "Array '[]') wird NICHT begrenzt.")
	String jsCalendar() default "[]";

	String webconsole_configurationFactory_nameHint() default "Controller Ess Top Of Charge [{id}]";
}
