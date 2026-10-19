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

	private LodestoreDrawerUpgrades() {
	}

	private static ResourceKey<DrawerUpgrade> key(String name) {
		return ResourceKey.create(LodestoreRegistries.DRAWER_UPGRADE, Lodestore.id(name));
	}
}
