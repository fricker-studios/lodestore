package dev.fricker.lodestore.block;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
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
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;

import dev.fricker.lodestore.api.drawer.DrawerTier;
import dev.fricker.lodestore.storage.DrawerSlot;

/**
 * A drawer block. One block instance per tier; the tier itself is datapack-defined and resolved
 * at runtime, so a pack can retune capacities without touching code.
 */
public class DrawerBlock extends Block implements EntityBlock {
	public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

	private final ResourceKey<DrawerTier> tierKey;

	public DrawerBlock(Properties properties, ResourceKey<DrawerTier> tierKey) {
		super(properties);
		this.tierKey = tierKey;
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
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
		// Face the player who placed it.
		return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new DrawerBlockEntity(pos, state);
	}

	/**
	 * Include the drawer's contents in its drops.
	 *
	 * <p>Without this the loot table returns only the drawer itself and everything inside is
	 * silently destroyed. A drawer can hold thousands of items, so that is far more damaging than
	 * losing a chest.
	 *
	 * <p>Hooked here rather than in {@code affectNeighborsAfterRemoval}, which runs after the block
	 * entity is already gone, and unlike a player-break hook this covers explosions and every other
	 * destruction path too.
	 */
	@Override
	protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
		List<ItemStack> drops = new ArrayList<>(super.getDrops(state, params));

		if (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof DrawerBlockEntity drawer) {
			for (DrawerSlot slot : drawer.slots()) {
				if (slot.isResourceBlank()) {
					continue;
				}

				ItemVariant variant = slot.getResource();
				int perStack = variant.getItem().getDefaultMaxStackSize();
				long remaining = slot.getAmount();

				// A long count becomes however many whole stacks it takes.
				while (remaining > 0) {
					int count = (int) Math.min(remaining, perStack);
					drops.add(variant.toStack(count));
					remaining -= count;
				}
			}
		}

		return drops;
	}

	@Override
	protected boolean hasAnalogOutputSignal(BlockState state) {
		return true;
	}

	/**
	 * Comparator output scaled by fullness, 0-15.
	 *
	 * <p>Until the front-face overlay exists this is the only way to observe a drawer's contents
	 * without clicking it, which makes it the practical way to confirm that a hopper or pipe is
	 * actually feeding one.
	 */
	@Override
	protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos, Direction direction) {
		if (!(level.getBlockEntity(pos) instanceof DrawerBlockEntity drawer)) {
			return 0;
		}

		long stored = 0;
		long capacity = 0;

		for (DrawerSlot slot : drawer.slots()) {
			stored += slot.getAmount();
			capacity += slot.getCapacity();
		}

		if (stored == 0 || capacity == 0) {
			return 0;
		}

		// Any non-zero amount must read as at least 1, or a nearly-empty drawer is
		// indistinguishable from an empty one.
		return (int) Math.max(1, (stored * 15L) / capacity);
	}

	/** Right-click holding an item: deposit as much of the held stack as fits. */
	@Override
	protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
			Player player, InteractionHand hand, BlockHitResult hit) {
		if (stack.isEmpty()) {
			return InteractionResult.PASS;
		}

		if (level.isClientSide()) {
			return InteractionResult.SUCCESS;
		}

		if (!(level.getBlockEntity(pos) instanceof DrawerBlockEntity drawer)) {
			return InteractionResult.PASS;
		}

		Storage<ItemVariant> storage = drawer.getItemStorage(null);

		if (storage == null) {
			return InteractionResult.PASS;
		}

		// A drawer takes its type from whatever goes in first, so it is easy to
		// assign one by accident while trying to place a block against it. Say so.
		boolean wasEmpty = drawer.slots().stream().allMatch(DrawerSlot::isResourceBlank);
		long inserted;

		try (Transaction tx = Transaction.openOuter()) {
			inserted = storage.insert(ItemVariant.of(stack), stack.getCount(), tx);
			tx.commit();
		}

		if (inserted == 0) {
			// insert() returns 0 for both "full" and "holds something else"; say which.
			player.sendSystemMessage(Component.literal(rejectionReason(drawer, ItemVariant.of(stack))));
			return InteractionResult.CONSUME;
		}

		if (!player.getAbilities().instabuild) {
			stack.shrink((int) inserted);
		}

		if (wasEmpty) {
			player.sendSystemMessage(Component.literal("Drawer now stores ")
					.append(stack.getHoverName())
					.append(" — sneak + right-click to inspect, right-click empty-handed to take it back."));
		}

		return InteractionResult.SUCCESS;
	}

	/** Right-click empty-handed: withdraw a stack. Sneak instead to read the contents. */
	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
			Player player, BlockHitResult hit) {
		if (level.isClientSide()) {
			return InteractionResult.SUCCESS;
		}

		if (!(level.getBlockEntity(pos) instanceof DrawerBlockEntity drawer)) {
			return InteractionResult.PASS;
		}

		if (player.isShiftKeyDown()) {
			player.sendSystemMessage(describe(drawer));
			return InteractionResult.CONSUME;
		}

		for (DrawerSlot slot : drawer.slots()) {
			if (slot.isResourceBlank()) {
				continue;
			}

			ItemVariant variant = slot.getResource();
			long extracted;

			try (Transaction tx = Transaction.openOuter()) {
				extracted = slot.extract(variant, variant.getItem().getDefaultMaxStackSize(), tx);
				tx.commit();
			}

			if (extracted > 0) {
				player.getInventory().placeItemBackInInventory(variant.toStack((int) extracted));
				return InteractionResult.SUCCESS;
			}
		}

		return InteractionResult.CONSUME;
	}

	private static String rejectionReason(DrawerBlockEntity drawer, ItemVariant variant) {
		for (DrawerSlot slot : drawer.slots()) {
			if (slot.isResourceBlank() || slot.getResource().equals(variant)) {
				return "Drawer is full.";
			}
		}

		// Name what is actually in there. "Holds a different item" leaves the player
		// guessing, which is exactly the situation this message exists to resolve.
		String held = drawer.slots().getFirst().getResource().toStack().getHoverName().getString();
		return "Drawer holds " + held + ", not " + variant.toStack().getHoverName().getString() + ".";
	}

	/**
	 * Temporary readout until the front-face count overlay lands. Also the quickest way to confirm
	 * that a hopper or pipe actually moved something.
	 */
	private static Component describe(DrawerBlockEntity drawer) {
		StringBuilder sb = new StringBuilder();

		for (DrawerSlot slot : drawer.slots()) {
			if (!sb.isEmpty()) {
				sb.append(" | ");
			}

			if (slot.isResourceBlank()) {
				sb.append("empty");
			} else {
				sb.append(slot.getAmount())
						.append(" / ")
						.append(slot.getCapacity())
						.append(" ")
						.append(slot.getResource().toStack().getHoverName().getString());
			}
		}

		return Component.literal(sb.toString());
	}
}
