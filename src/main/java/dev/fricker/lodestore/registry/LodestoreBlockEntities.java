package dev.fricker.lodestore.registry;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;

import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;

import dev.fricker.lodestore.Lodestore;
import dev.fricker.lodestore.block.CrateBlockEntity;

public final class LodestoreBlockEntities {
	public static BlockEntityType<CrateBlockEntity> CRATE;

	private LodestoreBlockEntities() {
	}

	/** Must run after {@link LodestoreBlocks#initialize()}, since the type binds to those blocks. */
	public static void initialize() {
		CRATE = Registry.register(
				BuiltInRegistries.BLOCK_ENTITY_TYPE,
				Lodestore.id("crate"),
				FabricBlockEntityTypeBuilder.create(
						CrateBlockEntity::new,
						LodestoreBlocks.BASIC_CRATE,
						LodestoreBlocks.STURDY_CRATE,
						LodestoreBlocks.REINFORCED_CRATE
				).build()
		);
	}
}
