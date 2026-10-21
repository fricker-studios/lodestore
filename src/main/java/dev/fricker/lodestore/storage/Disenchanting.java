package dev.fricker.lodestore.storage;

import it.unimi.dsi.fastutil.objects.Object2IntMap;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * What the disenchant upgrade does to an item, and what it is worth: the grindstone's arithmetic,
 * so a crate pays exactly what the block it stands in for would.
 *
 * <p>Curses stay on, as they do at a grindstone — taking one off for free would make the crate a
 * better grindstone than the grindstone. An enchanted book with nothing left on it becomes a plain
 * book, and the repair cost is reset to what the curses alone would carry.
 */
public final class Disenchanting {
	private Disenchanting() {
	}

	/** Whether the stack has an enchantment the upgrade would take off: any that is not a curse. */
	public static boolean hasRemovable(ItemStack stack) {
		for (Holder<Enchantment> enchantment : EnchantmentHelper.getEnchantmentsForCrafting(stack).keySet()) {
			if (!enchantment.is(EnchantmentTags.CURSE)) {
				return true;
			}
		}

		return false;
	}

	/**
	 * A copy of one of {@code stack} with its removable enchantments gone. Vanilla's
	 * {@code GrindstoneMenu#removeNonCursesFrom}, step for step.
	 */
	public static ItemStack stripped(ItemStack stack) {
		ItemStack result = stack.copyWithCount(1);
		ItemEnchantments left = EnchantmentHelper.updateEnchantments(result,
				enchantments -> enchantments.removeIf(holder -> !holder.is(EnchantmentTags.CURSE)));

		if (result.is(Items.ENCHANTED_BOOK) && left.isEmpty()) {
			result = result.transmuteCopy(Items.BOOK);
		}

		int cost = 0;

		for (int i = 0; i < left.size(); i++) {
			cost = AnvilMenu.calculateIncreasedRepairCost(cost);
		}

		result.set(DataComponents.REPAIR_COST, cost);
		return result;
	}

	/** The experience the enchantments on one of {@code stack} are worth before the grindstone's roll. */
	public static int worth(ItemStack stack) {
		int worth = 0;

		for (Object2IntMap.Entry<Holder<Enchantment>> entry : EnchantmentHelper.getEnchantmentsForCrafting(stack).entrySet()) {
			if (!entry.getKey().is(EnchantmentTags.CURSE)) {
				worth += entry.getKey().value().getMinCost(entry.getIntValue());
			}
		}

		return worth;
	}

	/**
	 * What a grindstone would actually pay out for {@code worth}: between half of it, rounded up,
	 * and one short of twice that. The same roll, so the upgrade is a grindstone that runs itself
	 * rather than a better one.
	 */
	public static int roll(RandomSource random, int worth) {
		if (worth <= 0) {
			return 0;
		}

		int half = Mth.ceil(worth / 2.0);
		return half + random.nextInt(half);
	}

	/** How many whole levels {@code points} of experience come to, counted up from level zero. */
	public static int levels(long points) {
		int level = 0;

		while (points >= pointsToNextLevel(level) && level < Integer.MAX_VALUE) {
			points -= pointsToNextLevel(level);
			level++;
		}

		return level;
	}

	/** Vanilla's {@code Player#getXpNeededForNextLevel}, for a player standing at {@code level}. */
	public static int pointsToNextLevel(int level) {
		if (level >= 30) {
			return 112 + (level - 30) * 9;
		}

		if (level >= 15) {
			return 37 + (level - 15) * 5;
		}

		return 7 + level * 2;
	}
}
