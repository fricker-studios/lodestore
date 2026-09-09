package dev.fricker.lodestore.testmod;

import net.fabricmc.api.ModInitializer;

/**
 * Entrypoint for the Lodestore test mod.
 *
 * <p>This source set is never part of the published jar, so the diagnostics it registers cannot
 * reach a player. That is a stronger guarantee than the {@code isDevelopmentEnvironment()} check
 * this replaces, which kept the command from running but still shipped its classes.
 */
public class LodestoreTestmod implements ModInitializer {
	@Override
	public void onInitialize() {
		DebugCommand.register();
	}
}
