package dev.fricker.lodestore.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;

import dev.fricker.lodestore.item.CrateBlockItem;

/** A bundle refuses a crate with something in it, as it refuses a shulker box. */
@Mixin(BundleContents.class)
abstract class BundleContentsMixin {
	@Inject(method = "canItemBeInBundle", at = @At("HEAD"), cancellable = true)
	private static void lodestore$refuseFilledCrates(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
		if (CrateBlockItem.isFilled(stack)) {
			cir.setReturnValue(false);
		}
	}
}
