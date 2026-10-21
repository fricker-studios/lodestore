package dev.fricker.lodestore.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;

import dev.fricker.lodestore.Lodestore;

/**
 * A client asking, through the interface box it has open, for some of one kind of item.
 *
 * <p>Names the kind rather than the cell: the view the client clicked may be a refresh behind the
 * one the server has since sent, and an index into the wrong view would hand over the wrong item.
 * A kind cannot be wrong, only absent. Addressed by container id, like the view coming the other
 * way, so a click that arrives after the screen has closed is dropped.
 *
 * @param containerId the menu the click came from
 * @param kind        the item, with its components
 * @param mode        how much, and where to
 */
public record NetworkPullPayload(int containerId, ItemVariant kind, PullMode mode) implements CustomPacketPayload {
	public static final Type<NetworkPullPayload> TYPE = new Type<>(Lodestore.id("network_pull"));

	public static final StreamCodec<RegistryFriendlyByteBuf, NetworkPullPayload> STREAM_CODEC =
			StreamCodec.composite(
					ByteBufCodecs.VAR_INT, NetworkPullPayload::containerId,
					ItemVariant.PACKET_CODEC, NetworkPullPayload::kind,
					PullMode.STREAM_CODEC, NetworkPullPayload::mode,
					NetworkPullPayload::new);

	@Override
	public Type<NetworkPullPayload> type() {
		return TYPE;
	}
}
