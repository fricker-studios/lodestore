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
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import dev.fricker.lodestore.block.CrateBlock;
import dev.fricker.lodestore.block.CrateBlockEntity;
import dev.fricker.lodestore.registry.LodestoreBlocks;
import dev.fricker.lodestore.registry.LodestoreItems;

/**
 * Screenshots the crates in world and with a crate open.
 *
 * <p>"Does the GUI open", "is the icon in the right place" and "does it render at all" are exactly
 * the failure modes no server-side assertion can see. This produces images to look at instead.
 *
 * <p>The row is laid out to put every rendering decision in one frame: all three tiers' sprites
 * side by side, a crate with contents, a locked crate with contents, and a locked crate that has
 * been emptied — the last of which is the only way to see that a lock keeps labelling its face.
 */
public class CrateRenderClientTest implements FabricClientGameTest {
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

				// Floor and backdrop, so the crates read against something and the player stands.
				for (int x = -7; x <= 7; x++) {
					for (int z = -1; z <= 10; z++) {
						level.setBlockAndUpdate(new BlockPos(x, Y - 1, z), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
					}

					for (int y = Y; y <= Y + 3; y++) {
						level.setBlockAndUpdate(new BlockPos(x, y, -1), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
					}
				}

				// One of each tier, left to right, so the three sprites can be compared directly.
				// The mixed one is locked and shows the slot chosen for its face — iron, though it
				// holds far more logs — so it proves the choice beats the most-common default.
				single(level, new BlockPos(-4, Y, 0), LodestoreBlocks.BASIC_CRATE, Items.COBBLESTONE, 320);
				single(level, new BlockPos(-2, Y, 0), LodestoreBlocks.STURDY_CRATE, Items.DIAMOND, 128);
				mixed(level, new BlockPos(0, Y, 0), LodestoreBlocks.REINFORCED_CRATE);

				// The two lock states. Both draw their count in the lock's gold rather than white.
				lockedWithContents(level, new BlockPos(2, Y, 0), LodestoreBlocks.STURDY_CRATE,
						Items.REDSTONE, 192);
				lockedAndEmptied(level, new BlockPos(4, Y, 0), LodestoreBlocks.BASIC_CRATE, Items.EMERALD);
			});

			// Only now that there is a floor to stand on: a creative player teleported into
			// open air simply falls, and drifts out of range of the menu we open below.
			// Spectator for the stills: it takes the hand and the hotbar out of frame, which in
			// creative sit directly over the crates these shots exist to show.
			singleplayer.getServer().runCommand("gamemode spectator @p");
			singleplayer.getServer().runCommand("tp @p 0.5 " + Y + " 8.5 180 8");

			context.waitTicks(220);
			LOGGER.info("[lodestore] world screenshot -> {}", context.takeScreenshot("crates-in-world"));

			// Close enough that the frames, brackets and rivets are legible per tier rather than
			// just "three brown blocks".
			singleplayer.getServer().runCommand("tp @p -2.0 " + Y + " 5.0 180 13");
			context.waitTicks(60);
			LOGGER.info("[lodestore] tier close-up -> {}", context.takeScreenshot("crate-tiers-close"));

			// The pair that only the lock produces: gold count, and a filter on an empty face.
			singleplayer.getServer().runCommand("tp @p 3.0 " + Y + " 5.0 180 13");
			context.waitTicks(60);
			LOGGER.info("[lodestore] lock close-up -> {}", context.takeScreenshot("crate-locks-close"));

			// Back to creative: a spectator cannot be given a menu.
			singleplayer.getServer().runCommand("gamemode creative @p");
			singleplayer.getServer().runCommand("tp @p 0.5 " + Y + " 4.5 180 6");
			context.waitTicks(20);

			// Open the reinforced crate, which is the six-row menu.
			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();

				if (server.overworld().getBlockEntity(new BlockPos(0, Y, 0)) instanceof CrateBlockEntity crate) {
					player.openMenu(crate);
				}
			});

			context.waitTicks(40);
			LOGGER.info("[lodestore] gui screenshot -> {}", context.takeScreenshot("crate-gui-open"));

			// Hover the first upgrade so its help text is in the shot. Right-clicking an upgrade
			// to choose the face's slot is not a gesture anything else in the game teaches, so the
			// hint is the only thing that makes it discoverable — worth a picture of its own.
			int[] cursor = context.computeOnClient(client -> {
				// The reinforced crate's screen: 176 of chest plus a 66-wide panel, and the
				// vanilla chest's own height for six rows.
				double scale = client.getWindow().getGuiScale();
				int left = (client.getWindow().getGuiScaledWidth() - (176 + 66)) / 2;
				int top = (client.getWindow().getGuiScaledHeight() - (114 + 6 * 18)) / 2;

				// Middle of the first upgrade slot.
				return new int[] {(int) ((left + 184 + 8) * scale), (int) ((top + 18 + 8) * scale)};
			});

			context.getInput().setCursorPos(cursor[0], cursor[1]);
			context.waitTicks(20);
			LOGGER.info("[lodestore] tooltip screenshot -> {}", context.takeScreenshot("crate-upgrade-tooltip"));

			// A slot of a stacked crate draws its real total, and the tooltip says out of how much.
			// Both are things only a picture can check. Worth one of its own.
			int[] stacked = context.computeOnClient(client -> {
				double scale = client.getWindow().getGuiScale();
				int left = (client.getWindow().getGuiScaledWidth() - (176 + 66)) / 2;
				int top = (client.getWindow().getGuiScaledHeight() - (114 + 6 * 18)) / 2;

				// Middle of storage slot 0.
				return new int[] {(int) ((left + 8 + 8) * scale), (int) ((top + 18 + 8) * scale)};
			});

			context.getInput().setCursorPos(stacked[0], stacked[1]);
			context.waitTicks(20);
			LOGGER.info("[lodestore] stacked slot screenshot -> {}", context.takeScreenshot("crate-stacked-slot-tooltip"));
		}
	}

	private static CrateBlockEntity single(ServerLevel level, BlockPos pos, Block block, Item item, int amount) {
		CrateBlockEntity crate = place(level, pos, block);

		if (crate == null) {
			return null;
		}

		int slot = 0;

		while (amount > 0 && slot < crate.storageSize()) {
			int count = Math.min(amount, item.getDefaultMaxStackSize());
			crate.contents().setItem(slot++, new ItemStack(item, count));
			amount -= count;
		}

		crate.setChanged();
		return crate;
	}

	/** A locked crate with stock in it: same icon as any other, but a gold count. */
	private static void lockedWithContents(ServerLevel level, BlockPos pos, Block block, Item item, int amount) {
		CrateBlockEntity crate = single(level, pos, block, item, amount);

		if (crate != null) {
			crate.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));
		}
	}

	/**
	 * A locked crate that has been emptied — the state the lock exists for.
	 *
	 * <p>The lock claims its filter from the first item in, so this stocks one, lets the lock take
	 * it, and then empties the crate again. Its face should still name the item.
	 */
	private static void lockedAndEmptied(ServerLevel level, BlockPos pos, Block block, Item item) {
		CrateBlockEntity crate = place(level, pos, block);

		if (crate == null) {
			return;
		}

		crate.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));
		crate.contents().setItem(0, new ItemStack(item, 1));
		crate.setChanged();

		crate.contents().setItem(0, ItemStack.EMPTY);
		crate.setChanged();
	}

	private static void mixed(ServerLevel level, BlockPos pos, Block block) {
		CrateBlockEntity crate = place(level, pos, block);

		if (crate == null) {
			return;
		}

		crate.contents().setItem(0, new ItemStack(Items.OAK_LOG, 64));
		crate.contents().setItem(1, new ItemStack(Items.IRON_INGOT, 32));
		crate.contents().setItem(2, new ItemStack(Items.REDSTONE, 16));
		crate.contents().setItem(3, new ItemStack(LodestoreBlocks.STURDY_CRATE, 2));
		crate.setChanged();

		// This is the crate the GUI screenshot opens. The lock's gold marks the one slot the face
		// shows; the lock, void and stack upgrades all cover the whole crate and tint nothing else.
		// The face points at the logs while the stack upgrade sorts, so the sort leaves them in
		// slot 0 for the stacked-slot shot.
		crate.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));
		crate.chooseDisplaySlot(0);
		crate.installUpgrade(new ItemStack(LodestoreItems.VOID_UPGRADE));
		crate.installUpgrade(new ItemStack(LodestoreItems.STACK_UPGRADE));

		// Slot 0 is filled to its four stacks: the stacked-slot screenshot hovers it, and the slot
		// should draw 256 where a plain one would draw 64, with the tooltip saying out of how much.
		try (Transaction transaction = Transaction.openOuter()) {
			crate.storage().getSlot(0).insert(ItemVariant.of(Items.OAK_LOG), 192, transaction);
			transaction.commit();
		}

		// Then at the iron, wherever the sort put it, so the face shows 32 iron over 256 logs.
		for (int slot = 0; slot < crate.storageSize(); slot++) {
			if (crate.contents().visible(slot).is(Items.IRON_INGOT)) {
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
