package dev.fricker.lodestore.item;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;

/**
 * What is bolted to a crate, carried on its item so it goes wherever the crate goes: the installed
 * upgrades, what the lock has been set to — the kinds it claimed and the slot chosen for the
 * face — and the experience the disenchant upgrade has banked.
 *
 * <p>The lock's state rides with the lock because it lasts exactly as long as the lock does.
 * Taking the lock off forgets it; breaking the crate is not taking the lock off, so a crate placed
 * back down is locked to what it was locked to before, even if its contents spilled. The banked
 * experience rides along for the same reason: it belongs to the disenchant upgrade, which cannot
 * be taken out until it is collected, so breaking the crate is the only other way out and must
 * not lose it either.
 *
 * <p>Templates rather than stacks, because a component is a value: two crates fitted alike must
 * compare equal or they would never stack, and {@code ItemStack} has no equality of its own.
 *
 * @param upgrades         the installed upgrades, densely, one each
 * @param lockFilter       the kinds of item the lock lets in
 * @param displaySlot      the storage slot the face shows, or -1 for the most numerous item
 * @param displayItem      what that slot last held
 * @param storedExperience       experience points the disenchant upgrade holds for the player
 * @param destructiveCompression whether the compress upgrade has been told to follow one-way pathways
 */
public record InstalledUpgrades(List<ItemStackTemplate> upgrades, List<ItemStackTemplate> lockFilter,
		int displaySlot, Optional<ItemStackTemplate> displayItem, long storedExperience, boolean destructiveCompression) {
	public static final InstalledUpgrades EMPTY =
			new InstalledUpgrades(List.of(), List.of(), -1, Optional.empty(), 0L, false);

	public static final Codec<InstalledUpgrades> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			ItemStackTemplate.CODEC.listOf().optionalFieldOf("upgrades", List.of()).forGetter(InstalledUpgrades::upgrades),
			ItemStackTemplate.CODEC.listOf().optionalFieldOf("lock_filter", List.of()).forGetter(InstalledUpgrades::lockFilter),
			Codec.INT.optionalFieldOf("display_slot", -1).forGetter(InstalledUpgrades::displaySlot),
			ItemStackTemplate.CODEC.optionalFieldOf("display_item").forGetter(InstalledUpgrades::displayItem),
			// Optional, and absent when zero, so a crate that has never disenchanted anything
			// carries exactly the component it carried before this field existed.
			Codec.LONG.optionalFieldOf("stored_experience", 0L).forGetter(InstalledUpgrades::storedExperience),
			Codec.BOOL.optionalFieldOf("destructive_compression", false).forGetter(InstalledUpgrades::destructiveCompression)
	).apply(instance, InstalledUpgrades::new));

	public static final StreamCodec<RegistryFriendlyByteBuf, InstalledUpgrades> STREAM_CODEC = StreamCodec.composite(
			ItemStackTemplate.STREAM_CODEC.apply(ByteBufCodecs.list(256)), InstalledUpgrades::upgrades,
			ItemStackTemplate.STREAM_CODEC.apply(ByteBufCodecs.list(256)), InstalledUpgrades::lockFilter,
			ByteBufCodecs.VAR_INT, InstalledUpgrades::displaySlot,
			ByteBufCodecs.optional(ItemStackTemplate.STREAM_CODEC), InstalledUpgrades::displayItem,
			ByteBufCodecs.VAR_LONG, InstalledUpgrades::storedExperience,
			ByteBufCodecs.BOOL, InstalledUpgrades::destructiveCompression,
			InstalledUpgrades::new);

	public InstalledUpgrades {
		upgrades = List.copyOf(upgrades);
		lockFilter = List.copyOf(lockFilter);
	}

	/** Captures a crate's fittings. Empty stacks are left out; an empty result is {@link #EMPTY}. */
	public static InstalledUpgrades of(List<ItemStack> upgrades, List<ItemStack> lockFilter, int displaySlot,
			ItemStack displayItem) {
		return of(upgrades, lockFilter, displaySlot, displayItem, 0L);
	}

	/** Captures a crate's fittings, banked experience included. */
	public static InstalledUpgrades of(List<ItemStack> upgrades, List<ItemStack> lockFilter, int displaySlot,
			ItemStack displayItem, long storedExperience) {
		return of(upgrades, lockFilter, displaySlot, displayItem, storedExperience, false);
	}

	/** Captures a crate's fittings, banked experience and the compress upgrade's setting included. */
	public static InstalledUpgrades of(List<ItemStack> upgrades, List<ItemStack> lockFilter, int displaySlot,
			ItemStack displayItem, long storedExperience, boolean destructiveCompression) {
		InstalledUpgrades fitted = new InstalledUpgrades(templates(upgrades), templates(lockFilter), displaySlot,
				displayItem.isEmpty() ? Optional.empty() : Optional.of(ItemStackTemplate.fromNonEmptyStack(displayItem)),
				Math.max(0L, storedExperience), destructiveCompression);
		return fitted.isEmpty() ? EMPTY : fitted;
	}

	private static List<ItemStackTemplate> templates(List<ItemStack> stacks) {
		List<ItemStackTemplate> templates = new ArrayList<>();

		for (ItemStack stack : stacks) {
			if (!stack.isEmpty()) {
				templates.add(ItemStackTemplate.fromNonEmptyStack(stack));
			}
		}

		return templates;
	}

	private static List<ItemStack> stacks(List<ItemStackTemplate> templates) {
		List<ItemStack> stacks = new ArrayList<>();

		for (ItemStackTemplate template : templates) {
			ItemStack stack = template.create();

			if (!stack.isEmpty()) {
				stacks.add(stack);
			}
		}

		return stacks;
	}

	/** Nothing installed and nothing set — what a crate fresh from the crafting table carries. */
	public boolean isEmpty() {
		return upgrades.isEmpty() && lockFilter.isEmpty() && displaySlot < 0 && displayItem.isEmpty()
				&& storedExperience <= 0 && !destructiveCompression;
	}

	/** The upgrades as fresh stacks, for the crate to install. */
	public List<ItemStack> upgradeStacks() {
		return stacks(upgrades);
	}

	/** The lock's claim as fresh stacks. */
	public List<ItemStack> lockFilterStacks() {
		return stacks(lockFilter);
	}

	public ItemStack displayItemStack() {
		return displayItem.map(ItemStackTemplate::create).orElse(ItemStack.EMPTY);
	}
}
