package dev.fricker.lodestore.registry;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;

import dev.fricker.lodestore.Lodestore;

/** Lodestore's own creative tab. Backpacks and network devices will join it in later phases. */
public final class LodestoreCreativeTab {
	public static final ResourceKey<CreativeModeTab> KEY =
			ResourceKey.create(Registries.CREATIVE_MODE_TAB, Lodestore.id("general"));

	private LodestoreCreativeTab() {
	}

	/** Must run after {@link LodestoreBlocks#initialize()}. */
	public static void initialize() {
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, KEY, FabricCreativeModeTab.builder()
				.title(Component.translatable("itemGroup.lodestore.general"))
				.icon(() -> new ItemStack(LodestoreBlocks.BASIC_CRATE))
				.displayItems((context, entries) -> {
					entries.accept(LodestoreBlocks.BASIC_CRATE);
					entries.accept(LodestoreBlocks.STURDY_CRATE);
					entries.accept(LodestoreBlocks.REINFORCED_CRATE);
					entries.accept(LodestoreItems.LOCK_UPGRADE);
					entries.accept(LodestoreItems.VOID_UPGRADE);
					entries.accept(LodestoreItems.STACK_UPGRADE);
					entries.accept(LodestoreItems.STACK_UPGRADE_2);
					entries.accept(LodestoreItems.STACK_UPGRADE_3);
				})
				.build());
	}
}
