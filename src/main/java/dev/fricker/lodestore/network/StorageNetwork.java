package dev.fricker.lodestore.network;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;

import dev.fricker.lodestore.block.ConnectionBlock;
import dev.fricker.lodestore.block.CrateBlockEntity;

/**
 * What a network device can reach: the crates on the far side of its connection blocks.
 *
 * <p>A network has no object of its own. It is found afresh from whichever device asks, by walking
 * the connection blocks touching it, and the crates touching any of those — or the device itself —
 * are its members. Only connection blocks conduct: a deposit box or an interface box is an endpoint,
 * so two devices side by side are not thereby on one network, and a crate touching a device is on
 * that device's network and nothing more. Walking from the device each time, rather than keeping a
 * graph up to date as blocks are placed and broken, costs a few hundred block lookups a second per
 * ticking device and cannot go stale.
 *
 * <p>The walk is capped, so a device buried in a wall of connection blocks cannot stall the tick,
 * and stays inside loaded chunks, so a network straddling a chunk border is simply shorter until
 * the far side loads.
 */
public final class StorageNetwork {
	/** The most connection blocks one walk will cross. Past this, the rest of the network is unreachable. */
	public static final int MAX_CONNECTIONS = 4096;

	private StorageNetwork() {
	}

	/**
	 * The crates on the network a device at {@code origin} belongs to, nearest first.
	 *
	 * <p>Nearest first is a property of the walk, which is breadth-first: a crate touching the device
	 * comes before one three connection blocks away. The deposit box fills in this order, so where an
	 * item has two homes, the one closer to the box gets it.
	 */
	public static List<CrateBlockEntity> crates(Level level, BlockPos origin) {
		List<CrateBlockEntity> crates = new ArrayList<>();
		LongSet seenCrates = new LongOpenHashSet();
		LongSet seenConnections = new LongOpenHashSet();
		Queue<BlockPos> frontier = new ArrayDeque<>();

		seenConnections.add(origin.asLong());
		frontier.add(origin);

		while (!frontier.isEmpty()) {
			BlockPos at = frontier.remove();

			for (Direction direction : Direction.values()) {
				BlockPos next = at.relative(direction);

				if (!level.isLoaded(next)) {
					continue;
				}

				if (level.getBlockState(next).getBlock() instanceof ConnectionBlock) {
					if (seenConnections.size() <= MAX_CONNECTIONS && seenConnections.add(next.asLong())) {
						frontier.add(next);
					}

					continue;
				}

				if (seenCrates.add(next.asLong()) && level.getBlockEntity(next) instanceof CrateBlockEntity crate
						&& !crate.isRemoved()) {
					crates.add(crate);
				}
			}
		}

		return crates;
	}

	/** Where the crates on the network a device at {@code origin} belongs to are, for a cache. */
	public static List<BlockPos> cratePositions(Level level, BlockPos origin) {
		List<BlockPos> positions = new ArrayList<>();

		for (CrateBlockEntity crate : crates(level, origin)) {
			positions.add(crate.getBlockPos());
		}

		return positions;
	}

	/**
	 * Whether {@code crate} is a home for {@code stack}: somewhere it already belongs.
	 *
	 * <p>A crate that holds the item, in any slot, or whose lock claims it. Not a crate with empty
	 * slots and no opinion: a deposit box filling those would scatter every new kind of item into
	 * whichever crate the walk found first, which is a chest, not a sorting system. An item with no
	 * home stays in the deposit box, where the player can see it and decide.
	 *
	 * <p>"The item" means the item, as the lock's claim does: a crate holding a bow is home to a
	 * damaged bow and an enchanted one. The crate's own slots still keep stacks that differ in
	 * their components apart, so the bows land in slots of their own once they arrive.
	 */
	public static boolean isHome(CrateBlockEntity crate, ItemStack stack) {
		for (ItemStack claimed : crate.lockedItems()) {
			if (ItemStack.isSameItem(claimed, stack)) {
				return true;
			}
		}

		for (int slot = 0; slot < crate.storageSize(); slot++) {
			ItemStack held = crate.contents().visible(slot);

			if (!held.isEmpty() && ItemStack.isSameItem(held, stack)) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Everything the network a device at {@code origin} belongs to holds, one entry per kind of
	 * item, most numerous first.
	 *
	 * <p>Counts are slot totals, so what a stack upgrade parks behind a full stack is in here; it is
	 * the one view a player has of it short of opening every crate.
	 */
	public static NetworkView view(Level level, BlockPos origin) {
		List<CrateBlockEntity> crates = crates(level, origin);
		Map<ItemVariant, Long> totals = new HashMap<>();

		for (CrateBlockEntity crate : crates) {
			for (int slot = 0; slot < crate.storageSize(); slot++) {
				ItemStack held = crate.contents().visible(slot);

				if (!held.isEmpty()) {
					totals.merge(ItemVariant.of(held), crate.total(slot), Long::sum);
				}
			}
		}

		List<NetworkEntry> entries = new ArrayList<>(totals.size());
		totals.forEach((kind, count) -> entries.add(new NetworkEntry(kind, count)));
		entries.sort(Comparator.comparingLong(NetworkEntry::count).reversed()
				.thenComparing(entry -> BuiltInRegistries.ITEM.getKey(entry.kind().getItem()))
				.thenComparing(entry -> entry.kind().getComponents().toString()));
		return new NetworkView(crates.size(), List.copyOf(entries));
	}
}
