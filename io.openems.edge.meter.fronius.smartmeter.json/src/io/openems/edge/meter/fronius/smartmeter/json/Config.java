package io.openems.edge.meter.fronius.smartmeter.json;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import io.openems.common.types.MeterType;

@ObjectClassDefinition(//
		name = "Fronius Smart Meter (JSON / Solar API)", //
		description = "Reads a Fronius Smart Meter attached to a GEN24 via the Fronius Solar API "
				+ "(GetMeterRealtimeData.cgi) over JSON/HTTP - no Modbus required.")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "meter0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "IP-Address", description = "IP address of the Fronius GEN24, e.g. 192.168.1.50")
	String ip();

	@AttributeDefinition(name = "Device-ID", //
			description = "Solar API DeviceId of the Smart Meter (usually \"0\"; try \"1\" if \"0\" gives no data). "
					+ "Test in the browser: http://<ip>/solar_api/v1/GetMeterRealtimeData.cgi?Scope=Device&DeviceId=0")
	String deviceId() default "0";

	@AttributeDefinition(name = "Meter-Type", description = "What is measured by this Meter? Usually GRID for the Fronius Smart Meter at the grid connection point.")
	MeterType type() default MeterType.GRID;

	@AttributeDefinition(name = "Invert Power/Current", //
			description = "Inverts Power and Current values (multiplies by -1). Use this if positive/negative "
					+ "is swapped compared to the OpenEMS convention for the configured Meter-Type "
					+ "(GRID: positive = buy-from-grid, negative = feed-to-grid).")
	boolean invert() default false;

	@AttributeDefinition(name = "Poll every N Cycles", //
			description = "How often (in multiples of the OpenEMS Cycle-Time, usually 1s) the GEN24 should be "
					+ "queried. The Fronius Solar API can take longer than one Cycle to respond; if it does, "
					+ "you will see 'Task is not queued twice' INFO log messages (harmless, but a sign the "
					+ "GEN24 can't keep up). Increase this value (e.g. 3-5) to poll less often and reduce load "
					+ "on the GEN24's webserver. 1 = every Cycle.")
	int pollEveryCycles() default 3;

	String webconsole_configurationFactory_nameHint() default "Fronius Smart Meter (JSON) [{id}]";
}
