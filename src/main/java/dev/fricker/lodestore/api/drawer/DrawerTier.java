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
 * @param keepsContents whether contents survive being broken, shulker-style, rather than spilling
 * @param nestingRank   ordering used to stop drawers nesting inside themselves. A drawer that keeps
 *                      its contents refuses any drawer of equal or higher rank, which would
 *                      otherwise let storage compound without limit.
 */
public record DrawerTier(boolean keepsContents, int nestingRank) {
	public static final Codec<DrawerTier> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.BOOL.fieldOf("keeps_contents").forGetter(DrawerTier::keepsContents),
			Codec.intRange(0, 1024).fieldOf("nesting_rank").forGetter(DrawerTier::nestingRank)
	).apply(instance, DrawerTier::new));

	/**
	 * Used when a tier cannot be resolved. Spills its contents rather than keeping them, because
	 * pretending an unresolved drawer is safe to break would lose items silently.
	 */
	public static final DrawerTier FALLBACK = new DrawerTier(false, 0);

	/** Whether this drawer may hold {@code other}. */
	public boolean canContain(DrawerTier other) {
		return !keepsContents || other.nestingRank() < nestingRank();
	}
}
