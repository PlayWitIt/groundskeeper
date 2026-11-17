package com.greysworkshop.groundskeeper;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

import java.util.function.Predicate;

/**
 * Getting somewhere further than a job's own walking: to the next tile of an area, back to one that needs fixing,
 * or to the safety zone at dusk. Long trips go with Baritone if it's installed (as one of Naughty or Nice's trips if
 * that's installed too, like Base Insurance's Walk here); otherwise, and for short hops, the job's own walker goes in
 * stages of up to 32 blocks. Both mods are optional and reached by reflection.
 */
final class Travel {
	enum Result {
		MOVING, ARRIVED, FAILED
	}

	private static Boolean baritone;
	private static boolean usingBaritone;
	private static int targetX, targetZ, ticks, fails, sinceCloser;
	private static double closest;
	private static boolean active;
	private static BlockPos hop;

	private Travel() {}

	static boolean baritoneInstalled() {
		if (baritone == null) {
			try {
				Class.forName("baritone.api.BaritoneAPI");
				baritone = true;
			} catch (ClassNotFoundException | LinkageError e) {
				baritone = false;
			}
		}
		return baritone;
	}

	static boolean active() {
		return active;
	}

	/** Moves towards (x, z) a tick at a time until within {@code within} blocks. */
	static Result tick(MinecraftClient client, ClientPlayerEntity p, ClientWorld w, Walker walker, int x, int z, double within) {
		double d = Math.hypot(x + 0.5 - p.getX(), z + 0.5 - p.getZ());
		if (!active || targetX != x || targetZ != z) {
			cancel(client, walker);
			active = true;
			targetX = x;
			targetZ = z;
			ticks = fails = sinceCloser = 0;
			closest = d;
			usingBaritone = false;
			hop = null;
			if (d > 96 && baritoneInstalled()) usingBaritone = baritoneGo(client, x, z);
			Job.log("travel to {} {} ({} blocks, {})", x, z, Math.round(d), usingBaritone ? "Baritone" : "walking");
		}
		ticks++;
		if (d <= within) {
			cancel(client, walker);
			return Result.ARRIVED;
		}
		if (d < closest - 1) {
			closest = d;
			sinceCloser = 0;
		} else sinceCloser++;
		if (usingBaritone) {
			// Baritone gave up, or hasn't got any closer in a minute: walk the rest.
			if (sinceCloser > 1200 || ticks > 40 && !baritonePathing()) {
				baritoneStop(client);
				usingBaritone = false;
				sinceCloser = 0;
				Job.log("travel: Baritone stopped short, walking the rest");
			}
			return Result.MOVING;
		}
		if (hop == null || walker.arrived() && !walker.moving()) {
			// The next stage: up to 32 blocks straight towards it, or a bit to either side if that won't go.
			double step = Math.min(32, d);
			double ang = Math.atan2(z + 0.5 - p.getZ(), x + 0.5 - p.getX());
			boolean planned = false;
			for (double turn : new double[] {0, 0.6, -0.6, 1.2, -1.2}) {
				for (double len : new double[] {step, step / 2}) {
					int hx = (int) Math.floor(p.getX() + Math.cos(ang + turn) * len), hz = (int) Math.floor(p.getZ() + Math.sin(ang + turn) * len);
					BlockPos target = new BlockPos(hx, p.getBlockY(), hz);
					double tol = len >= d - 0.5 ? Math.max(1.5, within - 1) : 5;
					Predicate<BlockPos> goal = c -> Math.hypot(c.getX() - target.getX(), c.getZ() - target.getZ()) <= tol;
					int minX = Math.min(p.getBlockX(), hx) - 16, maxX = Math.max(p.getBlockX(), hx) + 16;
					int minZ = Math.min(p.getBlockZ(), hz) - 16, maxZ = Math.max(p.getBlockZ(), hz) + 16;
					int[] bounds = {minX, Math.max(w.getBottomY(), p.getBlockY() - 40), minZ, maxX, p.getBlockY() + 40, maxZ};
					if (walker.plan(w, p, goal, bounds)) {
						hop = target;
						planned = true;
						break;
					}
				}
				if (planned) break;
			}
			if (!planned) {
				if (++fails > 8) {
					cancel(client, walker);
					return Result.FAILED;
				}
				hop = null;
				return Result.MOVING;
			}
		}
		if (!walker.tick(client)) hop = null;
		if (sinceCloser > 2400) {
			cancel(client, walker);
			return Result.FAILED;
		}
		return Result.MOVING;
	}

	static void cancel(MinecraftClient client, Walker walker) {
		if (usingBaritone) baritoneStop(client);
		usingBaritone = false;
		if (active) walker.clear(client);
		active = false;
		hop = null;
	}

	// ---------------------------------------------------------------- Baritone, by reflection

	private static boolean baritoneGo(MinecraftClient client, int x, int z) {
		try {
			Class<?> kind = Class.forName("com.greysworkshop.naughtyornice.Destinations$Kind");
			Class<?> destination = Class.forName("com.greysworkshop.naughtyornice.Destinations$Destination");
			Object waypoint = kind.getField("WAYPOINT").get(null);
			Object d = destination.getConstructor(kind, String.class, String.class, int.class, int.class, int.class, String.class, long.class)
					.newInstance(waypoint, "Groundskeeper", "Next part of the area", x, Integer.MIN_VALUE, z, Areas.dimension(client.world), System.currentTimeMillis());
			Object said = Class.forName("com.greysworkshop.naughtyornice.SmartTravel").getMethod("go", MinecraftClient.class, destination).invoke(null, client, d);
			if (said instanceof String s && s.startsWith("Going")) return true;
		} catch (ClassNotFoundException e) {
			// No Naughty or Nice: Baritone on its own.
		} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
			Groundskeeper.LOGGER.warn("Couldn't plan the trip with Naughty or Nice; asking Baritone directly", e);
		}
		return command("goto " + x + " " + z);
	}

	private static boolean command(String cmd) {
		try {
			Object b = primary();
			Object manager = Class.forName("baritone.api.IBaritone").getMethod("getCommandManager").invoke(b);
			Object ok = Class.forName("baritone.api.command.manager.ICommandManager").getMethod("execute", String.class).invoke(manager, cmd);
			return Boolean.TRUE.equals(ok);
		} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
			Groundskeeper.LOGGER.warn("Baritone '{}' failed", cmd, e);
			return false;
		}
	}

	private static Object primary() throws ReflectiveOperationException {
		Object provider = Class.forName("baritone.api.BaritoneAPI").getMethod("getProvider").invoke(null);
		return Class.forName("baritone.api.IBaritoneProvider").getMethod("getPrimaryBaritone").invoke(provider);
	}

	private static boolean baritonePathing() {
		try {
			Object behaviour = Class.forName("baritone.api.IBaritone").getMethod("getPathingBehavior").invoke(primary());
			Class<?> type = Class.forName("baritone.api.behavior.IPathingBehavior");
			// Still working out a long path counts: it has a goal, just no path yet.
			return Boolean.TRUE.equals(type.getMethod("isPathing").invoke(behaviour)) || type.getMethod("getGoal").invoke(behaviour) != null;
		} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
			return false;
		}
	}

	private static void baritoneStop(MinecraftClient client) {
		try {
			Class.forName("com.greysworkshop.naughtyornice.SmartTravel").getMethod("stop").invoke(null);
			return;
		} catch (ClassNotFoundException e) {
			// Baritone on its own.
		} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
			Groundskeeper.LOGGER.warn("Couldn't stop Naughty or Nice's trip", e);
		}
		command("stop");
	}
}
