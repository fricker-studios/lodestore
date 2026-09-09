package dev.fricker.lodestore.registry;

import net.minecraft.resources.ResourceKey;

import dev.fricker.lodestore.Lodestore;
import dev.fricker.lodestore.api.LodestoreRegistries;
import dev.fricker.lodestore.api.drawer.DrawerTier;

/**
 * Keys for the tiers Lodestore ships. The values live in datapack JSON under
 * {@code data/lodestore/lodestore/drawer_tier/}; only the keys are code.
 */
public final class LodestoreDrawerTiers {
	public static final ResourceKey<DrawerTier> BASIC = key("basic");
	public static final ResourceKey<DrawerTier> STURDY = key("sturdy");
	public static final ResourceKey<DrawerTier> REINFORCED = key("reinforced");

	private LodestoreDrawerTiers() {
	}

	private static ResourceKey<DrawerTier> key(String name) {
		return ResourceKey.create(LodestoreRegistries.DRAWER_TIER, Lodestore.id(name));
	}
}
