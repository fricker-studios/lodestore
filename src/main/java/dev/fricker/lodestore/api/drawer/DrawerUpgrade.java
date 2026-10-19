package dev.fricker.lodestore.api.drawer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Datapack-defined drawer upgrade.
 *
 * <p>An upgrade is a set of behaviour flags rather than a named effect. A datapack that wants a
 * "locked" drawer does not name the lock upgrade's implementation; it sets {@code locks_contents}.
 * That is what lets a pack add an upgrade of its own — pair a new item with an entry here — without
 * the mod shipping a branch for it.
 *
 * <p>Which item installs an upgrade is deliberately not here: the item carries the key, not the
 * other way round, so two items may share behaviour and an upgrade may exist with no item at all.
 *
 * @param locksContents whether the drawer accepts only the single item it has been locked to
 * @param voidsOverflow whether the drawer accepts and destroys the item it is locked to once it has
 *                      no room left, instead of refusing it. Has no effect on a drawer that is not
 *                      locked: voiding is only ever applied to the one item a drawer was explicitly
 *                      told it is for, so it cannot destroy something that merely passed through.
 * @param slotCapacity       how many storage slots a <em>lock</em> reaches; pools across what is
 *                           installed. The lock is the only behaviour that is targeted at slots —
 *                           voiding and stacking apply to the whole drawer — so this means nothing
 *                           on an upgrade that does not lock.
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
public record DrawerUpgrade(boolean locksContents, boolean voidsOverflow, int slotCapacity, int stacksPerSlot,
		int unstackablePerSlot) {
	/**
	 * The ceiling on {@code stacks_per_slot}. A thousand stacks of 64 is 65,536 of one item in one
	 * slot, comfortably inside an {@code int}, which is what the menu's slot arithmetic runs in.
	 */
	public static final int MAX_STACKS_PER_SLOT = 1024;

	public static final Codec<DrawerUpgrade> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.BOOL.fieldOf("locks_contents").forGetter(DrawerUpgrade::locksContents),
			// Optional so that upgrades written before voiding existed still decode. A missing
			// flag means "does not void", which is the harmless reading of the two.
			Codec.BOOL.optionalFieldOf("voids_overflow", false).forGetter(DrawerUpgrade::voidsOverflow),
			// How far one of these reaches. Levels are data, not code: a level-two lock is a
			// level-one lock with a larger number here, and capacities pool across what is
			// installed, so two level-ones reach exactly as far as one level-two.
			Codec.intRange(0, 1024).optionalFieldOf("slot_capacity", 0).forGetter(DrawerUpgrade::slotCapacity),
			// Optional for the same reason as voids_overflow: every upgrade written before the
			// storage layer existed is a one-stack upgrade, and says so by saying nothing.
			Codec.intRange(1, MAX_STACKS_PER_SLOT).optionalFieldOf("stacks_per_slot", 1)
					.forGetter(DrawerUpgrade::stacksPerSlot),
			Codec.intRange(1, MAX_STACKS_PER_SLOT).optionalFieldOf("unstackable_per_slot", 1)
					.forGetter(DrawerUpgrade::unstackablePerSlot)
	).apply(instance, DrawerUpgrade::new));

	/**
	 * Used when an upgrade cannot be resolved. Does nothing at all, because an unresolved upgrade
	 * that silently locked a drawer would strand its contents behind a filter nobody set — and one
	 * that silently voided would destroy them outright.
	 */
	public static final DrawerUpgrade FALLBACK = new DrawerUpgrade(false, false, 0, 1, 1);

	/** Whether this upgrade lets a slot hold more than it otherwise would. */
	public boolean stacks() {
		return stacksPerSlot > 1 || unstackablePerSlot > 1;
	}
}
