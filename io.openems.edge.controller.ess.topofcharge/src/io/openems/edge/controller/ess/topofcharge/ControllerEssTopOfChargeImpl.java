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

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;
import io.openems.edge.ess.api.ManagedSymmetricEss;

/**
 * Caps the charge power of a storage at 0 W once its SOC reaches/exceeds a
 * configured maximum ("Top-Of-Charge") - discharging remains unrestricted at
 * all times. First version without any time-based gating (e.g. only in
 * certain months) - see readme.adoc for why that is deliberately deferred
 * rather than designed in now.
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Controller.Ess.TopOfCharge", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class ControllerEssTopOfChargeImpl extends AbstractOpenemsComponent
		implements ControllerEssTopOfCharge, Controller, OpenemsComponent {

	private final Logger log = LoggerFactory.getLogger(ControllerEssTopOfChargeImpl.class);

	@Reference
	private ComponentManager componentManager;

	private Config config;

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
		var limited = soc.isDefined() && soc.get() >= this.config.maxSoc();

		this._setCurrentlyLimited(limited);
		this.logOnChange(limited, soc);

		// Every Cycle, unconditionally - like Controller.Ess.LimitTotalDischarge's
		// mirror-image discharge limit: 'null' when not limited clears any
		// previously set Constraint, so the storage falls back to unrestricted
		// charging as soon as the SOC drops back below the configured maximum.
		ess.setActivePowerGreaterOrEquals(limited ? 0 : null);

		this._setLastDecision(this.buildStatusText(soc, limited));
	}

	private String buildStatusText(Value<Integer> soc, boolean limited) {
		var socText = soc.isDefined() //
				? "SOC: " + soc.get() + " % (Max " + this.config.maxSoc() + " %)" //
				: "SOC: keine Daten verfuegbar";
		var result = limited //
				? "Ergebnis: Ladeleistung auf 0 W begrenzt" //
				: "Ergebnis: keine Begrenzung";
		return socText + "; " + result;
	}

	/**
	 * Logs a human-readable line only when the limited-state actually changes -
	 * {@link #run()} itself must apply the Constraint every Cycle unconditionally,
	 * but logging every Cycle would spam the log.
	 *
	 * @param limited the overall result
	 * @param soc     the current SOC, possibly undefined
	 */
	private void logOnChange(boolean limited, Value<Integer> soc) {
		if (Boolean.valueOf(limited).equals(this.lastLoggedLimited)) {
			return;
		}
		this.lastLoggedLimited = limited;
		this.logInfo(this.log, limited //
				? "Top-Of-Charge erreicht (SOC " + soc.get() + " % >= " + this.config.maxSoc()
						+ " %) - Ladeleistung auf 0 W begrenzt" //
				: "Top-Of-Charge unterschritten - Ladeleistung wieder unbegrenzt");
	}
}
