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

	public static final ResourceKey<Item> STACK_UPGRADE_KEY = itemKey("stack_upgrade");
	/**
	 * Every slot holds several stacks: four, for this level.
	 *
	 * <p>The one upgrade that could not exist while a drawer was a vanilla {@code Container}, and
	 * the reason Lodestore grew its own storage layer. See the README.
	 */
	public static final DrawerUpgradeItem STACK_UPGRADE = new DrawerUpgradeItem(
			new Item.Properties().setId(STACK_UPGRADE_KEY), LodestoreDrawerUpgrades.STACK);

	public static final ResourceKey<Item> STACK_UPGRADE_2_KEY = itemKey("stack_upgrade_2");
	/** Eight stacks a slot where the first level holds four. Same behaviour, deeper. */
	public static final DrawerUpgradeItem STACK_UPGRADE_2 = new DrawerUpgradeItem(
			new Item.Properties().setId(STACK_UPGRADE_2_KEY), LodestoreDrawerUpgrades.STACK_2);

	public static final ResourceKey<Item> STACK_UPGRADE_3_KEY = itemKey("stack_upgrade_3");
	public static final DrawerUpgradeItem STACK_UPGRADE_3 = new DrawerUpgradeItem(
			new Item.Properties().setId(STACK_UPGRADE_3_KEY), LodestoreDrawerUpgrades.STACK_3);

	private LodestoreItems() {
	}

	/** Forces class-loading and performs registration. */
	public static void initialize() {
		Registry.register(BuiltInRegistries.ITEM, LOCK_UPGRADE_KEY, LOCK_UPGRADE);
		Registry.register(BuiltInRegistries.ITEM, VOID_UPGRADE_KEY, VOID_UPGRADE);
		Registry.register(BuiltInRegistries.ITEM, STACK_UPGRADE_KEY, STACK_UPGRADE);
		Registry.register(BuiltInRegistries.ITEM, STACK_UPGRADE_2_KEY, STACK_UPGRADE_2);
		Registry.register(BuiltInRegistries.ITEM, STACK_UPGRADE_3_KEY, STACK_UPGRADE_3);
	}

	private static ResourceKey<Item> itemKey(String name) {
		return ResourceKey.create(Registries.ITEM, Lodestore.id(name));
	}
}
