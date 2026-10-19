package dev.fricker.lodestore.block;


import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
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

import dev.fricker.lodestore.api.crate.CrateTier;
import dev.fricker.lodestore.item.CrateUpgradeItem;

/**
 * A crate block. Structure (how many rows it opens) is fixed per block; behaviour (whether it
 * keeps its contents, and what it may contain) is datapack-driven via {@link CrateTier}.
 */
public class CrateBlock extends Block implements EntityBlock {
	public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

	private final int rows;
	private final ResourceKey<CrateTier> tierKey;

	public CrateBlock(Properties properties, int rows, ResourceKey<CrateTier> tierKey) {
		super(properties);
		this.rows = rows;
		this.tierKey = tierKey;
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
	}

	public int rows() {
		return rows;
	}

	public ResourceKey<CrateTier> tierKey() {
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
		return new CrateBlockEntity(pos, state);
	}

	/**
	 * Opens the crate.
	 *
	 * <p>{@code useItemOn} is deliberately not overridden, which gives the vanilla chest contract:
	 * right-clicking opens the crate whatever is in hand, and sneaking places the held block
	 * instead. The previous behaviour — depositing whatever you were holding — made it easy to
	 * assign a crate an item by accident while trying to build next to it.
	 */
	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
			Player player, BlockHitResult hit) {
		if (level.isClientSide()) {
			return InteractionResult.SUCCESS;
		}

		if (level.getBlockEntity(pos) instanceof CrateBlockEntity crate) {
			player.openMenu(crate);
			return InteractionResult.CONSUME;
		}

		return InteractionResult.PASS;
	}

	/**
	 * Installs an upgrade held in hand, and otherwise gets out of the way.
	 *
	 * <p>This is the one exception to "right-clicking opens the crate whatever is in hand".
	 * Holding an upgrade is unambiguous — it does nothing else anywhere — whereas the rule exists
	 * to stop a crate reacting to the building blocks a player happens to be carrying.
	 *
	 * <p>Everything else returns {@code TRY_WITH_EMPTY_HAND}, which is the default's own return
	 * value and the thing that routes the click on to {@link #useWithoutItem}. Returning
	 * {@code PASS} here would swallow it and the crate would stop opening.
	 */
	@Override
	protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
			Player player, InteractionHand hand, BlockHitResult hit) {
		if (!(stack.getItem() instanceof CrateUpgradeItem)
				|| !(level.getBlockEntity(pos) instanceof CrateBlockEntity crate)) {
			return InteractionResult.TRY_WITH_EMPTY_HAND;
		}

		if (level.isClientSide()) {
			return InteractionResult.SUCCESS;
		}

		// Already installed, or no slots left. Fall through and open, rather than eating the
		// click: a dead click gives the player nothing to tell the two cases apart by.
		if (!crate.installUpgrade(stack)) {
			return InteractionResult.TRY_WITH_EMPTY_HAND;
		}

		stack.consume(1, player);
		return InteractionResult.CONSUME;
	}

	@Override
	protected boolean hasAnalogOutputSignal(BlockState state) {
		return true;
	}

	@Override
	protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos, Direction direction) {
		// Not the generic helper: it divides by the container size, which includes the overflow
		// slot a voiding crate uses and which never holds anything, so a full crate would
		// read 14 rather than 15.
		return level.getBlockEntity(pos) instanceof CrateBlockEntity crate ? crate.comparatorSignal() : 0;
	}

}
