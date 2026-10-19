package dev.fricker.lodestore.registry;

import net.minecraft.resources.ResourceKey;

import dev.fricker.lodestore.Lodestore;
import dev.fricker.lodestore.api.LodestoreRegistries;
import dev.fricker.lodestore.api.crate.CrateUpgrade;

/**
 * Keys for the upgrades Lodestore ships. The values live in datapack JSON under
 * {@code data/lodestore/lodestore/crate_upgrade/}; only the keys are code.
 */
public final class LodestoreCrateUpgrades {
	public static final ResourceKey<CrateUpgrade> LOCK = key("lock");
	public static final ResourceKey<CrateUpgrade> VOID = key("void");
	/** Every slot holds several stacks. The one upgrade that needed Lodestore's own storage layer. */
	public static final ResourceKey<CrateUpgrade> STACK = key("stack");
	/** Levels differ only in how deep a slot goes; the behaviour is the same. */
	public static final ResourceKey<CrateUpgrade> STACK_2 = key("stack_2");
	public static final ResourceKey<CrateUpgrade> STACK_3 = key("stack_3");

	private LodestoreCrateUpgrades() {
	}

	private static ResourceKey<CrateUpgrade> key(String name) {
		return ResourceKey.create(LodestoreRegistries.CRATE_UPGRADE, Lodestore.id(name));
	}
}
