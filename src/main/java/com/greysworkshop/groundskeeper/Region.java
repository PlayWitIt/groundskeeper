package com.greysworkshop.groundskeeper;

import java.util.HashSet;
import java.util.Set;

/**
 * The columns one plan covers, and what kind each is: the old single circle, or one tile of a big {@link Project}.
 * Plain Java (no game classes), so it can be tested on its own.
 */
abstract class Region {
	/** In a circle of radius r around (0, 0): the same columns the circle job has always used. */
	static boolean inCircle(int dx, int dz, int r) {
		return dx * dx + dz * dz <= r * r + r;
	}

	/** On the circle's outer ring: one of its 8 neighbours is outside. */
	static boolean onRing(int dx, int dz, int r) {
		for (int ox = -1; ox <= 1; ox++) for (int oz = -1; oz <= 1; oz++) if (!inCircle(dx + ox, dz + oz, r)) return true;
		return false;
	}

	// The game's packing of a block position into a long (BlockPos.asLong): x 26 bits at 38, z 26 bits at 12, y 12 bits.

	static long pos(int x, int y, int z) {
		return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
	}

	static int posX(long pos) {
		return (int) (pos >> 38);
	}

	static int posY(long pos) {
		return (int) (pos << 52 >> 52);
	}

	static int posZ(long pos) {
		return (int) (pos << 26 >> 38);
	}

	/** Columns this plan looks at. */
	abstract boolean inside(int x, int z);

	/** On the outside edge of the whole selection: the border path goes here. */
	abstract boolean edge(int x, int z);

	/** Inside, not on the edge, but next to selected ground still to be worked: a temporary seam path goes here. */
	boolean seam(int x, int z) {
		return false;
	}

	/** A seam path block (BlockPos long) no longer needed: till it back. */
	boolean release(long pos) {
		return false;
	}

	/** {minX, minZ, maxX, maxZ} of the columns {@link #inside} can be true for. */
	abstract int[] bounds();

	/** Seeds go on a grid lined up with this column, so neighbouring tiles' grids meet up. */
	abstract int gridX();

	abstract int gridZ();

	/** Where to stand and watch it spread. */
	abstract int centerX();

	abstract int centerZ();

	/** A circle: the original job. Same columns as before: dx² + dz² ≤ r² + r. */
	static final class Circle extends Region {
		final int cx, cz, r;

		Circle(int cx, int cz, int r) {
			this.cx = cx;
			this.cz = cz;
			this.r = r;
		}

		@Override
		boolean inside(int x, int z) {
			return inCircle(x - cx, z - cz, r);
		}

		@Override
		boolean edge(int x, int z) {
			return inside(x, z) && onRing(x - cx, z - cz, r);
		}

		@Override
		int[] bounds() {
			return new int[] {cx - r, cz - r, cx + r, cz + r};
		}

		@Override
		int gridX() {
			return cx;
		}

		@Override
		int gridZ() {
			return cz;
		}

		@Override
		int centerX() {
			return cx;
		}

		@Override
		int centerZ() {
			return cz;
		}
	}

	/**
	 * One tile of a project: the selected columns in it, plus seam paths in the tiles around it that working this one
	 * makes unneeded (they're tilled back as part of this tile's work).
	 */
	static final class Tile extends Region {
		final Project project;
		final long tile;
		private final int[] tb;
		private final Set<Long> releaseCols = new HashSet<>(), releasePos = new HashSet<>();

		Tile(Project project, long tile) {
			this.project = project;
			this.tile = tile;
			this.tb = project.tileBounds(tile);
			for (long pos : project.seamSet) {
				int x = posX(pos), z = posZ(pos);
				// Only seams next to this tile can stop being needed when it's worked.
				if (x < tb[0] - 1 || x > tb[2] + 1 || z < tb[1] - 1 || z > tb[3] + 1) continue;
				if (inTile(x, z)) continue;
				if (!project.needsSeam(x, z, tile)) {
					releaseCols.add(Mask.key(x, z));
					releasePos.add(pos);
				}
			}
		}

		private boolean inTile(int x, int z) {
			return x >= tb[0] && x <= tb[2] && z >= tb[1] && z <= tb[3];
		}

		@Override
		boolean inside(int x, int z) {
			return inTile(x, z) && project.mask.contains(x, z) || releaseCols.contains(Mask.key(x, z));
		}

		@Override
		boolean edge(int x, int z) {
			return inTile(x, z) && project.mask.edge(x, z);
		}

		@Override
		boolean seam(int x, int z) {
			return inTile(x, z) && !project.mask.edge(x, z) && project.needsSeam(x, z, tile);
		}

		@Override
		boolean release(long pos) {
			return releasePos.contains(pos);
		}

		int releases() {
			return releasePos.size();
		}

		@Override
		int[] bounds() {
			return new int[] {tb[0] - 1, tb[1] - 1, tb[2] + 1, tb[3] + 1};
		}

		@Override
		int gridX() {
			return 0;
		}

		@Override
		int gridZ() {
			return 0;
		}

		@Override
		int centerX() {
			return (tb[0] + tb[2]) / 2;
		}

		@Override
		int centerZ() {
			return (tb[1] + tb[3]) / 2;
		}
	}
}
