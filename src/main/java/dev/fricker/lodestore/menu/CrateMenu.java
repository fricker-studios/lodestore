package dev.fricker.lodestore.menu;

import org.jspecify.annotations.Nullable;

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
import net.minecraft.world.level.Level;

import dev.fricker.lodestore.api.crate.CrateTier;
import dev.fricker.lodestore.block.CrateBlockEntity;
import dev.fricker.lodestore.item.CrateUpgradeItem;

/**
 * The crate screen's menu: the storage grid, plus a column of upgrade slots beside it.
 *
 * <p>Replaces the vanilla {@code ChestMenu} the crates used until upgrades needed managing after
 * they were installed. The cost is real — a custom menu is a thing to maintain, and the vanilla one
 * was free — but there is no way to show upgrade slots without one.
 *
 * <p>The storage slots hang off the crate's {@code CrateContents}, which is a {@code Container}
 * the block entity owns rather than the block entity itself — a hopper looks for a container at the
 * block entity and must not find one. Each slot only ever shows a legal stack; the crate's own
 * {@link CrateSlot} is what lets a click reach the room a stack upgrade adds behind it.
 */
public class CrateMenu extends AbstractContainerMenu {
	/** Left edge of the upgrade slots, past the right edge of the vanilla chest background. */
	public static final int PANEL_SLOT_X = 184;
	public static final int FIRST_SLOT_Y = 18;
	public static final int SLOT_PITCH = 18;
	/** Upgrade slots wrap at three, so the panel is never wider than it needs to be. */
	public static final int UPGRADES_PER_ROW = 3;

	/** What the client needs that slots cannot carry: whether a lock is installed, and which upgrades may be taken out. */
	public static final int DATA_LOCKED = 0;
	public static final int DATA_REMOVABLE = 1;
	public static final int DATA_COUNT = 2;

	/** Button ids below this name a storage slot for the face to show; storage never exceeds 54. */
	private static final int DISPLAY_BUTTONS = 256;
	/** One id past the display range: sort the crate, as a right-click on the stack upgrade asks. */
	public static final int SORT_BUTTON = DISPLAY_BUTTONS;

	private final Container storage;
	private final @Nullable CrateBlockEntity crate;
	private final ContainerData menuData;
	private final int rows;
	private final int upgradeSlots;
	private final BlockPos pos;

	/**
	 * Client side. The containers are stand-ins: the server owns the real contents and streams
	 * them into these slots, so they only have to be the right shape. The crate is the client's
	 * copy of the block entity, which carries the synced claim, depth and remainders the slots
	 * need to predict what the server will allow.
	 */
	public CrateMenu(int containerId, Inventory playerInventory, CrateMenuData data) {
		this(containerId, playerInventory,
				new SimpleContainer(data.rows() * 9),
				crateAt(playerInventory.player.level(), data.pos()),
				new SimpleContainer(CrateTier.MAX_UPGRADE_SLOTS),
				new SimpleContainerData(DATA_COUNT),
				data.rows(), data.upgradeSlots(), data.pos());
	}

	public CrateMenu(int containerId, Inventory playerInventory, Container storage, @Nullable CrateBlockEntity crate,
			Container upgrades, ContainerData menuData, int rows, int upgradeSlots, BlockPos pos) {
		super(LodestoreMenus.CRATE, containerId);

		checkContainerSize(storage, rows * 9);
		checkContainerDataCount(menuData, DATA_COUNT);

		this.storage = storage;
		this.crate = crate;
		this.menuData = menuData;
		this.rows = rows;
		this.upgradeSlots = Math.min(upgradeSlots, upgrades.getContainerSize());
		this.pos = pos;

		for (int row = 0; row < rows; row++) {
			for (int column = 0; column < 9; column++) {
				addSlot(new CrateSlot(storage, column + row * 9,
						8 + column * SLOT_PITCH, FIRST_SLOT_Y + row * SLOT_PITCH, crate));
			}
		}

		for (int slot = 0; slot < this.upgradeSlots; slot++) {
			int bit = 1 << slot;
			addSlot(new UpgradeSlot(upgrades, slot,
					PANEL_SLOT_X + (slot % UPGRADES_PER_ROW) * SLOT_PITCH,
					FIRST_SLOT_Y + (slot / UPGRADES_PER_ROW) * SLOT_PITCH,
					() -> (menuData.get(DATA_REMOVABLE) & bit) != 0));
		}

		// Same arithmetic as the vanilla chest, so the player's own inventory sits where muscle
		// memory expects it however many rows the crate has.
		addStandardInventorySlots(playerInventory, 8, 103 + (rows - 4) * SLOT_PITCH);
		addDataSlots(menuData);
	}

	private static @Nullable CrateBlockEntity crateAt(Level level, BlockPos pos) {
		return level.getBlockEntity(pos) instanceof CrateBlockEntity crate ? crate : null;
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

	/** Whether the upgrade in this column slot may be taken out, from the synced menu data. */
	public boolean removable(int upgradeSlot) {
		return (menuData.get(DATA_REMOVABLE) & (1 << upgradeSlot)) != 0;
	}

	public int storageEnd() {
		return rows * 9;
	}

	public int upgradeEnd() {
		return storageEnd() + upgradeSlots;
	}

	/** The button a click on a storage slot sends while the lock is choosing what the face shows. */
	public static int displayButton(int storageSlot) {
		return storageSlot;
	}

	/**
	 * Sorts the crate, as a right-click on the stack upgrade asks, or chooses the storage slot
	 * whose item the front face shows, as a click after right-clicking the lock asks.
	 *
	 * <p>Rides vanilla's container-button packet rather than a payload of our own: that path is
	 * already validated against the container the player actually has open, which is exactly the
	 * check a bespoke packet would have to reimplement.
	 */
	@Override
	public boolean clickMenuButton(Player player, int id) {
		if (id < 0 || crate == null) {
			return false;
		}

		if (id == SORT_BUTTON) {
			return crate.sortContents();
		}

		if (id >= storageEnd()) {
			return false;
		}

		return crate.chooseDisplaySlot(id);
	}

	@Override
	public boolean stillValid(Player player) {
		return storage.stillValid(player);
	}

	/**
	 * Shift-clicking. An upgrade moved in from the player's inventory goes to the upgrade column
	 * rather than into storage, which is the only sensible reading of the gesture — a crate full
	 * of loose upgrade items is never what was meant.
	 *
	 * <p>Out of a storage slot, this moves one legal stack; vanilla then calls it again for as long
	 * as the slot still holds the same item, so a slot with stacks parked behind the visible one
	 * empties as far as the player's inventory can take.
	 */
	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		Slot slot = slots.get(index);

		if (!slot.hasItem() || !slot.mayPickup(player)) {
			return ItemStack.EMPTY;
		}

		ItemStack stack = slot.getItem();
		ItemStack original = stack.copy();
		int playerEnd = upgradeEnd() + 36;

		if (index < upgradeEnd()) {
			if (!moveItemStackTo(stack, upgradeEnd(), playerEnd, true)) {
				return ItemStack.EMPTY;
			}
		} else if (stack.getItem() instanceof CrateUpgradeItem) {
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
