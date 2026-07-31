package io.openems.edge.pvinverter.hoymiles.opendtu;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import io.openems.common.types.MeterType;

@ObjectClassDefinition(//
		name = "Hoymiles PV-Inverter (OpenDTU JSON)", //
		description = "Reads one or all Hoymiles microinverter(s) behind an OpenDTU device via its JSON/HTTP web "
				+ "API - no Modbus required. If 'Inverter-Serial' is left empty, this Component reads OpenDTU's "
				+ "aggregate endpoint (sum of ALL connected inverters: Power/YieldDay/YieldTotal only - OpenDTU "
				+ "does not report Voltage/Current/Frequency in aggregate mode). If 'Inverter-Serial' is set, this "
				+ "Component reads the detail endpoint for exactly that one inverter, which additionally provides "
				+ "AC Voltage/Current/Frequency. Create one Component instance per desired view (e.g. one "
				+ "aggregate instance for the whole Hoymiles fleet, or one instance per inverter if you need "
				+ "per-inverter AC values).")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "pvInverterHoymiles0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "IP-Address", //
			description = "IP address of the OpenDTU device, e.g. 192.168.188.20")
	String ip();

	@AttributeDefinition(name = "Inverter-Serial", //
			description = "Serial number of a single Hoymiles inverter (as shown in the OpenDTU web UI, e.g. "
					+ "114172xxxxxx). Leave EMPTY to read the aggregate of ALL inverters connected to this OpenDTU "
					+ "instead (no per-inverter Voltage/Current/Frequency in that case). Test in the browser: "
					+ "http://<opendtu-ip>/api/livedata/status (aggregate) or "
					+ "http://<opendtu-ip>/api/livedata/status?inv=<serial> (single inverter).")
	String inverterSerial() default "";

	@AttributeDefinition(name = "Meter-Type", //
			description = "Fest auf PRODUCTION, da ein PV-Wechselrichter immer nur erzeugt (OpenDTU liefert hier "
					+ "keine Netz-/Verbrauchsdaten). Bewusst als echtes Config-Attribut (nicht nur als im "
					+ "Java-Code hartkodierter getMeterType()-Rueckgabewert) modelliert - siehe die entsprechende "
					+ "Begruendung im Fronius-PV-Inverter-Bundle (Config.java bzw. Impl-Klasse), die identisch "
					+ "gilt.")
	MeterType type() default MeterType.PRODUCTION;

	@AttributeDefinition(name = "Benutzername (Basic-Auth)", //
			description = "Nur notwendig, falls in OpenDTU unter Settings -> Security die Option 'Enable "
					+ "read-only access' DEAKTIVIERT ist - dann verlangt OpenDTU fuer JEDEN Endpunkt, auch "
					+ "/api/livedata/status, eine HTTP-Basic-Auth. Leer lassen, wenn 'read-only access' aktiv ist "
					+ "(OpenDTU-Standardeinstellung) oder kein Passwortschutz gewuenscht ist. OpenDTU-Standard-"
					+ "Benutzername ist 'admin'.")
	String username() default "";

	@AttributeDefinition(name = "Passwort (Basic-Auth)", //
			description = "Siehe 'Benutzername (Basic-Auth)'. OpenDTU-Standardpasswort ist 'openDTU42', sofern "
					+ "nicht geaendert.", type = AttributeType.PASSWORD)
	String password() default "";

	@AttributeDefinition(name = "Poll every N Cycles", //
			description = "How often (in multiples of the OpenEMS Cycle-Time, usually 1s) OpenDTU should be "
					+ "queried. OpenDTU itself only refreshes inverter data every few seconds (radio round-trip "
					+ "to the Hoymiles inverter), so polling faster than that brings no benefit. If the device "
					+ "can't keep up you will see harmless 'Task is not queued twice' INFO log messages; increase "
					+ "this value to reduce load. 1 = every Cycle.")
	int pollEveryCycles() default 3;

	String webconsole_configurationFactory_nameHint() default "Hoymiles PV-Inverter (OpenDTU JSON) [{id}]";
}
