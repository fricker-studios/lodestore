package dev.fricker.lodestore.block;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;

import dev.fricker.lodestore.api.LodestoreRegistries;
import dev.fricker.lodestore.api.drawer.DrawerTier;
import dev.fricker.lodestore.registry.LodestoreBlockEntities;

/**
 * A drawer, currently a plain container.
 *
 * <p>Extends {@link BaseContainerBlockEntity} so it is a vanilla {@code Container}: hoppers, pipes
 * and the transfer API all interoperate with no extra work, and the shulker-style "keeps contents"
 * behaviour comes from the loot table copying the implicit {@code minecraft:container} component
 * this base class already exposes.
 *
 * <p>Still has no ticker — drawers remain purely reactive.
 */
public class DrawerBlockEntity extends BaseContainerBlockEntity {
	private NonNullList<ItemStack> items;

	/**
	 * What the front face shows: the most common item inside, and how many of it there are.
	 *
	 * <p>Kept as a field and synced on its own rather than derived client-side from the contents,
	 * so the client never needs the inventory. Broadcasting all 54 slots on every hopper insert to
	 * draw one icon is what the delta-sync rule exists to avoid; this payload is one stack and a
	 * long however full the drawer is.
	 */
	private ItemStack previewItem = ItemStack.EMPTY;
	private long previewCount;

	public DrawerBlockEntity(BlockPos pos, BlockState state) {
		super(LodestoreBlockEntities.DRAWER, pos, state);
		this.items = NonNullList.withSize(rows() * 9, ItemStack.EMPTY);
	}

	/** Rows come from the block, not the datapack, so the size is known before the level is. */
	private int rows() {
		return getBlockState().getBlock() instanceof DrawerBlock drawer ? drawer.rows() : 3;
	}

	/** Resolves this drawer's behaviour from the datapack-driven registry. */
	public DrawerTier tier() {
		return getBlockState().getBlock() instanceof DrawerBlock drawer ? resolve(drawer.tierKey()) : DrawerTier.FALLBACK;
	}

	private DrawerTier resolve(ResourceKey<DrawerTier> key) {
		if (level == null) {
			return DrawerTier.FALLBACK;
		}

		return level.registryAccess()
				.lookup(LodestoreRegistries.DRAWER_TIER)
				.flatMap(registry -> registry.getOptional(key))
				.orElse(DrawerTier.FALLBACK);
	}

	public ItemStack previewItem() {
		return previewItem;
	}

	public long previewCount() {
		return previewCount;
	}

	/** Picks the most numerous item in the drawer, counting across every slot holding it. */
	private void recomputePreview() {
		List<ItemStack> kinds = new ArrayList<>();
		List<Long> totals = new ArrayList<>();

		for (int slot = 0; slot < getContainerSize(); slot++) {
			ItemStack stack = getItem(slot);

			if (stack.isEmpty()) {
				continue;
			}

			int index = -1;

			for (int i = 0; i < kinds.size(); i++) {
				if (ItemStack.isSameItemSameComponents(kinds.get(i), stack)) {
					index = i;
					break;
				}
			}

			if (index < 0) {
				kinds.add(stack);
				totals.add((long) stack.getCount());
			} else {
				totals.set(index, totals.get(index) + stack.getCount());
			}
		}

		ItemStack best = ItemStack.EMPTY;
		long bestTotal = 0;

		for (int i = 0; i < kinds.size(); i++) {
			if (totals.get(i) > bestTotal) {
				best = kinds.get(i);
				bestTotal = totals.get(i);
			}
		}

		previewItem = best;
		previewCount = bestTotal;
	}

	@Override
	public void setChanged() {
		super.setChanged();

		if (level == null || level.isClientSide()) {
			return;
		}

		ItemStack previousItem = previewItem;
		long previousCount = previewCount;
		recomputePreview();

		// Only tell clients when the face would actually look different.
		if (!ItemStack.isSameItemSameComponents(previousItem, previewItem) || previousCount != previewCount) {
			level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
		}
	}

	@Override
	public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}

	/** Deliberately carries the preview only, never the inventory. */
	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);

		if (!previewItem.isEmpty()) {
			out.store("preview_item", ItemStack.CODEC, previewItem);
			out.putLong("preview_count", previewCount);
		}

		return out.buildResult();
	}

	@Override
	public int getContainerSize() {
		return rows() * 9;
	}

	@Override
	protected NonNullList<ItemStack> getItems() {
		return items;
	}

	@Override
	protected void setItems(NonNullList<ItemStack> replacement) {
		this.items = replacement;
	}

	@Override
	protected void loadAdditional(ValueInput data) {
		super.loadAdditional(data);

		// Present only in the sync tag; a saved drawer recomputes from its contents instead.
		previewItem = data.read("preview_item", ItemStack.CODEC).orElse(ItemStack.EMPTY);
		previewCount = data.getLongOr("preview_count", 0L);

		if (previewItem.isEmpty() && !isEmpty()) {
			recomputePreview();
		}
	}

	@Override
	protected Component getDefaultName() {
		return getBlockState().getBlock().getName();
	}

	@Override
	protected AbstractContainerMenu createMenu(int syncId, Inventory playerInventory) {
		return rows() == 6
				? ChestMenu.sixRows(syncId, playerInventory, this)
				: ChestMenu.threeRows(syncId, playerInventory, this);
	}

	/**
	 * Stops a drawer that keeps its contents from holding another of equal or higher rank.
	 *
	 * <p>Applies to hoppers as well as the GUI, since {@code Container#canPlaceItem} governs both —
	 * otherwise the rule would be trivially bypassed by piping one in.
	 */
	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		DrawerTier self = tier();

		if (!self.keepsContents()) {
			return true;
		}

		if (stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof DrawerBlock other) {
			return self.canContain(resolve(other.tierKey()));
		}

		return true;
	}

	/**
	 * Keeps a shulker-style drawer from spilling.
	 *
	 * <p>This is the hook that drops a container block entity's contents on removal. For tiers
	 * whose contents ride inside the dropped item via the {@code minecraft:container} component,
	 * letting it run would duplicate everything inside.
	 */
	@Override
	public void preRemoveSideEffects(BlockPos pos, BlockState state) {
		if (tier().keepsContents()) {
			return;
		}

		super.preRemoveSideEffects(pos, state);
	}

	@Override
	protected void applyImplicitComponents(DataComponentGetter components) {
		super.applyImplicitComponents(components);
		setChanged();
	}

	@Override
	protected void collectImplicitComponents(DataComponentMap.Builder builder) {
		super.collectImplicitComponents(builder);
	}

	@Override
	public @Nullable Component getCustomName() {
		return super.getCustomName();
	}
}
