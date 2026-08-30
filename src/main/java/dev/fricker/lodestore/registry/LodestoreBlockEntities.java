package dev.fricker.lodestore.registry;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;

import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;

import dev.fricker.lodestore.Lodestore;
import dev.fricker.lodestore.block.DrawerBlockEntity;

public final class LodestoreBlockEntities {
	public static BlockEntityType<DrawerBlockEntity> DRAWER;

	private LodestoreBlockEntities() {
	}

	/** Must run after {@link LodestoreBlocks#initialize()}, since the type binds to those blocks. */
	public static void initialize() {
		DRAWER = Registry.register(
				BuiltInRegistries.BLOCK_ENTITY_TYPE,
				Lodestore.id("drawer"),
				FabricBlockEntityTypeBuilder.create(
						DrawerBlockEntity::new,
						LodestoreBlocks.BASIC_DRAWER,
						LodestoreBlocks.STURDY_DRAWER,
						LodestoreBlocks.REINFORCED_DRAWER
				).build()
		);
	}
}
