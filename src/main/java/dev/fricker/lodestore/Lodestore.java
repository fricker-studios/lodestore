package dev.fricker.lodestore;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.event.registry.DynamicRegistries;

import dev.fricker.lodestore.api.LodestoreRegistries;
import dev.fricker.lodestore.api.drawer.DrawerTier;
import dev.fricker.lodestore.registry.LodestoreBlockEntities;
import dev.fricker.lodestore.registry.LodestoreBlocks;
import dev.fricker.lodestore.registry.LodestoreCreativeTab;

/**
 * Common (both-sides) entrypoint.
 *
 * <p>Minecraft 26.x ships unobfuscated, so all Minecraft types are referenced by their official
 * names (e.g. {@code net.minecraft.resources.Identifier}). There is no remapping step.
 */
public class Lodestore implements ModInitializer {
	public static final String MOD_ID = "lodestore";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// Synced, because the client needs capacities for the upcoming front-face count overlay.
		DynamicRegistries.registerSynced(LodestoreRegistries.DRAWER_TIER, DrawerTier.CODEC);

		LodestoreBlocks.initialize();
		LodestoreBlockEntities.initialize();
		LodestoreCreativeTab.initialize();

		// Dev-only diagnostic. Never registered in a shipped jar.
		if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
			DebugCommand.register();
		}

		LOGGER.info("[lodestore] common init complete");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
