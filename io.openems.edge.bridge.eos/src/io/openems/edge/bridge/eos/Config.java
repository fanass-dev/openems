package io.openems.edge.bridge.eos;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
		name = "Bridge EOS", //
		description = "Generic HTTP-Bridge fuer den Akkudoktor-EOS-REST-Server (https://github.com/Akkudoktor-EOS/EOS). "
				+ "Kennt nur die Basis-URL und den rohen 'PUT /v1/measurement/value'-Aufruf - welche OpenEMS-Kanaele "
				+ "auf welche EOS-Measurement-Keys abgebildet werden, entscheidet eine separate Komponente "
				+ "(z. B. Controller.Api.Eos.Measurement), die diese Bridge referenziert.")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "bridgeEos0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "EOS Basis-URL", //
			description = "Basis-URL des EOS-REST-Servers, ohne abschliessenden Schraegstrich, "
					+ "z. B. http://192.168.1.50:8503", //
			required = true)
	String baseUrl();

	String webconsole_configurationFactory_nameHint() default "Bridge EOS [{id}]";
}
