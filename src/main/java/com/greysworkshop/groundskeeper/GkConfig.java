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
