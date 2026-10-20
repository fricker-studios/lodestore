package dev.fricker.lodestore.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.inventory.ShulkerBoxSlot;
import net.minecraft.world.item.ItemStack;

import dev.fricker.lodestore.item.CrateBlockItem;

/**
 * Keeps a crate with something in it out of a shulker box's screen.
 *
 * <p>The shulker box slot overrides {@code mayPlace} without calling up, so {@link SlotMixin} never
 * reaches it. Vanilla's own check here, {@code Item#canFitInsideContainerItems}, is asked of the
 * item rather than the stack, and so cannot tell a filled crate from an empty one, which is still
 * welcome.
 */
@Mixin(ShulkerBoxSlot.class)
abstract class ShulkerBoxSlotMixin {
	@Inject(method = "mayPlace", at = @At("HEAD"), cancellable = true)
	private void lodestore$refuseFilledCrates(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
		if (CrateBlockItem.isFilled(stack)) {
			cir.setReturnValue(false);
		}
	}
}
