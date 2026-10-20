package dev.fricker.lodestore.registry;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;

import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;

import dev.fricker.lodestore.Lodestore;
import dev.fricker.lodestore.block.CrateBlockEntity;
import dev.fricker.lodestore.block.DepositBoxBlockEntity;
import dev.fricker.lodestore.block.InterfaceBoxBlockEntity;

public final class LodestoreBlockEntities {
	public static BlockEntityType<CrateBlockEntity> CRATE;
	public static BlockEntityType<DepositBoxBlockEntity> DEPOSIT_BOX;
	public static BlockEntityType<InterfaceBoxBlockEntity> INTERFACE_BOX;

	private LodestoreBlockEntities() {
	}

	/** Must run after {@link LodestoreBlocks#initialize()}, since the types bind to those blocks. */
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
		DEPOSIT_BOX = Registry.register(
				BuiltInRegistries.BLOCK_ENTITY_TYPE,
				Lodestore.id("deposit_box"),
				FabricBlockEntityTypeBuilder.create(DepositBoxBlockEntity::new, LodestoreBlocks.DEPOSIT_BOX).build()
		);
		INTERFACE_BOX = Registry.register(
				BuiltInRegistries.BLOCK_ENTITY_TYPE,
				Lodestore.id("interface_box"),
				FabricBlockEntityTypeBuilder.create(InterfaceBoxBlockEntity::new, LodestoreBlocks.INTERFACE_BOX).build()
		);
	}
}
