package dev.fricker.lodestore.api.drawer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Datapack-defined drawer behaviour.
 *
 * <p>Container <em>size</em> is deliberately not here. It decides which menu type the block opens
 * and how many slots are deserialised, and the block entity has to know it before the level's
 * registries are reachable — a datapack-driven size would silently truncate a loaded inventory.
 * Size is therefore structural and lives on the block; this record carries what can safely change
 * at runtime.
 *
 * <p>The upgrade slot count <em>is</em> here, unlike size, and the difference is worth stating: a
 * slot count is only ever read when a player installs an upgrade, which happens with a level and
 * its registries already to hand. Nothing is sized from it and nothing is deserialised against it,
 * so a pack may change it between loads without stranding anything.
 *
 * @param keepsContents whether contents survive being broken, shulker-style, rather than spilling
 * @param nestingRank   ordering used to stop drawers nesting inside themselves. A drawer that keeps
 *                      its contents refuses any drawer of equal or higher rank, which would
 *                      otherwise let storage compound without limit.
 * @param upgradeSlots  how many upgrades a drawer of this tier may hold at once
 */
public record DrawerTier(boolean keepsContents, int nestingRank, int upgradeSlots) {
	/**
	 * The ceiling on {@code upgrade_slots}, and therefore on how wide the upgrade row in the
	 * drawer screen can ever be. A datapack cannot raise it: the screen is laid out in code.
	 */
	public static final int MAX_UPGRADE_SLOTS = 5;

	public static final Codec<DrawerTier> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.BOOL.fieldOf("keeps_contents").forGetter(DrawerTier::keepsContents),
			Codec.intRange(0, 1024).fieldOf("nesting_rank").forGetter(DrawerTier::nestingRank),
			Codec.intRange(0, MAX_UPGRADE_SLOTS).fieldOf("upgrade_slots").forGetter(DrawerTier::upgradeSlots)
	).apply(instance, DrawerTier::new));

	/**
	 * Used when a tier cannot be resolved. Spills its contents rather than keeping them, because
	 * pretending an unresolved drawer is safe to break would lose items silently, and accepts no
	 * upgrades, because installing one into a drawer whose rules are unknown would strand it.
	 */
	public static final DrawerTier FALLBACK = new DrawerTier(false, 0, 0);

	/** Whether this drawer may hold {@code other}. */
	public boolean canContain(DrawerTier other) {
		return !keepsContents || other.nestingRank() < nestingRank();
	}
}
