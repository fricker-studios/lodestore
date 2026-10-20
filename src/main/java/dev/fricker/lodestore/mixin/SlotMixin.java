package dev.fricker.lodestore.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import dev.fricker.lodestore.item.CrateBlockItem;

/**
 * Makes a plain menu slot ask its container before it takes a crate with something in it.
 *
 * <p>Vanilla's {@code Slot#mayPlace} answers yes to everything and never consults
 * {@code Container#canPlaceItem}, which only hoppers ask. So the storage that refuses a filled
 * crate — see {@link StorageRefusesFilledCratesMixin} — would refuse a hopper and still take the
 * same crate from a player's hand. Asking only for filled crates leaves every other item, and every
 * container that has never heard of crates, exactly as vanilla has them.
 */
@Mixin(Slot.class)
abstract class SlotMixin {
	@Shadow
	@Final
	public Container container;

	@Shadow
	public abstract int getContainerSlot();

	@Inject(method = "mayPlace", at = @At("HEAD"), cancellable = true)
	private void lodestore$askBeforeTakingAFilledCrate(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
		if (CrateBlockItem.isFilled(stack) && !container.canPlaceItem(getContainerSlot(), stack)) {
			cir.setReturnValue(false);
		}
	}
}
