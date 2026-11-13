package com.greysworkshop.groundskeeper;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/**
 * The panel (numpad 3): pick the circle around where you stand, see what it will take, start and stop, and the
 * settings. O still works for picking the circle by walking it.
 */
final class PanelScreen extends Screen {
	private static final int W = 320, GREEN = 0xFF60D060, WHITE = 0xFFF0F0F0, GRAY = 0xFFA0A0B0, RED = 0xFFFF6060, PURPLE = 0xFFC080FF,
			YELLOW = 0xFFFFD050;
	static int radius = 16;

	PanelScreen() {
		super(Text.literal("Groundskeeper"));
		if (Selection.mode == Selection.Mode.READY) radius = Selection.radius;
	}

	@Override
	protected void init() {
		int x = (width - W) / 2, y = 34;
		int half = (W - 4) / 2;
		GkConfig cf = GkConfig.INSTANCE;
		addDrawableChild(slider(x, y, half, 2, 160, radius, v -> radius = v, v -> "Radius: " + v,
				"How far from the middle. 16 is a 33-block-wide circle."));
		addDrawableChild(ButtonWidget.builder(Text.literal("Set circle around me"), b -> {
			MinecraftClient c = MinecraftClient.getInstance();
			if (c.player == null || Job.running()) return;
			Selection.set(c, c.player, Walker.feet(c.player).down(), radius, false);
			clearAndInit();
		}).dimensions(x + half + 4, y, half, 20).tooltip(Tooltip.of(Text.literal(
				"You're the middle. It plans the job and shows it in the world: the ring, purple dots where seeds go, red where grass can't be reached. "
						+ "Then Numpad 1 (or Start) starts, Numpad 2 pauses, Numpad 3 stops."))).build());
		y += 24;
		boolean running = Job.running();
		ButtonWidget start = ButtonWidget.builder(Text.literal(running ? "Stop" : "Start"), b -> {
			MinecraftClient c = MinecraftClient.getInstance();
			if (Job.running()) Job.stop(c, "Stopped.");
			else if (Selection.mode == Selection.Mode.READY) {
				Job.start(c, Plan.make(c.world, Selection.center, Selection.radius, true));
				if (Job.running()) close();
			}
			clearAndInit();
		}).dimensions(x, y, half, 20).build();
		start.active = running || Selection.mode == Selection.Mode.READY;
		addDrawableChild(start);
		ButtonWidget cancel = ButtonWidget.builder(Text.literal("Clear circle"), b -> {
			if (client != null && client.player != null) Selection.cancel(client.player);
			clearAndInit();
		}).dimensions(x + half + 4, y, half, 20).build();
		cancel.active = !running && Selection.mode != Selection.Mode.NONE;
		addDrawableChild(cancel);
		y += 32;
		addDrawableChild(slider(x, y, half, 2, 12, cf.spacing, v -> {
			cf.spacing = v;
			changed();
		}, v -> "Seed spacing: " + v, "Blocks between mycelium seeds. Smaller spreads faster but uses more. 5 uses about 4% of the area and fills it in about half an hour of daylight."));
		addDrawableChild(ButtonWidget.builder(borderText(), b -> {
			cf.border = GkConfig.Border.values()[(cf.border.ordinal() + 1) % GkConfig.Border.values().length];
			b.setMessage(borderText());
			changed();
		}).dimensions(x + half + 4, y, half, 20).tooltip(Tooltip.of(Text.literal(
				"Path: the outer ring becomes dirt path, free, and grass can't spread over it. Mycelium: a ring of real mycelium. None: grass outside keeps fighting the edge."))).build());
		y += 24;
		addDrawableChild(slider(x, y, half, 1, 20, cf.speed, v -> {
			cf.speed = v;
			GkConfig.save();
		}, v -> "Speed: " + v + " blocks/s", "How fast it changes blocks. Turn it down if the server complains."));
		addDrawableChild(toggle(x + half + 4, y, half, "Break flowers", () -> cf.clearFlowers, v -> {
			cf.clearFlowers = v;
			changed();
		}, "On: flowers on grass are broken (they drop). Off: that grass is left alone."));
		y += 24;
		addDrawableChild(toggle(x, y, half, "Watch afterwards", () -> cf.watch, v -> {
			cf.watch = v;
			GkConfig.save();
		}, "After the work, stay in the middle, look again every 30 seconds and fix grass that comes back, until it's all mycelium."));
		addDrawableChild(toggle(x + half + 4, y, half, "HUD", () -> cf.hud, v -> {
			cf.hud = v;
			GkConfig.save();
		}, "The lines at the top left while it works."));
		if (cf.swapMode()) {
			y += 24;
			addDrawableChild(ButtonWidget.builder(Text.literal("Swap mode on: " + cf.swapFrom + " → " + cf.swapTo + " (click: back to mycelium)"), b -> {
				cf.swapFrom = cf.swapTo = "";
				changed();
				clearAndInit();
			}).dimensions(x, y, W, 20).build());
		}
		textTop = y + 30;
		addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close()).dimensions(width / 2 - 50, height - 28, 100, 20).build());
	}

	private int textTop;

	/** A setting that changes the plan: save it, and plan again. */
	private void changed() {
		GkConfig.save();
		MinecraftClient c = MinecraftClient.getInstance();
		if (Selection.mode == Selection.Mode.READY && !Job.running() && c.player != null) Selection.set(c, c.player, Selection.center, Selection.radius, false);
	}

	private static Text borderText() {
		return Text.literal("Border: " + switch (GkConfig.INSTANCE.border) {
			case PATH -> "dirt path";
			case MYCELIUM -> "mycelium";
			case NONE -> "none";
		});
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, 12, GREEN);
		int x = (width - W) / 2, y = textTop;
		for (Line l : lines()) {
			if (y > height - 40) break;
			ctx.drawTextWithShadow(textRenderer, Text.literal(l.text), x, y, l.color);
			y += 11;
		}
	}

	private record Line(String text, int color) {}

	private List<Line> lines() {
		List<Line> out = new ArrayList<>();
		MinecraftClient c = MinecraftClient.getInstance();
		if (c.player == null) return out;
		if (Job.running()) {
			out.add(new Line("Working: " + Job.status.getString(), GREEN));
			out.add(new Line(Job.summary() + " · " + Job.left() + " left · " + Job.plan.coverage() + "% mycelium", WHITE));
			return out;
		}
		Plan p = Selection.mode == Selection.Mode.READY ? Selection.preview : null;
		if (p == null) {
			out.add(new Line("No circle yet. Stand in the middle, set the radius and click \"Set circle around me\".", GRAY));
			if (!Job.stopReason.isEmpty()) out.add(new Line("Last job: " + Job.stopReason, GRAY));
			return out;
		}
		PlayerInventory inv = c.player.getInventory();
		out.add(new Line("Radius " + p.radius + " around " + p.center.toShortString() + " · " + String.format("%,d", p.columns) + " columns", WHITE));
		int seeds = p.seeds();
		if (p.swap) {
			out.add(new Line("Swap " + p.count(Plan.Kind.SWAP) + " blocks", WHITE));
		} else {
			int till = p.count(Plan.Kind.TILL), path = p.count(Plan.Kind.PATH);
			out.add(new Line("Till " + till + " grass (free) · border path " + path + " (free)", WHITE));
			out.add(new Line("Mycelium seeds: " + seeds + " instead of " + (till + seeds + path + p.waterSeeds) + " (" + p.gridSeeds + " grid, " + p.slopeSeeds
					+ " slopes, " + p.guardSeeds + " fencing)", PURPLE));
			if (p.waterSeeds > 0) out.add(new Line("By water: " + p.waterSeeds + " swapped for dirt (you have " + Job.itemCount(inv, net.minecraft.item.Items.DIRT) + ")", WHITE));
		}
		if (!p.blocked.isEmpty()) out.add(new Line(p.blocked.size() + " grass it can't get at (red dots)", RED));
		if (!p.unloaded.isEmpty()) out.add(new Line(p.unloaded.size() + " columns not loaded: it'll walk to them", YELLOW));
		int have = Job.itemCount(inv, Job.placeItem());
		out.add(new Line("You have " + have + " " + Job.placeName() + (have >= seeds ? " ✔" : " ✘ " + (seeds - have) + " short"), have >= seeds ? GREEN : RED));
		if (!p.swap) {
			boolean hoe = Job.best(inv, s -> s.isIn(ItemTags.HOES)) >= 0, shovel = Job.best(inv, s -> s.isIn(ItemTags.SHOVELS)) >= 0;
			out.add(new Line("Hoe " + (hoe ? "✔" : "✘") + " · shovel " + (shovel ? "✔" : "✘"), hoe && shovel ? GREEN : RED));
		}
		String tools = Job.toolWarning(inv, p);
		if (tools != null) out.add(new Line(tools, YELLOW));
		String bad = Job.missing(inv, p);
		if (bad != null) out.add(new Line(bad, RED));
		return out;
	}

	private static SliderWidget slider(int x, int y, int w, int min, int max, int value, IntConsumer set, IntFunction<String> fmt, String tip) {
		SliderWidget s = new SliderWidget(x, y, w, 20, Text.empty(), (value - min) / (double) (max - min)) {
			{
				updateMessage();
			}

			private int current() {
				return min + (int) Math.round(MathHelper.clamp(this.value, 0, 1) * (max - min));
			}

			@Override
			protected void updateMessage() {
				setMessage(Text.literal(fmt.apply(current())));
			}

			@Override
			protected void applyValue() {
				set.accept(current());
			}
		};
		s.setTooltip(Tooltip.of(Text.literal(tip)));
		return s;
	}

	private static ButtonWidget toggle(int x, int y, int w, String name, java.util.function.BooleanSupplier get, java.util.function.Consumer<Boolean> set, String tip) {
		return ButtonWidget.builder(Text.literal(name + ": " + (get.getAsBoolean() ? "on" : "off")), b -> {
			set.accept(!get.getAsBoolean());
			b.setMessage(Text.literal(name + ": " + (get.getAsBoolean() ? "on" : "off")));
		}).dimensions(x, y, w, 20).tooltip(Tooltip.of(Text.literal(tip))).build();
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
