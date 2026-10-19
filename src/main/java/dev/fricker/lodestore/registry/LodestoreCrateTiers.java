package dev.fricker.lodestore.registry;

import net.minecraft.resources.ResourceKey;

import dev.fricker.lodestore.Lodestore;
import dev.fricker.lodestore.api.LodestoreRegistries;
import dev.fricker.lodestore.api.crate.CrateTier;

/**
 * Keys for the tiers Lodestore ships. The values live in datapack JSON under
 * {@code data/lodestore/lodestore/crate_tier/}; only the keys are code.
 */
public final class LodestoreCrateTiers {
	public static final ResourceKey<CrateTier> BASIC = key("basic");
	public static final ResourceKey<CrateTier> STURDY = key("sturdy");
	public static final ResourceKey<CrateTier> REINFORCED = key("reinforced");

	private LodestoreCrateTiers() {
	}

	private static ResourceKey<CrateTier> key(String name) {
		return ResourceKey.create(LodestoreRegistries.CRATE_TIER, Lodestore.id(name));
	}
}
