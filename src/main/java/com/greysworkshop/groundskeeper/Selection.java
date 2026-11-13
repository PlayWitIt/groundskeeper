package com.greysworkshop.groundskeeper;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

/**
 * Picking the circle. Press O where the middle should be, walk out to the edge (the ring follows you), press O again:
 * it plans the job and shows it (ring, seed spots in purple, blocks it can't get at in red) with what it will take.
 * O a third time starts. Shift+O, or /gk cancel, drops the selection.
 */
final class Selection {
	enum Mode {
		NONE, SIZING, READY
	}

	static Mode mode = Mode.NONE;
	static BlockPos center;
	static int radius;
	static Plan preview;
	private static int ticks;

	private Selection() {}

	static void key(MinecraftClient client, boolean shift) {
		ClientPlayerEntity p = client.player;
		if (p == null || client.world == null) return;
		if (Job.running()) {
			Job.stop(client, "Stopped.");
			return;
		}
		if (shift) {
			cancel(p);
			return;
		}
		switch (mode) {
			case NONE -> {
				center = Walker.feet(p).down();
				mode = Mode.SIZING;
				Job.chat(p, Text.literal("Middle set at " + center.getX() + " " + center.getZ() + ". Walk out to where the edge should be and press O.")
						.formatted(Formatting.GREEN));
			}
			case SIZING -> set(client, p, center, sizingRadius(p));
			case READY -> Job.start(client, Plan.make(client.world, center, radius, true));
		}
	}

	/** Circle around {@code c} (a block, the middle at ground level). Plans it and shows what it will do. */
	static void set(MinecraftClient client, ClientPlayerEntity p, BlockPos c, int r) {
		set(client, p, c, r, true);
	}

	static void set(MinecraftClient client, ClientPlayerEntity p, BlockPos c, int r, boolean tell) {
		center = c;
		radius = Math.max(2, Math.min(160, r));
		preview = Plan.make(client.world, center, radius, true);
		mode = Mode.READY;
		if (tell) describe(p, preview);
	}

	static void cancel(ClientPlayerEntity p) {
		if (mode == Mode.NONE) return;
		mode = Mode.NONE;
		preview = null;
		Job.chat(p, Text.literal("Selection cleared.").formatted(Formatting.GRAY));
	}

	private static int sizingRadius(ClientPlayerEntity p) {
		return (int) Math.round(Math.hypot(p.getX() - center.getX() - 0.5, p.getZ() - center.getZ() - 0.5));
	}

	static void describe(ClientPlayerEntity p, Plan plan) {
		PlayerInventory inv = p.getInventory();
		Job.chat(p, Text.literal("Radius " + plan.radius + " around " + plan.center.getX() + " " + plan.center.getY() + " " + plan.center.getZ()
				+ " · " + String.format("%,d", plan.columns) + " columns").formatted(Formatting.GREEN));
		int seeds = plan.seeds();
		if (plan.swap) {
			line(p, "Swap " + plan.count(Plan.Kind.SWAP) + " " + name(plan.from) + " for " + name(plan.to), Formatting.WHITE);
		} else {
			int till = plan.count(Plan.Kind.TILL), path = plan.count(Plan.Kind.PATH);
			int grass = till + seeds + path + plan.waterSeeds;
			if (till > 0) line(p, "Till " + fmt(till) + " grass (hoe: free, it turns to dirt by itself in a minute or so)", Formatting.WHITE);
			StringBuilder why = new StringBuilder();
			if (plan.gridSeeds > 0) why.append(plan.gridSeeds).append(" grid");
			if (plan.guardSeeds > 0) why.append(why.isEmpty() ? "" : " · ").append(plan.guardSeeds).append(" fencing in grass it can't reach");
			if (plan.slopeSeeds > 0) why.append(why.isEmpty() ? "" : " · ").append(plan.slopeSeeds).append(" for slopes it can't climb");
			line(p, "Mycelium seeds: " + fmt(seeds) + (why.isEmpty() ? "" : " (" + why + ")") + ", instead of " + fmt(grass) + " to replace it all",
					Formatting.LIGHT_PURPLE);
			if (plan.waterSeeds > 0) {
				int dirt = Job.itemCount(inv, net.minecraft.item.Items.DIRT);
				line(p, "By water: " + fmt(plan.waterSeeds) + " grass swapped for dirt (farmland there would stay wet). You have " + fmt(dirt) + " dirt"
						+ (dirt >= plan.waterSeeds ? " ✔" : ": the rest gets mycelium (bring dirt; every swap also drops one)"), Formatting.WHITE);
			}
			if (path > 0) line(p, "Border: " + fmt(path) + " dirt path (shovel: free; grass can't cross it)", Formatting.WHITE);
			if (GkConfig.INSTANCE.border == GkConfig.Border.NONE) line(p, "No border: grass outside will keep fighting the edge (/gk border path)", Formatting.YELLOW);
			if (plan.mycelium > 0) line(p, "Already mycelium: " + fmt(plan.mycelium), Formatting.GRAY);
		}
		if (!plan.blocked.isEmpty()) line(p, plan.blocked.size() + " grass it can't get at (a sapling, glass, a slab on top), shown in red: /gk blocked. It still spreads,"
				+ " so it gets fenced in with mycelium. Clear those blocks yourself to save the mycelium.", Formatting.RED);
		if (plan.stranded > 0) line(p, plan.stranded + " dirt nothing can reach and it can't seed", Formatting.YELLOW);
		if (!plan.unloaded.isEmpty()) line(p, fmt(plan.unloaded.size()) + " columns aren't loaded yet: it will walk over to them", Formatting.YELLOW);
		// What it needs, and whether you have it.
		int have = Job.itemCount(inv, Job.placeItem());
		boolean enough = have >= seeds;
		line(p, "You have " + fmt(have) + " " + Job.placeName() + (enough ? " ✔" : " ✘ (" + fmt(seeds - have) + " short)"), enough ? Formatting.GREEN : Formatting.RED);
		if (!plan.swap) {
			boolean hoe = Job.best(inv, s -> s.isIn(ItemTags.HOES)) >= 0, shovel = Job.best(inv, s -> s.isIn(ItemTags.SHOVELS)) >= 0;
			boolean needShovel = plan.count(Plan.Kind.PATH) > 0;
			line(p, "Hoe " + (hoe ? "✔" : "✘ needed") + " · shovel " + (shovel ? "✔" : needShovel ? "✘ needed for the border" : "✘ (faster with one)"),
					hoe && (shovel || !needShovel) ? Formatting.GREEN : Formatting.RED);
			int s = GkConfig.INSTANCE.spacing;
			line(p, "Then it spreads on its own while you stay nearby: most of it in about " + (3 + 2 * s) + " minutes of daylight, all of it in about "
					+ (18 + 3 * s) + ". Mycelium doesn't spread at night.", Formatting.GRAY);
		}
		String tools = Job.toolWarning(inv, plan);
		if (tools != null) line(p, tools, Formatting.YELLOW);
		String bad = Job.missing(inv, plan);
		line(p, bad == null ? "Numpad 1 to start · /gk cancel to drop it" : bad, bad == null ? Formatting.AQUA : Formatting.RED);
	}

	private static void line(ClientPlayerEntity p, String s, Formatting f) {
		p.sendMessage(Text.literal("  " + s).formatted(f), false);
	}

	private static String fmt(int n) {
		return String.format("%,d", n);
	}

	private static String name(net.minecraft.block.Block b) {
		return b == null ? "?" : b.getName().getString();
	}

	// ---------------------------------------------------------------- the preview

	private static final ParticleEffect RING = new DustParticleEffect(0xE0A040, 1.2f), SEED = new DustParticleEffect(0xB060F0, 1.6f),
			BLOCKED = new DustParticleEffect(0xFF2020, 1.6f);

	static void tick(MinecraftClient client) {
		ClientPlayerEntity p = client.player;
		ClientWorld w = client.world;
		if (p == null || w == null || mode == Mode.NONE) return;
		ticks++;
		if (mode == Mode.SIZING) {
			int r = sizingRadius(p);
			p.sendMessage(Text.literal("Radius " + r + " · press O to set it here · Shift+O cancels").formatted(Formatting.GREEN), true);
			if (ticks % 5 == 0) ring(w, center, Math.max(2, r), RING);
			return;
		}
		if (Job.running() || ticks % 10 != 0) return;
		ring(w, center, radius, RING);
		if (preview == null) return;
		for (Plan.Task t : preview.tasks) {
			if (t.kind == Plan.Kind.SEED || t.kind == Plan.Kind.SWAP) dot(w, t.pos, SEED);
		}
		for (BlockPos b : preview.blocked) dot(w, b, BLOCKED);
	}

	private static void ring(ClientWorld w, BlockPos c, int r, ParticleEffect fx) {
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				if (!Plan.inside(dx, dz, r) || !Plan.ring(dx, dz, r)) continue;
				int x = c.getX() + dx, z = c.getZ() + dz;
				int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
				w.addParticleClient(fx, x + 0.5, y + 0.15, z + 0.5, 0, 0, 0);
			}
		}
	}

	private static void dot(ClientWorld w, BlockPos b, ParticleEffect fx) {
		w.addParticleClient(fx, b.getX() + 0.5, b.getY() + 1.3, b.getZ() + 0.5, 0, 0, 0);
	}
}
