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

	/**
	 * Keeps inventory sorters out of the upgrade column.
	 *
	 * <p>In vanilla this flag changes one thing: the screen draws a fake slot's item without the
	 * player as its model's owner, as it does the crafting result slot — nothing about clicks. It
	 * is also the first thing Inventory Profiles Next checks when it decides which slots belong to
	 * a container's sortable area, read from its decompiled {@code AreaTypes}: a fake slot is left
	 * out of every area, so a sort, "move all" and the rest never pick an upgrade up. Without it,
	 * a sort treated the column as more slots of the same chest, lifted the upgrade out, found
	 * nowhere in the grid that would take it, and left it on the cursor.
	 */
	@Override
	public boolean isFake() {
		return true;
	}
}
