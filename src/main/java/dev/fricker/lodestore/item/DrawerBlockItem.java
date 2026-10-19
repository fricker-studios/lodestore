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
import dev.fricker.lodestore.storage.DrawerOverflow;

/**
 * A drawer in item form.
 *
 * <p>Exists for one line of tooltip. Vanilla draws {@code minecraft:container}'s contents on any
 * item that carries it, but it reaches that component by name from a fixed list, so a component of
 * ours is never asked. A broken drawer whose slots were holding more than a stack would otherwise
 * show the stacks and say nothing about the rest, which is exactly the kind of quiet undercount a
 * player would eventually mistake for loss.
 */
public class DrawerBlockItem extends BlockItem {
	public DrawerBlockItem(Block block, Properties properties) {
		super(block, properties);
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
			Consumer<Component> tooltip, TooltipFlag flag) {
		super.appendHoverText(stack, context, display, tooltip, flag);

		DrawerOverflow overflow = stack.get(LodestoreComponents.DRAWER_OVERFLOW);

		if (overflow != null && !overflow.isEmpty() && display.shows(LodestoreComponents.DRAWER_OVERFLOW)) {
			tooltip.accept(Component.translatable("lodestore.drawer.overflow", overflow.total())
					.withStyle(ChatFormatting.GRAY));
		}
	}
}
