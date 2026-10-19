package dev.fricker.lodestore.menu;

import java.util.function.BooleanSupplier;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import dev.fricker.lodestore.item.DrawerUpgradeItem;

/**
 * A slot that takes drawer upgrades.
 *
 * <p>Duplicates are allowed: the lock's capacity pools, so two level-one locks reach as far as one
 * level-two, and refusing a second of the same upgrade would make that impossible to express.
 *
 * <p>An upgrade the drawer's contents still need cannot be taken out. The drawer decides that and
 * the menu syncs the answer, so the slot refuses the pickup on both sides and the screen can say
 * why; the alternative was contents stranded over capacity when a stack upgrade left.
 */
public class UpgradeSlot extends Slot {
	private final BooleanSupplier removable;

	public UpgradeSlot(Container container, int slot, int x, int y, BooleanSupplier removable) {
		super(container, slot, x, y);
		this.removable = removable;
	}

	@Override
	public boolean mayPlace(ItemStack stack) {
		return stack.getItem() instanceof DrawerUpgradeItem;
	}

	@Override
	public boolean mayPickup(Player player) {
		return removable.getAsBoolean();
	}

	/** An upgrade is installed or it is not; a stack of them in one slot would mean nothing. */
	@Override
	public int getMaxStackSize() {
		return 1;
	}
}
