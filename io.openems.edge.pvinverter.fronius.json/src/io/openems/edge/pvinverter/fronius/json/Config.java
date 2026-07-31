package io.openems.edge.pvinverter.fronius.json;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import io.openems.common.types.MeterType;

@ObjectClassDefinition(//
		name = "Fronius PV-Inverter (JSON / Solar API)", //
		description = "Reads a Fronius PV-Inverter - GEN24 or Symo, hybrid or plain string-inverter - via the "
				+ "Fronius Solar API over JSON/HTTP - no Modbus required. ActivePower comes from "
				+ "GetPowerFlowRealtimeData.fcgi (Site.P_PV, the true DC-side PV yield); everything else "
				+ "(Voltage/Current/Frequency, energy counters) comes from GetInverterRealtimeData.cgi "
				+ "(DataCollection=CommonInverterData). On a hybrid inverter with a DC-coupled battery, PAC "
				+ "from CommonInverterData alone would net out battery charging and under-report production "
				+ "- see the Impl class Javadoc. Create one Component instance per physical inverter/"
				+ "installation, e.g. one instance for the GEN24 of Anlage 1 and a second instance (different "
				+ "IP, own Component-ID) for the Symo of Anlage 2.")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "pvInverter0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "IP-Address", //
			description = "IP address of the Fronius Inverter (GEN24 or Symo Datamanager), e.g. 192.168.188.10")
	String ip();

	@AttributeDefinition(name = "Device-ID", //
			description = "Solar API DeviceId of the inverter (usually \"1\" for the first/only inverter on this "
					+ "Datamanager). Test in the browser: "
					+ "http://<ip>/solar_api/v1/GetInverterRealtimeData.cgi?Scope=Device&DeviceId=1&DataCollection=CommonInverterData")
	String deviceId() default "1";

	@AttributeDefinition(name = "Meter-Type", //
			description = "Fest auf PRODUCTION, da ein PV-Wechselrichter immer nur erzeugt (die Fronius Solar API "
					+ "liefert hier keine Netz-/Verbrauchsdaten). Bewusst als echtes Config-Attribut (nicht nur als "
					+ "im Java-Code hartkodierter getMeterType()-Rueckgabewert) modelliert: Die OpenEMS-UI liest den "
					+ "Meter-Typ fuer die Produktions-Kachel im Energiemonitor aus dem persistierten "
					+ "Component-Property 'type' der EdgeConfig - ein rein zur Laufzeit ueberschriebenes "
					+ "getMeterType() wird dort wegen eines bekannten Apache-Felix-SCR-Verhaltens nicht "
					+ "zuverlaessig sichtbar (vgl. ui/src/app/shared/edge/edgeconfig.ts, isProducer()).")
	MeterType type() default MeterType.PRODUCTION;

	@AttributeDefinition(name = "Poll every N Cycles", //
			description = "How often (in multiples of the OpenEMS Cycle-Time, usually 1s) the inverter should be "
					+ "queried. If the device can't keep up with every-Cycle polling you will see harmless "
					+ "'Task is not queued twice' INFO log messages; increase this value (e.g. 3-5) to reduce "
					+ "load. 1 = every Cycle.")
	int pollEveryCycles() default 3;

	String webconsole_configurationFactory_nameHint() default "Fronius PV-Inverter (JSON) [{id}]";
}
