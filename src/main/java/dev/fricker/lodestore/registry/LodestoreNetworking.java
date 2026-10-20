package dev.fricker.lodestore.registry;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

import dev.fricker.lodestore.network.NetworkViewPayload;

/** Payload registration. The client's receiver is registered in {@code LodestoreClient}. */
public final class LodestoreNetworking {
	private LodestoreNetworking() {
	}

	public static void initialize() {
		PayloadTypeRegistry.clientboundPlay().register(NetworkViewPayload.TYPE, NetworkViewPayload.STREAM_CODEC);
	}
}
