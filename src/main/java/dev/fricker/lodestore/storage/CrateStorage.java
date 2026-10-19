package dev.fricker.lodestore.storage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import net.minecraft.world.item.ItemStack;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.StoragePreconditions;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;

/**
 * A crate as a {@code Storage<ItemVariant>}: what hoppers, droppers, crafters, pipes and other
 * mods reach through {@code ItemStorage.SIDED}.
 *
 * <p>They reach it at all because the crate's block entity is not a {@code Container}. Fabric's
 * hopper and dropper patches take the transfer-API route only when vanilla finds no container, and
 * a crate that offered both would have hoppers silently using the container — whose
 * {@code getMaxStackSize} cannot name a slot — and disagreeing with every pipe about whether the
 * crate was full. Measured, in {@code StackCeilingGameTests}.
 *
 * <p>Insertion fills slots already holding the item before it opens new ones, which is the order a
 * hopper into a chest has, and then applies the one rule the slot views cannot: a voiding crate
 * destroys what nothing had room for. Fabric's hopper patch never asks a storage whether it is full,
 * so there is no longer a fake slot to look empty — the storage simply accepts the amount and
 * changes nothing.
 *
 * <p>Also the participant that tells the block entity something changed, once per outer transaction
 * rather than once per slot, mirroring the transfer API's own container wrapper.
 */
public final class CrateStorage extends SnapshotParticipant<Boolean> implements SlottedStorage<ItemVariant> {
	private final CrateContents contents;
	private final List<SingleSlotStorage<ItemVariant>> slots;

	CrateStorage(CrateContents contents) {
		this.contents = contents;
		List<SingleSlotStorage<ItemVariant>> views = new ArrayList<>(contents.size());

		for (int slot = 0; slot < contents.size(); slot++) {
			views.add(new CrateSlotStorage(contents, this, slot));
		}

		this.slots = Collections.unmodifiableList(views);
	}

	@Override
	public long insert(ItemVariant variant, long maxAmount, TransactionContext transaction) {
		StoragePreconditions.notBlankNotNegative(variant, maxAmount);

		if (maxAmount == 0) {
			return 0;
		}

		long inserted = 0;

		// Slots already holding this item first: a crate tops up before it spreads out.
		for (int slot = 0; slot < slots.size() && inserted < maxAmount; slot++) {
			ItemStack held = contents.visible(slot);

			if (!held.isEmpty() && variant.matches(held)) {
				inserted += slots.get(slot).insert(variant, maxAmount - inserted, transaction);
			}
		}

		for (int slot = 0; slot < slots.size() && inserted < maxAmount; slot++) {
			if (contents.visible(slot).isEmpty()) {
				inserted += slots.get(slot).insert(variant, maxAmount - inserted, transaction);
			}
		}

		// Voiding is the last resort, not the first, and only for an item the crate already
		// holds. Accepting the rest without storing it is the destruction.
		if (inserted < maxAmount && contents.rules().voids(variant.toStack())) {
			return maxAmount;
		}

		return inserted;
	}

	@Override
	public long extract(ItemVariant variant, long maxAmount, TransactionContext transaction) {
		StoragePreconditions.notBlankNotNegative(variant, maxAmount);
		long extracted = 0;

		for (int slot = 0; slot < slots.size() && extracted < maxAmount; slot++) {
			extracted += slots.get(slot).extract(variant, maxAmount - extracted, transaction);
		}

		return extracted;
	}

	@Override
	public int getSlotCount() {
		return slots.size();
	}

	@Override
	public SingleSlotStorage<ItemVariant> getSlot(int slot) {
		return slots.get(slot);
	}

	@Override
	public Iterator<StorageView<ItemVariant>> iterator() {
		return new ArrayList<StorageView<ItemVariant>>(slots).iterator();
	}

	@Override
	protected Boolean createSnapshot() {
		return Boolean.TRUE;
	}

	@Override
	protected void readSnapshot(Boolean snapshot) {
		// Nothing of its own to restore: the slot views hold the state.
	}

	@Override
	protected void onFinalCommit() {
		contents.rules().onChanged();
	}

	@Override
	public String toString() {
		return "CrateStorage[" + slots.size() + " slots]";
	}
}
