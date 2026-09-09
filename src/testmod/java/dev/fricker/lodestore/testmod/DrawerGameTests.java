package dev.fricker.lodestore.testmod;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.phys.AABB;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import dev.fricker.lodestore.block.DrawerBlockEntity;
import dev.fricker.lodestore.registry.LodestoreBlocks;

/**
 * Gametests run inside a real ticking world, which is the only way to exercise hoppers: a plain
 * dedicated server never block-ticks them, so hopper assertions there pass vacuously in both
 * directions.
 */
public class DrawerGameTests {
	// --- sizes -------------------------------------------------------------

	@GameTest
	public void tiersHaveTheirIntendedSizes(GameTestHelper helper) {
		expectSize(helper, LodestoreBlocks.BASIC_DRAWER, 27);
		expectSize(helper, LodestoreBlocks.STURDY_DRAWER, 27);
		expectSize(helper, LodestoreBlocks.REINFORCED_DRAWER, 54);
		helper.succeed();
	}

	// --- breaking ----------------------------------------------------------

	/** A basic drawer is a chest: breaking it spills what is inside. */
	@GameTest(maxTicks = 100)
	public void basicDrawerSpillsContentsWhenBroken(GameTestHelper helper) {
		BlockPos pos = new BlockPos(1, 1, 1);
		DrawerBlockEntity drawer = place(helper, pos, LodestoreBlocks.BASIC_DRAWER);
		drawer.setItem(0, new ItemStack(Items.DIAMOND, 5));

		destroy(helper, pos);

		helper.runAtTickTime(5, () -> {
			List<ItemStack> drops = drops(helper, pos);

			if (count(drops, LodestoreBlocks.BASIC_DRAWER.asItem()) == 0) {
				throw helper.assertionException("the drawer block itself did not drop; test setup is wrong");
			}

			long loose = count(drops, Items.DIAMOND);

			if (loose != 5) {
				throw helper.assertionException("basic drawer should spill 5 diamonds, spilled " + loose);
			}

			helper.succeed();
		});
	}

	/** A sturdy drawer is a shulker box: contents travel inside the dropped item. */
	@GameTest(maxTicks = 100)
	public void sturdyDrawerKeepsContentsWhenBroken(GameTestHelper helper) {
		assertKeepsContents(helper, LodestoreBlocks.STURDY_DRAWER);
	}

	@GameTest(maxTicks = 100)
	public void reinforcedDrawerKeepsContentsWhenBroken(GameTestHelper helper) {
		assertKeepsContents(helper, LodestoreBlocks.REINFORCED_DRAWER);
	}

	private void assertKeepsContents(GameTestHelper helper, Block block) {
		BlockPos pos = new BlockPos(1, 1, 1);
		DrawerBlockEntity drawer = place(helper, pos, block);
		drawer.setItem(0, new ItemStack(Items.DIAMOND, 5));

		destroy(helper, pos);

		helper.runAtTickTime(5, () -> {
			List<ItemStack> drops = drops(helper, pos);
			ItemStack dropped = drops.stream()
					.filter(stack -> stack.is(block.asItem()))
					.findFirst()
					.orElse(ItemStack.EMPTY);

			if (dropped.isEmpty()) {
				throw helper.assertionException("the drawer block itself did not drop; test setup is wrong");
			}

			ItemContainerContents contents = dropped.get(DataComponents.CONTAINER);

			if (contents == null) {
				throw helper.assertionException("dropped drawer carries no container component");
			}

			long kept = contents.nonEmptyItemCopyStream()
					.filter(stack -> stack.is(Items.DIAMOND))
					.mapToLong(ItemStack::getCount)
					.sum();

			if (kept != 5) {
				throw helper.assertionException("drawer should have kept 5 diamonds, kept " + kept);
			}

			// Keeping and spilling would duplicate the contents.
			long loose = count(drops, Items.DIAMOND);

			if (loose != 0) {
				throw helper.assertionException("drawer both kept and spilled its contents (" + loose + " loose)");
			}

			helper.succeed();
		});
	}

	// --- nesting -----------------------------------------------------------

	@GameTest
	public void drawersRefuseToNestInsideThemselves(GameTestHelper helper) {
		DrawerBlockEntity basic = place(helper, new BlockPos(1, 1, 1), LodestoreBlocks.BASIC_DRAWER);
		DrawerBlockEntity sturdy = place(helper, new BlockPos(2, 1, 1), LodestoreBlocks.STURDY_DRAWER);
		DrawerBlockEntity reinforced = place(helper, new BlockPos(3, 1, 1), LodestoreBlocks.REINFORCED_DRAWER);

		ItemStack sturdyItem = new ItemStack(LodestoreBlocks.STURDY_DRAWER);
		ItemStack reinforcedItem = new ItemStack(LodestoreBlocks.REINFORCED_DRAWER);

		expect(helper, sturdy.canPlaceItem(0, sturdyItem), false, "sturdy accepted a sturdy drawer");
		expect(helper, sturdy.canPlaceItem(0, reinforcedItem), false, "sturdy accepted a reinforced drawer");
		expect(helper, reinforced.canPlaceItem(0, sturdyItem), true, "reinforced rejected a sturdy drawer");
		expect(helper, reinforced.canPlaceItem(0, reinforcedItem), false, "reinforced accepted a reinforced drawer");

		// A basic drawer spills when broken, so nesting into it compounds nothing.
		expect(helper, basic.canPlaceItem(0, reinforcedItem), true, "basic rejected a drawer");
		expect(helper, sturdy.canPlaceItem(0, new ItemStack(Items.DIAMOND)), true, "sturdy rejected a plain item");

		helper.succeed();
	}

	// --- recipes ------------------------------------------------------------

	/**
	 * A malformed recipe does not fail the build or throw: it is logged and skipped, and the
	 * recipe simply does not exist. Crafting the inputs is the only way to know it works.
	 */
	@GameTest
	public void bothBasicDrawerRecipesCraft(GameTestHelper helper) {
		// Shaped: planks around an iron nugget.
		ItemStack planks = new ItemStack(Items.OAK_PLANKS);
		ItemStack nugget = new ItemStack(Items.IRON_NUGGET);
		assertCrafts(helper, CraftingInput.of(3, 3, List.of(
				planks, planks, planks,
				planks, nugget, planks,
				planks, planks, planks)), "basic_drawer");

		// Shapeless: a chest plus an iron nugget.
		assertCrafts(helper, CraftingInput.of(2, 1, List.of(
				new ItemStack(Items.CHEST), nugget)), "basic_drawer_from_chest");

		helper.succeed();
	}

	private static void assertCrafts(GameTestHelper helper, CraftingInput input, String expectedRecipe) {
		RecipeHolder<CraftingRecipe> match = helper.getLevel().getServer().getRecipeManager()
				.getRecipeFor(RecipeType.CRAFTING, input, helper.getLevel())
				.orElseThrow(() -> helper.assertionException("no recipe matched for " + expectedRecipe));

		Identifier id = match.id().identifier();

		if (!id.equals(Identifier.fromNamespaceAndPath("lodestore", expectedRecipe))) {
			throw helper.assertionException("expected recipe lodestore:" + expectedRecipe + ", matched " + id);
		}

		ItemStack result = match.value().assemble(input);

		if (!result.is(LodestoreBlocks.BASIC_DRAWER.asItem())) {
			throw helper.assertionException(expectedRecipe + " produced " + result.getHoverName().getString());
		}
	}

	// --- front face preview -------------------------------------------------

	/**
	 * The face shows whichever item the drawer holds most of, counted across every slot holding
	 * it — so a kind split over several slots still beats a larger single stack of something else.
	 */
	@GameTest
	public void previewShowsTheMostCommonItem(GameTestHelper helper) {
		DrawerBlockEntity drawer = place(helper, new BlockPos(1, 1, 1), LodestoreBlocks.BASIC_DRAWER);

		drawer.setItem(0, new ItemStack(Items.REDSTONE, 10));
		drawer.setItem(1, new ItemStack(Items.OAK_LOG, 30));
		drawer.setItem(2, new ItemStack(Items.OAK_LOG, 30));
		drawer.setItem(3, new ItemStack(Items.DIAMOND, 50));
		drawer.setChanged();

		if (!drawer.previewItem().is(Items.OAK_LOG)) {
			throw helper.assertionException("preview should be oak log, was "
					+ drawer.previewItem().getHoverName().getString());
		}

		if (drawer.previewCount() != 60) {
			throw helper.assertionException("preview should count 60 oak logs across two slots, counted "
					+ drawer.previewCount());
		}

		helper.succeed();
	}

	@GameTest
	public void emptyDrawerPreviewsNothing(GameTestHelper helper) {
		DrawerBlockEntity drawer = place(helper, new BlockPos(1, 1, 1), LodestoreBlocks.BASIC_DRAWER);
		drawer.setChanged();

		if (!drawer.previewItem().isEmpty() || drawer.previewCount() != 0) {
			throw helper.assertionException("an empty drawer should preview nothing");
		}

		helper.succeed();
	}

	// --- interop -----------------------------------------------------------

	@GameTest(maxTicks = 120)
	public void hopperInsertsIntoDrawer(GameTestHelper helper) {
		BlockPos drawerPos = new BlockPos(1, 1, 1);
		DrawerBlockEntity drawer = place(helper, drawerPos, LodestoreBlocks.BASIC_DRAWER);
		helper.setBlock(drawerPos.above(), Blocks.HOPPER.defaultBlockState());

		HopperBlockEntity hopper = helper.getBlockEntity(drawerPos.above(), HopperBlockEntity.class);
		hopper.setItem(0, new ItemStack(Items.COBBLESTONE, 64));

		helper.runAtTickTime(100, () -> {
			if (drawer.isEmpty()) {
				throw helper.assertionException("hopper moved nothing into the drawer");
			}

			helper.succeed();
		});
	}

	@GameTest(maxTicks = 120)
	public void hopperExtractsFromDrawer(GameTestHelper helper) {
		BlockPos drawerPos = new BlockPos(1, 2, 1);
		DrawerBlockEntity drawer = place(helper, drawerPos, LodestoreBlocks.BASIC_DRAWER);
		drawer.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
		helper.setBlock(drawerPos.below(), Blocks.HOPPER.defaultBlockState());

		helper.runAtTickTime(100, () -> {
			HopperBlockEntity hopper = helper.getBlockEntity(drawerPos.below(), HopperBlockEntity.class);

			if (hopper.getItem(0).isEmpty()) {
				throw helper.assertionException("hopper pulled nothing out of the drawer");
			}

			helper.succeed();
		});
	}

	@GameTest(maxTicks = 200)
	public void chestThroughHopperIntoDrawer(GameTestHelper helper) {
		BlockPos drawerPos = new BlockPos(1, 1, 1);
		DrawerBlockEntity drawer = place(helper, drawerPos, LodestoreBlocks.BASIC_DRAWER);
		helper.setBlock(drawerPos.above(), Blocks.HOPPER.defaultBlockState());
		helper.setBlock(drawerPos.above().above(), Blocks.CHEST.defaultBlockState());

		ChestBlockEntity chest = helper.getBlockEntity(drawerPos.above().above(), ChestBlockEntity.class);
		chest.setItem(0, new ItemStack(Items.COBBLESTONE, 64));

		helper.runAtTickTime(180, () -> {
			if (drawer.isEmpty()) {
				throw helper.assertionException("chest -> hopper -> drawer moved nothing");
			}

			helper.succeed();
		});
	}

	@GameTest(maxTicks = 200)
	public void sidewaysHopperInsertsIntoDrawer(GameTestHelper helper) {
		BlockPos drawerPos = new BlockPos(1, 1, 1);
		DrawerBlockEntity drawer = place(helper, drawerPos, LodestoreBlocks.BASIC_DRAWER);
		helper.setBlock(drawerPos.north(), Blocks.HOPPER.defaultBlockState()
				.setValue(HopperBlock.FACING, Direction.SOUTH));

		HopperBlockEntity hopper = helper.getBlockEntity(drawerPos.north(), HopperBlockEntity.class);
		hopper.setItem(0, new ItemStack(Items.COBBLESTONE, 64));

		helper.runAtTickTime(180, () -> {
			if (drawer.isEmpty()) {
				throw helper.assertionException("sideways hopper moved nothing into the drawer");
			}

			helper.succeed();
		});
	}

	/** A hopper must not be able to pipe a drawer past the nesting rule. */
	@GameTest(maxTicks = 200)
	public void hopperCannotNestSturdyIntoSturdy(GameTestHelper helper) {
		BlockPos drawerPos = new BlockPos(1, 1, 1);
		DrawerBlockEntity drawer = place(helper, drawerPos, LodestoreBlocks.STURDY_DRAWER);
		helper.setBlock(drawerPos.above(), Blocks.HOPPER.defaultBlockState());

		HopperBlockEntity hopper = helper.getBlockEntity(drawerPos.above(), HopperBlockEntity.class);
		hopper.setItem(0, new ItemStack(LodestoreBlocks.STURDY_DRAWER, 4));

		helper.runAtTickTime(180, () -> {
			if (!drawer.isEmpty()) {
				throw helper.assertionException("a hopper nested a sturdy drawer inside a sturdy drawer");
			}

			helper.succeed();
		});
	}

	// --- comparator --------------------------------------------------------

	@GameTest(maxTicks = 200)
	public void fillingDrawerRaisesComparatorOutput(GameTestHelper helper) {
		BlockPos drawerPos = new BlockPos(1, 1, 1);
		DrawerBlockEntity drawer = place(helper, drawerPos, LodestoreBlocks.BASIC_DRAWER);

		if (signal(helper, drawerPos) != 0) {
			throw helper.assertionException("empty drawer should read 0 on a comparator");
		}

		drawer.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
		drawer.setChanged();

		if (signal(helper, drawerPos) <= 0) {
			throw helper.assertionException("drawer holds items but comparator still reads 0");
		}

		helper.succeed();
	}

	// --- helpers -----------------------------------------------------------

	private static DrawerBlockEntity place(GameTestHelper helper, BlockPos pos, Block block) {
		helper.setBlock(pos, block.defaultBlockState());
		return helper.getBlockEntity(pos, DrawerBlockEntity.class);
	}

	private static void destroy(GameTestHelper helper, BlockPos pos) {
		// helper.destroyBlock does not run the loot pipeline, so nothing would drop at all.
		helper.getLevel().destroyBlock(helper.absolutePos(pos), true, null, 512);
	}

	private static List<ItemStack> drops(GameTestHelper helper, BlockPos pos) {
		BlockPos abs = helper.absolutePos(pos);
		return helper.getLevel()
				.getEntitiesOfClass(ItemEntity.class, new AABB(abs).inflate(6.0))
				.stream()
				.map(ItemEntity::getItem)
				.toList();
	}

	private static long count(List<ItemStack> stacks, net.minecraft.world.item.Item item) {
		return stacks.stream().filter(stack -> stack.is(item)).mapToLong(ItemStack::getCount).sum();
	}

	private static void expectSize(GameTestHelper helper, Block block, int expected) {
		DrawerBlockEntity drawer = place(helper, new BlockPos(1, 1, 1), block);

		if (drawer.getContainerSize() != expected) {
			throw helper.assertionException(block.getName().getString() + " has "
					+ drawer.getContainerSize() + " slots, expected " + expected);
		}
	}

	private static void expect(GameTestHelper helper, boolean actual, boolean expected, String message) {
		if (actual != expected) {
			throw helper.assertionException(message);
		}
	}

	private static int signal(GameTestHelper helper, BlockPos relativePos) {
		BlockPos abs = helper.absolutePos(relativePos);
		return helper.getLevel().getBlockState(abs)
				.getAnalogOutputSignal(helper.getLevel(), abs, Direction.NORTH);
	}
}
