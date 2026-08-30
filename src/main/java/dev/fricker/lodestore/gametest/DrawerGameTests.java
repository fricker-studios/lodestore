package dev.fricker.lodestore.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import dev.fricker.lodestore.block.DrawerBlock;
import dev.fricker.lodestore.block.DrawerBlockEntity;
import dev.fricker.lodestore.registry.LodestoreBlocks;

/**
 * Gametests run inside a real ticking world, which is the only way to exercise hoppers: a plain
 * dedicated server never block-ticks them, so hopper assertions there pass vacuously in both
 * directions.
 */
public class DrawerGameTests {
	/** A hopper pointed into a drawer must deposit into it. */
	@GameTest(maxTicks = 120)
	public void hopperInsertsIntoDrawer(GameTestHelper helper) {
		BlockPos drawerPos = new BlockPos(1, 1, 1);
		BlockPos hopperPos = drawerPos.above();

		helper.setBlock(drawerPos, LodestoreBlocks.BASIC_DRAWER.defaultBlockState());
		// A hopper's default facing is already DOWN, into the drawer.
		helper.setBlock(hopperPos, Blocks.HOPPER.defaultBlockState());

		HopperBlockEntity hopper = helper.getBlockEntity(hopperPos, HopperBlockEntity.class);
		hopper.setItem(0, new ItemStack(Items.COBBLESTONE, 64));

		helper.runAtTickTime(100, () -> {
			DrawerBlockEntity drawer = helper.getBlockEntity(drawerPos, DrawerBlockEntity.class);
			long stored = drawer.slots().getFirst().getAmount();

			if (stored == 0) {
				throw helper.assertionException(
						"hopper moved nothing into the drawer; hopper still holds "
								+ hopper.getItem(0).getCount());
			}

			helper.succeed();
		});
	}

	/** A hopper underneath a drawer must pull out of it. */
	@GameTest(maxTicks = 120)
	public void hopperExtractsFromDrawer(GameTestHelper helper) {
		BlockPos drawerPos = new BlockPos(1, 2, 1);
		BlockPos hopperPos = drawerPos.below();

		helper.setBlock(drawerPos, LodestoreBlocks.BASIC_DRAWER.defaultBlockState());
		helper.setBlock(hopperPos, Blocks.HOPPER.defaultBlockState());

		DrawerBlockEntity drawer = helper.getBlockEntity(drawerPos, DrawerBlockEntity.class);
		seed(drawer, 64);

		helper.runAtTickTime(100, () -> {
			HopperBlockEntity hopper = helper.getBlockEntity(hopperPos, HopperBlockEntity.class);

			if (hopper.getItem(0).isEmpty()) {
				throw helper.assertionException("hopper pulled nothing out of the drawer");
			}

			helper.succeed();
		});
	}

	/** The common real-world build: a chest feeding a hopper feeding a drawer. */
	@GameTest(maxTicks = 200)
	public void chestThroughHopperIntoDrawer(GameTestHelper helper) {
		BlockPos drawerPos = new BlockPos(1, 1, 1);
		BlockPos hopperPos = drawerPos.above();
		BlockPos chestPos = hopperPos.above();

		helper.setBlock(drawerPos, LodestoreBlocks.BASIC_DRAWER.defaultBlockState());
		helper.setBlock(hopperPos, Blocks.HOPPER.defaultBlockState());
		helper.setBlock(chestPos, Blocks.CHEST.defaultBlockState());

		ChestBlockEntity chest = helper.getBlockEntity(chestPos, ChestBlockEntity.class);
		chest.setItem(0, new ItemStack(Items.COBBLESTONE, 64));

		helper.runAtTickTime(180, () -> {
			DrawerBlockEntity drawer = helper.getBlockEntity(drawerPos, DrawerBlockEntity.class);
			long stored = drawer.slots().getFirst().getAmount();

			if (stored == 0) {
				throw helper.assertionException("chest -> hopper -> drawer moved nothing");
			}

			helper.succeed();
		});
	}

	/** A hopper feeding the drawer from the side, rather than from above. */
	@GameTest(maxTicks = 200)
	public void sidewaysHopperInsertsIntoDrawer(GameTestHelper helper) {
		BlockPos drawerPos = new BlockPos(1, 1, 1);
		BlockPos hopperPos = drawerPos.north();

		helper.setBlock(drawerPos, LodestoreBlocks.BASIC_DRAWER.defaultBlockState());
		helper.setBlock(hopperPos, Blocks.HOPPER.defaultBlockState()
				.setValue(HopperBlock.FACING, Direction.SOUTH));

		HopperBlockEntity hopper = helper.getBlockEntity(hopperPos, HopperBlockEntity.class);
		hopper.setItem(0, new ItemStack(Items.COBBLESTONE, 64));

		helper.runAtTickTime(180, () -> {
			DrawerBlockEntity drawer = helper.getBlockEntity(drawerPos, DrawerBlockEntity.class);
			long stored = drawer.slots().getFirst().getAmount();

			if (stored == 0) {
				throw helper.assertionException("sideways hopper moved nothing into the drawer");
			}

			helper.succeed();
		});
	}

	/**
	 * A drawer placed by a player carries a FACING blockstate, unlike the default state used by the
	 * other tests. Guards against storage exposure accidentally depending on orientation.
	 */
	@GameTest(maxTicks = 120)
	public void hopperInsertsIntoRotatedDrawer(GameTestHelper helper) {
		BlockPos drawerPos = new BlockPos(1, 1, 1);
		BlockPos hopperPos = drawerPos.above();

		helper.setBlock(drawerPos, LodestoreBlocks.BASIC_DRAWER.defaultBlockState()
				.setValue(DrawerBlock.FACING, Direction.EAST));
		helper.setBlock(hopperPos, Blocks.HOPPER.defaultBlockState());

		HopperBlockEntity hopper = helper.getBlockEntity(hopperPos, HopperBlockEntity.class);
		hopper.setItem(0, new ItemStack(Items.COBBLESTONE, 64));

		helper.runAtTickTime(100, () -> {
			DrawerBlockEntity drawer = helper.getBlockEntity(drawerPos, DrawerBlockEntity.class);

			if (drawer.slots().getFirst().getAmount() == 0) {
				throw helper.assertionException("hopper moved nothing into a rotated drawer");
			}

			helper.succeed();
		});
	}

	/**
	 * A hopper filling a drawer must move the comparator reading off zero. This is the check a
	 * player can reproduce in their own world without any rendering.
	 */
	@GameTest(maxTicks = 200)
	public void hopperFillingDrawerRaisesComparatorOutput(GameTestHelper helper) {
		BlockPos drawerPos = new BlockPos(1, 1, 1);
		BlockPos hopperPos = drawerPos.above();

		helper.setBlock(drawerPos, LodestoreBlocks.BASIC_DRAWER.defaultBlockState());
		helper.setBlock(hopperPos, Blocks.HOPPER.defaultBlockState());

		DrawerBlockEntity drawer = helper.getBlockEntity(drawerPos, DrawerBlockEntity.class);

		if (signal(helper, drawerPos) != 0) {
			throw helper.assertionException("empty drawer should read 0 on a comparator");
		}

		HopperBlockEntity hopper = helper.getBlockEntity(hopperPos, HopperBlockEntity.class);
		hopper.setItem(0, new ItemStack(Items.COBBLESTONE, 64));

		helper.runAtTickTime(180, () -> {
			if (drawer.slots().getFirst().getAmount() == 0) {
				throw helper.assertionException("hopper moved nothing");
			}

			if (signal(helper, drawerPos) <= 0) {
				throw helper.assertionException("drawer holds items but comparator still reads 0");
			}

			helper.succeed();
		});
	}

	private static int signal(GameTestHelper helper, BlockPos relativePos) {
		BlockPos abs = helper.absolutePos(relativePos);
		return helper.getLevel().getBlockState(abs)
				.getAnalogOutputSignal(helper.getLevel(), abs, net.minecraft.core.Direction.NORTH);
	}

	private static void seed(DrawerBlockEntity drawer, long amount) {
		try (var tx = net.fabricmc.fabric.api.transfer.v1.transaction.Transaction.openOuter()) {
			drawer.slots().getFirst().insert(
					net.fabricmc.fabric.api.transfer.v1.item.ItemVariant.of(Items.COBBLESTONE),
					amount, tx);
			tx.commit();
		}
	}
}
