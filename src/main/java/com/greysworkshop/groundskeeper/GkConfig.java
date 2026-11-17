package com.greysworkshop.groundskeeper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Settings, in config/groundskeeper.json. */
final class GkConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	static GkConfig INSTANCE = new GkConfig();
	private static Path file = FabricLoader.getInstance().getConfigDir().resolve("groundskeeper.json");

	enum Border {
		/** The circle's outer ring becomes dirt path: free, and grass can't spread onto path. */
		PATH,
		/** The outer ring gets real mycelium: costs about 7 blocks per block of radius. */
		MYCELIUM,
		/** Nothing: grass from outside will keep fighting the edge. */
		NONE
	}

	/** What to do at night (and in thunderstorms) while a job runs. */
	enum Night {
		/** Dig a hole three deep, put the block back on top, climb out in the morning (default). */
		BUNKER,
		/** Go to the safety zone you set, if it's close enough; otherwise dig in. */
		ZONE,
		/** Keep working through the night (still hides from danger). */
		WORK,
		/** Stop the job. */
		STOP
	}

	/** Blocks between mycelium seeds. Smaller spreads faster but uses more mycelium. */
	int spacing = 5;
	Border border = Border.PATH;
	/** Block changes per second. */
	int speed = 10;
	/** Break flowers that stand on grass (they drop as items). Off: that grass is left alone. */
	boolean clearFlowers = true;
	/** After the work, stay, keep an eye on it and fix grass that comes back, until it's all mycelium. */
	boolean watch = true;
	/** Stop when health drops to this (half hearts). */
	int minHealth = 10;
	boolean hud = true;
	/** Swap mode: any block to any block (ids), instead of the grass-to-mycelium plan. Empty: the plan. */
	String swapFrom = "";
	String swapTo = "";
	/** Big areas: tiles are this many chunks across (4: 64 blocks). Keep it inside your simulation distance. */
	int tileChunks = 4;
	Night night = Night.BUNKER;
	/** Who to hide from (see {@link Threat.Players}). */
	Threat.Players players = Threat.Players.NAUGHTY;
	boolean zoneSet;
	int zoneX, zoneY, zoneZ;
	String zoneDimension = "";
	/** How far the safety zone may be for it to walk there at dusk (further: it digs in instead). */
	int zoneDistance = 160;
	boolean autoEat = true;
	/** Keep a totem in an empty off hand. */
	boolean totem = true;
	/** Hold off for 3 seconds after being hit (keys and camera left alone, for kill aura). Off: it keeps working. */
	boolean holdWhenHit = false;
	/** Tool guard: put a tool aside with {@link #toolReserve} uses left, so it can be mended instead of breaking. */
	boolean protectTools = true;
	/** Uses a tool keeps when the tool guard is on (at least 2: one use can cost a sword 2). */
	int toolReserve = 10;
	long totalSeeds, totalBlocks;

	static void load() {
		try {
			if (Files.exists(file)) {
				GkConfig c = GSON.fromJson(Files.readString(file), GkConfig.class);
				if (c != null) INSTANCE = c;
			}
		} catch (IOException | RuntimeException e) {
			Groundskeeper.LOGGER.warn("Could not read {}", file, e);
		}
		GkConfig c = INSTANCE;
		c.spacing = Math.max(2, Math.min(12, c.spacing));
		c.speed = Math.max(1, Math.min(20, c.speed));
		c.minHealth = Math.max(0, Math.min(19, c.minHealth));
		if (c.border == null) c.border = Border.PATH;
		if (c.swapFrom == null) c.swapFrom = "";
		if (c.swapTo == null) c.swapTo = "";
		c.tileChunks = Math.max(1, Math.min(16, c.tileChunks));
		if (c.night == null) c.night = Night.BUNKER;
		if (c.players == null) c.players = Threat.Players.NAUGHTY;
		if (c.zoneDimension == null) c.zoneDimension = "";
		c.zoneDistance = Math.max(0, Math.min(10_000, c.zoneDistance));
		c.toolReserve = Math.max(2, Math.min(500, c.toolReserve));
	}

	static void save() {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(INSTANCE));
		} catch (IOException e) {
			Groundskeeper.LOGGER.warn("Could not save {}", file, e);
		}
	}

	boolean swapMode() {
		return !swapFrom.isEmpty() && !swapTo.isEmpty();
	}

	/** For the self-test: settings in a file of its own. */
	static void useFile(Path f) {
		file = f;
		INSTANCE = new GkConfig();
	}
}
