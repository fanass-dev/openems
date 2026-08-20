package io.openems.edge.controller.ess.topofcharge;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonPrimitive;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.jscalendar.JSCalendar;
import io.openems.common.jsonrpc.serialization.JsonElementPath;
import io.openems.common.jsonrpc.serialization.JsonSerializer;
import io.openems.common.jsonrpc.serialization.JsonSerializerUtil;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;
import io.openems.edge.ess.api.ManagedSymmetricEss;

/**
 * Caps the charge power of a storage at 0 W once its SOC reaches/exceeds a
 * Max-SOC value ("Top-Of-Charge") - discharging remains unrestricted at all
 * times. The Max-SOC value itself comes from a JSCalendar schedule whose
 * Tasks each carry their own value as payload (see readme.adoc), so it can
 * vary over time (e.g. a lower value only during summer months) - a single,
 * permanently-covering Task is the trivial case of a constant value.
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Controller.Ess.TopOfCharge", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class ControllerEssTopOfChargeImpl extends AbstractOpenemsComponent
		implements ControllerEssTopOfCharge, Controller, OpenemsComponent {

	/**
	 * Deserializes a JSCalendar Task's {@code openems.io:payload} as a plain
	 * {@link Integer} (the Max-SOC value for that Task's period).
	 */
	private static final JsonSerializer<Integer> MAX_SOC_PAYLOAD_SERIALIZER = JsonSerializerUtil
			.jsonSerializer(Integer.class, JsonElementPath::getAsInt, JsonPrimitive::new);

	private final Logger log = LoggerFactory.getLogger(ControllerEssTopOfChargeImpl.class);

	@Reference
	private ComponentManager componentManager;

	private Config config;

	/**
	 * Parsed from {@link Config#jsCalendar()} - each Task's payload is the
	 * Max-SOC value active during that Task's period. Reuses the same JSCalendar
	 * engine as {@code Scheduler.JSCalendar}/{@code Controller.Ess
	 * .ForecastChargeWindow}, just with an {@link Integer} payload instead of
	 * {@link Void}.
	 */
	private JSCalendar.Tasks<Integer> maxSocSchedule = JSCalendar.Tasks.empty();

	/**
	 * Last logged limited-state - used only to avoid logging the same line every
	 * Cycle, see {@link #logOnChange}.
	 */
	private Boolean lastLoggedLimited = null;

	public ControllerEssTopOfChargeImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				Controller.ChannelId.values(), //
				ControllerEssTopOfCharge.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.config = config;
		this.maxSocSchedule = JSCalendar.Tasks.fromStringOrEmpty(this.componentManager.getClock(),
				config.jsCalendar(), MAX_SOC_PAYLOAD_SERIALIZER);
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public void run() throws OpenemsNamedException {
		ManagedSymmetricEss ess = this.componentManager.getComponent(this.config.ess_id());
		Value<Integer> soc = ess.getSoc();

		var activeTask = this.maxSocSchedule.getActiveOneTask();
		Integer maxSoc = activeTask == null ? null : activeTask.payload();
		this._setActiveMaxSoc(maxSoc);

		// No Task covering 'now' (e.g. empty schedule, or a gap between
		// configured periods) is a deliberate, safe default: don't restrict.
		var limited = maxSoc != null && soc.isDefined() && soc.get() >= maxSoc;

		this._setCurrentlyLimited(limited);
		this.logOnChange(limited, maxSoc, soc);

		// Every Cycle, unconditionally - like Controller.Ess.LimitTotalDischarge's
		// mirror-image discharge limit: 'null' when not limited clears any
		// previously set Constraint, so the storage falls back to unrestricted
		// charging as soon as the SOC drops back below the currently active
		// Max-SOC (or that Max-SOC no longer applies at all).
		ess.setActivePowerGreaterOrEquals(limited ? 0 : null);

		this._setLastDecision(this.buildStatusText(maxSoc, soc, limited));
	}

	private String buildStatusText(Integer maxSoc, Value<Integer> soc, boolean limited) {
		var maxSocText = maxSoc == null //
				? "Max-SOC: kein Zeitraum im Zeitplan aktiv (kein Limit)" //
				: "Max-SOC: " + maxSoc + " %";
		var socText = soc.isDefined() //
				? "SOC: " + soc.get() + " %" //
				: "SOC: keine Daten verfuegbar";
		var result = limited //
				? "Ergebnis: Ladeleistung auf 0 W begrenzt" //
				: "Ergebnis: keine Begrenzung";
		return maxSocText + "; " + socText + "; " + result;
	}

	/**
	 * Logs a human-readable line only when the limited-state actually changes -
	 * {@link #run()} itself must apply the Constraint every Cycle unconditionally,
	 * but logging every Cycle would spam the log.
	 *
	 * @param limited the overall result
	 * @param maxSoc  the currently active Max-SOC value, or null if none applies
	 * @param soc     the current SOC, possibly undefined
	 */
	private void logOnChange(boolean limited, Integer maxSoc, Value<Integer> soc) {
		if (Boolean.valueOf(limited).equals(this.lastLoggedLimited)) {
			return;
		}
		this.lastLoggedLimited = limited;
		this.logInfo(this.log, limited //
				? "Top-Of-Charge erreicht (SOC " + soc.get() + " % >= " + maxSoc
						+ " %) - Ladeleistung auf 0 W begrenzt" //
				: "Top-Of-Charge unterschritten oder kein Zeitraum aktiv - Ladeleistung wieder unbegrenzt");
	}
}
