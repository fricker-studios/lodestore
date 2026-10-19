package dev.fricker.lodestore.block;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.LockCode;
import net.minecraft.world.Nameable;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SidedStorageBlockEntity;

import dev.fricker.lodestore.api.LodestoreRegistries;
import dev.fricker.lodestore.api.drawer.DrawerTier;
import dev.fricker.lodestore.api.drawer.DrawerUpgrade;
import dev.fricker.lodestore.api.drawer.UpgradeBehaviour;
import dev.fricker.lodestore.item.DrawerUpgradeItem;
import dev.fricker.lodestore.menu.DrawerMenu;
import dev.fricker.lodestore.menu.DrawerMenuData;
import dev.fricker.lodestore.registry.LodestoreBlockEntities;
import dev.fricker.lodestore.registry.LodestoreComponents;
import dev.fricker.lodestore.storage.ContentsHolder;
import dev.fricker.lodestore.storage.DrawerContents;
import dev.fricker.lodestore.storage.DrawerOverflow;
import dev.fricker.lodestore.storage.DrawerStorage;

/**
 * A drawer.
 *
 * <p>Deliberately <em>not</em> a {@link Container}, and not a {@link BaseContainerBlockEntity}. Being
 * one used to be what made hoppers, droppers, pipes and the comparator work for free, and that was
 * given up on purpose: vanilla's hopper computes the room in a slot from the item's own stack size
 * with the container nowhere in the expression, {@code Container#getMaxStackSize} cannot name a
 * slot, and Fabric's hopper patch takes the transfer-API route only when vanilla finds no container
 * at all. So a drawer that wants per-slot capacity has to be reachable only as a
 * {@link Storage}, which is what {@link SidedStorageBlockEntity} makes it. The measurements are in
 * {@code StackCeilingGameTests} and the README.
 *
 * <p>The contents themselves live in {@link DrawerContents}: a legal stack plus a parked remainder
 * per slot. This class owns the rules the contents ask about — what a slot's capacity is, what it
 * will take, what the drawer voids — and everything a container block entity used to get from its
 * base class: its name, its lock, its menu, its components, and spilling on break.
 *
 * <p>Still has no ticker — drawers remain purely reactive.
 */
public class DrawerBlockEntity extends BlockEntity
		implements ExtendedMenuProvider<DrawerMenuData>, Nameable, SidedStorageBlockEntity, ContentsHolder {
	/** The nominal stack an empty slot's capacity is quoted against. */
	private static final int NOMINAL_STACK = 64;

	private final DrawerContents contents;

	/** The menu's window onto the contents and the storage are the same object's two faces. */
	private final DrawerContents.Rules rules = new DrawerContents.Rules() {
		@Override
		public int capacity(int slot, ItemStack stack) {
			return slotCapacity(slot, stack);
		}

		@Override
		public boolean canStore(int slot, ItemStack stack) {
			return DrawerBlockEntity.this.canStore(slot, stack);
		}

		@Override
		public boolean voids(ItemStack stack) {
			return isVoiding(stack);
		}

		@Override
		public void onChanged() {
			setChanged();
		}

		@Override
		public boolean stillValid(Player player) {
			return Container.stillValidBlockEntity(DrawerBlockEntity.this, player);
		}
	};

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

	/** The custom name and lock a container block entity used to inherit. Same keys, same codecs. */
	private @Nullable Component name;
	private LockCode lockKey = LockCode.NO_LOCK;

	/**
	 * Installed upgrades, one stack each.
	 *
	 * <p>Kept outside the contents on purpose: upgrades are hardware bolted to the drawer, not
	 * stored goods. Putting them in the inventory would expose them in the chest screen, let a
	 * hopper pull one out, and — for the tiers whose contents ride inside the dropped item — make
	 * them duplicate along with everything else.
	 */
	private final NonNullList<ItemStack> upgrades =
			NonNullList.withSize(DrawerTier.MAX_UPGRADE_SLOTS, ItemStack.EMPTY);

	/**
	 * The upgrade slots as a {@link Container}, for the drawer menu to hang slots off.
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
	 * Storage slots the player has chosen for each behaviour, or empty for the default.
	 *
	 * <p>Empty does not mean "none". It means the first {@code capacity} slots, which is what an
	 * upgrade covers before anyone has told it otherwise — an upgrade that did nothing until its
	 * owner had clicked nine slots would simply look broken.
	 */
	private final Map<UpgradeBehaviour, SortedSet<Integer>> targets = new EnumMap<>(UpgradeBehaviour.class);

	/**
	 * What each locked slot has been locked to.
	 *
	 * <p>A locked slot takes the item it holds, or the first one put into it, and refuses anything
	 * else afterwards. Captured rather than read back from the contents, so the lock survives the
	 * slot being emptied: an empty locked slot still says what belongs in it.
	 */
	private final NonNullList<ItemStack> slotFilters;

	/**
	 * How deep the slots go, as the server last told this client: the multiplier for items that
	 * stack, and the count for items that do not.
	 *
	 * <p>The client never has the installed upgrades, so it cannot work this out; like the covered
	 * sets, it is sent the answer.
	 */
	private int syncedStackMultiplier = 1;
	private int syncedUnstackableDepth = 1;

	/** What the client was last told, so a change worth sending can be spotted in setChanged. */
	private List<ItemStack> broadcastFilters = List.of();
	private final Map<UpgradeBehaviour, Set<Integer>> broadcastTargets = new EnumMap<>(UpgradeBehaviour.class);
	private int broadcastStackMultiplier = 1;
	private int broadcastUnstackableDepth = 1;
	private DrawerOverflow broadcastOverflow = DrawerOverflow.EMPTY;

	public DrawerBlockEntity(BlockPos pos, BlockState state) {
		super(LodestoreBlockEntities.DRAWER, pos, state);
		this.contents = new DrawerContents(storageSize(), rules);
		this.slotFilters = NonNullList.withSize(storageSize(), ItemStack.EMPTY);

		for (UpgradeBehaviour behaviour : UpgradeBehaviour.values()) {
			targets.put(behaviour, new TreeSet<>());
			broadcastTargets.put(behaviour, Set.of());
		}
	}

	/** Rows come from the block, not the datapack, so the size is known before the level is. */
	private int rows() {
		return getBlockState().getBlock() instanceof DrawerBlock drawer ? drawer.rows() : 3;
	}

	/** How many slots store goods — what the chest screen shows, and all there is. */
	public int storageSize() {
		return rows() * 9;
	}

	/** The contents: the menu's container, and the owner of every count. */
	@Override
	public DrawerContents contents() {
		return contents;
	}

	/** The transfer-API face, which is also what {@link #getItemStorage} hands out. */
	public DrawerStorage storage() {
		return contents.storage();
	}

	@Override
	public @Nullable Storage<ItemVariant> getItemStorage(@Nullable Direction side) {
		return contents.storage();
	}

	public boolean isEmpty() {
		return contents.isEmpty();
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

	// --- capacity ----------------------------------------------------------------

	/**
	 * How many of {@code stack} a slot may hold. The one place capacity is decided; the storage,
	 * the menu's slots and the comparator all ask here, which is why none of them can disagree
	 * about how full a slot is.
	 *
	 * <p>Without a stack upgrade it is the item's own stack size. With one, an item that stacks
	 * holds its stack size times the upgrade's multiplier — 256, 512 and 1,024 of a 64-stack across
	 * the shipped levels, and a 16-stack scales the same way to 64, 128 and 256 — and an item that
	 * does not stack holds the upgrade's own count for those: 8, 16 and 32. Both numbers are data
	 * on the upgrade, and the deepest installed upgrade's numbers are the drawer's.
	 */
	public int slotCapacity(int slot, ItemStack stack) {
		int perStack = stack.isEmpty() ? NOMINAL_STACK : Math.max(1, stack.getMaxStackSize());

		if (!isStackedSlot(slot)) {
			return perStack;
		}

		return perStack == 1 ? unstackableDepth() : perStack * stackMultiplier();
	}

	/** How many more of a slot's item are parked behind its visible stack. */
	public long remainder(int slot) {
		return slot >= 0 && slot < storageSize() ? contents.remainder(slot) : 0;
	}

	/** Everything in a slot, visible and parked. */
	public long total(int slot) {
		return slot >= 0 && slot < storageSize() ? contents.total(slot) : 0;
	}

	/** Whether a stack upgrade is installed, so every slot holds more than it otherwise would. */
	public boolean stacks() {
		return stackMultiplier() > 1 || unstackableDepth() > 1;
	}

	/**
	 * How many stacks a slot holds of an item that stacks. The deepest installed stack upgrade
	 * decides; depth does not pool, so two level-ones are one level-one.
	 */
	public int stackMultiplier() {
		return isClient() ? syncedStackMultiplier : deepest(DrawerUpgrade::stacksPerSlot);
	}

	/** How many a slot holds of an item that does not stack, from the deepest installed upgrade. */
	public int unstackableDepth() {
		return isClient() ? syncedUnstackableDepth : deepest(DrawerUpgrade::unstackablePerSlot);
	}

	private int deepest(java.util.function.ToIntFunction<DrawerUpgrade> number) {
		int depth = 1;

		for (ItemStack installed : upgrades) {
			if (installed.getItem() instanceof DrawerUpgradeItem item) {
				depth = Math.max(depth, number.applyAsInt(resolveUpgrade(item.upgradeKey())));
			}
		}

		return depth;
	}

	// --- upgrades ----------------------------------------------------------------

	public ItemStack previewItem() {
		return previewItem;
	}

	public long previewCount() {
		return previewCount;
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

	/** The upgrade slots, for the menu. */
	public Container upgradeContainer() {
		return upgradeContainer;
	}

	/** Whether an upgrade of this kind is already installed. */
	public boolean hasUpgrade(net.minecraft.world.item.Item item) {
		for (ItemStack installed : upgrades) {
			if (!installed.isEmpty() && installed.getItem() == item) {
				return true;
			}
		}

		return false;
	}

	/** Storage slots the installed locks control, pooled across levels and duplicates. */
	public int lockCapacity() {
		return capacity(UpgradeBehaviour.LOCK);
	}

	/**
	 * Reach pools. Two level-one upgrades reach exactly as far as one level-two, because what
	 * decides the reach is the total granted, not which upgrade granted it. Only the lock has a
	 * reach; the other behaviours cover the whole drawer.
	 */
	public int capacity(UpgradeBehaviour behaviour) {
		int total = 0;

		for (ItemStack installed : upgrades) {
			if (installed.getItem() instanceof DrawerUpgradeItem item) {
				DrawerUpgrade upgrade = resolveUpgrade(item.upgradeKey());

				if (behaviour.grantedBy(upgrade)) {
					total += upgrade.slotCapacity();
				}
			}
		}

		return total;
	}

	/**
	 * The storage slots a behaviour covers.
	 *
	 * <p>For the lock: those chosen, or the first {@code capacity} by default. For voiding and
	 * stacking: every slot, once an upgrade granting it is installed. The client knows the lock's
	 * answer from the sync tag and the stack's from the synced depth; it is never told about
	 * voiding, which only the server acts on.
	 */
	public SortedSet<Integer> coveredSlots(UpgradeBehaviour behaviour) {
		if (!behaviour.targetable()) {
			boolean installed = behaviour == UpgradeBehaviour.STACK
					? stacks()
					: !isClient() && anyUpgrade(behaviour::grantedBy);
			return installed ? covered(Collections.emptySortedSet(), storageSize()) : Collections.emptySortedSet();
		}

		SortedSet<Integer> chosen = targets.get(behaviour);
		return isClient() ? Collections.unmodifiableSortedSet(chosen) : covered(chosen, capacity(behaviour));
	}

	public SortedSet<Integer> lockedSlots() {
		return coveredSlots(UpgradeBehaviour.LOCK);
	}

	public SortedSet<Integer> voidedSlots() {
		return coveredSlots(UpgradeBehaviour.VOID);
	}

	public SortedSet<Integer> stackedSlots() {
		return coveredSlots(UpgradeBehaviour.STACK);
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
	 * Adds or removes one storage slot from a behaviour's selection.
	 *
	 * <p>The first edit seeds the selection from the default, so clicking a tenth slot adds to
	 * what the upgrade already covered rather than discarding it. Only the lock can be edited;
	 * the other behaviours cover the whole drawer and the packet is refused.
	 *
	 * @return {@code false} when the selection is already at capacity and nothing changed
	 */
	public boolean toggleTarget(UpgradeBehaviour behaviour, int storageSlot) {
		if (!behaviour.targetable()) {
			return false;
		}

		SortedSet<Integer> chosen = targets.get(behaviour);
		int capacity = capacity(behaviour);

		if (capacity <= 0 || storageSlot < 0 || storageSlot >= storageSize()) {
			return false;
		}

		if (chosen.isEmpty()) {
			chosen.addAll(covered(chosen, capacity));
		}

		if (chosen.contains(storageSlot)) {
			chosen.remove(storageSlot);
		} else if (chosen.size() < capacity) {
			chosen.add(storageSlot);
		} else {
			return false;
		}

		setChanged();
		return true;
	}

	/** Whether a lock covers this storage slot. */
	public boolean isLockedSlot(int storageSlot) {
		return lockedSlots().contains(storageSlot);
	}

	/** Whether a void upgrade covers this storage slot. */
	public boolean isVoidedSlot(int storageSlot) {
		return voidedSlots().contains(storageSlot);
	}

	/** Whether a stack upgrade covers this storage slot — every slot, once one is installed. */
	public boolean isStackedSlot(int storageSlot) {
		return stacks() && storageSlot >= 0 && storageSlot < storageSize();
	}

	/** What a locked slot has been locked to, or empty if it has not claimed anything yet. */
	public ItemStack slotFilter(int storageSlot) {
		return storageSlot >= 0 && storageSlot < slotFilters.size()
				? slotFilters.get(storageSlot) : ItemStack.EMPTY;
	}

	/** The distinct items this drawer's locked slots are claimed by, for the front face. */
	public List<ItemStack> lockedItems() {
		List<ItemStack> locked = new ArrayList<>();

		for (int slot : lockedSlots()) {
			ItemStack filter = slotFilter(slot);

			if (filter.isEmpty()) {
				continue;
			}

			boolean seen = false;

			for (ItemStack already : locked) {
				if (ItemStack.isSameItemSameComponents(already, filter)) {
					seen = true;
					break;
				}
			}

			if (!seen) {
				locked.add(filter);
			}
		}

		return locked;
	}

	/** The first claimed item, or empty. A convenience for callers that need "locked to what". */
	public ItemStack lockedItem() {
		List<ItemStack> locked = lockedItems();
		return locked.isEmpty() ? ItemStack.EMPTY : locked.getFirst();
	}

	/** Whether a lock is installed and at least one of its slots has claimed something. */
	public boolean isLocked() {
		return !lockedItems().isEmpty();
	}

	/** Whether {@code stack} is what this slot is locked to, or the slot has claimed nothing yet. */
	private boolean slotAccepts(int storageSlot, ItemStack stack) {
		ItemStack filter = slotFilter(storageSlot);
		return filter.isEmpty() || ItemStack.isSameItemSameComponents(filter, stack);
	}

	/** Locked slots claim whatever lands in them, and keep that claim once the slot empties. */
	private void captureSlotFilters() {
		for (int slot : lockedSlots()) {
			if (slot < slotFilters.size() && slotFilters.get(slot).isEmpty()) {
				ItemStack held = contents.visible(slot);

				if (!held.isEmpty()) {
					slotFilters.set(slot, held.copyWithCount(1));
				}
			}
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
	 * @return {@code false} if the drawer is full, in which case nothing is consumed and the
	 *         caller should fall back to opening the drawer
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
	 * Whether this drawer destroys {@code stack} rather than refusing it, once nothing has room.
	 *
	 * <p>Only ever true for an item one of the voided slots has claimed or already holds. The
	 * worst a misrouted item can then do is back the system up, which is recoverable, rather than
	 * vanish, which is not.
	 */
	private boolean isVoiding(ItemStack stack) {
		if (!voidsOverflow()) {
			return false;
		}

		for (int slot : voidedSlots()) {
			ItemStack claimed = slotFilter(slot);

			if (!claimed.isEmpty() && ItemStack.isSameItemSameComponents(claimed, stack)) {
				return true;
			}

			ItemStack held = contents.visible(slot);

			if (!held.isEmpty() && ItemStack.isSameItemSameComponents(held, stack)) {
				return true;
			}
		}

		return false;
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

	/**
	 * Whether a slot would take this kind of item at all, room aside.
	 *
	 * <p>The lock and the nesting rule, in one place, asked by both doors into the contents: the
	 * storage's insert and the menu's slot. A rule enforced at only one of them is not a rule — the
	 * screen alone would be bypassed by piping items in, and the storage alone by a player's hand.
	 * Works on the client too, from the synced claims and the synced registry, so the screen can
	 * predict a refusal instead of flickering.
	 */
	public boolean canStore(int slot, ItemStack stack) {
		if (slot < 0 || slot >= storageSize()) {
			return false;
		}

		// Upgrades are hardware, not goods, and the storage never holds them. This is also what
		// keeps an inventory sorter from shuffling an installed upgrade into the grid: nothing in
		// the container region will take it, so it stays where it was.
		if (stack.getItem() instanceof DrawerUpgradeItem) {
			return false;
		}

		if (isLockedSlot(slot) && !slotAccepts(slot, stack)) {
			return false;
		}

		DrawerTier self = tier();

		if (!self.keepsContents()) {
			return true;
		}

		// Stops a drawer that keeps its contents from holding another of equal or higher rank,
		// which would otherwise let storage compound without limit.
		if (stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof DrawerBlock other) {
			return self.canContain(resolve(other.tierKey()));
		}

		return true;
	}

	/**
	 * Comparator output, each slot measured against its own capacity.
	 *
	 * <p>A stack-upgraded slot a quarter full reads a quarter full, so a hopper line into one does
	 * not look done until it is.
	 */
	public int comparatorSignal() {
		return Mth.lerpDiscrete(contents.fill(), 0, 15);
	}

	/** Picks the most numerous item in the drawer, counting across every slot holding it. */
	private void recomputePreview() {
		List<ItemStack> kinds = new ArrayList<>();
		List<Long> totals = new ArrayList<>();

		for (int slot = 0; slot < storageSize(); slot++) {
			ItemStack stack = contents.visible(slot);

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
				totals.add(contents.total(slot));
			} else {
				totals.set(index, totals.get(index) + contents.total(slot));
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

		// With a stack upgrade, the same item spread over several slots is gathered into the first
		// ones. After the rebalance the menu has done, before anything is measured or sent.
		if (stacks()) {
			contents.compact();
		}

		ItemStack previousItem = previewItem;
		long previousCount = previewCount;
		recomputePreview();

		// Each locked slot claims whatever lands in it. A slot that claimed nothing would
		// refuse everything, including the item the lock was installed for.
		captureSlotFilters();

		List<ItemStack> currentFilters = lockedItems();
		int currentMultiplier = stackMultiplier();
		int currentUnstackable = unstackableDepth();
		DrawerOverflow currentOverflow = contents.overflow();
		boolean targetsChanged = false;

		for (UpgradeBehaviour behaviour : UpgradeBehaviour.values()) {
			if (!broadcastTargets.get(behaviour).equals(coveredSlots(behaviour))) {
				targetsChanged = true;
				break;
			}
		}

		// Only tell clients when the face or the screen would actually look different. Targeting
		// is in here because choosing a slot changes nothing else — without it the tints in the
		// screen would never update. The remainders are in here for the slot tooltip, and cost
		// bytes rather than packets: a parked count cannot change without the face's count
		// changing with it.
		if (!ItemStack.isSameItemSameComponents(previousItem, previewItem) || previousCount != previewCount
				|| !sameStacks(broadcastFilters, currentFilters) || targetsChanged
				|| broadcastStackMultiplier != currentMultiplier || broadcastUnstackableDepth != currentUnstackable
				|| !broadcastOverflow.equals(currentOverflow)) {
			broadcastFilters = currentFilters;
			broadcastStackMultiplier = currentMultiplier;
			broadcastUnstackableDepth = currentUnstackable;
			broadcastOverflow = currentOverflow;

			for (UpgradeBehaviour behaviour : UpgradeBehaviour.values()) {
				broadcastTargets.put(behaviour, new TreeSet<>(coveredSlots(behaviour)));
			}

			level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
		}
	}

	// --- persistence -------------------------------------------------------------

	/**
	 * Writes which slots each behaviour covers and what each locked slot has claimed.
	 *
	 * <p>Shared by the disk write and the client sync tag: the screen highlights the covered slots
	 * and the front face draws the claims, so both sides need the same state and there is no
	 * reason for them to disagree about its shape.
	 */
	private void saveTargeting(ValueOutput out, boolean resolved) {
		// Disk keeps what was chosen, so "nothing chosen" stays the default and follows the
		// upgrades if they change. The sync tag carries the resolved answer, because the client
		// has no upgrades to resolve it from.
		for (UpgradeBehaviour behaviour : UpgradeBehaviour.values()) {
			if (!behaviour.targetable()) {
				continue;
			}

			Collection<Integer> slots = resolved ? coveredSlots(behaviour) : targets.get(behaviour);

			if (!slots.isEmpty()) {
				out.putIntArray(targetKey(behaviour), toIntArray(slots));
			}
		}

		List<Integer> claimedSlots = new ArrayList<>();
		List<ItemStack> claimedItems = new ArrayList<>();

		for (int slot = 0; slot < slotFilters.size(); slot++) {
			if (!slotFilters.get(slot).isEmpty()) {
				claimedSlots.add(slot);
				claimedItems.add(slotFilters.get(slot));
			}
		}

		if (!claimedItems.isEmpty()) {
			out.putIntArray("claim_slots", toIntArray(claimedSlots));
			out.store("claim_items", ItemStack.CODEC.listOf(), claimedItems);
		}
	}

	private void loadTargeting(ValueInput data) {
		slotFilters.clear();

		// A save from when voiding was targeted carries void_targets; it is ignored, since voiding
		// now covers the whole drawer.
		for (UpgradeBehaviour behaviour : UpgradeBehaviour.values()) {
			SortedSet<Integer> chosen = targets.get(behaviour);
			chosen.clear();

			if (behaviour.targetable()) {
				data.getIntArray(targetKey(behaviour)).ifPresent(slots -> addAll(chosen, slots));
			}
		}

		int[] claimedSlots = data.getIntArray("claim_slots").orElse(new int[0]);
		List<ItemStack> claimedItems = data.read("claim_items", ItemStack.CODEC.listOf()).orElse(List.of());

		for (int i = 0; i < claimedSlots.length && i < claimedItems.size(); i++) {
			int slot = claimedSlots[i];

			if (slot >= 0 && slot < slotFilters.size()) {
				slotFilters.set(slot, claimedItems.get(i));
			}
		}
	}

	/** {@code lock_targets} predates the enum and keeps its name. */
	private static String targetKey(UpgradeBehaviour behaviour) {
		return behaviour.name().toLowerCase(java.util.Locale.ROOT) + "_targets";
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

	/**
	 * Deliberately carries the preview only, never the inventory.
	 *
	 * <p>The parked remainders go with it, under their own key so a reload from disk cannot
	 * mistake them for the saved ones: the client has no stacks to attach them to, and reads them
	 * as bare counts for the screen's slot tooltip.
	 */
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
		out.putInt("stack_multiplier", stackMultiplier());
		out.putInt("stack_unstackable", unstackableDepth());

		DrawerOverflow overflow = contents.overflow();

		if (!overflow.isEmpty()) {
			out.store("synced_overflow", DrawerOverflow.CODEC, overflow);
		}

		return out.buildResult();
	}

	/**
	 * Writes the contents and everything bolted to the drawer.
	 *
	 * <p>The contents are today's {@code Items} list plus the remainders when there are any, so a
	 * world saved before the storage layer existed loads with no conversion: it simply has no
	 * remainders. The name and lock use vanilla's keys, which {@code BaseContainerBlockEntity}
	 * used to write and this class now does.
	 */
	@Override
	protected void saveAdditional(ValueOutput data) {
		super.saveAdditional(data);
		contents.save(data);
		lockKey.addToTag(data);
		data.storeNullable("CustomName", ComponentSerialization.CODEC, name);

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
		contents.load(data);
		lockKey = LockCode.fromTag(data);
		name = parseCustomNameSafe(data, "CustomName");

		// Present only in the sync tag, which deliberately carries no items; a drawer loaded
		// from disk has the real contents and recomputes from them instead.
		upgrades.clear();
		List<ItemStack> saved = data.read("upgrades", ItemStack.CODEC.listOf()).orElse(List.of());

		for (int slot = 0; slot < saved.size() && slot < upgrades.size(); slot++) {
			upgrades.set(slot, saved.get(slot));
		}

		loadTargeting(data);
		broadcastFilters = lockedItems();

		syncedStackMultiplier = data.getIntOr("stack_multiplier", 1);
		syncedUnstackableDepth = data.getIntOr("stack_unstackable", 1);
		data.read("synced_overflow", DrawerOverflow.CODEC)
				.ifPresent(overflow -> contents.applyOverflow(overflow, false));

		previewItem = data.read("preview_item", ItemStack.CODEC).orElse(ItemStack.EMPTY);
		previewCount = data.getLongOr("preview_count", 0L);

		if (previewItem.isEmpty() && !isEmpty()) {
			recomputePreview();
		}
	}

	// --- name and lock -----------------------------------------------------------

	@Override
	public Component getName() {
		return name != null ? name : getBlockState().getBlock().getName();
	}

	@Override
	public Component getDisplayName() {
		return getName();
	}

	@Override
	public @Nullable Component getCustomName() {
		return name;
	}

	public boolean canOpen(Player player) {
		return lockKey.canUnlock(player);
	}

	// --- menu --------------------------------------------------------------------

	/**
	 * What the menu needs that its slots cannot carry: whether a lock is installed, which decides
	 * whether the filter grid is there at all. One int, synced by the menu's own data-slot path.
	 */
	private final ContainerData menuData = new ContainerData() {
		@Override
		public int get(int index) {
			return lockCapacity() > 0 ? 1 : 0;
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
	public @Nullable AbstractContainerMenu createMenu(int syncId, Inventory playerInventory, Player player) {
		if (!canOpen(player)) {
			BaseContainerBlockEntity.sendChestLockedNotifications(Vec3.atCenterOf(worldPosition), player, getDisplayName());
			return null;
		}

		return new DrawerMenu(syncId, playerInventory, contents, this, upgradeContainer,
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

	// --- breaking and components -------------------------------------------------

	/**
	 * What happens to the contents when the block goes.
	 *
	 * <p>Vanilla's default spills a block entity's contents only if it is a {@code Container}, and
	 * this one is not, so spilling is ours to do: every slot's total, as as many legal stacks as it
	 * takes. The tiers that keep their contents skip it — for them the contents ride inside the
	 * dropped item via the components below, and spilling as well would duplicate everything.
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

		if (tier().keepsContents() || level == null) {
			return;
		}

		NonNullList<ItemStack> spilled = NonNullList.create();
		spilled.addAll(contents.drain());
		Containers.dropContents(level, pos, spilled);
	}

	/**
	 * Reads a placed drawer's contents back off the item.
	 *
	 * <p>{@code minecraft:container} carries the visible stacks; the remainders come separately,
	 * as counts against those stacks, and a count whose stack did not arrive is dropped rather than
	 * attached to nothing.
	 */
	@Override
	protected void applyImplicitComponents(DataComponentGetter components) {
		super.applyImplicitComponents(components);
		name = components.get(DataComponents.CUSTOM_NAME);
		lockKey = components.getOrDefault(DataComponents.LOCK, LockCode.NO_LOCK);

		contents.clearContent();
		components.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(contents.items());
		contents.applyOverflow(
				components.getOrDefault(LodestoreComponents.DRAWER_OVERFLOW, DrawerOverflow.EMPTY), true);
		setChanged();
	}

	/**
	 * What the dropped item carries for the tiers whose loot table copies it.
	 *
	 * <p>The visible stacks are all legal, so {@code minecraft:container} validates none of them
	 * away. The remainders go in their own component, and only when there are any, so an ordinary
	 * drawer's drop stays identical to a fresh item and the two still stack.
	 */
	@Override
	protected void collectImplicitComponents(DataComponentMap.Builder builder) {
		super.collectImplicitComponents(builder);
		builder.set(DataComponents.CUSTOM_NAME, name);

		if (!lockKey.equals(LockCode.NO_LOCK)) {
			builder.set(DataComponents.LOCK, lockKey);
		}

		builder.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(contents.items()));

		DrawerOverflow overflow = contents.overflow();

		if (!overflow.isEmpty()) {
			builder.set(LodestoreComponents.DRAWER_OVERFLOW, overflow);
		}
	}

	@Override
	public void removeComponentsFromTag(ValueOutput output) {
		output.discard("CustomName");
		output.discard("lock");
		output.discard("Items");
		output.discard(DrawerContents.OVERFLOW_KEY);
	}
}
