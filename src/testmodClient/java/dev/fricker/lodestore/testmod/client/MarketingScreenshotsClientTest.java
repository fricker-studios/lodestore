package dev.fricker.lodestore.testmod.client;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;

import dev.fricker.lodestore.block.CrateBlock;
import dev.fricker.lodestore.block.CrateBlockEntity;
import dev.fricker.lodestore.block.InterfaceBoxBlock;
import dev.fricker.lodestore.block.InterfaceBoxBlockEntity;
import dev.fricker.lodestore.registry.LodestoreBlocks;
import dev.fricker.lodestore.registry.LodestoreItems;

/**
 * Pictures for the Modrinth page, not assertions: a stocked storage room at 1080p, the crate
 * screen with every upgrade installed, and the interface box's view of the whole network.
 *
 * <p>Built as a client gametest because that is the one thing in the project that can drive a
 * real client, place a world and take a screenshot. It asserts nothing, so it never fails a
 * build; it only writes files into {@code build/clientGametest/screenshots/}, named
 * {@code modrinth-*}.
 */
public class MarketingScreenshotsClientTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("lodestore-marketing");
	private static final int Y = 100;
	private static final int WIDTH = 1920;
	private static final int HEIGHT = 1080;
	private static final int GUI_SCALE = 4;

	@Override
	public void runTest(ClientGameTestContext context) {
		context.runOnClient(client -> {
			client.getWindow().setWindowed(WIDTH, HEIGHT);
			client.options.guiScale().set(GUI_SCALE);
			client.resizeGui();
		});

		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getServer().runCommand("gamerule sendCommandFeedback false");
			singleplayer.getServer().runCommand("gamerule doDaylightCycle false");
			singleplayer.getServer().runCommand("gamerule doWeatherCycle false");
			singleplayer.getServer().runCommand("time set 6000");
			singleplayer.getServer().runCommand("weather clear");
			singleplayer.getServer().runCommand("gamemode creative @p");

			singleplayer.getServer().runOnServer(server -> build(server.overworld()));

			// The HUD off, the hand invisible: nothing in frame but the mod.
			singleplayer.getServer().runCommand("effect give @p minecraft:invisibility infinite 0 true");
			context.runOnClient(client -> {
				if (!client.gui.hud.isHidden()) {
					client.gui.hud.toggle();
				}
			});

			// The room from the doorway: both rows of crates, the upgrades framed above them, the
			// network's devices at the end of the row.
			singleplayer.getServer().runCommand("gamemode spectator @p");
			singleplayer.getServer().runCommand("tp @p 1.0 " + (Y + 2.0) + " 8.6 180 12");
			context.waitTicks(240);
			shot(context, "modrinth-storage-room");

			// Close on the three tiers, their faces showing what they hold.
			singleplayer.getServer().runCommand("tp @p -1.3 " + (Y + 0.6) + " 4.0 165 12");
			context.waitTicks(80);
			shot(context, "modrinth-crates-close");

			// The network end of the row: interface box, deposit box under its chest, connection blocks.
			singleplayer.getServer().runCommand("tp @p 3.0 " + (Y + 1.0) + " 4.8 198 16");
			context.waitTicks(80);
			shot(context, "modrinth-network");

			// The upgrades, framed.
			singleplayer.getServer().runCommand("tp @p 1.0 " + (Y + 0.9) + " 4.6 180 0");
			context.waitTicks(80);
			shot(context, "modrinth-upgrades");

			// The crate screen: the reinforced crate with every upgrade installed.
			singleplayer.getServer().runCommand("gamemode creative @p");
			singleplayer.getServer().runCommand("tp @p 0.5 " + (Y + 0.0) + " 3.5 180 0");
			context.waitTicks(40);
			openCrate(singleplayer, new BlockPos(0, Y + 1, 0));
			context.waitTicks(60);
			shot(context, "modrinth-crate-screen");

			// The disenchant upgrade's tooltip: what it does, what it holds, how to collect it.
			// Third slot of the first row of the column.
			int[] cursor = context.computeOnClient(client -> {
				double scale = client.getWindow().getGuiScale();
				int left = (client.getWindow().getGuiScaledWidth() - (176 + 66)) / 2;
				int top = (client.getWindow().getGuiScaledHeight() - (114 + 6 * 18)) / 2;
				return new int[] {(int) ((left + 184 + 2 * 18 + 8) * scale), (int) ((top + 18 + 8) * scale)};
			});
			context.getInput().setCursorPos(cursor[0], cursor[1]);
			context.waitTicks(30);
			shot(context, "modrinth-crate-screen-tooltip");
			context.runOnClient(client -> {
				if (client.player != null) {
					client.player.closeContainer();
				}
			});
			context.waitTicks(20);

			// The interface box's view of the whole room.
			singleplayer.getServer().runCommand("tp @p 4.5 " + (Y + 0.0) + " 3.5 180 0");
			context.waitTicks(40);
			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();

				if (server.overworld().getBlockEntity(new BlockPos(4, Y, 0)) instanceof InterfaceBoxBlockEntity box) {
					player.openMenu(box);
				}
			});
			context.waitTicks(60);
			shot(context, "modrinth-interface-screen");
		} finally {
			context.restoreDefaultGameOptions();
		}
	}

	private static void shot(ClientGameTestContext context, String name) {
		LOGGER.info("[lodestore] marketing screenshot -> {}", context.takeScreenshot(name));
	}

	private static void openCrate(TestSingleplayerContext singleplayer, BlockPos pos) {
		singleplayer.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().getFirst();

			if (server.overworld().getBlockEntity(pos) instanceof CrateBlockEntity crate) {
				player.openMenu(crate);
			}
		});
	}

	// --- the room -------------------------------------------------------------------

	/**
	 * A storage room: a spruce floor, a stone-brick back wall, two rows of seven crates against
	 * it with the eight upgrades framed above them, and the network's devices at the right-hand
	 * end. The connection blocks run inside the wall behind the crates, so every crate is on the
	 * network and only the visible stretch by the devices shows.
	 */
	private static void build(ServerLevel level) {
		BlockState floor = Blocks.SPRUCE_PLANKS.defaultBlockState();
		BlockState wall = Blocks.STONE_BRICKS.defaultBlockState();
		BlockState trim = Blocks.DARK_OAK_PLANKS.defaultBlockState();

		for (int x = -9; x <= 9; x++) {
			for (int z = -2; z <= 12; z++) {
				level.setBlockAndUpdate(new BlockPos(x, Y - 1, z), floor);
			}

			for (int y = Y; y <= Y + 4; y++) {
				level.setBlockAndUpdate(new BlockPos(x, y, -1), y == Y + 4 ? trim : wall);
			}
		}

		for (int z = -1; z <= 12; z++) {
			for (int y = Y; y <= Y + 4; y++) {
				level.setBlockAndUpdate(new BlockPos(-9, y, z), y == Y + 4 ? trim : wall);
				level.setBlockAndUpdate(new BlockPos(9, y, z), y == Y + 4 ? trim : wall);
			}
		}

		for (int x : new int[] {-6, 6}) {
			level.setBlockAndUpdate(new BlockPos(x, Y + 3, 1), Blocks.LANTERN.defaultBlockState()
					.setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true));
			level.setBlockAndUpdate(new BlockPos(x, Y + 4, 1), trim);
		}

		// The network runs inside the wall, behind both rows, and the devices continue the bottom
		// row along it so both fronts face the room: the interface box, then the deposit box with
		// a chest feeding it from above, then a connection block in the open to show what carries
		// the network, with another over the interface box.
		for (int x = -3; x <= 6; x++) {
			for (int y = Y; y <= Y + 1; y++) {
				level.setBlockAndUpdate(new BlockPos(x, y, -1), LodestoreBlocks.CONNECTION_BLOCK.defaultBlockState());
			}
		}

		level.setBlockAndUpdate(new BlockPos(4, Y, 0), LodestoreBlocks.INTERFACE_BOX.defaultBlockState()
				.setValue(InterfaceBoxBlock.FACING, Direction.SOUTH));
		level.setBlockAndUpdate(new BlockPos(4, Y + 1, 0), LodestoreBlocks.CONNECTION_BLOCK.defaultBlockState());
		level.setBlockAndUpdate(new BlockPos(5, Y, 0), LodestoreBlocks.DEPOSIT_BOX.defaultBlockState());
		level.setBlockAndUpdate(new BlockPos(5, Y + 1, 0), Blocks.CHEST.defaultBlockState());
		level.setBlockAndUpdate(new BlockPos(6, Y, 0), LodestoreBlocks.CONNECTION_BLOCK.defaultBlockState());

		// Top row: the reinforced crate the screen opens in the middle.
		stocked(level, new BlockPos(-3, Y + 1, 0), LodestoreBlocks.STURDY_CRATE, Items.GOLD_INGOT, 384, true);
		stocked(level, new BlockPos(-2, Y + 1, 0), LodestoreBlocks.BASIC_CRATE, Items.OAK_LOG, 512, false);
		stocked(level, new BlockPos(-1, Y + 1, 0), LodestoreBlocks.REINFORCED_CRATE, Items.DIAMOND, 1024, true);
		showpiece(level, new BlockPos(0, Y + 1, 0));
		stocked(level, new BlockPos(1, Y + 1, 0), LodestoreBlocks.REINFORCED_CRATE, Items.IRON_BLOCK, 640, false);
		stocked(level, new BlockPos(2, Y + 1, 0), LodestoreBlocks.BASIC_CRATE, Items.REDSTONE, 256, true);
		stocked(level, new BlockPos(3, Y + 1, 0), LodestoreBlocks.STURDY_CRATE, Items.EMERALD, 96, false);

		// Bottom row.
		stocked(level, new BlockPos(-3, Y, 0), LodestoreBlocks.BASIC_CRATE, Items.WHEAT, 448, false);
		stocked(level, new BlockPos(-2, Y, 0), LodestoreBlocks.STURDY_CRATE, Items.COPPER_INGOT, 800, true);
		stocked(level, new BlockPos(-1, Y, 0), LodestoreBlocks.BASIC_CRATE, Items.COAL, 1200, false);
		stocked(level, new BlockPos(0, Y, 0), LodestoreBlocks.REINFORCED_CRATE, Items.COBBLESTONE, 2500, true);
		stocked(level, new BlockPos(1, Y, 0), LodestoreBlocks.STURDY_CRATE, Items.LAPIS_LAZULI, 320, false);
		lockedAndEmpty(level, new BlockPos(2, Y, 0), LodestoreBlocks.STURDY_CRATE, Items.ENDER_PEARL);
		stocked(level, new BlockPos(3, Y, 0), LodestoreBlocks.BASIC_CRATE, Items.BONE, 160, false);

		// The eight upgrades in a row of frames above the crates and the devices.
		List<Item> upgrades = List.of(LodestoreItems.LOCK_UPGRADE, LodestoreItems.VOID_UPGRADE,
				LodestoreItems.STACK_UPGRADE, LodestoreItems.STACK_UPGRADE_2, LodestoreItems.STACK_UPGRADE_3,
				LodestoreItems.COMPRESS_UPGRADE, LodestoreItems.DISENCHANT_UPGRADE, LodestoreItems.REPAIR_UPGRADE);

		for (int i = 0; i < upgrades.size(); i++) {
			ItemFrame frame = new ItemFrame(level, new BlockPos(i - 3, Y + 2, 0), Direction.SOUTH);
			frame.setItem(new ItemStack(upgrades.get(i)));
			level.addFreshEntity(frame);
		}
	}

	/** A crate of one item, with a stack upgrade where the count needs one, locked or not. */
	private static CrateBlockEntity stocked(ServerLevel level, BlockPos pos, Block block, Item item, int amount,
			boolean locked) {
		CrateBlockEntity crate = place(level, pos, block);

		if (crate == null) {
			return null;
		}

		int perStack = item.getDefaultMaxStackSize();

		if (amount > perStack * crate.storageSize() / 2) {
			crate.installUpgrade(new ItemStack(LodestoreItems.STACK_UPGRADE));
		}

		try (Transaction transaction = Transaction.openOuter()) {
			crate.storage().insert(ItemVariant.of(item), amount, transaction);
			transaction.commit();
		}

		if (locked) {
			crate.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));
		}

		crate.setChanged();
		return crate;
	}

	private static void lockedAndEmpty(ServerLevel level, BlockPos pos, Block block, Item item) {
		CrateBlockEntity crate = place(level, pos, block);

		if (crate == null) {
			return;
		}

		crate.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));
		crate.contents().setItem(0, new ItemStack(item));
		crate.setChanged();
		crate.contents().setItem(0, ItemStack.EMPTY);
		crate.setChanged();
	}

	/**
	 * The crate the screen shows: a reinforced crate with all six upgrades, a few kinds of goods
	 * including stacked slots, a disenchanted sword's worth in the bank, destructive compression
	 * on, and the face pointing at the iron.
	 */
	private static void showpiece(ServerLevel level, BlockPos pos) {
		CrateBlockEntity crate = place(level, pos, LodestoreBlocks.REINFORCED_CRATE);

		if (crate == null) {
			return;
		}

		// The goods go in before the lock, or the lock would claim the first two and refuse the rest.
		crate.installUpgrade(new ItemStack(LodestoreItems.STACK_UPGRADE_3));
		crate.installUpgrade(new ItemStack(LodestoreItems.COMPRESS_UPGRADE));
		crate.installUpgrade(new ItemStack(LodestoreItems.DISENCHANT_UPGRADE));
		crate.installUpgrade(new ItemStack(LodestoreItems.REPAIR_UPGRADE));

		ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
		sword.enchant(level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS), 5);
		sword.enchant(level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.UNBREAKING), 3);
		crate.contents().setItem(2, sword);

		for (int i = 0; i < 3; i++) {
			ItemStack pickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
			pickaxe.setDamageValue(600 + i * 200);
			crate.contents().setItem(9 + i, pickaxe);
		}

		crate.setChanged();

		// Enough kinds to fill a few rows, several of them deep enough to show the stack upgrade
		// and several that compress — into blocks, hay and slime, and bones into bone blocks.
		Object[] stock = {
				Items.IRON_INGOT, 1000, Items.GOLD_INGOT, 300, Items.DIAMOND, 128, Items.EMERALD, 100,
				Items.COPPER_INGOT, 900, Items.LAPIS_LAZULI, 300, Items.REDSTONE, 500, Items.COAL, 1500,
				Items.COBBLESTONE, 4000, Items.OAK_LOG, 2000, Items.WHEAT, 700, Items.SLIME_BALL, 100,
				Items.ENDER_PEARL, 48, Items.BLAZE_ROD, 40, Items.ARROW, 256, Items.TORCH, 400,
				Items.BREAD, 100, Items.APPLE, 64, Items.BONE, 30, Items.SNOWBALL, 100,
		};

		try (Transaction transaction = Transaction.openOuter()) {
			for (int i = 0; i < stock.length; i += 2) {
				crate.storage().insert(ItemVariant.of((Item) stock[i]), (Integer) stock[i + 1], transaction);
			}

			transaction.commit();
		}

		crate.toggleDestructiveCompression();
		crate.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));
		crate.installUpgrade(new ItemStack(LodestoreItems.VOID_UPGRADE));

		for (int slot = 0; slot < crate.storageSize(); slot++) {
			if (crate.contents().visible(slot).is(Items.IRON_BLOCK)) {
				crate.chooseDisplaySlot(slot);
				break;
			}
		}
	}

	private static CrateBlockEntity place(ServerLevel level, BlockPos pos, Block block) {
		level.setBlockAndUpdate(pos, block.defaultBlockState().setValue(CrateBlock.FACING, Direction.SOUTH));
		return level.getBlockEntity(pos) instanceof CrateBlockEntity crate ? crate : null;
	}
}
