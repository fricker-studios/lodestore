package dev.fricker.lodestore.menu;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import dev.fricker.lodestore.api.drawer.DrawerTier;
import dev.fricker.lodestore.block.DrawerBlockEntity;
import dev.fricker.lodestore.item.DrawerUpgradeItem;

/**
 * The drawer screen's menu: the storage grid, plus a column of upgrade slots beside it.
 *
 * <p>Replaces the vanilla {@code ChestMenu} the drawers used until upgrades needed managing after
 * they were installed. The cost is real — a custom menu is a thing to maintain, and the vanilla one
 * was free — but there is no way to show upgrade slots without one.
 *
 * <p>Only {@code rows * 9} storage slots are added, deliberately. A voiding drawer's container is
 * one larger than its storage so that hoppers have a slot to insert into, and that overflow slot
 * must never appear on screen: it always reads empty and discards what is put in it.
 */
public class DrawerMenu extends AbstractContainerMenu {
	/** Left edge of the upgrade slots, past the right edge of the vanilla chest background. */
	public static final int PANEL_SLOT_X = 184;
	public static final int FIRST_SLOT_Y = 18;
	public static final int SLOT_PITCH = 18;
	/** Upgrade slots wrap at three, so the panel is never wider than it needs to be. */
	public static final int UPGRADES_PER_ROW = 3;

	/** The one value the client needs that slots cannot carry: whether a lock is installed. */
	public static final int DATA_LOCKED = 0;
	public static final int DATA_COUNT = 1;

	/** Button ids are a behaviour and a storage slot packed together; storage never exceeds 54. */
	private static final int BUTTON_BEHAVIOUR_STRIDE = 256;

	private final Container storage;
	private final ContainerData menuData;
	private final int rows;
	private final int upgradeSlots;
	private final BlockPos pos;

	/**
	 * Client side. The containers are stand-ins: the server owns the real contents and streams
	 * them into these slots, so they only have to be the right shape.
	 */
	public DrawerMenu(int containerId, Inventory playerInventory, DrawerMenuData data) {
		this(containerId, playerInventory,
				new SimpleContainer(data.rows() * 9),
				new SimpleContainer(DrawerTier.MAX_UPGRADE_SLOTS),
				new SimpleContainerData(DATA_COUNT),
				data.rows(), data.upgradeSlots(), data.pos());
	}

	public DrawerMenu(int containerId, Inventory playerInventory, Container storage, Container upgrades,
			ContainerData menuData, int rows, int upgradeSlots, BlockPos pos) {
		super(LodestoreMenus.DRAWER, containerId);

		checkContainerSize(storage, rows * 9);
		checkContainerDataCount(menuData, DATA_COUNT);

		this.storage = storage;
		this.menuData = menuData;
		this.rows = rows;
		this.upgradeSlots = Math.min(upgradeSlots, upgrades.getContainerSize());
		this.pos = pos;

		for (int row = 0; row < rows; row++) {
			for (int column = 0; column < 9; column++) {
				addSlot(new Slot(storage, column + row * 9,
						8 + column * SLOT_PITCH, FIRST_SLOT_Y + row * SLOT_PITCH));
			}
		}

		for (int slot = 0; slot < this.upgradeSlots; slot++) {
			addSlot(new UpgradeSlot(upgrades, slot,
					PANEL_SLOT_X + (slot % UPGRADES_PER_ROW) * SLOT_PITCH,
					FIRST_SLOT_Y + (slot / UPGRADES_PER_ROW) * SLOT_PITCH));
		}

		// Same arithmetic as the vanilla chest, so the player's own inventory sits where muscle
		// memory expects it however many rows the drawer has.
		addStandardInventorySlots(playerInventory, 8, 103 + (rows - 4) * SLOT_PITCH);
		addDataSlots(menuData);
	}

	public int rows() {
		return rows;
	}

	public int upgradeSlots() {
		return upgradeSlots;
	}

	public BlockPos pos() {
		return pos;
	}

	/** How many rows the upgrade slots wrap onto. */
	public static int upgradeRows(int upgradeSlots) {
		return Math.max(1, (upgradeSlots + UPGRADES_PER_ROW - 1) / UPGRADES_PER_ROW);
	}

	/** Whether a lock is installed, from the synced menu data. */
	public boolean locked() {
		return menuData.get(DATA_LOCKED) != 0;
	}

	public int storageEnd() {
		return rows * 9;
	}

	public int upgradeEnd() {
		return storageEnd() + upgradeSlots;
	}

	/** Packs a targeting click into the one int vanilla's button path carries. */
	public static int targetButton(boolean lock, int storageSlot) {
		return (lock ? 0 : 1) * BUTTON_BEHAVIOUR_STRIDE + storageSlot;
	}

	/**
	 * Toggles a storage slot in an upgrade's selection.
	 *
	 * <p>Rides vanilla's container-button packet rather than a payload of our own: that path is
	 * already validated against the container the player actually has open, which is exactly the
	 * check a bespoke packet would have to reimplement.
	 */
	@Override
	public boolean clickMenuButton(Player player, int id) {
		if (id < 0 || !(storage instanceof DrawerBlockEntity drawer)) {
			return false;
		}

		boolean lock = id / BUTTON_BEHAVIOUR_STRIDE == 0;
		int storageSlot = id % BUTTON_BEHAVIOUR_STRIDE;

		if (storageSlot >= storageEnd()) {
			return false;
		}

		return drawer.toggleTarget(lock, storageSlot);
	}

	@Override
	public boolean stillValid(Player player) {
		return storage.stillValid(player);
	}

	/**
	 * Shift-clicking. An upgrade moved in from the player's inventory goes to the upgrade column
	 * rather than into storage, which is the only sensible reading of the gesture — a drawer full
	 * of loose upgrade items is never what was meant.
	 */
	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		Slot slot = slots.get(index);

		if (!slot.hasItem()) {
			return ItemStack.EMPTY;
		}

		ItemStack stack = slot.getItem();
		ItemStack original = stack.copy();
		int playerEnd = upgradeEnd() + 36;

		if (index < upgradeEnd()) {
			if (!moveItemStackTo(stack, upgradeEnd(), playerEnd, true)) {
				return ItemStack.EMPTY;
			}
		} else if (stack.getItem() instanceof DrawerUpgradeItem) {
			if (!moveItemStackTo(stack, storageEnd(), upgradeEnd(), false)) {
				return ItemStack.EMPTY;
			}
		} else if (!moveItemStackTo(stack, 0, storageEnd(), false)) {
			return ItemStack.EMPTY;
		}

		if (stack.isEmpty()) {
			slot.set(ItemStack.EMPTY);
		} else {
			slot.setChanged();
		}

		return original;
	}
}
