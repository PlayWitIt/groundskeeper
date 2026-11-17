package com.greysworkshop.groundskeeper;

/**
 * Where to dig in for the night. A bunker is a 1 × 2 hole three blocks deep with a block put back on top:
 * <pre>
 *   y+1  air (where you stood)
 *   y    ground  -> dug, then the roof goes back in here (flush with the ground)
 *   y-1  dug: your head
 *   y-2  dug: your feet
 *   y-3  floor: solid, not magma, no fluid
 * </pre>
 * Every side of the head and feet cells has to be a solid wall (nothing can walk or flow in), the roof needs a solid
 * block next to it at ground level to be placed against, and no water or lava may be anywhere near. Mobs can't reach
 * you, and the roof also keeps phantoms away (they only come for players who can see the sky).
 * Plain Java: the game side answers {@link Probe}.
 */
final class BunkerSpot {
	interface Probe {
		/** Can be dug out with what's in the inventory, quickly, safely (not a container, not falling, not fluid). */
		boolean diggable(int x, int y, int z);

		/** A solid full block face on the side facing the hole, and no fluid. */
		boolean wall(int x, int y, int z);

		/** Something safe to stand on: solid on top, not magma or a campfire, no fluid. */
		boolean floor(int x, int y, int z);

		/** Water or lava at or right next to here. */
		boolean fluid(int x, int y, int z);

		/** You can stand at (x, y, z) feet height and get there: standing on (x, y - 1, z). */
		boolean standable(int x, int y, int z);
	}

	private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

	private BunkerSpot() {}

	/** Whether ground block (x, y, z) works as a bunker's top. */
	static boolean ok(Probe p, int x, int y, int z) {
		if (!p.standable(x, y + 1, z)) return false;
		for (int d = 0; d <= 2; d++) if (!p.diggable(x, y - d, z)) return false;
		if (!p.floor(x, y - 3, z)) return false;
		boolean anchor = false;
		for (int[] s : SIDES) {
			if (!p.wall(x + s[0], y - 1, z + s[1]) || !p.wall(x + s[0], y - 2, z + s[1])) return false;
			if (p.wall(x + s[0], y, z + s[1])) anchor = true;
		}
		if (!anchor) return false;
		for (int dy = -3; dy <= 1; dy++) {
			for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (p.fluid(x + dx, y + dy, z + dz)) return false;
		}
		return true;
	}

	/**
	 * The nearest good spot within {@code range} blocks of the player's feet (fx, fy, fz), as the ground block
	 * {x, y, z}, or null. It looks a few blocks up and down too, for slopes.
	 */
	static int[] find(Probe p, int fx, int fy, int fz, int range) {
		int[] best = null;
		double bestD = Double.MAX_VALUE;
		for (int dx = -range; dx <= range; dx++) {
			for (int dz = -range; dz <= range; dz++) {
				for (int dy = -3; dy <= 2; dy++) {
					int x = fx + dx, y = fy - 1 + dy, z = fz + dz;
					// Up or down a block costs a bit more than across.
					double d = dx * dx + dz * dz + dy * dy * 2.0;
					if (d >= bestD || d > range * range + 8) continue;
					if (!ok(p, x, y, z)) continue;
					bestD = d;
					best = new int[] {x, y, z};
				}
			}
		}
		return best;
	}
}
