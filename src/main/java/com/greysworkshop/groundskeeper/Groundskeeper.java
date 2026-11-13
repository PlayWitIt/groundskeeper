package com.greysworkshop.groundskeeper;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Groundskeeper: turns the grass in a circle into mycelium, with as little mycelium as it can. */
public final class Groundskeeper implements ClientModInitializer {
	public static final String MOD_ID = "groundskeeper";
	static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	static KeyBinding key, panel;

	@Override
	public void onInitializeClient() {
		GkConfig.load();
		KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));
		key = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.groundskeeper.main", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_O, category));
		panel = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.groundskeeper.panel", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_KP_3, category));
		ClientTickEvents.END_CLIENT_TICK.register(Groundskeeper::tick);
		HudElementRegistry.addLast(Identifier.of(MOD_ID, "hud"), Hud::render);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> Commands.register(dispatcher, access));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
			Job.stop(client, "Stopped: disconnected.");
			Selection.mode = Selection.Mode.NONE;
		}));
		if (Boolean.getBoolean("groundskeeper.selftest")) {
			try {
				Class.forName("com.greysworkshop.groundskeeper.SelfTest").getDeclaredMethod("start").invoke(null);
			} catch (ReflectiveOperationException e) {
				LOGGER.error("Self-test missing", e);
			}
		}
	}

	private static void tick(MinecraftClient client) {
		while (key.wasPressed()) {
			if (client.currentScreen == null) Selection.key(client, client.player != null && client.player.isSneaking());
		}
		while (panel.wasPressed()) {
			if (client.currentScreen == null && client.player != null) client.setScreen(new PanelScreen());
		}
		Job.tick(client);
		Selection.tick(client);
	}
}
