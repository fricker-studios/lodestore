package dev.fricker.lodestore.registry;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

import dev.fricker.lodestore.Lodestore;
import dev.fricker.lodestore.item.DrawerUpgradeItem;

/**
 * Standalone item registration. Block items are registered alongside their blocks in
 * {@link LodestoreBlocks}; this is for items that are not a block in hand.
 */
public final class LodestoreItems {
	public static final ResourceKey<Item> LOCK_UPGRADE_KEY = itemKey("lock_upgrade");
	/** Locks a drawer to one kind of item — whatever it holds, or the next thing put in. */
	public static final DrawerUpgradeItem LOCK_UPGRADE = new DrawerUpgradeItem(
			new Item.Properties().setId(LOCK_UPGRADE_KEY), LodestoreDrawerUpgrades.LOCK);

	private LodestoreItems() {
	}

	/** Forces class-loading and performs registration. */
	public static void initialize() {
		Registry.register(BuiltInRegistries.ITEM, LOCK_UPGRADE_KEY, LOCK_UPGRADE);
	}

	private static ResourceKey<Item> itemKey(String name) {
		return ResourceKey.create(Registries.ITEM, Lodestore.id(name));
	}
}
