package dev.fricker.lodestore.api.crate;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Datapack-defined crate behaviour.
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
 * @param nestingRank   ordering used to stop crates nesting inside themselves. A crate that keeps
 *                      its contents refuses any crate <em>with contents</em> of equal or higher
 *                      rank, which would otherwise let storage compound without limit. An empty
 *                      crate compounds nothing and is taken by any tier.
 * @param upgradeSlots  how many upgrades a crate of this tier may hold at once
 */
public record CrateTier(boolean keepsContents, int nestingRank, int upgradeSlots) {
	/**
	 * The ceiling on {@code upgrade_slots}, and therefore on how wide the upgrade row in the
	 * crate screen can ever be. A datapack cannot raise it: the screen is laid out in code.
	 */
	public static final int MAX_UPGRADE_SLOTS = 5;

	public static final Codec<CrateTier> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.BOOL.fieldOf("keeps_contents").forGetter(CrateTier::keepsContents),
			Codec.intRange(0, 1024).fieldOf("nesting_rank").forGetter(CrateTier::nestingRank),
			Codec.intRange(0, MAX_UPGRADE_SLOTS).fieldOf("upgrade_slots").forGetter(CrateTier::upgradeSlots)
	).apply(instance, CrateTier::new));

	/**
	 * Used when a tier cannot be resolved. Spills its contents rather than keeping them, because
	 * pretending an unresolved crate is safe to break would lose items silently, and accepts no
	 * upgrades, because installing one into a crate whose rules are unknown would strand it.
	 */
	public static final CrateTier FALLBACK = new CrateTier(false, 0, 0);

	/** Whether this crate may hold {@code other}. */
	public boolean canContain(CrateTier other) {
		return !keepsContents || other.nestingRank() < nestingRank();
	}
}
