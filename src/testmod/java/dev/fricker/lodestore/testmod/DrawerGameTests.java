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
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;

import dev.fricker.lodestore.api.drawer.UpgradeBehaviour;
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
	 * A drawer is not a {@code Container}, and a hopper cannot find one at it.
	 *
	 * <p>This is the premise of the storage layer, pinned: vanilla's hopper reaches a
	 * {@code Container} before it reaches a {@code Storage}, and Fabric's patch takes the
	 * transfer-API route only when {@code getContainerAt} finds nothing. A drawer that became a
	 * container again — by delegation, say — would silently hand every hopper back to arithmetic
	 * that cannot name a slot. The chest alongside proves the probe itself works.
	 */
	@GameTest
	public void aDrawerIsNotAContainer(GameTestHelper helper) {
		BlockPos drawerPos = new BlockPos(1, 1, 1);
		DrawerBlockEntity drawer = place(helper, drawerPos, LodestoreBlocks.BASIC_DRAWER);
		helper.setBlock(new BlockPos(3, 1, 1), Blocks.CHEST.defaultBlockState());

		if (drawer instanceof net.minecraft.world.Container) {
			throw helper.assertionException("the drawer block entity is a Container again; hoppers will "
					+ "bypass its storage — see the README, 'Why the storage layer exists'");
		}

		if (HopperBlockEntity.getContainerAt(helper.getLevel(), helper.absolutePos(drawerPos)) != null) {
			throw helper.assertionException("a hopper can find a Container at the drawer");
		}

		if (HopperBlockEntity.getContainerAt(helper.getLevel(), helper.absolutePos(new BlockPos(3, 1, 1))) == null) {
			throw helper.assertionException("the probe cannot find the chest either; the test is measuring nothing");
		}

		Storage<ItemVariant> storage = ItemStorage.SIDED.find(helper.getLevel(), helper.absolutePos(drawerPos), Direction.UP);

		if (storage != drawer.storage()) {
			throw helper.assertionException("ItemStorage.SIDED does not resolve to the drawer's own storage, got " + storage);
		}

		if (drawer.contents().getContainerSize() != drawer.storageSize()) {
			throw helper.assertionException("the contents are a different size from the storage: "
					+ drawer.contents().getContainerSize() + " against " + drawer.storageSize());
		}

		helper.succeed();
	}

	// --- breaking ----------------------------------------------------------

	/** A basic drawer is a chest: breaking it spills what is inside. */
	@GameTest(maxTicks = 100)
	public void basicDrawerSpillsContentsWhenBroken(GameTestHelper helper) {
		BlockPos pos = new BlockPos(1, 1, 1);
		DrawerBlockEntity drawer = place(helper, pos, LodestoreBlocks.BASIC_DRAWER);
		drawer.contents().setItem(0, new ItemStack(Items.DIAMOND, 5));

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
		drawer.contents().setItem(0, new ItemStack(Items.DIAMOND, 5));

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

		expect(helper, sturdy.canStore(0, sturdyItem), false, "sturdy accepted a sturdy drawer");
		expect(helper, sturdy.canStore(0, reinforcedItem), false, "sturdy accepted a reinforced drawer");
		expect(helper, reinforced.canStore(0, sturdyItem), true, "reinforced rejected a sturdy drawer");
		expect(helper, reinforced.canStore(0, reinforcedItem), false, "reinforced accepted a reinforced drawer");

		// A basic drawer spills when broken, so nesting into it compounds nothing.
		expect(helper, basic.canStore(0, reinforcedItem), true, "basic rejected a drawer");
		expect(helper, sturdy.canStore(0, new ItemStack(Items.DIAMOND)), true, "sturdy rejected a plain item");

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

		// Guard against the check silently covering nothing, which would make it useless. A drawer
		// is no longer a Container, so a check that only knew containers would skip every one.
		if (!PersistenceSelfCheck.covers(LodestoreBlocks.BASIC_DRAWER)) {
			throw helper.assertionException("the self-check does not cover drawers; it is vacuous");
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

		// The stack upgrade: chests around an iron ingot, over a second ingot — the same shape in
		// the block that means "more room".
		ItemStack chest = new ItemStack(Items.CHEST);
		assertCrafts(helper, CraftingInput.of(3, 3, List.of(
				ItemStack.EMPTY, chest, ItemStack.EMPTY,
				chest, iron, chest,
				ItemStack.EMPTY, iron, ItemStack.EMPTY)), "stack_upgrade", LodestoreItems.STACK_UPGRADE);

		// Levels two and three: the previous level in copper, then in diamond, as the lock's are.
		ItemStack copper = new ItemStack(Items.COPPER_INGOT);
		ItemStack diamond = new ItemStack(Items.DIAMOND);
		assertCrafts(helper, CraftingInput.of(3, 3, List.of(
				ItemStack.EMPTY, copper, ItemStack.EMPTY,
				copper, new ItemStack(LodestoreItems.STACK_UPGRADE), copper,
				ItemStack.EMPTY, copper, ItemStack.EMPTY)), "stack_upgrade_2", LodestoreItems.STACK_UPGRADE_2);
		assertCrafts(helper, CraftingInput.of(3, 3, List.of(
				ItemStack.EMPTY, diamond, ItemStack.EMPTY,
				diamond, new ItemStack(LodestoreItems.STACK_UPGRADE_2), diamond,
				ItemStack.EMPTY, diamond, ItemStack.EMPTY)), "stack_upgrade_3", LodestoreItems.STACK_UPGRADE_3);

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
		// Folder per recipe: the drawers are decorations, the upgrades are not.
		Map<String, String> recipes = Map.ofEntries(
				Map.entry("basic_drawer", "decorations"),
				Map.entry("basic_drawer_from_chest", "decorations"),
				Map.entry("sturdy_drawer", "decorations"),
				Map.entry("reinforced_drawer", "decorations"),
				Map.entry("lock_upgrade", "misc"),
				Map.entry("lock_upgrade_2", "misc"),
				Map.entry("lock_upgrade_3", "misc"),
				Map.entry("void_upgrade", "misc"),
				Map.entry("stack_upgrade", "misc"),
				Map.entry("stack_upgrade_2", "misc"),
				Map.entry("stack_upgrade_3", "misc"));

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

		drawer.contents().setItem(0, new ItemStack(Items.REDSTONE, 10));
		drawer.contents().setItem(1, new ItemStack(Items.OAK_LOG, 30));
		drawer.contents().setItem(2, new ItemStack(Items.OAK_LOG, 30));
		drawer.contents().setItem(3, new ItemStack(Items.DIAMOND, 50));
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
		drawer.contents().setItem(0, new ItemStack(Items.COBBLESTONE, 64));
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

		drawer.contents().setItem(0, new ItemStack(Items.COBBLESTONE, 64));
		drawer.setChanged();

		if (signal(helper, drawerPos) <= 0) {
			throw helper.assertionException("drawer holds items but comparator still reads 0");
		}

		// Each slot is measured against its own capacity, and every slot here holds exactly one.
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
		drawer.contents().setItem(0, new ItemStack(Items.COBBLESTONE, 12));
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

		drawer.contents().setItem(0, new ItemStack(Items.REDSTONE, 4));
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
		drawer.contents().setItem(0, new ItemStack(Items.COBBLESTONE, 12));
		drawer.setChanged();

		expect(helper, drawer.canStore(0, new ItemStack(Items.DIRT)), true,
				"an unlocked drawer should take anything; the test proves nothing otherwise");

		drawer.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));

		// Per slot now: slot 0 has claimed cobblestone, so it is slot 0 that refuses.
		expect(helper, drawer.canStore(0, new ItemStack(Items.COBBLESTONE)), true,
				"a claimed slot must still accept what it claimed");
		expect(helper, drawer.canStore(0, new ItemStack(Items.DIRT)), false,
				"a claimed slot must refuse anything else");

		helper.succeed();
	}

	/**
	 * The lock is enforced on the storage's insert for the same reason the nesting rule is: a
	 * filter the chest screen applied alone would be bypassed by piping items in.
	 */
	@GameTest(maxTicks = 200)
	public void aHopperFillsPastALocksReach(GameTestHelper helper) {
		BlockPos drawerPos = new BlockPos(1, 1, 1);
		DrawerBlockEntity drawer = place(helper, drawerPos, LodestoreBlocks.BASIC_DRAWER);
		drawer.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));

		// Fill and claim every slot a level-one lock reaches, leaving slot 9 onwards uncovered.
		for (int slot = 0; slot < 9; slot++) {
			drawer.contents().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
		}

		drawer.setChanged();

		helper.setBlock(drawerPos.above(), Blocks.HOPPER.defaultBlockState());
		HopperBlockEntity hopper = helper.getBlockEntity(drawerPos.above(), HopperBlockEntity.class);
		hopper.setItem(0, new ItemStack(Items.DIRT, 64));

		helper.runAtTickTime(180, () -> {
			if (total(drawer, Items.DIRT) == 0) {
				throw helper.assertionException("dirt should have gone into a slot past the lock's reach");
			}

			for (int slot = 0; slot < 9; slot++) {
				if (drawer.contents().visible(slot).is(Items.DIRT)) {
					throw helper.assertionException("dirt reached slot " + slot + ", which the lock covers");
				}
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
		drawer.contents().setItem(0, new ItemStack(Items.COBBLESTONE, 12));
		drawer.setChanged();
		drawer.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));

		CompoundTag written = drawer.saveCustomOnly(helper.getLevel().registryAccess());

		DrawerBlockEntity reloaded = new DrawerBlockEntity(
				helper.absolutePos(pos), LodestoreBlocks.BASIC_DRAWER.defaultBlockState());
		reloaded.loadWithComponents(TagValueInput.create(
				ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), written));
		// Without a level the upgrade registry is unreachable and every upgrade reads as inert,
		// so the claim below would come back empty for the wrong reason.
		reloaded.setLevel(helper.getLevel());

		if (reloaded.upgrades().size() != 1 || !reloaded.upgrades().getFirst().is(LodestoreItems.LOCK_UPGRADE)) {
			throw helper.assertionException("the installed upgrade did not survive a save and load, got "
					+ reloaded.upgrades().size() + " upgrade(s)");
		}

		if (!reloaded.slotFilter(0).is(Items.COBBLESTONE)) {
			throw helper.assertionException("the slot's claim did not survive a save and load, got "
					+ describe(reloaded.slotFilter(0)));
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
	 * <p>Goes through {@code ItemStorage.SIDED}, the exact lookup Fabric's hopper and dropper
	 * patches make, and asserts on what the storage accepts rather than on any internal state.
	 */
	@GameTest
	public void aFullVoidingDrawerAcceptsAndDiscards(GameTestHelper helper) {
		DrawerBlockEntity drawer = voidingDrawerFullOf(helper, Items.COBBLESTONE);
		long before = total(drawer, Items.COBBLESTONE);

		ItemStack left = insert(helper, drawer, new ItemStack(Items.COBBLESTONE, 64));

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
	 * failure this upgrade exists to prevent, and a real hopper is the only honest way to test it.
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
		drawer.contents().setItem(0, new ItemStack(Items.COBBLESTONE, 1));
		drawer.setChanged();
		drawer.installUpgrade(new ItemStack(LodestoreItems.VOID_UPGRADE));

		ItemStack left = insert(helper, drawer, new ItemStack(Items.COBBLESTONE, 32));

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

		ItemStack left = insert(helper, drawer, new ItemStack(Items.DIAMOND, 7));

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

		if (reloaded.voidedSlots().isEmpty()) {
			throw helper.assertionException("the void upgrade's slot coverage did not survive a reload");
		}

		long before = total(reloaded, Items.COBBLESTONE);
		ItemStack left = insert(reloaded.storage(), new ItemStack(Items.COBBLESTONE, 64));

		if (!left.isEmpty() || total(reloaded, Items.COBBLESTONE) != before) {
			throw helper.assertionException("a reloaded voiding drawer stopped voiding");
		}

		helper.succeed();
	}

	/**
	 * Lock and void are separate items, so a drawer with room may carry both.
	 *
	 * <p>Reinforced, not basic: upgrade slots are per tier now, and a basic drawer has one.
	 */
	@GameTest
	public void aDrawerTakesBothUpgradesAtOnce(GameTestHelper helper) {
		DrawerBlockEntity drawer = place(helper, new BlockPos(1, 1, 1), LodestoreBlocks.REINFORCED_DRAWER);

		expect(helper, drawer.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE)), true,
				"the lock upgrade should install");
		expect(helper, drawer.installUpgrade(new ItemStack(LodestoreItems.VOID_UPGRADE)), true,
				"the void upgrade should install alongside a lock");

		if (drawer.upgrades().size() != 2) {
			throw helper.assertionException("expected both upgrades, drawer holds " + drawer.upgrades().size());
		}

		helper.succeed();
	}

	/** The tier buys upgrade room, so the tiers must actually differ in how much they have. */
	@GameTest
	public void upgradeSlotsRiseWithTheTier(GameTestHelper helper) {
		int basic = place(helper, new BlockPos(1, 1, 1), LodestoreBlocks.BASIC_DRAWER).upgradeSlots();
		int sturdy = place(helper, new BlockPos(2, 1, 1), LodestoreBlocks.STURDY_DRAWER).upgradeSlots();
		int reinforced = place(helper, new BlockPos(3, 1, 1), LodestoreBlocks.REINFORCED_DRAWER).upgradeSlots();

		if (!(basic < sturdy && sturdy < reinforced)) {
			throw helper.assertionException("upgrade slots should rise with the tier, got "
					+ basic + ", " + sturdy + ", " + reinforced);
		}

		helper.succeed();
	}

	/** A drawer refuses an upgrade past its tier's count rather than silently swallowing it. */
	@GameTest
	public void aDrawerRefusesUpgradesPastItsTierCount(GameTestHelper helper) {
		DrawerBlockEntity drawer = place(helper, new BlockPos(1, 1, 1), LodestoreBlocks.BASIC_DRAWER);

		if (drawer.upgradeSlots() != 1) {
			throw helper.assertionException("this test assumes a basic drawer has one upgrade slot, it has "
					+ drawer.upgradeSlots());
		}

		expect(helper, drawer.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE)), true,
				"the first upgrade should fill the basic drawer's only slot");
		expect(helper, drawer.installUpgrade(new ItemStack(LodestoreItems.VOID_UPGRADE)), false,
				"a second upgrade should be refused once the tier's slots are full");

		if (drawer.upgrades().size() != 1) {
			throw helper.assertionException("basic drawer ended up with " + drawer.upgrades().size() + " upgrades");
		}

		helper.succeed();
	}

	// --- slot targeting -----------------------------------------------------

	/**
	 * Capacity pools across installed upgrades, which is the arithmetic a player does in their
	 * head: two level-one locks reach the same two rows one level-two does.
	 */
	@GameTest
	public void lockCapacityPoolsAcrossUpgrades(GameTestHelper helper) {
		DrawerBlockEntity drawer = place(helper, new BlockPos(1, 1, 1), LodestoreBlocks.REINFORCED_DRAWER);

		drawer.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));

		if (drawer.lockCapacity() != 9) {
			throw helper.assertionException("one level-one lock should reach 9 slots, reaches "
					+ drawer.lockCapacity());
		}

		drawer.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));

		if (drawer.lockCapacity() != 18) {
			throw helper.assertionException("two level-one locks should reach 18 slots, reach "
					+ drawer.lockCapacity());
		}

		drawer.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE_2));

		if (drawer.lockCapacity() != 36) {
			throw helper.assertionException("two level-ones plus a level-two should reach 36 slots, reach "
					+ drawer.lockCapacity());
		}

		helper.succeed();
	}

	/**
	 * Choosing nothing covers the first rows, not none of them.
	 *
	 * <p>An upgrade that did nothing until its owner had clicked nine slots would simply look
	 * broken, so an empty selection means "as far as this upgrade reaches, from the start".
	 */
	@GameTest
	public void anUnchosenSelectionCoversTheFirstRows(GameTestHelper helper) {
		DrawerBlockEntity drawer = place(helper, new BlockPos(1, 1, 1), LodestoreBlocks.REINFORCED_DRAWER);
		drawer.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));

		expectCovers(helper, drawer.lockedSlots(), 0, 9);

		DrawerBlockEntity deeper = place(helper, new BlockPos(3, 1, 1), LodestoreBlocks.REINFORCED_DRAWER);
		deeper.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE_2));

		expectCovers(helper, deeper.lockedSlots(), 0, 18);
		helper.succeed();
	}

	/** "Up to": capacity past the end of the drawer covers the drawer, not past it. */
	@GameTest
	public void capacityPastTheDrawerCoversWhatThereIs(GameTestHelper helper) {
		DrawerBlockEntity drawer = place(helper, new BlockPos(1, 1, 1), LodestoreBlocks.BASIC_DRAWER);
		drawer.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE_3));

		if (drawer.lockedSlots().size() != drawer.storageSize()) {
			throw helper.assertionException("a 27-slot lock on a 27-slot drawer should cover all of it, covers "
					+ drawer.lockedSlots().size());
		}

		helper.succeed();
	}

	/** Toggling adds and removes one slot, and refuses to reach past the installed capacity. */
	@GameTest
	public void togglingRespectsCapacity(GameTestHelper helper) {
		DrawerBlockEntity drawer = place(helper, new BlockPos(1, 1, 1), LodestoreBlocks.REINFORCED_DRAWER);
		drawer.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));

		// The first edit seeds from the default, so this removes one of the nine already covered.
		expect(helper, drawer.toggleTarget(UpgradeBehaviour.LOCK, 0), true, "toggling a covered slot should remove it");

		if (drawer.lockedSlots().contains(0) || drawer.lockedSlots().size() != 8) {
			throw helper.assertionException("expected 8 slots after removing one, got "
					+ drawer.lockedSlots().size());
		}

		expect(helper, drawer.toggleTarget(UpgradeBehaviour.LOCK, 20), true, "there is room for another slot");
		expect(helper, drawer.toggleTarget(UpgradeBehaviour.LOCK, 21), false, "a tenth slot is past a level-one lock's reach");

		if (drawer.lockedSlots().size() != 9) {
			throw helper.assertionException("capacity should cap the selection at 9, got "
					+ drawer.lockedSlots().size());
		}

		helper.succeed();
	}

	/** A covered slot claims what lands in it, and then takes nothing else. */
	@GameTest
	public void aCoveredSlotClaimsWhatLandsInIt(GameTestHelper helper) {
		DrawerBlockEntity drawer = place(helper, new BlockPos(1, 1, 1), LodestoreBlocks.BASIC_DRAWER);
		drawer.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));

		expect(helper, drawer.canStore(0, new ItemStack(Items.DIAMOND)), true,
				"a covered slot that has claimed nothing yet must accept anything");

		drawer.contents().setItem(0, new ItemStack(Items.COBBLESTONE, 4));
		drawer.setChanged();

		if (!drawer.slotFilter(0).is(Items.COBBLESTONE)) {
			throw helper.assertionException("slot 0 should have claimed cobblestone, claimed "
					+ describe(drawer.slotFilter(0)));
		}

		expect(helper, drawer.canStore(0, new ItemStack(Items.COBBLESTONE)), true,
				"a claimed slot must still accept what it claimed");
		expect(helper, drawer.canStore(0, new ItemStack(Items.DIAMOND)), false,
				"a claimed slot must refuse anything else");

		helper.succeed();
	}

	/** The lock is per slot: a slot the upgrade does not reach is not locked at all. */
	@GameTest
	public void anUncoveredSlotIsNotLocked(GameTestHelper helper) {
		DrawerBlockEntity drawer = place(helper, new BlockPos(1, 1, 1), LodestoreBlocks.REINFORCED_DRAWER);
		drawer.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));

		drawer.contents().setItem(0, new ItemStack(Items.COBBLESTONE, 4));
		drawer.setChanged();

		expect(helper, drawer.canStore(0, new ItemStack(Items.DIAMOND)), false,
				"slot 0 is covered and has claimed cobblestone");
		expect(helper, drawer.canStore(20, new ItemStack(Items.DIAMOND)), true,
				"slot 20 is past a level-one lock's reach and should take anything");

		helper.succeed();
	}

	/** A hopper is held to the same per-slot rule as the screen. */
	@GameTest(maxTicks = 200)
	public void aHopperCannotInsertPastACoveredSlot(GameTestHelper helper) {
		BlockPos drawerPos = new BlockPos(1, 1, 1);
		DrawerBlockEntity drawer = place(helper, drawerPos, LodestoreBlocks.BASIC_DRAWER);
		drawer.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE_3));

		// Every slot covered and claimed by cobblestone, so there is nowhere for dirt to go.
		fillStorage(drawer, Items.COBBLESTONE);
		drawer.setChanged();

		HopperBlockEntity hopper = hopperAbove(helper, drawerPos, new ItemStack(Items.DIRT, 64));

		helper.runAtTickTime(180, () -> {
			if (total(drawer, Items.DIRT) != 0) {
				throw helper.assertionException("a hopper pushed dirt into a fully claimed drawer");
			}

			if (hopper.getItem(0).getCount() != 64) {
				throw helper.assertionException("the hopper lost dirt it was never allowed to insert");
			}

			helper.succeed();
		});
	}

	/** Targeting and claims are not inventory, so they need their own round trip. */
	@GameTest
	public void targetingSurvivesASaveAndLoad(GameTestHelper helper) {
		BlockPos pos = new BlockPos(1, 1, 1);
		DrawerBlockEntity drawer = place(helper, pos, LodestoreBlocks.REINFORCED_DRAWER);
		drawer.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));
		drawer.contents().setItem(0, new ItemStack(Items.COBBLESTONE, 4));
		drawer.setChanged();
		// The default already fills a level-one lock's nine slots, so one has to go to make room.
		drawer.toggleTarget(UpgradeBehaviour.LOCK, 8);
		drawer.toggleTarget(UpgradeBehaviour.LOCK, 30);

		CompoundTag written = drawer.saveCustomOnly(helper.getLevel().registryAccess());

		DrawerBlockEntity reloaded = new DrawerBlockEntity(
				helper.absolutePos(pos), LodestoreBlocks.REINFORCED_DRAWER.defaultBlockState());
		reloaded.setLevel(helper.getLevel());
		reloaded.loadWithComponents(TagValueInput.create(
				ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), written));

		if (!reloaded.lockedSlots().contains(30)) {
			throw helper.assertionException("the chosen slot did not survive a save and load");
		}

		if (!reloaded.slotFilter(0).is(Items.COBBLESTONE)) {
			throw helper.assertionException("the slot's claim did not survive a save and load, got "
					+ describe(reloaded.slotFilter(0)));
		}

		helper.succeed();
	}

	private static void expectCovers(GameTestHelper helper, java.util.Set<Integer> covered,
			int from, int count) {
		if (covered.size() != count) {
			throw helper.assertionException("expected " + count + " slots covered, got " + covered.size());
		}

		for (int slot = from; slot < from + count; slot++) {
			if (!covered.contains(slot)) {
				throw helper.assertionException("slot " + slot + " should be covered and is not");
			}
		}
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

	/** Fills every storage slot to one full stack. */
	private static void fillStorage(DrawerBlockEntity drawer, net.minecraft.world.item.Item item) {
		int max = new ItemStack(item).getMaxStackSize();

		for (int slot = 0; slot < drawer.storageSize(); slot++) {
			drawer.contents().setItem(slot, new ItemStack(item, max));
		}

		drawer.setChanged();
	}

	/** Counts an item across every slot, parked remainders included. */
	private static long total(DrawerBlockEntity drawer, net.minecraft.world.item.Item item) {
		long found = 0;

		for (int slot = 0; slot < drawer.storageSize(); slot++) {
			if (drawer.contents().visible(slot).is(item)) {
				found += drawer.contents().total(slot);
			}
		}

		return found;
	}

	/**
	 * Inserts through {@code ItemStorage.SIDED} at the drawer's position — the lookup a hopper,
	 * dropper or pipe makes — and returns what was not accepted, in the shape a hopper would hold.
	 */
	private static ItemStack insert(GameTestHelper helper, DrawerBlockEntity drawer, ItemStack stack) {
		Storage<ItemVariant> storage = ItemStorage.SIDED.find(helper.getLevel(), drawer.getBlockPos(), Direction.UP);

		if (storage == null) {
			throw helper.assertionException("no Storage<ItemVariant> at the drawer");
		}

		return insert(storage, stack);
	}

	private static ItemStack insert(Storage<ItemVariant> storage, ItemStack stack) {
		long accepted;

		try (Transaction transaction = Transaction.openOuter()) {
			accepted = storage.insert(ItemVariant.of(stack), stack.getCount(), transaction);
			transaction.commit();
		}

		return stack.copyWithCount(stack.getCount() - (int) accepted);
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
