package dev.fricker.lodestore.client.gametest;

import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;

import dev.fricker.lodestore.block.DrawerBlock;
import dev.fricker.lodestore.block.DrawerBlockEntity;
import dev.fricker.lodestore.registry.LodestoreBlocks;

/**
 * Builds a row of filled drawers, points the camera at them, and screenshots the result.
 *
 * <p>The front-face renderer is the one part of the mod that cannot be checked by an assertion:
 * the failure modes are "icon in the wrong place", "facing the wrong way", and "invisible", none of
 * which a server-side test can see. This produces an image to look at instead.
 */
public class DrawerRenderClientTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("lodestore-client-gametest");
	private static final int Y = 100;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getServer().runCommand("gamerule doDaylightCycle false");
			singleplayer.getServer().runCommand("time set noon");
			singleplayer.getServer().runCommand("weather clear");
			singleplayer.getServer().runCommand("gamemode spectator @p");
			// Stand back and look north at the row of drawers.
			singleplayer.getServer().runCommand("tp @p 0.5 " + (Y + 0.6) + " 4.5 180 0");

			context.waitTicks(20);

			singleplayer.getServer().runOnServer(server -> {
				ServerLevel level = server.overworld();
				// A dark backdrop so the icons read clearly against something.
				for (int x = -4; x <= 4; x++) {
					for (int y = Y - 2; y <= Y + 2; y++) {
						level.setBlockAndUpdate(new BlockPos(x, y, -1),
								net.minecraft.world.level.block.Blocks.DEEPSLATE_BRICKS.defaultBlockState());
					}
				}

				fill(level, new BlockPos(-2, Y, 0), LodestoreBlocks.BASIC_DRAWER, Items.COBBLESTONE, 500);
				fill(level, new BlockPos(0, Y, 0), LodestoreBlocks.STURDY_DRAWER, Items.DIAMOND, 2000);
				fill(level, new BlockPos(2, Y, 0), LodestoreBlocks.REINFORCED_DRAWER, Items.OAK_LOG, 8000);

				// An empty drawer, to confirm nothing is drawn when there is nothing inside.
				level.setBlockAndUpdate(new BlockPos(-4, Y, 0),
						LodestoreBlocks.BASIC_DRAWER.defaultBlockState()
								.setValue(DrawerBlock.FACING, net.minecraft.core.Direction.SOUTH));
			});

			context.waitTicks(220);

			Path shot = context.takeScreenshot("drawer-front-face");
			LOGGER.info("[lodestore] screenshot written to {}", shot);
		}
	}

	private static void fill(ServerLevel level, BlockPos pos, Block block, Item item, long amount) {
		level.setBlockAndUpdate(pos, block.defaultBlockState()
				.setValue(DrawerBlock.FACING, net.minecraft.core.Direction.SOUTH));

		if (level.getBlockEntity(pos) instanceof DrawerBlockEntity drawer) {
			try (Transaction tx = Transaction.openOuter()) {
				drawer.slots().getFirst().insert(ItemVariant.of(item), amount, tx);
				tx.commit();
			}
		}
	}
}
