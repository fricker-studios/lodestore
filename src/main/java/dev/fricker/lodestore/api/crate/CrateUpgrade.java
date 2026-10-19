package dev.fricker.lodestore.api.crate;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Datapack-defined crate upgrade.
 *
 * <p>An upgrade is a set of behaviour flags rather than a named effect. A datapack that wants a
 * "locked" crate does not name the lock upgrade's implementation; it sets {@code locks_contents}.
 * That is what lets a pack add an upgrade of its own — pair a new item with an entry here — without
 * the mod shipping a branch for it.
 *
 * <p>Which item installs an upgrade is deliberately not here: the item carries the key, not the
 * other way round, so two items may share behaviour and an upgrade may exist with no item at all.
 *
 * <p>Every behaviour applies to the whole crate, so there is no reach to state. An entry written
 * when the lock was aimed at slots may still carry {@code slot_capacity}; the codec ignores it.
 *
 * @param locksContents      whether the crate accepts only what it held when the lock went on —
 *                           every kind of item in it, or the first one put in if it was empty
 * @param voidsOverflow      whether the crate accepts and destroys an item it already holds once
 *                           it has no room left, instead of refusing it. Never applies to anything
 *                           the crate does not already hold, so it cannot destroy something that
 *                           merely passed through.
 * @param stacksPerSlot      how many stacks every slot holds, for items that stack. One is a
 *                           normal slot; anything above it is what a stack upgrade grants, and is
 *                           what Lodestore's own storage layer exists to express, since vanilla's
 *                           {@code Container} cannot. Does not pool: the deepest installed upgrade
 *                           decides.
 * @param unstackablePerSlot how many of an item that does not stack — a tool, a piece of armour —
 *                           a slot holds. Kept apart from {@code stacks_per_slot} because four
 *                           times one is a poor upgrade; the shipped levels give eight, sixteen
 *                           and thirty-two.
 */
public record CrateUpgrade(boolean locksContents, boolean voidsOverflow, int stacksPerSlot,
		int unstackablePerSlot) {
	/**
	 * The ceiling on {@code stacks_per_slot}. A thousand stacks of 64 is 65,536 of one item in one
	 * slot, comfortably inside an {@code int}, which is what the menu's slot arithmetic runs in.
	 */
	public static final int MAX_STACKS_PER_SLOT = 1024;

	public static final Codec<CrateUpgrade> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.BOOL.fieldOf("locks_contents").forGetter(CrateUpgrade::locksContents),
			// Optional so that upgrades written before voiding existed still decode. A missing
			// flag means "does not void", which is the harmless reading of the two.
			Codec.BOOL.optionalFieldOf("voids_overflow", false).forGetter(CrateUpgrade::voidsOverflow),
			// Optional for the same reason as voids_overflow: every upgrade written before the
			// storage layer existed is a one-stack upgrade, and says so by saying nothing.
			Codec.intRange(1, MAX_STACKS_PER_SLOT).optionalFieldOf("stacks_per_slot", 1)
					.forGetter(CrateUpgrade::stacksPerSlot),
			Codec.intRange(1, MAX_STACKS_PER_SLOT).optionalFieldOf("unstackable_per_slot", 1)
					.forGetter(CrateUpgrade::unstackablePerSlot)
	).apply(instance, CrateUpgrade::new));

	/**
	 * Used when an upgrade cannot be resolved. Does nothing at all, because an unresolved upgrade
	 * that silently locked a crate would strand its contents behind a filter nobody set — and one
	 * that silently voided would destroy them outright.
	 */
	public static final CrateUpgrade FALLBACK = new CrateUpgrade(false, false, 1, 1);

	/** Whether this upgrade lets a slot hold more than it otherwise would. */
	public boolean stacks() {
		return stacksPerSlot > 1 || unstackablePerSlot > 1;
	}
}
