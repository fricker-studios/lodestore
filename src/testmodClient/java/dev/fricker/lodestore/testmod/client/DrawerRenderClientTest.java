package dev.fricker.lodestore.testmod.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import dev.fricker.lodestore.block.DrawerBlock;
import dev.fricker.lodestore.block.DrawerBlockEntity;
import dev.fricker.lodestore.registry.LodestoreBlocks;

/**
 * Screenshots the drawers in world and with a drawer open.
 *
 * <p>"Does the GUI open", "is the icon in the right place" and "does it render at all" are exactly
 * the failure modes no server-side assertion can see. This produces images to look at instead.
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
			singleplayer.getServer().runCommand("gamemode creative @p");

			singleplayer.getServer().runOnServer(server -> {
				ServerLevel level = server.overworld();

				// Floor and backdrop, so the drawers read against something and the player stands.
				for (int x = -5; x <= 5; x++) {
					for (int z = -1; z <= 5; z++) {
						level.setBlockAndUpdate(new BlockPos(x, Y - 1, z), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
					}

					for (int y = Y; y <= Y + 2; y++) {
						level.setBlockAndUpdate(new BlockPos(x, y, -1), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
					}
				}

				// One of each tier. The front face previews only a single-item drawer, so the
				// mixed one should show nothing.
				single(level, new BlockPos(-2, Y, 0), LodestoreBlocks.BASIC_DRAWER, Items.COBBLESTONE, 320);
				single(level, new BlockPos(0, Y, 0), LodestoreBlocks.STURDY_DRAWER, Items.DIAMOND, 128);
				mixed(level, new BlockPos(2, Y, 0), LodestoreBlocks.REINFORCED_DRAWER);
			});

			// Only now that there is a floor to stand on: a creative player teleported into
			// open air simply falls, and drifts out of range of the menu we open below.
			singleplayer.getServer().runCommand("tp @p 0.5 " + Y + " 4.5 180 0");

			context.waitTicks(220);
			LOGGER.info("[lodestore] world screenshot -> {}", context.takeScreenshot("drawers-in-world"));

			// Open the reinforced drawer, which is the six-row menu.
			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();

				if (server.overworld().getBlockEntity(new BlockPos(2, Y, 0)) instanceof DrawerBlockEntity drawer) {
					player.openMenu(drawer);
				}
			});

			context.waitTicks(40);
			LOGGER.info("[lodestore] gui screenshot -> {}", context.takeScreenshot("drawer-gui-open"));
		}
	}

	private static void single(ServerLevel level, BlockPos pos, Block block, Item item, int amount) {
		DrawerBlockEntity drawer = place(level, pos, block);

		if (drawer == null) {
			return;
		}

		int slot = 0;

		while (amount > 0 && slot < drawer.getContainerSize()) {
			int count = Math.min(amount, item.getDefaultMaxStackSize());
			drawer.setItem(slot++, new ItemStack(item, count));
			amount -= count;
		}

		drawer.setChanged();
	}

	private static void mixed(ServerLevel level, BlockPos pos, Block block) {
		DrawerBlockEntity drawer = place(level, pos, block);

		if (drawer == null) {
			return;
		}

		drawer.setItem(0, new ItemStack(Items.OAK_LOG, 64));
		drawer.setItem(1, new ItemStack(Items.IRON_INGOT, 32));
		drawer.setItem(2, new ItemStack(Items.REDSTONE, 16));
		drawer.setItem(3, new ItemStack(LodestoreBlocks.STURDY_DRAWER, 2));
		drawer.setChanged();
	}

	private static DrawerBlockEntity place(ServerLevel level, BlockPos pos, Block block) {
		level.setBlockAndUpdate(pos, block.defaultBlockState().setValue(DrawerBlock.FACING, Direction.SOUTH));
		return level.getBlockEntity(pos) instanceof DrawerBlockEntity drawer ? drawer : null;
	}
}
