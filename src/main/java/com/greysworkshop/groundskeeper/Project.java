package com.greysworkshop.groundskeeper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A big area worked tile by tile, over as many sessions and Minecraft days as it takes. Saved as JSON (see
 * {@code Areas}); Base Insurance reads the same file to draw the progress on its map, so the field names are part of
 * that agreement: {@code tileChunks}, {@code tiles} ("tx,tz" to {@code state}) and {@code shapes}.
 * <p>
 * A tile is a square of {@link #tileChunks} × {@link #tileChunks} chunks. Tiles go QUEUED (grass), WORKED (tilled and
 * seeded, spreading), DONE (all mycelium). Where a worked tile meets one still to do, its edge gets a temporary dirt
 * path (a seam), so grass next door can't creep in; when the next tile is worked, the hoe turns the seam back into
 * farmland, which dries into dirt and is covered like the rest.
 */
final class Project {
	enum State {
		QUEUED, WORKED, DONE, SKIPPED
	}

	static final class Tile {
		State state = State.QUEUED;
		/** Ticks of daylight it has spent near the player since it was worked: how long it's had to spread. */
		long soak;
		/** Times it was looked at again after being worked. */
		int checks;
		/** Why it was skipped, or what's left. */
		String note = "";
		/** Mycelium coverage when last looked at (0-100). */
		int coverage;
	}

	/** Daylight to wait before going back to a worked tile: 15 minutes. */
	static final long SOAK = 15 * 60 * 20;

	String name = "Area";
	String dimension = "";
	int tileChunks = 4;
	List<Shapes.Shape> shapes = new ArrayList<>();
	Map<String, Tile> tiles = new LinkedHashMap<>();
	/** Seam path blocks (BlockPos longs) it put down and hasn't taken up again. */
	List<Long> seams = new ArrayList<>();
	/** The order tiles are worked in: rows top to bottom (or bottom to top), alternating direction. */
	boolean rowsDown = true, firstLeft = true;
	long created, updated;
	long tilled, seeded, pathed;

	transient Mask mask = new Mask();
	transient Set<Long> seamSet = new HashSet<>();
	private transient List<Long> order = List.of();

	static long tileKey(int tx, int tz) {
		return Mask.key(tx, tz);
	}

	static String name(long tile) {
		return Mask.keyX(tile) + "," + Mask.keyZ(tile);
	}

	static long parse(String name) {
		int c = name.indexOf(',');
		return tileKey(Integer.parseInt(name.substring(0, c).trim()), Integer.parseInt(name.substring(c + 1).trim()));
	}

	int tileX(int blockX) {
		return Math.floorDiv(blockX >> 4, tileChunks);
	}

	int tileZ(int blockZ) {
		return Math.floorDiv(blockZ >> 4, tileChunks);
	}

	long tileAt(int x, int z) {
		return tileKey(tileX(x), tileZ(z));
	}

	/** Block bounds of a tile: {minX, minZ, maxX, maxZ}. */
	int[] tileBounds(long tile) {
		int span = tileChunks * 16;
		int x0 = Mask.keyX(tile) * span, z0 = Mask.keyZ(tile) * span;
		return new int[] {x0, z0, x0 + span - 1, z0 + span - 1};
	}

	int[] tileCenter(long tile) {
		int[] b = tileBounds(tile);
		return new int[] {(b[0] + b[2]) / 2, (b[1] + b[3]) / 2};
	}

	Tile tile(long key) {
		return tiles.get(name(key));
	}

	State stateOf(long key) {
		Tile t = tile(key);
		return t == null ? null : t.state;
	}

	/**
	 * Draws the shapes again and lines the tiles up with them: new tiles are queued, ones no longer in the area are
	 * dropped, the rest keep how far they've got. Call after loading and after the shapes change.
	 */
	void rebuild() {
		Shapes s = new Shapes(shapes);
		shapes = new ArrayList<>(s.list);
		mask = s.rasterize();
		Set<String> present = new HashSet<>();
		for (long chunk : mask.chunkKeys()) {
			long t = tileKey(Math.floorDiv(Mask.keyX(chunk), tileChunks), Math.floorDiv(Mask.keyZ(chunk), tileChunks));
			present.add(name(t));
		}
		tiles.keySet().removeIf(k -> !present.contains(k));
		for (String k : present) tiles.computeIfAbsent(k, x -> new Tile());
		if (seams == null) seams = new ArrayList<>();
		seamSet = new HashSet<>(seams);
		order = null;
	}

	/** Picks the corner to start from: the one nearest you. */
	void startFrom(int x, int z) {
		int[] b = new Shapes(shapes).bounds();
		if (b == null) return;
		rowsDown = Math.abs(z - b[1]) <= Math.abs(z - b[3]);
		firstLeft = Math.abs(x - b[0]) <= Math.abs(x - b[2]);
		order = null;
	}

	/** Every tile, in working order. */
	List<Long> order() {
		if (order != null) return order;
		Map<Integer, List<Integer>> rows = new java.util.TreeMap<>();
		for (String k : tiles.keySet()) {
			long t = parse(k);
			rows.computeIfAbsent(Mask.keyZ(t), z -> new ArrayList<>()).add(Mask.keyX(t));
		}
		List<Integer> zs = new ArrayList<>(rows.keySet());
		if (!rowsDown) java.util.Collections.reverse(zs);
		List<Long> out = new ArrayList<>();
		boolean left = firstLeft;
		for (int z : zs) {
			List<Integer> xs = rows.get(z);
			xs.sort(left ? Integer::compare : (a, b) -> Integer.compare(b, a));
			for (int x : xs) out.add(tileKey(x, z));
			left = !left;
		}
		order = out;
		return out;
	}

	/** The next tile to work, or null when none are left to do. */
	Long nextQueued() {
		for (long t : order()) if (stateOf(t) == State.QUEUED) return t;
		return null;
	}

	int count(State s) {
		int n = 0;
		for (Tile t : tiles.values()) if (t.state == s) n++;
		return n;
	}

	/** Worked, or this one (about to be). */
	boolean worked(long tile, long working) {
		if (tile == working) return true;
		State s = stateOf(tile);
		return s == State.WORKED || s == State.DONE;
	}

	/**
	 * Column (x, z), in the area and in a worked tile (or the one being worked), next to area still to be worked: it
	 * needs a seam so grass from there can't get in.
	 */
	boolean needsSeam(int x, int z, long working) {
		if (!mask.contains(x, z) || !worked(tileAt(x, z), working)) return false;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (dx == 0 && dz == 0) continue;
				int nx = x + dx, nz = z + dz;
				if (mask.contains(nx, nz) && !worked(tileAt(nx, nz), working)) return true;
			}
		}
		return false;
	}

	void addSeam(long pos) {
		if (seamSet.add(pos)) seams.add(pos);
	}

	void removeSeam(long pos) {
		if (seamSet.remove(pos)) seams.remove(pos);
	}

	long selectedColumns() {
		return mask.count();
	}
}
