package com.greysworkshop.groundskeeper;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.function.Consumer;

/**
 * Drawing a big area on Base Insurance's map. Base Insurance (1.19.0 and later) has an area picker:
 * {@code com.greysworkshop.baseinsurance.AreaPicker.open(String title, String shapesJson, Consumer<String> done)}.
 * It's handed the area's shapes (as {@link Shapes} JSON) and gives back the new ones when you press Done (null if
 * you cancel). Reflection, so neither mod needs the other; without it, areas are made with /gk area add.
 */
final class MapBridge {
	private MapBridge() {}

	static boolean present() {
		return FabricLoader.getInstance().isModLoaded("baseinsurance");
	}

	static void open(MinecraftClient client) {
		if (client.player == null || client.world == null) return;
		if (!present()) {
			Job.chat(client.player, Text.literal("Drawing an area on a map needs Base Insurance (1.19.0 or later). Without it: /gk area add rect|circle|ellipse|square ...")
					.formatted(Formatting.YELLOW));
			return;
		}
		Project p = Areas.get(client);
		String json = p == null ? "" : new Shapes(p.shapes).toJson();
		Consumer<String> done = result -> client.execute(() -> {
			if (result == null || client.player == null) return;
			String why = Areas.setShapes(client, Shapes.fromJson(result));
			if (why != null) Job.chat(client.player, Text.literal(why).formatted(Formatting.RED));
			else {
				Areas.describe(client.player);
				Job.chat(client.player, Text.literal("Numpad 1 (or /gk area start) to start on it.").formatted(Formatting.AQUA));
			}
		});
		try {
			Class.forName("com.greysworkshop.baseinsurance.AreaPicker").getMethod("open", String.class, String.class, Consumer.class)
					.invoke(null, "Groundskeeper area", json, done);
		} catch (ClassNotFoundException | NoSuchMethodException e) {
			Job.chat(client.player, Text.literal("Your Base Insurance is too old to draw areas: update it to 1.19.0 or later.").formatted(Formatting.YELLOW));
		} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
			Groundskeeper.LOGGER.warn("Couldn't open Base Insurance's area picker", e);
			Job.chat(client.player, Text.literal("Couldn't open Base Insurance's map (see the log).").formatted(Formatting.RED));
		}
	}
}
