package com.greysworkshop.groundskeeper;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Works through a {@link Plan}: walks to each block (nearest first), knocks off whatever grows on it, and tills it,
 * paths it or swaps in mycelium. When the work runs out it looks again (farmland turning to dirt next to grass it
 * hadn't got to yet can go green again), and then, with watch on, stays and keeps looking until it's all mycelium.
 * Out of mycelium with a shulker box of it in the inventory: puts the box down, empties it, picks it back up.
 */
final class Job {
	enum State {
		OFF, WORK, UNPACK, ROAM, WATCH,
		/** A big area: on the way to the next tile. */
		TRAVEL
	}

	static State state = State.OFF;
	static Plan plan;
	static Text status = Text.empty();
	static String stopReason = "";
	static int tilled, seeded, pathed, swapped, cleared, passes, unpacked, dirted;
	static long startedAt;
	/** Blocks it gave up on (can't reach, server won't change them), with why. Not tried again this job. */
	static final java.util.Map<BlockPos, String> failed = new java.util.LinkedHashMap<>();

	static final Walker walker = new Walker();
	/** Where TRAVEL is headed. */
	private static int travelX, travelZ;
	private static Plan.Task current, resume;
	private static int wait, tries, cool, breakTicks, reachTries, ticks, watchTimer, stage, stageTicks;
	private static BlockPos breaking, walkingTo, box;
	private static boolean atCenter;
	private static final java.util.Map<BlockPos, Integer> farmlandSeen = new java.util.HashMap<>();
	private static int boxesBefore;

	private Job() {}

	static boolean running() {
		return state != State.OFF;
	}

	/** Paused by the player (Numpad 2, /gk pause). */
	static boolean paused;
	/** Holding off for a fight (hit, or low health) until this tick, so kill aura has the camera and the keys. */
	private static int fightUntil;
	private static float lastHealth = -1;
	private static boolean holding;

	/** Numpad 1 or /gk start: resume if paused, else start on the circle (one around you if none is picked). */
	static void startOrResume(MinecraftClient client) {
		ClientPlayerEntity p = client.player;
		if (p == null || client.world == null) return;
		if (running()) {
			if (paused) togglePause(client);
			return;
		}
		// A big area here and no circle picked: carry on with the area.
		Project area = Areas.get(client);
		if (Selection.mode != Selection.Mode.READY && area != null && !area.mask.isEmpty()) {
			Areas.start(client);
			return;
		}
		if (Selection.mode != Selection.Mode.READY) {
			Selection.set(client, p, Walker.feet(p).down(), PanelScreen.radius);
		}
		start(client, Plan.make(client.world, Selection.center, Selection.radius, true));
	}

	/** Numpad 2 or /gk pause. */
	static void togglePause(MinecraftClient client) {
		ClientPlayerEntity p = client.player;
		if (!running() || p == null) return;
		paused = !paused;
		chat(p, Text.literal(paused ? "Paused. Numpad 2 (or Numpad 1) to carry on." : "Carrying on.").formatted(Formatting.YELLOW));
		if (!paused) status = Text.literal("Working");
	}

	/** A hostile mob or another player close by. */
	private static boolean enemyNear(ClientPlayerEntity p, ClientWorld w) {
		return !w.getEntitiesByClass(net.minecraft.entity.LivingEntity.class, p.getBoundingBox().expand(6),
				e -> e != p && e.isAlive() && (e instanceof net.minecraft.entity.mob.Monster || e instanceof net.minecraft.entity.player.PlayerEntity)).isEmpty();
	}

	/** The shelter takes over: let go of the work (it carries on from the same block afterwards). */
	static void pauseForShelter(MinecraftClient client) {
		release(client);
		wait = tries = reachTries = 0;
		if (state == State.UNPACK && stage == 3 && client.player != null && client.player.currentScreenHandler != client.player.playerScreenHandler) {
			client.player.closeHandledScreen();
			stage = 2;
		}
	}

	static void resumeAfterShelter() {
		wait = tries = reachTries = 0;
		walkingTo = null;
		status = Text.literal("Working");
	}

	static void cancelBreaking(MinecraftClient client) {
		if (breaking != null && client.interactionManager != null) client.interactionManager.cancelBlockBreaking();
		breaking = null;
	}

	/** Lets go of everything (keys, camera, the block being broken) so other mods can fight. */
	private static void hold(MinecraftClient client, ClientPlayerEntity p, Text why) {
		if (!holding) {
			holding = true;
			release(client);
			// Our shulker box screen open: close it, and open it again afterwards.
			if (state == State.UNPACK && stage == 3 && p.currentScreenHandler != p.playerScreenHandler) {
				p.closeHandledScreen();
				stage = 2;
			}
			log("holding: {}", why.getString());
		}
		status = why;
	}

	// ---------------------------------------------------------------- start and stop

	static void start(MinecraftClient client, Plan p) {
		ClientPlayerEntity player = client.player;
		if (player == null || running()) return;
		PlayerInventory inv = player.getInventory();
		String missing = missing(inv, p);
		if (missing != null) {
			stopReason = missing;
			chat(player, Text.literal(missing).formatted(Formatting.RED));
			return;
		}
		plan = p;
		tilled = seeded = pathed = swapped = cleared = passes = unpacked = dirted = 0;
		failed.clear();
		reachFails.clear();
		paused = holding = false;
		startCount = -1;
		fightUntil = 0;
		lastHealth = -1;
		farmlandSeen.clear();
		startedAt = System.currentTimeMillis();
		atCenter = false;
		stopReason = "";
		Shelter.reset();
		Survival.reset(client);
		if (!p.tile()) Areas.tile = null;
		enter(State.WORK);
		int have = itemCount(inv, placeItem()), need = p.seeds();
		if (have < need) {
			chat(player, Text.literal("You have " + have + " " + placeName() + " for " + need + " blocks: it will stop when it runs out.").formatted(Formatting.YELLOW));
		}
		chat(player, Text.literal("Started. Numpad 2 pauses, Numpad 3 stops.").formatted(Formatting.GREEN));
		status = Text.literal("Working");
	}

	/** What it can't start without, or null. */
	static String missing(PlayerInventory inv, Plan p) {
		if (p.swap && (p.from == null || p.to == null)) return "Swap mode needs two real block ids: /gk swap <from> <to>.";
		if (p.swap && p.to.asItem() == Items.AIR) return p.to.getName().getString() + " has no item to place.";
		if (p.tasks.isEmpty()) return "Nothing to do in that circle.";
		if (p.count(Plan.Kind.TILL) > 0 && best(inv, s -> s.isIn(ItemTags.HOES)) < 0) return "Needs a hoe (any hoe) for the grass. " + toolNote(inv, ItemTags.HOES);
		if (p.count(Plan.Kind.PATH) > 0 && best(inv, s -> s.isIn(ItemTags.SHOVELS)) < 0)
			return "Needs a shovel for the path border (or /gk border mycelium, or /gk border none). " + toolNote(inv, ItemTags.SHOVELS);
		if (p.seeds() > 0 && itemCount(inv, placeItem()) == 0) return "No " + placeName() + " in your inventory (or in a shulker box in it).";
		return null;
	}

	/** A big area: walks to its next tile ({@link Areas} picked it), then plans and works it. */
	static void startArea(MinecraftClient client, int x, int z) {
		ClientPlayerEntity player = client.player;
		if (player == null || running()) return;
		PlayerInventory inv = player.getInventory();
		String missing = null;
		if (GkConfig.INSTANCE.swapMode()) missing = "Swap mode is on: big areas are grass to mycelium only (/gk swap off).";
		else if (best(inv, s -> s.isIn(ItemTags.HOES)) < 0) missing = "Needs a hoe (any hoe) for the grass. " + toolNote(inv, ItemTags.HOES);
		else if (best(inv, s -> s.isIn(ItemTags.SHOVELS)) < 0) missing = "Needs a shovel: big areas have a path border and seams between tiles. " + toolNote(inv, ItemTags.SHOVELS);
		else if (itemCount(inv, Items.MYCELIUM) == 0) missing = "No mycelium in your inventory (or in a shulker box in it).";
		if (missing != null) {
			stopReason = missing;
			Areas.tile = null;
			chat(player, Text.literal(missing).formatted(Formatting.RED));
			return;
		}
		plan = null;
		tilled = seeded = pathed = swapped = cleared = passes = unpacked = dirted = 0;
		failed.clear();
		reachFails.clear();
		paused = holding = false;
		startCount = -1;
		fightUntil = 0;
		lastHealth = -1;
		farmlandSeen.clear();
		startedAt = System.currentTimeMillis();
		stopReason = "";
		Shelter.reset();
		Survival.reset(client);
		Selection.mode = Selection.Mode.NONE;
		travelTo(x, z);
		chat(player, Text.literal("Area started: " + Areas.progress(Areas.project) + ". Numpad 2 pauses, Numpad 3 stops; it picks up where it left off next time.")
				.formatted(Formatting.GREEN));
	}

	/** On to another tile of the area. */
	static void travelTo(int x, int z) {
		travelX = x;
		travelZ = z;
		enter(State.TRAVEL);
		status = Text.literal("Walking to the next part of the area");
	}

	private static void travel(MinecraftClient client, ClientPlayerEntity p, ClientWorld w) {
		double d = Math.hypot(travelX + 0.5 - p.getX(), travelZ + 0.5 - p.getZ());
		status = Text.literal(String.format(java.util.Locale.ROOT, "Walking to %s %s (%,d blocks)", Areas.doing == Areas.Doing.WORK ? "the next tile" : "a tile to "
				+ (Areas.doing == Areas.Doing.CHECK ? "check" : "watch"), Areas.tile == null ? "" : Project.name(Areas.tile), Math.round(d)));
		switch (Travel.tick(client, p, w, walker, travelX, travelZ, 12)) {
			case ARRIVED -> {
				Plan next = Areas.arrived(client, p, w);
				// Null: nothing to do there, and it's already sent the job on (or stopped it).
				if (next == null) return;
				plan = next;
				passes = 0;
				startCount = -1;
				if (Areas.doing == Areas.Doing.WATCH) {
					watchTimer = 0;
					atCenter = false;
					enter(State.WATCH);
				} else enter(State.WORK);
			}
			case FAILED -> Areas.skip(client, p, "couldn't walk there");
			default -> {}
		}
	}

	private static String toolNote(PlayerInventory inv, net.minecraft.registry.tag.TagKey<Item> tag) {
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) if (inv.getStack(i).isIn(tag)) return "(Yours is about to break: it won't use it.)";
		return "";
	}

	static void stop(MinecraftClient client, String why) {
		if (state == State.OFF) return;
		state = State.OFF;
		stopReason = why;
		release(client);
		Survival.reset(client);
		if (Shelter.active()) walker.placeUnder = c -> false;
		Shelter.reset();
		Areas.stopped(client);
		ClientPlayerEntity p = client.player;
		if (p != null) {
			if (p.currentScreenHandler != p.playerScreenHandler) p.closeHandledScreen();
			chat(p, Text.literal(why).formatted(Formatting.YELLOW));
			p.playSound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 1, 0.7f);
		}
		status = Text.literal(why);
		GkConfig.INSTANCE.totalBlocks += tilled + pathed + seeded + swapped;
		GkConfig.INSTANCE.totalSeeds += seeded;
		GkConfig.save();
		log("stopped: {}", why);
		if (p != null && Boolean.getBoolean("groundskeeper.debug")) {
			StringBuilder inv = new StringBuilder();
			for (int i = 0; i < p.getInventory().size(); i++) {
				ItemStack st = p.getInventory().getStack(i);
				if (!st.isEmpty()) inv.append(i).append('=').append(st).append(st.isDamageable() ? "(" + (st.getMaxDamage() - st.getDamage()) + ")" : "").append(' ');
			}
			log("inventory: {} cursor={}", inv, p.currentScreenHandler.getCursorStack());
		}
	}

	private static void release(MinecraftClient client) {
		Travel.cancel(client, walker);
		walker.clear(client);
		walkingTo = null;
		if (breaking != null && client.interactionManager != null) client.interactionManager.cancelBlockBreaking();
		breaking = null;
		client.options.sneakKey.setPressed(false);
	}

	private static void enter(State s) {
		state = s;
		current = null;
		wait = tries = stage = stageTicks = reachTries = 0;
		walkingTo = null;
		log("-> {}", s);
	}

	// ---------------------------------------------------------------- the loop

	static void tick(MinecraftClient client) {
		if (state == State.OFF) return;
		ClientPlayerEntity p = client.player;
		ClientWorld w = client.world;
		if (p == null || w == null || client.interactionManager == null) return;
		ticks++;
		countUsed(p);
		if (p.isDead()) {
			stop(client, "Stopped: you died.");
			return;
		}
		// Hit: hold off for 3 seconds after the last hit, and while an enemy is still close.
		float hp = p.getHealth();
		if (p.hurtTime > 0 || lastHealth >= 0 && hp < lastHealth) fightUntil = ticks + 60;
		lastHealth = hp;
		boolean low = hp <= GkConfig.INSTANCE.minHealth;
		if (ticks < fightUntil && enemyNear(p, w)) fightUntil = Math.max(fightUntil, ticks + 20);
		if (paused || low || ticks < fightUntil) {
			hold(client, p, Text.literal(paused ? "Paused (Numpad 2 to carry on)"
					: low ? "Holding: low health (carries on above " + GkConfig.INSTANCE.minHealth / 2 + " hearts)" : "Holding: under attack"));
			return;
		}
		if (holding) {
			holding = false;
			wait = tries = reachTries = 0;
			walkingTo = null;
			status = Text.literal("Working");
			log("carrying on");
		}
		// Your screen open (inventory, chat): wait. Ours (the shulker box) is fine.
		if (client.currentScreen != null && !(state == State.UNPACK && (stage == 2 || stage == 3))) {
			walker.stop(client);
			return;
		}
		Areas.tick(client, p, w);
		// Not in the middle of emptying a shulker box (its screen is open).
		if (state != State.UNPACK || stage < 2 || stage > 3) {
			if (!Shelter.active() || Shelter.mode == Shelter.Mode.INSIDE || Shelter.mode == Shelter.Mode.AT_ZONE || Shelter.mode == Shelter.Mode.EXPOSED) {
				if (Survival.tick(client, p)) return;
			}
			if (Shelter.tick(client, p, w)) return;
		}
		if (cool > 0) cool--;
		switch (state) {
			case WORK -> work(client, p, w);
			case UNPACK -> unpack(client, p, w);
			case ROAM -> roam(client, p, w);
			case WATCH -> watch(client, p, w);
			case TRAVEL -> travel(client, p, w);
			default -> {}
		}
	}

	private static void work(MinecraftClient client, ClientPlayerEntity p, ClientWorld w) {
		if (current == null || current.done || current.failed) {
			current = pick(p);
			wait = tries = stage = reachTries = 0;
			walkingTo = null;
			if (current == null) {
				lookAgain(client, p, w);
				return;
			}
		}
		Plan.Task t = current;
		BlockState s = w.getBlockState(t.pos);
		if (isDone(t, s)) {
			complete(t, s);
			return;
		}
		if (!isTarget(t, s)) {
			// Changed by itself (someone else, a mob, a tree growing): not ours to do any more.
			t.done = true;
			if ("seam".equals(t.reason) && t.kind != Plan.Kind.PATH && !s.isOf(Blocks.DIRT_PATH)) Areas.seam(t.pos.asLong(), false);
			return;
		}
		boolean replace = t.kind == Plan.Kind.SEED || t.kind == Plan.Kind.SWAP || t.kind == Plan.Kind.DIRT;
		if (!reach(client, p, w, t.pos, replace)) return;
		status = Text.literal(verb(t) + " " + t.pos.toShortString());
		if (cool > 0) return;
		BlockState above = w.getBlockState(t.pos.up());
		if (!above.isAir() && !(stage == 1 && replace)) {
			if (Plan.clearable(above)) {
				if (breakBlock(client, p, w, t.pos.up())) cleared++;
				return;
			}
			fail(t, above.getBlock().getName().getString() + " on top");
			return;
		}
		switch (t.kind) {
			case TILL -> use(client, p, w, t, s2 -> s2.isIn(ItemTags.HOES), "hoe");
			case PATH -> use(client, p, w, t, s2 -> s2.isIn(ItemTags.SHOVELS), "shovel");
			case SEED, SWAP, DIRT -> {
				if (stage == 0) {
					if (s.isAir() || breakBlock(client, p, w, t.pos)) {
						stage = 1;
						wait = 0;
						// Straight in, before water next to it can flow into the hole.
						place(client, p, w, t);
					}
				} else {
					place(client, p, w, t);
				}
			}
		}
	}

	private static String verb(Plan.Task t) {
		return switch (t.kind) {
			case TILL -> "Tilling";
			case PATH -> "Border path";
			case SEED -> "Mycelium seed";
			case DIRT -> "Dirt (by water)";
			case SWAP -> "Swapping";
		};
	}

	private static boolean isDone(Plan.Task t, BlockState s) {
		return switch (t.kind) {
			case TILL -> s.isOf(Blocks.FARMLAND) || s.isOf(Blocks.DIRT) || s.isOf(Blocks.MYCELIUM);
			case PATH -> s.isOf(Blocks.DIRT_PATH);
			case SEED -> s.isOf(Blocks.MYCELIUM);
			case SWAP -> s.isOf(plan.to);
			case DIRT -> s.isOf(Blocks.DIRT) || s.isOf(Blocks.MYCELIUM);
		};
	}

	private static boolean isTarget(Plan.Task t, BlockState s) {
		return switch (t.kind) {
			case TILL -> s.isOf(Blocks.GRASS_BLOCK) || "seam".equals(t.reason) && s.isOf(Blocks.DIRT_PATH);
			case PATH -> s.isOf(Blocks.GRASS_BLOCK) || s.isOf(Blocks.DIRT);
			case SEED -> s.isOf(Blocks.GRASS_BLOCK) || s.isOf(Blocks.DIRT) || s.isOf(Blocks.FARMLAND) || s.isAir();
			case SWAP -> s.isOf(plan.from) || s.isAir();
			case DIRT -> s.isOf(Blocks.GRASS_BLOCK) || s.isOf(Blocks.FARMLAND) || s.isAir() || "seam".equals(t.reason) && s.isOf(Blocks.DIRT_PATH);
		};
	}

	private static void complete(Plan.Task t, BlockState s) {
		t.done = true;
		if (t.placed && MinecraftClient.getInstance().player != null)
			log("done {} {} placedMycelium={} block={} myc={}", t.kind, t.pos.toShortString(), t.placedMycelium, s.getBlock(), itemCount(MinecraftClient.getInstance().player.getInventory(), placeItem()));
		if ("seam".equals(t.reason)) Areas.seam(t.pos.asLong(), t.kind == Plan.Kind.PATH);
		switch (t.kind) {
			case TILL -> tilled++;
			case PATH -> pathed++;
			// Only what it put in itself: mycelium sometimes gets there first.
			case SEED -> {
				used(t); // counted from the inventory: see countUsed
			}
			case SWAP -> {
				if (used(t)) swapped++;
			}
			case DIRT -> {
				if (t.placedMycelium) used(t);
				else if (t.placed) dirted++;
			}
		}
		cool = Math.max(0, 20 / GkConfig.INSTANCE.speed - 1);
		current = null;
	}

	/** Mycelium (with what's in boxes) when the job started. */
	private static int startCount = -1;

	/**
	 * Seeds are what has left the inventory, not what it saw appear: on a server a placement it sees can still be
	 * refused a moment later (the block comes back, and it does that one again on the next pass).
	 */
	private static void countUsed(ClientPlayerEntity p) {
		if (plan == null || plan.swap || state == State.UNPACK) return;
		int now = itemCount(p.getInventory(), placeItem());
		if (startCount < 0) startCount = now;
		seeded = Math.max(0, startCount - now);
	}

	/** It placed a block there and one actually left the inventory (not grown in by itself, or a placement the server refused). */
	private static boolean used(Plan.Task t) {
		MinecraftClient c = MinecraftClient.getInstance();
		if (!t.placed || c.player == null) return false;
		boolean used = itemCount(c.player.getInventory(), t.placedMycelium || t.kind != Plan.Kind.DIRT ? placeItem() : Items.DIRT) < t.countBefore;
		if (!used) log("{} at {} filled without using a block", t.kind, t.pos.toShortString());
		return used;
	}

	private static final String CANT_REACH = "can't get there";
	private static final java.util.Map<BlockPos, Integer> reachFails = new java.util.HashMap<>();

	private static void fail(Plan.Task t, String why) {
		t.failed = true;
		t.why = why;
		failed.put(t.pos, why);
		log("gave up on {}: {}", t.pos, why);
		current = null;
		walkingTo = null;
	}

	/** The nearest block still to do. */
	private static Plan.Task pick(ClientPlayerEntity p) {
		Vec3d eye = p.getEyePos();
		Plan.Task best = null;
		double bestD = Double.MAX_VALUE;
		// Seams are taken back up last: the grass next to them has to be tilled first, or it would spread over.
		boolean early = false;
		for (Plan.Task t : plan.tasks) if (!t.done && !t.failed && !t.late) early = true;
		for (Plan.Task t : plan.tasks) {
			if (t.done || t.failed || early && t.late) continue;
			double d = eye.squaredDistanceTo(Vec3d.ofCenter(t.pos));
			if (d < bestD) {
				bestD = d;
				best = t;
			}
		}
		return best;
	}

	/** Right-click the top of the block with a tool, then wait for the server to change it. */
	private static void use(MinecraftClient client, ClientPlayerEntity p, ClientWorld w, Plan.Task t, Predicate<ItemStack> tool, String name) {
		if (wait > 0) {
			if (--wait == 0 && ++tries >= 3) {
				if (t.kind == Plan.Kind.TILL && "seam".equals(t.reason)) {
					// The hoe won't turn this path back: break it and put dirt in instead.
					t.kind = Plan.Kind.DIRT;
					tries = stage = 0;
					log("seam at {} won't till: swapping it for dirt", t.pos.toShortString());
					return;
				}
				fail(t, "the server didn't change it");
			}
			return;
		}
		if (!hold(client, p, tool)) {
			stop(client, "Stopped: no " + name + " left (it won't use one with 2 or fewer uses left).");
			return;
		}
		Vec3d hit = Vec3d.ofCenter(t.pos).add(0, 0.5, 0);
		look(p, hit);
		client.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, t.pos, false));
		p.swingHand(Hand.MAIN_HAND);
		wait = 10;
	}

	/** Put the new block into the (now empty) cell. */
	private static void place(MinecraftClient client, ClientPlayerEntity p, ClientWorld w, Plan.Task t) {
		if (wait > 0) {
			if (--wait == 0 && ++tries >= 3) fail(t, "couldn't place " + placeName() + " there");
			return;
		}
		if (!w.getBlockState(t.pos).isAir() && !w.getBlockState(t.pos).isReplaceable()) {
			stage = 0;
			return;
		}
		// By water: plain dirt if there's any (free, and the mycelium spreads onto it), else mycelium.
		Item item = t.kind == Plan.Kind.DIRT && slot(p.getInventory(), s -> s.isOf(Items.DIRT)) >= 0 ? Items.DIRT : placeItem();
		int slot = slot(p.getInventory(), s -> s.isOf(item));
		if (slot < 0) {
			if (boxWith(p.getInventory(), item) >= 0) {
				enter(State.UNPACK);
				resume = t;
				return;
			}
			stop(client, "Stopped: out of " + placeName() + ". " + left() + " left to do.");
			return;
		}
		Direction face = Plan.placeFace(w, t.pos);
		if (face == null) {
			fail(t, "nothing to place against");
			return;
		}
		if (p.getBoundingBox().intersects(new Box(t.pos))) {
			// Fell into the hole: step out and come back.
			walkingTo = null;
			stage = 1;
			reach(client, p, w, t.pos, true);
			return;
		}
		BlockPos against = t.pos.offset(face.getOpposite());
		select(client, p, slot);
		t.countBefore = itemCount(p.getInventory(), item);
		Vec3d hit = Vec3d.ofCenter(against).add(Vec3d.of(face.getVector()).multiply(0.5));
		look(p, hit);
		client.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hit, face, against, false));
		p.swingHand(Hand.MAIN_HAND);
		t.placed = true;
		t.placedMycelium = item == placeItem();
		wait = 10;
	}

	/** Out of work: look at the circle again. */
	private static void lookAgain(MinecraftClient client, ClientPlayerEntity p, ClientWorld w) {
		passes++;
		Plan next = plan.again(w);
		for (Plan.Task t : next.tasks) {
			String why = failed.get(t.pos);
			// Couldn't walk there: the ground around it has changed since, so try again (3 goes in all).
			if (CANT_REACH.equals(why) && reachFails.merge(t.pos, 1, Integer::sum) < 3) {
				failed.remove(t.pos);
				why = null;
			}
			if (why != null) {
				t.failed = true;
				t.why = why;
			}
		}
		plan = next;
		int left = left();
		log("pass {}: {} more to do, {} unloaded columns, coverage {}%", passes, left, next.unloaded.size(), next.coverage());
		if (left > 0 && passes < 50) {
			status = Text.literal("Looked again: " + left + " more");
			return; // still WORK
		}
		if (!next.unloaded.isEmpty() && passes < 50) {
			enter(State.ROAM);
			return;
		}
		if (next.tile()) {
			// A tile of a big area: on to the next part (it comes back to this one once it's had time to spread).
			Areas.finished(client, p, next);
			return;
		}
		if (!GkConfig.INSTANCE.watch) {
			stop(client, "Done: " + summary() + ". It will spread by itself while you're nearby.");
			return;
		}
		chat(p, Text.literal("Work done: " + summary() + ". Now watching it spread (Numpad 3 stops).").formatted(Formatting.GREEN));
		watchTimer = 0;
		atCenter = false;
		enter(State.WATCH);
	}

	static String summary() {
		StringBuilder b = new StringBuilder();
		if (tilled > 0) b.append(tilled).append(" tilled, ");
		if (seeded > 0) b.append(seeded).append(" mycelium seeds, ");
		if (pathed > 0) b.append(pathed).append(" border path, ");
		if (dirted > 0) b.append(dirted).append(" dirt by water, ");
		if (swapped > 0) b.append(swapped).append(" swapped, ");
		if (!failed.isEmpty()) b.append(failed.size()).append(" skipped (/gk skipped), ");
		if (b.isEmpty()) return "nothing needed doing";
		return b.substring(0, b.length() - 2);
	}

	static int left() {
		int n = 0;
		if (plan == null) return 0;
		for (Plan.Task t : plan.tasks) if (!t.done && !t.failed) n++;
		return n;
	}

	/** Part of the circle is in chunks that weren't loaded: walk towards them. */
	private static void roam(MinecraftClient client, ClientPlayerEntity p, ClientWorld w) {
		BlockPos far = plan.unloaded.stream().min(Comparator.comparingDouble(c -> c.getSquaredDistance(p.getBlockPos()))).orElse(null);
		if (far == null || w.getChunkManager().isChunkLoaded(far.getX() >> 4, far.getZ() >> 4)) {
			walker.stop(client);
			enter(State.WORK);
			return;
		}
		status = Text.literal("Walking to load " + plan.unloaded.size() + " more columns");
		if (walkingTo == null || walker.arrived() && !walker.moving()) {
			// Somewhere in the circle, as near that column as it can get.
			BlockPos target = far;
			Predicate<BlockPos> goal = c -> Math.hypot(c.getX() - target.getX(), c.getZ() - target.getZ()) < 24 && plan.inside(c.getX(), c.getZ());
			if (++reachTries > 4 || !walker.plan(w, p, goal, bounds(p))) {
				stop(client, "Stopped: part of the circle isn't loaded and it can't walk there. Try a smaller radius or a higher render distance.");
				return;
			}
			walkingTo = far;
		}
		if (!walker.tick(client)) walkingTo = null;
	}

	/** Stay near the middle, look again every 30 seconds, fix any grass that's come back, until it's all mycelium. */
	private static void watch(MinecraftClient client, ClientPlayerEntity p, ClientWorld w) {
		if (!atCenter) {
			BlockPos c = plan.center;
			if (Math.hypot(p.getX() - c.getX() - 0.5, p.getZ() - c.getZ() - 0.5) < 3.5) {
				atCenter = true;
				walker.stop(client);
			} else {
				if (walkingTo == null || walker.arrived() && !walker.moving()) {
					Predicate<BlockPos> goal = q -> Math.hypot(q.getX() - c.getX(), q.getZ() - c.getZ()) < 3;
					if (++reachTries > 4 || !walker.plan(w, p, goal, bounds(p))) atCenter = true;
					else walkingTo = c;
				}
				if (walkingTo != null && !walker.tick(client)) walkingTo = null;
			}
		}
		if (watchTimer-- > 0) return;
		watchTimer = 600;
		Plan next = plan.again(w);
		for (Plan.Task t : next.tasks) {
			if (CANT_REACH.equals(failed.get(t.pos)) && reachFails.merge(t.pos, 1, Integer::sum) < 3) failed.remove(t.pos);
			if (failed.containsKey(t.pos)) t.failed = true;
		}
		plan = next;
		// Farmland that won't dry out (wet from somewhere it can't see): swap it for dirt after two minutes.
		java.util.Map<BlockPos, Integer> still = new java.util.HashMap<>();
		for (BlockPos f : next.farmlandAt) {
			int n = farmlandSeen.getOrDefault(f, 0) + 1;
			still.put(f, n);
			if (n >= 4 && !failed.containsKey(f)) {
				Plan.Task t = new Plan.Task(f, Plan.Kind.DIRT);
				t.reason = "farmland";
				next.add(t);
			}
		}
		farmlandSeen.clear();
		farmlandSeen.putAll(still);
		int todo = left();
		long time = w.getTimeOfDay() % 24000;
		boolean night = time > 12800 && time < 23200;
		status = Text.literal("Watching · " + next.coverage() + "% mycelium" + (next.farmland > 0 ? " · " + next.farmland + " farmland still drying" : "")
				+ (night ? " · night: no spreading in the dark" : ""));
		if (todo > 0) {
			chat(p, Text.literal(todo + " to fix (grass came back or a seed is needed): on it.").formatted(Formatting.YELLOW));
			passes = 0;
			enter(State.WORK);
			return;
		}
		if (next.farmland == 0 && next.mycelium >= next.nodes - next.stranded) {
			if (next.tile()) {
				Areas.finished(client, p, next);
				return;
			}
			stop(client, "Done: the circle is all mycelium" + (next.blocked.isEmpty() ? "" : " (apart from " + next.blocked.size() + (next.blocked.size() == 1 ? " block" : " blocks") + " it can't get at: /gk blocked)") + ".");
		}
	}

	// ---------------------------------------------------------------- the shulker box

	/**
	 * Out of mycelium, with a shulker box of it: 0 find a spot, 1 put the box down, 2 open it, 3 take the mycelium,
	 * 4 break the box, 5 pick it up.
	 */
	private static void unpack(MinecraftClient client, ClientPlayerEntity p, ClientWorld w) {
		stageTicks++;
		if (stageTicks > 600 && stage < 5) {
			stop(client, "Stopped: unpacking the shulker box got stuck (step " + stage + ")" + (box != null ? " at " + box.toShortString() : "") + ".");
			return;
		}
		PlayerInventory inv = p.getInventory();
		Item item = placeItem();
		switch (stage) {
			case 0 -> {
				status = Text.literal("Unpacking a shulker box of " + placeName());
				box = boxSpot(p, w);
				if (box == null) {
					stop(client, "Stopped: out of " + placeName() + " and no flat spot within reach to put the shulker box down.");
					return;
				}
				boxesBefore = countBoxes(inv);
				next();
			}
			case 1 -> {
				if (w.getBlockState(box).getBlock() instanceof ShulkerBoxBlock) {
					next();
					return;
				}
				if (wait > 0) {
					if (--wait == 0 && ++tries >= 3) stop(client, "Stopped: couldn't put the shulker box down.");
					return;
				}
				int slot = boxWith(inv, item);
				if (slot < 0) {
					enter(State.WORK);
					return;
				}
				select(client, p, slot);
				BlockPos below = box.down();
				Vec3d hit = Vec3d.ofCenter(below).add(0, 0.5, 0);
				look(p, hit);
				client.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, below, false));
				p.swingHand(Hand.MAIN_HAND);
				wait = 10;
			}
			case 2 -> {
				if (p.currentScreenHandler != p.playerScreenHandler && !containerSlots(p.currentScreenHandler, p).isEmpty()) {
					next();
					return;
				}
				if (wait > 0) {
					if (--wait == 0 && ++tries >= 4) {
						stop(client, "Stopped: couldn't open the shulker box at " + box.toShortString() + " (it's still there).");
					}
					return;
				}
				client.options.sneakKey.setPressed(false);
				if (p.isSneaking()) return;
				Vec3d hit = Vec3d.ofCenter(box).add(0, 0.45, 0);
				look(p, hit);
				client.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, box, false));
				p.swingHand(Hand.MAIN_HAND);
				wait = 20;
			}
			case 3 -> {
				ScreenHandler h = p.currentScreenHandler;
				if (h == p.playerScreenHandler) {
					next();
					return;
				}
				// Leave a slot free: the box has to come back into the inventory.
				int free = 0;
				for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) if (inv.getStack(i).isEmpty()) free++;
				// The box's contents arrive a moment after the screen opens.
				if (stageTicks >= 6 && stageTicks % 2 == 0) {
					for (Slot s : containerSlots(h, p)) {
						if (!s.getStack().isOf(item)) continue;
						if (free <= 1 && !topUp(inv, s.getStack())) break;
						int n = s.getStack().getCount();
						client.interactionManager.clickSlot(h.syncId, h.slots.indexOf(s), 0, SlotActionType.QUICK_MOVE, p);
						unpacked += n - s.getStack().getCount();
						return;
					}
					p.closeHandledScreen();
					next();
				}
			}
			case 4 -> {
				if (p.currentScreenHandler != p.playerScreenHandler) p.closeHandledScreen();
				if (!(w.getBlockState(box).getBlock() instanceof ShulkerBoxBlock) || breakBlock(client, p, w, box)) next();
			}
			case 5 -> {
				if (countBoxes(inv) >= boxesBefore) {
					walker.stop(client);
					chat(p, Text.literal("Unpacked a shulker box of " + placeName() + " and picked the box back up.").formatted(Formatting.GRAY));
					Plan.Task back = resume;
					resume = null;
					enter(State.WORK);
					current = back;
					stage = 1;
					return;
				}
				if (stageTicks > 400) {
					stop(client, "Stopped: couldn't pick the shulker box back up. It's on the ground near " + box.toShortString() + ".");
					return;
				}
				// No room for it? Throw out a stack of dirt (the seeds' leftovers).
				if (firstEmpty(inv) < 0) {
					int dirt = slot(inv, s -> s.isOf(Items.DIRT));
					if (dirt >= 0) client.interactionManager.clickSlot(p.playerScreenHandler.syncId, invToScreenSlot(dirt), 1, SlotActionType.THROW, p);
				}
				ItemEntity drop = w.getEntitiesByClass(ItemEntity.class, new Box(box).expand(4), e -> isBox(e.getStack())).stream()
						.min(Comparator.comparingDouble(e -> e.squaredDistanceTo(p))).orElse(null);
				if (stageTicks % 40 == 1) log("pick up: drop {} me {} walking {}", drop == null ? "none" : drop.getEntityPos(), p.getEntityPos(), walkingTo);
				if (drop != null) walkOnto(client, p, w, drop);
			}
			default -> {}
		}
	}

	private static void next() {
		log("unpack step {} done after {} ticks (box {})", stage, stageTicks, box);
		stage++;
		stageTicks = 0;
		wait = tries = 0;
		walkingTo = null;
	}

	/** A flat spot in reach for the box: air with air above, on a full block that isn't still to be worked on. */
	private static BlockPos boxSpot(ClientPlayerEntity p, ClientWorld w) {
		Set<BlockPos> todo = new HashSet<>();
		for (Plan.Task t : plan.tasks) if (!t.done && !t.failed) todo.add(t.pos);
		BlockPos feet = Walker.feet(p);
		double range = p.getBlockInteractionRange() - 0.6;
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (BlockPos q : BlockPos.iterate(feet.add(-3, -2, -3), feet.add(3, 2, 3))) {
			BlockPos c = q.toImmutable();
			if (!w.getBlockState(c).isAir() || !w.getBlockState(c.up()).isAir() || todo.contains(c.down())) continue;
			if (!w.getBlockState(c.down()).isSideSolidFullSquare(w, c.down(), Direction.UP)) continue;
			if (p.getBoundingBox().expand(0.1).intersects(new Box(c))) continue;
			double d = p.getEyePos().squaredDistanceTo(Vec3d.ofCenter(c));
			if (d > range * range) continue;
			// Prefer a spot not right under its feet, low down.
			if (d < bestD) {
				bestD = d;
				best = c;
			}
		}
		return best;
	}

	private static boolean topUp(PlayerInventory inv, ItemStack s) {
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
			ItemStack t = inv.getStack(i);
			if (ItemStack.areItemsAndComponentsEqual(t, s) && t.getCount() < t.getMaxCount()) return true;
		}
		return false;
	}

	private static int firstEmpty(PlayerInventory inv) {
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) if (inv.getStack(i).isEmpty()) return i;
		return -1;
	}

	private static void walkOnto(MinecraftClient client, ClientPlayerEntity p, ClientWorld w, ItemEntity drop) {
		BlockPos cell = drop.getBlockPos();
		if (p.getBoundingBox().expand(1, 0.5, 1).intersects(drop.getBoundingBox())) {
			walker.stop(client);
			return;
		}
		if (walkingTo == null || !walkingTo.equals(cell) || walker.arrived()) {
			// Into the drop's own cell: from the next cell over it can be just out of pickup range.
			Predicate<BlockPos> into = c -> c.getX() == cell.getX() && c.getZ() == cell.getZ() && Math.abs(c.getY() - cell.getY()) <= 1;
			Predicate<BlockPos> onto = c -> Math.abs(c.getX() - cell.getX()) + Math.abs(c.getZ() - cell.getZ()) <= 1 && Math.abs(c.getY() - cell.getY()) <= 1;
			if (!walker.plan(w, p, into, bounds(p)) && !walker.plan(w, p, onto, bounds(p))) {
				walkingTo = null;
				return;
			}
			walkingTo = cell;
		}
		if (!walker.tick(client)) walkingTo = null;
	}

	// ---------------------------------------------------------------- moving and hands

	/**
	 * Walks until {@code pos} is in reach. True when it is. {@code notOnIt}: not standing on top of it (it's about to
	 * be broken).
	 */
	private static boolean reach(MinecraftClient client, ClientPlayerEntity p, ClientWorld w, BlockPos pos, boolean notOnIt) {
		double range = p.getBlockInteractionRange() - 0.4;
		Box column = new Box(pos.getX(), pos.getY() + 1, pos.getZ(), pos.getX() + 1, pos.getY() + 3, pos.getZ() + 1);
		boolean onIt = notOnIt && p.getBoundingBox().intersects(column);
		if (!onIt && p.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos).add(0, 0.5, 0)) <= range * range) {
			if (walkingTo != null) {
				walker.stop(client);
				walkingTo = null;
			}
			return true;
		}
		if (walkingTo == null || !walkingTo.equals(pos) || walker.arrived() && !walker.moving()) {
			// Well inside reach: it stops up to 0.3 off the middle of the cell it walks to.
			double want = range - 1.1;
			Vec3d top = Vec3d.ofCenter(pos).add(0, 0.5, 0);
			Predicate<BlockPos> goal = c -> Vec3d.ofBottomCenter(c).add(0, 1.62, 0).squaredDistanceTo(top) <= want * want
					&& !(notOnIt && c.getX() == pos.getX() && c.getZ() == pos.getZ());
			if (++reachTries > 4 || !walker.plan(w, p, goal, bounds(p))) {
				BlockPos f = Walker.feet(p);
				log("no way to {} (try {}): me {} standable {} fluid {} below {} expanded {}", pos.toShortString(), reachTries, p.getEntityPos(),
						Walker.standable(w, f), w.getFluidState(f), w.getBlockState(f.down()).getBlock(), walker.expanded);
				walker.stop(client);
				fail(current, CANT_REACH);
				return false;
			}
			walkingTo = pos;
		}
		status = Text.literal("Walking");
		if (!walker.tick(client)) walkingTo = null;
		return false;
	}

	/** The area the walker may use: the circle and a bit, from below its lowest block to above its highest. */
	private static int[] bounds(ClientPlayerEntity p) {
		BlockPos c = plan.center;
		int[] b = plan.region.bounds();
		int lo = Math.min(plan.minY == Integer.MAX_VALUE ? c.getY() : plan.minY, p.getBlockY()) - 12;
		int hi = Math.max(plan.maxY == Integer.MIN_VALUE ? c.getY() : plan.maxY, p.getBlockY()) + 12;
		// The region and a bit, and wherever the player is (they can start outside it).
		return new int[] {Math.min(b[0], p.getBlockX()) - 10, lo, Math.min(b[1], p.getBlockZ()) - 10, Math.max(b[2], p.getBlockX()) + 10, hi,
				Math.max(b[3], p.getBlockZ()) + 10};
	}

	/** Breaks a block with whatever in the inventory is fastest on it. True once it's gone. */
	static boolean breakBlock(MinecraftClient client, ClientPlayerEntity p, ClientWorld w, BlockPos pos) {
		BlockState s = w.getBlockState(pos);
		if (s.isAir()) {
			breaking = null;
			return true;
		}
		Direction face = Direction.getFacing(p.getEyePos().subtract(Vec3d.ofCenter(pos)));
		look(p, Vec3d.ofCenter(pos).add(Vec3d.of(face.getVector()).multiply(0.5)));
		holdFastest(client, p, s);
		if (!pos.equals(breaking)) {
			client.interactionManager.attackBlock(pos, face);
			breaking = pos;
			breakTicks = 0;
		} else {
			if (++breakTicks > 300) {
				breaking = null;
				client.interactionManager.cancelBlockBreaking();
				// The shelter has its own way out of a block that won't break.
				if (Shelter.active()) return false;
				if (current != null) fail(current, "couldn't break " + s.getBlock().getName().getString());
				else stop(client, "Stopped: couldn't break the block at " + pos.toShortString() + ".");
				return false;
			}
			client.interactionManager.updateBlockBreakingProgress(pos, face);
		}
		p.swingHand(Hand.MAIN_HAND);
		if (w.getBlockState(pos).isAir()) {
			breaking = null;
			return true;
		}
		return false;
	}

	private static void holdFastest(MinecraftClient client, ClientPlayerEntity p, BlockState s) {
		PlayerInventory inv = p.getInventory();
		int best = -1;
		float speed = 1;
		// Grass to be swapped for dirt: silk touch would drop grass, not the dirt the next swap needs.
		boolean wantDrop = s.isOf(Blocks.GRASS_BLOCK) && current != null && current.kind == Plan.Kind.DIRT;
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
			ItemStack st = inv.getStack(i);
			if (st.isEmpty() || !safe(st)) continue;
			if (wantDrop && silk(st)) continue;
			float f = st.getMiningSpeedMultiplier(s);
			if (f > speed) {
				speed = f;
				best = i;
			}
		}
		if (best >= 0 && inv.getStack(best) != p.getMainHandStack()) select(client, p, best);
	}

	static boolean silk(ItemStack s) {
		for (var e : s.getEnchantments().getEnchantments()) if (e.matchesKey(net.minecraft.enchantment.Enchantments.SILK_TOUCH)) return true;
		return false;
	}

	/** Never wears a tool down to nothing: 2 uses left and it's put aside. */
	private static boolean safe(ItemStack s) {
		return !s.isDamageable() || s.getMaxDamage() - s.getDamage() > 2;
	}

	/** Uses left in all the safe tools matching {@code what} (Unbreaking makes them last longer than this). */
	static int uses(PlayerInventory inv, Predicate<ItemStack> what) {
		int n = 0;
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
			ItemStack s = inv.getStack(i);
			if (s.isEmpty() || !what.test(s) || !safe(s)) continue;
			n += s.isDamageable() ? s.getMaxDamage() - s.getDamage() - 2 : 100_000;
		}
		return n;
	}

	/** A warning if the tools won't last the job, or null. */
	static String toolWarning(PlayerInventory inv, Plan p) {
		if (p.swap) return null;
		StringBuilder b = new StringBuilder();
		int till = p.count(Plan.Kind.TILL), hoe = uses(inv, s -> s.isIn(ItemTags.HOES));
		if (hoe > 0 && hoe < till) b.append("Hoe: ").append(hoe).append(" uses for ").append(till).append(" grass. ");
		int dig = p.count(Plan.Kind.PATH) + p.seeds() + p.dirtSwaps(), shovel = uses(inv, s -> s.isIn(ItemTags.SHOVELS));
		if (shovel > 0 && shovel < dig) b.append("Shovel: ").append(shovel).append(" uses for ").append(dig).append(". ");
		if (b.isEmpty()) return null;
		return b + "Bring another, or Mending/Unbreaking: it stops before one breaks.";
	}

	/** The best (most uses left) safe stack matching {@code what}, or -1. */
	static int best(PlayerInventory inv, Predicate<ItemStack> what) {
		int best = -1;
		int left = -1;
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
			ItemStack s = inv.getStack(i);
			if (s.isEmpty() || !what.test(s) || !safe(s)) continue;
			int l = s.isDamageable() ? s.getMaxDamage() - s.getDamage() : Integer.MAX_VALUE;
			if (l > left) {
				left = l;
				best = i;
			}
		}
		return best;
	}

	private static boolean hold(MinecraftClient client, ClientPlayerEntity p, Predicate<ItemStack> what) {
		if (what.test(p.getMainHandStack()) && safe(p.getMainHandStack())) return true;
		int i = best(p.getInventory(), what);
		if (i < 0) return false;
		select(client, p, i);
		return what.test(p.getMainHandStack());
	}

	/** Puts inventory slot {@code slot} in hand, swapping it into the hotbar if need be. */
	static void select(MinecraftClient client, ClientPlayerEntity p, int slot) {
		PlayerInventory inv = p.getInventory();
		if (slot < 0) return;
		if (slot < PlayerInventory.getHotbarSize()) {
			inv.setSelectedSlot(slot);
			return;
		}
		client.interactionManager.clickSlot(p.playerScreenHandler.syncId, slot, inv.getSelectedSlot(), SlotActionType.SWAP, p);
	}

	private static int invToScreenSlot(int index) {
		return index < 9 ? 36 + index : index;
	}

	static void look(ClientPlayerEntity p, Vec3d at) {
		Vec3d d = at.subtract(p.getEyePos());
		double flat = Math.sqrt(d.x * d.x + d.z * d.z);
		p.setYaw((float) (MathHelper.atan2(d.z, d.x) * 180 / Math.PI) - 90);
		p.setPitch((float) -(MathHelper.atan2(d.y, flat) * 180 / Math.PI));
	}

	// ---------------------------------------------------------------- items

	static Item placeItem() {
		GkConfig c = GkConfig.INSTANCE;
		if (c.swapMode()) {
			Block b = Plan.block(c.swapTo);
			if (b != null) return b.asItem();
		}
		return Items.MYCELIUM;
	}

	static String placeName() {
		return placeItem().getName().getString().toLowerCase(java.util.Locale.ROOT);
	}

	/** In the inventory, counting what's inside shulker boxes there. */
	static int itemCount(PlayerInventory inv, Item item) {
		int n = 0;
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
			ItemStack s = inv.getStack(i);
			if (s.isOf(item)) n += s.getCount();
			n += inBox(s, item);
		}
		return n;
	}

	static int inBox(ItemStack s, Item item) {
		if (!isBox(s)) return 0;
		ContainerComponent c = s.get(DataComponentTypes.CONTAINER);
		if (c == null) return 0;
		return c.streamNonEmpty().filter(x -> x.isOf(item)).mapToInt(ItemStack::getCount).sum();
	}

	static boolean isBox(ItemStack s) {
		return !s.isEmpty() && Block.getBlockFromItem(s.getItem()) instanceof ShulkerBoxBlock;
	}

	private static int boxWith(PlayerInventory inv, Item item) {
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) if (inBox(inv.getStack(i), item) > 0) return i;
		return -1;
	}

	private static int countBoxes(PlayerInventory inv) {
		int n = 0;
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) if (isBox(inv.getStack(i))) n++;
		return n;
	}

	static int slot(PlayerInventory inv, Predicate<ItemStack> what) {
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) if (!inv.getStack(i).isEmpty() && what.test(inv.getStack(i))) return i;
		return -1;
	}

	private static List<Slot> containerSlots(ScreenHandler h, ClientPlayerEntity p) {
		return h.slots.stream().filter(s -> s.inventory != p.getInventory()).toList();
	}

	static void chat(ClientPlayerEntity p, Text t) {
		p.sendMessage(Text.literal("[Groundskeeper] ").formatted(Formatting.DARK_GREEN).append(t), false);
	}

	static void log(String msg, Object... args) {
		if (Boolean.getBoolean("groundskeeper.debug")) Groundskeeper.LOGGER.info("[job] " + msg, args);
	}
}
