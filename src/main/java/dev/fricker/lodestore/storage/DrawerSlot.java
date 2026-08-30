package dev.fricker.lodestore.storage;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.item.base.SingleItemStorage;

import dev.fricker.lodestore.block.DrawerBlockEntity;

/**
 * One item-type slot of a drawer.
 *
 * <p>Extends {@link SingleItemStorage}, so the stored quantity is a {@code long} and the slot
 * participates in Fabric transactions (snapshot/rollback) for free. There is deliberately no
 * {@code ItemStack} anywhere in this class — the contents are {@code ItemVariant + long}.
 */
public class DrawerSlot extends SingleItemStorage {
	private final DrawerBlockEntity owner;

	public DrawerSlot(DrawerBlockEntity owner) {
		this.owner = owner;
	}

	@Override
	protected long getCapacity(ItemVariant variant) {
		return owner.capacityFor(variant);
	}

	@Override
	protected void onFinalCommit() {
		// Runs only when the outermost transaction commits, never on a rolled-back attempt.
		owner.onContentsChanged();
	}
}
