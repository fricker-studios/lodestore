package dev.fricker.lodestore.item;

import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.Block;

import dev.fricker.lodestore.registry.LodestoreComponents;
import dev.fricker.lodestore.storage.CrateOverflow;

/**
 * A crate in item form.
 *
 * <p>Exists for its tooltip. Vanilla draws {@code minecraft:container}'s contents on any item that
 * carries it, but it reaches that component by name from a fixed list, so a component of ours is
 * never asked. A broken crate whose slots were holding more than a stack would otherwise show the
 * stacks and say nothing about the rest, which is exactly the kind of quiet undercount a player
 * would eventually mistake for loss. The upgrades it carries are listed for the same reason: they
 * stay in the crate when it is broken, and nothing else would say they are there.
 */
public class CrateBlockItem extends BlockItem {
	public CrateBlockItem(Block block, Properties properties) {
		super(block, properties);
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
			Consumer<Component> tooltip, TooltipFlag flag) {
		super.appendHoverText(stack, context, display, tooltip, flag);

		CrateOverflow overflow = stack.get(LodestoreComponents.CRATE_OVERFLOW);

		if (overflow != null && !overflow.isEmpty() && display.shows(LodestoreComponents.CRATE_OVERFLOW)) {
			tooltip.accept(Component.translatable("lodestore.crate.overflow", overflow.total())
					.withStyle(ChatFormatting.GRAY));
		}

		InstalledUpgrades fitted = stack.get(LodestoreComponents.CRATE_UPGRADES);

		if (fitted != null && !fitted.upgrades().isEmpty() && display.shows(LodestoreComponents.CRATE_UPGRADES)) {
			tooltip.accept(Component.translatable("lodestore.crate.upgrades").withStyle(ChatFormatting.GRAY));

			for (ItemStack upgrade : fitted.upgradeStacks()) {
				tooltip.accept(Component.literal("  ").append(upgrade.getHoverName()).withStyle(ChatFormatting.GRAY));
			}
		}
	}
}
