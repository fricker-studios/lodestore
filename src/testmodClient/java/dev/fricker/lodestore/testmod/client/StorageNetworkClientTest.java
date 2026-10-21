package dev.fricker.lodestore.testmod.client;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
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
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;

import dev.fricker.lodestore.block.CrateBlock;
import dev.fricker.lodestore.block.CrateBlockEntity;
import dev.fricker.lodestore.block.DepositBoxBlock;
import dev.fricker.lodestore.block.DepositBoxBlockEntity;
import dev.fricker.lodestore.block.InterfaceBoxBlock;
import dev.fricker.lodestore.block.InterfaceBoxBlockEntity;
import dev.fricker.lodestore.registry.LodestoreBlocks;
import dev.fricker.lodestore.registry.LodestoreItems;

/**
 * Screenshots the network blocks in world, and the interface box's screen open.
 *
 * <p>The screen is the thing no server-side test can see: that the view arrives at all, that the
 * cells draw with their counts, that a count too wide for a cell shrinks rather than overflows,
 * that the scrollbar appears once there is more than a screenful, and that the tooltip says the
 * exact number. The network is stocked with more kinds of item than fit on one screen, and with
 * two counts that need abbreviating, so one open screen shows all of it.
 */
public class StorageNetworkClientTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("lodestore-network-gametest");
	private static final int Y = 100;
	private static final BlockPos INTERFACE = new BlockPos(0, Y, 0);
	/** The interface screen: the chest's width plus the scrollbar's strip, six rows tall. */
	private static final int IMAGE_WIDTH = 176 + 22;
	private static final int IMAGE_HEIGHT = 114 + 6 * 18;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getServer().runCommand("gamerule doDaylightCycle false");
			singleplayer.getServer().runCommand("time set noon");
			singleplayer.getServer().runCommand("weather clear");
			singleplayer.getServer().runCommand("gamemode creative @p");

			singleplayer.getServer().runOnServer(server -> {
				ServerLevel level = server.overworld();

				for (int x = -7; x <= 7; x++) {
					for (int z = -6; z <= 10; z++) {
						level.setBlockAndUpdate(new BlockPos(x, Y - 1, z), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
					}

					for (int y = Y; y <= Y + 3; y++) {
						level.setBlockAndUpdate(new BlockPos(x, y, -6), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
					}
				}

				// The three network blocks side by side, facing the camera, with the crates behind
				// them on a run of connection blocks: the interface in the middle, the deposit box
				// to its left with its spout into the run, a bare connection block to its right.
				level.setBlockAndUpdate(INTERFACE, LodestoreBlocks.INTERFACE_BOX.defaultBlockState()
						.setValue(InterfaceBoxBlock.FACING, Direction.SOUTH));
				level.setBlockAndUpdate(new BlockPos(-2, Y, 0), LodestoreBlocks.DEPOSIT_BOX.defaultBlockState()
						.setValue(DepositBoxBlock.FACING, Direction.NORTH));
				level.setBlockAndUpdate(new BlockPos(2, Y, 0), LodestoreBlocks.CONNECTION_BLOCK.defaultBlockState());

				for (int x = -2; x <= 2; x++) {
					level.setBlockAndUpdate(new BlockPos(x, Y, -1), LodestoreBlocks.CONNECTION_BLOCK.defaultBlockState());
				}

				// Two crates of distinct items, more kinds than one screen holds, so the view scrolls.
				List<Item> kinds = distinctItems(81);
				fill(level, new BlockPos(-1, Y, -2), LodestoreBlocks.REINFORCED_CRATE, kinds.subList(0, 54));
				fill(level, new BlockPos(1, Y, -2), LodestoreBlocks.BASIC_CRATE, kinds.subList(54, 81));

				// And one deep crate holding counts that need abbreviating: 12,345 and 1,500.
				CrateBlockEntity deep = place(level, new BlockPos(0, Y, -2), LodestoreBlocks.REINFORCED_CRATE);

				if (deep != null) {
					deep.installUpgrade(new ItemStack(LodestoreItems.STACK_UPGRADE_3));

					try (Transaction transaction = Transaction.openOuter()) {
						deep.storage().insert(ItemVariant.of(Items.COBBLESTONE), 12_345, transaction);
						deep.storage().insert(ItemVariant.of(Items.OAK_LOG), 1_500, transaction);
						transaction.commit();
					}
				}

				// Something waiting in the deposit box with no home, so the box is not empty in shot.
				if (level.getBlockEntity(new BlockPos(-2, Y, 0)) instanceof DepositBoxBlockEntity box) {
					box.setItem(0, new ItemStack(Items.ENDER_PEARL, 3));
				}
			});

			// From above and in front, so the crates behind the devices are in the frame too, and the
			// deposit box's mouth on its top is visible.
			singleplayer.getServer().runCommand("gamemode spectator @p");
			singleplayer.getServer().runCommand("tp @p 0.5 " + (Y + 3) + " 5.5 180 32");
			context.waitTicks(220);
			LOGGER.info("[lodestore] network world screenshot -> {}", context.takeScreenshot("network-in-world"));

			singleplayer.getServer().runCommand("gamemode creative @p");
			singleplayer.getServer().runCommand("tp @p 0.5 " + Y + " 3.5 180 6");
			context.waitTicks(20);

			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();

				if (server.overworld().getBlockEntity(INTERFACE) instanceof InterfaceBoxBlockEntity box) {
					player.openMenu(box);
				}
			});

			// Long enough for the menu to open and the first view to arrive on its ten-tick cycle.
			context.waitTicks(40);
			LOGGER.info("[lodestore] interface screenshot -> {}", context.takeScreenshot("network-gui-open"));

			// Hover the first cell, which holds the most numerous item: the tooltip gives the exact count.
			int[] first = context.computeOnClient(client -> {
				double scale = client.getWindow().getGuiScale();
				int left = (client.getWindow().getGuiScaledWidth() - IMAGE_WIDTH) / 2;
				int top = (client.getWindow().getGuiScaledHeight() - IMAGE_HEIGHT) / 2;
				return new int[] {(int) ((left + 8 + 8) * scale), (int) ((top + 18 + 8) * scale)};
			});

			context.getInput().setCursorPos(first[0], first[1]);
			context.waitTicks(20);
			LOGGER.info("[lodestore] interface tooltip -> {}", context.takeScreenshot("network-gui-tooltip"));

			// Scroll to the end, a notch at a time as a wheel does — one notch is one row — so the
			// last row of kinds shows and the thumb sits at the bottom of its track.
			for (int notch = 0; notch < 8; notch++) {
				context.getInput().scroll(-1);
				context.waitTicks(2);
			}

			context.waitTicks(20);
			LOGGER.info("[lodestore] interface scrolled -> {}", context.takeScreenshot("network-gui-scrolled"));
		}
	}

	/** The first {@code count} plain, stackable items in registry order: a varied, deterministic stock. */
	private static List<Item> distinctItems(int count) {
		List<Item> items = new ArrayList<>();

		for (Item item : BuiltInRegistries.ITEM) {
			if (item != Items.AIR && item != Items.COBBLESTONE && item != Items.OAK_LOG
					&& item.getDefaultMaxStackSize() == 64 && !(item instanceof net.minecraft.world.item.BlockItem block
					&& block.getBlock() instanceof net.minecraft.world.level.block.EntityBlock)) {
				items.add(item);
			}

			if (items.size() == count) {
				break;
			}
		}

		return items;
	}

	/** One kind per slot, with a count that climbs so the cells are not all the same number. */
	private static void fill(ServerLevel level, BlockPos pos, Block block, List<Item> kinds) {
		CrateBlockEntity crate = place(level, pos, block);

		if (crate == null) {
			return;
		}

		for (int slot = 0; slot < kinds.size() && slot < crate.storageSize(); slot++) {
			crate.contents().setItem(slot, new ItemStack(kinds.get(slot), 1 + slot));
		}

		crate.setChanged();
	}

	private static CrateBlockEntity place(ServerLevel level, BlockPos pos, Block block) {
		level.setBlockAndUpdate(pos, block.defaultBlockState().setValue(CrateBlock.FACING, Direction.SOUTH));
		return level.getBlockEntity(pos) instanceof CrateBlockEntity crate ? crate : null;
	}
}
