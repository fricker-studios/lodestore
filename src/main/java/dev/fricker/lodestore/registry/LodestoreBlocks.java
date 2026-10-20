package dev.fricker.lodestore.registry;

import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

import dev.fricker.lodestore.Lodestore;
import dev.fricker.lodestore.api.crate.CrateTier;
import dev.fricker.lodestore.block.CrateBlock;
import dev.fricker.lodestore.item.CrateBlockItem;
import dev.fricker.lodestore.item.InstalledUpgrades;
import dev.fricker.lodestore.storage.CrateOverflow;

/**
 * Block registration.
 *
 * <p>In 26.x every block and item must carry its own {@link ResourceKey} via
 * {@code Properties.setId(...)} before registration — this is not optional.
 */
public final class LodestoreBlocks {
	public static final ResourceKey<Block> BASIC_CRATE_KEY = blockKey("basic_crate");
	/** Single chest. */
	public static final CrateBlock BASIC_CRATE = crate(BASIC_CRATE_KEY, 3, LodestoreCrateTiers.BASIC);

	public static final ResourceKey<Block> STURDY_CRATE_KEY = blockKey("sturdy_crate");
	/** Single chest, shulker-style: keeps its contents when broken. */
	public static final CrateBlock STURDY_CRATE = crate(STURDY_CRATE_KEY, 3, LodestoreCrateTiers.STURDY);

	public static final ResourceKey<Block> REINFORCED_CRATE_KEY = blockKey("reinforced_crate");
	/** Double chest, shulker-style, and the only tier that may hold another crate. */
	public static final CrateBlock REINFORCED_CRATE = crate(REINFORCED_CRATE_KEY, 6, LodestoreCrateTiers.REINFORCED);

	private LodestoreBlocks() {
	}

	/** Forces class-loading and performs registration. */
	public static void initialize() {
		register(BASIC_CRATE_KEY, BASIC_CRATE);
		register(STURDY_CRATE_KEY, STURDY_CRATE);
		register(REINFORCED_CRATE_KEY, REINFORCED_CRATE);
	}

	private static CrateBlock crate(ResourceKey<Block> key, int rows, ResourceKey<CrateTier> tier) {
		return new CrateBlock(
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
	 * Breaking a crate copies the block entity's implicit components onto the dropped item, and
	 * the crate writes that component whether or not it held anything. Without the same component
	 * on the item to begin with, a crate that has been placed once carries a patch a never-placed
	 * one does not, the two stop stacking in the inventory, and the player ends up with two piles
	 * of identical crates. Declaring it here makes the copied value equal the prototype, which
	 * {@code PatchedDataComponentMap} then drops instead of recording.
	 *
	 * <p>The overflow component — the counts parked behind full stacks — and the installed upgrades
	 * get the same treatment for the same reason, even though the crate only writes either when
	 * there is something to say.
	 *
	 * <p>All of them go on every tier, not just the two that keep their contents: which tiers those are is
	 * datapack-driven and the loot tables are data too, so the item side stays permissive rather
	 * than encoding an assumption a pack can change.
	 */
	private static void register(ResourceKey<Block> key, Block block) {
		Registry.register(BuiltInRegistries.BLOCK, key, block);
		Registry.register(
				BuiltInRegistries.ITEM,
				key.identifier(),
				new CrateBlockItem(block, new Item.Properties()
						.setId(ResourceKey.create(Registries.ITEM, key.identifier()))
						.component(DataComponents.CONTAINER, ItemContainerContents.EMPTY)
						.component(LodestoreComponents.CRATE_OVERFLOW, CrateOverflow.EMPTY)
						.component(LodestoreComponents.CRATE_UPGRADES, InstalledUpgrades.EMPTY))
		);
	}
}
