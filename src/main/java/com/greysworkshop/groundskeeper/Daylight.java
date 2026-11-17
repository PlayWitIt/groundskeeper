package com.greysworkshop.groundskeeper;

/**
 * The time of day as it matters here (ticks, 0 to 23,999; 0 is just after sunrise, 6,000 noon, 18,000 midnight).
 * Monsters spawn on the surface from about 13,000 to 23,000, or any time in a thunderstorm. Mycelium only spreads in
 * daylight.
 */
final class Daylight {
	/** Monsters start spawning in the open. */
	static final int NIGHT = 13_000;
	/** The sky is light enough that they've stopped. */
	static final int MORNING = 23_200;
	/** Out of the shelter: zombies and skeletons left over are burning by now. */
	static final int SAFE_OUT = 300;
	/** Last moment to start getting into shelter, whatever else is going on. */
	static final int DUSK = 12_300;

	private Daylight() {}

	static int tod(long timeOfDay) {
		return (int) Math.floorMod(timeOfDay, 24_000L);
	}

	/** Monsters can spawn out in the open now. */
	static boolean dangerous(long timeOfDay, boolean thunder) {
		int t = tod(timeOfDay);
		return thunder || t >= NIGHT && t < MORNING;
	}

	/** Mycelium (and grass) spread: daytime. */
	static boolean spreads(long timeOfDay) {
		int t = tod(timeOfDay);
		return t < 12_500 || t >= 23_500;
	}

	/** Ticks until monsters can spawn (0 if they can now). */
	static int untilNight(long timeOfDay, boolean thunder) {
		if (dangerous(timeOfDay, thunder)) return 0;
		int t = tod(timeOfDay);
		return t < NIGHT ? NIGHT - t : NIGHT + 24_000 - t;
	}

	/**
	 * Time to take shelter: night is coming within {@code travelTicks} (plus a margin for digging in), or is here.
	 */
	static boolean shelterNow(long timeOfDay, boolean thunder, int travelTicks) {
		return untilNight(timeOfDay, thunder) <= travelTicks + 400 || tod(timeOfDay) >= DUSK && tod(timeOfDay) < MORNING;
	}

	/** Safe to come out and carry on. */
	static boolean canLeave(long timeOfDay, boolean thunder) {
		int t = tod(timeOfDay);
		return !thunder && t >= SAFE_OUT && t < DUSK - 600;
	}
}
