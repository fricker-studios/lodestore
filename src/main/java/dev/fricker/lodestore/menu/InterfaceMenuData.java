package dev.fricker.lodestore.menu;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * What the client needs to rebuild an interface menu: where the box is, so the menu can tell
 * whether the player has walked away from it.
 *
 * @param pos where the interface box is
 */
public record InterfaceMenuData(BlockPos pos) {
	public static final StreamCodec<RegistryFriendlyByteBuf, InterfaceMenuData> STREAM_CODEC =
			StreamCodec.composite(BlockPos.STREAM_CODEC, InterfaceMenuData::pos, InterfaceMenuData::new);
}
