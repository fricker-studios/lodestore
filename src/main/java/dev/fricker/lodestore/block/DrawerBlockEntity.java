package dev.fricker.lodestore.block;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

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
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.util.Mth;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.level.storage.ValueInput;

import dev.fricker.lodestore.api.LodestoreRegistries;
import dev.fricker.lodestore.api.drawer.DrawerTier;
import dev.fricker.lodestore.api.drawer.DrawerUpgrade;
import dev.fricker.lodestore.item.DrawerUpgradeItem;
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

	/**
	 * Installed upgrades, one stack each.
	 *
	 * <p>Kept outside {@link #getItems()} on purpose: upgrades are hardware bolted to the drawer,
	 * not stored goods. Putting them in the inventory would expose them in the chest screen, let a
	 * hopper pull one out, and — for the tiers whose contents ride inside the dropped item — make
	 * them duplicate along with everything else.
	 */
	private final List<ItemStack> upgrades = new ArrayList<>();

	/**
	 * The single item a locked drawer accepts, or empty.
	 *
	 * <p>Survives the lock upgrade being removed, so re-locking a drawer restores the filter it
	 * had rather than silently re-claiming whatever happens to be inside at the time.
	 */
	private ItemStack lockedItem = ItemStack.EMPTY;

	public DrawerBlockEntity(BlockPos pos, BlockState state) {
		super(LodestoreBlockEntities.DRAWER, pos, state);
		this.items = NonNullList.withSize(getContainerSize(), ItemStack.EMPTY);
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

	/** Installed upgrades, for the drop-on-break path and for tests. */
	public List<ItemStack> upgrades() {
		return List.copyOf(upgrades);
	}

	public ItemStack lockedItem() {
		return lockedItem;
	}

	/** How many upgrades one drawer may hold, whatever they are. */
	public static final int UPGRADE_SLOTS = 3;

	/**
	 * Installs an upgrade, taking a copy of a single item from {@code stack}.
	 *
	 * @return {@code false} if the drawer is full or already has this upgrade, in which case
	 *         nothing is consumed and the caller should fall back to opening the drawer
	 */
	public boolean installUpgrade(ItemStack stack) {
		if (!(stack.getItem() instanceof DrawerUpgradeItem item) || upgrades.size() >= UPGRADE_SLOTS) {
			return false;
		}

		for (ItemStack installed : upgrades) {
			if (installed.getItem() == item) {
				return false;
			}
		}

		upgrades.add(stack.copyWithCount(1));
		setChanged();
		return true;
	}

	/** Whether any installed upgrade locks this drawer to one kind of item. */
	private boolean locks() {
		return anyUpgrade(DrawerUpgrade::locksContents);
	}

	/** Whether any installed upgrade destroys what this drawer has no room for. */
	private boolean voidsOverflow() {
		return anyUpgrade(DrawerUpgrade::voidsOverflow);
	}

	private boolean anyUpgrade(Predicate<DrawerUpgrade> flag) {
		for (ItemStack installed : upgrades) {
			if (installed.getItem() instanceof DrawerUpgradeItem item
					&& flag.test(resolveUpgrade(item.upgradeKey()))) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Whether this drawer is currently destroying what it cannot hold.
	 *
	 * <p>Requires a filter as well as the flag. Voiding is only ever applied to the one item the
	 * drawer was explicitly told it is for, so the worst a misrouted item can do is back the system
	 * up — which is recoverable — rather than vanish, which is not.
	 */
	private boolean isVoiding() {
		return voidsOverflow() && !lockedItem.isEmpty();
	}

	/** Whether the storage slots could take any of {@code stack} — the question voiding answers. */
	private boolean hasRoomInStorage(ItemStack stack) {
		for (int slot = 0; slot < storageSize(); slot++) {
			ItemStack existing = getItem(slot);

			if (existing.isEmpty()) {
				return true;
			}

			if (ItemStack.isSameItemSameComponents(existing, stack)
					&& existing.getCount() < existing.getMaxStackSize()) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Empties the overflow slot. Writes the list directly rather than going through
	 * {@link #setItem}, which would call {@link #setChanged()} straight back into here.
	 */
	private void discardOverflow() {
		if (isVoiding() && !items.get(overflowSlot()).isEmpty()) {
			items.set(overflowSlot(), ItemStack.EMPTY);
		}
	}

	private DrawerUpgrade resolveUpgrade(ResourceKey<DrawerUpgrade> key) {
		if (level == null) {
			return DrawerUpgrade.FALLBACK;
		}

		return level.registryAccess()
				.lookup(LodestoreRegistries.DRAWER_UPGRADE)
				.flatMap(registry -> registry.getOptional(key))
				.orElse(DrawerUpgrade.FALLBACK);
	}

	public long previewCount() {
		return previewCount;
	}

	/**
	 * Comparator output, measured over the storage slots only.
	 *
	 * <p>{@code AbstractContainerMenu.getRedstoneSignalFromContainer} divides by
	 * {@link #getContainerSize()}, which here counts the overflow slot that can never hold
	 * anything — so a completely full drawer would read 14 and never 15.
	 */
	public int comparatorSignal() {
		float fill = 0.0F;

		for (int slot = 0; slot < storageSize(); slot++) {
			ItemStack stack = getItem(slot);

			if (!stack.isEmpty()) {
				fill += (float) stack.getCount() / getMaxStackSize(stack);
			}
		}

		return Mth.lerpDiscrete(fill / storageSize(), 0, 15);
	}

	/** Picks the most numerous item in the drawer, counting across every slot holding it. */
	private void recomputePreview() {
		List<ItemStack> kinds = new ArrayList<>();
		List<Long> totals = new ArrayList<>();

		for (int slot = 0; slot < storageSize(); slot++) {
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

		// Before the preview is recomputed, so a stack passing through the overflow slot never
		// reaches the face — it was never stored.
		discardOverflow();

		ItemStack previousItem = previewItem;
		long previousCount = previewCount;
		ItemStack previousLock = lockedItem;
		recomputePreview();

		// A lock installed on an empty drawer has nothing to lock to yet, so it claims the
		// first item that arrives. Locking to "nothing" instead would make the drawer refuse
		// everything, including the item the player installed it for.
		if (lockedItem.isEmpty() && !previewItem.isEmpty() && locks()) {
			lockedItem = previewItem.copyWithCount(1);
		}

		// Only tell clients when the face would actually look different.
		if (!ItemStack.isSameItemSameComponents(previousItem, previewItem) || previousCount != previewCount
				|| !ItemStack.isSameItemSameComponents(previousLock, lockedItem)) {
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

		// A locked drawer draws its filter on the face even while empty, so this has to reach
		// the client separately — an empty drawer has no preview to carry it.
		if (!lockedItem.isEmpty()) {
			out.store("locked_item", ItemStack.CODEC, lockedItem);
		}

		return out.buildResult();
	}

	/** How many slots actually store goods — what the chest screen shows. */
	public int storageSize() {
		return rows() * 9;
	}

	/**
	 * The overflow slot: one slot past the storage, never drawn in the chest screen.
	 *
	 * <p>It exists so that a voiding drawer can <em>accept</em> an insert it has no room for.
	 * Nothing else can express that. The lock is enforced in {@link #canPlaceItem}, which only
	 * answers yes or no, and a hopper never even asks: {@code HopperBlockEntity.ejectItems} calls
	 * {@code isFullContainer} first and gives up before any per-slot check. That test passes only
	 * if some slot reports room, so a drawer that wants to keep draining a hopper has to have one.
	 *
	 * <p>Whatever lands here is discarded by {@link #setChanged()} on the same tick, so the slot
	 * reads empty to the next caller and the drawer never looks full to a hopper. A full stack is
	 * taken in one go, because {@code tryMoveInItem} hands an empty slot the whole stack — which is
	 * what keeps throughput the same for a pipe moving 64 as for a hopper moving one.
	 */
	public int overflowSlot() {
		return storageSize();
	}

	/**
	 * Deliberately one larger than {@link #storageSize()}.
	 *
	 * <p>The vanilla {@code ChestMenu} is kept: {@code checkContainerSize} is a minimum rather than
	 * an exact match, and the menu lays out exactly {@code rows * 9} slots, so the overflow slot is
	 * simply not in the screen and no custom menu or screen is needed. It has to be a real index
	 * below this number rather than a virtual one past the end, because the transfer API's sided
	 * wrapper indexes a backing list sized from exactly this method and would throw otherwise.
	 */
	@Override
	public int getContainerSize() {
		return storageSize() + 1;
	}

	@Override
	protected NonNullList<ItemStack> getItems() {
		return items;
	}

	@Override
	protected void setItems(NonNullList<ItemStack> replacement) {
		this.items = replacement;
	}

	/**
	 * Writes the contents.
	 *
	 * <p>{@link BaseContainerBlockEntity} saves only the custom name and lock — every vanilla
	 * container writes its own items, and so must this one. Without it a drawer's contents live
	 * only in memory and vanish the moment the world is reloaded.
	 */
	@Override
	protected void saveAdditional(ValueOutput data) {
		super.saveAdditional(data);
		ContainerHelper.saveAllItems(data, items);

		if (!upgrades.isEmpty()) {
			data.store("upgrades", ItemStack.CODEC.listOf(), upgrades);
		}

		if (!lockedItem.isEmpty()) {
			data.store("locked_item", ItemStack.CODEC, lockedItem);
		}
	}

	@Override
	protected void loadAdditional(ValueInput data) {
		super.loadAdditional(data);

		items = NonNullList.withSize(getContainerSize(), ItemStack.EMPTY);
		ContainerHelper.loadAllItems(data, items);

		// Present only in the sync tag, which deliberately carries no items; a drawer loaded
		// from disk has the real contents and recomputes from them instead.
		upgrades.clear();
		upgrades.addAll(data.read("upgrades", ItemStack.CODEC.listOf()).orElse(List.of()));
		lockedItem = data.read("locked_item", ItemStack.CODEC).orElse(ItemStack.EMPTY);

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
		// Checked ahead of the nesting rule, and here rather than in the menu, for the same
		// reason: a filter the GUI enforced alone would be bypassed by piping items in.
		if (locks() && !lockedItem.isEmpty() && !ItemStack.isSameItemSameComponents(lockedItem, stack)) {
			return false;
		}

		// The overflow slot is not storage, so it takes an item only when there is genuinely
		// nowhere else for it to go. Asking here rather than relying on it being tried last
		// keeps the slot honest to a caller that probes it on its own.
		if (slot == overflowSlot() && (!isVoiding() || hasRoomInStorage(stack))) {
			return false;
		}

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
		// Upgrades drop for every tier, including the ones that keep their contents: they ride
		// outside the inventory, so the `minecraft:container` component never carries them and
		// leaving them here would destroy them with the block.
		if (level != null && !level.isClientSide()) {
			for (ItemStack upgrade : upgrades) {
				Block.popResource(level, pos, upgrade.copy());
			}
		}

		upgrades.clear();

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
