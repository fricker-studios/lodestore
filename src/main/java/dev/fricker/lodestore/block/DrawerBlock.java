package dev.fricker.lodestore.block;


import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

import dev.fricker.lodestore.api.drawer.DrawerTier;

/**
 * A drawer block. Structure (how many rows it opens) is fixed per block; behaviour (whether it
 * keeps its contents, and what it may contain) is datapack-driven via {@link DrawerTier}.
 */
public class DrawerBlock extends Block implements EntityBlock {
	public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

	private final int rows;
	private final ResourceKey<DrawerTier> tierKey;

	public DrawerBlock(Properties properties, int rows, ResourceKey<DrawerTier> tierKey) {
		super(properties);
		this.rows = rows;
		this.tierKey = tierKey;
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
	}

	public int rows() {
		return rows;
	}

	public ResourceKey<DrawerTier> tierKey() {
		return tierKey;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new DrawerBlockEntity(pos, state);
	}

	/**
	 * Opens the drawer.
	 *
	 * <p>{@code useItemOn} is deliberately not overridden, which gives the vanilla chest contract:
	 * right-clicking opens the drawer whatever is in hand, and sneaking places the held block
	 * instead. The previous behaviour — depositing whatever you were holding — made it easy to
	 * assign a drawer an item by accident while trying to build next to it.
	 */
	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
			Player player, BlockHitResult hit) {
		if (level.isClientSide()) {
			return InteractionResult.SUCCESS;
		}

		if (level.getBlockEntity(pos) instanceof DrawerBlockEntity drawer) {
			player.openMenu(drawer);
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
