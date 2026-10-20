package dev.fricker.lodestore.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;

/**
 * One kind of item on a network, and how many of it there are across every crate.
 *
 * <p>An {@link ItemVariant} rather than an {@code ItemStack}, because the interface menu decides
 * whether to send a fresh view by comparing it with the last one sent, and a stack compares by
 * identity. A variant compares by item and components, which is what "the same kind" means.
 *
 * @param kind  the item, with its components
 * @param count how many, summed over every slot of every crate, parked remainders included
 */
public record NetworkEntry(ItemVariant kind, long count) {
	public static final StreamCodec<RegistryFriendlyByteBuf, NetworkEntry> STREAM_CODEC =
			StreamCodec.composite(
					ItemVariant.PACKET_CODEC, NetworkEntry::kind,
					ByteBufCodecs.VAR_LONG, NetworkEntry::count,
					NetworkEntry::new);
}
