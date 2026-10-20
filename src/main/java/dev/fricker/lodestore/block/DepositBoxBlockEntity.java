package dev.fricker.lodestore.block;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;

import dev.fricker.lodestore.network.NetworkCrateCache;
import dev.fricker.lodestore.network.StorageNetwork;
import dev.fricker.lodestore.registry.LodestoreBlockEntities;

/**
 * A deposit box: the network's inbox.
 *
 * <p>One row of ordinary slots, which is deliberately a vanilla {@code Container} where a crate is
 * not. A crate refuses to be one so that its slots can be deeper than vanilla's arithmetic allows;
 * a deposit box's slots are plain, and being a container is what lets a hopper, a dropper or a
 * hand fill it with nothing further said. The one thing a hopper may not do is take from it: the
 * network takes, and a hopper under the box pulling things out before the network had sorted them
 * would make the box an ordinary chest with a delay.
 *
 * <p>Every tick, one item leaves for the network: a crate that is its home — one already holding
 * that item, or locked to it — nearest first, or else an unlocked crate, which is the network's
 * overflow. One a tick is the rate a hopper minecart pulls at, which is eight times a hopper
 * block. An item with nowhere to go stays, and the box says so by not emptying; the player decides
 * what it is for. The order, and when a void upgrade destroys instead, is
 * {@link StorageNetwork#insertOne}'s.
 */
public class DepositBoxBlockEntity extends BaseContainerBlockEntity implements WorldlyContainer {
	public static final int SIZE = 9;

	private NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
	private final int[] allSlots = new int[SIZE];
	private final NetworkCrateCache crates = new NetworkCrateCache();

	public DepositBoxBlockEntity(BlockPos pos, BlockState state) {
		super(LodestoreBlockEntities.DEPOSIT_BOX, pos, state);

		for (int slot = 0; slot < SIZE; slot++) {
			allSlots[slot] = slot;
		}
	}

	/** Moves one item into the network, if any item here has a home on it. */
	public static void serverTick(Level level, BlockPos pos, BlockState state, DepositBoxBlockEntity box) {
		if (box.isEmpty()) {
			return;
		}

		List<BlockPos> crates = box.crates.get(level, pos);

		for (int slot = 0; slot < SIZE; slot++) {
			ItemStack stack = box.items.get(slot);

			if (stack.isEmpty()) {
				continue;
			}

			try (Transaction transaction = Transaction.openOuter()) {
				if (StorageNetwork.insertOne(level, crates, ItemVariant.of(stack), transaction)) {
					transaction.commit();
					box.removeItem(slot, 1);
					return;
				}
			}
		}
	}

	// --- container ---------------------------------------------------------------

	@Override
	public int getContainerSize() {
		return SIZE;
	}

	@Override
	protected NonNullList<ItemStack> getItems() {
		return items;
	}

	@Override
	protected void setItems(NonNullList<ItemStack> items) {
		this.items = items;
	}

	@Override
	protected Component getDefaultName() {
		return getBlockState().getBlock().getName();
	}

	@Override
	protected AbstractContainerMenu createMenu(int containerId, Inventory playerInventory) {
		return new ChestMenu(MenuType.GENERIC_9x1, containerId, playerInventory, this, 1);
	}

	/** Anything goes in by any face. */
	@Override
	public int[] getSlotsForFace(Direction side) {
		return allSlots;
	}

	@Override
	public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction side) {
		return canPlaceItem(slot, stack);
	}

	/** Nothing comes out by any face: the network is the only way out, short of a player's hand. */
	@Override
	public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
		return false;
	}

	// --- persistence -------------------------------------------------------------

	/**
	 * The base class writes the name and lock and nothing else; every vanilla container writes its
	 * own items, and so does this one. {@code PersistenceSelfCheck} is what notices if it stops.
	 */
	@Override
	protected void saveAdditional(ValueOutput data) {
		super.saveAdditional(data);
		ContainerHelper.saveAllItems(data, items);
	}

	@Override
	protected void loadAdditional(ValueInput data) {
		super.loadAdditional(data);
		items.clear();
		ContainerHelper.loadAllItems(data, items);
	}
}
