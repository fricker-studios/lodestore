package dev.fricker.lodestore.api.drawer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;

/**
 * A datapack-defined drawer tier.
 *
 * <p>Capacity is expressed as a <em>stack multiplier</em> rather than an absolute count, so a tier
 * behaves consistently across items with different stack sizes: a 32-stack drawer holds 2048 cobble
 * or 512 ender pearls. The resulting capacity is a {@code long} and is free to exceed
 * {@link Integer#MAX_VALUE}.
 *
 * @param slots         number of independent item variants this drawer holds
 * @param stackCapacity capacity of each slot, in stacks of the stored item
 */
public record DrawerTier(int slots, int stackCapacity) {
	public static final Codec<DrawerTier> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.intRange(1, 16).fieldOf("slots").forGetter(DrawerTier::slots),
			Codec.intRange(1, 1 << 24).fieldOf("stack_capacity").forGetter(DrawerTier::stackCapacity)
	).apply(instance, DrawerTier::new));

	/**
	 * Used when a tier cannot be resolved — a datapack removed it, or the level's registries are not
	 * available yet. Deliberately conservative rather than zero, so an unresolvable tier degrades to
	 * a working vanilla-sized drawer instead of a black hole that voids items.
	 */
	public static final DrawerTier FALLBACK = new DrawerTier(1, 1);

	/** Capacity for {@code variant}, in items. A blank variant yields a nominal estimate. */
	public long capacityFor(ItemVariant variant) {
		int perStack = variant.isBlank() ? 64 : variant.getItem().getDefaultMaxStackSize();
		return (long) stackCapacity * perStack;
	}
}
