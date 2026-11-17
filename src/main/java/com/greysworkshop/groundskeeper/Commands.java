package com.greysworkshop.groundskeeper;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.argument.BlockStateArgument;
import net.minecraft.command.argument.BlockStateArgumentType;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.function.Consumer;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/** /groundskeeper (or /gk): selecting, starting, stopping and settings. All client-side. */
final class Commands {
	private Commands() {}

	static void register(CommandDispatcher<FabricClientCommandSource> d, CommandRegistryAccess access) {
		d.register(build("groundskeeper", access));
		d.register(build("gk", access));
	}

	private static LiteralArgumentBuilder<FabricClientCommandSource> build(String name, CommandRegistryAccess access) {
		return literal(name)
				.executes(Commands::help)
				.then(literal("circle").then(argument("radius", IntegerArgumentType.integer(2, 160)).executes(c -> {
					MinecraftClient client = MinecraftClient.getInstance();
					int r = IntegerArgumentType.getInteger(c, "radius");
					client.send(() -> {
						if (client.player == null || Job.running()) return;
						Selection.set(client, client.player, Walker.feet(client.player).down(), r);
					});
					return 1;
				})))
				.then(area())
				.then(literal("night")
						.then(literal("bunker").executes(c -> set(c, cf -> cf.night = GkConfig.Night.BUNKER, "At night: dig in, carry on in the morning")))
						.then(literal("zone").executes(c -> set(c, cf -> cf.night = GkConfig.Night.ZONE,
								"At night: go to the safety zone if it's within " + GkConfig.INSTANCE.zoneDistance + " blocks, else dig in" + (GkConfig.INSTANCE.zoneSet ? "" : " (set it: /gk zone set)"))))
						.then(literal("work").executes(c -> set(c, cf -> cf.night = GkConfig.Night.WORK, "At night: keep working (it still hides from danger)")))
						.then(literal("stop").executes(c -> set(c, cf -> cf.night = GkConfig.Night.STOP, "At night: stop the job"))))
				.then(literal("zone")
						.then(literal("set").executes(c -> {
							MinecraftClient client = MinecraftClient.getInstance();
							if (client.player == null || client.world == null) return 0;
							BlockPos f = Walker.feet(client.player);
							String dim = Areas.dimension(client.world);
							return set(c, cf -> {
								cf.zoneSet = true;
								cf.zoneX = f.getX();
								cf.zoneY = f.getY();
								cf.zoneZ = f.getZ();
								cf.zoneDimension = dim;
								if (cf.night == GkConfig.Night.BUNKER) cf.night = GkConfig.Night.ZONE;
							}, "Safety zone set here (" + f.toShortString() + "). At night it comes here if it's within " + GkConfig.INSTANCE.zoneDistance
									+ " blocks, else it digs in.");
						}))
						.then(literal("clear").executes(c -> set(c, cf -> {
							cf.zoneSet = false;
							if (cf.night == GkConfig.Night.ZONE) cf.night = GkConfig.Night.BUNKER;
						}, "Safety zone cleared: it digs in at night")))
						.then(literal("distance").then(argument("blocks", IntegerArgumentType.integer(0, 10_000)).executes(c -> set(c,
								cf -> cf.zoneDistance = IntegerArgumentType.getInteger(c, "blocks"),
								"Goes to the safety zone at dusk if it's within " + IntegerArgumentType.getInteger(c, "blocks") + " blocks")))))
				.then(literal("players")
						.then(literal("off").executes(c -> set(c, cf -> cf.players = Threat.Players.OFF, "Never hides from players (your kill aura, your call)")))
						.then(literal("naughty").executes(c -> set(c, cf -> cf.players = Threat.Players.NAUGHTY,
								"Hides from players on your Naughty or Look Into list" + (NaughtyBridge.present() ? "" : " (needs Naughty or Nice)"))))
						.then(literal("strangers").executes(c -> set(c, cf -> cf.players = Threat.Players.STRANGERS,
								"Hides from anyone who isn't a friend or on your Nice list"))))
				.then(literal("eat").then(argument("on", BoolArgumentType.bool()).executes(c -> set(c, cf -> cf.autoEat = BoolArgumentType.getBool(c, "on"),
						"Eats when hungry: " + BoolArgumentType.getBool(c, "on")))))
				.then(literal("totem").then(argument("on", BoolArgumentType.bool()).executes(c -> set(c, cf -> cf.totem = BoolArgumentType.getBool(c, "on"),
						"Keeps a totem in an empty off hand: " + BoolArgumentType.getBool(c, "on")))))
				.then(literal("hitpause").then(argument("on", BoolArgumentType.bool()).executes(c -> set(c, cf -> cf.holdWhenHit = BoolArgumentType.getBool(c, "on"),
						BoolArgumentType.getBool(c, "on") ? "When hit: holds off for 3 seconds (keys and camera left to your other mods)" : "When hit: keeps working"))))
				.then(literal("tools")
						.executes(c -> {
							GkConfig cf = GkConfig.INSTANCE;
							c.getSource().sendFeedback(Text.literal(cf.protectTools
									? "Tool guard on: each tool is put aside with " + cf.toolReserve + " uses left, so you can mend it. /gk tools off, /gk tools reserve <uses>"
									: "Tool guard off: tools are used until they break. /gk tools on").formatted(Formatting.GRAY));
							return 1;
						})
						.then(literal("on").executes(c -> set(c, cf -> cf.protectTools = true,
								"Tool guard on: tools are put aside with " + GkConfig.INSTANCE.toolReserve + " uses left, so you can mend them")))
						.then(literal("off").executes(c -> set(c, cf -> cf.protectTools = false, "Tool guard off: tools are used until they break")))
						.then(literal("reserve").then(argument("uses", IntegerArgumentType.integer(2, 500)).executes(c -> set(c, cf -> {
							cf.toolReserve = IntegerArgumentType.getInteger(c, "uses");
							cf.protectTools = true;
						}, "Tool guard on: tools are put aside with " + IntegerArgumentType.getInteger(c, "uses") + " uses left")))))
				.then(literal("start").executes(c -> {
					MinecraftClient client = MinecraftClient.getInstance();
					client.send(() -> Job.startOrResume(client));
					return 1;
				}))
				.then(literal("pause").executes(c -> {
					MinecraftClient client = MinecraftClient.getInstance();
					client.send(() -> Job.togglePause(client));
					return 1;
				}))
				.then(literal("panel").executes(c -> {
					MinecraftClient client = MinecraftClient.getInstance();
					client.send(() -> client.setScreen(new PanelScreen()));
					return 1;
				}))
				.then(literal("stop").executes(c -> {
					MinecraftClient client = MinecraftClient.getInstance();
					client.send(() -> Job.stop(client, "Stopped."));
					return 1;
				}))
				.then(literal("cancel").executes(c -> {
					MinecraftClient client = MinecraftClient.getInstance();
					client.send(() -> {
						if (client.player != null) Selection.cancel(client.player);
					});
					return 1;
				}))
				.then(literal("status").executes(Commands::status))
				.then(literal("blocked").executes(c -> list(c, "Grass it can't get at (something on top of it)", Selection.preview == null ? null : Selection.preview.blocked)))
				.then(literal("skipped").executes(c -> {
					FabricClientCommandSource s = c.getSource();
					if (Job.failed.isEmpty()) {
						s.sendFeedback(Text.literal("Nothing skipped."));
						return 1;
					}
					s.sendFeedback(Text.literal("Skipped " + Job.failed.size() + ":").formatted(Formatting.YELLOW));
					int n = 0;
					for (Map.Entry<BlockPos, String> e : Job.failed.entrySet()) {
						if (n++ >= 20) break;
						s.sendFeedback(Text.literal("  " + e.getKey().toShortString() + ": " + e.getValue()).formatted(Formatting.GRAY));
					}
					return 1;
				}))
				.then(literal("spacing").then(argument("blocks", IntegerArgumentType.integer(2, 12)).executes(c -> set(c,
						cf -> cf.spacing = IntegerArgumentType.getInteger(c, "blocks"),
						"Seeds every " + IntegerArgumentType.getInteger(c, "blocks") + " blocks (smaller: faster, more mycelium; default 5)"))))
				.then(literal("border")
						.then(literal("path").executes(c -> set(c, cf -> cf.border = GkConfig.Border.PATH, "Border: dirt path (free)")))
						.then(literal("mycelium").executes(c -> set(c, cf -> cf.border = GkConfig.Border.MYCELIUM, "Border: mycelium (costs a ring of it)")))
						.then(literal("none").executes(c -> set(c, cf -> cf.border = GkConfig.Border.NONE, "Border: none (grass outside will fight the edge)"))))
				.then(literal("speed").then(argument("perSecond", IntegerArgumentType.integer(1, 20)).executes(c -> set(c,
						cf -> cf.speed = IntegerArgumentType.getInteger(c, "perSecond"), "Speed: " + IntegerArgumentType.getInteger(c, "perSecond") + " blocks a second"))))
				.then(literal("flowers").then(argument("clear", BoolArgumentType.bool()).executes(c -> set(c, cf -> cf.clearFlowers = BoolArgumentType.getBool(c, "clear"),
						BoolArgumentType.getBool(c, "clear") ? "Flowers on grass get broken (they drop)" : "Flowers are left alone, with their grass"))))
				.then(literal("watch").then(argument("on", BoolArgumentType.bool()).executes(c -> set(c, cf -> cf.watch = BoolArgumentType.getBool(c, "on"),
						"Watch it spread afterwards: " + BoolArgumentType.getBool(c, "on")))))
				.then(literal("minhealth").then(argument("halfHearts", IntegerArgumentType.integer(0, 19)).executes(c -> set(c,
						cf -> cf.minHealth = IntegerArgumentType.getInteger(c, "halfHearts"), "Stops at " + IntegerArgumentType.getInteger(c, "halfHearts") + " health"))))
				.then(literal("hud").then(argument("on", BoolArgumentType.bool()).executes(c -> set(c, cf -> cf.hud = BoolArgumentType.getBool(c, "on"),
						"HUD: " + BoolArgumentType.getBool(c, "on")))))
				.then(literal("swap")
						.then(literal("off").executes(c -> set(c, cf -> cf.swapFrom = cf.swapTo = "", "Back to grass to mycelium")))
						.then(argument("from", BlockStateArgumentType.blockState(access)).then(argument("to", BlockStateArgumentType.blockState(access)).executes(c -> {
							Block from = c.getArgument("from", BlockStateArgument.class).getBlockState().getBlock();
							Block to = c.getArgument("to", BlockStateArgument.class).getBlockState().getBlock();
							return set(c, cf -> {
								cf.swapFrom = Registries.BLOCK.getId(from).toString();
								cf.swapTo = Registries.BLOCK.getId(to).toString();
							}, "Swap mode: every exposed " + from.getName().getString() + " becomes " + to.getName().getString() + " (/gk swap off to go back)");
						}))));
	}

	/** /gk area: big areas, any shape, worked tile by tile. */
	private static LiteralArgumentBuilder<FabricClientCommandSource> area() {
		return literal("area")
				.executes(c -> onClient(client -> {
					Areas.get(client);
					Areas.describe(client.player);
				}))
				.then(literal("map").executes(c -> onClient(MapBridge::open)))
				.then(literal("start").executes(c -> onClient(Areas::start)))
				.then(shape("add", false))
				.then(shape("cut", true))
				.then(literal("undo").executes(c -> onClient(client -> {
					Project p = Areas.get(client);
					if (p == null || p.shapes.isEmpty()) {
						Job.chat(client.player, Text.literal("No shapes to undo.").formatted(Formatting.GRAY));
						return;
					}
					java.util.List<Shapes.Shape> list = new java.util.ArrayList<>(p.shapes);
					list.remove(list.size() - 1);
					Areas.setShapes(client, new Shapes(list));
					Areas.describe(client.player);
				})))
				.then(literal("clear").executes(c -> onClient(client -> {
					if (Areas.running()) Job.stop(client, "Stopped.");
					Areas.clear(client);
					Job.chat(client.player, Text.literal("Area forgotten (and its progress).").formatted(Formatting.YELLOW));
				})))
				.then(literal("tile").then(argument("chunks", IntegerArgumentType.integer(1, 16)).executes(c -> {
					int n = IntegerArgumentType.getInteger(c, "chunks");
					return onClient(client -> {
						GkConfig.INSTANCE.tileChunks = n;
						GkConfig.save();
						Project p = Areas.get(client);
						if (p != null && p.count(Project.State.QUEUED) < p.tiles.size()) {
							Job.chat(client.player, Text.literal("New areas get " + n * 16 + "-block tiles. This one keeps its " + p.tileChunks * 16
									+ "-block tiles: it's already under way.").formatted(Formatting.YELLOW));
							return;
						}
						if (p != null) {
							p.tileChunks = n;
							p.rebuild();
							Areas.save(client);
						}
						Job.chat(client.player, Text.literal("Tiles: " + n * 16 + " × " + n * 16 + " blocks. Keep them within your simulation distance: they spread while you're near.")
								.formatted(Formatting.GREEN));
					});
				})));
	}

	private static LiteralArgumentBuilder<FabricClientCommandSource> shape(String name, boolean cut) {
		return literal(name)
				.then(literal("rect").then(argument("x1", IntegerArgumentType.integer()).then(argument("z1", IntegerArgumentType.integer())
						.then(argument("x2", IntegerArgumentType.integer()).then(argument("z2", IntegerArgumentType.integer()).executes(c -> {
							Shapes.Shape s = Shapes.Shape.rect(IntegerArgumentType.getInteger(c, "x1"), IntegerArgumentType.getInteger(c, "z1"),
									IntegerArgumentType.getInteger(c, "x2"), IntegerArgumentType.getInteger(c, "z2"));
							return addShape(cut ? s.cutting() : s);
						}))))))
				.then(literal("circle").then(argument("radius", IntegerArgumentType.integer(1, 8000)).executes(c -> {
					int r = IntegerArgumentType.getInteger(c, "radius");
					return aroundMe((x, z) -> Shapes.Shape.ellipse(x, z, r, r), cut);
				})))
				.then(literal("ellipse").then(argument("radiusX", IntegerArgumentType.integer(1, 8000)).then(argument("radiusZ", IntegerArgumentType.integer(1, 8000)).executes(c -> {
					int rx = IntegerArgumentType.getInteger(c, "radiusX"), rz = IntegerArgumentType.getInteger(c, "radiusZ");
					return aroundMe((x, z) -> Shapes.Shape.ellipse(x, z, rx, rz), cut);
				}))))
				.then(literal("square").then(argument("halfSide", IntegerArgumentType.integer(1, 8000)).executes(c -> {
					int h = IntegerArgumentType.getInteger(c, "halfSide");
					return aroundMe((x, z) -> Shapes.Shape.rect(x - h, z - h, x + h, z + h), cut);
				})));
	}

	private interface AroundMe {
		Shapes.Shape at(int x, int z);
	}

	private static int aroundMe(AroundMe make, boolean cut) {
		return onClient(client -> {
			BlockPos f = Walker.feet(client.player);
			Shapes.Shape s = make.at(f.getX(), f.getZ());
			Areas.add(client, cut ? s.cutting() : s);
		});
	}

	private static int addShape(Shapes.Shape s) {
		return onClient(client -> Areas.add(client, s));
	}

	/** Runs on the game thread, once there's a player. */
	private static int onClient(Consumer<MinecraftClient> run) {
		MinecraftClient client = MinecraftClient.getInstance();
		client.send(() -> {
			if (client.player != null && client.world != null) run.accept(client);
		});
		return 1;
	}

	private static int list(CommandContext<FabricClientCommandSource> c, String title, java.util.List<BlockPos> list) {
		FabricClientCommandSource s = c.getSource();
		if (list == null || list.isEmpty()) {
			s.sendFeedback(Text.literal("None."));
			return 1;
		}
		s.sendFeedback(Text.literal(title + ": " + list.size()).formatted(Formatting.YELLOW));
		for (int i = 0; i < Math.min(20, list.size()); i++) s.sendFeedback(Text.literal("  " + list.get(i).toShortString()).formatted(Formatting.GRAY));
		if (list.size() > 20) s.sendFeedback(Text.literal("  ...and " + (list.size() - 20) + " more").formatted(Formatting.GRAY));
		return 1;
	}

	private static int set(CommandContext<FabricClientCommandSource> c, Consumer<GkConfig> change, String said) {
		change.accept(GkConfig.INSTANCE);
		GkConfig.save();
		c.getSource().sendFeedback(Text.literal(said).formatted(Formatting.GREEN));
		// Settings change the plan: show it again.
		MinecraftClient client = MinecraftClient.getInstance();
		client.send(() -> {
			if (Selection.mode == Selection.Mode.READY && !Job.running() && client.player != null) {
				Selection.set(client, client.player, Selection.center, Selection.radius);
			}
		});
		return 1;
	}

	private static int status(CommandContext<FabricClientCommandSource> c) {
		GkConfig cf = GkConfig.INSTANCE;
		FabricClientCommandSource s = c.getSource();
		s.sendFeedback(Text.literal("Groundskeeper: " + (Job.running() ? Job.status.getString() : "stopped" + (Job.stopReason.isEmpty() ? "" : " (" + Job.stopReason + ")")))
				.formatted(Formatting.DARK_GREEN));
		if (Job.plan != null) s.sendFeedback(Text.literal("This job: " + Job.summary() + " · " + Job.left() + " left · " + Job.plan.coverage() + "% mycelium"));
		s.sendFeedback(Text.literal("All time: " + cf.totalBlocks + " blocks, " + cf.totalSeeds + " mycelium seeds"));
		if (Areas.project != null && !Areas.project.mask.isEmpty()) s.sendFeedback(Text.literal("Area: " + Areas.progress(Areas.project)).formatted(Formatting.GREEN));
		s.sendFeedback(Text.literal("Night: " + cf.night.name().toLowerCase() + (cf.zoneSet ? " · safety zone " + cf.zoneX + " " + cf.zoneY + " " + cf.zoneZ + " (within " + cf.zoneDistance + ")" : "")
				+ " · hides from players: " + cf.players.name().toLowerCase() + " · eat " + cf.autoEat + " · totem " + cf.totem).formatted(Formatting.GRAY));
		s.sendFeedback(Text.literal("Seed spacing " + cf.spacing + " · border " + cf.border.name().toLowerCase() + " · speed " + cf.speed + "/s · clear flowers " + cf.clearFlowers
				+ " · watch " + cf.watch + " · stop at health " + cf.minHealth + (cf.swapMode() ? " · swap " + cf.swapFrom + " -> " + cf.swapTo : "")).formatted(Formatting.GRAY));
		return 1;
	}

	private static int help(CommandContext<FabricClientCommandSource> c) {
		FabricClientCommandSource s = c.getSource();
		s.sendFeedback(Text.literal("Groundskeeper: grass to mycelium in a circle.").formatted(Formatting.DARK_GREEN));
		s.sendFeedback(Text.literal("Stand in the middle: Numpad 1 starts (a circle around you, the panel's radius, or /gk circle <radius> first to see the plan). Numpad 2 pauses, Numpad 3 stops. /gk panel opens the panel."));
		s.sendFeedback(Text.literal("/gk circle <radius> · start · pause · stop · panel · cancel · status · blocked · skipped").formatted(Formatting.GRAY));
		s.sendFeedback(Text.literal("Big areas, any shape: /gk area (status) · map (draw it on Base Insurance's map) · add|cut rect <x1> <z1> <x2> <z2> | circle <r> | ellipse <rx> <rz> | square <half>"
				+ " · undo · clear · start · tile <chunks>").formatted(Formatting.GRAY));
		s.sendFeedback(Text.literal("Staying alive: /gk night <bunker|zone|work|stop> · zone set|clear|distance <n> · players <off|naughty|strangers> · eat <on> · totem <on>")
				.formatted(Formatting.GRAY));
		s.sendFeedback(Text.literal("/gk spacing <2-12> · border <path|mycelium|none> · speed <1-20> · flowers <true|false> · watch <true|false> · minhealth <n> · hud <true|false>"
				+ " · swap <from> <to> | off").formatted(Formatting.GRAY));
		return 1;
	}
}
