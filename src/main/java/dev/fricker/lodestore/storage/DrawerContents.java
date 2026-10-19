package dev.fricker.lodestore.storage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * What a drawer holds: Lodestore's own storage layer.
 *
 * <p>Each storage slot is a <em>legal stack plus a parked remainder</em>. The stack — {@code visible}
 * — is never larger than the item's own maximum, and it is the only {@code ItemStack} object that
 * exists for the slot: the menu's slots are it, the save file writes it, and the
 * {@code minecraft:container} component carries it. The remainder is how many more of the same item
 * are parked behind it, as a plain count. Together they are the slot's total.
 *
 * <p>The invariants, which every mutation here restores before returning:
 * <ul>
 *   <li>a remainder above zero means the visible stack is full;</li>
 *   <li>an empty visible stack means the remainder is zero;</li>
 *   <li>the total never exceeds the slot's capacity, except after the capacity has been lowered
 *       under it — an upgrade removed, a slot un-targeted — in which case the slot reports no room
 *       and drains normally, because nothing in here is ever allowed to destroy a count.</li>
 * </ul>
 *
 * <p>Why this shape and not a variant with a count: an over-sized stack is never constructed
 * anywhere. {@code ItemStack}'s codec caps a count at 99, the container component validates against
 * the item's maximum, and one left click in a menu takes a slot's whole stack onto the cursor with
 * no ceiling at all — every one of those is a way to lose items, and none of them can be reached
 * from a model that only ever hands out legal stacks. It also keeps the save format today's with a
 * side table, so a world written before this layer existed loads with no conversion.
 *
 * <p>This class is the {@link Container} face, for the menu. {@link #storage()} is the transfer-API
 * face over the same two arrays, so the two cannot disagree about how full a slot is. Neither is
 * the block entity: a hopper finds its target by asking whether the block entity is a
 * {@code Container}, and the whole point is that it is not. The block entity owns the rules —
 * capacity, locks, nesting, voiding — and this class only asks.
 */
public final class DrawerContents implements Container {
	/** The save key for the remainders. The same codec carries them on the dropped item. */
	public static final String OVERFLOW_KEY = "overflow";

	/** What the drawer decides. The contents never work any of this out for themselves. */
	public interface Rules {
		/**
		 * How many of {@code stack} the slot may hold in total: the item's own stack size times the
		 * slot's depth. For an empty probe, a nominal stack times the depth.
		 */
		int capacity(int slot, ItemStack stack);

		/** Whether the slot would take this kind of item at all, room aside: lock claims, nesting. */
		boolean canStore(int slot, ItemStack stack);

		/** Whether the drawer destroys {@code stack} rather than refusing it, once nothing has room. */
		boolean voids(ItemStack stack);

		/** Something changed. Called outside any transaction, once per change or commit. */
		void onChanged();

		boolean stillValid(Player player);
	}

	private final NonNullList<ItemStack> visible;
	private final long[] remainder;
	private final Rules rules;
	private final DrawerStorage storage;

	public DrawerContents(int size, Rules rules) {
		this.visible = NonNullList.withSize(size, ItemStack.EMPTY);
		this.remainder = new long[size];
		this.rules = rules;
		this.storage = new DrawerStorage(this);
	}

	/** The transfer-API face: what hoppers, droppers, pipes and other mods reach. */
	public DrawerStorage storage() {
		return storage;
	}

	Rules rules() {
		return rules;
	}

	public int size() {
		return visible.size();
	}

	// --- reading -----------------------------------------------------------

	/** The legal stack in the slot — what a menu shows. Live, not a copy. */
	public ItemStack visible(int slot) {
		return visible.get(slot);
	}

	/** How many more of the slot's item are parked behind the visible stack. */
	public long remainder(int slot) {
		return remainder[slot];
	}

	/** Everything in the slot. */
	public long total(int slot) {
		return visible.get(slot).getCount() + remainder[slot];
	}

	/** The slot's capacity for what it holds, or for a nominal stack if empty. */
	public int capacity(int slot) {
		return rules.capacity(slot, visible.get(slot));
	}

	/** Room for {@code stack} in the slot: none if it holds something else, or refuses it. */
	public long room(int slot, ItemStack stack) {
		ItemStack kind = kindOf(slot);

		if (!kind.isEmpty() && !ItemStack.isSameItemSameComponents(kind, stack)) {
			return 0;
		}

		if (!rules.canStore(slot, stack)) {
			return 0;
		}

		return Math.max(0, (long) rules.capacity(slot, stack) - total(slot));
	}

	/** The visible stacks, for the container component and the preview. Live. */
	public NonNullList<ItemStack> items() {
		return visible;
	}

	/** The remainders as the sparse list the save file and the dropped item carry. */
	public DrawerOverflow overflow() {
		return DrawerOverflow.of(remainder);
	}

	// --- mutation primitives -------------------------------------------------
	//
	// Used by the storage views inside transactions, so none of these call onChanged: the
	// transaction does that once, on commit.

	/**
	 * Adds {@code amount} of {@code like} to the slot. The caller has checked {@link #room}.
	 *
	 * @return what was added, which is {@code amount}
	 */
	long add(int slot, ItemStack like, long amount) {
		long newTotal = total(slot) + amount;

		if (kindOf(slot).isEmpty()) {
			int show = (int) Math.min(newTotal, like.getMaxStackSize());
			visible.set(slot, like.copyWithCount(show));
			remainder[slot] = newTotal - show;
		} else {
			settle(slot, newTotal);
		}

		return amount;
	}

	/** Takes up to {@code amount} from the slot's total, remainder included, and returns how many. */
	long take(int slot, long amount) {
		if (kindOf(slot).isEmpty()) {
			return 0;
		}

		long taken = Math.min(amount, total(slot));
		settle(slot, total(slot) - taken);
		return taken;
	}

	/** Puts a transaction snapshot back. */
	void restore(int slot, ItemStack snapshotVisible, long snapshotRemainder) {
		visible.set(slot, snapshotVisible);
		remainder[slot] = snapshotRemainder;
	}

	/**
	 * The item a slot's stack names, as a one-count copy — even when the stack has been drained to
	 * zero in place.
	 *
	 * <p>That last case is the whole reason this exists. Vanilla's menu code empties a slot by
	 * setting its stack's count to zero and only then clearing the slot, and a stack whose count is
	 * zero answers every public question — its item, its components, its stack size — as if it were
	 * air. The item is still in the object; the accessors hide it behind {@code isEmpty()}. The only
	 * honest way to read it back is to give the stack a count for the duration of the question,
	 * which is what this does, on a single-threaded server, to an object nothing else is reading.
	 */
	private ItemStack kindOf(int slot) {
		ItemStack held = visible.get(slot);

		if (held == ItemStack.EMPTY) {
			return ItemStack.EMPTY;
		}

		if (held.getCount() > 0) {
			return held.isEmpty() ? ItemStack.EMPTY : held.copyWithCount(1);
		}

		held.setCount(1);
		ItemStack kind = held.isEmpty() ? ItemStack.EMPTY : held.copyWithCount(1);
		held.setCount(0);
		return kind;
	}

	/**
	 * Re-establishes the invariants for a slot whose total is {@code newTotal}, in place.
	 *
	 * <p>In place matters: vanilla's menu code holds the slot's stack across a call and mutates it
	 * directly, so the object has to stay the one it was given. A stack drained to zero in place is
	 * given its count back first, because until it has one it reports a stack size of one.
	 */
	private void settle(int slot, long newTotal) {
		ItemStack held = visible.get(slot);

		if (newTotal <= 0) {
			visible.set(slot, ItemStack.EMPTY);
			remainder[slot] = 0;
			return;
		}

		if (held == ItemStack.EMPTY) {
			throw new IllegalStateException("drawer slot " + slot + " has " + newTotal
					+ " items to settle and no stack to attach them to");
		}

		held.setCount(1);

		if (held.isEmpty()) {
			// A genuine stack of air, which nothing in the game hands out; treated as empty.
			visible.set(slot, ItemStack.EMPTY);
			remainder[slot] = 0;
			return;
		}

		int show = (int) Math.min(newTotal, held.getMaxStackSize());
		held.setCount(show);
		remainder[slot] = newTotal - show;
	}

	/**
	 * Restores the invariants after the menu has written to a visible stack.
	 *
	 * <p>Two things can have happened: a stack was grown past its legal size by a click or a
	 * shift-click, in which case the excess is parked; or one was drained to zero in place, in
	 * which case it refills from the remainder. The one state that cannot be repaired is a slot
	 * that has a remainder and no item to attach it to, and that is a bug in a caller rather than a
	 * state to paper over — so it throws rather than quietly losing the count.
	 */
	private void rebalance(int slot) {
		if (kindOf(slot).isEmpty()) {
			if (remainder[slot] > 0) {
				throw new IllegalStateException("drawer slot " + slot + " has " + remainder[slot]
						+ " parked items and no stack to attach them to");
			}

			if (visible.get(slot) != ItemStack.EMPTY) {
				visible.set(slot, ItemStack.EMPTY);
			}

			return;
		}

		settle(slot, total(slot));
	}

	// --- Container, for the menu ----------------------------------------------

	@Override
	public int getContainerSize() {
		return visible.size();
	}

	@Override
	public boolean isEmpty() {
		for (ItemStack stack : visible) {
			if (!stack.isEmpty()) {
				return false;
			}
		}

		return true;
	}

	@Override
	public ItemStack getItem(int slot) {
		return visible.get(slot);
	}

	/**
	 * Takes up to {@code count} from the visible stack and refills it from the remainder before
	 * returning, so a caller that empties the stack and then looks at the slot sees the next stack
	 * rather than an empty one. That is what keeps vanilla's {@code Slot#tryRemove} from clearing
	 * a slot that still has items parked behind it.
	 */
	@Override
	public ItemStack removeItem(int slot, int count) {
		ItemStack held = visible.get(slot);

		if (held.isEmpty() || count <= 0) {
			return ItemStack.EMPTY;
		}

		int taken = Math.min(count, held.getCount());
		ItemStack result = held.copyWithCount(taken);
		settle(slot, total(slot) - taken);
		rules.onChanged();
		return result;
	}

	@Override
	public ItemStack removeItemNoUpdate(int slot) {
		ItemStack held = visible.get(slot);

		if (held.isEmpty()) {
			return ItemStack.EMPTY;
		}

		ItemStack result = held.copy();
		settle(slot, remainder[slot]);
		return result;
	}

	/**
	 * Writes the visible stack.
	 *
	 * <p>An empty write over a parked remainder is the vanilla menu's way of saying "I drained this
	 * stack in place" — {@code moveItemStackTo} sets the count to zero and then clears the slot —
	 * so it refills from the remainder instead of emptying the slot. A write of a <em>different</em>
	 * item over a remainder has no honest meaning, since the remainder names the old item, and is
	 * refused loudly; the menu's slot never makes that call, because {@code DrawerSlot#mayPlace}
	 * refuses the swap that would lead to it.
	 */
	@Override
	public void setItem(int slot, ItemStack stack) {
		ItemStack held = visible.get(slot);

		if (stack.isEmpty()) {
			if (remainder[slot] > 0) {
				// Throws if there is nothing to refill from, which is the orphan case.
				rebalance(slot);
			} else {
				visible.set(slot, ItemStack.EMPTY);
			}

			return;
		}

		if (remainder[slot] > 0) {
			ItemStack kind = kindOf(slot);

			if (kind.isEmpty() || !ItemStack.isSameItemSameComponents(kind, stack)) {
				throw new IllegalStateException("drawer slot " + slot + " holds " + remainder[slot] + " parked "
						+ kind.getItem() + " and cannot be overwritten with " + stack.getItem());
			}
		}

		if (held != stack) {
			visible.set(slot, stack);
		}

		rebalance(slot);
	}

	/** Never raises a stack's own ceiling: the window shows legal stacks, and only legal stacks. */
	@Override
	public int getMaxStackSize(ItemStack stack) {
		return stack.getMaxStackSize();
	}

	/** Room aside — the rules only. Whether the slot has space is the slot's and the storage's job. */
	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		return rules.canStore(slot, stack);
	}

	@Override
	public void setChanged() {
		for (int slot = 0; slot < visible.size(); slot++) {
			rebalance(slot);
		}

		rules.onChanged();
	}

	@Override
	public boolean stillValid(Player player) {
		return rules.stillValid(player);
	}

	@Override
	public void clearContent() {
		visible.clear();
		Arrays.fill(remainder, 0);
	}

	// --- persistence ----------------------------------------------------------

	/** Today's {@code Items} list, plus the remainders only when there are any. */
	public void save(ValueOutput out) {
		ContainerHelper.saveAllItems(out, visible);
		DrawerOverflow overflow = overflow();

		if (!overflow.isEmpty()) {
			out.store(OVERFLOW_KEY, DrawerOverflow.CODEC, overflow);
		}
	}

	/** A tag written before the remainders existed has none, and loads exactly as it always did. */
	public void load(ValueInput in) {
		clearContent();
		ContainerHelper.loadAllItems(in, visible);
		applyOverflow(in.read(OVERFLOW_KEY, DrawerOverflow.CODEC).orElse(DrawerOverflow.EMPTY), true);
	}

	/**
	 * Attaches parked counts to the visible stacks.
	 *
	 * @param requireStacks whether a count whose slot holds nothing is dropped. True everywhere the
	 *                      stacks are present — disk, the dropped item — because a count with no
	 *                      item is not a quantity of anything. False only for the client's copy,
	 *                      which is deliberately never sent the stacks and needs the counts alone
	 *                      for the screen.
	 */
	public void applyOverflow(DrawerOverflow overflow, boolean requireStacks) {
		Arrays.fill(remainder, 0);

		for (DrawerOverflow.Entry entry : overflow.entries()) {
			int slot = entry.slot();

			if (slot < 0 || slot >= visible.size() || entry.count() <= 0) {
				continue;
			}

			ItemStack held = visible.get(slot);

			if (held.isEmpty()) {
				if (!requireStacks) {
					remainder[slot] = entry.count();
				}

				continue;
			}

			// Settling restores the invariant if the stack came back short of full.
			remainder[slot] = entry.count();
			settle(slot, held.getCount() + entry.count());
		}
	}

	/**
	 * Empties the drawer into a list of legal stacks, remainders split into as many stacks as they
	 * need. For spilling on break — destructive, so nothing can be dropped twice.
	 */
	public List<ItemStack> drain() {
		List<ItemStack> out = new ArrayList<>();

		for (int slot = 0; slot < visible.size(); slot++) {
			ItemStack kind = kindOf(slot);

			if (kind.isEmpty()) {
				continue;
			}

			long left = total(slot);
			int max = kind.getMaxStackSize();

			while (left > 0) {
				int count = (int) Math.min(left, max);
				out.add(kind.copyWithCount(count));
				left -= count;
			}
		}

		clearContent();
		return out;
	}

	/** Fullness over the storage, each slot against its own capacity, for the comparator. */
	public float fill() {
		float fill = 0.0F;

		for (int slot = 0; slot < visible.size(); slot++) {
			long total = total(slot);

			if (total > 0) {
				fill += Math.min(1.0F, (float) total / Math.max(1, capacity(slot)));
			}
		}

		return fill / visible.size();
	}
}
