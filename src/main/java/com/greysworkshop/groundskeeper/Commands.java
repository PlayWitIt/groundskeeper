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
				.then(literal("start").executes(c -> {
					MinecraftClient client = MinecraftClient.getInstance();
					client.send(() -> {
						if (Selection.mode != Selection.Mode.READY) {
							c.getSource().sendError(Text.literal("Pick a circle first: press O in the middle, then O at the edge (or /gk circle <radius>)."));
							return;
						}
						Job.start(client, Plan.make(client.world, Selection.center, Selection.radius, true));
					});
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
		s.sendFeedback(Text.literal("Seed spacing " + cf.spacing + " · border " + cf.border.name().toLowerCase() + " · speed " + cf.speed + "/s · clear flowers " + cf.clearFlowers
				+ " · watch " + cf.watch + " · stop at health " + cf.minHealth + (cf.swapMode() ? " · swap " + cf.swapFrom + " -> " + cf.swapTo : "")).formatted(Formatting.GRAY));
		return 1;
	}

	private static int help(CommandContext<FabricClientCommandSource> c) {
		FabricClientCommandSource s = c.getSource();
		s.sendFeedback(Text.literal("Groundskeeper: grass to mycelium in a circle.").formatted(Formatting.DARK_GREEN));
		s.sendFeedback(Text.literal("Press O in the middle, walk to the edge, press O (it shows the plan), press O to start. O again stops. Shift+O cancels."));
		s.sendFeedback(Text.literal("/gk circle <radius> · start · stop · cancel · status · blocked · skipped").formatted(Formatting.GRAY));
		s.sendFeedback(Text.literal("/gk spacing <2-12> · border <path|mycelium|none> · speed <1-20> · flowers <true|false> · watch <true|false> · minhealth <n> · hud <true|false>"
				+ " · swap <from> <to> | off").formatted(Formatting.GRAY));
		return 1;
	}
}
