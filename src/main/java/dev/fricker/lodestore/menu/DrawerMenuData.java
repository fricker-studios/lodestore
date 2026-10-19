package dev.fricker.lodestore.menu;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * What the client needs to rebuild a drawer's menu, sent with the open packet.
 *
 * <p>Rows and the upgrade slot count are server-side facts the client cannot derive — one comes
 * from the block, the other from the datapack-driven tier — and a client that guessed either would
 * lay out the wrong number of slots.
 *
 * <p>The position is here so the screen can read the drawer's own block entity for which slots the
 * upgrades target. That state is already synced to every client near the block, so sending it
 * again through the menu would be a second copy to keep in step with the first.
 *
 * @param rows         storage rows, nine slots each
 * @param upgradeSlots how many upgrade slots this drawer's tier offers
 * @param pos          where the drawer is, for reading its targeting client-side
 */
public record DrawerMenuData(int rows, int upgradeSlots, BlockPos pos) {
	public static final StreamCodec<RegistryFriendlyByteBuf, DrawerMenuData> STREAM_CODEC =
			StreamCodec.composite(
					ByteBufCodecs.VAR_INT, DrawerMenuData::rows,
					ByteBufCodecs.VAR_INT, DrawerMenuData::upgradeSlots,
					BlockPos.STREAM_CODEC, DrawerMenuData::pos,
					DrawerMenuData::new);
}
