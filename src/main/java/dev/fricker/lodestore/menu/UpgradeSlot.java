package dev.fricker.lodestore.menu;

import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import dev.fricker.lodestore.item.DrawerUpgradeItem;

/**
 * A slot that takes drawer upgrades, one of each kind.
 *
 * <p>The duplicate rule is enforced here as well as in
 * {@code DrawerBlockEntity#installUpgrade} because the two are different doors into the same
 * state: installing by right-click goes through the block entity, dragging one into the screen
 * goes through this slot, and a rule applied to only one of them is not a rule.
 */
public class UpgradeSlot extends Slot {
	public UpgradeSlot(Container container, int slot, int x, int y) {
		super(container, slot, x, y);
	}

	/**
	 * Duplicates are allowed: capacity pools, so two level-one locks reach as far as one
	 * level-two. Refusing a second of the same upgrade would make that impossible to express.
	 */
	@Override
	public boolean mayPlace(ItemStack stack) {
		return stack.getItem() instanceof DrawerUpgradeItem;
	}

	/** An upgrade is installed or it is not; a stack of them in one slot would mean nothing. */
	@Override
	public int getMaxStackSize() {
		return 1;
	}
}
