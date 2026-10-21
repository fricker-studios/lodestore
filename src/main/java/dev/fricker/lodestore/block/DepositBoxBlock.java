package dev.fricker.lodestore.block;

import java.util.EnumMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import dev.fricker.lodestore.registry.LodestoreBlockEntities;

/**
 * The deposit box: the one network block that ticks, because it is the one that moves anything.
 *
 * <p>It is shaped like a hopper and placed like one: a crate-like body with a spout, and the
 * spout points into whatever block it was placed against — down, up, or any of the four ways
 * sideways — so a box on top of a connection block points down into it and a box against a wall
 * of them points in. The body's back, opposite the spout, is its mouth: it draws from the
 * container behind it, as a hopper draws from the one above. A box pointing down has a chest on
 * top; a box pointing into the wall has its chest at its back.
 *
 * <p>Right-click opens it as a one-row chest. The contents spill on break through vanilla's own
 * path, since the block entity is a container. A comparator reads how full it is, which is how a
 * player learns something had no home without opening it.
 */
public class DepositBoxBlock extends Block implements EntityBlock {
	/** Where the spout points. */
	public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;

	/** The outline by facing, as the model draws it: body, funnel, neck. */
	private static final Map<Direction, VoxelShape> SHAPES = new EnumMap<>(Direction.class);
	/** The body alone, by facing: the part a click in the air beside the spout still lands on. */
	private static final Map<Direction, VoxelShape> BODIES = new EnumMap<>(Direction.class);

	static {
		for (Direction facing : Direction.values()) {
			VoxelShape body = box(facing, 0, 0, 8, 16, 16, 16);
			BODIES.put(facing, body);
			SHAPES.put(facing, Shapes.or(body, box(facing, 3, 3, 4, 13, 13, 8), box(facing, 6, 6, 0, 10, 10, 4)));
		}
	}

	public DepositBoxBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.DOWN));
	}

	/**
	 * A box given for a spout pointing north — the depth runs along z, the spout at z = 0 — turned
	 * to point {@code facing}. Each box is square across the spout's axis, so turning it is only a
	 * matter of which axis carries the depth and which way along it.
	 */
	private static VoxelShape box(Direction facing, double x1, double y1, double d1, double x2, double y2, double d2) {
		return switch (facing) {
			case NORTH -> Block.box(x1, y1, d1, x2, y2, d2);
			case SOUTH -> Block.box(x1, y1, 16 - d2, x2, y2, 16 - d1);
			case WEST -> Block.box(d1, y1, x1, d2, y2, x2);
			case EAST -> Block.box(16 - d2, y1, x1, 16 - d1, y2, x2);
			case DOWN -> Block.box(x1, d1, y1, x2, d2, y2);
			case UP -> Block.box(x1, 16 - d2, y1, x2, 16 - d1, y2);
		};
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	/** The spout points into the block the box was placed against, as a hopper's does. */
	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState().setValue(FACING, context.getClickedFace().getOpposite());
	}

	@Override
	protected BlockState rotate(BlockState state, Rotation rotation) {
		return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
	}

	@Override
	protected BlockState mirror(BlockState state, Mirror mirror) {
		return state.rotate(mirror.getRotation(state.getValue(FACING)));
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPES.get(state.getValue(FACING));
	}

	@Override
	protected VoxelShape getInteractionShape(BlockState state, BlockGetter level, BlockPos pos) {
		return BODIES.get(state.getValue(FACING));
	}

	@Override
	protected boolean isPathfindable(BlockState state, PathComputationType type) {
		return false;
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new DepositBoxBlockEntity(pos, state);
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state,
			BlockEntityType<T> type) {
		if (level.isClientSide() || type != LodestoreBlockEntities.DEPOSIT_BOX) {
			return null;
		}

		return (BlockEntityTicker<T>) (BlockEntityTicker<DepositBoxBlockEntity>) DepositBoxBlockEntity::serverTick;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
			Player player, BlockHitResult hit) {
		if (level.isClientSide()) {
			return InteractionResult.SUCCESS;
		}

		if (level.getBlockEntity(pos) instanceof DepositBoxBlockEntity box) {
			player.openMenu(box);
			return InteractionResult.CONSUME;
		}

		return InteractionResult.PASS;
	}

	@Override
	protected boolean hasAnalogOutputSignal(BlockState state) {
		return true;
	}

	@Override
	protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos, Direction direction) {
		return AbstractContainerMenu.getRedstoneSignalFromBlockEntity(level.getBlockEntity(pos));
	}
}
