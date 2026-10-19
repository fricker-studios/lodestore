package dev.fricker.lodestore.api;

import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

import dev.fricker.lodestore.api.crate.CrateTier;
import dev.fricker.lodestore.api.crate.CrateUpgrade;

/**
 * Registry keys for Lodestore's extensible content.
 *
 * <p>This package is public API: it must never depend on Lodestore internals. Other mods are
 * expected to reference these keys directly, and to contribute entries via datapack JSON.
 */
public final class LodestoreRegistries {
	/** Namespace of the Lodestore registries themselves (not of the entries within them). */
	public static final String NAMESPACE = "lodestore";

	/**
	 * Datapack-driven crate tiers.
	 *
	 * <p>Entries live at {@code data/<pack>/lodestore/crate_tier/<name>.json} and are synced to
	 * clients, because capacity is needed client-side for the front-face count overlay.
	 */
	public static final ResourceKey<Registry<CrateTier>> CRATE_TIER =
			ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath(NAMESPACE, "crate_tier"));

	/**
	 * Datapack-driven crate upgrades.
	 *
	 * <p>Entries live at {@code data/<pack>/lodestore/crate_upgrade/<name>.json} and are synced to
	 * clients, because an upgrade changes how the crate's front face reads and the renderer has
	 * only the client's copy of the registry to resolve it against.
	 */
	public static final ResourceKey<Registry<CrateUpgrade>> CRATE_UPGRADE =
			ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath(NAMESPACE, "crate_upgrade"));

	private LodestoreRegistries() {
	}
}
