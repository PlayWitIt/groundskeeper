package com.greysworkshop.groundskeeper;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/** A few lines at the top left while it works. */
final class Hud {
	private Hud() {}

	static void render(DrawContext context, RenderTickCounter counter) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (!GkConfig.INSTANCE.enabled || !GkConfig.INSTANCE.hud || client.player == null || client.options.hudHidden || client.getDebugHud().shouldShowDebugHud()) return;
		if (!Job.running()) return;
		int y = 4;
		for (Text line : lines(client)) {
			int w = client.textRenderer.getWidth(line);
			context.fill(2, y - 2, 6 + w, y + 9, 0x90000000);
			context.drawTextWithShadow(client.textRenderer, line, 4, y, 0xFFFFFFFF);
			y += 11;
		}
	}

	static List<Text> lines(MinecraftClient client) {
		List<Text> lines = new ArrayList<>();
		lines.add(Text.literal("Groundskeeper · ").formatted(Formatting.DARK_GREEN).append(Job.status.copy().formatted(Formatting.WHITE)));
		if (Areas.running() && Areas.project != null) lines.add(Text.literal("Area · " + Areas.progress(Areas.project)).formatted(Formatting.LIGHT_PURPLE));
		if (Shelter.threat.level() == Threat.Level.CAUTION && !Shelter.active()) lines.add(Text.literal("Watching: " + Shelter.threat.reason()).formatted(Formatting.YELLOW));
		Plan p = Job.plan;
		if (p == null) return lines;
		StringBuilder b = new StringBuilder();
		if (p.swap) b.append("Swapped ").append(Job.swapped);
		else b.append("Tilled ").append(Job.tilled).append(" · seeds ").append(Job.seeded).append(" · path ").append(Job.pathed);
		b.append(" · ").append(Job.left()).append(" left");
		lines.add(Text.literal(b.toString()).formatted(Formatting.GRAY));
		int have = Job.itemCount(client.player.getInventory(), Job.placeItem());
		lines.add(Text.literal(have + " " + Job.placeName() + " · " + p.coverage() + "% done"
				+ (Job.failed.isEmpty() ? "" : " · " + Job.failed.size() + " skipped")).formatted(have < p.seeds() ? Formatting.RED : Formatting.GRAY));
		return lines;
	}
}
