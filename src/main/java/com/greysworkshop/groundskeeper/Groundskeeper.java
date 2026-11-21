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
	static KeyBinding key, panel, startKey, pauseKey, stopKey;

	@Override
	public void onInitializeClient() {
		GkConfig.load();
		KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));
		startKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.groundskeeper.start", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_KP_1, category));
		pauseKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.groundskeeper.pause", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_KP_2, category));
		stopKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.groundskeeper.stop", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_KP_3, category));
		// Not bound to start with (O is Naughty or Nice's radar): set them in Controls if wanted, or use /gk panel.
		key = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.groundskeeper.main", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));
		panel = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.groundskeeper.panel", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));
		ClientTickEvents.END_CLIENT_TICK.register(Groundskeeper::tick);
		HudElementRegistry.addLast(Identifier.of(MOD_ID, "hud"), Hud::render);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> Commands.register(dispatcher, access));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
			Job.stop(client, "Stopped: disconnected.");
			Selection.mode = Selection.Mode.NONE;
		}));
		String test = System.getProperty("groundskeeper.selftest", "");
		if (test.equals("true") || test.equals("area")) {
			try {
				Class.forName("com.greysworkshop.groundskeeper." + (test.equals("area") ? "AreaTest" : "SelfTest")).getDeclaredMethod("start").invoke(null);
			} catch (ReflectiveOperationException e) {
				LOGGER.error("Self-test missing", e);
			}
		}
	}

	/** When the start key was last pressed with nothing running: a second press within {@link #CONFIRM_MS} starts. */
	private static long armedAt;
	static final long CONFIRM_MS = 3000;

	private static void tick(MinecraftClient client) {
		// Off: the keys do nothing (the panel key still opens the panel, to turn it back on).
		if (!GkConfig.INSTANCE.enabled) {
			while (key.wasPressed() || startKey.wasPressed() || pauseKey.wasPressed() || stopKey.wasPressed()) {
				// Pressed while off: swallowed.
			}
			while (panel.wasPressed()) {
				if (client.currentScreen == null && client.player != null) client.setScreen(new PanelScreen());
			}
			return;
		}
		while (key.wasPressed()) {
			if (client.currentScreen == null) Selection.key(client, client.player != null && client.player.isSneaking());
		}
		while (startKey.wasPressed()) {
			if (client.currentScreen == null) startKeyPressed(client);
		}
		while (pauseKey.wasPressed()) {
			if (client.currentScreen == null) Job.togglePause(client);
		}
		while (stopKey.wasPressed()) {
			if (client.currentScreen == null) Job.stop(client, "Stopped.");
		}
		while (panel.wasPressed()) {
			if (client.currentScreen == null && client.player != null) client.setScreen(new PanelScreen());
		}
		Job.tick(client);
		Selection.tick(client);
	}

	/**
	 * The start key. A paused job carries on at once. Starting new work takes a second press within 3 seconds, so a
	 * stray key (on some laptops End sends the same code as Numpad 1) can't set it off; the message names the key.
	 */
	static void startKeyPressed(MinecraftClient client) {
		if (client.player == null) return;
		if (Job.running()) {
			Job.startOrResume(client);
			return;
		}
		long now = System.currentTimeMillis();
		if (now - armedAt <= CONFIRM_MS) {
			armedAt = 0;
			Job.startOrResume(client);
			return;
		}
		armedAt = now;
		String keyName = startKey.getBoundKeyLocalizedText().getString();
		Project area = Areas.get(client);
		String what = Selection.mode != Selection.Mode.READY && area != null && !area.mask.isEmpty() ? "carry on with your area"
				: Selection.mode == Selection.Mode.READY ? "start the circle you set (radius " + Selection.radius + ")"
				: "start a circle around you (radius " + PanelScreen.radius + ")";
		Job.chat(client.player, net.minecraft.text.Text.literal("[" + keyName + "] pressed. Press it again within 3 seconds to " + what + ". ")
				.formatted(net.minecraft.util.Formatting.YELLOW)
				.append(net.minecraft.text.Text.literal("Didn't mean to? Ignore this. /gk off turns Groundskeeper off.").formatted(net.minecraft.util.Formatting.GRAY)));
	}

	/** Turns the whole mod on or off; off stops anything running. */
	static void setEnabled(MinecraftClient client, boolean on) {
		GkConfig.INSTANCE.enabled = on;
		GkConfig.save();
		armedAt = 0;
		if (!on) {
			Job.stop(client, "Stopped: Groundskeeper turned off.");
			Selection.mode = Selection.Mode.NONE;
		}
		if (client.player != null) {
			Job.chat(client.player, net.minecraft.text.Text.literal(on ? "Groundskeeper is on." : "Groundskeeper is off: its keys do nothing and nothing runs. /gk on (or the panel) turns it back on.")
					.formatted(on ? net.minecraft.util.Formatting.GREEN : net.minecraft.util.Formatting.GRAY));
		}
	}
}
