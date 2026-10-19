package dev.fricker.lodestore.menu;

import org.jspecify.annotations.Nullable;

import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import dev.fricker.lodestore.block.DrawerBlockEntity;

/**
 * A storage slot in the drawer screen.
 *
 * <p>{@code Slot#getMaxStackSize(ItemStack)} is the one capacity hook in vanilla that knows which
 * slot it is for, and it governs both a click and a shift-click. It reports the room left for the
 * slot's <em>total</em> — the capacity less what is already parked — so a player can fill a
 * stack-upgraded slot by hand, and the contents park whatever a legal stack cannot show.
 *
 * <p>{@code mayPlace} refuses the one menu gesture that would orphan a parked remainder: swapping a
 * different item onto a slot that has one. Vanilla gates the swap on exactly this method.
 *
 * <p>The drawer is nullable because the client builds its menu before it necessarily has the block
 * entity; without one, the slot behaves as a plain chest slot and the server corrects anything the
 * client guessed wrong.
 */
public class DrawerSlot extends Slot {
	private final @Nullable DrawerBlockEntity drawer;
	private final int storageSlot;

	public DrawerSlot(Container container, int slot, int x, int y, @Nullable DrawerBlockEntity drawer) {
		super(container, slot, x, y);
		this.drawer = drawer;
		this.storageSlot = slot;
	}

	@Override
	public int getMaxStackSize() {
		ItemStack held = getItem();
		return held.isEmpty() ? super.getMaxStackSize() : getMaxStackSize(held);
	}

	@Override
	public int getMaxStackSize(ItemStack stack) {
		if (drawer == null) {
			return super.getMaxStackSize(stack);
		}

		long room = (long) drawer.slotCapacity(storageSlot, stack) - drawer.remainder(storageSlot);
		return (int) Mth.clamp(room, 0, Integer.MAX_VALUE);
	}

	@Override
	public boolean mayPlace(ItemStack stack) {
		if (drawer == null) {
			return container.canPlaceItem(storageSlot, stack);
		}

		if (drawer.remainder(storageSlot) > 0 && !ItemStack.isSameItemSameComponents(getItem(), stack)) {
			return false;
		}

		return drawer.canStore(storageSlot, stack);
	}
}
