package dev.fricker.lodestore.storage;

import net.minecraft.world.item.ItemStack;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.StoragePreconditions;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;

/**
 * One crate slot as the transfer API sees it.
 *
 * <p>Amount is the slot's total, visible stack and remainder together, and capacity is whatever the
 * crate's rules say for the slot — which is the per-slot answer vanilla's {@code Container} has no
 * way to give, and the reason this layer exists. Insertion and extraction change the total and let
 * {@link CrateContents} settle how much of it is on show.
 *
 * <p>Transactions snapshot the pair rather than the stack alone, so an aborted hopper move puts the
 * remainder back too.
 */
final class CrateSlotStorage extends SnapshotParticipant<CrateSlotStorage.Snapshot>
		implements SingleSlotStorage<ItemVariant> {
	record Snapshot(ItemStack visible, long remainder) {
	}

	private final CrateContents contents;
	private final CrateStorage owner;
	private final int slot;

	CrateSlotStorage(CrateContents contents, CrateStorage owner, int slot) {
		this.contents = contents;
		this.owner = owner;
		this.slot = slot;
	}

	@Override
	public long insert(ItemVariant variant, long maxAmount, TransactionContext transaction) {
		StoragePreconditions.notBlankNotNegative(variant, maxAmount);
		ItemStack probe = variant.toStack();
		long accepted = Math.min(maxAmount, contents.room(slot, probe));

		if (accepted <= 0) {
			return 0;
		}

		updateSnapshots(transaction);
		owner.updateSnapshots(transaction);
		return contents.add(slot, probe, accepted);
	}

	@Override
	public long extract(ItemVariant variant, long maxAmount, TransactionContext transaction) {
		StoragePreconditions.notBlankNotNegative(variant, maxAmount);
		ItemStack held = contents.visible(slot);

		if (held.isEmpty() || !variant.matches(held)) {
			return 0;
		}

		long taken = Math.min(maxAmount, contents.total(slot));

		if (taken <= 0) {
			return 0;
		}

		updateSnapshots(transaction);
		owner.updateSnapshots(transaction);
		return contents.take(slot, taken);
	}

	@Override
	public boolean isResourceBlank() {
		return contents.visible(slot).isEmpty();
	}

	@Override
	public ItemVariant getResource() {
		ItemStack held = contents.visible(slot);
		return held.isEmpty() ? ItemVariant.blank() : ItemVariant.of(held);
	}

	@Override
	public long getAmount() {
		return contents.total(slot);
	}

	@Override
	public long getCapacity() {
		return contents.capacity(slot);
	}

	@Override
	protected Snapshot createSnapshot() {
		return new Snapshot(contents.visible(slot).copy(), contents.remainder(slot));
	}

	@Override
	protected void readSnapshot(Snapshot snapshot) {
		contents.restore(slot, snapshot.visible(), snapshot.remainder());
	}

	@Override
	public String toString() {
		return "CrateSlotStorage[" + slot + ": " + getAmount() + "/" + getCapacity() + " " + getResource() + "]";
	}
}
