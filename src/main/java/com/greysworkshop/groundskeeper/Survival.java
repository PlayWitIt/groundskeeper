package com.greysworkshop.groundskeeper;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The small things that keep a long job alive over days: eats when hungry (good food first, never anything that
 * poisons, teleports or is too precious), keeps a totem in the off hand, and says when armour is nearly worn out.
 */
final class Survival {
	/** Best first. Anything else edible comes after these, unless it's in {@link #NEVER}. */
	private static final List<Item> PREFER = List.of(Items.GOLDEN_CARROT, Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.COOKED_MUTTON, Items.COOKED_SALMON,
			Items.BAKED_POTATO, Items.COOKED_CHICKEN, Items.COOKED_RABBIT, Items.COOKED_COD, Items.RABBIT_STEW, Items.MUSHROOM_STEW, Items.BEETROOT_SOUP,
			Items.BREAD, Items.PUMPKIN_PIE, Items.APPLE, Items.CARROT, Items.MELON_SLICE, Items.SWEET_BERRIES, Items.GLOW_BERRIES, Items.COOKIE,
			Items.DRIED_KELP, Items.BEETROOT, Items.POTATO);
	/** Poisons, makes you hungry, teleports you, or is worth keeping for an emergency. */
	private static final Set<Item> NEVER = Set.of(Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO, Items.PUFFERFISH, Items.CHORUS_FRUIT,
			Items.SUSPICIOUS_STEW, Items.CHICKEN, Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE, Items.HONEY_BOTTLE);

	private static int eating, eatFood, cooldown, ticks;
	private static final Set<EquipmentSlot> warned = new HashSet<>();

	private Survival() {}

	static void reset(MinecraftClient client) {
		if (eating > 0) client.options.useKey.setPressed(false);
		eating = 0;
		cooldown = 0;
		warned.clear();
	}

	static boolean eating() {
		return eating > 0;
	}

	/** Every job tick. True while it's eating (the job waits). */
	static boolean tick(MinecraftClient client, ClientPlayerEntity p) {
		GkConfig cfg = GkConfig.INSTANCE;
		ticks++;
		if (cooldown > 0) cooldown--;
		int food = p.getHungerManager().getFoodLevel();
		if (eating > 0) {
			eating++;
			Job.status = Text.literal("Eating");
			// Up at the sky: eating while looking at farmland would plant a carrot in it.
			p.setPitch(-90);
			client.options.useKey.setPressed(true);
			if (food > eatFood || eating > 60) {
				client.options.useKey.setPressed(false);
				if (food <= eatFood) cooldown = 600;
				eating = 0;
			}
			return true;
		}
		if (ticks % 20 == 0) {
			if (cfg.totem) totem(client, p);
			armour(p);
		}
		if (!cfg.autoEat || cooldown > 0) return false;
		boolean hungry = food <= 14 || food < 20 && p.getHealth() <= 12;
		if (!hungry) return false;
		int slot = foodSlot(p.getInventory());
		if (slot < 0) {
			if (food <= 6 && ticks % 1200 == 0) Job.chat(p, Text.literal("Hungry and nothing safe to eat: bring food.").formatted(Formatting.YELLOW));
			cooldown = 200;
			return false;
		}
		Job.walker.stop(client);
		Job.select(client, p, slot);
		eating = 1;
		eatFood = food;
		return true;
	}

	static int foodSlot(PlayerInventory inv) {
		for (Item item : PREFER) {
			int s = Job.slot(inv, st -> st.isOf(item));
			if (s >= 0) return s;
		}
		return Job.slot(inv, st -> st.get(DataComponentTypes.FOOD) != null && !NEVER.contains(st.getItem()));
	}

	/** A totem from the inventory into an empty off hand. */
	private static void totem(MinecraftClient client, ClientPlayerEntity p) {
		if (!p.getOffHandStack().isEmpty() || p.currentScreenHandler != p.playerScreenHandler || client.interactionManager == null) return;
		int slot = Job.slot(p.getInventory(), s -> s.isOf(Items.TOTEM_OF_UNDYING));
		if (slot < 0) return;
		client.interactionManager.clickSlot(p.playerScreenHandler.syncId, slot < 9 ? 36 + slot : slot, 40, SlotActionType.SWAP, p);
		Job.log("totem into the off hand from slot {}", slot);
	}

	private static void armour(ClientPlayerEntity p) {
		for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			ItemStack s = p.getEquippedStack(slot);
			if (s.isEmpty() || !s.isDamageable()) continue;
			if (Shelter.wear(s) <= 0.1f) {
				if (warned.add(slot)) Job.chat(p, Text.literal("Your " + s.getName().getString() + " is nearly worn out.").formatted(Formatting.YELLOW));
			} else warned.remove(slot);
		}
	}
}
