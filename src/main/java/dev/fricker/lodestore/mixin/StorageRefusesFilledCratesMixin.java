package dev.fricker.lodestore.mixin;

import org.spongepowered.asm.mixin.Mixin;

import net.minecraft.world.Container;
import net.minecraft.world.entity.vehicle.boat.AbstractChestBoat;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;

import dev.fricker.lodestore.item.CrateBlockItem;

/**
 * Vanilla's storage refuses a crate with something in it. A crate's contents nest only where
 * Lodestore's own nesting rule says they may, inside another crate.
 *
 * <p>Covers chests (trapped and copper ones are {@code ChestBlockEntity} too, and a double chest
 * asks both halves), barrels, shulker boxes, the ender chest, chest minecarts and chest boats. None
 * of them declares {@code canPlaceItem}, so this adds the method rather than replacing one, and
 * everything else they hold is still taken. Hoppers, droppers and pipes ask this; a player's hand
 * asks it through {@link SlotMixin}. Hoppers, droppers and crafters themselves are left alone:
 * they move a crate along, they do not keep it.
 */
@Mixin({ChestBlockEntity.class, BarrelBlockEntity.class, ShulkerBoxBlockEntity.class,
		PlayerEnderChestContainer.class, MinecartChest.class, AbstractChestBoat.class})
abstract class StorageRefusesFilledCratesMixin implements Container {
	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		return !CrateBlockItem.isFilled(stack);
	}
}
