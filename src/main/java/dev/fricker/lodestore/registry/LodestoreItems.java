package dev.fricker.lodestore.registry;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

import dev.fricker.lodestore.Lodestore;
import dev.fricker.lodestore.item.CrateUpgradeItem;

/**
 * Standalone item registration. Block items are registered alongside their blocks in
 * {@link LodestoreBlocks}; this is for items that are not a block in hand.
 */
public final class LodestoreItems {
	public static final ResourceKey<Item> LOCK_UPGRADE_KEY = itemKey("lock_upgrade");
	/**
	 * Locks a whole crate to what it already holds, however many kinds that is — or, if it is
	 * empty, to the first thing put in.
	 */
	public static final CrateUpgradeItem LOCK_UPGRADE = new CrateUpgradeItem(
			new Item.Properties().setId(LOCK_UPGRADE_KEY), LodestoreCrateUpgrades.LOCK);

	public static final ResourceKey<Item> VOID_UPGRADE_KEY = itemKey("void_upgrade");
	/**
	 * Locks a crate and then destroys what will not fit, so a filling machine never backs up.
	 *
	 * <p>Locks as well as voids, rather than only voiding, so that one item is enough to set a
	 * crate up and the thing it destroys is always something the player named.
	 */
	public static final CrateUpgradeItem VOID_UPGRADE = new CrateUpgradeItem(
			new Item.Properties().setId(VOID_UPGRADE_KEY), LodestoreCrateUpgrades.VOID);

	public static final ResourceKey<Item> STACK_UPGRADE_KEY = itemKey("stack_upgrade");
	/**
	 * Every slot holds several stacks: four, for this level.
	 *
	 * <p>The one upgrade that could not exist while a crate was a vanilla {@code Container}, and
	 * the reason Lodestore grew its own storage layer. See the README.
	 */
	public static final CrateUpgradeItem STACK_UPGRADE = new CrateUpgradeItem(
			new Item.Properties().setId(STACK_UPGRADE_KEY), LodestoreCrateUpgrades.STACK);

	public static final ResourceKey<Item> STACK_UPGRADE_2_KEY = itemKey("stack_upgrade_2");
	/** Eight stacks a slot where the first level holds four. Same behaviour, deeper. */
	public static final CrateUpgradeItem STACK_UPGRADE_2 = new CrateUpgradeItem(
			new Item.Properties().setId(STACK_UPGRADE_2_KEY), LodestoreCrateUpgrades.STACK_2);

	public static final ResourceKey<Item> STACK_UPGRADE_3_KEY = itemKey("stack_upgrade_3");
	public static final CrateUpgradeItem STACK_UPGRADE_3 = new CrateUpgradeItem(
			new Item.Properties().setId(STACK_UPGRADE_3_KEY), LodestoreCrateUpgrades.STACK_3);

	public static final ResourceKey<Item> COMPRESS_UPGRADE_KEY = itemKey("compress_upgrade");
	/**
	 * Crafts what the crate holds into its densest form — nuggets into ingots, ingots into
	 * blocks — along pathways that craft back the other way, and no others.
	 */
	public static final CrateUpgradeItem COMPRESS_UPGRADE = new CrateUpgradeItem(
			new Item.Properties().setId(COMPRESS_UPGRADE_KEY), LodestoreCrateUpgrades.COMPRESS);

	public static final ResourceKey<Item> DISENCHANT_UPGRADE_KEY = itemKey("disenchant_upgrade");
	/**
	 * Strips the enchantments off whatever is put in the crate and banks the experience they were
	 * worth, which a right-click on the installed upgrade hands over.
	 */
	public static final CrateUpgradeItem DISENCHANT_UPGRADE = new CrateUpgradeItem(
			new Item.Properties().setId(DISENCHANT_UPGRADE_KEY), LodestoreCrateUpgrades.DISENCHANT);

	public static final ResourceKey<Item> REPAIR_UPGRADE_KEY = itemKey("repair_upgrade");
	/** Combines worn, unenchanted tools and armour of a kind into fewer, fuller ones. */
	public static final CrateUpgradeItem REPAIR_UPGRADE = new CrateUpgradeItem(
			new Item.Properties().setId(REPAIR_UPGRADE_KEY), LodestoreCrateUpgrades.REPAIR);

	private LodestoreItems() {
	}

	/** Forces class-loading and performs registration. */
	public static void initialize() {
		Registry.register(BuiltInRegistries.ITEM, LOCK_UPGRADE_KEY, LOCK_UPGRADE);
		Registry.register(BuiltInRegistries.ITEM, VOID_UPGRADE_KEY, VOID_UPGRADE);
		Registry.register(BuiltInRegistries.ITEM, STACK_UPGRADE_KEY, STACK_UPGRADE);
		Registry.register(BuiltInRegistries.ITEM, STACK_UPGRADE_2_KEY, STACK_UPGRADE_2);
		Registry.register(BuiltInRegistries.ITEM, STACK_UPGRADE_3_KEY, STACK_UPGRADE_3);
		Registry.register(BuiltInRegistries.ITEM, COMPRESS_UPGRADE_KEY, COMPRESS_UPGRADE);
		Registry.register(BuiltInRegistries.ITEM, DISENCHANT_UPGRADE_KEY, DISENCHANT_UPGRADE);
		Registry.register(BuiltInRegistries.ITEM, REPAIR_UPGRADE_KEY, REPAIR_UPGRADE);
	}

	private static ResourceKey<Item> itemKey(String name) {
		return ResourceKey.create(Registries.ITEM, Lodestore.id(name));
	}
}
