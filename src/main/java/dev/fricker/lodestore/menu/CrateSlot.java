package dev.fricker.lodestore.menu;

import org.jspecify.annotations.Nullable;

import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import dev.fricker.lodestore.block.CrateBlockEntity;

/**
 * A storage slot in the crate screen.
 *
 * <p>{@code Slot#getMaxStackSize(ItemStack)} is the one capacity hook in vanilla that knows which
 * slot it is for, and it governs both a click and a shift-click. It reports the room left for the
 * slot's <em>total</em> — the capacity less what is already parked — so a player can fill a
 * stack-upgraded slot by hand, and the contents park whatever a legal stack cannot show.
 *
 * <p>{@code mayPlace} refuses the one menu gesture that would orphan a parked remainder: swapping a
 * different item onto a slot that has one. Vanilla gates the swap on exactly this method.
 *
 * <p>The crate is nullable because the client builds its menu before it necessarily has the block
 * entity; without one, the slot behaves as a plain chest slot and the server corrects anything the
 * client guessed wrong.
 */
public class CrateSlot extends Slot {
	private final @Nullable CrateBlockEntity crate;
	private final int storageSlot;

	public CrateSlot(Container container, int slot, int x, int y, @Nullable CrateBlockEntity crate) {
		super(container, slot, x, y);
		this.crate = crate;
		this.storageSlot = slot;
	}

	@Override
	public int getMaxStackSize() {
		ItemStack held = getItem();
		return held.isEmpty() ? super.getMaxStackSize() : getMaxStackSize(held);
	}

	@Override
	public int getMaxStackSize(ItemStack stack) {
		if (crate == null) {
			return super.getMaxStackSize(stack);
		}

		long room = (long) crate.slotCapacity(storageSlot, stack) - crate.remainder(storageSlot);
		return (int) Mth.clamp(room, 0, Integer.MAX_VALUE);
	}

	/**
	 * A stacked crate's grid is marked fake, which keeps Inventory Profiles Next out of it.
	 *
	 * <p>A sorter that simulates clicks on what the slots show cannot sort these slots: one refills
	 * after it is picked up and takes more than a stack when filled, so the sorter's plan diverges
	 * from what the server does and its leftovers land on the cursor. The server stays right —
	 * nothing is lost or doubled — but the result is chaos, so the sorter is not offered the grid.
	 * In vanilla the flag only changes the owner the screen hands the item model. A plain crate
	 * is a chest and sorts as one; a stacked crate sorts by a right-click on its stack upgrade.
	 */
	@Override
	public boolean isFake() {
		return crate != null && crate.stacks();
	}

	@Override
	public boolean mayPlace(ItemStack stack) {
		if (crate == null) {
			return container.canPlaceItem(storageSlot, stack);
		}

		if (crate.remainder(storageSlot) > 0 && !ItemStack.isSameItemSameComponents(getItem(), stack)) {
			return false;
		}

		return crate.canStore(storageSlot, stack);
	}
}
