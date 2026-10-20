package dev.fricker.lodestore.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import dev.fricker.lodestore.Lodestore;

/**
 * The server telling a client what the interface box it has open can see.
 *
 * <p>A payload of our own rather than the menu's data slots, which carry ints only, or its item
 * slots, which carry legal stacks only: a count on a network is a long, and a stack cannot say 4,000.
 * Addressed by container id so a packet that arrives after the screen has closed, or after another
 * has opened, is dropped rather than applied to the wrong menu.
 *
 * @param containerId the menu this view is for
 * @param view        what the box can see
 */
public record NetworkViewPayload(int containerId, NetworkView view) implements CustomPacketPayload {
	public static final Type<NetworkViewPayload> TYPE = new Type<>(Lodestore.id("network_view"));

	public static final StreamCodec<RegistryFriendlyByteBuf, NetworkViewPayload> STREAM_CODEC =
			StreamCodec.composite(
					ByteBufCodecs.VAR_INT, NetworkViewPayload::containerId,
					NetworkView.STREAM_CODEC, NetworkViewPayload::view,
					NetworkViewPayload::new);

	@Override
	public Type<NetworkViewPayload> type() {
		return TYPE;
	}
}
