package dev.fricker.lodestore.testmod;

import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.phys.AABB;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import dev.fricker.lodestore.block.DrawerBlockEntity;
import dev.fricker.lodestore.registry.LodestoreBlocks;
import dev.fricker.lodestore.registry.LodestoreItems;
import dev.fricker.lodestore.util.PersistenceSelfCheck;

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

	/**
	 * The container is one slot larger than the storage, and that slot is the overflow slot a
	 * voiding drawer accepts into. The chest screen must not grow with it: the whole reason the
	 * slot can exist at all is that {@code ChestMenu} checks a minimum and lays out exactly
	 * {@code rows * 9}, so a drawer that handed back a menu sized from the container would put a
	 * slot on screen that swallows whatever is dropped into it.
	 */
	@GameTest
	public void theOverflowSlotIsOutsideTheStorage(GameTestHelper helper) {
		DrawerBlockEntity drawer = place(helper, new BlockPos(1, 1, 1), LodestoreBlocks.BASIC_DRAWER);

		if (drawer.getContainerSize() != drawer.storageSize() + 1) {
			throw helper.assertionException("expected exactly one slot past the storage, container is "
					+ drawer.getContainerSize() + " against " + drawer.storageSize() + " stored");
		}

		if (drawer.overflowSlot() != drawer.storageSize()) {
			throw helper.assertionException("the overflow slot should sit just past the storage, is "
					+ drawer.overflowSlot());
		}

		// A drawer nobody has upgraded must not quietly eat things through it.
		expect(helper, drawer.canPlaceItem(drawer.overflowSlot(), new ItemStack(Items.COBBLESTONE)), false,
				"a drawer with no void upgrade accepted an item into its overflow slot");

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

	// --- tools ---------------------------------------------------------------

	/**
	 * Drawers are wood, so an axe should be the efficient tool, as it is for a chest or barrel.
	 *
	 * <p>A tag file in the wrong place is another silent failure: nothing errors, the block is
	 * simply absent from the tag and quietly slow to break.
	 */
	@GameTest
	public void drawersAreMineableWithAnAxe(GameTestHelper helper) {
		for (Block block : List.of(LodestoreBlocks.BASIC_DRAWER, LodestoreBlocks.STURDY_DRAWER,
				LodestoreBlocks.REINFORCED_DRAWER)) {
			BlockState state = block.defaultBlockState();

			if (!state.is(BlockTags.MINEABLE_WITH_AXE)) {
				throw helper.assertionException(block.getName().getString() + " is not in minecraft:mineable/axe");
			}

			// An axe must actually be faster than a bare hand, which is the point of the tag.
			float byHand = state.getDestroySpeed(helper.getLevel(), helper.absolutePos(new BlockPos(1, 1, 1)));
			float axeSpeed = new ItemStack(Items.IRON_AXE).getDestroySpeed(state);
			float handSpeed = ItemStack.EMPTY.getDestroySpeed(state);

			if (byHand <= 0) {
				throw helper.assertionException(block.getName().getString() + " is unbreakable");
			}

			if (axeSpeed <= handSpeed) {
				throw helper.assertionException(block.getName().getString()
						+ " is no faster with an axe (" + axeSpeed + " vs " + handSpeed + " by hand)");
			}
		}

		helper.succeed();
	}

	// --- persistence --------------------------------------------------------

	/**
	 * Every Lodestore container must survive being written and read back.
	 *
	 * <p>Drawer contents were once lost exactly this way, past sixteen passing tests: none of them
	 * round-tripped a block entity through a save, and a container that writes nothing is
	 * indistinguishable from an empty one at runtime. Runs against whatever is registered, so a
	 * backpack or network cell added later is covered without extending this test.
	 */
	@GameTest
	public void everyContainerSurvivesASaveAndLoad(GameTestHelper helper) {
		List<String> failures = PersistenceSelfCheck.run(helper.getLevel().registryAccess());

		if (!failures.isEmpty()) {
			throw helper.assertionException(String.join("; ", failures));
		}

		// Guard against the check silently covering nothing, which would make it useless.
		DrawerBlockEntity drawer = place(helper, new BlockPos(1, 1, 1), LodestoreBlocks.BASIC_DRAWER);

		if (drawer.getContainerSize() == 0) {
			throw helper.assertionException("no containers were exercised; the self-check is vacuous");
		}

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
				planks, planks, planks)), "basic_drawer", LodestoreBlocks.BASIC_DRAWER.asItem());

		// Shapeless: a chest plus an iron nugget.
		assertCrafts(helper, CraftingInput.of(2, 1, List.of(
				new ItemStack(Items.CHEST), nugget)), "basic_drawer_from_chest",
				LodestoreBlocks.BASIC_DRAWER.asItem());

		// The lock upgrade: gold nuggets around an iron ingot, over a second ingot.
		ItemStack gold = new ItemStack(Items.GOLD_NUGGET);
		ItemStack iron = new ItemStack(Items.IRON_INGOT);
		assertCrafts(helper, CraftingInput.of(3, 3, List.of(
				ItemStack.EMPTY, gold, ItemStack.EMPTY,
				gold, iron, gold,
				ItemStack.EMPTY, iron, ItemStack.EMPTY)), "lock_upgrade", LodestoreItems.LOCK_UPGRADE);

		// The void upgrade: obsidian around an iron ingot, over a second ingot — the lock's
		// shape in a material that reads as destructive.
		ItemStack obsidian = new ItemStack(Items.OBSIDIAN);
		assertCrafts(helper, CraftingInput.of(3, 3, List.of(
				ItemStack.EMPTY, obsidian, ItemStack.EMPTY,
				obsidian, iron, obsidian,
				ItemStack.EMPTY, iron, ItemStack.EMPTY)), "void_upgrade", LodestoreItems.VOID_UPGRADE);

		helper.succeed();
	}

	private static void assertCrafts(GameTestHelper helper, CraftingInput input, String expectedRecipe,
			net.minecraft.world.item.Item expectedResult) {
		RecipeHolder<CraftingRecipe> match = helper.getLevel().getServer().getRecipeManager()
				.getRecipeFor(RecipeType.CRAFTING, input, helper.getLevel())
				.orElseThrow(() -> helper.assertionException("no recipe matched for " + expectedRecipe));

		Identifier id = match.id().identifier();

		if (!id.equals(Identifier.fromNamespaceAndPath("lodestore", expectedRecipe))) {
			throw helper.assertionException("expected recipe lodestore:" + expectedRecipe + ", matched " + id);
		}

		ItemStack result = match.value().assemble(input);

		if (!result.is(expectedResult)) {
			throw helper.assertionException(expectedRecipe + " produced " + result.getHoverName().getString());
		}
	}

	/**
	 * Every recipe ships an advancement that reveals it in the recipe book, and each advancement
	 * grants the recipe it is named for. A malformed advancement is skipped just as quietly as a
	 * malformed recipe, leaving a recipe that works but can never be discovered.
	 */
	@GameTest
	public void everyRecipeHasAnUnlockAdvancement(GameTestHelper helper) {
		// Folder per recipe: the drawers are decorations, the upgrade is not.
		Map<String, String> recipes = Map.of(
				"basic_drawer", "decorations",
				"basic_drawer_from_chest", "decorations",
				"sturdy_drawer", "decorations",
				"reinforced_drawer", "decorations",
				"lock_upgrade", "misc",
				"void_upgrade", "misc");

		for (Map.Entry<String, String> entry : recipes.entrySet()) {
			String recipe = entry.getKey();
			Identifier advancementId = Identifier.fromNamespaceAndPath(
					"lodestore", "recipes/" + entry.getValue() + "/" + recipe);
			AdvancementHolder holder = helper.getLevel().getServer().getAdvancements().get(advancementId);

			if (holder == null) {
				throw helper.assertionException("no advancement loaded at " + advancementId);
			}

			ResourceKey<Recipe<?>> expected = ResourceKey.create(Registries.RECIPE,
					Identifier.fromNamespaceAndPath("lodestore", recipe));

			if (!holder.value().rewards().recipes().contains(expected)) {
				throw helper.assertionException(advancementId + " does not grant " + expected.identifier());
			}
		}

		helper.succeed();
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

		// The overflow slot must not count towards fullness. Measured over the container rather
		// than the storage, a completely full drawer tops out at 14 and never reads full.
		fillStorage(drawer, Items.COBBLESTONE);

		if (signal(helper, drawerPos) != 15) {
			throw helper.assertionException("a completely full drawer should read 15, reads "
					+ signal(helper, drawerPos));
		}

		helper.succeed();
	}

	// --- stacking ----------------------------------------------------------

	/**
	 * A drawer that has been placed and broken must stack with one straight from the creative
	 * menu.
	 *
	 * <p>Breaking a container copies its implicit components onto the dropped item, and
	 * {@code BaseContainerBlockEntity} writes {@code minecraft:container} unconditionally — an
	 * empty drawer still produces an empty container component. An item carrying that does not
	 * match one that has never had it, so the two sit in separate inventory slots and will not
	 * merge. Vanilla avoids this by giving shulker boxes the same component by default.
	 */
	@GameTest(maxTicks = 100)
	public void brokenBasicDrawerStacksWithAFreshOne(GameTestHelper helper) {
		assertStacksWithFresh(helper, LodestoreBlocks.BASIC_DRAWER);
	}

	@GameTest(maxTicks = 100)
	public void brokenSturdyDrawerStacksWithAFreshOne(GameTestHelper helper) {
		assertStacksWithFresh(helper, LodestoreBlocks.STURDY_DRAWER);
	}

	@GameTest(maxTicks = 100)
	public void brokenReinforcedDrawerStacksWithAFreshOne(GameTestHelper helper) {
		assertStacksWithFresh(helper, LodestoreBlocks.REINFORCED_DRAWER);
	}

	private static void assertStacksWithFresh(GameTestHelper helper, Block block) {
		BlockPos pos = new BlockPos(1, 1, 1);
		place(helper, pos, block);  // placed and broken, never filled
		destroy(helper, pos);

		helper.runAtTickTime(5, () -> {
			ItemStack dropped = drops(helper, pos).stream()
					.filter(stack -> stack.is(block.asItem()))
					.findFirst()
					.orElseThrow(() -> helper.assertionException("the drawer did not drop at all"));

			ItemStack fresh = new ItemStack(block);

			if (!ItemStack.isSameItemSameComponents(dropped, fresh)) {
				throw helper.assertionException("a broken " + block.getName().getString()
						+ " will not stack with a fresh one: dropped carries " + dropped.getComponentsPatch()
						+ ", fresh carries " + fresh.getComponentsPatch());
			}

			helper.succeed();
		});
	}

	// --- upgrades ----------------------------------------------------------

	/** A lock dropped onto a drawer that already holds something locks to what is inside. */
	@GameTest
	public void lockClaimsWhatTheDrawerAlreadyHolds(GameTestHelper helper) {
		DrawerBlockEntity drawer = place(helper, new BlockPos(1, 1, 1), LodestoreBlocks.BASIC_DRAWER);
		drawer.setItem(0, new ItemStack(Items.COBBLESTONE, 12));
		drawer.setChanged();

		if (!drawer.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE))) {
			throw helper.assertionException("the lock upgrade would not install");
		}

		if (!drawer.lockedItem().is(Items.COBBLESTONE)) {
			throw helper.assertionException("lock should have claimed cobblestone, claimed "
					+ describe(drawer.lockedItem()));
		}

		helper.succeed();
	}

	/**
	 * A lock on an empty drawer has nothing to claim yet, so it takes the first item in. Locking
	 * to "nothing" would make the drawer refuse everything, the one item included.
	 */
	@GameTest
	public void lockOnAnEmptyDrawerClaimsTheFirstItemIn(GameTestHelper helper) {
		DrawerBlockEntity drawer = place(helper, new BlockPos(1, 1, 1), LodestoreBlocks.BASIC_DRAWER);
		drawer.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));

		if (!drawer.lockedItem().isEmpty()) {
			throw helper.assertionException("a lock on an empty drawer claimed "
					+ describe(drawer.lockedItem()) + " out of nowhere");
		}

		drawer.setItem(0, new ItemStack(Items.REDSTONE, 4));
		drawer.setChanged();

		if (!drawer.lockedItem().is(Items.REDSTONE)) {
			throw helper.assertionException("lock should have claimed redstone, claimed "
					+ describe(drawer.lockedItem()));
		}

		helper.succeed();
	}

	@GameTest
	public void aLockedDrawerRefusesEverythingElse(GameTestHelper helper) {
		DrawerBlockEntity drawer = place(helper, new BlockPos(1, 1, 1), LodestoreBlocks.BASIC_DRAWER);
		drawer.setItem(0, new ItemStack(Items.COBBLESTONE, 12));
		drawer.setChanged();

		expect(helper, drawer.canPlaceItem(1, new ItemStack(Items.DIRT)), true,
				"an unlocked drawer should take anything; the test proves nothing otherwise");

		drawer.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));

		expect(helper, drawer.canPlaceItem(1, new ItemStack(Items.COBBLESTONE)), true,
				"a locked drawer must still accept the item it is locked to");
		expect(helper, drawer.canPlaceItem(1, new ItemStack(Items.DIRT)), false,
				"a locked drawer must refuse anything but the item it is locked to");

		helper.succeed();
	}

	/**
	 * The lock is enforced in {@code canPlaceItem} for the same reason the nesting rule is: a
	 * filter the chest screen applied alone would be bypassed by piping items in.
	 */
	@GameTest(maxTicks = 200)
	public void aHopperCannotInsertPastALock(GameTestHelper helper) {
		BlockPos drawerPos = new BlockPos(1, 1, 1);
		DrawerBlockEntity drawer = place(helper, drawerPos, LodestoreBlocks.BASIC_DRAWER);
		drawer.setItem(0, new ItemStack(Items.COBBLESTONE, 1));
		drawer.setChanged();
		drawer.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));

		helper.setBlock(drawerPos.above(), Blocks.HOPPER.defaultBlockState());
		HopperBlockEntity hopper = helper.getBlockEntity(drawerPos.above(), HopperBlockEntity.class);
		hopper.setItem(0, new ItemStack(Items.DIRT, 64));

		helper.runAtTickTime(180, () -> {
			for (int slot = 0; slot < drawer.getContainerSize(); slot++) {
				if (drawer.getItem(slot).is(Items.DIRT)) {
					throw helper.assertionException("a hopper pushed dirt into a drawer locked to cobblestone");
				}
			}

			// The other half: refusing must not eat the items it refused.
			if (hopper.getItem(0).getCount() != 64) {
				throw helper.assertionException("the hopper lost dirt it was never allowed to insert, "
						+ hopper.getItem(0).getCount() + " left");
			}

			helper.succeed();
		});
	}

	/**
	 * Upgrades are not inventory, so {@code PersistenceSelfCheck} — which only knows how to fill a
	 * {@code Container} — does not cover them. They get their own round trip here.
	 */
	@GameTest
	public void upgradeStateSurvivesASaveAndLoad(GameTestHelper helper) {
		BlockPos pos = new BlockPos(1, 1, 1);
		DrawerBlockEntity drawer = place(helper, pos, LodestoreBlocks.BASIC_DRAWER);
		drawer.setItem(0, new ItemStack(Items.COBBLESTONE, 12));
		drawer.setChanged();
		drawer.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));

		CompoundTag written = drawer.saveCustomOnly(helper.getLevel().registryAccess());

		DrawerBlockEntity reloaded = new DrawerBlockEntity(
				helper.absolutePos(pos), LodestoreBlocks.BASIC_DRAWER.defaultBlockState());
		reloaded.loadWithComponents(TagValueInput.create(
				ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), written));

		if (reloaded.upgrades().size() != 1 || !reloaded.upgrades().getFirst().is(LodestoreItems.LOCK_UPGRADE)) {
			throw helper.assertionException("the installed upgrade did not survive a save and load, got "
					+ reloaded.upgrades().size() + " upgrade(s)");
		}

		if (!reloaded.lockedItem().is(Items.COBBLESTONE)) {
			throw helper.assertionException("the lock's filter did not survive a save and load, got "
					+ describe(reloaded.lockedItem()));
		}

		helper.succeed();
	}

	/**
	 * Upgrades drop for every tier. The shulker-style tiers carry their contents inside the
	 * dropped item, but upgrades deliberately ride outside the inventory, so that component never
	 * carries them and nothing else would.
	 */
	@GameTest(maxTicks = 100)
	public void upgradesDropWhenTheDrawerIsBroken(GameTestHelper helper) {
		BlockPos pos = new BlockPos(1, 1, 1);
		DrawerBlockEntity drawer = place(helper, pos, LodestoreBlocks.STURDY_DRAWER);
		drawer.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));

		destroy(helper, pos);

		helper.runAtTickTime(5, () -> {
			long dropped = count(drops(helper, pos), LodestoreItems.LOCK_UPGRADE);

			if (dropped != 1) {
				throw helper.assertionException("breaking a sturdy drawer should drop its 1 lock upgrade, dropped "
						+ dropped);
			}

			helper.succeed();
		});
	}

	/** One of each: a second lock is refused rather than silently swallowed. */
	@GameTest
	public void aDrawerRefusesADuplicateUpgrade(GameTestHelper helper) {
		DrawerBlockEntity drawer = place(helper, new BlockPos(1, 1, 1), LodestoreBlocks.BASIC_DRAWER);

		expect(helper, drawer.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE)), true,
				"the first lock upgrade should install");
		expect(helper, drawer.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE)), false,
				"a second lock upgrade should be refused");

		if (drawer.upgrades().size() != 1) {
			throw helper.assertionException("drawer ended up with " + drawer.upgrades().size() + " upgrades");
		}

		helper.succeed();
	}


	// --- voiding -----------------------------------------------------------

	/**
	 * The behaviour the upgrade exists for: a drawer with no room left takes the item anyway and
	 * destroys it, rather than refusing it.
	 *
	 * <p>{@code Container#canPlaceItem} cannot express this — it only answers yes or no — so this
	 * goes through {@code HopperBlockEntity.addItem}, the same entry point a hopper and a dropper
	 * use, and asserts on what comes back rather than on any internal state.
	 */
	@GameTest
	public void aFullVoidingDrawerAcceptsAndDiscards(GameTestHelper helper) {
		DrawerBlockEntity drawer = voidingDrawerFullOf(helper, Items.COBBLESTONE);
		long before = total(drawer, Items.COBBLESTONE);

		ItemStack left = HopperBlockEntity.addItem(new SimpleContainer(1), drawer,
				new ItemStack(Items.COBBLESTONE, 64), Direction.DOWN);

		if (!left.isEmpty()) {
			throw helper.assertionException("a full voiding drawer refused " + left.getCount()
					+ " of the item it is voiding");
		}

		if (total(drawer, Items.COBBLESTONE) != before) {
			throw helper.assertionException("the voided items were stored rather than destroyed: "
					+ before + " before, " + total(drawer, Items.COBBLESTONE) + " after");
		}

		helper.succeed();
	}

	/**
	 * The point of voiding: the machine upstream keeps running. A hopper that backs up is the
	 * failure this upgrade exists to prevent, and a hopper is the only way to test it — the
	 * drawer looking full to {@code isFullContainer} is what makes one give up.
	 */
	@GameTest(maxTicks = 160)
	public void aHopperKeepsDrainingIntoAFullVoidingDrawer(GameTestHelper helper) {
		BlockPos drawerPos = new BlockPos(1, 1, 1);
		DrawerBlockEntity drawer = voidingDrawerFullOf(helper, drawerPos, Items.COBBLESTONE);
		long before = total(drawer, Items.COBBLESTONE);

		HopperBlockEntity hopper = hopperAbove(helper, drawerPos, new ItemStack(Items.COBBLESTONE, 5));

		helper.runAtTickTime(140, () -> {
			if (!hopper.getItem(0).isEmpty()) {
				throw helper.assertionException("the hopper backed up against a voiding drawer, "
						+ hopper.getItem(0).getCount() + " still held");
			}

			if (total(drawer, Items.COBBLESTONE) != before) {
				throw helper.assertionException("the drawer grew past full while voiding: " + before
						+ " before, " + total(drawer, Items.COBBLESTONE) + " after");
			}

			helper.succeed();
		});
	}

	/** The control for the test above: without the upgrade, a full drawer still backs a hopper up. */
	@GameTest(maxTicks = 160)
	public void aFullDrawerWithoutTheUpgradeStillRefuses(GameTestHelper helper) {
		BlockPos drawerPos = new BlockPos(1, 1, 1);
		DrawerBlockEntity drawer = place(helper, drawerPos, LodestoreBlocks.BASIC_DRAWER);
		fillStorage(drawer, Items.COBBLESTONE);

		HopperBlockEntity hopper = hopperAbove(helper, drawerPos, new ItemStack(Items.COBBLESTONE, 5));

		helper.runAtTickTime(140, () -> {
			if (hopper.getItem(0).getCount() != 5) {
				throw helper.assertionException("a full drawer with no void upgrade swallowed "
						+ (5 - hopper.getItem(0).getCount()) + " items");
			}

			helper.succeed();
		});
	}

	/** Voiding is the last resort, not the first: a drawer with room still stores what arrives. */
	@GameTest
	public void aVoidingDrawerStoresWhileItHasRoom(GameTestHelper helper) {
		DrawerBlockEntity drawer = place(helper, new BlockPos(1, 1, 1), LodestoreBlocks.BASIC_DRAWER);
		drawer.setItem(0, new ItemStack(Items.COBBLESTONE, 1));
		drawer.setChanged();
		drawer.installUpgrade(new ItemStack(LodestoreItems.VOID_UPGRADE));

		ItemStack left = HopperBlockEntity.addItem(new SimpleContainer(1), drawer,
				new ItemStack(Items.COBBLESTONE, 32), Direction.DOWN);

		if (!left.isEmpty()) {
			throw helper.assertionException("a drawer with room refused " + left.getCount());
		}

		if (total(drawer, Items.COBBLESTONE) != 33) {
			throw helper.assertionException("a voiding drawer with room should have stored all 33, holds "
					+ total(drawer, Items.COBBLESTONE));
		}

		helper.succeed();
	}

	/**
	 * Voiding only ever applies to the item the drawer is locked to. A misrouted item is refused
	 * and backs the system up, which is recoverable; destroying it would not be.
	 */
	@GameTest
	public void aVoidingDrawerRefusesWhatItIsNotLockedTo(GameTestHelper helper) {
		DrawerBlockEntity drawer = voidingDrawerFullOf(helper, Items.COBBLESTONE);

		ItemStack left = HopperBlockEntity.addItem(new SimpleContainer(1), drawer,
				new ItemStack(Items.DIAMOND, 7), Direction.DOWN);

		if (left.getCount() != 7) {
			throw helper.assertionException("a drawer voiding cobblestone destroyed " + (7 - left.getCount())
					+ " diamonds it was never locked to");
		}

		if (total(drawer, Items.DIAMOND) != 0) {
			throw helper.assertionException("the diamonds ended up inside the drawer");
		}

		helper.succeed();
	}

	/**
	 * Upgrade state is outside the inventory, so {@code PersistenceSelfCheck} does not cover it.
	 * This round-trips the drawer and then exercises the behaviour on the far side, because an
	 * upgrade that reloads as an inert item would still pass a test that only counted upgrades.
	 */
	@GameTest
	public void voidingSurvivesASaveAndLoad(GameTestHelper helper) {
		BlockPos pos = new BlockPos(1, 1, 1);
		DrawerBlockEntity drawer = voidingDrawerFullOf(helper, pos, Items.COBBLESTONE);

		CompoundTag written = drawer.saveCustomOnly(helper.getLevel().registryAccess());

		DrawerBlockEntity reloaded = new DrawerBlockEntity(
				helper.absolutePos(pos), LodestoreBlocks.BASIC_DRAWER.defaultBlockState());
		reloaded.loadWithComponents(TagValueInput.create(
				ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), written));
		// Without a level the upgrade registry is unreachable and every upgrade reads as inert,
		// so the behaviour below would pass for the wrong reason.
		reloaded.setLevel(helper.getLevel());

		if (reloaded.upgrades().size() != 1 || !reloaded.upgrades().getFirst().is(LodestoreItems.VOID_UPGRADE)) {
			throw helper.assertionException("the void upgrade did not survive a save and load, got "
					+ reloaded.upgrades().size() + " upgrade(s)");
		}

		if (!reloaded.lockedItem().is(Items.COBBLESTONE)) {
			throw helper.assertionException("the filter voiding depends on did not survive, got "
					+ describe(reloaded.lockedItem()));
		}

		long before = total(reloaded, Items.COBBLESTONE);
		ItemStack left = HopperBlockEntity.addItem(new SimpleContainer(1), reloaded,
				new ItemStack(Items.COBBLESTONE, 64), Direction.DOWN);

		if (!left.isEmpty() || total(reloaded, Items.COBBLESTONE) != before) {
			throw helper.assertionException("a reloaded voiding drawer stopped voiding");
		}

		helper.succeed();
	}

	/** Lock and void are separate items, so a drawer may carry both. */
	@GameTest
	public void aDrawerTakesBothUpgradesAtOnce(GameTestHelper helper) {
		DrawerBlockEntity drawer = place(helper, new BlockPos(1, 1, 1), LodestoreBlocks.BASIC_DRAWER);

		expect(helper, drawer.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE)), true,
				"the lock upgrade should install");
		expect(helper, drawer.installUpgrade(new ItemStack(LodestoreItems.VOID_UPGRADE)), true,
				"the void upgrade should install alongside a lock");

		if (drawer.upgrades().size() != 2) {
			throw helper.assertionException("expected both upgrades, drawer holds " + drawer.upgrades().size());
		}

		helper.succeed();
	}

	// --- helpers -----------------------------------------------------------

	private static DrawerBlockEntity voidingDrawerFullOf(GameTestHelper helper,
			net.minecraft.world.item.Item item) {
		return voidingDrawerFullOf(helper, new BlockPos(1, 1, 1), item);
	}

	/** A drawer filled to capacity, then given the void upgrade — the state voiding applies in. */
	private static DrawerBlockEntity voidingDrawerFullOf(GameTestHelper helper, BlockPos pos,
			net.minecraft.world.item.Item item) {
		DrawerBlockEntity drawer = place(helper, pos, LodestoreBlocks.BASIC_DRAWER);
		fillStorage(drawer, item);
		drawer.installUpgrade(new ItemStack(LodestoreItems.VOID_UPGRADE));
		return drawer;
	}

	/** Fills every storage slot to the brim, leaving the overflow slot alone. */
	private static void fillStorage(DrawerBlockEntity drawer, net.minecraft.world.item.Item item) {
		int max = new ItemStack(item).getMaxStackSize();

		for (int slot = 0; slot < drawer.storageSize(); slot++) {
			drawer.setItem(slot, new ItemStack(item, max));
		}

		drawer.setChanged();
	}

	/** Counts across the whole container, so anything stranded in the overflow slot shows up. */
	private static long total(DrawerBlockEntity drawer, net.minecraft.world.item.Item item) {
		long found = 0;

		for (int slot = 0; slot < drawer.getContainerSize(); slot++) {
			ItemStack stack = drawer.getItem(slot);

			if (stack.is(item)) {
				found += stack.getCount();
			}
		}

		return found;
	}

	private static HopperBlockEntity hopperAbove(GameTestHelper helper, BlockPos drawerPos, ItemStack load) {
		helper.setBlock(drawerPos.above(), Blocks.HOPPER.defaultBlockState());
		HopperBlockEntity hopper = helper.getBlockEntity(drawerPos.above(), HopperBlockEntity.class);
		hopper.setItem(0, load);
		return hopper;
	}

	private static String describe(ItemStack stack) {
		return stack.isEmpty() ? "nothing" : stack.getHoverName().getString();
	}

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

		if (drawer.storageSize() != expected) {
			throw helper.assertionException(block.getName().getString() + " has "
					+ drawer.storageSize() + " storage slots, expected " + expected);
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
