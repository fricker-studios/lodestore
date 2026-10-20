package dev.fricker.lodestore;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.registry.DynamicRegistries;

import dev.fricker.lodestore.api.LodestoreRegistries;
import dev.fricker.lodestore.api.crate.CrateTier;
import dev.fricker.lodestore.api.crate.CrateUpgrade;
import dev.fricker.lodestore.registry.LodestoreAliases;
import dev.fricker.lodestore.registry.LodestoreBlockEntities;
import dev.fricker.lodestore.registry.LodestoreBlocks;
import dev.fricker.lodestore.registry.LodestoreComponents;
import dev.fricker.lodestore.registry.LodestoreCreativeTab;
import dev.fricker.lodestore.menu.LodestoreMenus;
import dev.fricker.lodestore.registry.LodestoreItems;
import dev.fricker.lodestore.registry.LodestoreNetworking;
import dev.fricker.lodestore.util.PersistenceSelfCheck;

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
		DynamicRegistries.registerSynced(LodestoreRegistries.CRATE_TIER, CrateTier.CODEC);
		DynamicRegistries.registerSynced(LodestoreRegistries.CRATE_UPGRADE, CrateUpgrade.CODEC);

		// Before the blocks: the crate items declare the overflow component as a default.
		LodestoreComponents.initialize();
		LodestoreBlocks.initialize();
		LodestoreItems.initialize();
		LodestoreBlockEntities.initialize();
		LodestoreMenus.initialize();
		LodestoreNetworking.initialize();
		LodestoreCreativeTab.initialize();
		// Last, so every id it redirects to already exists.
		LodestoreAliases.initialize();

		// A container that never writes its contents fails silently — nothing throws and
		// nothing looks wrong until a reload. Check once per server start, on both a
		// dedicated server and singleplayer's integrated one.
		ServerLifecycleEvents.SERVER_STARTED.register(server ->
				PersistenceSelfCheck.runAndReport(server.registryAccess()));

		LOGGER.info("[lodestore] common init complete");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
