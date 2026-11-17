package com.greysworkshop.groundskeeper;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Checks the plain-Java parts (areas, shapes, tiles, seams, day and night, threats, bunker spots) without the game.
 * Run it with only Gson on the class path:
 * <pre>
 * javac -cp gson.jar -d out Shapes.java Mask.java Project.java Region.java Daylight.java Threat.java BunkerSpot.java CoreCheck.java
 * java -cp gson.jar:out com.greysworkshop.groundskeeper.CoreCheck
 * </pre>
 */
public final class CoreCheck {
	private static int passed, failed;

	public static void main(String[] args) {
		mask();
		circles();
		shapes();
		big();
		json();
		tiles();
		seams();
		packing();
		daylight();
		threats();
		bunkers();
		System.out.println(passed + " passed, " + failed + " failed");
		if (failed > 0) System.exit(1);
	}

	private static void check(boolean ok, String what) {
		if (ok) passed++;
		else {
			failed++;
			System.out.println("FAIL: " + what);
		}
	}

	private static void mask() {
		Random r = new Random(7);
		Mask m = new Mask();
		boolean[][] ref = new boolean[200][200];
		for (int i = 0; i < 3000; i++) {
			int z = r.nextInt(200), a = r.nextInt(200), b = r.nextInt(200);
			boolean on = r.nextInt(3) != 0;
			int from = Math.min(a, b), to = Math.max(a, b);
			m.setRun(z - 100, from - 100, to - 100, on);
			for (int x = from; x <= to; x++) ref[x][z] = on;
		}
		m.compact();
		boolean same = true;
		long n = 0;
		for (int x = 0; x < 200; x++) for (int z = 0; z < 200; z++) {
			if (ref[x][z]) n++;
			if (m.contains(x - 100, z - 100) != ref[x][z]) same = false;
		}
		check(same, "mask matches a plain grid after random runs (negative coordinates too)");
		check(m.count() == n, "mask count " + m.count() + " == " + n);
	}

	private static void circles() {
		boolean same = true;
		for (int rad = 2; rad <= 40; rad++) {
			Mask m = new Shapes(List.of(Shapes.Shape.ellipse(5, -7, rad, rad))).rasterize();
			for (int x = -50; x <= 60; x++) for (int z = -60; z <= 50; z++) {
				if (m.contains(x, z) != Region.inCircle(x - 5, z + 7, rad)) same = false;
			}
		}
		check(same, "a circle shape has exactly the columns of the old circle job (r 2 to 40)");
		Region.Circle c = new Region.Circle(0, 0, 16);
		long ring = 0, inside = 0;
		for (int x = -17; x <= 17; x++) for (int z = -17; z <= 17; z++) {
			if (c.inside(x, z)) inside++;
			if (c.edge(x, z)) ring++;
		}
		check(inside == 861 && ring > 90 && ring < 140, "radius 16: " + inside + " columns, ring " + ring);
	}

	private static void shapes() {
		Mask square = new Shapes(List.of(Shapes.Shape.polygon(new int[] {0, 0, 9, 0, 9, 9, 0, 9}))).rasterize();
		Mask rect = new Shapes(List.of(Shapes.Shape.rect(0, 0, 9, 9))).rasterize();
		// A polygon through block centres covers the centres inside it: its corners' rows are borderline.
		check(square.count() >= 81 && square.count() <= 100, "square polygon " + square.count());
		check(rect.count() == 100, "10 × 10 rect has 100 columns: " + rect.count());
		check(rect.edgeCount() == 36, "its edge is 36: " + rect.edgeCount());

		List<Shapes.Shape> list = new ArrayList<>();
		list.add(Shapes.Shape.rect(-50, -50, 49, 49));
		list.add(Shapes.Shape.ellipse(0, 0, 10, 10).cutting());
		Mask holed = new Shapes(list).rasterize();
		long expect = 10_000;
		for (int x = -11; x <= 11; x++) for (int z = -11; z <= 11; z++) if (Region.inCircle(x, z, 10)) expect--;
		check(holed.count() == expect, "rect with a circle cut out: " + holed.count() + " == " + expect);
		check(!holed.contains(0, 0) && holed.contains(-50, -50) && !holed.contains(50, 0), "the hole is a hole, the corners are in");
		check(holed.edge(0, 11) || holed.edge(0, 12), "the hole's rim is an edge (border path goes round it)");

		// A triangle: every column centre inside by an independent test.
		int[] tri = {0, 0, 40, 5, 10, 30};
		Mask t = new Shapes(List.of(Shapes.Shape.polygon(tri))).rasterize();
		int diffs = 0;
		for (int x = -2; x <= 42; x++) for (int z = -2; z <= 32; z++) if (t.contains(x, z) != inTriangle(x + 0.5, z + 0.5, tri)) diffs++;
		// Only a column exactly on a corner can go either way.
		check(diffs <= 1, "triangle polygon matches a point-in-triangle test (" + diffs + " on the boundary)");

		List<Integer> pts = new ArrayList<>();
		Shapes.stroke(pts, 4, 0, 0);
		Shapes.stroke(pts, 4, 40, 0);
		int[] arr = pts.stream().mapToInt(Integer::intValue).toArray();
		Mask brush = new Shapes(List.of(Shapes.Shape.brush(4, arr))).rasterize();
		boolean line = true;
		for (int x = 0; x <= 40; x++) if (!brush.contains(x, 0) || !brush.contains(x, 3) || brush.contains(x, 6)) line = false;
		check(line, "a brush stroke is a solid band with no gaps, about the brush's width");
	}

	private static boolean inTriangle(double px, double pz, int[] t) {
		double ax = t[0] + 0.5, az = t[1] + 0.5, bx = t[2] + 0.5, bz = t[3] + 0.5, cx = t[4] + 0.5, cz = t[5] + 0.5;
		double d1 = (px - bx) * (az - bz) - (ax - bx) * (pz - bz);
		double d2 = (px - cx) * (bz - cz) - (bx - cx) * (pz - cz);
		double d3 = (px - ax) * (cz - az) - (cx - ax) * (pz - az);
		boolean neg = d1 < 0 || d2 < 0 || d3 < 0, pos = d1 > 0 || d2 > 0 || d3 > 0;
		return !(neg && pos);
	}

	private static void big() {
		long t0 = System.nanoTime();
		Shapes s = new Shapes(List.of(Shapes.Shape.rect(-5000, -5000, 4999, 4999), Shapes.Shape.ellipse(1000, 1000, 300, 200).cutting()));
		check(s.tooBig() == null, "10,000 × 10,000 is allowed");
		Mask m = s.rasterize();
		double ms = (System.nanoTime() - t0) / 1e6;
		check(m.count() > 99_000_000L && m.count() < 100_000_000L, "10,000 × 10,000 with a hole: " + m.count() + " columns");
		check(m.chunkCount() > 391_000 && m.chunkCount() < 626 * 626, "626 × 626 chunks less the ones inside the hole: " + m.chunkCount());
		check(ms < 20_000, "drawn in " + Math.round(ms) + " ms");
		System.out.println("  (10,000 × 10,000 area drawn in " + Math.round(ms) + " ms)");
		Shapes huge = new Shapes(List.of(Shapes.Shape.rect(0, 0, 20_000, 20_000)));
		check(huge.tooBig() != null, "20,000 × 20,000 is refused: " + huge.tooBig());
	}

	private static void json() {
		List<Shapes.Shape> list = new ArrayList<>();
		list.add(Shapes.Shape.rect(0, 0, 100, 50));
		list.add(Shapes.Shape.polygon(new int[] {10, 10, 30, 10, 20, 40}).cutting());
		list.add(Shapes.Shape.brush(3, new int[] {1, 2, 3, 4}));
		String json = new Shapes(list).toJson();
		Shapes back = Shapes.fromJson(json);
		check(back.list.size() == 3 && back.list.get(1).cut() && back.list.get(2).r == 3, "shapes survive JSON: " + json);
		check(back.rasterize().count() == new Shapes(list).rasterize().count(), "and draw the same");
		check(Shapes.fromJson("not json").isEmpty() && Shapes.fromJson("{\"shapes\":[{\"type\":\"polygon\",\"points\":[1,2]}]}").isEmpty(),
				"bad JSON and bad shapes are dropped, not crashed on");
	}

	private static Project project(Shapes.Shape... shapes) {
		Project p = new Project();
		p.shapes = new ArrayList<>(List.of(shapes));
		p.rebuild();
		return p;
	}

	private static void tiles() {
		// 4 × 3 tiles of 64 blocks.
		Project p = project(Shapes.Shape.rect(0, 0, 255, 191));
		check(p.tiles.size() == 12, "12 tiles: " + p.tiles.size());
		p.startFrom(250, 0);
		List<Long> order = p.order();
		boolean adjacent = true;
		for (int i = 1; i < order.size(); i++) {
			long a = order.get(i - 1), b = order.get(i);
			if (Math.abs(Mask.keyX(a) - Mask.keyX(b)) + Math.abs(Mask.keyZ(a) - Mask.keyZ(b)) != 1) adjacent = false;
		}
		check(adjacent, "each tile in the order is next to the one before (no long walks)");
		check(Project.name(order.get(0)).equals("3,0"), "starts in the corner nearest you: " + Project.name(order.get(0)));
		p.tile(order.get(0)).state = Project.State.WORKED;
		check(p.nextQueued().equals(order.get(1)), "then carries on in order");
		// Cut a tile's worth out: that tile disappears, the others keep their state.
		p.shapes.add(Shapes.Shape.rect(0, 128, 63, 191).cutting());
		p.rebuild();
		check(p.tiles.size() == 11 && p.tile(order.get(0)).state == Project.State.WORKED, "changing the shapes keeps progress");
	}

	private static void seams() {
		// Two tiles side by side: A (0..63) and B (64..127), 64 high.
		Project p = project(Shapes.Shape.rect(0, 0, 127, 63));
		long a = Project.tileKey(0, 0), b = Project.tileKey(1, 0);
		Region.Tile ra = new Region.Tile(p, a);
		int seams = 0, edges = 0;
		for (int x = 0; x <= 127; x++) for (int z = 0; z <= 63; z++) {
			if (ra.seam(x, z)) seams++;
			if (ra.edge(x, z)) edges++;
			if (ra.seam(x, z) && x != 63) check(false, "a seam at " + x + "," + z + " (only x=63 should be)");
		}
		check(seams == 62, "working A: a seam all along its side facing B (not on the outer edge): " + seams);
		check(edges == 64 + 63 + 63, "A's outer border: " + edges);
		// A is worked and its seams are down.
		p.tile(a).state = Project.State.WORKED;
		for (int z = 1; z <= 62; z++) p.addSeam(Region.pos(63, 70, z));
		Region.Tile rb = new Region.Tile(p, b);
		check(rb.releases() == 62, "working B takes all of A's seams back up: " + rb.releases());
		check(rb.inside(63, 10) && rb.release(Region.pos(63, 70, 10)) && !rb.release(Region.pos(63, 71, 10)), "only the exact seam blocks");
		int bSeams = 0;
		for (int x = 64; x <= 127; x++) for (int z = 0; z <= 63; z++) if (rb.seam(x, z)) bSeams++;
		check(bSeams == 0, "B needs no seams of its own (nothing left to do next to it)");
		// Three in a row: working the middle one keeps a seam towards the third.
		Project q = project(Shapes.Shape.rect(0, 0, 191, 63));
		q.tile(Project.tileKey(0, 0)).state = Project.State.WORKED;
		Region.Tile mid = new Region.Tile(q, Project.tileKey(1, 0));
		check(mid.seam(127, 30) && !mid.seam(64, 30), "the middle tile seams its far side only");
	}

	private static void packing() {
		boolean ok = true;
		int[][] cases = {{0, 0, 0}, {-1, -64, -1}, {29_999_999, 319, -29_999_999}, {-12345, 70, 67890}};
		for (int[] c : cases) {
			long l = Region.pos(c[0], c[1], c[2]);
			if (Region.posX(l) != c[0] || Region.posY(l) != c[1] || Region.posZ(l) != c[2]) ok = false;
		}
		check(ok, "block positions pack and unpack (negative and world-border coordinates)");
		// The game's own example: BlockPos.asLong(1, 2, 3).
		check(Region.pos(1, 2, 3) == (1L << 38 | 3L << 12 | 2L), "same layout as BlockPos.asLong");
	}

	private static void daylight() {
		check(!Daylight.dangerous(6000, false) && Daylight.dangerous(18000, false) && Daylight.dangerous(6000, true), "noon safe, midnight not, thunder never");
		check(Daylight.shelterNow(12_700, false, 0) && !Daylight.shelterNow(9000, false, 0), "shelter at dusk, not mid-afternoon");
		check(Daylight.shelterNow(11_000, false, 1800), "a long walk to the safety zone sets off early");
		check(!Daylight.canLeave(23_800, false) && Daylight.canLeave(1000, false) && !Daylight.canLeave(1000, true), "out after sunrise, not in a storm");
		check(Daylight.untilNight(24_000 + 12_000, false) == 1000, "days wrap");
		check(Daylight.spreads(6000) && !Daylight.spreads(18000), "mycelium spreads by day only");
	}

	private static void threats() {
		var clear = Threat.assess(List.of(new Threat.Seen(Threat.Kind.HOSTILE, 30, null)), Threat.Players.NAUGHTY);
		check(clear.level() == Threat.Level.NONE, "a zombie 30 blocks off is nothing");
		var creeper = Threat.assess(List.of(new Threat.Seen(Threat.Kind.CREEPER_LIT, 3, null)), Threat.Players.NAUGHTY);
		check(creeper.dodge() && !creeper.hide(), "a lit creeper close: back off");
		var naughty = Threat.assess(List.of(new Threat.Seen(Threat.Kind.PLAYER_NAUGHTY, 70, "Griefer")), Threat.Players.NAUGHTY);
		check(naughty.hide() && naughty.reason().contains("Griefer"), "someone on your Naughty list: hide (" + naughty.reason() + ")");
		var stranger = Threat.assess(List.of(new Threat.Seen(Threat.Kind.PLAYER_STRANGER, 20, "Bob")), Threat.Players.NAUGHTY);
		check(!stranger.hide(), "strangers ignored on the Naughty setting");
		check(Threat.assess(List.of(new Threat.Seen(Threat.Kind.PLAYER_STRANGER, 20, "Bob")), Threat.Players.STRANGERS).hide(), "but not on Strangers");
		check(!Threat.assess(List.of(new Threat.Seen(Threat.Kind.PLAYER_FRIEND, 2, "Pal")), Threat.Players.STRANGERS).hide(), "friends never");
		List<Threat.Seen> crowd = new ArrayList<>();
		for (int i = 0; i < 5; i++) crowd.add(new Threat.Seen(Threat.Kind.HOSTILE, 3 + i, null));
		check(Threat.assess(crowd, Threat.Players.OFF).hide(), "five zombies close: hide");
		check(Threat.assess(List.of(new Threat.Seen(Threat.Kind.HOSTILE, 4, null)), Threat.Players.OFF).level() == Threat.Level.CAUTION, "one zombie close: caution");
	}

	/** A world of stone below y = 64 with grass on top, and whatever's set by hand. */
	private static final class FakeWorld implements BunkerSpot.Probe {
		final java.util.Map<Long, Character> set = new java.util.HashMap<>();

		char at(int x, int y, int z) {
			Character c = set.get(Region.pos(x, y, z));
			if (c != null) return c;
			return y <= 64 ? 's' : ' ';
		}

		public boolean diggable(int x, int y, int z) {
			char c = at(x, y, z);
			return c == 's' || c == 'd';
		}

		public boolean wall(int x, int y, int z) {
			char c = at(x, y, z);
			return c == 's' || c == 'd' || c == 'o';
		}

		public boolean floor(int x, int y, int z) {
			return wall(x, y, z);
		}

		public boolean fluid(int x, int y, int z) {
			return at(x, y, z) == 'w';
		}

		public boolean standable(int x, int y, int z) {
			return at(x, y, z) == ' ' && at(x, y + 1, z) == ' ' && wall(x, y - 1, z);
		}
	}

	private static void bunkers() {
		FakeWorld w = new FakeWorld();
		int[] spot = BunkerSpot.find(w, 0, 65, 0, 5);
		check(spot != null && spot[0] == 0 && spot[1] == 64 && spot[2] == 0, "flat ground: dig in right where you stand");
		w.set.put(Region.pos(0, 62, 1), 'w');
		spot = BunkerSpot.find(w, 0, 65, 0, 5);
		check(spot != null && !(spot[0] == 0 && spot[2] == 0) && !BunkerSpot.ok(w, 0, 64, 0), "water next to the hole: somewhere else (" + (spot == null ? "none" : spot[0] + "," + spot[2]) + ")");
		FakeWorld cave = new FakeWorld();
		cave.set.put(Region.pos(1, 63, 0), ' ');
		check(!BunkerSpot.ok(cave, 0, 64, 0), "a cave next to the head cell: not here (something could walk in)");
		FakeWorld lava = new FakeWorld();
		for (int x = -8; x <= 8; x++) for (int z = -8; z <= 8; z++) lava.set.put(Region.pos(x, 61, z), 'w');
		check(BunkerSpot.find(lava, 0, 65, 0, 5) == null, "a lake under the floor everywhere: nowhere to dig");
	}
}
