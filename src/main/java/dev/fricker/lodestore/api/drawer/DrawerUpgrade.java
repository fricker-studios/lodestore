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
 */
public record DrawerUpgrade(boolean locksContents) {
	public static final Codec<DrawerUpgrade> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.BOOL.fieldOf("locks_contents").forGetter(DrawerUpgrade::locksContents)
	).apply(instance, DrawerUpgrade::new));

	/**
	 * Used when an upgrade cannot be resolved. Does nothing at all, because an unresolved upgrade
	 * that silently locked a drawer would strand its contents behind a filter nobody set.
	 */
	public static final DrawerUpgrade FALLBACK = new DrawerUpgrade(false);
}
