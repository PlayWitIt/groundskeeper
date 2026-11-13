package com.greysworkshop.groundskeeper;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.item.Items;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.Heightmap;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.rule.GameRules;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Development-only, {@code ./gradlew runSelfTest}. Builds a mini mountain on a superflat world: a cone with a 3-high
 * plateau (mycelium can't climb onto it), a lone spike, an overhang with grass under it, a pond, plants, a sapling and a
 * glass block on grass. Runs the job for real in survival with a hoe, shovel, a few loose mycelium and a shulker box of
 * it; then speeds up random ticks and checks the circle goes all mycelium and grass from outside never gets in.
 */
final class SelfTest {
	private static final String WORLD = "groundskeeper-selftest";
	private static final int R = 16, GROUND = -61;
	private static final BlockPos SAPLING = new BlockPos(5, 0, -5), GLASS = new BlockPos(-4, 0, -8);
	private static boolean started;
	private static int tick = -1, phaseStart, passed, failed;
	private static String phase = "setup";
	private static int myceliumBefore, outsideGrassBefore;
	private static float hitYaw;
	private static int hitDone;

	private SelfTest() {}

	static void start() {
		log("Self-test armed");
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (!started && screen instanceof net.minecraft.client.gui.screen.AccessibilityOnboardingScreen) {
				client.options.onboardAccessibility = false;
				client.send(() -> client.setScreen(new TitleScreen()));
				return;
			}
			if (started || !(screen instanceof TitleScreen)) return;
			started = true;
			client.send(() -> openWorld(client));
		});
		ClientTickEvents.END_CLIENT_TICK.register(SelfTest::tick);
	}

	private static void openWorld(MinecraftClient client) {
		client.options.pauseOnLostFocus = false;
		deleteTree(client.runDirectory.toPath().resolve("saves").resolve(WORLD));
		try {
			GkConfig.useFile(Files.createTempDirectory("groundskeeper").resolve("groundskeeper.json"));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		LevelInfo info = new LevelInfo(WORLD, GameMode.SURVIVAL, false, Difficulty.PEACEFUL, true,
				new GameRules(FeatureFlags.DEFAULT_ENABLED_FEATURES), DataConfiguration.SAFE_MODE);
		client.createIntegratedServerLoader().createAndStart(WORLD, info, GeneratorOptions.createTestWorld(),
				WorldPresets::createTestOptions, new TitleScreen());
	}

	private static void deleteTree(Path dir) {
		try (Stream<Path> files = Files.exists(dir) ? Files.walk(dir) : Stream.empty()) {
			files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		} catch (IOException e) {
			log("Could not clear " + dir + ": " + e);
		}
	}

	/** Mountain height above the flat ground at a column. */
	static int height(int x, int z) {
		double d = Math.hypot(x, z);
		int h = (int) Math.round(9 * Math.max(0, 1 - d / 13.0));
		if (x >= 1 && x <= 3 && z >= 6 && z <= 8) h += 3; // a plateau off the grid, 3 up from its sides
		if (x == -7 && z == 2) h += 4; // a lone spike
		return h;
	}

	private static void tick(MinecraftClient client) {
		if (client.player == null || client.world == null || client.getServer() == null) return;
		tick++;
		int since = tick - phaseStart;
		switch (phase) {
			case "setup" -> {
				if (tick == 20) build(client);
				if (tick == 140) {
					command(client, "tp @p 0.5 " + (GROUND + height(0, 0) + 1) + " -1.5");
					// The real kit: hoe, a silk touch and a fortune shovel, armour, mycelium in the hotbar and a full box of it.
					command(client, "give @p minecraft:diamond_hoe");
					command(client, "give @p minecraft:diamond_shovel[minecraft:enchantments={\"minecraft:silk_touch\":1}]");
					command(client, "give @p minecraft:diamond_shovel[minecraft:enchantments={\"minecraft:fortune\":3}]");
					command(client, "give @p minecraft:mycelium 16");
					command(client, "give @p minecraft:purple_shulker_box[minecraft:container=[{slot:0,item:{id:\"minecraft:mycelium\",count:64}},{slot:1,item:{id:\"minecraft:mycelium\",count:64}},{slot:2,item:{id:\"minecraft:mycelium\",count:64}},{slot:3,item:{id:\"minecraft:mycelium\",count:64}},{slot:4,item:{id:\"minecraft:mycelium\",count:64}},{slot:5,item:{id:\"minecraft:mycelium\",count:64}},{slot:6,item:{id:\"minecraft:mycelium\",count:64}},{slot:7,item:{id:\"minecraft:mycelium\",count:64}},{slot:8,item:{id:\"minecraft:mycelium\",count:64}},{slot:9,item:{id:\"minecraft:mycelium\",count:64}},{slot:10,item:{id:\"minecraft:mycelium\",count:64}},{slot:11,item:{id:\"minecraft:mycelium\",count:64}},{slot:12,item:{id:\"minecraft:mycelium\",count:64}},{slot:13,item:{id:\"minecraft:mycelium\",count:64}},{slot:14,item:{id:\"minecraft:mycelium\",count:64}},{slot:15,item:{id:\"minecraft:mycelium\",count:64}},{slot:16,item:{id:\"minecraft:mycelium\",count:64}},{slot:17,item:{id:\"minecraft:mycelium\",count:64}},{slot:18,item:{id:\"minecraft:mycelium\",count:64}},{slot:19,item:{id:\"minecraft:mycelium\",count:64}},{slot:20,item:{id:\"minecraft:mycelium\",count:64}},{slot:21,item:{id:\"minecraft:mycelium\",count:64}},{slot:22,item:{id:\"minecraft:mycelium\",count:64}},{slot:23,item:{id:\"minecraft:mycelium\",count:64}},{slot:24,item:{id:\"minecraft:mycelium\",count:64}},{slot:25,item:{id:\"minecraft:mycelium\",count:64}},{slot:26,item:{id:\"minecraft:mycelium\",count:64}}]]");
					command(client, "item replace entity @p armor.head with minecraft:diamond_helmet");
					command(client, "item replace entity @p armor.chest with minecraft:diamond_chestplate");
					command(client, "item replace entity @p armor.legs with minecraft:diamond_leggings");
					command(client, "item replace entity @p armor.feet with minecraft:diamond_boots");
				}
				if (tick == 200) {
					outsideGrassBefore = server(client, s -> outsideGrass(s.getOverworld()));
					myceliumBefore = Job.itemCount(client.player.getInventory(), Items.MYCELIUM);
					Selection.set(client, client.player, new BlockPos(0, GROUND, 0), R);
					Plan p = Selection.preview;
					log("plan: till " + p.count(Plan.Kind.TILL) + ", seeds " + p.seeds() + " (grid " + p.gridSeeds + ", slope " + p.slopeSeeds + ", guard " + p.guardSeeds + "), dirt by water "
							+ p.waterSeeds + ", path " + p.count(Plan.Kind.PATH) + ", blocked " + p.blocked + ", max hops " + p.maxHops);
					check("the plan seeds the plateau and the spike (slope seeds: " + p.slopeSeeds + ")", seedAt(p, 1, 3, 6, 8) && seedAt(p, -7, -7, 2, 2));
					check("grass by the pond is swapped for dirt, not seeded (" + p.waterSeeds + ")", p.waterSeeds > 0 && p.tasks.stream().noneMatch(t -> t.kind == Plan.Kind.SEED && t.reason.equals("water")));
					check("the sapling's and the glass's grass are reported (" + p.blocked + ")",
							p.blocked.stream().anyMatch(b -> b.getX() == SAPLING.getX() && b.getZ() == SAPLING.getZ())
									&& p.blocked.stream().anyMatch(b -> b.getX() == GLASS.getX() && b.getZ() == GLASS.getZ()));
					check("the grass under the overhang is in the plan",
							p.tasks.stream().anyMatch(t -> t.pos.getX() == -9 && t.pos.getZ() == -3 && t.pos.getY() < topY(client, -9, -3) - 1));
					check("far fewer seeds than grass (" + p.seeds() + " of " + p.tasks.size() + ")", p.seeds() * 8 < p.tasks.size());
					long silky = java.util.stream.IntStream.range(0, 36).mapToObj(i -> client.player.getInventory().getStack(i)).filter(Job::silk).count();
					check("kit: one silk touch shovel spotted, no dirt (" + silky + ", " + Job.itemCount(client.player.getInventory(), Items.DIRT) + ")",
							silky == 1 && Job.itemCount(client.player.getInventory(), Items.DIRT) == 0);
					check("plan needs more than the loose mycelium (so it must unpack the box)", p.seeds() > 6);
				}
				if (tick == 240) screenshot(client, "1_preview");
				if (tick == 260) {
					Job.start(client, Plan.make(client.world, Selection.center, Selection.radius, true));
					check("started", Job.running());
					next("work");
				}
			}
			case "work" -> {
				if (since % 400 == 0) log("work: " + Job.status.getString() + " · " + Job.summary() + " · left " + Job.left());
				// Hit: it lets go of the keys and the camera (for kill aura), then carries on 3 seconds after.
				if (since == 600) command(client, "damage @p 3 minecraft:generic");
				if (since == 606) {
					hitYaw = client.player.getYaw();
					hitDone = Job.tilled + Job.pathed + Job.seeded + Job.dirted;
					check("hit: holding (" + Job.status.getString() + ")", Job.status.getString().startsWith("Holding"));
				}
				if (since == 640) {
					check("hit: hands off, camera and keys alone (yaw " + hitYaw + " -> " + client.player.getYaw() + ")",
							client.player.getYaw() == hitYaw && !client.options.forwardKey.isPressed() && !client.options.jumpKey.isPressed()
									&& Job.tilled + Job.pathed + Job.seeded + Job.dirted == hitDone);
				}
				if (since == 720) check("hit: carried on after 3 seconds (" + Job.status.getString() + ")", !Job.status.getString().startsWith("Holding"));
				// Numpad 2 pauses, Numpad 1 carries on.
				if (since == 1000) Job.togglePause(client);
				if (since == 1005) hitDone = Job.tilled + Job.pathed + Job.seeded + Job.dirted;
				if (since == 1100) {
					check("pause: nothing done while paused (" + Job.status.getString() + ")",
							Job.paused && Job.tilled + Job.pathed + Job.seeded + Job.dirted == hitDone && !client.options.forwardKey.isPressed());
					Job.startOrResume(client);
				}
				if (since == 1300) check("pause: carries on after Numpad 1", !Job.paused && Job.tilled + Job.pathed + Job.seeded + Job.dirted > hitDone);
				if (Job.state == Job.State.WATCH) {
					log("work done in " + since + " ticks (" + since / 20 + " s): " + Job.summary());
					check("unpacked the shulker box (" + Job.unpacked + " mycelium) and picked it back up",
							Job.unpacked > 0 && countBoxes(client) == 1);
					int used = myceliumBefore - Job.itemCount(client.player.getInventory(), Items.MYCELIUM);
					check("mycelium used = seeds placed (" + used + " vs " + Job.seeded + ")", used == Job.seeded);
					check("skipped little (" + Job.failed + ")", Job.failed.size() <= 3);
					int[] r = server(client, s -> new int[] {liveGrassInside(s.getOverworld()), ringOpen(s.getOverworld())}, null);
					check("no workable grass left inside (" + r[0] + ")", r[0] == 0);
					check("the border ring is all path (" + r[1] + " open)", r[1] == 0);
					screenshot(client, "2_worked");
					// Fast-forward: random ticks 100x. Grass outside races in, mycelium spreads.
					command(client, "gamerule random_tick_speed 300");
					command(client, "gamerule randomTickSpeed 300");
					next("spread");
				}
				if (!Job.running()) fail(client, "stopped while working: " + Job.stopReason);
				else if (since > 20 * 60 * 8) fail(client, "work took too long: " + Job.summary());
			}
			case "spread" -> {
				if (since % 200 == 0) {
					int[] r = server(client, s -> new int[] {mycelium(s.getOverworld()), liveGrassInside(s.getOverworld())}, null);
					log("spread " + since / 20 + " s: " + r[0] + " mycelium inside, " + r[1] + " grass inside · " + Job.status.getString());
				}
				if (!Job.running()) {
					log("watch ended: " + Job.stopReason + " after " + since / 20 + " s");
					check("watch finished with it all mycelium (" + Job.stopReason + ")", Job.stopReason.startsWith("Done"));
					int[] r = server(client, s -> new int[] {liveGrassInside(s.getOverworld()), ringOpen(s.getOverworld()), outsideGrass(s.getOverworld()),
							dirtInside(s.getOverworld())}, null);
					check("no grass inside the circle, apart from under the sapling and glass (" + r[0] + ")", r[0] == 0);
					check("border still path, nothing got in (" + r[1] + ")", r[1] == 0);
					check("grass outside untouched (" + r[2] + " vs " + outsideGrassBefore + ")", r[2] >= outsideGrassBefore);
					check("no plain dirt left inside (" + r[3] + ")", r[3] == 0);
					command(client, "gamemode spectator @p");
					command(client, "tp @p 0.5 -38 -24.5 0 40");
					next("photo");
				} else if (since > 20 * 60 * 6) fail(client, "still not all mycelium after 6 minutes at 100x: " + Job.status.getString());
			}
			case "photo" -> {
				if (since == 60) screenshot(client, "3_done");
				if (since == 80) finish(client);
			}
			default -> {}
		}
	}

	private static boolean seedAt(Plan p, int x0, int x1, int z0, int z1) {
		return p.tasks.stream().anyMatch(t -> t.kind == Plan.Kind.SEED && t.pos.getX() >= x0 && t.pos.getX() <= x1 && t.pos.getZ() >= z0 && t.pos.getZ() <= z1);
	}

	private static int topY(MinecraftClient client, int x, int z) {
		return client.world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
	}

	private static void build(MinecraftClient client) {
		command(client, "gamerule doDaylightCycle false");
		command(client, "gamerule advance_time false");
		command(client, "time set 6000");
		command(client, "gamerule doWeatherCycle false");
		command(client, "gamerule advance_weather false");
		command(client, "weather clear");
		server(client, s -> {
			ServerWorld w = s.getOverworld();
			for (int x = -30; x <= 30; x++) {
				for (int z = -30; z <= 30; z++) {
					int h = height(x, z);
					for (int y = GROUND; y < GROUND + h; y++) w.setBlockState(new BlockPos(x, y, z), Blocks.DIRT.getDefaultState(), Block.NOTIFY_LISTENERS);
					BlockPos top = new BlockPos(x, GROUND + h, z);
					w.setBlockState(top, Blocks.GRASS_BLOCK.getDefaultState(), Block.NOTIFY_LISTENERS);
					// Plants: short grass everywhere-ish, some flowers.
					int k = Math.floorMod(x * 31 + z * 17, 7);
					BlockState plant = k == 0 ? Blocks.SHORT_GRASS.getDefaultState() : k == 3 ? Blocks.POPPY.getDefaultState() : null;
					if (plant != null) w.setBlockState(top.up(), plant, Block.NOTIFY_LISTENERS);
				}
			}
			// An overhang: stone 3 above two grass blocks.
			for (int x = -9; x <= -8; x++) for (int z = -3; z <= -2; z++) w.setBlockState(new BlockPos(x, GROUND + height(x, z) + 3, z), Blocks.STONE.getDefaultState(), 3);
			// A pond at the foot.
			for (int x = 9; x <= 11; x++) for (int z = -2; z <= 0; z++) {
				BlockPos q = new BlockPos(x, GROUND + height(x, z), z);
				w.setBlockState(q.up(), Blocks.AIR.getDefaultState(), 3);
				w.setBlockState(q, Blocks.WATER.getDefaultState(), 3);
			}
			// A sapling and a glass block on grass, snow on the peak.
			BlockPos sap = new BlockPos(SAPLING.getX(), GROUND + height(SAPLING.getX(), SAPLING.getZ()) + 1, SAPLING.getZ());
			w.setBlockState(sap, Blocks.OAK_SAPLING.getDefaultState(), 3);
			BlockPos glass = new BlockPos(GLASS.getX(), GROUND + height(GLASS.getX(), GLASS.getZ()) + 1, GLASS.getZ());
			w.setBlockState(glass, Blocks.GLASS.getDefaultState(), 3);
			w.setBlockState(new BlockPos(0, GROUND + height(0, 0) + 1, 0), Blocks.SNOW.getDefaultState(), 3);
			return 0;
		});
	}

	// ---------------------------------------------------------------- server-side checks

	private static boolean in(int x, int z) {
		return Plan.inside(x, z, R);
	}

	private static boolean ringCol(int x, int z) {
		return in(x, z) && Plan.ring(x, z, R);
	}

	/** The top grass or dirt-ish block of a column (the flat world's ground or the mountain). */
	private static BlockPos ground(ServerWorld w, int x, int z) {
		int y = w.getTopY(Heightmap.Type.WORLD_SURFACE, x, z) - 1;
		for (; y > GROUND - 3; y--) {
			BlockState s = w.getBlockState(new BlockPos(x, y, z));
			if (s.isOf(Blocks.GRASS_BLOCK) || s.isOf(Blocks.DIRT) || s.isOf(Blocks.MYCELIUM) || s.isOf(Blocks.FARMLAND) || s.isOf(Blocks.DIRT_PATH)) {
				return new BlockPos(x, y, z);
			}
		}
		return null;
	}

	/** Grass inside the circle that could live and that a tool could get at (so: should have been dealt with). */
	private static int liveGrassInside(ServerWorld w) {
		int n = 0;
		for (int x = -R; x <= R; x++) {
			for (int z = -R; z <= R; z++) {
				if (!in(x, z)) continue;
				int top = w.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
				for (int y = top; y > GROUND - 3; y--) {
					BlockPos q = new BlockPos(x, y, z);
					if (!w.getBlockState(q).isOf(Blocks.GRASS_BLOCK)) continue;
					BlockState above = w.getBlockState(q.up());
					if (Plan.survives(above) && (above.isAir() || Plan.clearable(above))) n++;
				}
			}
		}
		return n;
	}

	private static int dirtInside(ServerWorld w) {
		int n = 0;
		for (int x = -R; x <= R; x++) {
			for (int z = -R; z <= R; z++) {
				if (!in(x, z) || ringCol(x, z)) continue;
				BlockPos g = ground(w, x, z);
				if (g != null && (w.getBlockState(g).isOf(Blocks.DIRT) || w.getBlockState(g).isOf(Blocks.FARMLAND)) && w.getBlockState(g.up()).isAir()) n++;
			}
		}
		return n;
	}

	private static int mycelium(ServerWorld w) {
		int n = 0;
		for (int x = -R; x <= R; x++) for (int z = -R; z <= R; z++) {
			BlockPos g = in(x, z) ? ground(w, x, z) : null;
			if (g != null && w.getBlockState(g).isOf(Blocks.MYCELIUM)) n++;
		}
		return n;
	}

	/** Ring columns whose top is still grass or dirt. */
	private static int ringOpen(ServerWorld w) {
		int n = 0;
		for (int x = -R; x <= R; x++) for (int z = -R; z <= R; z++) {
			if (!ringCol(x, z)) continue;
			BlockPos g = ground(w, x, z);
			if (g != null && (w.getBlockState(g).isOf(Blocks.GRASS_BLOCK) || w.getBlockState(g).isOf(Blocks.DIRT))) {
				log("ring open at " + g.toShortString() + ": " + w.getBlockState(g).getBlock());
				n++;
			}
		}
		return n;
	}

	private static int outsideGrass(ServerWorld w) {
		int n = 0;
		for (int x = -26; x <= 26; x++) for (int z = -26; z <= 26; z++) {
			if (in(x, z)) continue;
			BlockPos g = ground(w, x, z);
			if (g != null && w.getBlockState(g).isOf(Blocks.GRASS_BLOCK)) n++;
		}
		return n;
	}

	private static int countBoxes(MinecraftClient client) {
		var inv = client.player.getInventory();
		int n = 0;
		for (int i = 0; i < inv.size(); i++) if (Job.isBox(inv.getStack(i))) n++;
		return n;
	}

	// ---------------------------------------------------------------- plumbing

	private static int server(MinecraftClient client, Function<MinecraftServer, Integer> job) {
		MinecraftServer s = client.getServer();
		try {
			return s.submit(() -> job.apply(s)).get();
		} catch (InterruptedException | ExecutionException e) {
			log("server job failed: " + e);
			return Integer.MIN_VALUE;
		}
	}

	private static int[] server(MinecraftClient client, Function<MinecraftServer, int[]> job, Void unused) {
		MinecraftServer s = client.getServer();
		try {
			return s.submit(() -> job.apply(s)).get();
		} catch (InterruptedException | ExecutionException e) {
			log("server job failed: " + e);
			return new int[8];
		}
	}

	private static void command(MinecraftClient client, String command) {
		server(client, s -> {
			s.getCommandManager().parseAndExecute(s.getCommandSource(), command);
			return 0;
		});
	}

	private static void next(String name) {
		phase = name;
		phaseStart = tick;
		log("phase " + name);
	}

	private static void fail(MinecraftClient client, String why) {
		check(why, false);
		finish(client);
	}

	private static void finish(MinecraftClient client) {
		log("RESULT " + passed + " passed, " + failed + " failed");
		next("done");
		client.scheduleStop();
	}

	private static void screenshot(MinecraftClient client, String name) {
		client.getToastManager().clear();
		ScreenshotRecorder.saveScreenshot(client.runDirectory, "gk_" + name + ".png", client.getFramebuffer(), 1, message -> {});
		log("Screenshot " + name);
	}

	private static void check(String what, boolean ok) {
		if (ok) passed++;
		else failed++;
		log((ok ? "PASS " : "FAIL ") + what);
	}

	private static void log(String message) {
		Groundskeeper.LOGGER.info("[SELFTEST] {}", message);
	}
}
