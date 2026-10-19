package dev.fricker.lodestore.testmod;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
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
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;

import dev.fricker.lodestore.api.drawer.UpgradeBehaviour;
import dev.fricker.lodestore.block.DrawerBlockEntity;
import dev.fricker.lodestore.menu.DrawerMenu;
import dev.fricker.lodestore.registry.LodestoreBlocks;
import dev.fricker.lodestore.registry.LodestoreComponents;
import dev.fricker.lodestore.registry.LodestoreItems;
import dev.fricker.lodestore.storage.DrawerOverflow;

/**
 * Lodestore's own storage layer, and the stack upgrade it exists for.
 *
 * <p>A slot is a legal stack plus a parked remainder. Everything here checks that the remainder is
 * reached by every path that should reach it — hoppers, droppers, hopper minecarts, the menu's
 * clicks and shift-clicks — and never escapes by any path: no over-sized stack is ever handed to a
 * cursor, a save or a dropped item, and no count is ever lowered by anything but a withdrawal.
 *
 * <p>In pairs where a single assertion would pass for broken code: a stacked drawer against the same
 * drawer without the upgrade, a parked remainder against a slot with none, an aborted transaction
 * against a committed one.
 *
 * <p>A stack upgrade covers the whole drawer. Stacks stay where they are put; what moves on its own
 * is only what a slot holds past its capacity, when that capacity drops.
 */
public class StorageLayerGameTests {
	private static final BlockPos DRAWER = new BlockPos(1, 1, 1);
	private static final int STACK = 64;
	/** What the shipped stack upgrade grants: four stacks per covered slot. */
	private static final int DEPTH = 4;

	// --- hoppers and droppers ----------------------------------------------------

	/**
	 * The thing vanilla could not do: a hopper fills a slot past one stack.
	 *
	 * <p>{@code HopperBlockEntity.tryMoveInItem} computes room as the item's own stack size less
	 * the slot's count, with the container nowhere in it — so a drawer that was a {@code Container}
	 * could tell a pipe the slot held 256 and the hopper bolted to its top would still stop at 64.
	 * Through the storage the hopper has no stack size to consult.
	 */
	@GameTest(maxTicks = 220)
	public void aHopperFillsAStackedSlotPastAStack(GameTestHelper helper) {
		DrawerBlockEntity drawer = stackedDrawer(helper, LodestoreBlocks.BASIC_DRAWER);
		drawer.contents().setItem(0, new ItemStack(Items.COBBLESTONE, STACK));
		drawer.setChanged();

		HopperBlockEntity hopper = hopperAbove(helper, new ItemStack(Items.COBBLESTONE, STACK));

		helper.runAtTickTime(200, () -> {
			if (drawer.total(0) <= STACK || drawer.remainder(0) == 0) {
				throw helper.assertionException("the hopper did not fill slot 0 past a stack: total "
						+ drawer.total(0) + ", parked " + drawer.remainder(0));
			}

			if (drawer.contents().visible(0).getCount() != STACK) {
				throw helper.assertionException("the visible stack grew past a legal stack: "
						+ drawer.contents().visible(0).getCount());
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
		DrawerBlockEntity drawer = place(helper, DRAWER, LodestoreBlocks.BASIC_DRAWER);
		drawer.contents().setItem(0, new ItemStack(Items.COBBLESTONE, STACK));
		drawer.setChanged();

		hopperAbove(helper, new ItemStack(Items.COBBLESTONE, STACK));

		helper.runAtTickTime(200, () -> {
			if (drawer.total(0) != STACK || drawer.remainder(0) != 0) {
				throw helper.assertionException("slot 0 grew past a stack with no stack upgrade installed: total "
						+ drawer.total(0) + ", parked " + drawer.remainder(0));
			}

			// It went somewhere, which proves the hopper was working and not merely blocked.
			if (drawer.contents().visible(1).isEmpty()) {
				throw helper.assertionException("the hopper moved nothing at all; the control proves nothing");
			}

			helper.succeed();
		});
	}

	/** A hopper under a drawer drains the parked remainder before the stack on show shrinks. */
	@GameTest(maxTicks = 220)
	public void aHopperDrainsAParkedRemainderFirst(GameTestHelper helper) {
		BlockPos drawerPos = new BlockPos(1, 2, 1);
		DrawerBlockEntity drawer = stackedDrawer(helper, drawerPos, LodestoreBlocks.BASIC_DRAWER);
		park(helper, drawer, 0, Items.COBBLESTONE, STACK * 2);
		helper.setBlock(drawerPos.below(), Blocks.HOPPER.defaultBlockState());

		helper.runAtTickTime(200, () -> {
			HopperBlockEntity hopper = helper.getBlockEntity(drawerPos.below(), HopperBlockEntity.class);

			if (hopper.getItem(0).isEmpty()) {
				throw helper.assertionException("the hopper pulled nothing out of the drawer");
			}

			if (drawer.remainder(0) >= STACK) {
				throw helper.assertionException("the remainder did not drain: still " + drawer.remainder(0));
			}

			if (drawer.contents().visible(0).getCount() != STACK) {
				throw helper.assertionException("the visible stack shrank before the remainder was gone: "
						+ drawer.contents().visible(0).getCount());
			}

			helper.succeed();
		});
	}

	/**
	 * Droppers are the hopper's path, not a second one: Fabric patches {@code DropperBlock} to
	 * use the storage when vanilla finds no container, and a drawer is never a container.
	 */
	@GameTest(maxTicks = 100)
	public void aDropperInsertsIntoADrawer(GameTestHelper helper) {
		DrawerBlockEntity drawer = place(helper, DRAWER, LodestoreBlocks.BASIC_DRAWER);
		DispenserBlockEntity dropper = dropperFacing(helper, new ItemStack(Items.COBBLESTONE, 5));

		helper.pulseRedstone(new BlockPos(1, 2, 2), 2);

		helper.runAtTickTime(40, () -> {
			if (drawer.total(0) != 1 || !drawer.contents().visible(0).is(Items.COBBLESTONE)) {
				throw helper.assertionException("the dropper did not put one cobblestone into the drawer; slot 0 holds "
						+ drawer.total(0));
			}

			if (dropper.getItem(0).getCount() != 4) {
				throw helper.assertionException("the dropper should have 4 left, has " + dropper.getItem(0).getCount());
			}

			helper.succeed();
		});
	}

	/** The control: a full drawer makes the dropper keep its item, rather than throw it on the ground. */
	@GameTest(maxTicks = 100)
	public void aDropperKeepsWhatAFullDrawerRefuses(GameTestHelper helper) {
		DrawerBlockEntity drawer = place(helper, DRAWER, LodestoreBlocks.BASIC_DRAWER);

		for (int slot = 0; slot < drawer.storageSize(); slot++) {
			drawer.contents().setItem(slot, new ItemStack(Items.DIAMOND, STACK));
		}

		drawer.setChanged();
		DispenserBlockEntity dropper = dropperFacing(helper, new ItemStack(Items.COBBLESTONE, 5));

		helper.pulseRedstone(new BlockPos(1, 2, 2), 2);

		helper.runAtTickTime(40, () -> {
			if (dropper.getItem(0).getCount() != 5) {
				throw helper.assertionException("the dropper lost cobblestone into a full drawer: "
						+ dropper.getItem(0).getCount() + " left");
			}

			if (!drops(helper, DRAWER).isEmpty()) {
				throw helper.assertionException("the dropper threw its item on the ground instead");
			}

			helper.succeed();
		});
	}

	/** A hopper minecart runs the same static {@code suckInItems} a hopper block does. */
	@GameTest(maxTicks = 160)
	public void aHopperMinecartExtractsFromADrawer(GameTestHelper helper) {
		BlockPos railPos = new BlockPos(1, 1, 1);
		BlockPos drawerPos = railPos.above();
		helper.setBlock(railPos, Blocks.RAIL.defaultBlockState());
		DrawerBlockEntity drawer = place(helper, drawerPos, LodestoreBlocks.BASIC_DRAWER);
		drawer.contents().setItem(0, new ItemStack(Items.COBBLESTONE, STACK));
		drawer.setChanged();

		MinecartHopper minecart = helper.spawn(EntityTypes.HOPPER_MINECART, railPos);

		helper.runAtTickTime(140, () -> {
			if (minecart.getItem(0).isEmpty()) {
				throw helper.assertionException("the hopper minecart pulled nothing out of the drawer above it");
			}

			if (drawer.total(0) >= STACK) {
				throw helper.assertionException("the drawer did not lose what the minecart took");
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
		DrawerBlockEntity drawer = stackedDrawer(helper, LodestoreBlocks.BASIC_DRAWER);
		park(helper, drawer, 0, Items.COBBLESTONE, 200);

		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		AbstractContainerMenu menu = open(helper, drawer, player);
		menu.clicked(0, 0, ContainerInput.PICKUP, player);

		ItemStack carried = menu.getCarried();

		if (carried.getCount() != STACK || !carried.is(Items.COBBLESTONE)) {
			throw helper.assertionException("the cursor should hold one stack of cobblestone, holds " + describe(carried));
		}

		if (drawer.contents().visible(0).getCount() != STACK || drawer.remainder(0) != 200 - 2 * STACK) {
			throw helper.assertionException("the slot should show a full stack with " + (200 - 2 * STACK)
					+ " parked, shows " + drawer.contents().visible(0).getCount() + " with " + drawer.remainder(0));
		}

		if (drawer.total(0) + carried.getCount() != 200) {
			throw helper.assertionException("items went missing: " + drawer.total(0) + " in the drawer and "
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
		DrawerBlockEntity drawer = stackedDrawer(helper, LodestoreBlocks.BASIC_DRAWER);
		drawer.contents().setItem(0, new ItemStack(Items.COBBLESTONE, STACK));
		drawer.setChanged();

		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		AbstractContainerMenu menu = open(helper, drawer, player);
		menu.setCarried(new ItemStack(Items.COBBLESTONE, STACK));
		menu.clicked(0, 0, ContainerInput.PICKUP, player);

		if (!menu.getCarried().isEmpty()) {
			throw helper.assertionException("a stacked slot with room refused the cursor's stack: "
					+ menu.getCarried().getCount() + " left on the cursor");
		}

		if (drawer.total(0) != 2 * STACK || drawer.contents().visible(0).getCount() != STACK || drawer.remainder(0) != STACK) {
			throw helper.assertionException("expected a full stack shown with a stack parked, got "
					+ drawer.contents().visible(0).getCount() + " shown and " + drawer.remainder(0) + " parked");
		}

		helper.succeed();
	}

	/** The control: without the upgrade the same click places nothing, as in any chest. */
	@GameTest
	public void placingOntoAFullSlotWithoutTheUpgradeIsRefused(GameTestHelper helper) {
		DrawerBlockEntity drawer = place(helper, DRAWER, LodestoreBlocks.BASIC_DRAWER);
		drawer.contents().setItem(0, new ItemStack(Items.COBBLESTONE, STACK));
		drawer.setChanged();

		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		AbstractContainerMenu menu = open(helper, drawer, player);
		menu.setCarried(new ItemStack(Items.COBBLESTONE, STACK));
		menu.clicked(0, 0, ContainerInput.PICKUP, player);

		if (menu.getCarried().getCount() != STACK || drawer.total(0) != STACK) {
			throw helper.assertionException("a full slot with no stack upgrade took items: cursor "
					+ menu.getCarried().getCount() + ", slot " + drawer.total(0));
		}

		helper.succeed();
	}

	/**
	 * Swapping a different item onto a slot with a parked remainder is refused.
	 *
	 * <p>The remainder is a count of the slot's current item, so the swap would leave it attached
	 * to the wrong one. Vanilla gates the swap on {@code Slot#mayPlace}, which is where the drawer
	 * says no. The same swap on a slot with nothing parked goes through, as it would in a chest.
	 */
	@GameTest
	public void aSwapOntoAParkedRemainderIsRefused(GameTestHelper helper) {
		DrawerBlockEntity drawer = stackedDrawer(helper, LodestoreBlocks.BASIC_DRAWER);
		park(helper, drawer, 0, Items.COBBLESTONE, STACK + 100);
		// A different item, or the drawer would gather it into slot 0 before the test looked.
		drawer.contents().setItem(1, new ItemStack(Items.OAK_LOG, STACK));
		drawer.setChanged();

		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		AbstractContainerMenu menu = open(helper, drawer, player);

		menu.setCarried(new ItemStack(Items.DIRT, 10));
		menu.clicked(0, 0, ContainerInput.PICKUP, player);

		if (!menu.getCarried().is(Items.DIRT) || menu.getCarried().getCount() != 10) {
			throw helper.assertionException("the swap onto a parked remainder went through; cursor now holds "
					+ describe(menu.getCarried()));
		}

		if (drawer.total(0) != STACK + 100 || !drawer.contents().visible(0).is(Items.COBBLESTONE)) {
			throw helper.assertionException("slot 0 changed under a refused swap: " + drawer.total(0)
					+ " of " + describe(drawer.contents().visible(0)));
		}

		// Nothing parked in slot 1, so the ordinary swap is allowed there.
		menu.clicked(1, 0, ContainerInput.PICKUP, player);

		if (!menu.getCarried().is(Items.OAK_LOG) || menu.getCarried().getCount() != STACK) {
			throw helper.assertionException("the swap on a slot with nothing parked was refused; cursor holds "
					+ describe(menu.getCarried()));
		}

		if (!drawer.contents().visible(1).is(Items.DIRT) || drawer.total(1) != 10) {
			throw helper.assertionException("slot 1 should now hold 10 dirt, holds " + drawer.total(1)
					+ " of " + describe(drawer.contents().visible(1)));
		}

		helper.succeed();
	}

	/** Shift-click into a stacked drawer reaches the parked space; into a plain one it takes the next slot. */
	@GameTest
	public void shiftClickingInFillsThroughTheParkedSpace(GameTestHelper helper) {
		DrawerBlockEntity stacked = stackedDrawer(helper, DRAWER, LodestoreBlocks.BASIC_DRAWER);
		DrawerBlockEntity plain = place(helper, new BlockPos(3, 1, 1), LodestoreBlocks.BASIC_DRAWER);

		for (DrawerBlockEntity drawer : List.of(stacked, plain)) {
			drawer.contents().setItem(0, new ItemStack(Items.COBBLESTONE, STACK));
			drawer.setChanged();
		}

		long moved = shiftClickIn(helper, stacked, new ItemStack(Items.COBBLESTONE, STACK));

		if (moved != STACK || stacked.total(0) != 2 * STACK || stacked.total(1) != 0) {
			throw helper.assertionException("shift-click moved " + moved + " into a stacked drawer; slot 0 holds "
					+ stacked.total(0) + " and slot 1 " + stacked.total(1));
		}

		moved = shiftClickIn(helper, plain, new ItemStack(Items.COBBLESTONE, STACK));

		if (moved != STACK || plain.total(0) != STACK || plain.total(1) != STACK) {
			throw helper.assertionException("shift-click into a plain drawer should fill the next slot: moved "
					+ moved + ", slot 0 holds " + plain.total(0) + " and slot 1 " + plain.total(1));
		}

		helper.succeed();
	}

	/**
	 * A stack moved out of a deep slot goes where it is put, and only that stack goes.
	 *
	 * <p>The drawer does not gather an item into one slot after a change; a version that did
	 * followed a placed stack with the rest of its slot, which is not what moving a stack means.
	 */
	@GameTest
	public void aMovedStackStaysWhereItIsPut(GameTestHelper helper) {
		DrawerBlockEntity drawer = stackedDrawer(helper, LodestoreBlocks.BASIC_DRAWER);
		park(helper, drawer, 5, Items.OAK_LOG, STACK * DEPTH);

		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		AbstractContainerMenu menu = open(helper, drawer, player);
		menu.clicked(5, 0, ContainerInput.PICKUP, player);
		menu.clicked(0, 0, ContainerInput.PICKUP, player);

		if (!menu.getCarried().isEmpty()) {
			throw helper.assertionException("the stack did not go into the empty slot; cursor holds " + describe(menu.getCarried()));
		}

		if (drawer.total(0) != STACK || drawer.total(5) != STACK * DEPTH - STACK) {
			throw helper.assertionException("expected one stack in slot 0 and the rest left in slot 5, got "
					+ drawer.total(0) + " and " + drawer.total(5));
		}

		// Two half stacks of the same item in two slots stay two.
		drawer.contents().setItem(1, new ItemStack(Items.COBBLESTONE, 30));
		drawer.contents().setItem(2, new ItemStack(Items.COBBLESTONE, 30));
		drawer.setChanged();

		if (drawer.total(1) != 30 || drawer.total(2) != 30) {
			throw helper.assertionException("the drawer gathered stacks on its own: " + drawer.total(1) + " and " + drawer.total(2));
		}

		helper.succeed();
	}

	/**
	 * A stack upgrade gathers the drawer's stacks the moment it goes in, by either door.
	 *
	 * <p>The pair: a lock going in gathers nothing, so it is the stack upgrade and not installing
	 * that does it. Only into slots already holding the item, so the dirt in slot 7 is untouched.
	 */
	@GameTest
	public void installingTheStackUpgradeGathersStacks(GameTestHelper helper) {
		DrawerBlockEntity byHand = place(helper, DRAWER, LodestoreBlocks.BASIC_DRAWER);
		DrawerBlockEntity byMenu = place(helper, new BlockPos(3, 1, 1), LodestoreBlocks.BASIC_DRAWER);
		DrawerBlockEntity control = place(helper, new BlockPos(5, 1, 1), LodestoreBlocks.BASIC_DRAWER);

		for (DrawerBlockEntity drawer : List.of(byHand, byMenu, control)) {
			drawer.contents().setItem(0, new ItemStack(Items.COBBLESTONE, STACK));
			drawer.contents().setItem(5, new ItemStack(Items.COBBLESTONE, STACK));
			drawer.contents().setItem(7, new ItemStack(Items.DIRT, 10));
			drawer.setChanged();
		}

		byHand.installUpgrade(new ItemStack(LodestoreItems.STACK_UPGRADE));

		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		AbstractContainerMenu menu = open(helper, byMenu, player);
		menu.setCarried(new ItemStack(LodestoreItems.STACK_UPGRADE));
		menu.clicked(byMenu.storageSize(), 0, ContainerInput.PICKUP, player);

		control.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));

		for (DrawerBlockEntity drawer : List.of(byHand, byMenu)) {
			if (drawer.upgrades().size() != 1 || drawer.total(0) != 2 * STACK || drawer.total(5) != 0 || drawer.total(7) != 10) {
				throw helper.assertionException("installing the stack upgrade did not gather the cobblestone: "
						+ drawer.upgrades().size() + " installed, slots hold " + drawer.total(0) + ", " + drawer.total(5)
						+ " and " + drawer.total(7));
			}
		}

		if (control.total(0) != STACK || control.total(5) != STACK) {
			throw helper.assertionException("installing a lock gathered stacks: " + control.total(0) + " and " + control.total(5));
		}

		helper.succeed();
	}

	/**
	 * Right-clicking the installed stack upgrade gathers the stacks again, on request.
	 *
	 * <p>Goes through the menu's button packet, which is what the screen sends. The pair: a drawer
	 * without a stack upgrade ignores the button and moves nothing.
	 */
	@GameTest
	public void rightClickingTheStackUpgradeGathersStacks(GameTestHelper helper) {
		DrawerBlockEntity stacked = stackedDrawer(helper, DRAWER, LodestoreBlocks.BASIC_DRAWER);
		DrawerBlockEntity plain = place(helper, new BlockPos(3, 1, 1), LodestoreBlocks.BASIC_DRAWER);

		for (DrawerBlockEntity drawer : List.of(stacked, plain)) {
			drawer.contents().setItem(0, new ItemStack(Items.COBBLESTONE, STACK));
			drawer.contents().setItem(5, new ItemStack(Items.COBBLESTONE, STACK));
			drawer.setChanged();
		}

		// Put after the upgrade went in, so nothing has gathered them yet.
		if (stacked.total(0) != STACK || stacked.total(5) != STACK) {
			throw helper.assertionException("the drawer gathered stacks on its own before being asked");
		}

		Player player = helper.makeMockPlayer(GameType.SURVIVAL);

		if (!open(helper, stacked, player).clickMenuButton(player, DrawerMenu.COMPRESS_BUTTON)
				|| stacked.total(0) != 2 * STACK || stacked.total(5) != 0) {
			throw helper.assertionException("the button did not gather the stacks: " + stacked.total(0) + " and " + stacked.total(5));
		}

		if (open(helper, plain, player).clickMenuButton(player, DrawerMenu.COMPRESS_BUTTON)
				|| plain.total(0) != STACK || plain.total(5) != STACK) {
			throw helper.assertionException("a drawer without a stack upgrade acted on the button: "
					+ plain.total(0) + " and " + plain.total(5));
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
		DrawerBlockEntity drawer = stackedDrawer(helper, LodestoreBlocks.BASIC_DRAWER);
		park(helper, drawer, 0, Items.COBBLESTONE, 200);

		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		AbstractContainerMenu menu = open(helper, drawer, player);
		int upgradeIndex = drawer.storageSize();
		menu.clicked(upgradeIndex, 0, ContainerInput.PICKUP, player);

		if (!menu.getCarried().is(LodestoreItems.STACK_UPGRADE) || !drawer.upgrades().isEmpty()) {
			throw helper.assertionException("the upgrade did not come out; cursor holds " + describe(menu.getCarried()));
		}

		long sum = 0;

		for (int slot = 0; slot < drawer.storageSize(); slot++) {
			sum += drawer.total(slot);

			if (drawer.total(slot) > STACK) {
				throw helper.assertionException("slot " + slot + " still holds " + drawer.total(slot) + " with no stack upgrade");
			}
		}

		if (sum != 200 || drawer.total(0) != STACK || drawer.total(3) != 200 - 3 * STACK) {
			throw helper.assertionException("expected 200 spread as 64, 64, 64, 8 from slot 0, got " + sum
					+ " with slot 0 at " + drawer.total(0) + " and slot 3 at " + drawer.total(3));
		}

		helper.succeed();
	}

	@GameTest
	public void anUpgradeTheContentsNeedCannotBeTakenOut(GameTestHelper helper) {
		DrawerBlockEntity drawer = stackedDrawer(helper, LodestoreBlocks.BASIC_DRAWER);
		park(helper, drawer, 0, Items.COBBLESTONE, STACK * DEPTH);

		for (int slot = 1; slot < drawer.storageSize(); slot++) {
			drawer.contents().setItem(slot, new ItemStack(Items.DIRT, STACK));
		}

		drawer.setChanged();

		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		AbstractContainerMenu menu = open(helper, drawer, player);
		int upgradeIndex = drawer.storageSize();
		menu.broadcastChanges();

		if (menu.getSlot(upgradeIndex).mayPickup(player)) {
			throw helper.assertionException("the slot lets the upgrade out of a drawer with nowhere to spread 192 cobblestone");
		}

		menu.clicked(upgradeIndex, 0, ContainerInput.PICKUP, player);
		menu.clicked(upgradeIndex, 0, ContainerInput.QUICK_MOVE, player);

		if (drawer.upgrades().size() != 1 || !menu.getCarried().isEmpty() || drawer.total(0) != STACK * DEPTH) {
			throw helper.assertionException("the upgrade left anyway: " + drawer.upgrades().size() + " installed, cursor "
					+ describe(menu.getCarried()) + ", slot 0 at " + drawer.total(0));
		}

		// Two empty slots are not enough for three stacks' worth; three are.
		drawer.contents().setItem(25, ItemStack.EMPTY);
		drawer.contents().setItem(26, ItemStack.EMPTY);
		drawer.setChanged();
		menu.broadcastChanges();

		if (menu.getSlot(upgradeIndex).mayPickup(player)) {
			throw helper.assertionException("two empty slots were taken as room for 192 cobblestone");
		}

		drawer.contents().setItem(24, ItemStack.EMPTY);
		drawer.setChanged();
		menu.broadcastChanges();

		if (!menu.getSlot(upgradeIndex).mayPickup(player)) {
			throw helper.assertionException("three empty slots should be room enough for 192 cobblestone");
		}

		// Taken out by something other than the menu — a pack change, say — nothing is lost: the
		// slot is over capacity, reports no room, and drains normally.
		drawer.contents().setItem(24, new ItemStack(Items.DIRT, STACK));
		drawer.contents().setItem(25, new ItemStack(Items.DIRT, STACK));
		drawer.contents().setItem(26, new ItemStack(Items.DIRT, STACK));
		drawer.upgradeContainer().removeItemNoUpdate(0);
		drawer.setChanged();

		if (drawer.total(0) != STACK * DEPTH || drawer.slotCapacity(0, new ItemStack(Items.COBBLESTONE)) != STACK) {
			throw helper.assertionException("an over-capacity slot lost items or kept its capacity: "
					+ drawer.total(0) + " at capacity " + drawer.slotCapacity(0, new ItemStack(Items.COBBLESTONE)));
		}

		long intoSlot;

		try (Transaction transaction = Transaction.openOuter()) {
			intoSlot = drawer.storage().getSlot(0).insert(ItemVariant.of(Items.COBBLESTONE), 1, transaction);
			transaction.commit();
		}

		if (intoSlot != 0) {
			throw helper.assertionException("an over-capacity slot accepted more");
		}

		long extracted;

		try (Transaction transaction = Transaction.openOuter()) {
			extracted = drawer.storage().extract(ItemVariant.of(Items.COBBLESTONE), 150, transaction);
			transaction.commit();
		}

		if (extracted != 150 || drawer.total(0) != STACK * DEPTH - 150) {
			throw helper.assertionException("draining an over-capacity slot went wrong: took " + extracted
					+ ", left " + drawer.total(0));
		}

		helper.succeed();
	}

	/** An upgrade taken out through the menu goes back in through it, and the drawer deepens again. */
	@GameTest
	public void anUpgradeTakenOutGoesBackIn(GameTestHelper helper) {
		DrawerBlockEntity drawer = stackedDrawer(helper, LodestoreBlocks.BASIC_DRAWER);
		park(helper, drawer, 0, Items.COBBLESTONE, 100);

		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		AbstractContainerMenu menu = open(helper, drawer, player);
		int upgradeIndex = drawer.storageSize();

		menu.clicked(upgradeIndex, 0, ContainerInput.PICKUP, player);

		if (!drawer.upgrades().isEmpty() || drawer.total(0) != STACK || drawer.total(1) != 100 - STACK) {
			throw helper.assertionException("taking the upgrade out did not spread 100 into 64 and 36: "
					+ drawer.total(0) + " and " + drawer.total(1));
		}

		menu.clicked(upgradeIndex, 0, ContainerInput.PICKUP, player);

		if (drawer.upgrades().size() != 1 || !menu.getCarried().isEmpty()) {
			throw helper.assertionException("the upgrade did not go back in; cursor holds " + describe(menu.getCarried()));
		}

		// Going back in gathers the two stacks it had spread, as any installation does.
		if (drawer.slotCapacity(0, new ItemStack(Items.COBBLESTONE)) != STACK * DEPTH
				|| drawer.total(0) != 100 || drawer.total(1) != 0) {
			throw helper.assertionException("putting the upgrade back did not deepen the slots and gather the 100: "
					+ drawer.total(0) + " and " + drawer.total(1) + " at " + drawer.slotCapacity(0, new ItemStack(Items.COBBLESTONE)));
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
		DrawerBlockEntity drawer = place(helper, DRAWER, LodestoreBlocks.BASIC_DRAWER);

		if (insert(drawer, LodestoreItems.LOCK_UPGRADE, 1) != 0) {
			throw helper.assertionException("the storage accepted an upgrade as goods");
		}

		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		AbstractContainerMenu menu = open(helper, drawer, player);

		if (menu.getSlot(0).mayPlace(new ItemStack(LodestoreItems.LOCK_UPGRADE))) {
			throw helper.assertionException("the menu's slot accepted an upgrade as goods");
		}

		if (!menu.getSlot(0).mayPlace(new ItemStack(Items.DIRT)) || insert(drawer, Items.DIRT, 1) != 1) {
			throw helper.assertionException("the drawer refuses plain items; the test is measuring the wrong thing");
		}

		if (shiftClickIn(helper, drawer, new ItemStack(LodestoreItems.LOCK_UPGRADE)) != 1
				|| drawer.upgrades().size() != 1 || !drawer.contents().visible(0).is(Items.DIRT)) {
			throw helper.assertionException("a shift-clicked upgrade did not land in the upgrade column: "
					+ drawer.upgrades().size() + " installed, slot 0 holds " + describe(drawer.contents().visible(0)));
		}

		// The column is marked fake, which is the flag Inventory Profiles Next leaves out of every
		// sortable area; the grid is not, or it could not be sorted at all.
		if (!menu.getSlot(drawer.storageSize()).isFake() || menu.getSlot(0).isFake()) {
			throw helper.assertionException("the upgrade column should be the only fake slots: column "
					+ menu.getSlot(drawer.storageSize()).isFake() + ", grid " + menu.getSlot(0).isFake());
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
		DrawerBlockEntity drawer = stackedDrawer(helper, LodestoreBlocks.BASIC_DRAWER);
		park(helper, drawer, 0, Items.COBBLESTONE, 200);

		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		AbstractContainerMenu menu = open(helper, drawer, player);
		menu.clicked(0, 0, ContainerInput.QUICK_MOVE, player);

		int inInventory = player.getInventory().countItem(Items.COBBLESTONE);

		if (inInventory != 200 || drawer.total(0) != 0) {
			throw helper.assertionException("expected all 200 in the player's inventory and none in the slot, got "
					+ inInventory + " and " + drawer.total(0));
		}

		helper.succeed();
	}

	/** The rules reach the menu's slots too, not only the storage: lock and nesting. */
	@GameTest
	public void theMenusSlotsEnforceTheLockAndNesting(GameTestHelper helper) {
		DrawerBlockEntity sturdy = place(helper, DRAWER, LodestoreBlocks.STURDY_DRAWER);
		sturdy.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));
		sturdy.contents().setItem(0, new ItemStack(Items.COBBLESTONE, 4));
		sturdy.setChanged();

		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		AbstractContainerMenu menu = open(helper, sturdy, player);

		if (!menu.getSlot(0).mayPlace(new ItemStack(Items.COBBLESTONE))) {
			throw helper.assertionException("the claimed slot refuses the item it claimed");
		}

		if (menu.getSlot(0).mayPlace(new ItemStack(Items.DIRT))) {
			throw helper.assertionException("the menu's slot lets dirt into a slot locked to cobblestone");
		}

		ItemStack filled = new ItemStack(LodestoreBlocks.STURDY_DRAWER);
		filled.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND, 5))));

		if (menu.getSlot(20).mayPlace(filled)) {
			throw helper.assertionException("the menu's slot lets a sturdy drawer with contents nest inside a sturdy drawer");
		}

		if (!menu.getSlot(20).mayPlace(new ItemStack(LodestoreBlocks.STURDY_DRAWER))) {
			throw helper.assertionException("the menu's slot refuses an empty sturdy drawer, which nests anywhere");
		}

		if (!menu.getSlot(20).mayPlace(new ItemStack(Items.DIRT))) {
			throw helper.assertionException("an uncovered slot refuses dirt; the test is measuring the wrong thing");
		}

		helper.succeed();
	}

	// --- breaking ----------------------------------------------------------------

	/** A basic drawer spills every parked item, as repeated legal stacks. */
	@GameTest(maxTicks = 100)
	public void aBasicDrawerSpillsItsParkedRemainder(GameTestHelper helper) {
		DrawerBlockEntity drawer = stackedDrawer(helper, LodestoreBlocks.BASIC_DRAWER);
		park(helper, drawer, 0, Items.COBBLESTONE, 200);

		destroy(helper, DRAWER);

		helper.runAtTickTime(5, () -> {
			List<ItemStack> drops = drops(helper, DRAWER);
			long loose = count(drops, Items.COBBLESTONE);

			if (loose != 200) {
				throw helper.assertionException("a basic drawer holding 200 cobblestone spilled " + loose);
			}

			for (ItemStack drop : drops) {
				if (drop.getCount() > drop.getMaxStackSize()) {
					throw helper.assertionException("an over-sized stack was spilled: " + describe(drop));
				}
			}

			if (count(drops, LodestoreItems.STACK_UPGRADE) != 1) {
				throw helper.assertionException("the stack upgrade did not drop");
			}

			helper.succeed();
		});
	}

	/**
	 * A sturdy drawer keeps its parked items inside the dropped item, and gives them back when
	 * placed.
	 *
	 * <p>{@code minecraft:container} carries the visible stacks, which are legal and so survive its
	 * validation; the remainders ride in Lodestore's own component. Placing the drop back restores
	 * the total even though the stack upgrade dropped separately — the slot is simply over capacity.
	 */
	@GameTest(maxTicks = 100)
	public void aSturdyDrawerKeepsItsParkedRemainderWhenBroken(GameTestHelper helper) {
		DrawerBlockEntity drawer = stackedDrawer(helper, LodestoreBlocks.STURDY_DRAWER);
		park(helper, drawer, 0, Items.COBBLESTONE, 200);

		destroy(helper, DRAWER);

		helper.runAtTickTime(5, () -> {
			List<ItemStack> drops = drops(helper, DRAWER);

			if (count(drops, Items.COBBLESTONE) != 0) {
				throw helper.assertionException("a sturdy drawer spilled " + count(drops, Items.COBBLESTONE) + " cobblestone");
			}

			ItemStack dropped = drops.stream()
					.filter(stack -> stack.is(LodestoreBlocks.STURDY_DRAWER.asItem()))
					.findFirst()
					.orElseThrow(() -> helper.assertionException("the drawer did not drop at all"));

			ItemContainerContents contents = dropped.get(DataComponents.CONTAINER);
			DrawerOverflow overflow = dropped.get(LodestoreComponents.DRAWER_OVERFLOW);

			if (contents == null || contents.nonEmptyItemCopyStream().mapToLong(ItemStack::getCount).sum() != STACK) {
				throw helper.assertionException("the container component should carry one legal stack, carries "
						+ (contents == null ? "nothing" : contents.nonEmptyItemCopyStream().mapToLong(ItemStack::getCount).sum()));
			}

			if (overflow == null || overflow.total() != 200 - STACK) {
				throw helper.assertionException("the overflow component should carry " + (200 - STACK) + ", carries "
						+ (overflow == null ? "nothing" : overflow.total()));
			}

			// Placed back without its upgrade, which dropped separately: the 200 are all there, spread
			// into legal stacks because the slot they were parked in no longer holds that many.
			BlockPos again = new BlockPos(3, 1, 1);
			DrawerBlockEntity placed = place(helper, again, LodestoreBlocks.STURDY_DRAWER);
			placed.applyComponentsFromItemStack(dropped);
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
				throw helper.assertionException("placing the drop back gave " + back + " cobblestone, not 200");
			}

			helper.succeed();
		});
	}

	// --- capacity changes and transactions ---------------------------------------

	/** An aborted insert leaves the remainder untouched; a committed one changes it. */
	@GameTest
	public void anAbortedInsertLeavesNoTrace(GameTestHelper helper) {
		DrawerBlockEntity drawer = stackedDrawer(helper, LodestoreBlocks.BASIC_DRAWER);
		park(helper, drawer, 0, Items.COBBLESTONE, STACK + 50);

		try (Transaction transaction = Transaction.openOuter()) {
			drawer.storage().insert(ItemVariant.of(Items.COBBLESTONE), 100, transaction);
			transaction.abort();
		}

		if (drawer.total(0) != STACK + 50 || drawer.remainder(0) != 50) {
			throw helper.assertionException("an aborted insert changed the slot: " + drawer.total(0)
					+ " with " + drawer.remainder(0) + " parked");
		}

		if (insert(drawer, Items.COBBLESTONE, 100) != 100 || drawer.total(0) != STACK + 150) {
			throw helper.assertionException("the committed insert did not land: " + drawer.total(0));
		}

		helper.succeed();
	}

	/**
	 * Twenty-seven stacks fill a plain drawer and leave a stacked one a quarter full.
	 *
	 * <p>The whole drawer is filled rather than one slot, because one slot in twenty-seven is below
	 * the comparator's granularity — any non-zero fill reads at least 1, so a single slot at a
	 * quarter and a single slot full would read the same and prove nothing.
	 */
	@GameTest
	public void theComparatorMeasuresEachSlotAgainstItsCapacity(GameTestHelper helper) {
		BlockPos plainPos = new BlockPos(3, 1, 1);
		DrawerBlockEntity stacked = stackedDrawer(helper, DRAWER, LodestoreBlocks.BASIC_DRAWER);
		DrawerBlockEntity plain = place(helper, plainPos, LodestoreBlocks.BASIC_DRAWER);

		// Twenty-seven stacks each: a plain drawer is full; the storage packs a stacked one's into
		// seven slots, and it is a quarter full.
		for (int slot = 0; slot < plain.storageSize(); slot++) {
			plain.contents().setItem(slot, new ItemStack(Items.COBBLESTONE, STACK));
		}

		plain.setChanged();
		long stacks = (long) stacked.storageSize() * STACK;

		if (insert(stacked, Items.COBBLESTONE, stacks) != stacks) {
			throw helper.assertionException("the stacked drawer did not take " + stacks + " cobblestone");
		}

		int stackedSignal = signal(helper, DRAWER);
		int plainSignal = signal(helper, plainPos);

		if (plainSignal != 15) {
			throw helper.assertionException("a plain drawer with a stack in every slot should read 15, reads " + plainSignal);
		}

		if (stackedSignal >= plainSignal || stackedSignal <= 0) {
			throw helper.assertionException("a stacked drawer holding the same reads " + stackedSignal
					+ "; it is a quarter full and should read below 15");
		}

		long rest = (long) stacked.storageSize() * STACK * DEPTH - stacks;

		if (insert(stacked, Items.COBBLESTONE, rest) != rest) {
			throw helper.assertionException("the stacked drawer did not take the " + rest + " that fill it");
		}

		if (signal(helper, DRAWER) != 15) {
			throw helper.assertionException("a stacked drawer with every slot full should read 15, reads "
					+ signal(helper, DRAWER));
		}

		helper.succeed();
	}

	// --- the upgrade's reach and depth ---------------------------------------------

	/**
	 * Every slot, three levels, two classes of item.
	 *
	 * <p>A stack upgrade covers the whole drawer rather than chosen slots. An item that stacks holds
	 * its stack size times the level's multiplier — 256, 512 and 1,024 of a 64-stack, and a 16-stack
	 * scales the same way — and an item that does not stack holds the level's own count: 8, 16 and
	 * 32. The deepest installed level decides; nothing pools.
	 */
	@GameTest
	public void stackLevelsScaleByItemClass(GameTestHelper helper) {
		DrawerBlockEntity drawer = place(helper, DRAWER, LodestoreBlocks.REINFORCED_DRAWER);
		ItemStack cobble = new ItemStack(Items.COBBLESTONE);
		ItemStack pearl = new ItemStack(Items.ENDER_PEARL);
		ItemStack sword = new ItemStack(Items.IRON_SWORD);

		drawer.installUpgrade(new ItemStack(LodestoreItems.STACK_UPGRADE));
		expectCapacity(helper, drawer, cobble, 256);
		expectCapacity(helper, drawer, pearl, 64);
		expectCapacity(helper, drawer, sword, 8);

		if (drawer.stackedSlots().size() != drawer.storageSize() || !drawer.isStackedSlot(drawer.storageSize() - 1)) {
			throw helper.assertionException("a stack upgrade should cover every slot, covers " + drawer.stackedSlots().size());
		}

		if (drawer.toggleTarget(UpgradeBehaviour.STACK, 0)) {
			throw helper.assertionException("stacking accepted a slot choice; it covers the whole drawer");
		}

		drawer.installUpgrade(new ItemStack(LodestoreItems.STACK_UPGRADE_3));
		expectCapacity(helper, drawer, cobble, 1024);
		expectCapacity(helper, drawer, pearl, 256);
		expectCapacity(helper, drawer, sword, 32);

		// A shallower level alongside changes nothing: the deepest decides, and nothing adds up.
		drawer.installUpgrade(new ItemStack(LodestoreItems.STACK_UPGRADE_2));
		expectCapacity(helper, drawer, cobble, 1024);
		expectCapacity(helper, drawer, sword, 32);

		helper.succeed();
	}

	private static void expectCapacity(GameTestHelper helper, DrawerBlockEntity drawer, ItemStack stack, int expected) {
		for (int slot : new int[] {0, drawer.storageSize() - 1}) {
			if (drawer.slotCapacity(slot, stack) != expected) {
				throw helper.assertionException(describe(stack) + " should have a capacity of " + expected
						+ " in slot " + slot + ", has " + drawer.slotCapacity(slot, stack));
			}
		}
	}

	/** The client gets the stack upgrade's two numbers and the parked counts. */
	@GameTest
	public void theSyncTagCarriesStackingForTheClient(GameTestHelper helper) {
		DrawerBlockEntity drawer = stackedDrawer(helper, LodestoreBlocks.BASIC_DRAWER);
		park(helper, drawer, 0, Items.COBBLESTONE, 200);

		CompoundTag sync = drawer.getUpdateTag(helper.getLevel().registryAccess());

		if (sync.getIntOr("stack_multiplier", 0) != DEPTH || sync.getIntOr("stack_unstackable", 0) != 8
				|| !sync.contains("synced_overflow")) {
			throw helper.assertionException("the sync tag is missing stacking state: " + sync);
		}

		if (sync.contains("Items")) {
			throw helper.assertionException("the sync tag carries the inventory, which it must never do");
		}

		// Loaded as a client would load it: no stacks arrive, and the counts have to stand alone.
		DrawerBlockEntity client = new DrawerBlockEntity(helper.absolutePos(DRAWER), LodestoreBlocks.BASIC_DRAWER.defaultBlockState());
		client.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), sync));

		if (client.remainder(0) != 200 - STACK) {
			throw helper.assertionException("the client's copy did not pick up the parked count: " + client.remainder(0));
		}

		helper.succeed();
	}

	// --- migration -----------------------------------------------------------------

	/**
	 * A drawer saved before the storage layer existed loads with nothing lost.
	 *
	 * <p>The old block entity wrote {@code Items} through {@code ContainerHelper}, its upgrades,
	 * its targeting and claims, and the name and lock it inherited. This writes that shape by hand,
	 * rather than through the current code, and loads it into a fresh drawer. The pair is the same
	 * tag with a remainder table added: a loader that ignored the table would pass the first and
	 * fail the second.
	 */
	@GameTest
	public void anOldWorldsDrawerLoadsUnchanged(GameTestHelper helper) {
		TagValueOutput out = oldShapeTag(helper);
		DrawerBlockEntity reloaded = reload(helper, out.buildResult());

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

		if (!reloaded.lockedSlots().contains(5) || !reloaded.slotFilter(0).is(Items.COBBLESTONE)) {
			throw helper.assertionException("targeting or the claim did not survive: covers " + reloaded.lockedSlots()
					+ ", claim " + describe(reloaded.slotFilter(0)));
		}

		if (reloaded.getCustomName() == null || !"Spares".equals(reloaded.getCustomName().getString())) {
			throw helper.assertionException("the custom name did not survive: " + reloaded.getCustomName());
		}

		helper.succeed();
	}

	/** The pair: the same old-shaped tag with a remainder table restores the parked count. */
	@GameTest
	public void aSavedRemainderIsRestored(GameTestHelper helper) {
		TagValueOutput out = oldShapeTag(helper);
		out.store("overflow", DrawerOverflow.CODEC, new DrawerOverflow(List.of(new DrawerOverflow.Entry(0, 100))));
		DrawerBlockEntity reloaded = reload(helper, out.buildResult());

		if (reloaded.total(0) != 112 || reloaded.contents().visible(0).getCount() != STACK || reloaded.remainder(0) != 112 - STACK) {
			throw helper.assertionException("expected 112 cobblestone as a full stack with " + (112 - STACK)
					+ " parked, got " + reloaded.total(0) + " shown as " + reloaded.contents().visible(0).getCount()
					+ " with " + reloaded.remainder(0));
		}

		// A count with no stack to attach to is dropped, not invented.
		TagValueOutput orphan = oldShapeTag(helper);
		orphan.store("overflow", DrawerOverflow.CODEC, new DrawerOverflow(List.of(new DrawerOverflow.Entry(10, 100))));

		if (reload(helper, orphan.buildResult()).total(10) != 0) {
			throw helper.assertionException("a parked count with no stack came back as items");
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

	private static DrawerBlockEntity reload(GameTestHelper helper, CompoundTag tag) {
		DrawerBlockEntity reloaded = new DrawerBlockEntity(
				helper.absolutePos(DRAWER), LodestoreBlocks.BASIC_DRAWER.defaultBlockState());
		// Without a level the upgrade registry is unreachable and the lock reads as inert.
		reloaded.setLevel(helper.getLevel());
		reloaded.loadWithComponents(TagValueInput.create(
				ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), tag));
		return reloaded;
	}

	// --- helpers -------------------------------------------------------------------

	private static DrawerBlockEntity stackedDrawer(GameTestHelper helper, Block block) {
		return stackedDrawer(helper, DRAWER, block);
	}

	/** A drawer with the shipped stack upgrade, which covers its first row at depth four. */
	private static DrawerBlockEntity stackedDrawer(GameTestHelper helper, BlockPos pos, Block block) {
		DrawerBlockEntity drawer = place(helper, pos, block);

		if (!drawer.installUpgrade(new ItemStack(LodestoreItems.STACK_UPGRADE))) {
			throw helper.assertionException("the stack upgrade would not install");
		}

		if (!drawer.isStackedSlot(0)) {
			throw helper.assertionException("the stack upgrade does not cover slot 0; the test is set up wrong");
		}

		return drawer;
	}

	/** Adds {@code amount} of an item to one slot through its storage view, remainder included. */
	private static void park(GameTestHelper helper, DrawerBlockEntity drawer, int slot,
			net.minecraft.world.item.Item item, long amount) {
		long before = drawer.total(slot);
		long accepted;

		try (Transaction transaction = Transaction.openOuter()) {
			accepted = drawer.storage().getSlot(slot).insert(ItemVariant.of(item), amount, transaction);
			transaction.commit();
		}

		if (accepted != amount || drawer.total(slot) != before + amount) {
			throw helper.assertionException("could not park " + amount + " in slot " + slot + ": accepted " + accepted
					+ ", slot went from " + before + " to " + drawer.total(slot));
		}
	}

	private static long insert(DrawerBlockEntity drawer, net.minecraft.world.item.Item item, long amount) {
		try (Transaction transaction = Transaction.openOuter()) {
			long accepted = drawer.storage().insert(ItemVariant.of(item), amount, transaction);
			transaction.commit();
			return accepted;
		}
	}

	private static AbstractContainerMenu open(GameTestHelper helper, DrawerBlockEntity drawer, Player player) {
		AbstractContainerMenu menu = drawer.createMenu(1, player.getInventory(), player);

		if (menu == null) {
			throw helper.assertionException("the drawer refused to open");
		}

		player.containerMenu = menu;
		return menu;
	}

	/** Shift-clicks {@code stack} from the player's inventory into the drawer; returns how many moved. */
	private static long shiftClickIn(GameTestHelper helper, DrawerBlockEntity drawer, ItemStack stack) {
		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		AbstractContainerMenu menu = open(helper, drawer, player);
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
		helper.setBlock(DRAWER.above(), Blocks.HOPPER.defaultBlockState());
		HopperBlockEntity hopper = helper.getBlockEntity(DRAWER.above(), HopperBlockEntity.class);
		hopper.setItem(0, load);
		return hopper;
	}

	/** A dropper south of the drawer, facing north into it. */
	private static DispenserBlockEntity dropperFacing(GameTestHelper helper, ItemStack load) {
		BlockPos pos = DRAWER.south();
		helper.setBlock(pos, Blocks.DROPPER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.NORTH));
		DispenserBlockEntity dropper = helper.getBlockEntity(pos, DispenserBlockEntity.class);
		dropper.setItem(0, load);
		return dropper;
	}

	private static String describe(ItemStack stack) {
		return stack.isEmpty() ? "nothing" : stack.getCount() + "x " + stack.getHoverName().getString();
	}

	private static DrawerBlockEntity place(GameTestHelper helper, BlockPos pos, Block block) {
		helper.setBlock(pos, block.defaultBlockState());
		return helper.getBlockEntity(pos, DrawerBlockEntity.class);
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
