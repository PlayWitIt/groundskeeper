package com.greysworkshop.groundskeeper;

import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Which of your Naughty or Nice lists a player is on, if that mod is installed. Reflection, so neither mod needs the
 * other (the same way Naughty or Nice talks to Base Insurance).
 */
final class NaughtyBridge {
	private static boolean resolved;
	private static Field instance, ledger, verdict;
	private static Method get;

	private NaughtyBridge() {}

	static boolean present() {
		return FabricLoader.getInstance().isModLoaded("naughtyornice");
	}

	private static boolean resolve() {
		if (resolved) return get != null;
		resolved = true;
		if (!present()) return false;
		try {
			Class<?> client = Class.forName("com.greysworkshop.naughtyornice.NaughtyOrNiceClient");
			Class<?> ledgerClass = Class.forName("com.greysworkshop.naughtyornice.Ledger");
			Class<?> profile = Class.forName("com.greysworkshop.naughtyornice.Profile");
			instance = client.getField("INSTANCE");
			ledger = client.getField("ledger");
			get = ledgerClass.getMethod("get", UUID.class);
			verdict = profile.getField("verdict");
			return true;
		} catch (ReflectiveOperationException | LinkageError e) {
			Groundskeeper.LOGGER.warn("Naughty or Nice is installed but couldn't be linked up: {}", e.toString());
			get = null;
			return false;
		}
	}

	/** "NAUGHTY", "LOOK", "NICE", "FRIEND", or "NONE" (on no list, or no Naughty or Nice). */
	static String verdict(UUID id) {
		if (!resolve()) return "NONE";
		try {
			Object c = instance.get(null);
			if (c == null) return "NONE";
			Object profile = get.invoke(ledger.get(c), id);
			if (profile == null) return "NONE";
			Object v = verdict.get(profile);
			return v == null ? "NONE" : v.toString();
		} catch (ReflectiveOperationException | RuntimeException e) {
			return "NONE";
		}
	}
}
