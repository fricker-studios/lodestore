package dev.fricker.lodestore.registry;

import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;

import dev.fricker.lodestore.Lodestore;
import dev.fricker.lodestore.item.InstalledUpgrades;
import dev.fricker.lodestore.storage.CrateOverflow;

/** Data components Lodestore puts on items. */
public final class LodestoreComponents {
	/**
	 * The counts parked behind a crate's visible stacks, carried on the dropped item alongside
	 * {@code minecraft:container} for the tiers that keep their contents.
	 *
	 * <p>Synchronised because a crate in a player's hand is sent to the client, and the tooltip
	 * has to be able to say what the stacks alone do not.
	 */
	public static final DataComponentType<CrateOverflow> CRATE_OVERFLOW = DataComponentType.<CrateOverflow>builder()
			.persistent(CrateOverflow.CODEC)
			.networkSynchronized(CrateOverflow.STREAM_CODEC)
			.build();

	/**
	 * The upgrades installed in a crate, and what its lock has been set to, carried on the dropped
	 * item for every tier so that breaking a crate never takes its upgrades out.
	 *
	 * <p>Synchronised for the item's tooltip, which lists what is installed.
	 */
	public static final DataComponentType<InstalledUpgrades> CRATE_UPGRADES = DataComponentType.<InstalledUpgrades>builder()
			.persistent(InstalledUpgrades.CODEC)
			.networkSynchronized(InstalledUpgrades.STREAM_CODEC)
			.build();

	private LodestoreComponents() {
	}

	/** Forces class-loading and performs registration. */
	public static void initialize() {
		Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, Lodestore.id("crate_overflow"), CRATE_OVERFLOW);
		Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, Lodestore.id("crate_upgrades"), CRATE_UPGRADES);
	}
}
