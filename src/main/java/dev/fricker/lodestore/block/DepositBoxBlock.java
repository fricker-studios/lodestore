package dev.fricker.lodestore.block;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import dev.fricker.lodestore.registry.LodestoreBlockEntities;

/**
 * The deposit box: the one network block that ticks, because it is the one that moves anything.
 *
 * <p>Right-click opens it as a one-row chest. The contents spill on break through vanilla's own
 * path, since the block entity is a container. A comparator reads how full it is, which is how a
 * player learns something had no home without opening it.
 */
public class DepositBoxBlock extends Block implements EntityBlock {
	public DepositBoxBlock(Properties properties) {
		super(properties);
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
