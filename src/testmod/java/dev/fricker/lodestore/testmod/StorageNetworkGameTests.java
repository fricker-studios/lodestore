package dev.fricker.lodestore.testmod;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.vehicle.minecart.MinecartHopper;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;

import dev.fricker.lodestore.block.CrateBlockEntity;
import dev.fricker.lodestore.block.DepositBoxBlockEntity;
import dev.fricker.lodestore.block.InterfaceBoxBlockEntity;
import dev.fricker.lodestore.network.NetworkEntry;
import dev.fricker.lodestore.network.NetworkView;
import dev.fricker.lodestore.network.StorageNetwork;
import dev.fricker.lodestore.registry.LodestoreBlocks;
import dev.fricker.lodestore.registry.LodestoreItems;

/**
 * The storage network: a deposit box, connection blocks, crates, and an interface box.
 *
 * <p>Every flow test has its control alongside — a crate that is not connected, or does not hold
 * the item — because a deposit box that emptied into every crate it could reach would pass a test
 * that only looked at the crate it was meant to fill.
 *
 * <p>Laid out along x: the deposit box at one end, connection blocks between, crates at the other.
 */
public class StorageNetworkGameTests {
	private static final BlockPos BOX = new BlockPos(1, 1, 1);
	private static final BlockPos LINK_A = new BlockPos(2, 1, 1);
	private static final BlockPos LINK_B = new BlockPos(3, 1, 1);
	private static final BlockPos CRATE = new BlockPos(4, 1, 1);
	private static final int STACK = 64;

	// --- flow ----------------------------------------------------------------------

	/**
	 * Items flow from the deposit box, through the connection blocks, into the crate that already
	 * holds that item — and only that crate. A connected crate holding something else and an
	 * unconnected crate holding the same thing both stay as they were.
	 */
	@GameTest(maxTicks = 100)
	public void aDepositBoxFlowsIntoTheCrateThatHoldsTheItem(GameTestHelper helper) {
		DepositBoxBlockEntity box = depositBox(helper, BOX, new ItemStack(Items.COBBLESTONE, 10));
		link(helper, LINK_A, LINK_B);
		CrateBlockEntity home = crate(helper, CRATE, Items.COBBLESTONE, 5);
		CrateBlockEntity other = crate(helper, LINK_B.north(), Items.DIRT, 5);
		CrateBlockEntity apart = crate(helper, new BlockPos(6, 1, 1), Items.COBBLESTONE, 5);

		helper.runAtTickTime(60, () -> {
			if (!box.isEmpty()) {
				throw helper.assertionException("the deposit box still holds " + describe(box.getItem(0)));
			}

			if (count(home, Items.COBBLESTONE) != 15) {
				throw helper.assertionException("the home crate should hold 15 cobblestone, holds " + count(home, Items.COBBLESTONE));
			}

			if (count(other, Items.COBBLESTONE) != 0 || count(other, Items.DIRT) != 5) {
				throw helper.assertionException("the crate holding dirt was given cobblestone");
			}

			if (count(apart, Items.COBBLESTONE) != 5) {
				throw helper.assertionException("a crate not on the network was filled: " + count(apart, Items.COBBLESTONE));
			}

			helper.succeed();
		});
	}

	/**
	 * An item with nowhere to go stays. A locked crate holding something else is not a home and not
	 * overflow; a crate whose lock claims the item is a home, even while it is empty.
	 */
	@GameTest(maxTicks = 100)
	public void anItemWithNoHomeStaysInTheDepositBox(GameTestHelper helper) {
		DepositBoxBlockEntity box = depositBox(helper, BOX, new ItemStack(Items.DIRT, 5), new ItemStack(Items.OAK_LOG, 3));
		link(helper, LINK_A, LINK_B);
		CrateBlockEntity cobble = lockedCrate(helper, CRATE, Items.COBBLESTONE, 5);
		CrateBlockEntity claimed = place(helper, LINK_B.north(), LodestoreBlocks.STURDY_CRATE);
		claimed.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));
		claimed.contents().setItem(0, new ItemStack(Items.OAK_LOG, 1));
		claimed.setChanged();
		// The claim is captured; now the crate is empty and locked to oak logs.
		claimed.contents().setItem(0, ItemStack.EMPTY);
		claimed.setChanged();

		if (!claimed.isEmpty() || claimed.lockedItems().isEmpty()) {
			throw helper.assertionException("the locked crate is not empty and claiming oak logs; the test is set up wrong");
		}

		helper.runAtTickTime(60, () -> {
			if (box.getItem(0).getCount() != 5 || !box.getItem(0).is(Items.DIRT)) {
				throw helper.assertionException("dirt with no home left the box: slot 0 holds " + describe(box.getItem(0)));
			}

			if (count(cobble, Items.DIRT) != 0) {
				throw helper.assertionException("a locked crate was treated as overflow for dirt");
			}

			if (!box.getItem(1).isEmpty() || count(claimed, Items.OAK_LOG) != 3) {
				throw helper.assertionException("the oak logs did not go to the crate locked to them: box holds "
						+ describe(box.getItem(1)) + ", crate holds " + count(claimed, Items.OAK_LOG));
			}

			helper.succeed();
		});
	}

	/**
	 * A home is for the item, not the stack: a worn bow flows to the crate holding a new one, and
	 * lands in a slot of its own there. The dirt alongside is the control, with no home anywhere.
	 */
	@GameTest(maxTicks = 100)
	public void aHomeTakesTheSameItemWhateverItsComponents(GameTestHelper helper) {
		ItemStack worn = new ItemStack(Items.BOW);
		worn.setDamageValue(100);
		DepositBoxBlockEntity box = depositBox(helper, BOX, worn, new ItemStack(Items.DIRT, 2));
		link(helper, LINK_A, LINK_B);
		CrateBlockEntity bows = lockedCrate(helper, CRATE, Items.BOW, 1);

		helper.runAtTickTime(40, () -> {
			if (!box.getItem(0).isEmpty() || count(bows, Items.BOW) != 2) {
				throw helper.assertionException("the worn bow did not flow to the crate holding a bow: box holds "
						+ describe(box.getItem(0)) + ", crate holds " + count(bows, Items.BOW));
			}

			if (!bows.contents().visible(1).is(Items.BOW) || bows.contents().visible(1).getDamageValue() != 100) {
				throw helper.assertionException("the worn bow should sit in its own slot, slot 1 holds "
						+ describe(bows.contents().visible(1)));
			}

			if (box.getItem(1).getCount() != 2) {
				throw helper.assertionException("the dirt, with nowhere to go, left the box");
			}

			helper.succeed();
		});
	}

	// --- overflow ------------------------------------------------------------------

	/**
	 * An unlocked crate is overflow: it takes what has no home, after the homes. The cobblestone
	 * goes to its locked home and not to the overflow crate; the dirt, with no home, goes to the
	 * overflow crate and not to the locked one.
	 */
	@GameTest(maxTicks = 100)
	public void anUnlockedCrateTakesWhatHasNoHome(GameTestHelper helper) {
		DepositBoxBlockEntity box = depositBox(helper, BOX, new ItemStack(Items.COBBLESTONE, 4), new ItemStack(Items.DIRT, 3));
		link(helper, LINK_A, LINK_B);
		CrateBlockEntity overflow = place(helper, LINK_A.north(), LodestoreBlocks.BASIC_CRATE);
		CrateBlockEntity home = lockedCrate(helper, CRATE, Items.COBBLESTONE, 1);

		helper.runAtTickTime(40, () -> {
			if (!box.isEmpty()) {
				throw helper.assertionException("the box did not empty: " + describe(box.getItem(0)) + ", " + describe(box.getItem(1)));
			}

			if (count(home, Items.COBBLESTONE) != 5 || count(overflow, Items.COBBLESTONE) != 0) {
				throw helper.assertionException("cobblestone with a home went to overflow: home " + count(home, Items.COBBLESTONE)
						+ ", overflow " + count(overflow, Items.COBBLESTONE));
			}

			if (count(overflow, Items.DIRT) != 3 || count(home, Items.DIRT) != 0) {
				throw helper.assertionException("dirt did not go to the overflow crate: overflow " + count(overflow, Items.DIRT)
						+ ", home " + count(home, Items.DIRT));
			}

			helper.succeed();
		});
	}

	/**
	 * A full home spills into overflow unless it voids. Two networks: on the first the full home
	 * has no void upgrade and the cobblestone lands in the overflow crate; on the second it has
	 * one and the cobblestone is destroyed, the overflow crate staying empty.
	 */
	@GameTest(maxTicks = 100)
	public void aFullHomeSpillsIntoOverflowUnlessItVoids(GameTestHelper helper) {
		DepositBoxBlockEntity spilling = depositBox(helper, BOX, new ItemStack(Items.COBBLESTONE, 3));
		link(helper, LINK_A, LINK_B);
		fullCrate(helper, CRATE, Items.COBBLESTONE, true, false);
		CrateBlockEntity spillInto = place(helper, LINK_B.north(), LodestoreBlocks.BASIC_CRATE);

		BlockPos boxB = new BlockPos(1, 1, 5);
		DepositBoxBlockEntity voiding = depositBox(helper, boxB, new ItemStack(Items.COBBLESTONE, 3));
		link(helper, boxB.east(), boxB.east(2));
		CrateBlockEntity voidHome = fullCrate(helper, boxB.east(3), Items.COBBLESTONE, true, true);
		long voidHomeBefore = count(voidHome, Items.COBBLESTONE);
		CrateBlockEntity voidOverflow = place(helper, boxB.east(2).north(), LodestoreBlocks.BASIC_CRATE);

		helper.runAtTickTime(40, () -> {
			if (!spilling.isEmpty() || count(spillInto, Items.COBBLESTONE) != 3) {
				throw helper.assertionException("a full home without a void upgrade did not spill to overflow: box "
						+ describe(spilling.getItem(0)) + ", overflow " + count(spillInto, Items.COBBLESTONE));
			}

			if (!voiding.isEmpty()) {
				throw helper.assertionException("a full voiding home did not take the cobblestone: box holds " + describe(voiding.getItem(0)));
			}

			if (count(voidOverflow, Items.COBBLESTONE) != 0 || count(voidHome, Items.COBBLESTONE) != voidHomeBefore) {
				throw helper.assertionException("a full voiding home spilled instead of voiding: overflow holds "
						+ count(voidOverflow, Items.COBBLESTONE) + ", home " + count(voidHome, Items.COBBLESTONE));
			}

			helper.succeed();
		});
	}

	/**
	 * Once the overflow is full, an overflow crate with a void upgrade destroys everything with no
	 * home; without one, the item stays in the box. Two networks, one of each.
	 */
	@GameTest(maxTicks = 100)
	public void aFullVoidingOverflowCrateDestroysWhatHasNoHome(GameTestHelper helper) {
		DepositBoxBlockEntity destroying = depositBox(helper, BOX, new ItemStack(Items.DIRT, 3));
		link(helper, LINK_A, LINK_B);
		CrateBlockEntity voidOverflow = fullCrate(helper, CRATE, Items.COBBLESTONE, false, true);

		BlockPos boxB = new BlockPos(1, 1, 5);
		DepositBoxBlockEntity keeping = depositBox(helper, boxB, new ItemStack(Items.DIRT, 3));
		link(helper, boxB.east(), boxB.east(2));
		CrateBlockEntity plainOverflow = fullCrate(helper, boxB.east(3), Items.COBBLESTONE, false, false);

		helper.runAtTickTime(40, () -> {
			if (!destroying.isEmpty() || count(voidOverflow, Items.DIRT) != 0) {
				throw helper.assertionException("a full voiding overflow crate did not destroy the dirt: box "
						+ describe(destroying.getItem(0)) + ", crate holds " + count(voidOverflow, Items.DIRT) + " dirt");
			}

			if (keeping.getItem(0).getCount() != 3) {
				throw helper.assertionException("dirt with nowhere to go left the box: " + describe(keeping.getItem(0)));
			}

			helper.succeed();
		});
	}

	/** A full voiding home near the box does not destroy what a home further along had room for. */
	@GameTest(maxTicks = 100)
	public void aHomeWithRoomBeatsANearerVoidingHome(GameTestHelper helper) {
		DepositBoxBlockEntity box = depositBox(helper, BOX, new ItemStack(Items.COBBLESTONE, 4));
		link(helper, LINK_A, LINK_B);
		CrateBlockEntity near = fullCrate(helper, LINK_A.north(), Items.COBBLESTONE, true, true);
		CrateBlockEntity far = lockedCrate(helper, CRATE, Items.COBBLESTONE, 1);

		helper.runAtTickTime(40, () -> {
			if (!box.isEmpty() || count(far, Items.COBBLESTONE) != 5) {
				throw helper.assertionException("the cobblestone did not reach the home with room: box "
						+ describe(box.getItem(0)) + ", far crate " + count(far, Items.COBBLESTONE));
			}

			helper.succeed();
		});
	}

	/** One item a tick: a hopper minecart's rate, eight times a hopper's. */
	@GameTest(maxTicks = 120)
	public void theDepositBoxDrainsOneItemATick(GameTestHelper helper) {
		DepositBoxBlockEntity box = depositBox(helper, BOX, new ItemStack(Items.COBBLESTONE, STACK));
		link(helper, LINK_A, LINK_B);
		CrateBlockEntity home = crate(helper, CRATE, Items.COBBLESTONE, 1);

		helper.runAtTickTime(30, () -> {
			long moved = count(home, Items.COBBLESTONE) - 1;

			// Allow for the ticks the box and the test are scheduled on; a hopper's rate would be 3.
			if (moved < 24 || moved > 31) {
				throw helper.assertionException("after 30 ticks " + moved + " cobblestone had moved; one a tick means about 30");
			}

			if (box.getItem(0).getCount() + moved != STACK) {
				throw helper.assertionException("cobblestone went missing in transit: " + box.getItem(0).getCount() + " left, " + moved + " moved");
			}
		});

		helper.runAtTickTime(100, () -> {
			if (!box.isEmpty() || count(home, Items.COBBLESTONE) != STACK + 1) {
				throw helper.assertionException("the stack did not all arrive: box " + describe(box.getItem(0))
						+ ", crate " + count(home, Items.COBBLESTONE));
			}

			helper.succeed();
		});
	}

	/** The nearer home gets the item: the walk is breadth-first and the box fills in that order. */
	@GameTest(maxTicks = 100)
	public void theNearestHomeIsFilledFirst(GameTestHelper helper) {
		DepositBoxBlockEntity box = depositBox(helper, BOX, new ItemStack(Items.COBBLESTONE, 4));
		link(helper, LINK_A, LINK_B, new BlockPos(4, 1, 1), new BlockPos(5, 1, 1));
		CrateBlockEntity near = crate(helper, LINK_A.north(), Items.COBBLESTONE, 1);
		CrateBlockEntity far = crate(helper, new BlockPos(6, 1, 1), Items.COBBLESTONE, 1);

		helper.runAtTickTime(40, () -> {
			if (!box.isEmpty() || count(near, Items.COBBLESTONE) != 5 || count(far, Items.COBBLESTONE) != 1) {
				throw helper.assertionException("expected the near crate to take all 4: near " + count(near, Items.COBBLESTONE)
						+ ", far " + count(far, Items.COBBLESTONE) + ", box " + describe(box.getItem(0)));
			}

			helper.succeed();
		});
	}

	/** A full home is skipped for the next, so a run of crates holding one item fills along. */
	@GameTest(maxTicks = 100)
	public void aFullHomeIsSkippedForTheNext(GameTestHelper helper) {
		DepositBoxBlockEntity box = depositBox(helper, BOX, new ItemStack(Items.COBBLESTONE, 4));
		link(helper, LINK_A, LINK_B);
		CrateBlockEntity full = place(helper, LINK_A.north(), LodestoreBlocks.BASIC_CRATE);

		for (int slot = 0; slot < full.storageSize(); slot++) {
			full.contents().setItem(slot, new ItemStack(Items.COBBLESTONE, STACK));
		}

		full.setChanged();
		CrateBlockEntity spare = crate(helper, CRATE, Items.COBBLESTONE, 1);

		helper.runAtTickTime(40, () -> {
			if (!box.isEmpty() || count(spare, Items.COBBLESTONE) != 5) {
				throw helper.assertionException("the full crate blocked the flow: spare holds " + count(spare, Items.COBBLESTONE)
						+ ", box " + describe(box.getItem(0)));
			}

			helper.succeed();
		});
	}

	// --- what conducts -----------------------------------------------------------------

	/**
	 * Only connection blocks carry the network. A crate touching the box is on it; a crate on the
	 * far side of another device, or of an ordinary block, is not.
	 */
	@GameTest(maxTicks = 100)
	public void onlyConnectionBlocksConduct(GameTestHelper helper) {
		DepositBoxBlockEntity box = depositBox(helper, BOX, new ItemStack(Items.COBBLESTONE, 6));
		CrateBlockEntity touching = crate(helper, BOX.north(), Items.COBBLESTONE, 1);

		helper.setBlock(LINK_A, LodestoreBlocks.INTERFACE_BOX.defaultBlockState());
		CrateBlockEntity behindDevice = crate(helper, LINK_B, Items.COBBLESTONE, 1);

		helper.setBlock(BOX.south(), Blocks.STONE.defaultBlockState());
		CrateBlockEntity behindStone = crate(helper, BOX.south(2), Items.COBBLESTONE, 1);

		helper.runAtTickTime(40, () -> {
			if (!box.isEmpty() || count(touching, Items.COBBLESTONE) != 7) {
				throw helper.assertionException("a crate touching the box should have taken all 6, holds "
						+ count(touching, Items.COBBLESTONE));
			}

			if (count(behindDevice, Items.COBBLESTONE) != 1) {
				throw helper.assertionException("an interface box conducted the network to a crate behind it");
			}

			if (count(behindStone, Items.COBBLESTONE) != 1) {
				throw helper.assertionException("stone conducted the network");
			}

			helper.succeed();
		});
	}

	// --- hoppers ---------------------------------------------------------------------

	/** A hopper fills a deposit box, and a hopper under one takes nothing out: the network is the only way out. */
	@GameTest(maxTicks = 120)
	public void aHopperFeedsADepositBoxAndCannotEmptyIt(GameTestHelper helper) {
		BlockPos boxPos = new BlockPos(1, 2, 1);
		DepositBoxBlockEntity box = depositBox(helper, boxPos);
		helper.setBlock(boxPos.above(), Blocks.HOPPER.defaultBlockState());
		helper.setBlock(boxPos.below(), Blocks.HOPPER.defaultBlockState());
		HopperBlockEntity above = helper.getBlockEntity(boxPos.above(), HopperBlockEntity.class);
		HopperBlockEntity below = helper.getBlockEntity(boxPos.below(), HopperBlockEntity.class);
		above.setItem(0, new ItemStack(Items.DIRT, 4));

		helper.runAtTickTime(100, () -> {
			if (!above.isEmpty()) {
				throw helper.assertionException("the hopper above did not empty into the deposit box: " + describe(above.getItem(0)));
			}

			if (box.countItem(Items.DIRT) != 4) {
				throw helper.assertionException("the deposit box should hold 4 dirt, holds " + box.countItem(Items.DIRT));
			}

			if (!below.isEmpty()) {
				throw helper.assertionException("the hopper below pulled " + describe(below.getItem(0)) + " out of the deposit box");
			}

			helper.succeed();
		});
	}

	// --- pulling -----------------------------------------------------------------------

	/**
	 * A hopper minecart parked on an interface box is emptied into the network, one item a tick,
	 * and only of what the network has a place for: the dirt, with the homes locked and no
	 * overflow, stays aboard. The chest alongside is a second interface
	 * box's source, and proves a block container is drawn from the same way.
	 */
	@GameTest(maxTicks = 120)
	public void theInterfacePullsFromWhatSitsOnTopOfIt(GameTestHelper helper) {
		BlockPos interfacePos = new BlockPos(1, 1, 1);
		helper.setBlock(interfacePos, LodestoreBlocks.INTERFACE_BOX.defaultBlockState());
		link(helper, LINK_A, LINK_B);
		CrateBlockEntity home = lockedCrate(helper, CRATE, Items.COBBLESTONE, 1);

		helper.setBlock(interfacePos.above(), Blocks.RAIL.defaultBlockState());
		MinecartHopper minecart = helper.spawn(EntityTypes.HOPPER_MINECART, interfacePos.above());
		minecart.setItem(0, new ItemStack(Items.COBBLESTONE, 10));
		minecart.setItem(1, new ItemStack(Items.DIRT, 3));

		BlockPos chestInterface = new BlockPos(1, 1, 3);
		helper.setBlock(chestInterface, LodestoreBlocks.INTERFACE_BOX.defaultBlockState());
		link(helper, new BlockPos(2, 1, 3), new BlockPos(3, 1, 3));
		CrateBlockEntity chestHome = lockedCrate(helper, new BlockPos(4, 1, 3), Items.OAK_LOG, 1);
		helper.setBlock(chestInterface.above(), Blocks.CHEST.defaultBlockState());
		ChestBlockEntity chest = helper.getBlockEntity(chestInterface.above(), ChestBlockEntity.class);
		chest.setItem(0, new ItemStack(Items.OAK_LOG, 6));
		chest.setItem(1, new ItemStack(Items.DIRT, 2));

		helper.runAtTickTime(80, () -> {
			if (!minecart.getItem(0).isEmpty() || count(home, Items.COBBLESTONE) != 11) {
				throw helper.assertionException("the minecart's cobblestone did not reach its home: minecart holds "
						+ describe(minecart.getItem(0)) + ", crate " + count(home, Items.COBBLESTONE));
			}

			if (minecart.getItem(1).getCount() != 3) {
				throw helper.assertionException("dirt with no home was taken from the minecart: " + describe(minecart.getItem(1)));
			}

			if (!chest.getItem(0).isEmpty() || count(chestHome, Items.OAK_LOG) != 7 || chest.getItem(1).getCount() != 2) {
				throw helper.assertionException("the chest was not drawn from as the minecart was: holds "
						+ describe(chest.getItem(0)) + " and " + describe(chest.getItem(1)) + ", crate " + count(chestHome, Items.OAK_LOG));
			}

			helper.succeed();
		});
	}

	/** A crate on top of an interface box is storage, not a feeder: nothing moves. */
	@GameTest(maxTicks = 80)
	public void theInterfaceDoesNotPullFromACrate(GameTestHelper helper) {
		BlockPos interfacePos = new BlockPos(1, 1, 1);
		helper.setBlock(interfacePos, LodestoreBlocks.INTERFACE_BOX.defaultBlockState());
		link(helper, LINK_A, LINK_B);
		CrateBlockEntity other = crate(helper, CRATE, Items.COBBLESTONE, 1);
		CrateBlockEntity above = crate(helper, interfacePos.above(), Items.COBBLESTONE, 10);

		helper.runAtTickTime(60, () -> {
			if (count(above, Items.COBBLESTONE) != 10 || count(other, Items.COBBLESTONE) != 1) {
				throw helper.assertionException("the interface pulled from the crate on top of it: " + count(above, Items.COBBLESTONE)
						+ " left above, " + count(other, Items.COBBLESTONE) + " in the other crate");
			}

			helper.succeed();
		});
	}

	// --- the view ----------------------------------------------------------------------

	/**
	 * The interface sums every crate it can reach, parked remainders included, one entry per kind,
	 * most numerous first; a crate off the network is not in the sum.
	 */
	@GameTest
	public void theInterfaceSumsEveryCrateOnTheNetwork(GameTestHelper helper) {
		helper.setBlock(BOX, LodestoreBlocks.INTERFACE_BOX.defaultBlockState());
		InterfaceBoxBlockEntity box = helper.getBlockEntity(BOX, InterfaceBoxBlockEntity.class);
		link(helper, LINK_A, LINK_B);

		CrateBlockEntity first = crate(helper, CRATE, Items.COBBLESTONE, 10);
		first.contents().setItem(1, new ItemStack(Items.DIRT, 3));
		first.setChanged();

		CrateBlockEntity second = place(helper, LINK_B.north(), LodestoreBlocks.BASIC_CRATE);
		second.installUpgrade(new ItemStack(LodestoreItems.STACK_UPGRADE));
		long parked;

		try (Transaction transaction = Transaction.openOuter()) {
			parked = second.storage().insert(ItemVariant.of(Items.COBBLESTONE), 200, transaction);
			transaction.commit();
		}

		if (parked != 200 || second.remainder(0) == 0) {
			throw helper.assertionException("the stacked crate did not park 200 cobblestone; the test is set up wrong");
		}

		crate(helper, new BlockPos(6, 1, 1), Items.DIAMOND, 9);

		NetworkView view = box.view();

		if (view.crates() != 2) {
			throw helper.assertionException("the interface should reach 2 crates, reaches " + view.crates());
		}

		List<NetworkEntry> entries = view.entries();

		if (entries.size() != 2) {
			throw helper.assertionException("expected cobblestone and dirt, got " + entries);
		}

		if (!entries.get(0).kind().matches(new ItemStack(Items.COBBLESTONE)) || entries.get(0).count() != 210) {
			throw helper.assertionException("the first entry should be 210 cobblestone, is " + entries.get(0));
		}

		if (!entries.get(1).kind().matches(new ItemStack(Items.DIRT)) || entries.get(1).count() != 3) {
			throw helper.assertionException("the second entry should be 3 dirt, is " + entries.get(1));
		}

		// Off the network entirely: the view says so, rather than showing an empty network.
		helper.setBlock(new BlockPos(1, 1, 5), LodestoreBlocks.INTERFACE_BOX.defaultBlockState());
		NetworkView alone = helper.getBlockEntity(new BlockPos(1, 1, 5), InterfaceBoxBlockEntity.class).view();

		if (alone.crates() != 0 || !alone.isEmpty()) {
			throw helper.assertionException("an interface box with nothing attached reports " + alone);
		}

		helper.succeed();
	}

	/** The walk from a deposit box and from an interface box in the same place agree. */
	@GameTest
	public void theWalkIsTheSameFromEitherDevice(GameTestHelper helper) {
		link(helper, LINK_A, LINK_B);
		crate(helper, CRATE, Items.COBBLESTONE, 1);
		crate(helper, LINK_A.north(), Items.DIRT, 1);

		List<BlockPos> fromBox = StorageNetwork.cratePositions(helper.getLevel(), helper.absolutePos(BOX));

		if (fromBox.size() != 2 || !fromBox.get(0).equals(helper.absolutePos(LINK_A.north()))
				|| !fromBox.get(1).equals(helper.absolutePos(CRATE))) {
			throw helper.assertionException("expected the near crate then the far one, got " + fromBox);
		}

		helper.succeed();
	}

	// --- helpers -------------------------------------------------------------------------

	private static DepositBoxBlockEntity depositBox(GameTestHelper helper, BlockPos pos, ItemStack... load) {
		helper.setBlock(pos, LodestoreBlocks.DEPOSIT_BOX.defaultBlockState());
		DepositBoxBlockEntity box = helper.getBlockEntity(pos, DepositBoxBlockEntity.class);

		for (int slot = 0; slot < load.length; slot++) {
			box.setItem(slot, load[slot]);
		}

		return box;
	}

	private static void link(GameTestHelper helper, BlockPos... positions) {
		for (BlockPos pos : positions) {
			helper.setBlock(pos, LodestoreBlocks.CONNECTION_BLOCK.defaultBlockState());
		}
	}

	private static CrateBlockEntity place(GameTestHelper helper, BlockPos pos, Block block) {
		helper.setBlock(pos, block.defaultBlockState());
		return helper.getBlockEntity(pos, CrateBlockEntity.class);
	}

	/** A basic crate holding {@code count} of {@code item} in its first slot. */
	private static CrateBlockEntity crate(GameTestHelper helper, BlockPos pos, net.minecraft.world.item.Item item, int count) {
		CrateBlockEntity crate = place(helper, pos, LodestoreBlocks.BASIC_CRATE);
		crate.contents().setItem(0, new ItemStack(item, count));
		crate.setChanged();
		return crate;
	}

	/** A basic crate holding {@code count} of {@code item}, with a lock claiming it: a home and nothing else. */
	private static CrateBlockEntity lockedCrate(GameTestHelper helper, BlockPos pos, net.minecraft.world.item.Item item, int count) {
		CrateBlockEntity crate = crate(helper, pos, item, count);
		crate.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));
		return crate;
	}

	/**
	 * A crate with every slot full of {@code item}, unlocked unless {@code locked}, voiding if
	 * {@code voids}. Sturdy, because a basic crate has one upgrade slot and the second upgrade
	 * would quietly not go in.
	 */
	private static CrateBlockEntity fullCrate(GameTestHelper helper, BlockPos pos, net.minecraft.world.item.Item item,
			boolean locked, boolean voids) {
		CrateBlockEntity crate = place(helper, pos, LodestoreBlocks.STURDY_CRATE);
		int max = new ItemStack(item).getMaxStackSize();

		for (int slot = 0; slot < crate.storageSize(); slot++) {
			crate.contents().setItem(slot, new ItemStack(item, max));
		}

		crate.setChanged();

		if (locked && !crate.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE))) {
			throw helper.assertionException("the lock would not install; the test is set up wrong");
		}

		if (voids && !crate.installUpgrade(new ItemStack(LodestoreItems.VOID_UPGRADE))) {
			throw helper.assertionException("the void upgrade would not install; the test is set up wrong");
		}

		return crate;
	}

	private static long count(CrateBlockEntity crate, net.minecraft.world.item.Item item) {
		long total = 0;

		for (int slot = 0; slot < crate.storageSize(); slot++) {
			if (crate.contents().visible(slot).is(item)) {
				total += crate.total(slot);
			}
		}

		return total;
	}

	private static String describe(ItemStack stack) {
		return stack.isEmpty() ? "nothing" : stack.getCount() + "x " + stack.getHoverName().getString();
	}
}
