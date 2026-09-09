package dev.fricker.lodestore.client;

import net.fabricmc.api.ClientModInitializer;

import dev.fricker.lodestore.Lodestore;

/** Client-only entrypoint. Rendering and screens will be registered here in later phases. */
public class LodestoreClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		Lodestore.LOGGER.info("[lodestore] client init complete");
	}
}
