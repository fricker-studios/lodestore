package dev.fricker.lodestore.registry;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import dev.fricker.lodestore.Lodestore;

/**
 * The ids crates were saved under while they were called drawers.
 *
 * <p>Up to and including v0.2.0-rc.1 a crate was a drawer, and a world from then names its blocks,
 * items, block entities and parked counts by the old ids. Vanilla resolves every one of those by
 * name — chunk palettes, item stacks, block entity tags, component patches — so without these a
 * drawer from that world loads as air and its item drops out of the inventory, with nothing more
 * than a log line to say so.
 *
 * <p>An alias only redirects a lookup by id or key. The registry still holds each crate once, under
 * its new name, so nothing is listed twice and nothing is ever written back under an old name.
 *
 * <p>The menu is left out because no save names it. The two datapack registries cannot be aliased
 * this way, and do not need to be: nothing saved names them either, only the entries in them, and
 * those kept their names. A pack that wrote to {@code lodestore/drawer_upgrade/} has to move to
 * {@code lodestore/crate_upgrade/}.
 */
public final class LodestoreAliases {
	private LodestoreAliases() {
	}

	/** Must run after everything it points at is registered. */
	public static void initialize() {
		for (String tier : new String[] {"basic", "sturdy", "reinforced"}) {
			alias(BuiltInRegistries.BLOCK, tier + "_drawer", tier + "_crate");
			alias(BuiltInRegistries.ITEM, tier + "_drawer", tier + "_crate");
		}

		alias(BuiltInRegistries.BLOCK_ENTITY_TYPE, "drawer", "crate");
		alias(BuiltInRegistries.DATA_COMPONENT_TYPE, "drawer_overflow", "crate_overflow");
	}

	private static void alias(Registry<?> registry, String oldName, String newName) {
		Identifier target = Lodestore.id(newName);

		// A typo here would alias to nothing and fail only when an old world is opened.
		if (!registry.containsKey(target)) {
			throw new IllegalStateException(registry.key().identifier() + " has no " + target + " to alias "
					+ oldName + " to");
		}

		registry.addAlias(Lodestore.id(oldName), target);
	}
}
