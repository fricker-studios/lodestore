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
	public static final ResourceKey<DrawerUpgrade> VOID = key("void");
	/** Every slot holds several stacks. The one upgrade that needed Lodestore's own storage layer. */
	public static final ResourceKey<DrawerUpgrade> STACK = key("stack");
	/** Levels differ only in how deep a slot goes; the behaviour is the same. */
	public static final ResourceKey<DrawerUpgrade> STACK_2 = key("stack_2");
	public static final ResourceKey<DrawerUpgrade> STACK_3 = key("stack_3");

	private LodestoreDrawerUpgrades() {
	}

	private static ResourceKey<DrawerUpgrade> key(String name) {
		return ResourceKey.create(LodestoreRegistries.DRAWER_UPGRADE, Lodestore.id(name));
	}
}
