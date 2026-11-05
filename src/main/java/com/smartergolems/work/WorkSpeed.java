package com.smartergolems.work;

import net.minecraft.world.entity.animal.golem.CopperGolem;

/**
 * Oxidation slows working golems down, like rusty machinery. Waxing a golem with
 * honeycomb stops further oxidation, so it keeps its current speed.
 */
public final class WorkSpeed {
	private WorkSpeed() {}

	/** 1.0 for fresh copper, down to 0.55 when fully oxidized. */
	public static double multiplier(CopperGolem golem) {
		return switch (golem.getWeatherState()) {
			case UNAFFECTED -> 1.0;
			case EXPOSED -> 0.85;
			case WEATHERED -> 0.7;
			case OXIDIZED -> 0.55;
		};
	}

	/** Stretches a duration to match the golem's speed (slower golems take longer). */
	public static int scaleTicks(CopperGolem golem, int ticks) {
		return (int) Math.ceil(ticks / multiplier(golem));
	}
}
