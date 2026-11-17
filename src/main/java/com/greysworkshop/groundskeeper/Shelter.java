package com.greysworkshop.groundskeeper;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FallingBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Keeps the player alive through nights and trouble while a job runs, without a bed.
 * <ul>
 * <li><b>Night</b> (or a thunderstorm): by default it digs in where it is (see {@link BunkerSpot}: a hole three deep
 * with the ground block put back as a roof). With a safety zone set and close enough, it walks there instead. In the
 * morning it climbs out, filling the hole as it goes, and carries on.</li>
 * <li><b>Danger</b> any time: a warden, a crowd of monsters, or a player you'd rather not meet (your Naughty or Look
 * Into list in Naughty or Nice, or anyone you don't know, as set) and it hides in a bunker until they've gone. A lit
 * creeper close by: it runs.</li>
 * </ul>
 * Mycelium doesn't spread at night anyway, so hiding costs nothing but the tilling it could have done.
 */
final class Shelter {
	enum Mode {
		NONE,
		/** Walking to the safety zone. */
		TO_ZONE,
		AT_ZONE,
		/** Getting onto the spot to dig. */
		WALK,
		DIG,
		ROOF,
		INSIDE,
		/** Nowhere safe to dig: waiting it out where it is, leaving the fighting to you or your other mods. */
		EXPOSED,
		CLIMB
	}

	static Mode mode = Mode.NONE;
	/** Why it's sheltering, for the HUD. */
	static String why = "";
	static Threat.Assessment threat = Threat.Assessment.CLEAR;
	private static BlockPos ground;
	private static int stageTicks, tries, quiet, ticks, dodge;
	private static boolean roofed;
	private static Vec3d dodgeFrom;

	private Shelter() {}

	static boolean active() {
		return mode != Mode.NONE;
	}

	/** The block the shelter is dug under (its roof), or null. */
	static BlockPos ground() {
		return ground;
	}

	static void reset() {
		mode = Mode.NONE;
		ground = null;
		threat = Threat.Assessment.CLEAR;
		dodge = 0;
	}

	/** Every job tick. True while it has the controls (the job waits). */
	static boolean tick(MinecraftClient client, ClientPlayerEntity p, ClientWorld w) {
		GkConfig cfg = GkConfig.INSTANCE;
		ticks++;
		if (ticks % 10 == 0) threat = assess(p, w, cfg.players);
		if (threat.hide()) quiet = 0;
		else quiet++;
		long time = w.getTimeOfDay();
		boolean thunder = w.isThundering();
		boolean days = Areas.dimension(w).equals("minecraft:overworld");
		// A lit creeper right there: run first, whatever else is going on (not from inside a sealed bunker).
		if (threat.dodge() && mode != Mode.INSIDE && mode != Mode.CLIMB && threat.worst() != null) {
			dodge = 30;
		}
		if (dodge > 0) {
			dodge--;
			runFromCreeper(client, p, w);
			if (dodge == 0) client.options.forwardKey.setPressed(false);
			return true;
		}
		stageTicks++;
		switch (mode) {
			case NONE -> {
				int travel = zoneUsable(p, w) ? (int) (Math.hypot(cfg.zoneX - p.getX(), cfg.zoneZ - p.getZ()) / 4.0 * 20) + 200 : 0;
				boolean night = days && cfg.night != GkConfig.Night.WORK && Daylight.shelterNow(time, thunder, cfg.night == GkConfig.Night.ZONE ? travel : 0);
				if (night && cfg.night == GkConfig.Night.STOP) {
					Job.stop(client, "Stopped for the night (/gk night bunker to dig in and carry on in the morning).");
					return true;
				}
				if (!night && !threat.hide()) return false;
				Job.pauseForShelter(client);
				why = threat.hide() ? threat.reason() : thunder ? "thunderstorm" : "night";
				if (!threat.hide() && cfg.night == GkConfig.Night.ZONE && zoneUsable(p, w)) {
					go(Mode.TO_ZONE);
					Job.chat(p, Text.literal("Night's coming: off to the safety zone.").formatted(Formatting.GOLD));
				} else {
					find(client, p, w);
					Job.chat(p, Text.literal((threat.hide() ? "Hiding: " + threat.reason() : thunder ? "Thunderstorm" : "Night's coming") + ": digging in.").formatted(Formatting.GOLD));
				}
				return true;
			}
			case TO_ZONE -> {
				Job.status = Text.literal("Going to the safety zone (" + why + ")");
				// Trouble on the way and still far off: dig in here instead.
				if (threat.hide() && Math.hypot(cfg.zoneX - p.getX(), cfg.zoneZ - p.getZ()) > 24) {
					Travel.cancel(client, Job.walker);
					find(client, p, w);
					return true;
				}
				switch (Travel.tick(client, p, w, Job.walker, cfg.zoneX, cfg.zoneZ, 1.5)) {
					case ARRIVED -> {
						go(Mode.AT_ZONE);
						Job.chat(p, Text.literal("At the safety zone. It carries on in the morning.").formatted(Formatting.GRAY));
					}
					case FAILED -> {
						Job.chat(p, Text.literal("Couldn't get to the safety zone: digging in here.").formatted(Formatting.YELLOW));
						find(client, p, w);
					}
					default -> {}
				}
			}
			case AT_ZONE, EXPOSED -> {
				Job.status = Text.literal((mode == Mode.AT_ZONE ? "At the safety zone" : "Waiting it out (nowhere safe to dig)") + " · " + why + until(time, thunder, days));
				if (leaveOk(time, thunder, days)) done(client, p);
			}
			case WALK -> {
				Job.status = Text.literal("Getting to a spot to dig in (" + why + ")");
				if (onSpot(p)) {
					Job.walker.clear(client);
					go(Mode.DIG);
					return true;
				}
				if (!Job.walker.moving() || Job.walker.arrived()) {
					BlockPos feet = ground.up();
					Predicate<BlockPos> goal = c -> c.equals(feet);
					if (++tries > 4 || !Job.walker.plan(w, p, goal, box(p, ground, 16))) {
						find(client, p, w);
						return true;
					}
				}
				if (!Job.walker.tick(client)) Job.walker.clear(client);
			}
			case DIG -> {
				Job.status = Text.literal("Digging in (" + why + ")");
				centre(p);
				if (stageTicks > 600 || !inColumn(p, 0.8)) {
					// Slid off, or it won't break: try somewhere else.
					Job.cancelBreaking(client);
					find(client, p, w);
					return true;
				}
				BlockPos target = null;
				for (int d = 0; d <= 2; d++) {
					BlockPos c = ground.down(d);
					if (!w.getBlockState(c).isAir()) {
						target = c;
						break;
					}
				}
				if (target != null) {
					Job.breakBlock(client, p, w, target);
					return true;
				}
				// All dug: wait to land on the floor.
				if (p.isOnGround() && p.getY() < ground.getY() - 1.5) go(Mode.ROOF);
			}
			case ROOF -> {
				Job.status = Text.literal("Putting the roof on");
				centre(p);
				if (!w.getBlockState(ground).isAir() && !w.getBlockState(ground).isReplaceable()) {
					roofed = true;
					go(Mode.INSIDE);
					Job.chat(p, Text.literal("Dug in for the " + (why.equals("night") ? "night" : "moment") + " at " + ground.toShortString() + ".").formatted(Formatting.GRAY));
					return true;
				}
				if (stageTicks % 10 != 1) return true;
				if (++tries > 4 || !place(client, p, w, ground)) {
					roofed = false;
					go(Mode.INSIDE);
					Job.chat(p, Text.literal("No roof on the hole (no blocks to put back, or it wouldn't place): only mobs from the side are kept out.").formatted(Formatting.YELLOW));
				}
			}
			case INSIDE -> {
				Job.status = Text.literal("Sheltering · " + why + until(time, thunder, days));
				centre(p);
				// Something took the roof (an enderman can): put it back.
				if (roofed && stageTicks % 40 == 0 && w.getBlockState(ground).isAir()) go(Mode.ROOF);
				if (leaveOk(time, thunder, days)) go(Mode.CLIMB);
			}
			case CLIMB -> {
				Job.status = Text.literal("Climbing out");
				if (stageTicks > 1200) {
					Job.stop(client, "Stopped: couldn't climb out of the shelter at " + ground.toShortString() + ". Dig yourself out and press Numpad 1.");
					return true;
				}
				if (p.getY() >= ground.getY() + 0.9 && p.isOnGround()) {
					done(client, p);
					return true;
				}
				if (!w.getBlockState(ground).isAir()) {
					Job.breakBlock(client, p, w, ground);
					return true;
				}
				Job.walker.placeUnder = cell -> place(client, p, w, cell);
				if (!Job.walker.moving() || Job.walker.arrived()) {
					Predicate<BlockPos> goal = c -> c.getX() == ground.getX() && c.getZ() == ground.getZ() && c.getY() >= ground.getY() + 1;
					Predicate<BlockPos> tower = c -> c.getX() == ground.getX() && c.getZ() == ground.getZ();
					if (!Job.walker.plan(w, p, goal, box(p, ground, 3), tower, null)) {
						if (++tries > 10) {
							Job.stop(client, "Stopped: no blocks to climb out of the shelter with at " + ground.toShortString() + ".");
							return true;
						}
					}
				}
				if (!Job.walker.tick(client)) Job.walker.clear(client);
			}
		}
		return true;
	}

	private static void go(Mode m) {
		Job.log("shelter: {} -> {}", mode, m);
		mode = m;
		stageTicks = tries = 0;
	}

	private static void done(MinecraftClient client, ClientPlayerEntity p) {
		Job.walker.placeUnder = c -> false;
		Job.walker.clear(client);
		boolean wasDay = !why.equals("night") && !why.equals("thunderstorm");
		go(Mode.NONE);
		ground = null;
		Job.chat(p, Text.literal(wasDay ? "All clear: back to work." : "Morning: back to work.").formatted(Formatting.GREEN));
		Job.resumeAfterShelter();
	}

	/** Time to come out: day (or no nights here), nothing to hide from for 10 seconds. */
	private static boolean leaveOk(long time, boolean thunder, boolean days) {
		GkConfig cfg = GkConfig.INSTANCE;
		boolean day = !days || cfg.night == GkConfig.Night.WORK || Daylight.canLeave(time, thunder);
		return day && quiet >= 200;
	}

	private static String until(long time, boolean thunder, boolean days) {
		if (!days || !Daylight.dangerous(time, thunder) && quiet < 200) return "";
		if (thunder) return " · until the storm passes";
		int t = Daylight.tod(time);
		int left = (t < Daylight.SAFE_OUT ? Daylight.SAFE_OUT - t : 24_000 - t + Daylight.SAFE_OUT) / 20;
		return " · out in about " + (left >= 60 ? left / 60 + " min" : left + " s");
	}

	// ---------------------------------------------------------------- the bunker

	private static void find(MinecraftClient client, ClientPlayerEntity p, ClientWorld w) {
		Job.walker.clear(client);
		boolean pick = Job.best(p.getInventory(), s -> s.isIn(ItemTags.PICKAXES)) >= 0;
		BunkerSpot.Probe probe = probe(w, pick);
		BlockPos feet = Walker.feet(p);
		int[] spot = BunkerSpot.find(probe, feet.getX(), feet.getY(), feet.getZ(), 4);
		if (spot == null) spot = BunkerSpot.find(probe, feet.getX(), feet.getY(), feet.getZ(), 12);
		if (spot == null || roofBlocks(p.getInventory()) == 0 && !willDrop(w, spot)) {
			go(Mode.EXPOSED);
			Job.chat(p, Text.literal("Nowhere safe to dig in here (water, caves, sand or hard rock all round): waiting it out where it stands.").formatted(Formatting.YELLOW));
			return;
		}
		ground = new BlockPos(spot[0], spot[1], spot[2]);
		go(onSpot(p) ? Mode.DIG : Mode.WALK);
	}

	/** The dug blocks will give it something to put back (dirt, cobblestone): it doesn't need to have any first. */
	private static boolean willDrop(ClientWorld w, int[] spot) {
		BlockState s = w.getBlockState(new BlockPos(spot[0], spot[1], spot[2]));
		return s.isIn(BlockTags.SHOVEL_MINEABLE) || s.isIn(BlockTags.BASE_STONE_OVERWORLD);
	}

	private static boolean onSpot(ClientPlayerEntity p) {
		return ground != null && inColumn(p, 0.35) && Math.abs(p.getY() - (ground.getY() + 1)) < 0.6;
	}

	private static boolean inColumn(ClientPlayerEntity p, double slack) {
		return Math.abs(p.getX() - (ground.getX() + 0.5)) <= slack && Math.abs(p.getZ() - (ground.getZ() + 0.5)) <= slack;
	}

	/** Nudges the player to the middle of the hole's column. */
	private static void centre(ClientPlayerEntity p) {
		double dx = ground.getX() + 0.5 - p.getX(), dz = ground.getZ() + 0.5 - p.getZ();
		Vec3d v = p.getVelocity();
		p.setVelocity(dx * 0.3, v.y, dz * 0.3);
	}

	private static int[] box(ClientPlayerEntity p, BlockPos g, int r) {
		return new int[] {Math.min(p.getBlockX(), g.getX()) - r, Math.min(p.getBlockY(), g.getY()) - 6, Math.min(p.getBlockZ(), g.getZ()) - r,
				Math.max(p.getBlockX(), g.getX()) + r, Math.max(p.getBlockY(), g.getY()) + 6, Math.max(p.getBlockZ(), g.getZ()) + r};
	}

	private static BunkerSpot.Probe probe(ClientWorld w, boolean pickaxe) {
		BlockPos.Mutable m = new BlockPos.Mutable();
		return new BunkerSpot.Probe() {
			@Override
			public boolean diggable(int x, int y, int z) {
				BlockPos pos = m.set(x, y, z);
				BlockState s = w.getBlockState(pos);
				if (s.isAir() || !s.getFluidState().isEmpty() || s.hasBlockEntity() || s.getBlock() instanceof FallingBlock) return false;
				float hardness = s.getHardness(w, pos);
				if (hardness < 0 || hardness > 3.5f) return false;
				return s.isIn(BlockTags.SHOVEL_MINEABLE) || pickaxe && s.isIn(BlockTags.PICKAXE_MINEABLE) || hardness <= 0.6f;
			}

			@Override
			public boolean wall(int x, int y, int z) {
				BlockPos pos = m.set(x, y, z);
				BlockState s = w.getBlockState(pos);
				if (!s.getFluidState().isEmpty()) return false;
				for (Direction d : Direction.Type.HORIZONTAL) if (!s.isSideSolidFullSquare(w, pos, d)) return false;
				return true;
			}

			@Override
			public boolean floor(int x, int y, int z) {
				BlockPos pos = m.set(x, y, z);
				BlockState s = w.getBlockState(pos);
				if (!s.getFluidState().isEmpty() || s.isOf(Blocks.MAGMA_BLOCK) || s.isIn(BlockTags.CAMPFIRES) || s.isOf(Blocks.POWDER_SNOW)) return false;
				return s.isSideSolidFullSquare(w, pos, Direction.UP);
			}

			@Override
			public boolean fluid(int x, int y, int z) {
				return !w.getFluidState(m.set(x, y, z)).isEmpty();
			}

			@Override
			public boolean standable(int x, int y, int z) {
				return Walker.standable(w, new BlockPos(x, y, z));
			}
		};
	}

	/** Blocks fit to put back as a roof or climb out on, cheapest first. Never mycelium (that's for the job). */
	private static final List<Item> ROOF = List.of(Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.DIRT, Items.NETHERRACK, Items.STONE, Items.DEEPSLATE,
			Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.TUFF, Items.BLACKSTONE, Items.COARSE_DIRT, Items.ROOTED_DIRT, Items.GRASS_BLOCK,
			Items.END_STONE, Items.CALCITE, Items.MUD_BRICKS, Items.STONE_BRICKS);

	static int roofBlocks(PlayerInventory inv) {
		int n = 0;
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) if (ROOF.contains(inv.getStack(i).getItem())) n += inv.getStack(i).getCount();
		return n;
	}

	private static int roofSlot(PlayerInventory inv) {
		for (Item item : ROOF) {
			int s = Job.slot(inv, st -> st.isOf(item));
			if (s >= 0) return s;
		}
		return -1;
	}

	/** Puts a block into {@code cell}: against the block under it if that's solid, else against a side. */
	static boolean place(MinecraftClient client, ClientPlayerEntity p, ClientWorld w, BlockPos cell) {
		int slot = roofSlot(p.getInventory());
		if (slot < 0) return false;
		Direction face = Plan.placeFace(w, cell);
		if (face == null) return false;
		BlockPos against = cell.offset(face.getOpposite());
		Job.select(client, p, slot);
		Vec3d hit = Vec3d.ofCenter(against).add(Vec3d.of(face.getVector()).multiply(0.5));
		Job.look(p, hit);
		client.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hit, face, against, false));
		p.swingHand(Hand.MAIN_HAND);
		return true;
	}

	// ---------------------------------------------------------------- what's about

	private static boolean zoneUsable(ClientPlayerEntity p, ClientWorld w) {
		GkConfig cfg = GkConfig.INSTANCE;
		return cfg.zoneSet && Areas.dimension(w).equals(cfg.zoneDimension) && Math.hypot(cfg.zoneX - p.getX(), cfg.zoneZ - p.getZ()) <= cfg.zoneDistance;
	}

	/** What can be seen within 96 blocks, scored. */
	static Threat.Assessment assess(ClientPlayerEntity p, ClientWorld w, Threat.Players players) {
		List<Threat.Seen> seen = new ArrayList<>();
		for (Entity e : w.getEntities()) {
			if (e == p || !e.isAlive() || !(e instanceof LivingEntity living)) continue;
			double d = e.distanceTo(p);
			if (d > 96) continue;
			if (e instanceof PlayerEntity other) {
				if (other.isSpectator()) continue;
				String verdict = NaughtyBridge.verdict(other.getUuid());
				Threat.Kind kind = "NAUGHTY".equals(verdict) || "LOOK".equals(verdict) ? Threat.Kind.PLAYER_NAUGHTY
						: "NICE".equals(verdict) || "FRIEND".equals(verdict) ? Threat.Kind.PLAYER_FRIEND : Threat.Kind.PLAYER_STRANGER;
				seen.add(new Threat.Seen(kind, d, other.getGameProfile().name()));
				continue;
			}
			if (!(e instanceof Monster)) continue;
			EntityType<?> type = e.getType();
			Threat.Kind kind;
			if (e instanceof CreeperEntity creeper) kind = creeper.getFuseSpeed() > 0 || creeper.isIgnited() ? Threat.Kind.CREEPER_LIT : Threat.Kind.CREEPER;
			else if (type == EntityType.WARDEN) kind = Threat.Kind.WARDEN;
			else if (type == EntityType.SKELETON || type == EntityType.STRAY || type == EntityType.BOGGED || type == EntityType.PILLAGER)
				kind = living.isUsingItem() ? Threat.Kind.ARCHER_AIMING : Threat.Kind.ARCHER;
			else kind = Threat.Kind.HOSTILE;
			seen.add(new Threat.Seen(kind, d, null));
		}
		return Threat.assess(seen, players);
	}

	/** Away from the creeper, sprinting. */
	private static void runFromCreeper(MinecraftClient client, ClientPlayerEntity p, ClientWorld w) {
		Job.status = Text.literal("Running from a creeper");
		Entity nearest = null;
		double best = Double.MAX_VALUE;
		for (Entity e : w.getEntities()) {
			if (!(e instanceof CreeperEntity c) || !c.isAlive()) continue;
			double d = c.distanceTo(p);
			if (d < best) {
				best = d;
				nearest = c;
			}
		}
		if (nearest != null) dodgeFrom = nearest.getEntityPos();
		if (dodgeFrom == null) return;
		Vec3d away = p.getEntityPos().subtract(dodgeFrom);
		Job.look(p, p.getEyePos().add(away.x, 0, away.z));
		client.options.forwardKey.setPressed(true);
		client.options.sprintKey.setPressed(true);
		if (p.horizontalCollision && p.isOnGround()) client.options.jumpKey.setPressed(true);
		else client.options.jumpKey.setPressed(false);
	}

	/** The block an armour piece or tool is worn down to, as a share of new. */
	static float wear(ItemStack s) {
		return s.isDamageable() ? (s.getMaxDamage() - s.getDamage()) / (float) s.getMaxDamage() : 1;
	}
}
