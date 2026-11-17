package com.greysworkshop.groundskeeper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The big area of this server (or world) and dimension, and working through it: which tile next, when a worked tile
 * has had long enough to spread to be worth looking at again, and when it's done.
 * <p>
 * Saved in config/groundskeeper/areas/&lt;world&gt;/&lt;dimension&gt;.json, the world spelled as Base Insurance
 * spells it ("server:address" or "local:world name") with anything but letters, digits, dot, dash and underscore
 * turned into _. Base Insurance reads that file to draw the tiles on its map.
 * <p>
 * The order: work the next tile; whenever a worked tile has had {@link Project#SOAK} of daylight near you, go back
 * and fix what's come back (it's usually next door); once every tile is worked, stand in each unfinished one until
 * it's all mycelium.
 */
final class Areas {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** What the job is doing with the tile it's on. */
	enum Doing {
		/** First time: till, seed, path. */
		WORK,
		/** Going back: fix grass that came back, add seeds where needed. */
		CHECK,
		/** Nothing else left: stand there until it's all mycelium. */
		WATCH
	}

	static Project project;
	private static String loadedFor;
	/** The tile the job is on, and what for; null when no area job is running. */
	static Long tile;
	static Doing doing;
	private static int ticks;

	private Areas() {}

	static String worldKey(MinecraftClient client) {
		ServerInfo server = client.getCurrentServerEntry();
		if (server != null) {
			String a = server.address.strip().toLowerCase(Locale.ROOT);
			if (a.endsWith(":25565")) a = a.substring(0, a.length() - 6);
			while (a.endsWith(".")) a = a.substring(0, a.length() - 1);
			return "server:" + a;
		}
		if (client.getServer() != null) return "local:" + client.getServer().getSaveProperties().getLevelName();
		return null;
	}

	static String dimension(ClientWorld w) {
		return w.getRegistryKey().getValue().toString();
	}

	static String sanitize(String s) {
		return s.replaceAll("[^a-zA-Z0-9._-]", "_");
	}

	static Path file(MinecraftClient client) {
		String key = worldKey(client);
		if (key == null || client.world == null) return null;
		return FabricLoader.getInstance().getConfigDir().resolve("groundskeeper").resolve("areas").resolve(sanitize(key))
				.resolve(sanitize(dimension(client.world)) + ".json");
	}

	/** The area here, loaded if need be; null if there isn't one. */
	static Project get(MinecraftClient client) {
		Path f = file(client);
		if (f == null) return null;
		String key = f.toString();
		if (key.equals(loadedFor)) return project;
		loadedFor = key;
		project = null;
		tile = null;
		if (!Files.exists(f)) return null;
		try {
			Project p = GSON.fromJson(Files.readString(f), Project.class);
			if (p != null) {
				if (p.shapes == null) p.shapes = new ArrayList<>();
				if (p.tiles == null) p.tiles = new java.util.LinkedHashMap<>();
				p.tileChunks = Math.max(1, Math.min(16, p.tileChunks));
				p.rebuild();
				project = p;
			}
		} catch (IOException | RuntimeException e) {
			Groundskeeper.LOGGER.warn("Could not read the area in {}", f, e);
		}
		return project;
	}

	/** Drops what's in memory, so the next {@link #get} reads the file again (as after a restart). */
	static void forget() {
		loadedFor = null;
		project = null;
		tile = null;
	}

	static void save(MinecraftClient client) {
		Path f = file(client);
		if (f == null || project == null) return;
		project.updated = System.currentTimeMillis();
		try {
			Files.createDirectories(f.getParent());
			Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
			Files.writeString(tmp, GSON.toJson(project));
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			Groundskeeper.LOGGER.warn("Could not save the area to {}", f, e);
		}
	}

	/** Sets the area's shapes (from the map, or the commands). Progress on tiles still in it is kept. Null if fine, else why not. */
	static String setShapes(MinecraftClient client, Shapes shapes) {
		if (client.world == null) return "Join a world first.";
		String big = shapes.tooBig();
		if (big != null) return big;
		Project p = get(client);
		if (p == null) {
			p = new Project();
			p.created = System.currentTimeMillis();
			p.tileChunks = GkConfig.INSTANCE.tileChunks;
			p.dimension = dimension(client.world);
			project = p;
		}
		p.shapes = new ArrayList<>(shapes.list);
		p.rebuild();
		if (client.player != null && p.count(Project.State.QUEUED) == p.tiles.size()) p.startFrom(client.player.getBlockX(), client.player.getBlockZ());
		save(client);
		return null;
	}

	static void add(MinecraftClient client, Shapes.Shape s) {
		Project p = get(client);
		List<Shapes.Shape> list = p == null ? new ArrayList<>() : new ArrayList<>(p.shapes);
		list.add(s);
		String why = setShapes(client, new Shapes(list));
		if (client.player != null) {
			if (why != null) Job.chat(client.player, Text.literal(why).formatted(Formatting.RED));
			else describe(client.player);
		}
	}

	/** Forgets the area here (and its progress). */
	static void clear(MinecraftClient client) {
		Path f = file(client);
		project = null;
		tile = null;
		if (f != null) {
			try {
				Files.deleteIfExists(f);
			} catch (IOException e) {
				Groundskeeper.LOGGER.warn("Could not delete {}", f, e);
			}
		}
	}

	static void describe(ClientPlayerEntity p) {
		Project pr = project;
		if (pr == null || pr.mask.isEmpty()) {
			Job.chat(p, Text.literal("No area here yet. Draw one on the map (/gk area map, needs Base Insurance) or add shapes: /gk area add rect|circle|ellipse ...").formatted(Formatting.GRAY));
			return;
		}
		long cols = pr.selectedColumns();
		int span = pr.tileChunks * 16;
		Job.chat(p, Text.literal(String.format(Locale.ROOT, "Area: %,d blocks² (%s) in %,d tiles of %d × %d · %d shapes", cols, size(cols), pr.tiles.size(), span, span,
				pr.shapes.size())).formatted(Formatting.GREEN));
		Job.chat(p, Text.literal(String.format(Locale.ROOT, "  Done %d · spreading %d · to do %d%s", pr.count(Project.State.DONE), pr.count(Project.State.WORKED),
				pr.count(Project.State.QUEUED), pr.count(Project.State.SKIPPED) > 0 ? " · skipped " + pr.count(Project.State.SKIPPED) : "")).formatted(Formatting.WHITE));
		int spacing = GkConfig.INSTANCE.spacing;
		long seeds = cols / ((long) spacing * spacing) + pr.mask.edgeCount() / 20;
		double hours = cols / (GkConfig.INSTANCE.speed * 3600.0);
		Job.chat(p, Text.literal(String.format(Locale.ROOT, "  If it's all grass: about %,d mycelium (%,.1f shulker boxes) and %,d hoe uses; %.1f hours of work at %d blocks a second, plus walking and spreading.",
				seeds, seeds / 1728.0, cols, hours, GkConfig.INSTANCE.speed)).formatted(Formatting.GRAY));
	}

	static String size(long columns) {
		double side = Math.sqrt(columns);
		return side >= 1000 ? String.format(Locale.ROOT, "like %.1f km square", side / 1000) : String.format(Locale.ROOT, "like %d × %d", Math.round(side), Math.round(side));
	}

	// ---------------------------------------------------------------- running

	static boolean running() {
		return tile != null && Job.running();
	}

	/** Numpad 1 / /gk area start: carry on with the area here. */
	static void start(MinecraftClient client) {
		ClientPlayerEntity p = client.player;
		if (p == null || client.world == null) return;
		Project pr = get(client);
		if (pr == null || pr.mask.isEmpty()) {
			describe(p);
			return;
		}
		if (Job.running()) {
			Job.chat(p, Text.literal("Already working. Numpad 3 stops it.").formatted(Formatting.YELLOW));
			return;
		}
		// Anything it skipped last time gets another go.
		for (Project.Tile t : pr.tiles.values()) {
			if (t.state == Project.State.SKIPPED) {
				t.state = Project.State.QUEUED;
				t.note = "";
			}
		}
		if (!goNext(client, p, true)) Job.chat(p, Text.literal("The whole area is mycelium already.").formatted(Formatting.GREEN));
	}

	/** Picks what's next and sends the job there. False when there's nothing left. */
	private static boolean goNext(MinecraftClient client, ClientPlayerEntity p, boolean starting) {
		Project pr = project;
		Long next = null;
		Doing what = null;
		// A worked tile that's had long enough to spread, nearest first: fix it before moving on.
		double best = Double.MAX_VALUE;
		for (String k : pr.tiles.keySet()) {
			Project.Tile t = pr.tiles.get(k);
			if (t.state != Project.State.WORKED || t.soak < Project.SOAK) continue;
			long key = Project.parse(k);
			double d = distance(pr, key, p);
			if (d < best) {
				best = d;
				next = key;
				what = Doing.CHECK;
			}
		}
		if (next == null) {
			next = pr.nextQueued();
			what = Doing.WORK;
		}
		if (next == null) {
			best = Double.MAX_VALUE;
			for (String k : pr.tiles.keySet()) {
				if (pr.tiles.get(k).state != Project.State.WORKED) continue;
				long key = Project.parse(k);
				double d = distance(pr, key, p);
				if (d < best) {
					best = d;
					next = key;
					what = Doing.WATCH;
				}
			}
		}
		if (next == null) {
			tile = null;
			if (!starting) Job.stop(client, "Done: the whole area is mycelium (" + pr.tiles.size() + " tiles).");
			return false;
		}
		tile = next;
		doing = what;
		int[] c = pr.tileCenter(next);
		Job.log("area: {} tile {} ({} done, {} spreading, {} to do)", what, Project.name(next), pr.count(Project.State.DONE), pr.count(Project.State.WORKED),
				pr.count(Project.State.QUEUED));
		if (starting) Job.startArea(client, c[0], c[1]);
		else Job.travelTo(c[0], c[1]);
		return true;
	}

	private static double distance(Project pr, long tile, ClientPlayerEntity p) {
		int[] c = pr.tileCenter(tile);
		return Math.hypot(c[0] - p.getX(), c[1] - p.getZ());
	}

	/** Arrived at the tile: the plan for it. Null (and on to the next) if there's nothing to do there. */
	static Plan arrived(MinecraftClient client, ClientPlayerEntity p, ClientWorld w) {
		Project pr = project;
		if (pr == null || tile == null) return null;
		Plan plan = Plan.make(w, pr, tile, doing == Doing.WORK);
		Project.Tile t = pr.tile(tile);
		if (plan.tasks.isEmpty() && plan.unloaded.isEmpty() && doing != Doing.WATCH) {
			finished(client, p, plan);
			return null;
		}
		if (t != null) t.coverage = plan.coverage();
		return plan;
	}

	/** The work on the tile is done (nothing left to do there): what it means for the tile, then what's next. */
	static void finished(MinecraftClient client, ClientPlayerEntity p, Plan plan) {
		Project pr = project;
		if (pr == null || tile == null) return;
		Project.Tile t = pr.tile(tile);
		if (t != null) {
			t.coverage = plan.coverage();
			boolean full = plan.farmland == 0 && plan.mycelium >= plan.nodes - plan.stranded;
			if (full || plan.nodes == 0) {
				t.state = Project.State.DONE;
			} else {
				if (t.state == Project.State.QUEUED) t.soak = 0;
				else {
					t.checks++;
					t.soak = Project.SOAK / 2;
				}
				t.state = Project.State.WORKED;
				// Looked at plenty of times and nearly all there: what's left is out of reach.
				if (t.checks >= 6 && t.coverage >= 97) {
					t.state = Project.State.DONE;
					t.note = t.coverage + "% (the rest out of reach)";
				}
			}
			chat(p, "Tile " + Project.name(tile) + ": " + (t.state == Project.State.DONE ? "done" : t.coverage + "% mycelium, spreading") + " · " + progress(pr));
		}
		save(client);
		goNext(client, p, false);
	}

	/** A tile couldn't be got to or worked: skip it for now. */
	static void skip(MinecraftClient client, ClientPlayerEntity p, String why) {
		Project pr = project;
		if (pr == null || tile == null) return;
		Project.Tile t = pr.tile(tile);
		if (t != null && t.state == Project.State.QUEUED) {
			t.state = Project.State.SKIPPED;
			t.note = why;
		} else if (t != null) {
			// A worked one: try again later, not straight away.
			t.soak = 0;
		}
		chat(p, "Skipping tile " + Project.name(tile) + " for now: " + why);
		save(client);
		goNext(client, p, false);
	}

	static String progress(Project pr) {
		int all = pr.tiles.size();
		return String.format(Locale.ROOT, "%d of %d tiles done, %d spreading, %d to do", pr.count(Project.State.DONE), all, pr.count(Project.State.WORKED),
				pr.count(Project.State.QUEUED) + pr.count(Project.State.SKIPPED));
	}

	/** A seam path went down (record it, so it's taken up again later), or a seam was taken back up. */
	static void seam(long pos, boolean down) {
		if (project == null) return;
		if (down) project.addSeam(pos);
		else project.removeSeam(pos);
	}

	/** Every tick while an area job runs: tiles near you spread by day, and the file is saved now and then. */
	static void tick(MinecraftClient client, ClientPlayerEntity p, ClientWorld w) {
		if (project == null || tile == null) return;
		if (++ticks % 20 != 0) return;
		if (Daylight.spreads(w.getTimeOfDay())) {
			// Random ticks happen around the player: count it for tiles whose middle is close.
			int reach = 96 + project.tileChunks * 8;
			for (String k : project.tiles.keySet()) {
				Project.Tile t = project.tiles.get(k);
				if (t.state != Project.State.WORKED) continue;
				if (distance(project, Project.parse(k), p) <= reach) t.soak += 20;
			}
		}
		if (ticks % 1200 == 0) save(client);
	}

	static void stopped(MinecraftClient client) {
		if (tile != null) save(client);
		tile = null;
	}

	private static void chat(ClientPlayerEntity p, String s) {
		Job.chat(p, Text.literal(s).formatted(Formatting.GRAY));
	}
}
