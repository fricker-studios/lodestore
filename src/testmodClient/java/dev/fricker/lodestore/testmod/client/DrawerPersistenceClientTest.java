package dev.fricker.lodestore.testmod.client;

import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;

import dev.fricker.lodestore.block.DrawerBlockEntity;
import dev.fricker.lodestore.registry.LodestoreBlocks;

/**
 * Quits to the title screen and reopens the same save, which is the disconnect a singleplayer
 * player actually performs.
 *
 * <p>Server gametests cannot cover this: they never round-trip a drawer through the save file, so
 * anything that only goes wrong across a save and load is invisible to them.
 */
public class DrawerPersistenceClientTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("lodestore-persistence-gametest");
	private static final BlockPos POS = new BlockPos(0, 100, 0);
	private static final int EXPECTED = 7;

	@Override
	public void runTest(ClientGameTestContext context) {
		TestWorldSave save;

		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			save = singleplayer.getWorldSave();
			singleplayer.getServer().runCommand("forceload add 0 0");

			singleplayer.getServer().runOnServer(server -> {
				ServerLevel level = server.overworld();
				level.setBlockAndUpdate(POS, LodestoreBlocks.BASIC_DRAWER.defaultBlockState());

				if (level.getBlockEntity(POS) instanceof DrawerBlockEntity drawer) {
					drawer.contents().setItem(0, new ItemStack(Items.DIAMOND, EXPECTED));
					drawer.setChanged();
					LOGGER.info("[lodestore] before disconnect: {} diamonds", drawer.contents().visible(0).getCount());
				} else {
					throw new AssertionError("drawer block entity missing right after placement");
				}
			});

			context.waitTicks(40);
		}

		// Reopening the save is the reconnect.
		try (TestSingleplayerContext reopened = save.open()) {
			reopened.getServer().runCommand("forceload add 0 0");
			context.waitTicks(40);

			AtomicInteger found = new AtomicInteger(-1);

			reopened.getServer().runOnServer(server -> {
				ServerLevel level = server.overworld();

				if (level.getBlockEntity(POS) instanceof DrawerBlockEntity drawer) {
					found.set(drawer.contents().visible(0).getCount());
				} else {
					found.set(-2);
				}
			});

			LOGGER.info("[lodestore] after reconnect: {} diamonds", found.get());

			if (found.get() == -2) {
				throw new AssertionError("the drawer block entity did not survive the reload at all");
			}

			if (found.get() != EXPECTED) {
				throw new AssertionError("drawer held " + EXPECTED + " diamonds before disconnect, "
						+ found.get() + " after reconnect");
			}
		}
	}
}
