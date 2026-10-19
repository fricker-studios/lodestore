package dev.fricker.lodestore.registry;

import net.minecraft.resources.ResourceKey;

import dev.fricker.lodestore.Lodestore;
import dev.fricker.lodestore.api.LodestoreRegistries;
import dev.fricker.lodestore.api.drawer.DrawerUpgrade;

/**
 * Keys for the upgrades Lodestore ships. The values live in datapack JSON under
 * {@code data/lodestore/lodestore/drawer_upgrade/}; only the keys are code.
 */
public final class LodestoreDrawerUpgrades {
	public static final ResourceKey<DrawerUpgrade> LOCK = key("lock");
	/** Levels differ only in how many storage slots they reach; the behaviour is the same. */
	public static final ResourceKey<DrawerUpgrade> LOCK_2 = key("lock_2");
	public static final ResourceKey<DrawerUpgrade> LOCK_3 = key("lock_3");
	public static final ResourceKey<DrawerUpgrade> VOID = key("void");
	/** Covered slots hold several stacks. The one upgrade that needed Lodestore's own storage layer. */
	public static final ResourceKey<DrawerUpgrade> STACK = key("stack");

	private LodestoreDrawerUpgrades() {
	}

	private static ResourceKey<DrawerUpgrade> key(String name) {
		return ResourceKey.create(LodestoreRegistries.DRAWER_UPGRADE, Lodestore.id(name));
	}
}
