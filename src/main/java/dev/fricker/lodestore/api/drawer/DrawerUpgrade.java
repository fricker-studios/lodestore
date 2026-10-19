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
 * @param locksContents whether the drawer accepts only what it held when the lock went on — every
 *                      kind of item in it, or the first one put in if it was empty
 * @param voidsOverflow whether the drawer accepts and destroys the item it is locked to once it has
 *                      no room left, instead of refusing it. Has no effect on a drawer that is not
 *                      locked: voiding is only ever applied to the one item a drawer was explicitly
 *                      told it is for, so it cannot destroy something that merely passed through.
 */
public record DrawerUpgrade(boolean locksContents, boolean voidsOverflow, int slotCapacity) {
	public static final Codec<DrawerUpgrade> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.BOOL.fieldOf("locks_contents").forGetter(DrawerUpgrade::locksContents),
			// Optional so that upgrades written before voiding existed still decode. A missing
			// flag means "does not void", which is the harmless reading of the two.
			Codec.BOOL.optionalFieldOf("voids_overflow", false).forGetter(DrawerUpgrade::voidsOverflow)
			,
			// How many storage slots one of these reaches, for behaviours that cover chosen
			// slots. Capacities pool across what is installed, so two void upgrades reach twice
			// as far as one. The lock ignores it: it always covers the whole drawer.
			Codec.intRange(0, 1024).optionalFieldOf("slot_capacity", 0).forGetter(DrawerUpgrade::slotCapacity)
	).apply(instance, DrawerUpgrade::new));

	/**
	 * Used when an upgrade cannot be resolved. Does nothing at all, because an unresolved upgrade
	 * that silently locked a drawer would strand its contents behind a filter nobody set — and one
	 * that silently voided would destroy them outright.
	 */
	public static final DrawerUpgrade FALLBACK = new DrawerUpgrade(false, false, 0);
}
