package dev.fricker.lodestore.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import dev.fricker.lodestore.block.CrateBlockEntity;

/**
 * Where a ticking device's crates were when it last walked the network, and how it got to them.
 *
 * <p>A device that moves one item a tick would otherwise walk the network every tick. The walk is
 * redone every twenty ticks; in between, the positions found last time are reused, and a crate
 * that has gone in the meantime is simply not a crate when looked up. The paths come with the
 * walk for free, which is what lets the indicators along a line be lit without walking again.
 */
public final class NetworkCrateCache {
	private static final int RESCAN_TICKS = 20;

	private StorageNetwork.Walk walk = StorageNetwork.Walk.EMPTY;
	private List<BlockPos> crates = List.of();
	private int ticksSinceScan = RESCAN_TICKS;

	/** The crates on the network a device at {@code origin} belongs to, nearest first, as of the last walk. */
	public List<BlockPos> get(Level level, BlockPos origin) {
		if (++ticksSinceScan >= RESCAN_TICKS) {
			walk = StorageNetwork.walk(level, origin);
			List<BlockPos> positions = new ArrayList<>(walk.crates().size());

			for (CrateBlockEntity crate : walk.crates()) {
				positions.add(crate.getBlockPos());
			}

			crates = List.copyOf(positions);
			ticksSinceScan = 0;
		}

		return crates;
	}

	/** The connection blocks between the device and the crate at {@code crate}, as of the last walk. */
	public List<BlockPos> pathTo(BlockPos crate) {
		return walk.pathTo(crate);
	}
}
