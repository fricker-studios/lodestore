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
	/**
	 * Locks a whole drawer to what it already holds, however many kinds that is — or, if it is
	 * empty, to the first thing put in.
	 */
	public static final DrawerUpgradeItem LOCK_UPGRADE = new DrawerUpgradeItem(
			new Item.Properties().setId(LOCK_UPGRADE_KEY), LodestoreDrawerUpgrades.LOCK);

	public static final ResourceKey<Item> VOID_UPGRADE_KEY = itemKey("void_upgrade");
	/**
	 * Locks a drawer and then destroys what will not fit, so a filling machine never backs up.
	 *
	 * <p>Locks as well as voids, rather than only voiding, so that one item is enough to set a
	 * drawer up and the thing it destroys is always something the player named.
	 */
	public static final DrawerUpgradeItem VOID_UPGRADE = new DrawerUpgradeItem(
			new Item.Properties().setId(VOID_UPGRADE_KEY), LodestoreDrawerUpgrades.VOID);

	private LodestoreItems() {
	}

	/** Forces class-loading and performs registration. */
	public static void initialize() {
		Registry.register(BuiltInRegistries.ITEM, LOCK_UPGRADE_KEY, LOCK_UPGRADE);
		Registry.register(BuiltInRegistries.ITEM, VOID_UPGRADE_KEY, VOID_UPGRADE);
	}

	private static ResourceKey<Item> itemKey(String name) {
		return ResourceKey.create(Registries.ITEM, Lodestore.id(name));
	}
}
