package dev.fricker.lodestore.testmod;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.RegistryOps;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.minecart.MinecartHopper;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;

import dev.fricker.lodestore.block.CrateBlock;
import dev.fricker.lodestore.block.CrateBlockEntity;
import dev.fricker.lodestore.item.InstalledUpgrades;
import dev.fricker.lodestore.menu.CrateMenu;
import dev.fricker.lodestore.registry.LodestoreBlocks;
import dev.fricker.lodestore.registry.LodestoreComponents;
import dev.fricker.lodestore.registry.LodestoreItems;
import dev.fricker.lodestore.storage.CrateOverflow;

/**
 * Lodestore's own storage layer, and the stack upgrade it exists for.
 *
 * <p>A slot is a legal stack plus a parked remainder. Everything here checks that the remainder is
 * reached by every path that should reach it — hoppers, droppers, hopper minecarts, the menu's
 * clicks and shift-clicks — and never escapes by any path: no over-sized stack is ever handed to a
 * cursor, a save or a dropped item, and no count is ever lowered by anything but a withdrawal.
 *
 * <p>In pairs where a single assertion would pass for broken code: a stacked crate against the same
 * crate without the upgrade, a parked remainder against a slot with none, an aborted transaction
 * against a committed one.
 *
 * <p>A stack upgrade covers the whole crate. Stacks stay where they are put; what moves on its own
 * is only what a slot holds past its capacity, when that capacity drops.
 */
public class StorageLayerGameTests {
	private static final BlockPos CRATE = new BlockPos(1, 1, 1);
	private static final int STACK = 64;
	/** What the shipped stack upgrade grants: four stacks per covered slot. */
	private static final int DEPTH = 4;

	// --- hoppers and droppers ----------------------------------------------------

	/**
	 * The thing vanilla could not do: a hopper fills a slot past one stack.
	 *
	 * <p>{@code HopperBlockEntity.tryMoveInItem} computes room as the item's own stack size less
	 * the slot's count, with the container nowhere in it — so a crate that was a {@code Container}
	 * could tell a pipe the slot held 256 and the hopper bolted to its top would still stop at 64.
	 * Through the storage the hopper has no stack size to consult.
	 */
	@GameTest(maxTicks = 220)
	public void aHopperFillsAStackedSlotPastAStack(GameTestHelper helper) {
		CrateBlockEntity crate = stackedCrate(helper, LodestoreBlocks.BASIC_CRATE);
		crate.contents().setItem(0, new ItemStack(Items.COBBLESTONE, STACK));
		crate.setChanged();

		HopperBlockEntity hopper = hopperAbove(helper, new ItemStack(Items.COBBLESTONE, STACK));

		helper.runAtTickTime(200, () -> {
			if (crate.total(0) <= STACK || crate.remainder(0) == 0) {
				throw helper.assertionException("the hopper did not fill slot 0 past a stack: total "
						+ crate.total(0) + ", parked " + crate.remainder(0));
			}

			if (crate.contents().visible(0).getCount() != STACK) {
				throw helper.assertionException("the visible stack grew past a legal stack: "
						+ crate.contents().visible(0).getCount());
			}

			if (hopper.getItem(0).getCount() == STACK) {
				throw helper.assertionException("the hopper still holds everything it started with");
			}

			helper.succeed();
		});
	}

	/** The control: the same slot without the upgrade takes nothing more, and the hopper moves on. */
	@GameTest(maxTicks = 220)
	public void aHopperStopsAtAStackWithoutTheUpgrade(GameTestHelper helper) {
		CrateBlockEntity crate = place(helper, CRATE, LodestoreBlocks.BASIC_CRATE);
		crate.contents().setItem(0, new ItemStack(Items.COBBLESTONE, STACK));
		crate.setChanged();

		hopperAbove(helper, new ItemStack(Items.COBBLESTONE, STACK));

		helper.runAtTickTime(200, () -> {
			if (crate.total(0) != STACK || crate.remainder(0) != 0) {
				throw helper.assertionException("slot 0 grew past a stack with no stack upgrade installed: total "
						+ crate.total(0) + ", parked " + crate.remainder(0));
			}

			// It went somewhere, which proves the hopper was working and not merely blocked.
			if (crate.contents().visible(1).isEmpty()) {
				throw helper.assertionException("the hopper moved nothing at all; the control proves nothing");
			}

			helper.succeed();
		});
	}

	/** A hopper under a crate drains the parked remainder before the stack on show shrinks. */
	@GameTest(maxTicks = 220)
	public void aHopperDrainsAParkedRemainderFirst(GameTestHelper helper) {
		BlockPos cratePos = new BlockPos(1, 2, 1);
		CrateBlockEntity crate = stackedCrate(helper, cratePos, LodestoreBlocks.BASIC_CRATE);
		park(helper, crate, 0, Items.COBBLESTONE, STACK * 2);
		helper.setBlock(cratePos.below(), Blocks.HOPPER.defaultBlockState());

		helper.runAtTickTime(200, () -> {
			HopperBlockEntity hopper = helper.getBlockEntity(cratePos.below(), HopperBlockEntity.class);

			if (hopper.getItem(0).isEmpty()) {
				throw helper.assertionException("the hopper pulled nothing out of the crate");
			}

			if (crate.remainder(0) >= STACK) {
				throw helper.assertionException("the remainder did not drain: still " + crate.remainder(0));
			}

			if (crate.contents().visible(0).getCount() != STACK) {
				throw helper.assertionException("the visible stack shrank before the remainder was gone: "
						+ crate.contents().visible(0).getCount());
			}

			helper.succeed();
		});
	}

	/**
	 * Droppers are the hopper's path, not a second one: Fabric patches {@code DropperBlock} to
	 * use the storage when vanilla finds no container, and a crate is never a container.
	 */
	@GameTest(maxTicks = 100)
	public void aDropperInsertsIntoACrate(GameTestHelper helper) {
		CrateBlockEntity crate = place(helper, CRATE, LodestoreBlocks.BASIC_CRATE);
		DispenserBlockEntity dropper = dropperFacing(helper, new ItemStack(Items.COBBLESTONE, 5));

		helper.pulseRedstone(new BlockPos(1, 2, 2), 2);

		helper.runAtTickTime(40, () -> {
			if (crate.total(0) != 1 || !crate.contents().visible(0).is(Items.COBBLESTONE)) {
				throw helper.assertionException("the dropper did not put one cobblestone into the crate; slot 0 holds "
						+ crate.total(0));
			}

			if (dropper.getItem(0).getCount() != 4) {
				throw helper.assertionException("the dropper should have 4 left, has " + dropper.getItem(0).getCount());
			}

			helper.succeed();
		});
	}

	/** The control: a full crate makes the dropper keep its item, rather than throw it on the ground. */
	@GameTest(maxTicks = 100)
	public void aDropperKeepsWhatAFullCrateRefuses(GameTestHelper helper) {
		CrateBlockEntity crate = place(helper, CRATE, LodestoreBlocks.BASIC_CRATE);

		for (int slot = 0; slot < crate.storageSize(); slot++) {
			crate.contents().setItem(slot, new ItemStack(Items.DIAMOND, STACK));
		}

		crate.setChanged();
		DispenserBlockEntity dropper = dropperFacing(helper, new ItemStack(Items.COBBLESTONE, 5));

		helper.pulseRedstone(new BlockPos(1, 2, 2), 2);

		helper.runAtTickTime(40, () -> {
			if (dropper.getItem(0).getCount() != 5) {
				throw helper.assertionException("the dropper lost cobblestone into a full crate: "
						+ dropper.getItem(0).getCount() + " left");
			}

			if (!drops(helper, CRATE).isEmpty()) {
				throw helper.assertionException("the dropper threw its item on the ground instead");
			}

			helper.succeed();
		});
	}

	/** A hopper minecart runs the same static {@code suckInItems} a hopper block does. */
	@GameTest(maxTicks = 160)
	public void aHopperMinecartExtractsFromACrate(GameTestHelper helper) {
		BlockPos railPos = new BlockPos(1, 1, 1);
		BlockPos cratePos = railPos.above();
		helper.setBlock(railPos, Blocks.RAIL.defaultBlockState());
		CrateBlockEntity crate = place(helper, cratePos, LodestoreBlocks.BASIC_CRATE);
		crate.contents().setItem(0, new ItemStack(Items.COBBLESTONE, STACK));
		crate.setChanged();

		MinecartHopper minecart = helper.spawn(EntityTypes.HOPPER_MINECART, railPos);

		helper.runAtTickTime(140, () -> {
			if (minecart.getItem(0).isEmpty()) {
				throw helper.assertionException("the hopper minecart pulled nothing out of the crate above it");
			}

			if (crate.total(0) >= STACK) {
				throw helper.assertionException("the crate did not lose what the minecart took");
			}

			helper.succeed();
		});
	}

	// --- the menu ----------------------------------------------------------------

	/**
	 * One click takes one legal stack, and the slot refills from what was parked.
	 *
	 * <p>{@code AbstractContainerMenu#doClick} takes a slot's whole contents onto the cursor with no
	 * ceiling of any kind, so the fence is that the slot never holds more than a legal stack to
	 * take. What ends up on the cursor is checked against the item codec, because an over-sized
	 * stack cannot be written and would be destroyed by the next save.
	 */
	@GameTest
	public void aClickTakesOneLegalStackAndTheSlotRefills(GameTestHelper helper) {
		CrateBlockEntity crate = stackedCrate(helper, LodestoreBlocks.BASIC_CRATE);
		park(helper, crate, 0, Items.COBBLESTONE, 200);

		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		AbstractContainerMenu menu = open(helper, crate, player);
		menu.clicked(0, 0, ContainerInput.PICKUP, player);

		ItemStack carried = menu.getCarried();

		if (carried.getCount() != STACK || !carried.is(Items.COBBLESTONE)) {
			throw helper.assertionException("the cursor should hold one stack of cobblestone, holds " + describe(carried));
		}

		if (crate.contents().visible(0).getCount() != STACK || crate.remainder(0) != 200 - 2 * STACK) {
			throw helper.assertionException("the slot should show a full stack with " + (200 - 2 * STACK)
					+ " parked, shows " + crate.contents().visible(0).getCount() + " with " + crate.remainder(0));
		}

		if (crate.total(0) + carried.getCount() != 200) {
			throw helper.assertionException("items went missing: " + crate.total(0) + " in the crate and "
					+ carried.getCount() + " on the cursor, from 200");
		}

		boolean encodable = ItemStack.CODEC.encodeStart(
				helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE), carried).isSuccess();

		if (!encodable) {
			throw helper.assertionException("the stack on the cursor cannot be saved: " + describe(carried));
		}

		helper.succeed();
	}

	/** Placing onto a stacked slot that shows a full stack parks the excess instead of refusing it. */
	@GameTest
	public void placingOntoAStackedSlotParksTheExcess(GameTestHelper helper) {
		CrateBlockEntity crate = stackedCrate(helper, LodestoreBlocks.BASIC_CRATE);
		crate.contents().setItem(0, new ItemStack(Items.COBBLESTONE, STACK));
		crate.setChanged();

		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		AbstractContainerMenu menu = open(helper, crate, player);
		menu.setCarried(new ItemStack(Items.COBBLESTONE, STACK));
		menu.clicked(0, 0, ContainerInput.PICKUP, player);

		if (!menu.getCarried().isEmpty()) {
			throw helper.assertionException("a stacked slot with room refused the cursor's stack: "
					+ menu.getCarried().getCount() + " left on the cursor");
		}

		if (crate.total(0) != 2 * STACK || crate.contents().visible(0).getCount() != STACK || crate.remainder(0) != STACK) {
			throw helper.assertionException("expected a full stack shown with a stack parked, got "
					+ crate.contents().visible(0).getCount() + " shown and " + crate.remainder(0) + " parked");
		}

		helper.succeed();
	}

	/** The control: without the upgrade the same click places nothing, as in any chest. */
	@GameTest
	public void placingOntoAFullSlotWithoutTheUpgradeIsRefused(GameTestHelper helper) {
		CrateBlockEntity crate = place(helper, CRATE, LodestoreBlocks.BASIC_CRATE);
		crate.contents().setItem(0, new ItemStack(Items.COBBLESTONE, STACK));
		crate.setChanged();

		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		AbstractContainerMenu menu = open(helper, crate, player);
		menu.setCarried(new ItemStack(Items.COBBLESTONE, STACK));
		menu.clicked(0, 0, ContainerInput.PICKUP, player);

		if (menu.getCarried().getCount() != STACK || crate.total(0) != STACK) {
			throw helper.assertionException("a full slot with no stack upgrade took items: cursor "
					+ menu.getCarried().getCount() + ", slot " + crate.total(0));
		}

		helper.succeed();
	}

	/**
	 * Swapping a different item onto a slot with a parked remainder is refused.
	 *
	 * <p>The remainder is a count of the slot's current item, so the swap would leave it attached
	 * to the wrong one. Vanilla gates the swap on {@code Slot#mayPlace}, which is where the crate
	 * says no. The same swap on a slot with nothing parked goes through, as it would in a chest.
	 */
	@GameTest
	public void aSwapOntoAParkedRemainderIsRefused(GameTestHelper helper) {
		CrateBlockEntity crate = stackedCrate(helper, LodestoreBlocks.BASIC_CRATE);
		park(helper, crate, 0, Items.COBBLESTONE, STACK + 100);
		// A different item, or the crate would gather it into slot 0 before the test looked.
		crate.contents().setItem(1, new ItemStack(Items.OAK_LOG, STACK));
		crate.setChanged();

		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		AbstractContainerMenu menu = open(helper, crate, player);

		menu.setCarried(new ItemStack(Items.DIRT, 10));
		menu.clicked(0, 0, ContainerInput.PICKUP, player);

		if (!menu.getCarried().is(Items.DIRT) || menu.getCarried().getCount() != 10) {
			throw helper.assertionException("the swap onto a parked remainder went through; cursor now holds "
					+ describe(menu.getCarried()));
		}

		if (crate.total(0) != STACK + 100 || !crate.contents().visible(0).is(Items.COBBLESTONE)) {
			throw helper.assertionException("slot 0 changed under a refused swap: " + crate.total(0)
					+ " of " + describe(crate.contents().visible(0)));
		}

		// Nothing parked in slot 1, so the ordinary swap is allowed there.
		menu.clicked(1, 0, ContainerInput.PICKUP, player);

		if (!menu.getCarried().is(Items.OAK_LOG) || menu.getCarried().getCount() != STACK) {
			throw helper.assertionException("the swap on a slot with nothing parked was refused; cursor holds "
					+ describe(menu.getCarried()));
		}

		if (!crate.contents().visible(1).is(Items.DIRT) || crate.total(1) != 10) {
			throw helper.assertionException("slot 1 should now hold 10 dirt, holds " + crate.total(1)
					+ " of " + describe(crate.contents().visible(1)));
		}

		helper.succeed();
	}

	/** Shift-click into a stacked crate reaches the parked space; into a plain one it takes the next slot. */
	@GameTest
	public void shiftClickingInFillsThroughTheParkedSpace(GameTestHelper helper) {
		CrateBlockEntity stacked = stackedCrate(helper, CRATE, LodestoreBlocks.BASIC_CRATE);
		CrateBlockEntity plain = place(helper, new BlockPos(3, 1, 1), LodestoreBlocks.BASIC_CRATE);

		for (CrateBlockEntity crate : List.of(stacked, plain)) {
			crate.contents().setItem(0, new ItemStack(Items.COBBLESTONE, STACK));
			crate.setChanged();
		}

		long moved = shiftClickIn(helper, stacked, new ItemStack(Items.COBBLESTONE, STACK));

		if (moved != STACK || stacked.total(0) != 2 * STACK || stacked.total(1) != 0) {
			throw helper.assertionException("shift-click moved " + moved + " into a stacked crate; slot 0 holds "
					+ stacked.total(0) + " and slot 1 " + stacked.total(1));
		}

		moved = shiftClickIn(helper, plain, new ItemStack(Items.COBBLESTONE, STACK));

		if (moved != STACK || plain.total(0) != STACK || plain.total(1) != STACK) {
			throw helper.assertionException("shift-click into a plain crate should fill the next slot: moved "
					+ moved + ", slot 0 holds " + plain.total(0) + " and slot 1 " + plain.total(1));
		}

		helper.succeed();
	}

	/**
	 * A stack moved out of a deep slot goes where it is put, and only that stack goes.
	 *
	 * <p>The crate does not gather an item into one slot after a change; a version that did
	 * followed a placed stack with the rest of its slot, which is not what moving a stack means.
	 */
	@GameTest
	public void aMovedStackStaysWhereItIsPut(GameTestHelper helper) {
		CrateBlockEntity crate = stackedCrate(helper, LodestoreBlocks.BASIC_CRATE);
		park(helper, crate, 5, Items.OAK_LOG, STACK * DEPTH);

		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		AbstractContainerMenu menu = open(helper, crate, player);
		menu.clicked(5, 0, ContainerInput.PICKUP, player);
		menu.clicked(0, 0, ContainerInput.PICKUP, player);

		if (!menu.getCarried().isEmpty()) {
			throw helper.assertionException("the stack did not go into the empty slot; cursor holds " + describe(menu.getCarried()));
		}

		if (crate.total(0) != STACK || crate.total(5) != STACK * DEPTH - STACK) {
			throw helper.assertionException("expected one stack in slot 0 and the rest left in slot 5, got "
					+ crate.total(0) + " and " + crate.total(5));
		}

		// Two half stacks of the same item in two slots stay two.
		crate.contents().setItem(1, new ItemStack(Items.COBBLESTONE, 30));
		crate.contents().setItem(2, new ItemStack(Items.COBBLESTONE, 30));
		crate.setChanged();

		if (crate.total(1) != 30 || crate.total(2) != 30) {
			throw helper.assertionException("the crate gathered stacks on its own: " + crate.total(1) + " and " + crate.total(2));
		}

		helper.succeed();
	}

	/**
	 * A stack upgrade sorts the crate the moment it goes in, by either door.
	 *
	 * <p>Gathers the cobblestone into one slot and orders by item, so the dirt that was in slot 7
	 * follows it into slot 1. The pair: a lock going in does none of this, so it is the stack
	 * upgrade and not installing that does it.
	 */
	@GameTest
	public void installingTheStackUpgradeSortsTheCrate(GameTestHelper helper) {
		CrateBlockEntity byHand = place(helper, CRATE, LodestoreBlocks.BASIC_CRATE);
		CrateBlockEntity byMenu = place(helper, new BlockPos(3, 1, 1), LodestoreBlocks.BASIC_CRATE);
		CrateBlockEntity control = place(helper, new BlockPos(5, 1, 1), LodestoreBlocks.BASIC_CRATE);

		for (CrateBlockEntity crate : List.of(byHand, byMenu, control)) {
			crate.contents().setItem(0, new ItemStack(Items.COBBLESTONE, STACK));
			crate.contents().setItem(5, new ItemStack(Items.COBBLESTONE, STACK));
			crate.contents().setItem(7, new ItemStack(Items.DIRT, 10));
			crate.setChanged();
		}

		byHand.installUpgrade(new ItemStack(LodestoreItems.STACK_UPGRADE));

		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		AbstractContainerMenu menu = open(helper, byMenu, player);
		menu.setCarried(new ItemStack(LodestoreItems.STACK_UPGRADE));
		menu.clicked(byMenu.storageSize(), 0, ContainerInput.PICKUP, player);

		control.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));

		for (CrateBlockEntity crate : List.of(byHand, byMenu)) {
			if (crate.upgrades().size() != 1 || crate.total(0) != 2 * STACK || !crate.contents().visible(0).is(Items.COBBLESTONE)
					|| crate.total(1) != 10 || !crate.contents().visible(1).is(Items.DIRT)
					|| crate.total(5) != 0 || crate.total(7) != 0) {
				throw helper.assertionException("installing the stack upgrade did not sort the crate: "
						+ crate.upgrades().size() + " installed, slots hold " + describe(crate.contents().visible(0)) + ", "
						+ describe(crate.contents().visible(1)) + ", " + crate.total(5) + " and " + crate.total(7));
			}
		}

		if (control.total(0) != STACK || control.total(5) != STACK || control.total(7) != 10) {
			throw helper.assertionException("installing a lock sorted the crate: " + control.total(0) + ", "
					+ control.total(5) + " and " + control.total(7));
		}

		helper.succeed();
	}

	/**
	 * Right-clicking the installed stack upgrade sorts the crate again, on request.
	 *
	 * <p>Goes through the menu's button packet, which is what the screen sends. Items are ordered
	 * by id, cobblestone before oak logs, each gathered to one slot. The pair: a crate without a
	 * stack upgrade ignores the button and moves nothing — it is a chest, and a sorter's to sort,
	 * which is why only the stacked crate's grid is marked fake.
	 */
	@GameTest
	public void rightClickingTheStackUpgradeSortsTheCrate(GameTestHelper helper) {
		CrateBlockEntity stacked = stackedCrate(helper, CRATE, LodestoreBlocks.BASIC_CRATE);
		CrateBlockEntity plain = place(helper, new BlockPos(3, 1, 1), LodestoreBlocks.BASIC_CRATE);

		// Put after the upgrade went in, so nothing has sorted them yet.
		for (CrateBlockEntity crate : List.of(stacked, plain)) {
			crate.contents().setItem(0, new ItemStack(Items.OAK_LOG, STACK));
			crate.contents().setItem(3, new ItemStack(Items.COBBLESTONE, STACK));
			crate.contents().setItem(5, new ItemStack(Items.OAK_LOG, STACK));
			crate.contents().setItem(8, new ItemStack(Items.COBBLESTONE, 30));
			crate.setChanged();
		}

		if (stacked.total(0) != STACK || stacked.total(5) != STACK) {
			throw helper.assertionException("the crate sorted on its own before being asked");
		}

		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		AbstractContainerMenu menu = open(helper, stacked, player);

		if (!menu.getSlot(0).isFake() || !menu.getSlot(26).isFake()) {
			throw helper.assertionException("a stacked crate's grid should be fake, to keep click-based sorters out");
		}

		if (!menu.clickMenuButton(player, CrateMenu.SORT_BUTTON)
				|| stacked.total(0) != STACK + 30 || !stacked.contents().visible(0).is(Items.COBBLESTONE)
				|| stacked.total(1) != 2 * STACK || !stacked.contents().visible(1).is(Items.OAK_LOG)
				|| stacked.total(3) != 0 || stacked.total(5) != 0 || stacked.total(8) != 0) {
			throw helper.assertionException("the button did not sort the crate: " + describe(stacked.contents().visible(0))
					+ " (" + stacked.total(0) + "), " + describe(stacked.contents().visible(1)) + " (" + stacked.total(1) + ")");
		}

		AbstractContainerMenu plainMenu = open(helper, plain, player);

		if (plainMenu.getSlot(0).isFake()) {
			throw helper.assertionException("a plain crate's grid should not be fake; a sorter can sort it");
		}

		if (plainMenu.clickMenuButton(player, CrateMenu.SORT_BUTTON)
				|| plain.total(0) != STACK || plain.total(3) != STACK || plain.total(5) != STACK) {
			throw helper.assertionException("a crate without a stack upgrade acted on the button");
		}

		helper.succeed();
	}

	/**
	 * Sorting leaves the slot the face shows exactly as it is, and lays the rest out around it.
	 *
	 * <p>Oak logs sort last by id, so a sort that ignored the choice would put cobblestone in slot 0;
	 * the logs ending up there is the choice being kept, not the order happening to agree.
	 */
	@GameTest
	public void sortingLeavesTheShownSlotAlone(GameTestHelper helper) {
		CrateBlockEntity crate = place(helper, CRATE, LodestoreBlocks.STURDY_CRATE);
		crate.contents().setItem(0, new ItemStack(Items.OAK_LOG, STACK));
		crate.contents().setItem(2, new ItemStack(Items.DIRT, 5));
		crate.contents().setItem(3, new ItemStack(Items.COBBLESTONE, STACK));
		crate.contents().setItem(5, new ItemStack(Items.OAK_LOG, STACK));
		crate.contents().setItem(8, new ItemStack(Items.COBBLESTONE, 30));
		crate.setChanged();
		crate.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));

		if (!crate.chooseDisplaySlot(0) || crate.displaySlot() != 0) {
			throw helper.assertionException("the face would not take slot 0, shows slot " + crate.displaySlot());
		}

		// Installing sorts. The logs gather into the shown slot, which stays; the rest is ordered after it.
		crate.installUpgrade(new ItemStack(LodestoreItems.STACK_UPGRADE));

		if (crate.total(0) != 2 * STACK || !crate.contents().visible(0).is(Items.OAK_LOG)
				|| crate.total(1) != STACK + 30 || !crate.contents().visible(1).is(Items.COBBLESTONE)
				|| crate.total(2) != 5 || !crate.contents().visible(2).is(Items.DIRT)
				|| crate.total(3) != 0 || crate.total(5) != 0 || crate.total(8) != 0) {
			throw helper.assertionException("sorting around the shown slot went wrong: " + describe(crate.contents().visible(0))
					+ " (" + crate.total(0) + "), " + describe(crate.contents().visible(1)) + " (" + crate.total(1) + "), "
					+ describe(crate.contents().visible(2)) + " (" + crate.total(2) + ")");
		}

		helper.succeed();
	}

	/**
	 * Taking the stack upgrade out spreads what was parked into legal stacks of their own.
	 *
	 * <p>The pair: an upgrade the contents still need cannot be taken out — the slot refuses the
	 * pickup on both sides, and the menu's synced flag is what the client's slot predicts from —
	 * until enough slots are empty for the parked items to go somewhere.
	 */
	@GameTest
	public void takingTheStackUpgradeOutSpreadsItsStacks(GameTestHelper helper) {
		CrateBlockEntity crate = stackedCrate(helper, LodestoreBlocks.BASIC_CRATE);
		park(helper, crate, 0, Items.COBBLESTONE, 200);

		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		AbstractContainerMenu menu = open(helper, crate, player);
		int upgradeIndex = crate.storageSize();
		menu.clicked(upgradeIndex, 0, ContainerInput.PICKUP, player);

		if (!menu.getCarried().is(LodestoreItems.STACK_UPGRADE) || !crate.upgrades().isEmpty()) {
			throw helper.assertionException("the upgrade did not come out; cursor holds " + describe(menu.getCarried()));
		}

		long sum = 0;

		for (int slot = 0; slot < crate.storageSize(); slot++) {
			sum += crate.total(slot);

			if (crate.total(slot) > STACK) {
				throw helper.assertionException("slot " + slot + " still holds " + crate.total(slot) + " with no stack upgrade");
			}
		}

		if (sum != 200 || crate.total(0) != STACK || crate.total(3) != 200 - 3 * STACK) {
			throw helper.assertionException("expected 200 spread as 64, 64, 64, 8 from slot 0, got " + sum
					+ " with slot 0 at " + crate.total(0) + " and slot 3 at " + crate.total(3));
		}

		helper.succeed();
	}

	@GameTest
	public void anUpgradeTheContentsNeedCannotBeTakenOut(GameTestHelper helper) {
		CrateBlockEntity crate = stackedCrate(helper, LodestoreBlocks.BASIC_CRATE);
		park(helper, crate, 0, Items.COBBLESTONE, STACK * DEPTH);

		for (int slot = 1; slot < crate.storageSize(); slot++) {
			crate.contents().setItem(slot, new ItemStack(Items.DIRT, STACK));
		}

		crate.setChanged();

		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		AbstractContainerMenu menu = open(helper, crate, player);
		int upgradeIndex = crate.storageSize();
		menu.broadcastChanges();

		if (menu.getSlot(upgradeIndex).mayPickup(player)) {
			throw helper.assertionException("the slot lets the upgrade out of a crate with nowhere to spread 192 cobblestone");
		}

		menu.clicked(upgradeIndex, 0, ContainerInput.PICKUP, player);
		menu.clicked(upgradeIndex, 0, ContainerInput.QUICK_MOVE, player);

		if (crate.upgrades().size() != 1 || !menu.getCarried().isEmpty() || crate.total(0) != STACK * DEPTH) {
			throw helper.assertionException("the upgrade left anyway: " + crate.upgrades().size() + " installed, cursor "
					+ describe(menu.getCarried()) + ", slot 0 at " + crate.total(0));
		}

		// Two empty slots are not enough for three stacks' worth; three are.
		crate.contents().setItem(25, ItemStack.EMPTY);
		crate.contents().setItem(26, ItemStack.EMPTY);
		crate.setChanged();
		menu.broadcastChanges();

		if (menu.getSlot(upgradeIndex).mayPickup(player)) {
			throw helper.assertionException("two empty slots were taken as room for 192 cobblestone");
		}

		crate.contents().setItem(24, ItemStack.EMPTY);
		crate.setChanged();
		menu.broadcastChanges();

		if (!menu.getSlot(upgradeIndex).mayPickup(player)) {
			throw helper.assertionException("three empty slots should be room enough for 192 cobblestone");
		}

		// Taken out by something other than the menu — a pack change, say — nothing is lost: the
		// slot is over capacity, reports no room, and drains normally.
		crate.contents().setItem(24, new ItemStack(Items.DIRT, STACK));
		crate.contents().setItem(25, new ItemStack(Items.DIRT, STACK));
		crate.contents().setItem(26, new ItemStack(Items.DIRT, STACK));
		crate.upgradeContainer().removeItemNoUpdate(0);
		crate.setChanged();

		if (crate.total(0) != STACK * DEPTH || crate.slotCapacity(0, new ItemStack(Items.COBBLESTONE)) != STACK) {
			throw helper.assertionException("an over-capacity slot lost items or kept its capacity: "
					+ crate.total(0) + " at capacity " + crate.slotCapacity(0, new ItemStack(Items.COBBLESTONE)));
		}

		long intoSlot;

		try (Transaction transaction = Transaction.openOuter()) {
			intoSlot = crate.storage().getSlot(0).insert(ItemVariant.of(Items.COBBLESTONE), 1, transaction);
			transaction.commit();
		}

		if (intoSlot != 0) {
			throw helper.assertionException("an over-capacity slot accepted more");
		}

		long extracted;

		try (Transaction transaction = Transaction.openOuter()) {
			extracted = crate.storage().extract(ItemVariant.of(Items.COBBLESTONE), 150, transaction);
			transaction.commit();
		}

		if (extracted != 150 || crate.total(0) != STACK * DEPTH - 150) {
			throw helper.assertionException("draining an over-capacity slot went wrong: took " + extracted
					+ ", left " + crate.total(0));
		}

		helper.succeed();
	}

	/** An upgrade taken out through the menu goes back in through it, and the crate deepens again. */
	@GameTest
	public void anUpgradeTakenOutGoesBackIn(GameTestHelper helper) {
		CrateBlockEntity crate = stackedCrate(helper, LodestoreBlocks.BASIC_CRATE);
		park(helper, crate, 0, Items.COBBLESTONE, 100);

		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		AbstractContainerMenu menu = open(helper, crate, player);
		int upgradeIndex = crate.storageSize();

		menu.clicked(upgradeIndex, 0, ContainerInput.PICKUP, player);

		if (!crate.upgrades().isEmpty() || crate.total(0) != STACK || crate.total(1) != 100 - STACK) {
			throw helper.assertionException("taking the upgrade out did not spread 100 into 64 and 36: "
					+ crate.total(0) + " and " + crate.total(1));
		}

		menu.clicked(upgradeIndex, 0, ContainerInput.PICKUP, player);

		if (crate.upgrades().size() != 1 || !menu.getCarried().isEmpty()) {
			throw helper.assertionException("the upgrade did not go back in; cursor holds " + describe(menu.getCarried()));
		}

		// Going back in gathers the two stacks it had spread, as any installation does.
		if (crate.slotCapacity(0, new ItemStack(Items.COBBLESTONE)) != STACK * DEPTH
				|| crate.total(0) != 100 || crate.total(1) != 0) {
			throw helper.assertionException("putting the upgrade back did not deepen the slots and gather the 100: "
					+ crate.total(0) + " and " + crate.total(1) + " at " + crate.slotCapacity(0, new ItemStack(Items.COBBLESTONE)));
		}

		helper.succeed();
	}

	/**
	 * Upgrades are hardware, not goods: the storage refuses them through both doors.
	 *
	 * <p>This is also what keeps an inventory sorter from shuffling an installed upgrade into the
	 * grid — nothing in the container region will take it. A shift-clicked upgrade goes to the
	 * upgrade column, as it always did.
	 */
	@GameTest
	public void upgradesAreNotGoodsThroughEitherDoor(GameTestHelper helper) {
		CrateBlockEntity crate = place(helper, CRATE, LodestoreBlocks.BASIC_CRATE);

		if (insert(crate, LodestoreItems.LOCK_UPGRADE, 1) != 0) {
			throw helper.assertionException("the storage accepted an upgrade as goods");
		}

		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		AbstractContainerMenu menu = open(helper, crate, player);

		if (menu.getSlot(0).mayPlace(new ItemStack(LodestoreItems.LOCK_UPGRADE))) {
			throw helper.assertionException("the menu's slot accepted an upgrade as goods");
		}

		if (!menu.getSlot(0).mayPlace(new ItemStack(Items.DIRT)) || insert(crate, Items.DIRT, 1) != 1) {
			throw helper.assertionException("the crate refuses plain items; the test is measuring the wrong thing");
		}

		if (shiftClickIn(helper, crate, new ItemStack(LodestoreItems.LOCK_UPGRADE)) != 1
				|| crate.upgrades().size() != 1 || !crate.contents().visible(0).is(Items.DIRT)) {
			throw helper.assertionException("a shift-clicked upgrade did not land in the upgrade column: "
					+ crate.upgrades().size() + " installed, slot 0 holds " + describe(crate.contents().visible(0)));
		}

		// The column is marked fake, which is the flag Inventory Profiles Next leaves out of every
		// sortable area; the grid is not, or it could not be sorted at all.
		if (!menu.getSlot(crate.storageSize()).isFake() || menu.getSlot(0).isFake()) {
			throw helper.assertionException("the upgrade column should be the only fake slots: column "
					+ menu.getSlot(crate.storageSize()).isFake() + ", grid " + menu.getSlot(0).isFake());
		}

		helper.succeed();
	}

	/**
	 * Shift-clicking out of a stacked slot empties it stack by stack.
	 *
	 * <p>Vanilla keeps calling {@code quickMoveStack} for as long as the slot still holds the same
	 * item, which is exactly the loop a refilling slot needs: each pass moves one legal stack.
	 */
	@GameTest
	public void shiftClickingOutDrainsAParkedSlotStackByStack(GameTestHelper helper) {
		CrateBlockEntity crate = stackedCrate(helper, LodestoreBlocks.BASIC_CRATE);
		park(helper, crate, 0, Items.COBBLESTONE, 200);

		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		AbstractContainerMenu menu = open(helper, crate, player);
		menu.clicked(0, 0, ContainerInput.QUICK_MOVE, player);

		int inInventory = player.getInventory().countItem(Items.COBBLESTONE);

		if (inInventory != 200 || crate.total(0) != 0) {
			throw helper.assertionException("expected all 200 in the player's inventory and none in the slot, got "
					+ inInventory + " and " + crate.total(0));
		}

		helper.succeed();
	}

	/** The rules reach the menu's slots too, not only the storage: lock and nesting. */
	@GameTest
	public void theMenusSlotsEnforceTheLockAndNesting(GameTestHelper helper) {
		CrateBlockEntity locked = place(helper, new BlockPos(3, 1, 1), LodestoreBlocks.STURDY_CRATE);
		locked.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));
		locked.contents().setItem(0, new ItemStack(Items.COBBLESTONE, 4));
		locked.setChanged();

		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		AbstractContainerMenu lockedMenu = open(helper, locked, player);

		if (!lockedMenu.getSlot(20).mayPlace(new ItemStack(Items.COBBLESTONE))) {
			throw helper.assertionException("an empty slot of a crate locked to cobblestone refuses cobblestone");
		}

		if (lockedMenu.getSlot(0).mayPlace(new ItemStack(Items.DIRT))
				|| lockedMenu.getSlot(20).mayPlace(new ItemStack(Items.DIRT))) {
			throw helper.assertionException("the menu's slots let dirt into a crate locked to cobblestone");
		}

		// Nesting on a crate of its own: with a lock on as well, the lock would refuse the crate
		// items first and the nesting rule would never be what was tested.
		CrateBlockEntity sturdy = place(helper, CRATE, LodestoreBlocks.STURDY_CRATE);
		AbstractContainerMenu menu = open(helper, sturdy, player);

		ItemStack filled = new ItemStack(LodestoreBlocks.STURDY_CRATE);
		filled.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND, 5))));

		if (menu.getSlot(20).mayPlace(filled)) {
			throw helper.assertionException("the menu's slot lets a sturdy crate with contents nest inside a sturdy crate");
		}

		if (!menu.getSlot(20).mayPlace(new ItemStack(LodestoreBlocks.STURDY_CRATE))) {
			throw helper.assertionException("the menu's slot refuses an empty sturdy crate, which nests anywhere");
		}

		if (!menu.getSlot(20).mayPlace(new ItemStack(Items.DIRT))) {
			throw helper.assertionException("an unlocked crate refuses dirt; the test is measuring the wrong thing");
		}

		helper.succeed();
	}

	// --- breaking ----------------------------------------------------------------

	/** A basic crate spills every parked item, as repeated legal stacks. */
	@GameTest(maxTicks = 100)
	public void aBasicCrateSpillsItsParkedRemainder(GameTestHelper helper) {
		CrateBlockEntity crate = stackedCrate(helper, LodestoreBlocks.BASIC_CRATE);
		park(helper, crate, 0, Items.COBBLESTONE, 200);

		destroy(helper, CRATE);

		helper.runAtTickTime(5, () -> {
			List<ItemStack> drops = drops(helper, CRATE);
			long loose = count(drops, Items.COBBLESTONE);

			if (loose != 200) {
				throw helper.assertionException("a basic crate holding 200 cobblestone spilled " + loose);
			}

			for (ItemStack drop : drops) {
				if (drop.getCount() > drop.getMaxStackSize()) {
					throw helper.assertionException("an over-sized stack was spilled: " + describe(drop));
				}
			}

			// The contents spill; the upgrade stays in the crate.
			if (count(drops, LodestoreItems.STACK_UPGRADE) != 0) {
				throw helper.assertionException("the stack upgrade dropped loose instead of staying in the crate");
			}

			ItemStack dropped = drops.stream()
					.filter(stack -> stack.is(LodestoreBlocks.BASIC_CRATE.asItem()))
					.findFirst()
					.orElseThrow(() -> helper.assertionException("the crate did not drop at all"));
			List<ItemStack> carried = dropped.getOrDefault(LodestoreComponents.CRATE_UPGRADES, InstalledUpgrades.EMPTY)
					.upgradeStacks();

			if (carried.size() != 1 || !carried.getFirst().is(LodestoreItems.STACK_UPGRADE)) {
				throw helper.assertionException("the dropped crate does not carry its stack upgrade");
			}

			helper.succeed();
		});
	}

	/**
	 * A sturdy crate keeps its parked items inside the dropped item, and gives them back when
	 * placed.
	 *
	 * <p>{@code minecraft:container} carries the visible stacks, which are legal and so survive its
	 * validation; the remainders ride in Lodestore's own component, and the stack upgrade in another.
	 * Placing the drop back restores the crate as it was, the 200 still parked in one slot, which
	 * holds only if the upgrade goes back in before the contents settle. A drop stripped of its
	 * upgrades still gives back all 200, spread into legal stacks.
	 */
	@GameTest(maxTicks = 100)
	public void aSturdyCrateKeepsItsParkedRemainderWhenBroken(GameTestHelper helper) {
		CrateBlockEntity crate = stackedCrate(helper, LodestoreBlocks.STURDY_CRATE);
		park(helper, crate, 0, Items.COBBLESTONE, 200);

		destroy(helper, CRATE);

		helper.runAtTickTime(5, () -> {
			List<ItemStack> drops = drops(helper, CRATE);

			if (count(drops, Items.COBBLESTONE) != 0) {
				throw helper.assertionException("a sturdy crate spilled " + count(drops, Items.COBBLESTONE) + " cobblestone");
			}

			ItemStack dropped = drops.stream()
					.filter(stack -> stack.is(LodestoreBlocks.STURDY_CRATE.asItem()))
					.findFirst()
					.orElseThrow(() -> helper.assertionException("the crate did not drop at all"));

			ItemContainerContents contents = dropped.get(DataComponents.CONTAINER);
			CrateOverflow overflow = dropped.get(LodestoreComponents.CRATE_OVERFLOW);

			if (contents == null || contents.nonEmptyItemCopyStream().mapToLong(ItemStack::getCount).sum() != STACK) {
				throw helper.assertionException("the container component should carry one legal stack, carries "
						+ (contents == null ? "nothing" : contents.nonEmptyItemCopyStream().mapToLong(ItemStack::getCount).sum()));
			}

			if (overflow == null || overflow.total() != 200 - STACK) {
				throw helper.assertionException("the overflow component should carry " + (200 - STACK) + ", carries "
						+ (overflow == null ? "nothing" : overflow.total()));
			}

			if (count(drops, LodestoreItems.STACK_UPGRADE) != 0) {
				throw helper.assertionException("the stack upgrade dropped loose instead of staying in the crate");
			}

			// Placed back as it dropped: the upgrade is in again and the slot holds all 200.
			CrateBlockEntity restored = place(helper, new BlockPos(3, 1, 1), LodestoreBlocks.STURDY_CRATE);
			restored.applyComponentsFromItemStack(dropped);
			restored.setChanged();

			if (!restored.stacks() || restored.total(0) != 200 || restored.remainder(0) != 200 - STACK) {
				throw helper.assertionException("placing the drop back gave slot 0 " + restored.total(0) + " with "
						+ restored.remainder(0) + " parked, stack upgrade " + (restored.stacks() ? "in" : "missing")
						+ "; expected 200 with " + (200 - STACK) + " parked");
			}

			// Placed back stripped of its upgrade — another mod, hand editing: the 200 are all
			// there, spread into legal stacks because the slot no longer holds that many.
			ItemStack stripped = dropped.copy();
			stripped.remove(LodestoreComponents.CRATE_UPGRADES);
			CrateBlockEntity placed = place(helper, new BlockPos(5, 1, 1), LodestoreBlocks.STURDY_CRATE);
			placed.applyComponentsFromItemStack(stripped);
			long back = 0;

			for (int slot = 0; slot < placed.storageSize(); slot++) {
				if (placed.contents().visible(slot).is(Items.COBBLESTONE)) {
					back += placed.total(slot);
				}

				if (placed.total(slot) > STACK) {
					throw helper.assertionException("slot " + slot + " holds " + placed.total(slot) + " with no stack upgrade");
				}
			}

			if (back != 200) {
				throw helper.assertionException("placing the stripped drop back gave " + back + " cobblestone, not 200");
			}

			helper.succeed();
		});
	}

	// --- capacity changes and transactions ---------------------------------------

	/** An aborted insert leaves the remainder untouched; a committed one changes it. */
	@GameTest
	public void anAbortedInsertLeavesNoTrace(GameTestHelper helper) {
		CrateBlockEntity crate = stackedCrate(helper, LodestoreBlocks.BASIC_CRATE);
		park(helper, crate, 0, Items.COBBLESTONE, STACK + 50);

		try (Transaction transaction = Transaction.openOuter()) {
			crate.storage().insert(ItemVariant.of(Items.COBBLESTONE), 100, transaction);
			transaction.abort();
		}

		if (crate.total(0) != STACK + 50 || crate.remainder(0) != 50) {
			throw helper.assertionException("an aborted insert changed the slot: " + crate.total(0)
					+ " with " + crate.remainder(0) + " parked");
		}

		if (insert(crate, Items.COBBLESTONE, 100) != 100 || crate.total(0) != STACK + 150) {
			throw helper.assertionException("the committed insert did not land: " + crate.total(0));
		}

		helper.succeed();
	}

	/**
	 * Twenty-seven stacks fill a plain crate and leave a stacked one a quarter full.
	 *
	 * <p>The whole crate is filled rather than one slot, because one slot in twenty-seven is below
	 * the comparator's granularity — any non-zero fill reads at least 1, so a single slot at a
	 * quarter and a single slot full would read the same and prove nothing.
	 */
	@GameTest
	public void theComparatorMeasuresEachSlotAgainstItsCapacity(GameTestHelper helper) {
		BlockPos plainPos = new BlockPos(3, 1, 1);
		CrateBlockEntity stacked = stackedCrate(helper, CRATE, LodestoreBlocks.BASIC_CRATE);
		CrateBlockEntity plain = place(helper, plainPos, LodestoreBlocks.BASIC_CRATE);

		// Twenty-seven stacks each: a plain crate is full; the storage packs a stacked one's into
		// seven slots, and it is a quarter full.
		for (int slot = 0; slot < plain.storageSize(); slot++) {
			plain.contents().setItem(slot, new ItemStack(Items.COBBLESTONE, STACK));
		}

		plain.setChanged();
		long stacks = (long) stacked.storageSize() * STACK;

		if (insert(stacked, Items.COBBLESTONE, stacks) != stacks) {
			throw helper.assertionException("the stacked crate did not take " + stacks + " cobblestone");
		}

		int stackedSignal = signal(helper, CRATE);
		int plainSignal = signal(helper, plainPos);

		if (plainSignal != 15) {
			throw helper.assertionException("a plain crate with a stack in every slot should read 15, reads " + plainSignal);
		}

		if (stackedSignal >= plainSignal || stackedSignal <= 0) {
			throw helper.assertionException("a stacked crate holding the same reads " + stackedSignal
					+ "; it is a quarter full and should read below 15");
		}

		long rest = (long) stacked.storageSize() * STACK * DEPTH - stacks;

		if (insert(stacked, Items.COBBLESTONE, rest) != rest) {
			throw helper.assertionException("the stacked crate did not take the " + rest + " that fill it");
		}

		if (signal(helper, CRATE) != 15) {
			throw helper.assertionException("a stacked crate with every slot full should read 15, reads "
					+ signal(helper, CRATE));
		}

		helper.succeed();
	}

	// --- the upgrade's reach and depth ---------------------------------------------

	/**
	 * Every slot, three levels, two classes of item.
	 *
	 * <p>A stack upgrade covers the whole crate rather than chosen slots. An item that stacks holds
	 * its stack size times the level's multiplier — 256, 512 and 1,024 of a 64-stack, and a 16-stack
	 * scales the same way — and an item that does not stack holds the level's own count: 8, 16 and
	 * 32. The deepest installed level decides; nothing pools.
	 */
	@GameTest
	public void stackLevelsScaleByItemClass(GameTestHelper helper) {
		CrateBlockEntity crate = place(helper, CRATE, LodestoreBlocks.REINFORCED_CRATE);
		ItemStack cobble = new ItemStack(Items.COBBLESTONE);
		ItemStack pearl = new ItemStack(Items.ENDER_PEARL);
		ItemStack sword = new ItemStack(Items.IRON_SWORD);

		crate.installUpgrade(new ItemStack(LodestoreItems.STACK_UPGRADE));
		expectCapacity(helper, crate, cobble, 256);
		expectCapacity(helper, crate, pearl, 64);
		expectCapacity(helper, crate, sword, 8);

		if (crate.stackedSlots().size() != crate.storageSize() || !crate.isStackedSlot(crate.storageSize() - 1)) {
			throw helper.assertionException("a stack upgrade should cover every slot, covers " + crate.stackedSlots().size());
		}

		crate.installUpgrade(new ItemStack(LodestoreItems.STACK_UPGRADE_3));
		expectCapacity(helper, crate, cobble, 1024);
		expectCapacity(helper, crate, pearl, 256);
		expectCapacity(helper, crate, sword, 32);

		// A shallower level alongside changes nothing: the deepest decides, and nothing adds up.
		crate.installUpgrade(new ItemStack(LodestoreItems.STACK_UPGRADE_2));
		expectCapacity(helper, crate, cobble, 1024);
		expectCapacity(helper, crate, sword, 32);

		helper.succeed();
	}

	private static void expectCapacity(GameTestHelper helper, CrateBlockEntity crate, ItemStack stack, int expected) {
		for (int slot : new int[] {0, crate.storageSize() - 1}) {
			if (crate.slotCapacity(slot, stack) != expected) {
				throw helper.assertionException(describe(stack) + " should have a capacity of " + expected
						+ " in slot " + slot + ", has " + crate.slotCapacity(slot, stack));
			}
		}
	}

	/** The client gets the stack upgrade's two numbers and the parked counts. */
	@GameTest
	public void theSyncTagCarriesStackingForTheClient(GameTestHelper helper) {
		CrateBlockEntity crate = stackedCrate(helper, LodestoreBlocks.BASIC_CRATE);
		park(helper, crate, 0, Items.COBBLESTONE, 200);

		CompoundTag sync = crate.getUpdateTag(helper.getLevel().registryAccess());

		if (sync.getIntOr("stack_multiplier", 0) != DEPTH || sync.getIntOr("stack_unstackable", 0) != 8
				|| !sync.contains("synced_overflow")) {
			throw helper.assertionException("the sync tag is missing stacking state: " + sync);
		}

		if (sync.contains("Items")) {
			throw helper.assertionException("the sync tag carries the inventory, which it must never do");
		}

		// Loaded as a client would load it: no stacks arrive, and the counts have to stand alone.
		CrateBlockEntity client = new CrateBlockEntity(helper.absolutePos(CRATE), LodestoreBlocks.BASIC_CRATE.defaultBlockState());
		client.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), sync));

		if (client.remainder(0) != 200 - STACK) {
			throw helper.assertionException("the client's copy did not pick up the parked count: " + client.remainder(0));
		}

		helper.succeed();
	}

	// --- migration -----------------------------------------------------------------

	/**
	 * A crate saved before the storage layer existed loads with nothing lost.
	 *
	 * <p>The old block entity wrote {@code Items} through {@code ContainerHelper}, its upgrades,
	 * the slots its lock covered and what each had claimed, and the name and lock it inherited. This writes that shape by hand,
	 * rather than through the current code, and loads it into a fresh crate. The pair is the same
	 * tag with a remainder table added: a loader that ignored the table would pass the first and
	 * fail the second.
	 */
	@GameTest
	public void anOldWorldsCrateLoadsUnchanged(GameTestHelper helper) {
		TagValueOutput out = oldShapeTag(helper);
		CrateBlockEntity reloaded = reload(helper, out.buildResult());

		if (!reloaded.contents().visible(0).is(Items.COBBLESTONE) || reloaded.total(0) != 12) {
			throw helper.assertionException("slot 0 should hold 12 cobblestone, holds " + reloaded.total(0)
					+ " of " + describe(reloaded.contents().visible(0)));
		}

		if (!reloaded.contents().visible(26).is(Items.DIAMOND) || reloaded.total(26) != 3) {
			throw helper.assertionException("slot 26 should hold 3 diamonds, holds " + reloaded.total(26));
		}

		for (int slot = 0; slot < reloaded.storageSize(); slot++) {
			if (reloaded.remainder(slot) != 0) {
				throw helper.assertionException("an old save produced a parked count out of nowhere in slot " + slot);
			}
		}

		if (reloaded.upgrades().size() != 1 || !reloaded.upgrades().getFirst().is(LodestoreItems.LOCK_UPGRADE)) {
			throw helper.assertionException("the installed lock did not survive, got " + reloaded.upgrades().size() + " upgrade(s)");
		}

		// The old lock covered slots 0 and 5 and claimed cobblestone in slot 0. It covers the whole
		// crate now, so what it claims is the old claim and everything else the crate held.
		if (!claims(reloaded, Items.COBBLESTONE) || !claims(reloaded, Items.DIAMOND) || reloaded.lockedItems().size() != 2) {
			throw helper.assertionException("the old claim did not carry over: claims "
					+ reloaded.lockedItems().stream().map(StorageLayerGameTests::describe).toList());
		}

		if (reloaded.canStore(10, new ItemStack(Items.DIRT))) {
			throw helper.assertionException("a migrated lock lets in dirt it never claimed");
		}

		if (reloaded.getCustomName() == null || !"Spares".equals(reloaded.getCustomName().getString())) {
			throw helper.assertionException("the custom name did not survive: " + reloaded.getCustomName());
		}

		helper.succeed();
	}

	/**
	 * An old claim survives even where the slot it was made in has since been emptied, which is
	 * the point of a claim: the migration keeps it rather than rebuilding from the contents alone.
	 */
	@GameTest
	public void anOldLocksEmptiedClaimCarriesOver(GameTestHelper helper) {
		TagValueOutput out = oldShapeTag(helper);
		out.putIntArray("claim_slots", new int[] {5});
		out.store("claim_items", ItemStack.CODEC.listOf(), List.of(new ItemStack(Items.REDSTONE)));
		CrateBlockEntity reloaded = reload(helper, out.buildResult());

		if (!claims(reloaded, Items.REDSTONE)) {
			throw helper.assertionException("an emptied slot's old claim was lost: claims "
					+ reloaded.lockedItems().stream().map(StorageLayerGameTests::describe).toList());
		}

		if (!reloaded.canStore(5, new ItemStack(Items.REDSTONE))) {
			throw helper.assertionException("a migrated lock refuses the redstone it claimed");
		}

		helper.succeed();
	}

	private static boolean claims(CrateBlockEntity crate, net.minecraft.world.item.Item item) {
		return crate.lockedItems().stream().anyMatch(stack -> stack.is(item));
	}

	/** The pair: the same old-shaped tag with a remainder table restores the parked count. */
	@GameTest
	public void aSavedRemainderIsRestored(GameTestHelper helper) {
		TagValueOutput out = oldShapeTag(helper);
		out.store("overflow", CrateOverflow.CODEC, new CrateOverflow(List.of(new CrateOverflow.Entry(0, 100))));
		CrateBlockEntity reloaded = reload(helper, out.buildResult());

		if (reloaded.total(0) != 112 || reloaded.contents().visible(0).getCount() != STACK || reloaded.remainder(0) != 112 - STACK) {
			throw helper.assertionException("expected 112 cobblestone as a full stack with " + (112 - STACK)
					+ " parked, got " + reloaded.total(0) + " shown as " + reloaded.contents().visible(0).getCount()
					+ " with " + reloaded.remainder(0));
		}

		// A count with no stack to attach to is dropped, not invented.
		TagValueOutput orphan = oldShapeTag(helper);
		orphan.store("overflow", CrateOverflow.CODEC, new CrateOverflow(List.of(new CrateOverflow.Entry(10, 100))));

		if (reload(helper, orphan.buildResult()).total(10) != 0) {
			throw helper.assertionException("a parked count with no stack came back as items");
		}

		helper.succeed();
	}

	/**
	 * A world from v0.2.0-rc.1, when crates were drawers, still has its crates.
	 *
	 * <p>Each piece is written by hand under its old id and read back through the codec the game
	 * reads it with: the block through the one a chunk's palette uses, the item through a stack's,
	 * and the block entity through the loader that picks its type by id. Without the aliases the
	 * block and the item fail to decode and the block entity is never created.
	 */
	@GameTest
	public void aDrawerFromRc1LoadsAsACrate(GameTestHelper helper) {
		RegistryOps<Tag> ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);

		CompoundTag properties = new CompoundTag();
		properties.putString("facing", "east");
		CompoundTag savedState = new CompoundTag();
		savedState.putString("Name", "lodestore:reinforced_drawer");
		savedState.put("Properties", properties);
		BlockState state = BlockState.CODEC.parse(ops, savedState)
				.getOrThrow(error -> helper.assertionException("an old reinforced drawer did not load: " + error));

		if (!state.is(LodestoreBlocks.REINFORCED_CRATE) || state.getValue(CrateBlock.FACING) != Direction.EAST) {
			throw helper.assertionException("an old reinforced drawer loaded as " + state);
		}

		CompoundTag components = new CompoundTag();
		components.put("lodestore:drawer_overflow", CrateOverflow.CODEC.encodeStart(ops,
				new CrateOverflow(List.of(new CrateOverflow.Entry(0, 100)))).getOrThrow());
		CompoundTag savedItem = new CompoundTag();
		savedItem.putString("id", "lodestore:sturdy_drawer");
		savedItem.putInt("count", 1);
		savedItem.put("components", components);
		ItemStack item = ItemStack.CODEC.parse(ops, savedItem)
				.getOrThrow(error -> helper.assertionException("an old sturdy drawer item did not load: " + error));

		if (!item.is(LodestoreBlocks.STURDY_CRATE.asItem())
				|| item.getOrDefault(LodestoreComponents.CRATE_OVERFLOW, CrateOverflow.EMPTY).total() != 100) {
			throw helper.assertionException("an old sturdy drawer item loaded as " + item + " carrying "
					+ item.get(LodestoreComponents.CRATE_OVERFLOW));
		}

		CompoundTag savedEntity = new CompoundTag();
		savedEntity.putString("id", "lodestore:drawer");

		if (!(BlockEntity.loadStatic(helper.absolutePos(CRATE), LodestoreBlocks.BASIC_CRATE.defaultBlockState(),
				savedEntity, helper.getLevel().registryAccess()) instanceof CrateBlockEntity)) {
			throw helper.assertionException("an old drawer's block entity was not recreated as a crate's");
		}

		helper.succeed();
	}

	private static TagValueOutput oldShapeTag(GameTestHelper helper) {
		TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());

		// The old container was one slot larger than the storage: the overflow slot, always empty.
		NonNullList<ItemStack> items = NonNullList.withSize(28, ItemStack.EMPTY);
		items.set(0, new ItemStack(Items.COBBLESTONE, 12));
		items.set(26, new ItemStack(Items.DIAMOND, 3));
		ContainerHelper.saveAllItems(out, items);

		out.store("upgrades", ItemStack.CODEC.listOf(), List.of(new ItemStack(LodestoreItems.LOCK_UPGRADE)));
		out.putIntArray("lock_targets", new int[] {0, 5});
		out.putIntArray("claim_slots", new int[] {0});
		out.store("claim_items", ItemStack.CODEC.listOf(), List.of(new ItemStack(Items.COBBLESTONE)));
		out.store("CustomName", ComponentSerialization.CODEC, Component.literal("Spares"));
		return out;
	}

	private static CrateBlockEntity reload(GameTestHelper helper, CompoundTag tag) {
		CrateBlockEntity reloaded = new CrateBlockEntity(
				helper.absolutePos(CRATE), LodestoreBlocks.BASIC_CRATE.defaultBlockState());
		// Without a level the upgrade registry is unreachable and the lock reads as inert.
		reloaded.setLevel(helper.getLevel());
		reloaded.loadWithComponents(TagValueInput.create(
				ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), tag));
		return reloaded;
	}

	// --- helpers -------------------------------------------------------------------

	private static CrateBlockEntity stackedCrate(GameTestHelper helper, Block block) {
		return stackedCrate(helper, CRATE, block);
	}

	/** A crate with the shipped stack upgrade, which covers its first row at depth four. */
	private static CrateBlockEntity stackedCrate(GameTestHelper helper, BlockPos pos, Block block) {
		CrateBlockEntity crate = place(helper, pos, block);

		if (!crate.installUpgrade(new ItemStack(LodestoreItems.STACK_UPGRADE))) {
			throw helper.assertionException("the stack upgrade would not install");
		}

		if (!crate.isStackedSlot(0)) {
			throw helper.assertionException("the stack upgrade does not cover slot 0; the test is set up wrong");
		}

		return crate;
	}

	/** Adds {@code amount} of an item to one slot through its storage view, remainder included. */
	private static void park(GameTestHelper helper, CrateBlockEntity crate, int slot,
			net.minecraft.world.item.Item item, long amount) {
		long before = crate.total(slot);
		long accepted;

		try (Transaction transaction = Transaction.openOuter()) {
			accepted = crate.storage().getSlot(slot).insert(ItemVariant.of(item), amount, transaction);
			transaction.commit();
		}

		if (accepted != amount || crate.total(slot) != before + amount) {
			throw helper.assertionException("could not park " + amount + " in slot " + slot + ": accepted " + accepted
					+ ", slot went from " + before + " to " + crate.total(slot));
		}
	}

	private static long insert(CrateBlockEntity crate, net.minecraft.world.item.Item item, long amount) {
		try (Transaction transaction = Transaction.openOuter()) {
			long accepted = crate.storage().insert(ItemVariant.of(item), amount, transaction);
			transaction.commit();
			return accepted;
		}
	}

	private static AbstractContainerMenu open(GameTestHelper helper, CrateBlockEntity crate, Player player) {
		AbstractContainerMenu menu = crate.createMenu(1, player.getInventory(), player);

		if (menu == null) {
			throw helper.assertionException("the crate refused to open");
		}

		player.containerMenu = menu;
		return menu;
	}

	/** Shift-clicks {@code stack} from the player's inventory into the crate; returns how many moved. */
	private static long shiftClickIn(GameTestHelper helper, CrateBlockEntity crate, ItemStack stack) {
		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		AbstractContainerMenu menu = open(helper, crate, player);
		int count = stack.getCount();
		player.getInventory().setItem(9, stack);

		for (int index = 0; index < menu.slots.size(); index++) {
			if (menu.slots.get(index).container == player.getInventory() && menu.slots.get(index).getItem() == stack) {
				menu.clicked(index, 0, ContainerInput.QUICK_MOVE, player);
				return count - player.getInventory().countItem(stack.getItem());
			}
		}

		throw helper.assertionException("the player's stack is not in the menu");
	}

	private static HopperBlockEntity hopperAbove(GameTestHelper helper, ItemStack load) {
		helper.setBlock(CRATE.above(), Blocks.HOPPER.defaultBlockState());
		HopperBlockEntity hopper = helper.getBlockEntity(CRATE.above(), HopperBlockEntity.class);
		hopper.setItem(0, load);
		return hopper;
	}

	/** A dropper south of the crate, facing north into it. */
	private static DispenserBlockEntity dropperFacing(GameTestHelper helper, ItemStack load) {
		BlockPos pos = CRATE.south();
		helper.setBlock(pos, Blocks.DROPPER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.NORTH));
		DispenserBlockEntity dropper = helper.getBlockEntity(pos, DispenserBlockEntity.class);
		dropper.setItem(0, load);
		return dropper;
	}

	private static String describe(ItemStack stack) {
		return stack.isEmpty() ? "nothing" : stack.getCount() + "x " + stack.getHoverName().getString();
	}

	private static CrateBlockEntity place(GameTestHelper helper, BlockPos pos, Block block) {
		helper.setBlock(pos, block.defaultBlockState());
		return helper.getBlockEntity(pos, CrateBlockEntity.class);
	}

	private static void destroy(GameTestHelper helper, BlockPos pos) {
		// helper.destroyBlock does not run the loot pipeline, so nothing would drop at all.
		helper.getLevel().destroyBlock(helper.absolutePos(pos), true, null, 512);
	}

	private static List<ItemStack> drops(GameTestHelper helper, BlockPos pos) {
		BlockPos abs = helper.absolutePos(pos);
		return helper.getLevel()
				.getEntitiesOfClass(ItemEntity.class, new AABB(abs).inflate(6.0))
				.stream()
				.map(ItemEntity::getItem)
				.toList();
	}

	private static long count(List<ItemStack> stacks, net.minecraft.world.item.Item item) {
		return stacks.stream().filter(stack -> stack.is(item)).mapToLong(ItemStack::getCount).sum();
	}

	private static int signal(GameTestHelper helper, BlockPos relativePos) {
		BlockPos abs = helper.absolutePos(relativePos);
		return helper.getLevel().getBlockState(abs)
				.getAnalogOutputSignal(helper.getLevel(), abs, Direction.NORTH);
	}
}
