package dev.fricker.lodestore.network;

import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * What an interface box shows: how many crates it found, and what they hold.
 *
 * <p>The crate count is here for the empty screen, which otherwise could not say whether there is
 * nothing stored or nothing connected — and a player who has just laid a run of connection blocks
 * that missed by one block needs to be told the second.
 *
 * @param crates  how many crates the walk reached
 * @param entries one per kind of item, most numerous first
 */
public record NetworkView(int crates, List<NetworkEntry> entries) {
	public static final NetworkView EMPTY = new NetworkView(0, List.of());

	public static final StreamCodec<RegistryFriendlyByteBuf, NetworkView> STREAM_CODEC =
			StreamCodec.composite(
					ByteBufCodecs.VAR_INT, NetworkView::crates,
					NetworkEntry.STREAM_CODEC.apply(ByteBufCodecs.list()), NetworkView::entries,
					NetworkView::new);

	/** Whether anything at all is stored. */
	public boolean isEmpty() {
		return entries.isEmpty();
	}
}
