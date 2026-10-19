package dev.fricker.lodestore.block;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
import dev.fricker.lodestore.api.crate.CrateTier;
import dev.fricker.lodestore.api.crate.CrateUpgrade;
import dev.fricker.lodestore.api.crate.UpgradeBehaviour;
import dev.fricker.lodestore.item.CrateUpgradeItem;
import dev.fricker.lodestore.menu.CrateMenu;
import dev.fricker.lodestore.menu.CrateMenuData;
import dev.fricker.lodestore.registry.LodestoreBlockEntities;
import dev.fricker.lodestore.registry.LodestoreComponents;
import dev.fricker.lodestore.storage.ContentsHolder;
import dev.fricker.lodestore.storage.CrateContents;
import dev.fricker.lodestore.storage.CrateOverflow;
import dev.fricker.lodestore.storage.CrateStorage;

/**
 * A crate.
 *
 * <p>Deliberately <em>not</em> a {@link Container}, and not a {@link BaseContainerBlockEntity}. Being
 * one used to be what made hoppers, droppers, pipes and the comparator work for free, and that was
 * given up on purpose: vanilla's hopper computes the room in a slot from the item's own stack size
 * with the container nowhere in the expression, {@code Container#getMaxStackSize} cannot name a
 * slot, and Fabric's hopper patch takes the transfer-API route only when vanilla finds no container
 * at all. So a crate that wants per-slot capacity has to be reachable only as a
 * {@link Storage}, which is what {@link SidedStorageBlockEntity} makes it. The measurements are in
 * {@code StackCeilingGameTests} and the README.
 *
 * <p>The contents themselves live in {@link CrateContents}: a legal stack plus a parked remainder
 * per slot. This class owns the rules the contents ask about — what a slot's capacity is, what it
 * will take, what the crate voids — and everything a container block entity used to get from its
 * base class: its name, its lock, its menu, its components, and spilling on break.
 *
 * <p>Still has no ticker — crates remain purely reactive.
 */
public class CrateBlockEntity extends BlockEntity
		implements ExtendedMenuProvider<CrateMenuData>, Nameable, SidedStorageBlockEntity, ContentsHolder {
	/** The nominal stack an empty slot's capacity is quoted against. */
	private static final int NOMINAL_STACK = 64;

	private final CrateContents contents;

	/** The menu's window onto the contents and the storage are the same object's two faces. */
	private final CrateContents.Rules rules = new CrateContents.Rules() {
		@Override
		public int capacity(int slot, ItemStack stack) {
			return slotCapacity(slot, stack);
		}

		@Override
		public boolean canStore(int slot, ItemStack stack) {
			return CrateBlockEntity.this.canStore(slot, stack);
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
			return Container.stillValidBlockEntity(CrateBlockEntity.this, player);
		}
	};

	/**
	 * What the front face shows: the most common item inside, and how many of it there are.
	 *
	 * <p>Kept as a field and synced on its own rather than derived client-side from the contents,
	 * so the client never needs the inventory. Broadcasting all 54 slots on every hopper insert to
	 * draw one icon is what the delta-sync rule exists to avoid; this payload is one stack and a
	 * long however full the crate is.
	 */
	private ItemStack previewItem = ItemStack.EMPTY;
	private long previewCount;

	/** The custom name and lock a container block entity used to inherit. Same keys, same codecs. */
	private @Nullable Component name;
	private LockCode lockKey = LockCode.NO_LOCK;

	/**
	 * Installed upgrades, one stack each.
	 *
	 * <p>Kept outside the contents on purpose: upgrades are hardware bolted to the crate, not
	 * stored goods. Putting them in the inventory would expose them in the chest screen, let a
	 * hopper pull one out, and — for the tiers whose contents ride inside the dropped item — make
	 * them duplicate along with everything else.
	 */
	private final NonNullList<ItemStack> upgrades =
			NonNullList.withSize(CrateTier.MAX_UPGRADE_SLOTS, ItemStack.EMPTY);

	/**
	 * The upgrade slots as a {@link Container}, for the crate menu to hang slots off.
	 *
	 * <p>Sized to {@link CrateTier#MAX_UPGRADE_SLOTS} rather than to this crate's tier, because
	 * a datapack reload can change the tier's count under a crate that already exists. The menu
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
			onUpgradeInstalled(stack);
			setChanged();
		}

		@Override
		public void setChanged() {
			CrateBlockEntity.this.setChanged();
		}

		@Override
		public boolean stillValid(Player player) {
			return Container.stillValidBlockEntity(CrateBlockEntity.this, player);
		}

		@Override
		public void clearContent() {
			upgrades.clear();
		}
	};

	/**
	 * The kinds of item the lock lets in, distinct, one of each.
	 *
	 * <p>The lock covers the whole crate. It claims everything the crate holds when it goes on,
	 * however many kinds that is, or the first item put in if the crate was empty, and refuses
	 * anything else afterwards. Captured rather than read back from the contents, so the claim
	 * survives the crate being emptied: an empty locked crate still says what belongs in it.
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

	/** Whether a lock is installed, as the server last told this client, which has no upgrades. */
	private boolean syncedLocked;

	/**
	 * How deep the slots go, as the server last told this client: the multiplier for items that
	 * stack, and the count for items that do not.
	 *
	 * <p>The client never has the installed upgrades, so it cannot work this out; like the lock, it
	 * is sent the answer.
	 */
	private int syncedStackMultiplier = 1;
	private int syncedUnstackableDepth = 1;

	/** What the client was last told, so a change worth sending can be spotted in setChanged. */
	private List<ItemStack> broadcastFilters = List.of();
	private boolean broadcastLocked;
	private int broadcastDisplaySlot = -1;
	private ItemStack broadcastGhost = ItemStack.EMPTY;
	private int broadcastStackMultiplier = 1;
	private int broadcastUnstackableDepth = 1;
	private CrateOverflow broadcastOverflow = CrateOverflow.EMPTY;

	public CrateBlockEntity(BlockPos pos, BlockState state) {
		super(LodestoreBlockEntities.CRATE, pos, state);
		this.contents = new CrateContents(storageSize(), rules);
	}

	/** Rows come from the block, not the datapack, so the size is known before the level is. */
	private int rows() {
		return getBlockState().getBlock() instanceof CrateBlock crate ? crate.rows() : 3;
	}

	/** How many slots store goods — what the chest screen shows, and all there is. */
	public int storageSize() {
		return rows() * 9;
	}

	/** The contents: the menu's container, and the owner of every count. */
	@Override
	public CrateContents contents() {
		return contents;
	}

	/** The transfer-API face, which is also what {@link #getItemStorage} hands out. */
	public CrateStorage storage() {
		return contents.storage();
	}

	@Override
	public @Nullable Storage<ItemVariant> getItemStorage(@Nullable Direction side) {
		return contents.storage();
	}

	public boolean isEmpty() {
		return contents.isEmpty();
	}

	/** Resolves this crate's behaviour from the datapack-driven registry. */
	public CrateTier tier() {
		return getBlockState().getBlock() instanceof CrateBlock crate ? resolve(crate.tierKey()) : CrateTier.FALLBACK;
	}

	private CrateTier resolve(ResourceKey<CrateTier> key) {
		if (level == null) {
			return CrateTier.FALLBACK;
		}

		return level.registryAccess()
				.lookup(LodestoreRegistries.CRATE_TIER)
				.flatMap(registry -> registry.getOptional(key))
				.orElse(CrateTier.FALLBACK);
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
	 * on the upgrade, and the deepest installed upgrade's numbers are the crate's.
	 */
	public int slotCapacity(int slot, ItemStack stack) {
		if (slot < 0 || slot >= storageSize()) {
			return 0;
		}

		return capacityWith(stack, stackMultiplier(), unstackableDepth());
	}

	/** The capacity formula, for a given depth — the crate's, or the one it would have. */
	private static int capacityWith(ItemStack stack, int multiplier, int unstackable) {
		int perStack = stack.isEmpty() ? NOMINAL_STACK : Math.max(1, stack.getMaxStackSize());

		if (multiplier <= 1 && unstackable <= 1) {
			return perStack;
		}

		return perStack == 1 ? unstackable : perStack * multiplier;
	}

	/**
	 * Whether the upgrade in this slot may be taken out: only if what the crate holds would still
	 * fit without it, spreading what is parked into empty slots. Otherwise the slot refuses the
	 * pickup and its tooltip says why — the alternative was contents stranded over capacity, or
	 * spilled on the floor, neither of which anyone taking out an upgrade meant.
	 */
	public boolean canRemoveUpgrade(int upgradeSlot) {
		if (upgradeSlot < 0 || upgradeSlot >= upgrades.size() || upgrades.get(upgradeSlot).isEmpty()) {
			return true;
		}

		int multiplier = 1;
		int unstackable = 1;

		for (int slot = 0; slot < upgrades.size(); slot++) {
			if (slot != upgradeSlot && upgrades.get(slot).getItem() instanceof CrateUpgradeItem item) {
				CrateUpgrade upgrade = resolveUpgrade(item.upgradeKey());
				multiplier = Math.max(multiplier, upgrade.stacksPerSlot());
				unstackable = Math.max(unstackable, upgrade.unstackablePerSlot());
			}
		}

		int without = multiplier;
		int withoutUnstackable = unstackable;
		return contents.fitsUnder(stack -> capacityWith(stack, without, withoutUnstackable));
	}

	/** Which upgrade slots may be emptied right now, one bit each, for the menu to sync. */
	private int removableUpgrades() {
		int mask = 0;

		for (int slot = 0; slot < upgrades.size(); slot++) {
			if (canRemoveUpgrade(slot)) {
				mask |= 1 << slot;
			}
		}

		return mask;
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
		return isClient() ? syncedStackMultiplier : deepest(CrateUpgrade::stacksPerSlot);
	}

	/** How many a slot holds of an item that does not stack, from the deepest installed upgrade. */
	public int unstackableDepth() {
		return isClient() ? syncedUnstackableDepth : deepest(CrateUpgrade::unstackablePerSlot);
	}

	private int deepest(java.util.function.ToIntFunction<CrateUpgrade> number) {
		int depth = 1;

		for (ItemStack installed : upgrades) {
			if (installed.getItem() instanceof CrateUpgradeItem item) {
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

	/**
	 * The storage slots a behaviour covers: every slot, once an upgrade granting it is installed,
	 * since none is aimed at particular slots. The client knows the lock's answer and the stack's
	 * from what it is synced; it is never told about voiding, which only the server acts on.
	 */
	public SortedSet<Integer> coveredSlots(UpgradeBehaviour behaviour) {
		boolean installed = switch (behaviour) {
			case LOCK -> locks();
			case STACK -> stacks();
			case VOID -> !isClient() && voidsOverflow();
		};

		if (!installed) {
			return Collections.emptySortedSet();
		}

		SortedSet<Integer> all = new TreeSet<>();

		for (int slot = 0; slot < storageSize(); slot++) {
			all.add(slot);
		}

		return Collections.unmodifiableSortedSet(all);
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
	 * tag — so it cannot work out what they do. It is sent the answers instead.
	 */
	private boolean isClient() {
		return level != null && level.isClientSide();
	}

	/** Whether a void upgrade covers this storage slot. */
	public boolean isVoidedSlot(int storageSlot) {
		return voidedSlots().contains(storageSlot);
	}

	/** Whether a stack upgrade covers this storage slot — every slot, once one is installed. */
	public boolean isStackedSlot(int storageSlot) {
		return stacks() && storageSlot >= 0 && storageSlot < storageSize();
	}

	/**
	 * Chooses the storage slot whose item the front face shows. Choosing the same slot again goes
	 * back to showing whatever the crate holds most of.
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
		} else if (contents.visible(storageSlot).isEmpty()) {
			return false;
		} else {
			displaySlot = storageSlot;
			displayItem = contents.visible(storageSlot).copyWithCount(1);
		}

		setChanged();
		return true;
	}

	/** The storage slot the face shows, or -1 for the most numerous item. Synced to the client. */
	public int displaySlot() {
		return displaySlot;
	}

	/** Whether a lock is installed. The client is told rather than working it out, like the depths. */
	private boolean locks() {
		return isClient() ? syncedLocked : anyUpgrade(CrateUpgrade::locksContents);
	}

	/** The kinds of item the lock lets in; empty with no lock, or one that has claimed nothing yet. */
	public List<ItemStack> lockedItems() {
		return locks() ? Collections.unmodifiableList(lockFilter) : List.of();
	}

	/**
	 * The one item an empty locked crate names on its face: whatever its display slot last
	 * showed, or else the first thing it was locked to. It holds still rather than cycling through
	 * the claim, so the face says one thing about what the crate is for.
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
	 * Whether the crate will take {@code stack}, as far as the lock is concerned.
	 *
	 * <p>Before the lock has claimed anything, the contents stand in for the claim. That is what
	 * keeps "the first item put into an empty crate" true inside a single transfer: the claim is
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
			ItemStack held = contents.visible(slot);

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
	 * <p>Nothing here guards against a transfer still in flight, and nothing needs to: the storage
	 * only reports a change once its outer transaction commits. So a pipe that simulates an insert
	 * — opens a transaction, inserts, aborts — never reaches this, and cannot lock an empty crate
	 * to an item that never arrived.
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
		if (displaySlot >= 0 && displaySlot < storageSize() && !contents.visible(displaySlot).isEmpty()) {
			displayItem = contents.visible(displaySlot).copyWithCount(1);
		}
	}

	/**
	 * How many upgrades this crate may hold, from its tier.
	 *
	 * <p>Returns 0 on a crate with no reachable level, since the tier cannot be resolved there and
	 * {@link CrateTier#FALLBACK} accepts nothing.
	 */
	public int upgradeSlots() {
		return tier().upgradeSlots();
	}

	/**
	 * Installs an upgrade, taking a copy of a single item from {@code stack}.
	 *
	 * @return {@code false} if the crate is full, in which case nothing is consumed and the
	 *         caller should fall back to opening the crate
	 */
	public boolean installUpgrade(ItemStack stack) {
		if (!(stack.getItem() instanceof CrateUpgradeItem)) {
			return false;
		}

		// Only the slots this tier offers, even though the list is sized to the ceiling.
		for (int slot = 0; slot < upgradeSlots() && slot < upgrades.size(); slot++) {
			if (upgrades.get(slot).isEmpty()) {
				upgrades.set(slot, stack.copyWithCount(1));
				onUpgradeInstalled(upgrades.get(slot));
				setChanged();
				return true;
			}
		}

		return false;
	}

	/**
	 * A stack upgrade sorts the crate the moment it goes in — gathers the same item into as few
	 * slots as the capacity allows and orders by item — the one time a player has just asked for
	 * deeper slots and would expect the crate to use them. Runs with the new upgrade already
	 * installed, so the sort sees the capacity it grants. The slot the face shows stays as it is.
	 */
	private void onUpgradeInstalled(ItemStack upgrade) {
		if (upgrade.getItem() instanceof CrateUpgradeItem item
				&& UpgradeBehaviour.STACK.grantedBy(resolveUpgrade(item.upgradeKey()))) {
			contents.sort(this::isDisplaySlot);
		}
	}

	/**
	 * Sorts the crate — gathers the same item into as few slots as the capacity allows, then
	 * orders by item — on the player's request: a right-click on the installed stack upgrade.
	 * The slot the face shows stays as it is. Nothing without a stack upgrade: a plain crate is a
	 * chest and an inventory sorter handles it.
	 *
	 * @return whether the crate has a stack upgrade, and so did anything at all
	 */
	public boolean sortContents() {
		if (!stacks()) {
			return false;
		}

		contents.sort(this::isDisplaySlot);
		setChanged();
		return true;
	}

	/**
	 * Whether a sort must leave this slot alone. The lock no longer pins anything — its claim is on
	 * the whole crate, so any slot may hold what it claimed — but the slot chosen for the face is a
	 * promise about where that item is, and a sort that moved a different item in would break it.
	 */
	private boolean isDisplaySlot(int storageSlot) {
		return storageSlot == displaySlot;
	}

	/** Whether any installed upgrade destroys what this crate has no room for. */
	private boolean voidsOverflow() {
		return anyUpgrade(CrateUpgrade::voidsOverflow);
	}

	private boolean anyUpgrade(Predicate<CrateUpgrade> flag) {
		for (ItemStack installed : upgrades) {
			if (installed.getItem() instanceof CrateUpgradeItem item
					&& flag.test(resolveUpgrade(item.upgradeKey()))) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Whether this crate destroys {@code stack} rather than refusing it, once nothing has room.
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
			ItemStack held = contents.visible(slot);

			if (!held.isEmpty() && ItemStack.isSameItemSameComponents(held, stack)) {
				return true;
			}
		}

		return false;
	}

	private CrateUpgrade resolveUpgrade(ResourceKey<CrateUpgrade> key) {
		if (level == null) {
			return CrateUpgrade.FALLBACK;
		}

		return level.registryAccess()
				.lookup(LodestoreRegistries.CRATE_UPGRADE)
				.flatMap(registry -> registry.getOptional(key))
				.orElse(CrateUpgrade.FALLBACK);
	}

	/**
	 * Whether a slot would take this kind of item at all, room aside.
	 *
	 * <p>The lock and the nesting rule, in one place, asked by both doors into the contents: the
	 * storage's insert and the menu's slot. A rule enforced at only one of them is not a rule — the
	 * screen alone would be bypassed by piping items in, and the storage alone by a player's hand.
	 * Works on the client too, from the synced claim and the synced registry, so the screen can
	 * predict a refusal instead of flickering.
	 */
	public boolean canStore(int slot, ItemStack stack) {
		if (slot < 0 || slot >= storageSize()) {
			return false;
		}

		// Upgrades are hardware, not goods, and the storage never holds them. This is also what
		// keeps an inventory sorter from shuffling an installed upgrade into the grid: nothing in
		// the container region will take it, so it stays where it was.
		if (stack.getItem() instanceof CrateUpgradeItem) {
			return false;
		}

		if (!accepts(stack)) {
			return false;
		}

		CrateTier self = tier();

		if (!self.keepsContents()) {
			return true;
		}

		// Stops a crate that keeps its contents from holding another of equal or higher rank,
		// which would otherwise let storage compound without limit. An empty crate compounds
		// nothing — it is a block item like any other — so only one with contents is held to it.
		if (stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof CrateBlock other
				&& hasContents(stack)) {
			return self.canContain(resolve(other.tierKey()));
		}

		return true;
	}

	/** Whether a crate item carries anything: stacks in {@code minecraft:container}, or parked counts. */
	public static boolean hasContents(ItemStack stack) {
		ItemContainerContents contents = stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);

		if (contents.nonEmptyItems().iterator().hasNext()) {
			return true;
		}

		return !stack.getOrDefault(LodestoreComponents.CRATE_OVERFLOW, CrateOverflow.EMPTY).isEmpty();
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

	/**
	 * Picks what the face shows: the item in the slot chosen through the lock while there is any of
	 * it, or else the most numerous item in the crate, counting across every slot holding it.
	 */
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

		// A slot over its capacity — an upgrade taken out, a level lowered — spreads what is parked
		// into empty slots. A no-op while everything fits, so it never moves what a player placed.
		contents.spread();

		// The lock claims what the crate holds, or the first item into an empty one. Done before
		// the preview, which reads the display choice this keeps current.
		captureLockFilter();

		ItemStack previousItem = previewItem;
		long previousCount = previewCount;
		recomputePreview();

		List<ItemStack> currentFilters = lockedItems();
		boolean currentLocked = locks();
		ItemStack currentGhost = ghostItem();
		int currentMultiplier = stackMultiplier();
		int currentUnstackable = unstackableDepth();
		CrateOverflow currentOverflow = contents.overflow();

		// Only tell clients when the face or the screen would actually look different. The chosen
		// slot is in here because choosing one may change nothing else — without it the tint in the
		// screen would never update. The remainders are in here for the slot tooltip, and cost
		// bytes rather than packets: a parked count cannot change without the face's count
		// changing with it.
		if (!ItemStack.isSameItemSameComponents(previousItem, previewItem) || previousCount != previewCount
				|| !sameStacks(broadcastFilters, currentFilters) || broadcastLocked != currentLocked
				|| broadcastDisplaySlot != displaySlot || !ItemStack.isSameItemSameComponents(broadcastGhost, currentGhost)
				|| broadcastStackMultiplier != currentMultiplier || broadcastUnstackableDepth != currentUnstackable
				|| !broadcastOverflow.equals(currentOverflow)) {
			// Copied, because lockedItems() is a live view and would otherwise always match.
			broadcastFilters = List.copyOf(currentFilters);
			broadcastLocked = currentLocked;
			broadcastDisplaySlot = displaySlot;
			broadcastGhost = currentGhost;
			broadcastStackMultiplier = currentMultiplier;
			broadcastUnstackableDepth = currentUnstackable;
			broadcastOverflow = currentOverflow;
			level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
		}
	}

	// --- persistence -------------------------------------------------------------

	/**
	 * Writes what the lock has claimed and which slot the face shows.
	 *
	 * <p>Shared by the disk write and the client sync tag: the screen tints the chosen slot and the
	 * front face draws the claim, so both sides need the same state and there is no reason for them
	 * to disagree about its shape. The sync tag sends only a claim that applies, since the client
	 * cannot check for a lock itself.
	 */
	private void saveLockState(ValueOutput out, boolean resolved) {
		List<ItemStack> filter = resolved ? lockedItems() : lockFilter;

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

	/**
	 * Reads the claim and the display choice back. Runs after the contents have loaded, which the
	 * migration below needs.
	 *
	 * <p>A save from when the lock was aimed at slots has {@code lock_targets} and a claim per slot
	 * instead of {@code lock_filter}. The slots are dropped — the lock covers the whole crate now —
	 * but the claims are kept, so a locked slot that had been emptied goes on saying what belongs in
	 * the crate, and whatever else the crate holds joins them, which is what a lock going on today
	 * would have claimed. {@code void_targets}, from when voiding was aimed at slots, is ignored.
	 */
	private void loadLockState(ValueInput data) {
		lockFilter.clear();
		displaySlot = data.getIntOr("display_slot", -1);
		displayItem = data.read("display_item", ItemStack.CODEC).orElse(ItemStack.EMPTY);

		List<ItemStack> filter = data.read("lock_filter", ItemStack.CODEC.listOf()).orElse(List.of());
		List<ItemStack> legacyClaims = data.read("claim_items", ItemStack.CODEC.listOf()).orElse(List.of());

		if (!filter.isEmpty() || legacyClaims.isEmpty()) {
			lockFilter.addAll(filter);
			return;
		}

		for (ItemStack claimed : legacyClaims) {
			if (!claimed.isEmpty() && !containsKind(lockFilter, claimed)) {
				lockFilter.add(claimed.copyWithCount(1));
			}
		}

		for (ItemStack held : storedKinds()) {
			if (!containsKind(lockFilter, held)) {
				lockFilter.add(held);
			}
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

		// A locked crate draws its claim on the face even while empty, so this has to reach the
		// client separately — an empty crate has no preview to carry it.
		saveLockState(out, true);
		out.putBoolean("locked", locks());
		out.putInt("stack_multiplier", stackMultiplier());
		out.putInt("stack_unstackable", unstackableDepth());

		CrateOverflow overflow = contents.overflow();

		if (!overflow.isEmpty()) {
			out.store("synced_overflow", CrateOverflow.CODEC, overflow);
		}

		return out.buildResult();
	}

	/**
	 * Writes the contents and everything bolted to the crate.
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

		saveLockState(data, false);
	}

	@Override
	protected void loadAdditional(ValueInput data) {
		super.loadAdditional(data);
		contents.load(data);
		lockKey = LockCode.fromTag(data);
		name = parseCustomNameSafe(data, "CustomName");

		// Present only in the sync tag, which deliberately carries no items; a crate loaded
		// from disk has the real contents and recomputes from them instead.
		upgrades.clear();
		List<ItemStack> saved = data.read("upgrades", ItemStack.CODEC.listOf()).orElse(List.of());

		for (int slot = 0; slot < saved.size() && slot < upgrades.size(); slot++) {
			upgrades.set(slot, saved.get(slot));
		}

		loadLockState(data);
		broadcastFilters = List.copyOf(lockedItems());

		syncedLocked = data.getBooleanOr("locked", false);
		syncedStackMultiplier = data.getIntOr("stack_multiplier", 1);
		syncedUnstackableDepth = data.getIntOr("stack_unstackable", 1);
		data.read("synced_overflow", CrateOverflow.CODEC)
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
	 * What the menu needs that its slots cannot carry: whether a lock is installed, and which
	 * upgrades may be taken out. Two ints, synced by the menu's own data-slot path, so the client's
	 * slots predict a refused pickup instead of flickering.
	 */
	private final ContainerData menuData = new ContainerData() {
		@Override
		public int get(int index) {
			return switch (index) {
				case CrateMenu.DATA_LOCKED -> locks() ? 1 : 0;
				case CrateMenu.DATA_REMOVABLE -> removableUpgrades();
				default -> 0;
			};
		}

		@Override
		public void set(int index, int value) {
			// Server-owned; the client is told, never the other way round.
		}

		@Override
		public int getCount() {
			return CrateMenu.DATA_COUNT;
		}
	};

	@Override
	public @Nullable AbstractContainerMenu createMenu(int syncId, Inventory playerInventory, Player player) {
		if (!canOpen(player)) {
			BaseContainerBlockEntity.sendChestLockedNotifications(Vec3.atCenterOf(worldPosition), player, getDisplayName());
			return null;
		}

		return new CrateMenu(syncId, playerInventory, contents, this, upgradeContainer,
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
	public CrateMenuData getScreenOpeningData(ServerPlayer player) {
		return new CrateMenuData(rows(), upgradeSlots(), worldPosition);
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
	 * Reads a placed crate's contents back off the item.
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
				components.getOrDefault(LodestoreComponents.CRATE_OVERFLOW, CrateOverflow.EMPTY), true);
		setChanged();
	}

	/**
	 * What the dropped item carries for the tiers whose loot table copies it.
	 *
	 * <p>The visible stacks are all legal, so {@code minecraft:container} validates none of them
	 * away. The remainders go in their own component, and only when there are any, so an ordinary
	 * crate's drop stays identical to a fresh item and the two still stack.
	 */
	@Override
	protected void collectImplicitComponents(DataComponentMap.Builder builder) {
		super.collectImplicitComponents(builder);
		builder.set(DataComponents.CUSTOM_NAME, name);

		if (!lockKey.equals(LockCode.NO_LOCK)) {
			builder.set(DataComponents.LOCK, lockKey);
		}

		builder.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(contents.items()));

		CrateOverflow overflow = contents.overflow();

		if (!overflow.isEmpty()) {
			builder.set(LodestoreComponents.CRATE_OVERFLOW, overflow);
		}
	}

	@Override
	public void removeComponentsFromTag(ValueOutput output) {
		output.discard("CustomName");
		output.discard("lock");
		output.discard("Items");
		output.discard(CrateContents.OVERFLOW_KEY);
	}
}
