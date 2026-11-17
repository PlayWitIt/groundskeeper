package com.greysworkshop.groundskeeper;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.CreeperEntity;
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
 * Development-only, {@code ./gradlew runAreaTest}. The 0.3.0 parts, for real in survival on a flat grass world:
 * a 48 × 32 area in 16-block tiles with a cut-out (a "base"), worked tile by tile; seam paths between worked and
 * unworked tiles, taken back up later; progress read back from the file as after a restart; eating, the totem;
 * digging in at night and climbing out in the morning; hiding from a warden; running from a lit creeper; and at the
 * end every tile all mycelium, the cut-out untouched and no seams left.
 */
final class AreaTest {
	private static final String WORLD = "groundskeeper-areatest";
	private static final int GROUND = -61, X0 = 0, Z0 = 0, X1 = 47, Z1 = 31, CX0 = 20, CZ0 = 4, CX1 = 27, CZ1 = 11;
	private static boolean started;
	private static int tick = -1, phaseStart, passed, failed;
	private static String phase = "setup";
	private static int myceliumBefore, breadBefore, worked;
	private static boolean seamsChecked, restarted, nightDone, wardenDone, creeperDone, fed;
	private static BlockPos roof;
	private static double creeperStart;

	private AreaTest() {}

	static void start() {
		log("Area test armed");
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
		ClientTickEvents.END_CLIENT_TICK.register(AreaTest::tick);
	}

	private static void openWorld(MinecraftClient client) {
		client.options.pauseOnLostFocus = false;
		deleteTree(client.runDirectory.toPath().resolve("saves").resolve(WORLD));
		// No area left over from an earlier run.
		deleteTree(client.runDirectory.toPath().resolve("config").resolve("groundskeeper").resolve("areas").resolve("local_" + WORLD));
		try {
			GkConfig.useFile(Files.createTempDirectory("groundskeeper").resolve("groundskeeper.json"));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		GkConfig.INSTANCE.tileChunks = 1;
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

	private static void tick(MinecraftClient client) {
		if (client.player == null || client.world == null || client.getServer() == null) return;
		tick++;
		int since = tick - phaseStart;
		switch (phase) {
			case "setup" -> {
				if (tick == 20) build(client);
				if (tick == 140) {
					command(client, "tp @p -3.5 " + (GROUND + 1) + " -3.5");
					command(client, "give @p minecraft:diamond_hoe");
					command(client, "give @p minecraft:diamond_shovel[minecraft:enchantments={\"minecraft:silk_touch\":1}]");
					command(client, "give @p minecraft:diamond_shovel[minecraft:enchantments={\"minecraft:fortune\":3}]");
					command(client, "give @p minecraft:mycelium 64");
					command(client, "give @p minecraft:mycelium 64");
					command(client, "give @p minecraft:bread 8");
					command(client, "give @p minecraft:totem_of_undying");
					command(client, "item replace entity @p armor.head with minecraft:diamond_helmet");
					command(client, "item replace entity @p armor.chest with minecraft:diamond_chestplate");
					command(client, "item replace entity @p armor.legs with minecraft:diamond_leggings");
					command(client, "item replace entity @p armor.feet with minecraft:diamond_boots");
				}
				if (tick == 200) {
					Areas.add(client, Shapes.Shape.rect(X0, Z0, X1, Z1));
					Areas.add(client, Shapes.Shape.rect(CX0, CZ0, CX1, CZ1).cutting());
					Project p = Areas.get(client);
					check("area: 6 tiles of 16, 48 × 32 less the 8 × 8 cut-out (" + (p == null ? "none" : p.tiles.size() + " tiles, " + p.selectedColumns() + " columns") + ")",
							p != null && p.tiles.size() == 6 && p.selectedColumns() == 48 * 32 - 64 && p.tileChunks == 1);
					check("area saved to its file", Areas.file(client) != null && Files.exists(Areas.file(client)));
					myceliumBefore = Job.itemCount(client.player.getInventory(), Items.MYCELIUM);
					breadBefore = Job.itemCount(client.player.getInventory(), Items.BREAD);
				}
				if (tick == 220) {
					Areas.start(client);
					check("area job started", Job.running() && Areas.running());
					next("work");
				}
			}
			case "work" -> work(client, since);
			case "spread" -> {
				if (since % 400 == 0) log("spread " + since / 20 + " s: " + Areas.progress(Areas.project) + " · " + Job.status.getString());
				if (!Job.running()) {
					log("ended: " + Job.stopReason);
					Project p = Areas.project;
					check("whole area finished (" + Job.stopReason + ")", Job.stopReason.startsWith("Done: the whole area"));
					check("every tile done (" + (p == null ? "?" : Areas.progress(p)) + ")", p != null && p.count(Project.State.DONE) == p.tiles.size());
					int[] r = server(client, s -> new int[] {liveGrassInside(s.getOverworld(), p), cutGrass(s.getOverworld()), seamPaths(s.getOverworld(), p),
							edgeOpen(s.getOverworld(), p), dirtInside(s.getOverworld(), p)}, null);
					check("no grass left in the area (" + r[0] + ")", r[0] == 0);
					check("the cut-out (the base) left as it was: 64 grass (" + r[1] + ")", r[1] == 64);
					check("no seam paths left inside (" + r[2] + ")", r[2] == 0);
					check("the outside edge (and round the cut-out) is all path (" + r[3] + " open)", r[3] == 0);
					check("no plain dirt left inside (" + r[4] + ")", r[4] == 0);
					check("saved seams all taken back up (" + (p == null ? "?" : p.seamSet.size()) + ")", p != null && p.seamSet.isEmpty());
					command(client, "gamemode spectator @p");
					command(client, "tp @p 24 -30 -14 0 55");
					next("photo");
				} else if (since > 20 * 60 * 10) fail(client, "area not finished after 10 minutes at 100x: " + Areas.progress(Areas.project));
			}
			case "photo" -> {
				if (since == 60) screenshot(client, "area_done");
				if (since == 80) finish(client);
			}
			default -> {}
		}
	}

	private static void work(MinecraftClient client, int since) {
		Project p = Areas.project;
		if (since % 400 == 0) log("work: " + Job.status.getString() + " · " + (p == null ? "" : Areas.progress(p)) + " · shelter " + Shelter.mode);
		if (since == 100) check("totem put in the off hand", client.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING));
		// Hungry: it eats bread (and never the totem, or mycelium).
		// (Not on peaceful, where hunger fills back up by itself.)
		if (since == 298) command(client, "difficulty easy");
		if (since == 300) server(client, s -> {
			s.getPlayerManager().getPlayerList().getFirst().getHungerManager().setFoodLevel(8);
			return 0;
		});
		if (since > 300 && since < 900 && !fed && Job.itemCount(client.player.getInventory(), Items.BREAD) < breadBefore) {
			fed = true;
			check("ate bread when hungry (food now " + client.player.getHungerManager().getFoodLevel() + ")", client.player.getHungerManager().getFoodLevel() > 8);
			command(client, "difficulty peaceful");
		}
		if (since == 900 && !fed) check("ate bread when hungry (food " + client.player.getHungerManager().getFoodLevel() + ")", false);
		if (p == null) return;
		int w = p.count(Project.State.WORKED) + p.count(Project.State.DONE);
		// The first tile worked: its sides next to unworked tiles are path now.
		if (!seamsChecked && w >= 1 && Job.state == Job.State.TRAVEL) {
			seamsChecked = true;
			int[] r = server(client, s -> seamCheck(s.getOverworld(), p), null);
			check("seams: path along a worked tile's sides next to unworked ones (" + r[0] + " path, " + r[1] + " missing)", r[0] >= 15 && r[1] == 0);
		}
		// "Restart" after 2 tiles: drop everything in memory, read the file back, carry on.
		if (!restarted && w >= 2 && Job.state == Job.State.TRAVEL) {
			restarted = true;
			worked = w;
			int seams = p.seamSet.size();
			java.util.Set<String> done = new java.util.HashSet<>();
			p.tiles.forEach((k, t) -> {
				if (t.state != Project.State.QUEUED) done.add(k);
			});
			Job.stop(client, "Stopped.");
			Areas.forget();
			Project back = Areas.get(client);
			java.util.Set<String> doneBack = new java.util.HashSet<>();
			if (back != null) back.tiles.forEach((k, t) -> {
				if (t.state != Project.State.QUEUED) doneBack.add(k);
			});
			check("restart: progress read back from the file (" + done + " vs " + doneBack + ", seams " + seams + " vs " + (back == null ? "?" : back.seamSet.size()) + ")",
					back != null && back != p && done.equals(doneBack) && back.seamSet.size() == seams && seams > 0);
			Areas.start(client);
			check("restart: carries on with a tile it hasn't done (" + (Areas.tile == null ? "none" : Project.name(Areas.tile)) + ")",
					Job.running() && Areas.tile != null && !doneBack.contains(Project.name(Areas.tile)));
			return;
		}
		// Night, while working a tile: it digs in, and climbs out in the morning.
		if (restarted && !nightDone && roof == null && Job.state == Job.State.WORK && !Shelter.active() && since > 900) {
			command(client, "time set 12600");
			roof = BlockPos.ORIGIN;
			log("night set at " + since);
		}
		if (roof == BlockPos.ORIGIN && Shelter.mode == Shelter.Mode.INSIDE) {
			roof = Shelter.ground();
			BlockPos r = roof;
			int[] s = server(client, sv -> {
				ServerWorld world = sv.getOverworld();
				return new int[] {world.getBlockState(r).isAir() ? 0 : 1, world.getBlockState(r.down()).isAir() ? 1 : 0, world.getBlockState(r.down(2)).isAir() ? 1 : 0};
			}, null);
			check("night: dug in three deep with a roof on (" + r.toShortString() + ", player y " + client.player.getY() + ")",
					s[0] == 1 && s[1] == 1 && s[2] == 1 && client.player.getY() < r.getY() - 1.5);
			screenshot(client, "area_bunker");
			command(client, "time set 1000");
		}
		if (roof != null && roof != BlockPos.ORIGIN && !nightDone && Shelter.mode == Shelter.Mode.NONE) {
			nightDone = true;
			BlockPos r = roof;
			int[] s = server(client, sv -> {
				ServerWorld world = sv.getOverworld();
				return new int[] {world.getBlockState(r.down()).isAir() ? 0 : 1, world.getBlockState(r.down(2)).isAir() ? 0 : 1};
			}, null);
			check("morning: climbed out and filled the hole behind it (" + s[0] + s[1] + ", y " + client.player.getY() + ")",
					s[0] == 1 && s[1] == 1 && client.player.getY() >= r.getY() + 0.9);
			check("morning: back to work (" + Job.status.getString() + ")", Job.running());
		}
		// A warden: hide until it's gone.
		if (nightDone && !wardenDone && Job.state == Job.State.WORK && !Shelter.active() && roof != null && !"warden".equals(phaseFlag)) {
			phaseFlag = "warden";
			flagAt = tick;
			command(client, "difficulty easy");
			command(client, "summon minecraft:warden " + (client.player.getX() + 20) + " " + (GROUND + 1) + " " + client.player.getZ() + " {NoAI:1b,Silent:1b}");
		}
		if ("warden".equals(phaseFlag)) {
			int t = tick - flagAt;
			if (t == 100) check("warden: hiding (" + Shelter.mode + ", " + Shelter.why + ")", Shelter.active() && Shelter.why.contains("warden"));
			if (t == 600) command(client, "kill @e[type=minecraft:warden]");
			if (t > 600 && Shelter.mode == Shelter.Mode.NONE) {
				check("warden gone: back out and working after " + (t - 600) / 20 + " s", Job.running() && t - 600 < 20 * 60);
				wardenDone = true;
				phaseFlag = "";
			}
			if (t > 600 + 20 * 60 && !wardenDone) {
				check("warden gone: came back out (" + Shelter.mode + ")", false);
				wardenDone = true;
				phaseFlag = "";
			}
		}
		// A lit creeper close by: it runs.
		if (wardenDone && !creeperDone && Job.state == Job.State.WORK && !Shelter.active() && !"creeper".equals(phaseFlag)) {
			phaseFlag = "creeper";
			flagAt = tick;
			command(client, "summon minecraft:creeper " + (client.player.getX() + 3) + " " + client.player.getY() + " " + client.player.getZ()
					+ " {NoAI:1b,ignited:1b,Fuse:200s,ExplosionRadius:0b}");
		}
		if ("creeper".equals(phaseFlag)) {
			int t = tick - flagAt;
			if (t == 2) creeperStart = creeperDistance(client);
			if (t == 50) {
				double d = creeperDistance(client);
				check("lit creeper: ran from it (" + Math.round(creeperStart * 10) / 10.0 + " -> " + Math.round(d * 10) / 10.0 + " blocks)", d > creeperStart + 4);
				command(client, "kill @e[type=minecraft:creeper]");
				command(client, "difficulty peaceful");
			}
			if (t == 200) {
				check("after the creeper: working again (" + Job.status.getString() + ")", Job.running() && !client.options.forwardKey.isPressed() || Job.running() && Job.state != Job.State.WORK);
				creeperDone = true;
				phaseFlag = "";
			}
		}
		if (p.count(Project.State.QUEUED) == 0 && creeperDone) {
			int used = myceliumBefore - Job.itemCount(client.player.getInventory(), Items.MYCELIUM);
			log("all tiles worked: " + Areas.progress(p) + ", " + used + " mycelium used");
			check("mycelium used is sparse (" + used + " for " + p.selectedColumns() + " columns)", used > 0 && used * 10 < p.selectedColumns());
			check("walker rarely stuck (" + Walker.stuckCount + ")", Walker.stuckCount <= 5);
			// Fast-forward: random ticks 100x, and the tiles have all soaked long enough to be checked.
			command(client, "gamerule random_tick_speed 300");
			command(client, "gamerule randomTickSpeed 300");
			next("spread");
			return;
		}
		if (!Job.running()) fail(client, "stopped while working: " + Job.stopReason);
		else if (since > 20 * 60 * 12) fail(client, "work took too long: " + Areas.progress(p));
	}

	private static String phaseFlag = "";
	private static int flagAt;

	private static double creeperDistance(MinecraftClient client) {
		double best = 999;
		for (Entity e : client.world.getEntities()) if (e instanceof CreeperEntity) best = Math.min(best, e.distanceTo(client.player));
		return best;
	}

	private static void build(MinecraftClient client) {
		command(client, "gamerule doDaylightCycle false");
		command(client, "gamerule advance_time false");
		command(client, "time set 6000");
		command(client, "gamerule doWeatherCycle false");
		command(client, "gamerule advance_weather false");
		command(client, "weather clear");
		command(client, "gamerule mobGriefing false");
		command(client, "gamerule mob_griefing false");
		command(client, "gamerule doMobSpawning false");
		command(client, "gamerule spawn_mobs false");
		server(client, s -> {
			ServerWorld w = s.getOverworld();
			for (int x = X0 - 12; x <= X1 + 12; x++) {
				for (int z = Z0 - 12; z <= Z1 + 12; z++) {
					int k = Math.floorMod(x * 31 + z * 17, 7);
					BlockState plant = k == 0 ? Blocks.SHORT_GRASS.getDefaultState() : k == 3 ? Blocks.POPPY.getDefaultState() : null;
					if (plant != null) w.setBlockState(new BlockPos(x, GROUND + 1, z), plant, Block.NOTIFY_LISTENERS);
				}
			}
			return 0;
		});
	}

	// ---------------------------------------------------------------- server-side checks

	private static boolean inArea(int x, int z) {
		return x >= X0 && x <= X1 && z >= Z0 && z <= Z1 && !inCut(x, z);
	}

	private static boolean inCut(int x, int z) {
		return x >= CX0 && x <= CX1 && z >= CZ0 && z <= CZ1;
	}

	private static BlockState top(ServerWorld w, int x, int z) {
		return w.getBlockState(new BlockPos(x, GROUND, z));
	}

	/** For each worked tile's column next to an unworked tile's column: is it path? {path, missing}. */
	private static int[] seamCheck(ServerWorld w, Project p) {
		int path = 0, missing = 0;
		for (int x = X0; x <= X1; x++) {
			for (int z = Z0; z <= Z1; z++) {
				if (!inArea(x, z) || p.mask.edge(x, z) || p.stateOf(p.tileAt(x, z)) == Project.State.QUEUED) continue;
				boolean next = false;
				for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
					int nx = x + dx, nz = z + dz;
					if (inArea(nx, nz) && p.stateOf(p.tileAt(nx, nz)) == Project.State.QUEUED) next = true;
				}
				if (!next) continue;
				if (top(w, x, z).isOf(Blocks.DIRT_PATH)) path++;
				else {
					missing++;
					log("seam missing at " + x + " " + z + ": " + top(w, x, z).getBlock());
				}
			}
		}
		return new int[] {path, missing};
	}

	private static int liveGrassInside(ServerWorld w, Project p) {
		int n = 0;
		for (int x = X0; x <= X1; x++) for (int z = Z0; z <= Z1; z++) if (inArea(x, z) && top(w, x, z).isOf(Blocks.GRASS_BLOCK)) {
			n++;
			if (n <= 5) log("grass left at " + x + " " + z);
		}
		return n;
	}

	private static int cutGrass(ServerWorld w) {
		int n = 0;
		for (int x = CX0; x <= CX1; x++) for (int z = CZ0; z <= CZ1; z++) if (top(w, x, z).isOf(Blocks.GRASS_BLOCK)) n++;
		return n;
	}

	private static int seamPaths(ServerWorld w, Project p) {
		int n = 0;
		for (int x = X0; x <= X1; x++) for (int z = Z0; z <= Z1; z++) {
			if (inArea(x, z) && !p.mask.edge(x, z) && top(w, x, z).isOf(Blocks.DIRT_PATH)) {
				n++;
				if (n <= 5) log("path left at " + x + " " + z);
			}
		}
		return n;
	}

	private static int edgeOpen(ServerWorld w, Project p) {
		int n = 0;
		for (int x = X0; x <= X1; x++) for (int z = Z0; z <= Z1; z++) {
			if (!inArea(x, z) || !p.mask.edge(x, z)) continue;
			BlockState s = top(w, x, z);
			if (s.isOf(Blocks.GRASS_BLOCK) || s.isOf(Blocks.DIRT)) {
				n++;
				if (n <= 5) log("edge open at " + x + " " + z + ": " + s.getBlock());
			}
		}
		return n;
	}

	private static int dirtInside(ServerWorld w, Project p) {
		int n = 0;
		for (int x = X0; x <= X1; x++) for (int z = Z0; z <= Z1; z++) {
			if (!inArea(x, z) || p.mask.edge(x, z)) continue;
			BlockState s = top(w, x, z);
			if (s.isOf(Blocks.DIRT) || s.isOf(Blocks.FARMLAND)) {
				n++;
				if (n <= 5) log("dirt left at " + x + " " + z + ": " + s.getBlock());
			}
		}
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
