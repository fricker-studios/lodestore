package dev.fricker.lodestore.registry;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import dev.fricker.lodestore.menu.InterfaceMenu;
import dev.fricker.lodestore.network.NetworkPullPayload;
import dev.fricker.lodestore.network.NetworkViewPayload;

/**
 * Payload registration, both ways, and the server's receiver for a pull through the interface
 * box. The client's receiver for the view is registered in {@code LodestoreClient}.
 */
public final class LodestoreNetworking {
	private LodestoreNetworking() {
	}

	public static void initialize() {
		PayloadTypeRegistry.clientboundPlay().register(NetworkViewPayload.TYPE, NetworkViewPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(NetworkPullPayload.TYPE, NetworkPullPayload.STREAM_CODEC);

		// Only the menu the player actually has open may act on it, and only the interface box's:
		// a stale or forged container id reaches nothing. Runs on the server thread.
		ServerPlayNetworking.registerGlobalReceiver(NetworkPullPayload.TYPE, (payload, context) -> {
			if (context.player().containerMenu instanceof InterfaceMenu menu
					&& menu.containerId == payload.containerId()) {
				menu.pull(context.player(), payload.kind(), payload.mode());
			}
		});
	}
}
