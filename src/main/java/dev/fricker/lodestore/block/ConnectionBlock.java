package dev.fricker.lodestore.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;

import dev.fricker.lodestore.network.ConnectionFlow;

/**
 * A connection block: what a storage network is made of.
 *
 * <p>It has no block entity and no behaviour of its own; it exists so that {@code StorageNetwork}
 * can tell it from everything else when it walks from a device, and so that two devices side by
 * side do not quietly become one network. Everything the network does, the devices on it do.
 *
 * <p>What it has is a face. Its front and back carry a redstone indicator, facing toward and
 * away from the player who placed it as an observer's do, and the indicator lights while items
 * are passing through this block on their way between a device and a crate. {@link ConnectionFlow}
 * lights it and keeps it lit; the scheduled tick here is what puts it out once the flow stops.
 * The light is a light only: it powers nothing, so a run of them never ticks redstone it happens
 * to touch.
 */
public class ConnectionBlock extends Block {
	public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.AXIS;
	public static final BooleanProperty LIT = BlockStateProperties.LIT;

	public ConnectionBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(AXIS, Direction.Axis.Z).setValue(LIT, false));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(AXIS, LIT);
	}

	/** Front and back toward and away from the player, as an observer's are; never up and down. */
	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState().setValue(AXIS, context.getHorizontalDirection().getAxis());
	}

	@Override
	protected BlockState rotate(BlockState state, Rotation rotation) {
		return RotatedPillarBlock.rotatePillar(state, rotation);
	}

	/**
	 * The glow's timer. Scheduled when the block lights; while items were still passing within
	 * the last few ticks it runs on, and once they have stopped it puts the light out.
	 */
	@Override
	protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		if (!state.getValue(LIT)) {
			return;
		}

		if (ConnectionFlow.stillFlowing(level, pos)) {
			level.scheduleTick(pos, this, ConnectionFlow.GLOW_TICKS);
			return;
		}

		level.setBlock(pos, state.setValue(LIT, false), Block.UPDATE_CLIENTS);
	}
}
