package io.openems.edge.evcs.goe.geminimanaged;

import io.openems.common.types.OptionsEnum;

/**
 * How the phase-switch mode ({@code psm}) of the go-e Gemini is controlled.
 * Purely an OpenEMS-side concept (not a go-e API field) - {@code AUTOMATIC}
 * means this Component's own PV-surplus/time-hysteresis logic decides
 * between forcing 1-phase or 3-phase, it does NOT delegate to the go-e's own
 * native {@code psm=0} "Automatic" mode.
 */
public enum PhaseControlMode implements OptionsEnum {

	UNDEFINED(-1, "Undefined"), //
	AUTOMATIC(0, "Automatic"), //
	FORCE_1_PHASE(1, "Force 1-phase"), //
	FORCE_3_PHASE(2, "Force 3-phase");

	private final int value;
	private final String name;

	private PhaseControlMode(int value, String name) {
		this.value = value;
		this.name = name;
	}

	@Override
	public int getValue() {
		return this.value;
	}

	@Override
	public String getName() {
		return this.name;
	}

	@Override
	public OptionsEnum getUndefined() {
		return UNDEFINED;
	}
}
