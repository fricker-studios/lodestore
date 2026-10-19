package dev.fricker.lodestore.registry;

import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

import dev.fricker.lodestore.Lodestore;
import dev.fricker.lodestore.api.drawer.DrawerTier;
import dev.fricker.lodestore.block.DrawerBlock;

/**
 * Block registration.
 *
 * <p>In 26.x every block and item must carry its own {@link ResourceKey} via
 * {@code Properties.setId(...)} before registration — this is not optional.
 */
public final class LodestoreBlocks {
	public static final ResourceKey<Block> BASIC_DRAWER_KEY = blockKey("basic_drawer");
	/** Single chest. */
	public static final DrawerBlock BASIC_DRAWER = drawer(BASIC_DRAWER_KEY, 3, LodestoreDrawerTiers.BASIC);

	public static final ResourceKey<Block> STURDY_DRAWER_KEY = blockKey("sturdy_drawer");
	/** Single chest, shulker-style: keeps its contents when broken. */
	public static final DrawerBlock STURDY_DRAWER = drawer(STURDY_DRAWER_KEY, 3, LodestoreDrawerTiers.STURDY);

	public static final ResourceKey<Block> REINFORCED_DRAWER_KEY = blockKey("reinforced_drawer");
	/** Double chest, shulker-style, and the only tier that may hold another drawer. */
	public static final DrawerBlock REINFORCED_DRAWER = drawer(REINFORCED_DRAWER_KEY, 6, LodestoreDrawerTiers.REINFORCED);

	private LodestoreBlocks() {
	}

	/** Forces class-loading and performs registration. */
	public static void initialize() {
		register(BASIC_DRAWER_KEY, BASIC_DRAWER);
		register(STURDY_DRAWER_KEY, STURDY_DRAWER);
		register(REINFORCED_DRAWER_KEY, REINFORCED_DRAWER);
	}

	private static DrawerBlock drawer(ResourceKey<Block> key, int rows, ResourceKey<DrawerTier> tier) {
		return new DrawerBlock(
				BlockBehaviour.Properties.of()
						.setId(key)
						.strength(2.5f)
						.sound(SoundType.WOOD),
				rows,
				tier
		);
	}

	private static ResourceKey<Block> blockKey(String name) {
		return ResourceKey.create(Registries.BLOCK, Lodestore.id(name));
	}

	/**
	 * Registers the block and its item.
	 *
	 * <p>The item carries an empty {@code minecraft:container} by default, which is not cosmetic.
	 * Breaking a drawer copies the block entity's implicit components onto the dropped item, and
	 * {@link net.minecraft.world.level.block.entity.BaseContainerBlockEntity} writes that component
	 * whether or not the drawer held anything. Without the same component on the item to begin
	 * with, a drawer that has been placed once carries a patch a never-placed one does not, the two
	 * stop stacking in the inventory, and the player ends up with two piles of identical drawers.
	 * Declaring it here makes the copied value equal the prototype, which
	 * {@code PatchedDataComponentMap} then drops instead of recording.
	 *
	 * <p>It goes on every tier, not just the two that keep their contents: which tiers those are is
	 * datapack-driven and the loot tables are data too, so the item side stays permissive rather
	 * than encoding an assumption a pack can change.
	 */
	private static void register(ResourceKey<Block> key, Block block) {
		Registry.register(BuiltInRegistries.BLOCK, key, block);
		Registry.register(
				BuiltInRegistries.ITEM,
				key.identifier(),
				new BlockItem(block, new Item.Properties()
						.setId(ResourceKey.create(Registries.ITEM, key.identifier()))
						.component(DataComponents.CONTAINER, ItemContainerContents.EMPTY))
		);
	}
}
