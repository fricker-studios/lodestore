package dev.fricker.lodestore.block;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
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
import dev.fricker.lodestore.menu.DrawerMenu;
import dev.fricker.lodestore.menu.DrawerMenuData;

import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
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
public class DrawerBlockEntity extends BaseContainerBlockEntity
		implements ExtendedMenuProvider<DrawerMenuData> {
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
	private final NonNullList<ItemStack> upgrades =
			NonNullList.withSize(DrawerTier.MAX_UPGRADE_SLOTS, ItemStack.EMPTY);

	/**
	 * The upgrade slots as a {@link Container}, for the drawer menu to hang slots off.
	 *
	 * <p>Deliberately a separate object from the block entity. The block entity is the Container
	 * that hoppers, droppers and the transfer API see; exposing upgrades through it would be the
	 * very thing the field above is kept out of {@link #getItems()} to avoid.
	 *
	 * <p>Sized to {@link DrawerTier#MAX_UPGRADE_SLOTS} rather than to this drawer's tier, because
	 * a datapack reload can change the tier's count under a drawer that already exists. The menu
	 * shows only as many slots as the tier allows; the rest stay addressable so an upgrade in a
	 * slot a shrunken tier no longer offers is still there to be taken out.
	 */
	private final Container upgradeContainer = new Container() {
		@Override
		public int getContainerSize() {
			return upgrades.size();
		}

		@Override
		public boolean isEmpty() {
			return installedUpgrades().isEmpty();
		}

		@Override
		public ItemStack getItem(int slot) {
			return upgrades.get(slot);
		}

		@Override
		public ItemStack removeItem(int slot, int amount) {
			ItemStack removed = ContainerHelper.removeItem(upgrades, slot, amount);

			if (!removed.isEmpty()) {
				setChanged();
			}

			return removed;
		}

		@Override
		public ItemStack removeItemNoUpdate(int slot) {
			return ContainerHelper.takeItem(upgrades, slot);
		}

		@Override
		public void setItem(int slot, ItemStack stack) {
			upgrades.set(slot, stack);
			setChanged();
		}

		@Override
		public void setChanged() {
			DrawerBlockEntity.this.setChanged();
		}

		@Override
		public boolean stillValid(Player player) {
			return Container.stillValidBlockEntity(DrawerBlockEntity.this, player);
		}

		@Override
		public void clearContent() {
			upgrades.clear();
		}
	};

	/**
	 * Storage slots the player has chosen for the void upgrade, or empty for the default.
	 *
	 * <p>Empty does not mean "none". It means the first {@code capacity} slots, which is what an
	 * upgrade covers before anyone has told it otherwise — an upgrade that did nothing until its
	 * owner had clicked nine slots would simply look broken.
	 */
	private final SortedSet<Integer> voidTargets = new TreeSet<>();

	/**
	 * The kinds of item the lock lets in, distinct, one of each.
	 *
	 * <p>The lock covers the whole drawer. It claims everything the drawer holds when it goes on,
	 * however many kinds that is, or the first item put in if the drawer was empty, and refuses
	 * anything else afterwards. Captured rather than read back from the contents, so the claim
	 * survives the drawer being emptied: an empty locked drawer still says what belongs in it.
	 */
	private final List<ItemStack> lockFilter = new ArrayList<>();

	/**
	 * The storage slot whose item the front face shows, or -1 to show the most numerous item.
	 *
	 * <p>Chosen through the lock, so it exists only while one is installed. {@link #displayItem}
	 * is what that slot last held, so the face keeps naming it after the slot runs dry.
	 */
	private int displaySlot = -1;
	private ItemStack displayItem = ItemStack.EMPTY;

	/** What the client was last told, so a change worth sending can be spotted in setChanged. */
	private List<ItemStack> broadcastFilters = List.of();
	private Set<Integer> broadcastVoid = Set.of();
	private int broadcastDisplaySlot = -1;
	private ItemStack broadcastGhost = ItemStack.EMPTY;

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

	/** Installed upgrades, empties omitted, for the drop-on-break path and for tests. */
	public List<ItemStack> upgrades() {
		return installedUpgrades();
	}

	private List<ItemStack> installedUpgrades() {
		List<ItemStack> installed = new ArrayList<>();

		for (ItemStack stack : upgrades) {
			if (!stack.isEmpty()) {
				installed.add(stack);
			}
		}

		return installed;
	}

	/** The upgrade slots, for the menu. Not the container hoppers see. */
	public Container upgradeContainer() {
		return upgradeContainer;
	}

	/** Whether an upgrade of this kind is already installed; one of each is the rule. */
	public boolean hasUpgrade(net.minecraft.world.item.Item item) {
		for (ItemStack installed : upgrades) {
			if (!installed.isEmpty() && installed.getItem() == item) {
				return true;
			}
		}

		return false;
	}

	/** Storage slots the installed void upgrades control, pooled across duplicates. */
	public int voidCapacity() {
		return capacity(DrawerUpgrade::voidsOverflow);
	}

	/**
	 * Capacity pools. Two upgrades reach exactly as far as one that grants twice as much, because
	 * what decides the reach is the total granted, not which upgrade granted it.
	 */
	private int capacity(Predicate<DrawerUpgrade> flag) {
		int total = 0;

		for (ItemStack installed : upgrades) {
			if (installed.getItem() instanceof DrawerUpgradeItem item) {
				DrawerUpgrade upgrade = resolveUpgrade(item.upgradeKey());

				if (flag.test(upgrade)) {
					total += upgrade.slotCapacity();
				}
			}
		}

		return total;
	}

	/** The storage slots the void upgrades cover. */
	public SortedSet<Integer> voidedSlots() {
		return isClient() ? Collections.unmodifiableSortedSet(voidTargets) : covered(voidTargets, voidCapacity());
	}

	/**
	 * The client never has the installed upgrades — they are deliberately left out of the sync
	 * tag — so it cannot work out how far they reach. It is sent the answer instead, and reads
	 * the stored sets as given rather than resolving a default against a capacity of zero.
	 */
	private boolean isClient() {
		return level != null && level.isClientSide();
	}

	private SortedSet<Integer> covered(SortedSet<Integer> chosen, int capacity) {
		if (capacity <= 0) {
			return Collections.emptySortedSet();
		}

		if (!chosen.isEmpty()) {
			return Collections.unmodifiableSortedSet(chosen);
		}

		SortedSet<Integer> fallback = new TreeSet<>();

		// "Up to": a capacity past the end of the drawer simply covers all of it.
		for (int slot = 0; slot < Math.min(capacity, storageSize()); slot++) {
			fallback.add(slot);
		}

		return fallback;
	}

	/**
	 * Adds or removes one storage slot from the void upgrade's selection.
	 *
	 * <p>The first edit seeds the selection from the default, so clicking a tenth slot adds to
	 * what the upgrade already covered rather than discarding it.
	 *
	 * @return {@code false} when the selection is already at capacity and nothing changed
	 */
	public boolean toggleVoidTarget(int storageSlot) {
		int capacity = voidCapacity();

		if (capacity <= 0 || storageSlot < 0 || storageSlot >= storageSize()) {
			return false;
		}

		if (voidTargets.isEmpty()) {
			voidTargets.addAll(covered(voidTargets, capacity));
		}

		if (voidTargets.contains(storageSlot)) {
			voidTargets.remove(storageSlot);
		} else if (voidTargets.size() < capacity) {
			voidTargets.add(storageSlot);
		} else {
			return false;
		}

		setChanged();
		return true;
	}

	/**
	 * Chooses the storage slot whose item the front face shows. Choosing the same slot again goes
	 * back to showing whatever the drawer holds most of.
	 *
	 * <p>Only a slot that holds something can be chosen, since there would be nothing to show, and
	 * only while a lock is installed, because the lock is what offers the choice.
	 *
	 * @return {@code false} when nothing changed
	 */
	public boolean chooseDisplaySlot(int storageSlot) {
		if (!locks() || storageSlot < 0 || storageSlot >= storageSize()) {
			return false;
		}

		if (storageSlot == displaySlot) {
			displaySlot = -1;
			displayItem = ItemStack.EMPTY;
		} else if (items.get(storageSlot).isEmpty()) {
			return false;
		} else {
			displaySlot = storageSlot;
			displayItem = items.get(storageSlot).copyWithCount(1);
		}

		setChanged();
		return true;
	}

	/** The storage slot the face shows, or -1 for the most numerous item. Synced to the client. */
	public int displaySlot() {
		return displaySlot;
	}

	/** Whether a void upgrade covers this storage slot. */
	public boolean isVoidedSlot(int storageSlot) {
		return voidsOverflow() && voidedSlots().contains(storageSlot);
	}

	/** The kinds of item the lock lets in; empty with no lock, or one that has claimed nothing yet. */
	public List<ItemStack> lockedItems() {
		// The client has no upgrades to check, and is only ever sent a filter that applies.
		return isClient() || locks() ? Collections.unmodifiableList(lockFilter) : List.of();
	}

	/**
	 * The one item an empty locked drawer names on its face: whatever its display slot last
	 * showed, or else the first thing it was locked to. It holds still rather than cycling through
	 * the filter, so the face says one thing about what the drawer is for.
	 */
	public ItemStack ghostItem() {
		List<ItemStack> locked = lockedItems();

		if (locked.isEmpty()) {
			return ItemStack.EMPTY;
		}

		return displayItem.isEmpty() ? locked.getFirst() : displayItem;
	}

	/** Whether a lock is installed and has claimed something. */
	public boolean isLocked() {
		return !lockedItems().isEmpty();
	}

	/**
	 * Whether the drawer will take {@code stack}, as far as the lock is concerned.
	 *
	 * <p>Before the lock has claimed anything, the contents stand in for the claim. That is what
	 * keeps "the first item put into an empty drawer" true inside a single transfer: the claim is
	 * only made once a transaction commits, a pipe can insert several kinds before then, and only
	 * the first of them may land.
	 */
	private boolean accepts(ItemStack stack) {
		if (!locks()) {
			return true;
		}

		List<ItemStack> allowed = lockFilter.isEmpty() ? storedKinds() : lockFilter;
		return allowed.isEmpty() || containsKind(allowed, stack);
	}

	/** The distinct kinds of item in storage, one of each, in slot order. */
	private List<ItemStack> storedKinds() {
		List<ItemStack> kinds = new ArrayList<>();

		for (int slot = 0; slot < storageSize(); slot++) {
			ItemStack held = items.get(slot);

			if (!held.isEmpty() && !containsKind(kinds, held)) {
				kinds.add(held.copyWithCount(1));
			}
		}

		return kinds;
	}

	private static boolean containsKind(List<ItemStack> kinds, ItemStack stack) {
		for (ItemStack kind : kinds) {
			if (ItemStack.isSameItemSameComponents(kind, stack)) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Keeps the lock's claim and the display choice in step with the upgrades and the contents.
	 *
	 * <p>Taking the lock off forgets both, so a lock put back on claims afresh from whatever is
	 * inside by then.
	 *
	 * <p>Nothing here guards against a transfer still in flight, and nothing needs to: the transfer
	 * API suppresses the {@link #setChanged()} inside {@code setItem} until the outer transaction
	 * commits. So a pipe that simulates an insert — opens a transaction, inserts, aborts — never
	 * reaches this, and cannot lock an empty drawer to an item that never arrived.
	 */
	private void captureLockFilter() {
		if (!locks()) {
			lockFilter.clear();
			displaySlot = -1;
			displayItem = ItemStack.EMPTY;
			return;
		}

		if (lockFilter.isEmpty()) {
			lockFilter.addAll(storedKinds());
		}

		// The face follows the slot rather than the item: put something else there and that is
		// what it shows.
		if (displaySlot >= 0 && displaySlot < storageSize() && !items.get(displaySlot).isEmpty()) {
			displayItem = items.get(displaySlot).copyWithCount(1);
		}
	}

	/**
	 * How many upgrades this drawer may hold, from its tier.
	 *
	 * <p>Returns 0 on a drawer with no reachable level, since the tier cannot be resolved there and
	 * {@link DrawerTier#FALLBACK} accepts nothing.
	 */
	public int upgradeSlots() {
		return tier().upgradeSlots();
	}

	/**
	 * Installs an upgrade, taking a copy of a single item from {@code stack}.
	 *
	 * @return {@code false} if the drawer is full or already has this upgrade, in which case
	 *         nothing is consumed and the caller should fall back to opening the drawer
	 */
	public boolean installUpgrade(ItemStack stack) {
		if (!(stack.getItem() instanceof DrawerUpgradeItem)) {
			return false;
		}

		// Only the slots this tier offers, even though the list is sized to the ceiling.
		for (int slot = 0; slot < upgradeSlots() && slot < upgrades.size(); slot++) {
			if (upgrades.get(slot).isEmpty()) {
				upgrades.set(slot, stack.copyWithCount(1));
				setChanged();
				return true;
			}
		}

		return false;
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
	 * <p>Only ever true for an item one of the voided slots already holds. The worst a misrouted
	 * item can then do is back the system up, which is recoverable, rather than vanish, which is
	 * not.
	 */
	private boolean isVoiding(ItemStack stack) {
		if (!voidsOverflow()) {
			return false;
		}

		for (int slot : voidedSlots()) {
			ItemStack held = items.get(slot);

			if (!held.isEmpty() && ItemStack.isSameItemSameComponents(held, stack)) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Whether the voided slots could take any of {@code stack} — the question voiding answers.
	 *
	 * <p>Asks only about the slots the void upgrade covers. Room elsewhere in the drawer is not
	 * room as far as voiding is concerned, or a half-targeted drawer would never void at all.
	 */
	private boolean hasRoomInStorage(ItemStack stack) {
		for (int slot : voidedSlots()) {
			ItemStack existing = items.get(slot);

			if (existing.isEmpty()) {
				return accepts(stack);
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
		ItemStack overflow = items.get(overflowSlot());

		if (!overflow.isEmpty() && isVoiding(overflow)) {
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
			// The chosen slot's item wins outright while there is any of it about. Only once the
			// last of it has gone does the face fall back to whatever there is most of.
			if (!displayItem.isEmpty() && ItemStack.isSameItemSameComponents(kinds.get(i), displayItem)) {
				best = kinds.get(i);
				bestTotal = totals.get(i);
				break;
			}

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

		// The lock claims what the drawer holds, or the first item into an empty one. Done before
		// the preview, which reads the display choice this keeps current.
		captureLockFilter();

		ItemStack previousItem = previewItem;
		long previousCount = previewCount;
		recomputePreview();

		List<ItemStack> currentFilters = lockedItems();
		SortedSet<Integer> currentVoid = voidedSlots();
		ItemStack currentGhost = ghostItem();

		// Only tell clients when the face or the screen would actually look different. Targeting
		// is in here because choosing a slot changes nothing else — without it the highlights in
		// the screen would never update.
		if (!ItemStack.isSameItemSameComponents(previousItem, previewItem) || previousCount != previewCount
				|| !sameStacks(broadcastFilters, currentFilters)
				|| !ItemStack.isSameItemSameComponents(broadcastGhost, currentGhost)
				|| !broadcastVoid.equals(currentVoid) || broadcastDisplaySlot != displaySlot) {
			// Copied, because lockedItems() is a live view and would otherwise always match.
			broadcastFilters = List.copyOf(currentFilters);
			broadcastVoid = new TreeSet<>(currentVoid);
			broadcastDisplaySlot = displaySlot;
			broadcastGhost = currentGhost;
			level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
		}
	}

	/**
	 * Writes which slots the void upgrade covers, what the lock has claimed, and which slot the
	 * face shows.
	 *
	 * <p>Shared by the disk write and the client sync tag: the screen highlights the covered and
	 * chosen slots and the front face draws the claim, so both sides need the same state and there
	 * is no reason for them to disagree about its shape.
	 */
	private void saveTargeting(ValueOutput out, boolean resolved) {
		// Disk keeps what was chosen, so "nothing chosen" stays the default and follows the
		// upgrades if they change. The sync tag carries the resolved answer, because the client
		// has no upgrades to resolve it from.
		Collection<Integer> voided = resolved ? voidedSlots() : voidTargets;
		List<ItemStack> filter = resolved ? lockedItems() : lockFilter;

		if (!voided.isEmpty()) {
			out.putIntArray("void_targets", toIntArray(voided));
		}

		if (!filter.isEmpty()) {
			out.store("lock_filter", ItemStack.CODEC.listOf(), filter);
		}

		if (displaySlot >= 0) {
			out.putInt("display_slot", displaySlot);
		}

		if (!displayItem.isEmpty()) {
			out.store("display_item", ItemStack.CODEC, displayItem);
		}
	}

	private void loadTargeting(ValueInput data) {
		voidTargets.clear();
		lockFilter.clear();

		data.getIntArray("void_targets").ifPresent(slots -> addAll(voidTargets, slots));
		lockFilter.addAll(data.read("lock_filter", ItemStack.CODEC.listOf()).orElse(List.of()));
		displaySlot = data.getIntOr("display_slot", -1);
		displayItem = data.read("display_item", ItemStack.CODEC).orElse(ItemStack.EMPTY);
	}

	private static int[] toIntArray(Collection<Integer> values) {
		int[] out = new int[values.size()];
		int i = 0;

		for (int value : values) {
			out[i++] = value;
		}

		return out;
	}

	private static void addAll(SortedSet<Integer> target, int[] values) {
		for (int value : values) {
			target.add(value);
		}
	}

	private static boolean sameStacks(List<ItemStack> left, List<ItemStack> right) {
		if (left.size() != right.size()) {
			return false;
		}

		for (int i = 0; i < left.size(); i++) {
			if (!ItemStack.isSameItemSameComponents(left.get(i), right.get(i))) {
				return false;
			}
		}

		return true;
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
		saveTargeting(out, true);

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

		// Written densely, without the empty slots, so the save format does not depend on
		// MAX_UPGRADE_SLOTS and a later change to that ceiling cannot strand anything.
		List<ItemStack> installed = installedUpgrades();

		if (!installed.isEmpty()) {
			data.store("upgrades", ItemStack.CODEC.listOf(), installed);
		}

		saveTargeting(data, false);
	}

	@Override
	protected void loadAdditional(ValueInput data) {
		super.loadAdditional(data);

		items = NonNullList.withSize(getContainerSize(), ItemStack.EMPTY);
		ContainerHelper.loadAllItems(data, items);

		// Present only in the sync tag, which deliberately carries no items; a drawer loaded
		// from disk has the real contents and recomputes from them instead.
		upgrades.clear();
		List<ItemStack> saved = data.read("upgrades", ItemStack.CODEC.listOf()).orElse(List.of());

		for (int slot = 0; slot < saved.size() && slot < upgrades.size(); slot++) {
			upgrades.set(slot, saved.get(slot));
		}
		loadTargeting(data);
		broadcastFilters = List.copyOf(lockedItems());

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

	/**
	 * What the menu needs that its slots cannot carry: whether a lock is installed, which decides
	 * whether the filter grid is there at all. One int, synced by the menu's own data-slot path.
	 */
	private final net.minecraft.world.inventory.ContainerData menuData =
			new net.minecraft.world.inventory.ContainerData() {
				@Override
				public int get(int index) {
					return locks() ? 1 : 0;
				}

				@Override
				public void set(int index, int value) {
					// Server-owned; the client is told, never the other way round.
				}

				@Override
				public int getCount() {
					return DrawerMenu.DATA_COUNT;
				}
			};

	@Override
	protected AbstractContainerMenu createMenu(int syncId, Inventory playerInventory) {
		return new DrawerMenu(syncId, playerInventory, this, upgradeContainer,
				menuData, rows(), upgradeSlots(), worldPosition);
	}

	/**
	 * The shape the client needs before it can lay the screen out.
	 *
	 * <p>Sent with the open packet, because vanilla's carries only a menu type and a title. Rows
	 * come from the block and the upgrade count from the datapack-driven tier, and the client can
	 * derive neither.
	 */
	@Override
	public DrawerMenuData getScreenOpeningData(ServerPlayer player) {
		return new DrawerMenuData(rows(), upgradeSlots(), worldPosition);
	}

	/**
	 * Stops a drawer that keeps its contents from holding another of equal or higher rank.
	 *
	 * <p>Applies to hoppers as well as the GUI, since {@code Container#canPlaceItem} governs both —
	 * otherwise the rule would be trivially bypassed by piping one in.
	 */
	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		// Checked here rather than in the menu for the same reason the nesting rule is: a filter
		// the screen enforced alone would be bypassed by piping items in.
		if (slot < storageSize() && !accepts(stack)) {
			return false;
		}

		// The overflow slot is not storage, so it takes an item only when there is genuinely
		// nowhere else for it to go. Asking here rather than relying on it being tried last
		// keeps the slot honest to a caller that probes it on its own.
		if (slot == overflowSlot() && (!isVoiding(stack) || hasRoomInStorage(stack))) {
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
			for (ItemStack upgrade : installedUpgrades()) {
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
