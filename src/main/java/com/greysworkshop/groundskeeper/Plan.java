package com.greysworkshop.groundskeeper;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.SnowBlock;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.light.ChunkLightProvider;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What to do to a circle, worked out from the blocks that are there. The rules it plans by are the game's own (1.21.11):
 * <ul>
 * <li>Grass and mycelium spread only onto plain <b>dirt</b>, at most 1 block sideways, 1 block up or 3 down, and only
 * where the dirt could live (nothing opaque on it, no water on it). They never take over each other, farmland or path.</li>
 * <li>A hoe turns grass into farmland; dry farmland with nothing planted turns back into dirt on its next random tick
 * (about a minute), unless there's water within 4 blocks. So tilling makes dirt for free, with no drops.</li>
 * <li>A shovel turns grass into dirt path, which nothing spreads onto, and dirt under path or farmland can't grow anything.</li>
 * </ul>
 * So: the outer ring becomes path (grass outside is then at least 2 blocks from any dirt inside and can't get in), the
 * grass inside is tilled, and mycelium goes down only as seeds: on a grid every {@code spacing} blocks, plus wherever
 * the slopes mean no seed could reach (mycelium climbs only 1 block, so a 2-block step needs a seed on top), plus where
 * water would keep farmland from turning back into dirt.
 */
final class Plan {
	enum Kind {
		/** Hoe: grass to farmland, which turns into dirt by itself. */
		TILL,
		/** Break it and put mycelium in its place. */
		SEED,
		/** Shovel: grass or dirt to path (the border). */
		PATH,
		/** Swap mode: break it and put the other block in. */
		SWAP,
		/** By water (farmland there stays wet): break the grass and put plain dirt in. Mycelium if out of dirt. */
		DIRT
	}

	static final class Task {
		final BlockPos pos;
		Kind kind;
		boolean done, failed;
		/** It put a block in (rather than finding it done already). */
		boolean placed;
		/** What it put in was mycelium (not dirt). */
		boolean placedMycelium;
		/** How many of the block it had just before the last place. */
		int countBefore;
		String why = "";
		/** Why it was made a seed: grid, slope, water; "border" or "seam" for paths; "seam" for a seam being tilled back. */
		String reason = "";
		/** Left until everything else is done (taking a seam back up: the grass next to it has to be tilled first). */
		boolean late;

		Task(BlockPos pos, Kind kind) {
			this.pos = pos;
			this.kind = kind;
		}
	}

	/** How far below a column's top block it looks for grass (under overhangs, trees, cliff ledges). */
	private static final int DEPTH = 48;

	final BlockPos center;
	/** The circle's radius; 0 for a tile of a big area. */
	final int radius;
	/** Which columns, and which of them are border, seam, or seam to take back up. */
	final Region region;
	final boolean swap;
	final Block from, to;
	final List<Task> tasks = new ArrayList<>();
	/** Grass that could spread but can't be reached with a tool: something not breakable is on top. */
	final List<BlockPos> blocked = new ArrayList<>();
	/** Farmland that should be turning back into dirt. */
	final List<BlockPos> farmlandAt = new ArrayList<>();
	/** Columns in chunks that aren't loaded. */
	final List<BlockPos> unloaded = new ArrayList<>();
	/** Dirt-to-be (and mycelium) inside the border: what coverage is measured over. */
	int nodes, mycelium, farmland, gridSeeds, slopeSeeds, waterSeeds, guardSeeds, stranded, maxHops, columns;
	int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;

	private Plan(Region region, BlockPos center, int radius, Block from, Block to) {
		this.region = region;
		this.center = center;
		this.radius = radius;
		this.from = from;
		this.to = to;
		this.swap = from != null;
	}

	static boolean inside(int dx, int dz, int r) {
		return Region.inCircle(dx, dz, r);
	}

	boolean inside(int x, int z) {
		return region.inside(x, z);
	}

	/** On the outer ring: one of its 8 neighbours is outside. */
	static boolean ring(int dx, int dz, int r) {
		return Region.onRing(dx, dz, r);
	}

	/** A tile of a big area, rather than a circle. */
	boolean tile() {
		return region instanceof Region.Tile;
	}

	int seeds() {
		int n = 0;
		for (Task t : tasks) if ((t.kind == Kind.SEED || t.kind == Kind.SWAP) && !t.done && !t.failed) n++;
		return n;
	}

	int dirtSwaps() {
		int n = 0;
		for (Task t : tasks) if (t.kind == Kind.DIRT && !t.done && !t.failed) n++;
		return n;
	}

	int count(Kind k) {
		int n = 0;
		for (Task t : tasks) if (t.kind == k) n++;
		return n;
	}

	int coverage() {
		return nodes == 0 ? 100 : (int) Math.floor(mycelium * 100.0 / nodes);
	}

	// ---------------------------------------------------------------- block rules

	/** Small stuff on top of grass that can just be knocked off (it's in the way of the hoe and shovel). */
	static boolean clearable(BlockState s) {
		if (s.isAir() || !s.getFluidState().isEmpty() || s.isIn(BlockTags.LEAVES)) return false;
		if (s.isOf(Blocks.SNOW)) return true;
		if (s.isIn(BlockTags.FLOWERS)) return GkConfig.INSTANCE.clearFlowers;
		return s.isIn(BlockTags.REPLACEABLE_BY_TREES);
	}

	private static boolean exposed(BlockState above) {
		return above.isAir() || clearable(above);
	}

	/** The game's check for whether grass (or mycelium) can live under {@code above}. */
	static boolean survives(BlockState above) {
		if (above.isOf(Blocks.SNOW) && above.get(SnowBlock.LAYERS) == 1) return true;
		if (above.getFluidState().getLevel() == 8) return false;
		return ChunkLightProvider.getRealisticOpacity(Blocks.GRASS_BLOCK.getDefaultState(), above, Direction.UP, above.getOpacity()) < 15;
	}

	/** Can be spread onto (as dirt): could live there and no water on top. */
	private static boolean spreadable(BlockState above) {
		return survives(above) && !above.getFluidState().isIn(FluidTags.WATER);
	}

	/** The game's own test: farmland here would stay wet, so it would never turn back into dirt. */
	static boolean waterNearby(ClientWorld w, BlockPos pos) {
		for (BlockPos q : BlockPos.iterate(pos.add(-4, 0, -4), pos.add(4, 1, 4))) {
			if (w.getFluidState(q).isIn(FluidTags.WATER)) return true;
		}
		return false;
	}

	/** The face of a neighbour to place a block into {@code cell} against, or null. Prefers the block below. */
	static Direction placeFace(ClientWorld w, BlockPos cell) {
		if (w.getBlockState(cell.down()).isSideSolidFullSquare(w, cell.down(), Direction.UP)) return Direction.UP;
		for (Direction d : Direction.Type.HORIZONTAL) {
			BlockPos n = cell.offset(d);
			if (w.getBlockState(n).isSideSolidFullSquare(w, n, d.getOpposite())) return d.getOpposite();
		}
		return null;
	}

	private static boolean seedable(ClientWorld w, BlockPos p) {
		return placeFace(w, p) != null;
	}

	static Block block(String id) {
		Identifier i = Identifier.tryParse(id);
		if (i == null || !Registries.BLOCK.containsId(i)) return null;
		return Registries.BLOCK.get(i);
	}

	// ---------------------------------------------------------------- planning

	/**
	 * Looks at every column of the circle. {@code fresh}: a new job, so it lays out the seed grid; otherwise (checking
	 * on the work) it only adds seeds where something can't be reached.
	 */
	static Plan make(ClientWorld w, BlockPos center, int r, boolean fresh) {
		return make(w, new Region.Circle(center.getX(), center.getZ(), r), center, r, fresh);
	}

	/** A tile of a big area. {@code fresh}: the first time (lays out the seed grid); otherwise a check on it. */
	static Plan make(ClientWorld w, Project project, long tile, boolean fresh) {
		Region.Tile region = new Region.Tile(project, tile);
		int x = region.centerX(), z = region.centerZ();
		int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
		return make(w, region, new BlockPos(x, y, z), 0, fresh);
	}

	/** The same columns looked at again (to check on the work, and fix what's come back). */
	Plan again(ClientWorld w) {
		if (region instanceof Region.Tile t) return make(w, new Region.Tile(t.project, t.tile), center, radius, false);
		return make(w, region, center, radius, false);
	}

	static Plan make(ClientWorld w, Region region, BlockPos center, int r, boolean fresh) {
		GkConfig cfg = GkConfig.INSTANCE;
		Block from = null, to = null;
		if (cfg.swapMode()) {
			from = block(cfg.swapFrom);
			to = block(cfg.swapTo);
		}
		Plan plan = new Plan(region, center, r, from, to);
		if (plan.swap) plan.scanSwap(w);
		else plan.scan(w, cfg, fresh);
		return plan;
	}

	private void scanSwap(ClientWorld w) {
		BlockPos.Mutable m = new BlockPos.Mutable();
		forColumns(w, (x, z, kind) -> {
			int top = w.getTopY(Heightmap.Type.WORLD_SURFACE, x, z) - 1;
			int bottom = Math.max(w.getBottomY(), top - DEPTH);
			BlockState above = w.getBlockState(m.set(x, top + 1, z));
			for (int y = top; y >= bottom; y--) {
				BlockState s = w.getBlockState(m.set(x, y, z));
				if (s.isOf(from) && exposed(above)) add(new Task(m.toImmutable(), Kind.SWAP));
				else if (s.isOf(to) && exposed(above)) mycelium++;
				above = s;
			}
		});
		nodes = mycelium + tasks.size();
	}

	/** What a column is: inside, on the outer edge (border), or a seam next to ground still to be worked. */
	private static final int PLAIN = 0, EDGE = 1, SEAM = 2;

	private interface ColumnJob {
		void run(int x, int z, int kind);
	}

	private void forColumns(ClientWorld w, ColumnJob job) {
		int[] b = region.bounds();
		for (int x = b[0]; x <= b[2]; x++) {
			for (int z = b[1]; z <= b[3]; z++) {
				if (!region.inside(x, z)) continue;
				if (!w.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) {
					unloaded.add(new BlockPos(x, center.getY(), z));
					continue;
				}
				columns++;
				job.run(x, z, region.edge(x, z) ? EDGE : region.seam(x, z) ? SEAM : PLAIN);
			}
		}
	}

	void add(Task t) {
		tasks.add(t);
		minY = Math.min(minY, t.pos.getY());
		maxY = Math.max(maxY, t.pos.getY());
	}

	// The spread graph: every block that is or will be dirt or mycelium.
	private final Map<Long, Integer> index = new HashMap<>();
	private final List<BlockPos> nodePos = new ArrayList<>();
	private final List<Task> nodeTask = new ArrayList<>();
	private final List<Boolean> nodeSource = new ArrayList<>();

	private void node(BlockPos p, Task t, boolean source) {
		index.put(p.asLong(), nodePos.size());
		nodePos.add(p);
		nodeTask.add(t);
		nodeSource.add(source);
	}

	private void scan(ClientWorld w, GkConfig cfg, boolean fresh) {
		GkConfig.Border border = cfg.border;
		BlockPos.Mutable m = new BlockPos.Mutable();
		forColumns(w, (x, z, kind) -> {
			// Seams are always path: they're only there for a while, and mycelium there would be wasted.
			boolean seam = kind == SEAM;
			boolean ring = kind == EDGE && border != GkConfig.Border.NONE || seam;
			int top = w.getTopY(Heightmap.Type.WORLD_SURFACE, x, z) - 1;
			int bottom = Math.max(w.getBottomY(), top - DEPTH);
			BlockState above = w.getBlockState(m.set(x, top + 1, z));
			for (int y = top; y >= bottom; y--) {
				BlockState s = w.getBlockState(m.set(x, y, z));
				BlockPos p = null;
				if (s.isOf(Blocks.GRASS_BLOCK) || s.isOf(Blocks.DIRT)) {
					boolean grass = s.isOf(Blocks.GRASS_BLOCK);
					// Grass that can't live there turns into dirt by itself; dirt that can't take grass is no risk.
					if (grass ? survives(above) : spreadable(above)) {
						p = m.toImmutable();
						if (!exposed(above)) {
							// Under glass, a slab, a sapling...: alive, but no tool can get at it.
							if (grass || ring) blocked.add(p);
							else node(p, null, false);
						} else if (ring) {
							Task t = new Task(p, seam || border == GkConfig.Border.PATH ? Kind.PATH : Kind.SEED);
							t.reason = seam ? "seam" : "border";
							add(t);
							if (t.kind == Kind.SEED) node(p, t, false);
						} else if (grass) {
							Task t = new Task(p, Kind.TILL);
							add(t);
							node(p, t, false);
						} else {
							node(p, null, false);
						}
					}
				} else if (s.isOf(Blocks.MYCELIUM)) {
					p = m.toImmutable();
					node(p, null, true);
				} else if (s.isOf(Blocks.DIRT_PATH) && region.release(m.asLong())) {
					// A seam no longer needed: the hoe turns path into farmland, which dries into dirt like the rest.
					p = m.toImmutable();
					if (exposed(above)) {
						Task t = new Task(p, Kind.TILL);
						t.reason = "seam";
						t.late = true;
						add(t);
						node(p, t, false);
					}
				} else if (s.isOf(Blocks.FARMLAND) && !ring) {
					// Turning back into dirt, unless it's a farm: something planted, or watered.
					p = m.toImmutable();
					if (above.isAir() && !waterNearby(w, p)) {
						node(p, null, false);
						farmland++;
						farmlandAt.add(p);
					}
				}
				above = s;
			}
		});
		seeds(w, cfg, fresh);
		// Coverage counts what's inside the border.
		for (int i = 0; i < nodePos.size(); i++) {
			BlockPos p = nodePos.get(i);
			if (!inside(p.getX(), p.getZ())) continue;
			nodes++;
			if (nodeSource.get(i)) mycelium++;
		}
	}

	private void seeds(ClientWorld w, GkConfig cfg, boolean fresh) {
		int n = nodePos.size();
		int s = cfg.spacing;
		// Water: farmland there would stay farmland.
		for (int i = 0; i < n; i++) {
			Task t = nodeTask.get(i);
			if (t == null || t.kind != Kind.TILL) continue;
			if (waterNearby(w, t.pos)) {
				if (seedable(w, t.pos)) {
					t.kind = Kind.DIRT;
					t.reason = "water";
					waterSeeds++;
				} else {
					t.failed = true;
					t.why = "next to water and nothing to place mycelium against";
				}
			}
		}
		// Grass no tool can get at (under glass, a sapling) stays alive and keeps spreading. Mycelium on every block it
		// could spread to fences it in for good.
		for (BlockPos b : blocked) {
			for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = -3; dy <= 1; dy++) {
				Integer i = index.get(BlockPos.asLong(b.getX() + dx, b.getY() + dy, b.getZ() + dz));
				if (i == null || nodeSource.get(i)) continue;
				Task t = nodeTask.get(i);
				if (t != null && t.kind == Kind.SEED) continue;
				if (makeSeed(w, i, "guard")) guardSeeds++;
			}
		}
		// The grid: the highest grass in every s-th column (mycelium spreads down 3 but up only 1).
		if (fresh) {
			Set<Long> sourceCols = new HashSet<>();
			for (int i = 0; i < n; i++) {
				Task t = nodeTask.get(i);
				if (nodeSource.get(i) || t != null && t.kind == Kind.SEED) sourceCols.add(col(nodePos.get(i)));
			}
			Map<Long, Integer> gridTop = new HashMap<>();
			for (int i = 0; i < n; i++) {
				Task t = nodeTask.get(i);
				if (t == null || t.kind != Kind.TILL) continue;
				BlockPos p = nodePos.get(i);
				if (Math.floorMod(p.getX() - region.gridX(), s) != 0 || Math.floorMod(p.getZ() - region.gridZ(), s) != 0) continue;
				gridTop.merge(col(p), i, (a, b) -> nodePos.get(a).getY() >= nodePos.get(b).getY() ? a : b);
			}
			for (int i : gridTop.values()) {
				BlockPos p = nodePos.get(i);
				if (near(sourceCols, p, s / 2) || !seedable(w, p)) continue;
				Task t = nodeTask.get(i);
				t.kind = Kind.SEED;
				t.reason = "grid";
				gridSeeds++;
			}
		}
		// Reach: how many spreads from the nearest seed each block is.
		int[] dist = new int[n];
		boolean[] given = new boolean[n];
		java.util.Arrays.fill(dist, Integer.MAX_VALUE);
		ArrayDeque<Integer> q = new ArrayDeque<>();
		for (int i = 0; i < n; i++) {
			Task t = nodeTask.get(i);
			if (nodeSource.get(i) || t != null && t.kind == Kind.SEED && !t.failed) {
				dist[i] = 0;
				q.add(i);
			}
		}
		spread(dist, q);
		// Nothing reaches it (a ledge, a step up of 2 or more): seed the highest such block, then look again.
		List<Integer> order = new ArrayList<>();
		for (int i = 0; i < n; i++) order.add(i);
		order.sort((a, b) -> Integer.compare(nodePos.get(b).getY(), nodePos.get(a).getY()));
		for (int i : order) {
			if (dist[i] != Integer.MAX_VALUE || given[i]) continue;
			if (makeSeed(w, i, "slope")) {
				slopeSeeds++;
				dist[i] = 0;
				q.add(i);
				spread(dist, q);
			} else {
				given[i] = true;
				stranded++;
			}
		}
		// Too far from any seed (a long thin ridge, say): it would take ages. Seed the furthest, then look again.
		int limit = Math.max(2, s);
		for (int round = 0; round < 5000; round++) {
			int far = -1;
			for (int i = 0; i < n; i++) if (!given[i] && dist[i] != Integer.MAX_VALUE && dist[i] > limit && (far < 0 || dist[i] > dist[far])) far = i;
			if (far < 0) break;
			if (makeSeed(w, far, "far")) {
				slopeSeeds++;
				dist[far] = 0;
				q.add(far);
				spread(dist, q);
			} else {
				given[far] = true;
			}
		}
		maxHops = 0;
		for (int i = 0; i < n; i++) if (dist[i] != Integer.MAX_VALUE) maxHops = Math.max(maxHops, dist[i]);
	}

	/** Turns a block into a seed: its till task, or a new one for plain dirt. */
	private boolean makeSeed(ClientWorld w, int i, String reason) {
		BlockPos p = nodePos.get(i);
		if (!seedable(w, p)) return false;
		Task t = nodeTask.get(i);
		if (t == null) {
			// Plain dirt: only if it's out in the open (break it and put mycelium in).
			if (!w.getBlockState(p.up()).isAir() && !clearable(w.getBlockState(p.up()))) return false;
			t = new Task(p, Kind.SEED);
			add(t);
			nodeTask.set(i, t);
		}
		if (t.failed) return false;
		t.kind = Kind.SEED;
		t.reason = reason;
		return true;
	}

	/** Breadth-first: a block spreads to blocks 1 sideways and 3 down to 1 up from it. */
	private void spread(int[] dist, ArrayDeque<Integer> q) {
		while (!q.isEmpty()) {
			int u = q.poll();
			BlockPos p = nodePos.get(u);
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					for (int dy = -3; dy <= 1; dy++) {
						if (dx == 0 && dy == 0 && dz == 0) continue;
						Integer v = index.get(BlockPos.asLong(p.getX() + dx, p.getY() + dy, p.getZ() + dz));
						if (v == null || dist[v] <= dist[u] + 1) continue;
						dist[v] = dist[u] + 1;
						q.add(v);
					}
				}
			}
		}
	}

	private static long col(BlockPos p) {
		return BlockPos.asLong(p.getX(), 0, p.getZ());
	}

	private static boolean near(Set<Long> cols, BlockPos p, int r) {
		for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) if (cols.contains(BlockPos.asLong(p.getX() + dx, 0, p.getZ() + dz))) return true;
		return false;
	}
}
