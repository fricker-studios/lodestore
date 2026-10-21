package dev.fricker.lodestore.network;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import dev.fricker.lodestore.block.ConnectionBlock;

/**
 * Lights the connection blocks an item passes through, and keeps them lit while items keep
 * passing.
 *
 * <p>A connection block has no block entity, so the moment it last saw an item is kept here, per
 * level and per position, rather than on the block. Lighting is a block state change and costs a
 * block update, so it happens once when the flow starts and once when it stops: a pulse on a
 * block already lit only refreshes its moment, and the block's own scheduled tick asks
 * {@link #stillFlowing} whether to run on or go dark. One item a tick down a line of twenty
 * connectors is therefore two block updates per connector per burst, not forty a second.
 */
public final class ConnectionFlow {
	/** How long the light stays on after the last item passed, in ticks: half a second. */
	public static final int GLOW_TICKS = 10;

	/** When each lit connector last saw an item, by position, for each level. */
	private static final Map<ServerLevel, Long2LongMap> LAST_FLOW = new WeakHashMap<>();

	private ConnectionFlow() {
	}

	/** Lights every connection block on {@code path}, or keeps it lit. */
	public static void pulse(ServerLevel level, List<BlockPos> path) {
		for (BlockPos pos : path) {
			pulse(level, pos);
		}
	}

	/** Lights the connection block at {@code pos}, or keeps it lit. Anything else is left alone. */
	public static void pulse(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);

		if (!(state.getBlock() instanceof ConnectionBlock)) {
			return;
		}

		lastFlow(level).put(pos.asLong(), level.getGameTime());

		if (!state.getValue(ConnectionBlock.LIT)) {
			level.setBlock(pos, state.setValue(ConnectionBlock.LIT, true), Block.UPDATE_CLIENTS);
			level.scheduleTick(pos, state.getBlock(), GLOW_TICKS);
		}
	}

	/** Whether an item passed {@code pos} within the last {@link #GLOW_TICKS}. */
	public static boolean stillFlowing(ServerLevel level, BlockPos pos) {
		long last = lastFlow(level).getOrDefault(pos.asLong(), Long.MIN_VALUE);
		return last != Long.MIN_VALUE && level.getGameTime() - last < GLOW_TICKS;
	}

	private static synchronized Long2LongMap lastFlow(ServerLevel level) {
		return LAST_FLOW.computeIfAbsent(level, key -> new Long2LongOpenHashMap());
	}
}
