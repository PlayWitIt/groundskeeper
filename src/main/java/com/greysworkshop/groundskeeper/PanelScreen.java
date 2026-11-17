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
 * The panel (/gk panel, or its key): pick the circle around where you stand, or a big area drawn on Base Insurance's
 * map; see what it will take; start and stop; the settings, including what to do at night.
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
		// Short screens (854 × 480 at GUI scale 2 is 240 tall): tighter rows, Done up by the title.
		boolean compact = height < 300;
		int step = compact ? 22 : 24, gap = compact ? 22 : 30;
		int x = (width - W) / 2, y = compact ? 28 : 34;
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
		y += step;
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
		y += compact ? step : 32;
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
		y += step;
		addDrawableChild(slider(x, y, half, 1, 20, cf.speed, v -> {
			cf.speed = v;
			GkConfig.save();
		}, v -> "Speed: " + v + " blocks/s", "How fast it changes blocks. Turn it down if the server complains."));
		addDrawableChild(toggle(x + half + 4, y, half, "Break flowers", () -> cf.clearFlowers, v -> {
			cf.clearFlowers = v;
			changed();
		}, "On: flowers on grass are broken (they drop). Off: that grass is left alone."));
		y += step;
		addDrawableChild(toggle(x, y, half, "Watch afterwards", () -> cf.watch, v -> {
			cf.watch = v;
			GkConfig.save();
		}, "After the work, stay in the middle, look again every 30 seconds and fix grass that comes back, until it's all mycelium."));
		addDrawableChild(toggle(x + half + 4, y, half, "HUD", () -> cf.hud, v -> {
			cf.hud = v;
			GkConfig.save();
		}, "The lines at the top left while it works."));
		// Big areas.
		y += gap;
		Project area = client == null ? null : Areas.get(client);
		boolean hasArea = area != null && !area.mask.isEmpty();
		ButtonWidget draw = ButtonWidget.builder(Text.literal(hasArea ? "Edit area on map…" : "Draw an area on the map…"), b -> {
			close();
			MapBridge.open(MinecraftClient.getInstance());
		}).dimensions(x, y, half, 20).tooltip(Tooltip.of(Text.literal(MapBridge.present()
				? "Any size and shape: rectangles, circles, polygons, a brush, and cut-outs. It's worked a tile at a time, over as many days as it takes."
				: "Needs Base Insurance (1.19.0 or later) for the map. Without it: /gk area add rect|circle|ellipse|square ..."))).build();
		draw.active = !running && MapBridge.present();
		addDrawableChild(draw);
		ButtonWidget go = ButtonWidget.builder(Text.literal(Areas.running() ? "Stop area" : "Start area"), b -> {
			MinecraftClient c = MinecraftClient.getInstance();
			if (Job.running()) Job.stop(c, "Stopped.");
			else {
				Areas.start(c);
				if (Job.running()) close();
			}
			clearAndInit();
		}).dimensions(x + half + 4, y, half, 20).tooltip(Tooltip.of(Text.literal("Works the area tile by tile and picks up where it left off next time."))).build();
		go.active = hasArea && (!running || Areas.running());
		addDrawableChild(go);
		y += step;
		addDrawableChild(ButtonWidget.builder(nightText(), b -> {
			cf.night = GkConfig.Night.values()[(cf.night.ordinal() + 1) % GkConfig.Night.values().length];
			b.setMessage(nightText());
			GkConfig.save();
		}).dimensions(x, y, half, 20).tooltip(Tooltip.of(Text.literal(
				"Dig in: a hole three deep with the block put back on top, out again in the morning. Safety zone: walks to the spot you set (if it's close enough), else digs in. "
						+ "Keep working: carries on (mycelium doesn't spread at night, but tilling still gets done). Stop: stops the job."))).build());
		addDrawableChild(ButtonWidget.builder(playersText(), b -> {
			cf.players = Threat.Players.values()[(cf.players.ordinal() + 1) % Threat.Players.values().length];
			b.setMessage(playersText());
			GkConfig.save();
		}).dimensions(x + half + 4, y, half, 20).tooltip(Tooltip.of(Text.literal(
				"Who it hides from (in a bunker, until they've gone). Your lists: players on your Naughty or Look Into list in Naughty or Nice. Strangers: anyone who isn't a friend "
						+ "or on your Nice list. Nobody: leaves players to you. It always hides from a warden or a crowd of monsters, and runs from a lit creeper."))).build());
		y += step;
		addDrawableChild(ButtonWidget.builder(Text.literal(cf.zoneSet ? "Safety zone: move it here" : "Set safety zone here"), b -> {
			MinecraftClient c = MinecraftClient.getInstance();
			if (c.player == null || c.world == null) return;
			net.minecraft.util.math.BlockPos f = Walker.feet(c.player);
			cf.zoneSet = true;
			cf.zoneX = f.getX();
			cf.zoneY = f.getY();
			cf.zoneZ = f.getZ();
			cf.zoneDimension = Areas.dimension(c.world);
			if (cf.night == GkConfig.Night.BUNKER) cf.night = GkConfig.Night.ZONE;
			GkConfig.save();
			clearAndInit();
		}).dimensions(x, y, half, 20).tooltip(Tooltip.of(Text.literal("Stand in the shelter you built. At dusk it walks here if it's within " + cf.zoneDistance
				+ " blocks (/gk zone distance), else it digs in where it is."))).build());
		addDrawableChild(toggle(x + half + 4, y, half, "Eat when hungry", () -> cf.autoEat, v -> {
			cf.autoEat = v;
			GkConfig.save();
		}, "Good food first; never rotten flesh, spider eyes, raw chicken, chorus fruit or golden apples. It also keeps a totem in an empty off hand (/gk totem)."));
		if (cf.swapMode()) {
			y += step;
			addDrawableChild(ButtonWidget.builder(Text.literal("Swap mode on: " + cf.swapFrom + " → " + cf.swapTo + " (click: back to mycelium)"), b -> {
				cf.swapFrom = cf.swapTo = "";
				changed();
				clearAndInit();
			}).dimensions(x, y, W, 20).build());
		}
		textTop = y + (compact ? 26 : 30);
		textBottom = compact ? height - 4 : height - 40;
		if (compact) addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close()).dimensions(width - 56, 4, 50, 20).build());
		else addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close()).dimensions(width / 2 - 50, height - 28, 100, 20).build());
	}

	private int textTop, textBottom;

	/** For the self-test: no two buttons overlap and all are on screen. */
	boolean fits() {
		List<net.minecraft.client.gui.widget.ClickableWidget> all = new java.util.ArrayList<>();
		for (var c : children()) if (c instanceof net.minecraft.client.gui.widget.ClickableWidget w) all.add(w);
		for (var a : all) {
			if (a.getX() < 0 || a.getY() < 0 || a.getX() + a.getWidth() > width || a.getY() + a.getHeight() > height) return false;
			for (var b : all) if (a != b && a.getX() < b.getX() + b.getWidth() && b.getX() < a.getX() + a.getWidth() && a.getY() < b.getY() + b.getHeight() && b.getY() < a.getY() + a.getHeight()) return false;
		}
		return all.size() >= 15;
	}

	/** A setting that changes the plan: save it, and plan again. */
	private void changed() {
		GkConfig.save();
		MinecraftClient c = MinecraftClient.getInstance();
		if (Selection.mode == Selection.Mode.READY && !Job.running() && c.player != null) Selection.set(c, c.player, Selection.center, Selection.radius, false);
	}

	private static Text nightText() {
		return Text.literal("At night: " + switch (GkConfig.INSTANCE.night) {
			case BUNKER -> "dig in";
			case ZONE -> "safety zone";
			case WORK -> "keep working";
			case STOP -> "stop";
		});
	}

	private static Text playersText() {
		return Text.literal("Hide from: " + switch (GkConfig.INSTANCE.players) {
			case OFF -> "nobody";
			case NAUGHTY -> "your lists";
			case STRANGERS -> "strangers";
		});
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
			if (y + 9 > textBottom) break;
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
			if (Job.plan != null) out.add(new Line(Job.summary() + " · " + Job.left() + " left · " + Job.plan.coverage() + "% mycelium", WHITE));
			if (Areas.running() && Areas.project != null) out.add(new Line("Area: " + Areas.progress(Areas.project), PURPLE));
			if (Shelter.active()) out.add(new Line("Sheltering: " + Shelter.why, YELLOW));
			return out;
		}
		if (Areas.project != null && !Areas.project.mask.isEmpty()) {
			Project a = Areas.project;
			out.add(new Line(String.format(java.util.Locale.ROOT, "Area: %,d blocks² (%s) · %s", a.selectedColumns(), Areas.size(a.selectedColumns()), Areas.progress(a)), PURPLE));
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
