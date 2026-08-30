package dev.fricker.lodestore.api;

import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

import dev.fricker.lodestore.api.drawer.DrawerTier;

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
	 * Datapack-driven drawer tiers.
	 *
	 * <p>Entries live at {@code data/<pack>/lodestore/drawer_tier/<name>.json} and are synced to
	 * clients, because capacity is needed client-side for the front-face count overlay.
	 */
	public static final ResourceKey<Registry<DrawerTier>> DRAWER_TIER =
			ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath(NAMESPACE, "drawer_tier"));

	private LodestoreRegistries() {
	}
}
