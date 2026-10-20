package dev.fricker.lodestore.network;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Where a ticking device's crates were when it last walked the network.
 *
 * <p>A device that moves one item a tick would otherwise walk the network every tick. The walk is
 * redone every twenty ticks; in between, the positions found last time are reused, and a crate
 * that has gone in the meantime is simply not a crate when looked up.
 */
public final class NetworkCrateCache {
	private static final int RESCAN_TICKS = 20;

	private List<BlockPos> crates = List.of();
	private int ticksSinceScan = RESCAN_TICKS;

	/** The crates on the network a device at {@code origin} belongs to, nearest first, as of the last walk. */
	public List<BlockPos> get(Level level, BlockPos origin) {
		if (++ticksSinceScan >= RESCAN_TICKS) {
			crates = StorageNetwork.cratePositions(level, origin);
			ticksSinceScan = 0;
		}

		return crates;
	}
}
