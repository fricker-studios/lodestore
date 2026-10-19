package dev.fricker.lodestore.client;

import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;

import net.fabricmc.api.ClientModInitializer;

import dev.fricker.lodestore.Lodestore;
import dev.fricker.lodestore.client.render.DrawerRenderer;
import dev.fricker.lodestore.client.screen.DrawerScreen;
import dev.fricker.lodestore.menu.LodestoreMenus;
import dev.fricker.lodestore.registry.LodestoreBlockEntities;

/** Client-only entrypoint. Rendering and screens will be registered here in later phases. */
public class LodestoreClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		BlockEntityRenderers.register(LodestoreBlockEntities.DRAWER, DrawerRenderer::new);

		// Drawers had used the vanilla chest screen, which cost nothing to maintain. Managing
		// upgrades after they are installed needs slots the chest screen has no room for.
		MenuScreens.register(LodestoreMenus.DRAWER, DrawerScreen::new);

		Lodestore.LOGGER.info("[lodestore] client init complete");
	}
}
