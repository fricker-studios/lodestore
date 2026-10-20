package dev.fricker.lodestore.client;

import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import dev.fricker.lodestore.Lodestore;
import dev.fricker.lodestore.client.render.CrateRenderer;
import dev.fricker.lodestore.client.screen.CrateScreen;
import dev.fricker.lodestore.client.screen.InterfaceScreen;
import dev.fricker.lodestore.menu.InterfaceMenu;
import dev.fricker.lodestore.menu.LodestoreMenus;
import dev.fricker.lodestore.network.NetworkViewPayload;
import dev.fricker.lodestore.registry.LodestoreBlockEntities;

/** Client-only entrypoint: renderers, screens, and the receiver for the network view. */
public class LodestoreClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		BlockEntityRenderers.register(LodestoreBlockEntities.CRATE, CrateRenderer::new);

		// Crates had used the vanilla chest screen, which cost nothing to maintain. Managing
		// upgrades after they are installed needs slots the chest screen has no room for.
		MenuScreens.register(LodestoreMenus.CRATE, CrateScreen::new);
		MenuScreens.register(LodestoreMenus.INTERFACE, InterfaceScreen::new);

		// The view lands on the menu rather than the screen: the menu is what the container id
		// names, and a packet for a menu the player no longer has open is dropped here.
		ClientPlayNetworking.registerGlobalReceiver(NetworkViewPayload.TYPE, (payload, context) -> {
			if (context.player().containerMenu instanceof InterfaceMenu menu
					&& menu.containerId == payload.containerId()) {
				menu.setView(payload.view());
			}
		});

		Lodestore.LOGGER.info("[lodestore] client init complete");
	}
}
