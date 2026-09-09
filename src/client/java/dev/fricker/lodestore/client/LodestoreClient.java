package dev.fricker.lodestore.client;

import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;

import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;

import net.fabricmc.api.ClientModInitializer;

import dev.fricker.lodestore.Lodestore;
import dev.fricker.lodestore.client.render.DrawerRenderer;
import dev.fricker.lodestore.registry.LodestoreBlockEntities;
import dev.fricker.lodestore.client.render.DrawerRenderer;
import dev.fricker.lodestore.registry.LodestoreBlockEntities;

/** Client-only entrypoint. Rendering and screens will be registered here in later phases. */
public class LodestoreClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		BlockEntityRenderers.register(LodestoreBlockEntities.DRAWER, DrawerRenderer::new);

		// The drawers use the vanilla chest screen, so only the front face needs registering.
		BlockEntityRenderers.register(LodestoreBlockEntities.DRAWER, DrawerRenderer::new);

		Lodestore.LOGGER.info("[lodestore] client init complete");
	}
}
