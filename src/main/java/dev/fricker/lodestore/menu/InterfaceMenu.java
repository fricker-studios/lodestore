package dev.fricker.lodestore.menu;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;

import dev.fricker.lodestore.block.InterfaceBoxBlockEntity;
import dev.fricker.lodestore.network.NetworkEntry;
import dev.fricker.lodestore.network.NetworkView;
import dev.fricker.lodestore.network.NetworkViewPayload;
import dev.fricker.lodestore.network.PullMode;
import dev.fricker.lodestore.network.StorageNetwork;

/**
 * The interface box's menu: the player's inventory, and a view of the network that is not slots.
 *
 * <p>What the network holds is one entry per kind of item with a count that can be anything, and
 * a {@code Slot} can show one legal stack. So the view is not slots at all: the server walks the
 * network every few ticks while the menu is open and sends the whole view when it has changed, and
 * the screen draws it as a grid of its own. The player's inventory is real slots, where it always
 * is, so the screen reads as a container and the inventory can be managed from it.
 *
 * <p>Sent whole rather than as deltas, because a network's view is small — one entry per kind, a
 * few dozen bytes each — and whole means a dropped or reordered packet cannot leave the screen
 * showing a count that was never true.
 *
 * <p>The view is also the way out. A click on a cell asks the server, by kind, for a stack, half
 * a stack or a stack into the inventory — the three gestures a vanilla slot has — and the server
 * takes it from the crates nearest the box and hands it over, then sends the view again at once.
 * Nothing goes in this way: the deposit box is the network's mouth, and a click with something
 * carried does nothing.
 */
public class InterfaceMenu extends AbstractContainerMenu {
	/** Columns and rows of the view the screen draws; the vanilla double chest's shape. */
	public static final int COLUMNS = 9;
	public static final int ROWS = 6;

	/** How often the server walks the network while the menu is open. */
	private static final int REFRESH_TICKS = 10;

	private final @Nullable InterfaceBoxBlockEntity box;
	private final @Nullable ServerPlayer viewer;
	private final BlockPos pos;

	/** What this side knows the network holds: sent by the server, received by the client. */
	private NetworkView view = NetworkView.EMPTY;
	/** The entries as stacks, for the screen to draw, rebuilt only when the view changes. */
	private List<ItemStack> stacks = List.of();
	private int ticks;
	private boolean sent;

	/** Client side: the box itself is never needed, only where it is. */
	public InterfaceMenu(int containerId, Inventory playerInventory, InterfaceMenuData data) {
		this(containerId, playerInventory, null, data.pos());
	}

	public InterfaceMenu(int containerId, Inventory playerInventory, @Nullable InterfaceBoxBlockEntity box, BlockPos pos) {
		super(LodestoreMenus.INTERFACE, containerId);
		this.box = box;
		this.viewer = playerInventory.player instanceof ServerPlayer player ? player : null;
		this.pos = pos;

		// The double chest's own arithmetic, so the inventory sits where a six-row screen puts it.
		addStandardInventorySlots(playerInventory, 8, 103 + (ROWS - 4) * 18);
	}

	public BlockPos pos() {
		return pos;
	}

	public NetworkView view() {
		return view;
	}

	/** The view's items as stacks, in the view's order, one per entry. */
	public List<ItemStack> stacks() {
		return stacks;
	}

	/** The client applying what the server sent. */
	public void setView(NetworkView view) {
		this.view = view;
		List<ItemStack> built = new ArrayList<>(view.entries().size());

		for (NetworkEntry entry : view.entries()) {
			built.add(entry.kind().toStack());
		}

		this.stacks = List.copyOf(built);
	}

	/**
	 * Takes {@code kind} out of the network for {@code player}, as a click on its cell asks.
	 *
	 * <p>Onto the cursor, a stack or half of one, if the cursor is empty or already holds the same
	 * kind with room; or straight into the inventory, as far as it has room, with whatever does not
	 * fit put back. All of it in one transaction, so a crate is never left short of what the player
	 * did not receive. The view goes out again on the next tick rather than the next refresh, so
	 * the cell's count follows the click.
	 *
	 * @return whether anything moved
	 */
	public boolean pull(ServerPlayer player, ItemVariant kind, PullMode mode) {
		if (box == null || box.getLevel() == null || kind.isBlank()) {
			return false;
		}

		ItemStack probe = kind.toStack();
		int max = probe.getMaxStackSize();
		long want = mode == PullMode.HALF ? Math.max(1, max / 2) : max;
		ItemStack carried = getCarried();

		if (mode.toCursor()) {
			if (!carried.isEmpty()) {
				if (!ItemStack.isSameItemSameComponents(carried, probe)) {
					return false;
				}

				want = Math.min(want, max - carried.getCount());
			}
		} else {
			// Measured first rather than discovered by trying: Inventory#add throws away what a
			// creative player has no room for, and nothing taken out of a crate may be thrown away.
			want = Math.min(want, roomFor(player.getInventory(), probe));
		}

		if (want <= 0) {
			return false;
		}

		try (Transaction transaction = Transaction.openOuter()) {
			long taken = StorageNetwork.extract(box.getLevel(), pos, kind, want, transaction);

			if (taken <= 0) {
				transaction.abort();
				return false;
			}

			ItemStack stack = kind.toStack((int) taken);

			if (mode.toCursor()) {
				setCarried(carried.isEmpty() ? stack : carried.copyWithCount(carried.getCount() + stack.getCount()));
			} else {
				player.getInventory().add(stack);

				// Only what was measured was taken, so this is empty; if it is not, nothing is
				// committed rather than anything lost.
				if (!stack.isEmpty()) {
					transaction.abort();
					return false;
				}
			}

			transaction.commit();
		}

		// Resend at once rather than at the next refresh.
		ticks = 0;
		sent = false;
		return true;
	}

	/** How many of {@code probe} the inventory's own slots would take: the gaps in stacks of it, and the empties. */
	private static long roomFor(net.minecraft.world.entity.player.Inventory inventory, ItemStack probe) {
		long room = 0;

		for (ItemStack held : inventory.getNonEquipmentItems()) {
			if (held.isEmpty()) {
				room += probe.getMaxStackSize();
			} else if (ItemStack.isSameItemSameComponents(held, probe)) {
				room += Math.max(0, held.getMaxStackSize() - held.getCount());
			}
		}

		return room;
	}

	/** Walks the network every few ticks and sends the view to the viewer when it has changed. */
	@Override
	public void broadcastChanges() {
		super.broadcastChanges();

		if (box == null || viewer == null) {
			return;
		}

		if (ticks++ % REFRESH_TICKS != 0) {
			return;
		}

		NetworkView current = box.view();

		if (sent && current.equals(view)) {
			return;
		}

		view = current;
		sent = true;
		ServerPlayNetworking.send(viewer, new NetworkViewPayload(containerId, current));
	}

	@Override
	public boolean stillValid(Player player) {
		return box == null || Container.stillValidBlockEntity(box, player);
	}

	/** Nothing to move anything into: the view is not slots. */
	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		return ItemStack.EMPTY;
	}
}
