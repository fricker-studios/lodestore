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
import net.minecraft.tags.TagKey;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;

import dev.fricker.lodestore.block.CrateBlockEntity;
import dev.fricker.lodestore.item.InstalledUpgrades;
import dev.fricker.lodestore.registry.LodestoreBlocks;
import dev.fricker.lodestore.registry.LodestoreComponents;
import dev.fricker.lodestore.registry.LodestoreItems;
import dev.fricker.lodestore.storage.CrateOverflow;
import dev.fricker.lodestore.util.PersistenceSelfCheck;

/**
 * Gametests run inside a real ticking world, which is the only way to exercise hoppers: a plain
 * dedicated server never block-ticks them, so hopper assertions there pass vacuously in both
 * directions.
 */
public class CrateGameTests {
	// --- sizes -------------------------------------------------------------

	@GameTest
	public void tiersHaveTheirIntendedSizes(GameTestHelper helper) {
		expectSize(helper, LodestoreBlocks.BASIC_CRATE, 27);
		expectSize(helper, LodestoreBlocks.STURDY_CRATE, 27);
		expectSize(helper, LodestoreBlocks.REINFORCED_CRATE, 54);
		helper.succeed();
	}

	/**
	 * A crate is not a {@code Container}, and a hopper cannot find one at it.
	 *
	 * <p>This is the premise of the storage layer, pinned: vanilla's hopper reaches a
	 * {@code Container} before it reaches a {@code Storage}, and Fabric's patch takes the
	 * transfer-API route only when {@code getContainerAt} finds nothing. A crate that became a
	 * container again — by delegation, say — would silently hand every hopper back to arithmetic
	 * that cannot name a slot. The chest alongside proves the probe itself works.
	 */
	@GameTest
	public void aCrateIsNotAContainer(GameTestHelper helper) {
		BlockPos cratePos = new BlockPos(1, 1, 1);
		CrateBlockEntity crate = place(helper, cratePos, LodestoreBlocks.BASIC_CRATE);
		helper.setBlock(new BlockPos(3, 1, 1), Blocks.CHEST.defaultBlockState());

		if (crate instanceof net.minecraft.world.Container) {
			throw helper.assertionException("the crate block entity is a Container again; hoppers will "
					+ "bypass its storage — see the README, 'Why the storage layer exists'");
		}

		if (HopperBlockEntity.getContainerAt(helper.getLevel(), helper.absolutePos(cratePos)) != null) {
			throw helper.assertionException("a hopper can find a Container at the crate");
		}

		if (HopperBlockEntity.getContainerAt(helper.getLevel(), helper.absolutePos(new BlockPos(3, 1, 1))) == null) {
			throw helper.assertionException("the probe cannot find the chest either; the test is measuring nothing");
		}

		Storage<ItemVariant> storage = ItemStorage.SIDED.find(helper.getLevel(), helper.absolutePos(cratePos), Direction.UP);

		if (storage != crate.storage()) {
			throw helper.assertionException("ItemStorage.SIDED does not resolve to the crate's own storage, got " + storage);
		}

		if (crate.contents().getContainerSize() != crate.storageSize()) {
			throw helper.assertionException("the contents are a different size from the storage: "
					+ crate.contents().getContainerSize() + " against " + crate.storageSize());
		}

		helper.succeed();
	}

	// --- breaking ----------------------------------------------------------

	/** A basic crate is a chest: breaking it spills what is inside. */
	@GameTest(maxTicks = 100)
	public void basicCrateSpillsContentsWhenBroken(GameTestHelper helper) {
		BlockPos pos = new BlockPos(1, 1, 1);
		CrateBlockEntity crate = place(helper, pos, LodestoreBlocks.BASIC_CRATE);
		crate.contents().setItem(0, new ItemStack(Items.DIAMOND, 5));

		destroy(helper, pos);

		helper.runAtTickTime(5, () -> {
			List<ItemStack> drops = drops(helper, pos);

			if (count(drops, LodestoreBlocks.BASIC_CRATE.asItem()) == 0) {
				throw helper.assertionException("the crate block itself did not drop; test setup is wrong");
			}

			long loose = count(drops, Items.DIAMOND);

			if (loose != 5) {
				throw helper.assertionException("basic crate should spill 5 diamonds, spilled " + loose);
			}

			helper.succeed();
		});
	}

	/** A sturdy crate is a shulker box: contents travel inside the dropped item. */
	@GameTest(maxTicks = 100)
	public void sturdyCrateKeepsContentsWhenBroken(GameTestHelper helper) {
		assertKeepsContents(helper, LodestoreBlocks.STURDY_CRATE);
	}

	@GameTest(maxTicks = 100)
	public void reinforcedCrateKeepsContentsWhenBroken(GameTestHelper helper) {
		assertKeepsContents(helper, LodestoreBlocks.REINFORCED_CRATE);
	}

	private void assertKeepsContents(GameTestHelper helper, Block block) {
		BlockPos pos = new BlockPos(1, 1, 1);
		CrateBlockEntity crate = place(helper, pos, block);
		crate.contents().setItem(0, new ItemStack(Items.DIAMOND, 5));

		destroy(helper, pos);

		helper.runAtTickTime(5, () -> {
			List<ItemStack> drops = drops(helper, pos);
			ItemStack dropped = drops.stream()
					.filter(stack -> stack.is(block.asItem()))
					.findFirst()
					.orElse(ItemStack.EMPTY);

			if (dropped.isEmpty()) {
				throw helper.assertionException("the crate block itself did not drop; test setup is wrong");
			}

			ItemContainerContents contents = dropped.get(DataComponents.CONTAINER);

			if (contents == null) {
				throw helper.assertionException("dropped crate carries no container component");
			}

			long kept = contents.nonEmptyItemCopyStream()
					.filter(stack -> stack.is(Items.DIAMOND))
					.mapToLong(ItemStack::getCount)
					.sum();

			if (kept != 5) {
				throw helper.assertionException("crate should have kept 5 diamonds, kept " + kept);
			}

			// Keeping and spilling would duplicate the contents.
			long loose = count(drops, Items.DIAMOND);

			if (loose != 0) {
				throw helper.assertionException("crate both kept and spilled its contents (" + loose + " loose)");
			}

			helper.succeed();
		});
	}

	// --- nesting -----------------------------------------------------------

	/**
	 * The rank rule applies to a crate with contents, and allows exactly one nesting: a sturdy
	 * crate with contents inside a reinforced one. A basic crate is a chest and refuses every
	 * filled crate, as chests now do; a sturdy crate refuses every filled crate; and a filled basic
	 * crate, which only a command can make, goes nowhere. An empty crate is a block item like any
	 * other and compounds nothing, so every tier takes it.
	 */
	@GameTest
	public void cratesRefuseToNestInsideThemselves(GameTestHelper helper) {
		CrateBlockEntity basic = place(helper, new BlockPos(1, 1, 1), LodestoreBlocks.BASIC_CRATE);
		CrateBlockEntity sturdy = place(helper, new BlockPos(2, 1, 1), LodestoreBlocks.STURDY_CRATE);
		CrateBlockEntity reinforced = place(helper, new BlockPos(3, 1, 1), LodestoreBlocks.REINFORCED_CRATE);

		ItemStack basicItem = filledCrate(LodestoreBlocks.BASIC_CRATE);
		ItemStack sturdyItem = filledCrate(LodestoreBlocks.STURDY_CRATE);
		ItemStack reinforcedItem = filledCrate(LodestoreBlocks.REINFORCED_CRATE);

		for (ItemStack filled : List.of(basicItem, sturdyItem, reinforcedItem)) {
			String name = filled.getHoverName().getString();
			expect(helper, basic.canStore(0, filled), false, "basic accepted a " + name + " with contents");
			expect(helper, sturdy.canStore(0, filled), false, "sturdy accepted a " + name + " with contents");
		}

		expect(helper, reinforced.canStore(0, basicItem), false, "reinforced accepted a basic crate with contents");
		expect(helper, reinforced.canStore(0, sturdyItem), true, "reinforced rejected a sturdy crate with contents");
		expect(helper, reinforced.canStore(0, reinforcedItem), false, "reinforced accepted a reinforced crate with contents");

		expect(helper, basic.canStore(0, new ItemStack(Items.DIAMOND)), true, "basic rejected a plain item");
		expect(helper, sturdy.canStore(0, new ItemStack(Items.DIAMOND)), true, "sturdy rejected a plain item");

		// Empty, every tier takes every tier.
		for (CrateBlockEntity crate : List.of(basic, sturdy, reinforced)) {
			for (Block block : List.of(LodestoreBlocks.BASIC_CRATE, LodestoreBlocks.STURDY_CRATE,
					LodestoreBlocks.REINFORCED_CRATE)) {
				expect(helper, crate.canStore(0, new ItemStack(block)), true,
						crate.tier() + " refused an empty " + block.getName().getString());
			}
		}

		// Parked counts are contents too, even with the container component empty.
		ItemStack parked = new ItemStack(LodestoreBlocks.REINFORCED_CRATE);
		parked.set(LodestoreComponents.CRATE_OVERFLOW,
				new CrateOverflow(List.of(new CrateOverflow.Entry(0, 100))));
		expect(helper, reinforced.canStore(0, parked), false, "reinforced accepted a reinforced crate with parked counts");

		ItemStack parkedSturdy = new ItemStack(LodestoreBlocks.STURDY_CRATE);
		parkedSturdy.set(LodestoreComponents.CRATE_OVERFLOW,
				new CrateOverflow(List.of(new CrateOverflow.Entry(0, 100))));
		expect(helper, basic.canStore(0, parkedSturdy), false, "basic accepted a sturdy crate with parked counts");
		expect(helper, sturdy.canStore(0, parkedSturdy), false, "sturdy accepted a sturdy crate with parked counts");

		helper.succeed();
	}

	/**
	 * A crate with something in it never goes into vanilla storage — chests, double chests,
	 * barrels, shulker boxes, the ender chest, chest minecarts, bundles — whether a hand puts it
	 * there or a hopper does. Its contents nest only where the rank rule allows, inside another
	 * crate. Each refusal is paired with an empty crate going into the same place, so a container
	 * that refused everything would fail here too.
	 */
	@GameTest
	public void filledCratesStayOutOfVanillaStorage(GameTestHelper helper) {
		ItemStack filled = filledCrate(LodestoreBlocks.STURDY_CRATE);
		ItemStack parked = new ItemStack(LodestoreBlocks.REINFORCED_CRATE);
		parked.set(LodestoreComponents.CRATE_OVERFLOW, new CrateOverflow(List.of(new CrateOverflow.Entry(0, 100))));
		ItemStack empty = new ItemStack(LodestoreBlocks.STURDY_CRATE);
		Player player = helper.makeMockPlayer(GameType.SURVIVAL);

		helper.setBlock(new BlockPos(1, 1, 1), Blocks.CHEST.defaultBlockState());
		helper.setBlock(new BlockPos(3, 1, 1), Blocks.CHEST.defaultBlockState());
		helper.setBlock(new BlockPos(5, 1, 1), Blocks.BARREL.defaultBlockState());
		helper.setBlock(new BlockPos(1, 1, 3), Blocks.SHULKER_BOX.defaultBlockState());
		ChestBlockEntity chest = helper.getBlockEntity(new BlockPos(1, 1, 1), ChestBlockEntity.class);
		ChestBlockEntity other = helper.getBlockEntity(new BlockPos(3, 1, 1), ChestBlockEntity.class);
		BarrelBlockEntity barrel = helper.getBlockEntity(new BlockPos(5, 1, 1), BarrelBlockEntity.class);
		ShulkerBoxBlockEntity shulker = helper.getBlockEntity(new BlockPos(1, 1, 3), ShulkerBoxBlockEntity.class);
		MinecartChest minecart = helper.spawn(EntityTypes.CHEST_MINECART, new BlockPos(5, 1, 3));

		Map<String, Container> storage = Map.of(
				"a chest", chest,
				"a double chest", new CompoundContainer(chest, other),
				"a barrel", barrel,
				"a shulker box", shulker,
				"the ender chest", player.getEnderChestInventory(),
				"a chest minecart", minecart);

		for (Map.Entry<String, Container> entry : storage.entrySet()) {
			Container container = entry.getValue();
			int last = container.getContainerSize() - 1;

			expect(helper, container.canPlaceItem(last, filled), false, entry.getKey() + " takes a crate with contents");
			expect(helper, container.canPlaceItem(last, parked), false, entry.getKey() + " takes a crate with parked counts");
			expect(helper, container.canPlaceItem(last, empty), true, entry.getKey() + " refuses an empty crate");

			// A hopper, a dropper or a pipe asks the same question; this is the hopper's own call.
			ItemStack left = HopperBlockEntity.addItem(null, container, filled.copy(), Direction.DOWN);

			if (left.isEmpty()) {
				throw helper.assertionException("a hopper put a crate with contents into " + entry.getKey());
			}

			if (!HopperBlockEntity.addItem(null, container, empty.copy(), Direction.DOWN).isEmpty()) {
				throw helper.assertionException("a hopper could not put an empty crate into " + entry.getKey());
			}
		}

		// A hand, through each screen. The player's own slots in the same screen still take it.
		Map<String, AbstractContainerMenu> screens = Map.of(
				"a chest", chest.createMenu(1, player.getInventory(), player),
				"a double chest", ChestMenu.sixRows(2, player.getInventory(), new CompoundContainer(chest, other)),
				"a barrel", barrel.createMenu(3, player.getInventory(), player),
				"a shulker box", shulker.createMenu(4, player.getInventory(), player),
				"the ender chest", ChestMenu.threeRows(5, player.getInventory(), player.getEnderChestInventory()));

		for (Map.Entry<String, AbstractContainerMenu> entry : screens.entrySet()) {
			AbstractContainerMenu menu = entry.getValue();
			Slot inside = menu.getSlot(0);
			Slot own = menu.getSlot(menu.slots.size() - 1);

			expect(helper, inside.mayPlace(filled), false, "the screen of " + entry.getKey() + " takes a crate with contents");
			expect(helper, inside.mayPlace(empty), true, "the screen of " + entry.getKey() + " refuses an empty crate");
			expect(helper, own.container == player.getInventory() && own.mayPlace(filled), true,
					"the player's own slots in the screen of " + entry.getKey() + " refuse a crate with contents");
		}

		expect(helper, BundleContents.canItemBeInBundle(filled), false, "a bundle takes a crate with contents");
		expect(helper, BundleContents.canItemBeInBundle(empty), true, "a bundle refuses an empty crate");

		helper.succeed();
	}

	/**
	 * The nesting the rank rule does allow loses nothing: a reinforced crate full of sturdy crates,
	 * each full itself, comes back whole from being broken and placed again, and from a save and
	 * load. Every slot's crate is checked down to the stacks inside it.
	 */
	@GameTest(maxTicks = 100)
	public void aReinforcedCrateOfFilledCratesComesBackWhole(GameTestHelper helper) {
		BlockPos pos = new BlockPos(1, 1, 1);
		CrateBlockEntity reinforced = place(helper, pos, LodestoreBlocks.REINFORCED_CRATE);

		for (int slot = 0; slot < reinforced.storageSize(); slot++) {
			ItemStack inner = new ItemStack(LodestoreBlocks.STURDY_CRATE);
			inner.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(innerStacks(slot)));

			if (!reinforced.canStore(slot, inner)) {
				throw helper.assertionException("a reinforced crate refuses a sturdy crate with contents");
			}

			reinforced.contents().setItem(slot, inner);
		}

		reinforced.setChanged();

		CompoundTag written = reinforced.saveCustomOnly(helper.getLevel().registryAccess());
		CrateBlockEntity reloaded = new CrateBlockEntity(
				helper.absolutePos(pos), LodestoreBlocks.REINFORCED_CRATE.defaultBlockState());
		reloaded.loadWithComponents(TagValueInput.create(
				ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), written));
		expectNestedWhole(helper, reloaded, "after a save and load");

		destroy(helper, pos);

		helper.runAtTickTime(5, () -> {
			ItemStack dropped = droppedCrate(helper, drops(helper, pos), LodestoreBlocks.REINFORCED_CRATE);
			expectNestedWhole(helper, placeFrom(helper, new BlockPos(3, 1, 1), dropped), "after being broken and placed");
			helper.succeed();
		});
	}

	/** What the sturdy crate in a given slot holds: 27 stacks, different in every slot. */
	private static List<ItemStack> innerStacks(int slot) {
		List<ItemStack> stacks = new java.util.ArrayList<>();

		for (int i = 0; i < 27; i++) {
			stacks.add(new ItemStack(i % 2 == 0 ? Items.COBBLESTONE : Items.DIAMOND, 1 + (slot + i) % 64));
		}

		return stacks;
	}

	private static void expectNestedWhole(GameTestHelper helper, CrateBlockEntity crate, String when) {
		for (int slot = 0; slot < crate.storageSize(); slot++) {
			ItemStack inner = crate.contents().visible(slot);

			if (!inner.is(LodestoreBlocks.STURDY_CRATE.asItem())) {
				throw helper.assertionException("slot " + slot + " lost its sturdy crate " + when);
			}

			List<ItemStack> held = inner.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY)
					.nonEmptyItemCopyStream().toList();
			List<ItemStack> expected = innerStacks(slot);

			if (!ItemStack.listMatches(held, expected)) {
				throw helper.assertionException("the sturdy crate in slot " + slot + " came back holding "
						+ held.size() + " stacks that differ from the " + expected.size() + " it held, " + when);
			}
		}
	}

	/** A crate item holding five diamonds — what the rank rule is about. */
	private static ItemStack filledCrate(Block block) {
		ItemStack stack = new ItemStack(block);
		stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND, 5))));
		return stack;
	}

	// --- tools ---------------------------------------------------------------

	/**
	 * Crates are wood, so an axe should be the efficient tool, as it is for a chest or barrel. The
	 * reinforced crate is iron-banded as well, so a pickaxe should be one too.
	 *
	 * <p>A tag file in the wrong place is another silent failure: nothing errors, the block is
	 * simply absent from the tag and quietly slow to break.
	 */
	@GameTest
	public void cratesAreMineableWithTheirTools(GameTestHelper helper) {
		for (Block block : List.of(LodestoreBlocks.BASIC_CRATE, LodestoreBlocks.STURDY_CRATE,
				LodestoreBlocks.REINFORCED_CRATE)) {
			expectMineable(helper, block, BlockTags.MINEABLE_WITH_AXE, Items.IRON_AXE);
		}

		expectMineable(helper, LodestoreBlocks.REINFORCED_CRATE, BlockTags.MINEABLE_WITH_PICKAXE, Items.IRON_PICKAXE);

		// The control: a pickaxe tag that took every crate would pass the line above on its own.
		for (Block block : List.of(LodestoreBlocks.BASIC_CRATE, LodestoreBlocks.STURDY_CRATE)) {
			if (block.defaultBlockState().is(BlockTags.MINEABLE_WITH_PICKAXE)) {
				throw helper.assertionException(block.getName().getString()
						+ " is in minecraft:mineable/pickaxe, but only the reinforced crate has iron to it");
			}
		}

		helper.succeed();
	}

	private static void expectMineable(GameTestHelper helper, Block block, TagKey<Block> tag, Item tool) {
		BlockState state = block.defaultBlockState();

		if (!state.is(tag)) {
			throw helper.assertionException(block.getName().getString() + " is not in " + tag.location());
		}

		// The tool must actually be faster than a bare hand, which is the point of the tag.
		float byHand = state.getDestroySpeed(helper.getLevel(), helper.absolutePos(new BlockPos(1, 1, 1)));
		ItemStack toolStack = new ItemStack(tool);
		float toolSpeed = toolStack.getDestroySpeed(state);
		float handSpeed = ItemStack.EMPTY.getDestroySpeed(state);

		if (byHand <= 0) {
			throw helper.assertionException(block.getName().getString() + " is unbreakable");
		}

		if (toolSpeed <= handSpeed) {
			throw helper.assertionException(block.getName().getString() + " is no faster with "
					+ toolStack.getHoverName().getString() + " (" + toolSpeed + " vs " + handSpeed + " by hand)");
		}
	}

	// --- persistence --------------------------------------------------------

	/**
	 * Every Lodestore container must survive being written and read back.
	 *
	 * <p>Crate contents were once lost exactly this way, past sixteen passing tests: none of them
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

		// Guard against the check silently covering nothing, which would make it useless. A crate
		// is no longer a Container, so a check that only knew containers would skip every one.
		if (!PersistenceSelfCheck.covers(LodestoreBlocks.BASIC_CRATE)) {
			throw helper.assertionException("the self-check does not cover crates; it is vacuous");
		}

		helper.succeed();
	}

	// --- recipes ------------------------------------------------------------

	/**
	 * A malformed recipe does not fail the build or throw: it is logged and skipped, and the
	 * recipe simply does not exist. Crafting the inputs is the only way to know it works.
	 */
	@GameTest
	public void bothBasicCrateRecipesCraft(GameTestHelper helper) {
		// Shaped: planks around an iron nugget.
		ItemStack planks = new ItemStack(Items.OAK_PLANKS);
		ItemStack nugget = new ItemStack(Items.IRON_NUGGET);
		assertCrafts(helper, CraftingInput.of(3, 3, List.of(
				planks, planks, planks,
				planks, nugget, planks,
				planks, planks, planks)), "basic_crate", LodestoreBlocks.BASIC_CRATE.asItem());

		// Shapeless: a chest plus an iron nugget.
		assertCrafts(helper, CraftingInput.of(2, 1, List.of(
				new ItemStack(Items.CHEST), nugget)), "basic_crate_from_chest",
				LodestoreBlocks.BASIC_CRATE.asItem());

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

	/**
	 * Crafting a crate up a tier keeps everything in it: the upgrades, which now stay with a crate,
	 * and a sturdy crate's contents, parked counts included. A shaped recipe hands back a fresh item
	 * and would have destroyed all of it; these are vanilla's transmute recipes, which shulker box
	 * dyeing uses for the same reason. The crate does not have to sit in the middle, and the
	 * planks can be any mix.
	 */
	@GameTest
	public void craftingACrateUpATierKeepsWhatIsInIt(GameTestHelper helper) {
		ItemStack basic = new ItemStack(LodestoreBlocks.BASIC_CRATE);
		basic.set(LodestoreComponents.CRATE_UPGRADES, InstalledUpgrades.of(
				List.of(new ItemStack(LodestoreItems.LOCK_UPGRADE)), List.of(new ItemStack(Items.COBBLESTONE)), -1,
				ItemStack.EMPTY));

		ItemStack oak = new ItemStack(Items.OAK_PLANKS);
		ItemStack birch = new ItemStack(Items.BIRCH_PLANKS);
		ItemStack sturdy = assertCrafts(helper, CraftingInput.of(3, 3, List.of(
				basic, oak, birch,
				oak, oak, oak,
				birch, oak, oak)), "sturdy_crate", LodestoreBlocks.STURDY_CRATE.asItem());

		InstalledUpgrades fitted = sturdy.getOrDefault(LodestoreComponents.CRATE_UPGRADES, InstalledUpgrades.EMPTY);

		if (fitted.upgradeStacks().size() != 1 || !fitted.upgradeStacks().getFirst().is(LodestoreItems.LOCK_UPGRADE)
				|| fitted.lockFilterStacks().size() != 1) {
			throw helper.assertionException("crafting a basic crate into a sturdy one lost its lock: " + fitted);
		}

		sturdy.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.COBBLESTONE, 64))));
		sturdy.set(LodestoreComponents.CRATE_OVERFLOW, new CrateOverflow(List.of(new CrateOverflow.Entry(0, 136))));

		ItemStack iron = new ItemStack(Items.IRON_INGOT);
		ItemStack reinforced = assertCrafts(helper, CraftingInput.of(3, 3, List.of(
				iron, iron, iron,
				iron, sturdy, iron,
				iron, iron, iron)), "reinforced_crate", LodestoreBlocks.REINFORCED_CRATE.asItem());

		long stacks = reinforced.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY)
				.nonEmptyItemCopyStream().mapToLong(ItemStack::getCount).sum();
		long parked = reinforced.getOrDefault(LodestoreComponents.CRATE_OVERFLOW, CrateOverflow.EMPTY).total();

		if (stacks != 64 || parked != 136) {
			throw helper.assertionException("crafting a sturdy crate into a reinforced one kept " + stacks
					+ " in stacks and " + parked + " parked, not 64 and 136");
		}

		if (carriedUpgrades(reinforced).size() != 1) {
			throw helper.assertionException("crafting a sturdy crate into a reinforced one lost its lock");
		}

		helper.succeed();
	}

	private static ItemStack assertCrafts(GameTestHelper helper, CraftingInput input, String expectedRecipe,
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

		return result;
	}

	/**
	 * Every recipe ships an advancement that reveals it in the recipe book, and each advancement
	 * grants the recipe it is named for. A malformed advancement is skipped just as quietly as a
	 * malformed recipe, leaving a recipe that works but can never be discovered.
	 */
	@GameTest
	public void everyRecipeHasAnUnlockAdvancement(GameTestHelper helper) {
		// Folder per recipe: the crates are decorations, the upgrades are not.
		Map<String, String> recipes = Map.ofEntries(
				Map.entry("basic_crate", "decorations"),
				Map.entry("basic_crate_from_chest", "decorations"),
				Map.entry("sturdy_crate", "decorations"),
				Map.entry("reinforced_crate", "decorations"),
				Map.entry("lock_upgrade", "misc"),
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
	 * The face shows whichever item the crate holds most of, counted across every slot holding
	 * it — so a kind split over several slots still beats a larger single stack of something else.
	 */
	@GameTest
	public void previewShowsTheMostCommonItem(GameTestHelper helper) {
		CrateBlockEntity crate = place(helper, new BlockPos(1, 1, 1), LodestoreBlocks.BASIC_CRATE);

		crate.contents().setItem(0, new ItemStack(Items.REDSTONE, 10));
		crate.contents().setItem(1, new ItemStack(Items.OAK_LOG, 30));
		crate.contents().setItem(2, new ItemStack(Items.OAK_LOG, 30));
		crate.contents().setItem(3, new ItemStack(Items.DIAMOND, 50));
		crate.setChanged();

		if (!crate.previewItem().is(Items.OAK_LOG)) {
			throw helper.assertionException("preview should be oak log, was "
					+ crate.previewItem().getHoverName().getString());
		}

		if (crate.previewCount() != 60) {
			throw helper.assertionException("preview should count 60 oak logs across two slots, counted "
					+ crate.previewCount());
		}

		helper.succeed();
	}

	@GameTest
	public void emptyCratePreviewsNothing(GameTestHelper helper) {
		CrateBlockEntity crate = place(helper, new BlockPos(1, 1, 1), LodestoreBlocks.BASIC_CRATE);
		crate.setChanged();

		if (!crate.previewItem().isEmpty() || crate.previewCount() != 0) {
			throw helper.assertionException("an empty crate should preview nothing");
		}

		helper.succeed();
	}

	// --- interop -----------------------------------------------------------

	@GameTest(maxTicks = 120)
	public void hopperInsertsIntoCrate(GameTestHelper helper) {
		BlockPos cratePos = new BlockPos(1, 1, 1);
		CrateBlockEntity crate = place(helper, cratePos, LodestoreBlocks.BASIC_CRATE);
		helper.setBlock(cratePos.above(), Blocks.HOPPER.defaultBlockState());

		HopperBlockEntity hopper = helper.getBlockEntity(cratePos.above(), HopperBlockEntity.class);
		hopper.setItem(0, new ItemStack(Items.COBBLESTONE, 64));

		helper.runAtTickTime(100, () -> {
			if (crate.isEmpty()) {
				throw helper.assertionException("hopper moved nothing into the crate");
			}

			helper.succeed();
		});
	}

	@GameTest(maxTicks = 120)
	public void hopperExtractsFromCrate(GameTestHelper helper) {
		BlockPos cratePos = new BlockPos(1, 2, 1);
		CrateBlockEntity crate = place(helper, cratePos, LodestoreBlocks.BASIC_CRATE);
		crate.contents().setItem(0, new ItemStack(Items.COBBLESTONE, 64));
		helper.setBlock(cratePos.below(), Blocks.HOPPER.defaultBlockState());

		helper.runAtTickTime(100, () -> {
			HopperBlockEntity hopper = helper.getBlockEntity(cratePos.below(), HopperBlockEntity.class);

			if (hopper.getItem(0).isEmpty()) {
				throw helper.assertionException("hopper pulled nothing out of the crate");
			}

			helper.succeed();
		});
	}

	@GameTest(maxTicks = 200)
	public void chestThroughHopperIntoCrate(GameTestHelper helper) {
		BlockPos cratePos = new BlockPos(1, 1, 1);
		CrateBlockEntity crate = place(helper, cratePos, LodestoreBlocks.BASIC_CRATE);
		helper.setBlock(cratePos.above(), Blocks.HOPPER.defaultBlockState());
		helper.setBlock(cratePos.above().above(), Blocks.CHEST.defaultBlockState());

		ChestBlockEntity chest = helper.getBlockEntity(cratePos.above().above(), ChestBlockEntity.class);
		chest.setItem(0, new ItemStack(Items.COBBLESTONE, 64));

		helper.runAtTickTime(180, () -> {
			if (crate.isEmpty()) {
				throw helper.assertionException("chest -> hopper -> crate moved nothing");
			}

			helper.succeed();
		});
	}

	@GameTest(maxTicks = 200)
	public void sidewaysHopperInsertsIntoCrate(GameTestHelper helper) {
		BlockPos cratePos = new BlockPos(1, 1, 1);
		CrateBlockEntity crate = place(helper, cratePos, LodestoreBlocks.BASIC_CRATE);
		helper.setBlock(cratePos.north(), Blocks.HOPPER.defaultBlockState()
				.setValue(HopperBlock.FACING, Direction.SOUTH));

		HopperBlockEntity hopper = helper.getBlockEntity(cratePos.north(), HopperBlockEntity.class);
		hopper.setItem(0, new ItemStack(Items.COBBLESTONE, 64));

		helper.runAtTickTime(180, () -> {
			if (crate.isEmpty()) {
				throw helper.assertionException("sideways hopper moved nothing into the crate");
			}

			helper.succeed();
		});
	}

	/** A hopper must not be able to pipe a crate with contents past the nesting rule. */
	@GameTest(maxTicks = 200)
	public void hopperCannotNestSturdyIntoSturdy(GameTestHelper helper) {
		BlockPos cratePos = new BlockPos(1, 1, 1);
		CrateBlockEntity crate = place(helper, cratePos, LodestoreBlocks.STURDY_CRATE);
		helper.setBlock(cratePos.above(), Blocks.HOPPER.defaultBlockState());

		HopperBlockEntity hopper = helper.getBlockEntity(cratePos.above(), HopperBlockEntity.class);
		hopper.setItem(0, filledCrate(LodestoreBlocks.STURDY_CRATE));

		helper.runAtTickTime(180, () -> {
			if (!crate.isEmpty()) {
				throw helper.assertionException("a hopper nested a sturdy crate with contents inside a sturdy crate");
			}

			helper.succeed();
		});
	}

	/** The pair: the same hopper moves an empty sturdy crate in, because empty ones nest anywhere. */
	@GameTest(maxTicks = 200)
	public void hopperNestsAnEmptyCrateAnywhere(GameTestHelper helper) {
		BlockPos cratePos = new BlockPos(1, 1, 1);
		CrateBlockEntity crate = place(helper, cratePos, LodestoreBlocks.STURDY_CRATE);
		helper.setBlock(cratePos.above(), Blocks.HOPPER.defaultBlockState());

		HopperBlockEntity hopper = helper.getBlockEntity(cratePos.above(), HopperBlockEntity.class);
		hopper.setItem(0, new ItemStack(LodestoreBlocks.REINFORCED_CRATE, 4));

		helper.runAtTickTime(180, () -> {
			if (total(crate, LodestoreBlocks.REINFORCED_CRATE.asItem()) == 0) {
				throw helper.assertionException("a hopper could not put an empty reinforced crate into a sturdy crate");
			}

			helper.succeed();
		});
	}

	// --- comparator --------------------------------------------------------

	@GameTest(maxTicks = 200)
	public void fillingCrateRaisesComparatorOutput(GameTestHelper helper) {
		BlockPos cratePos = new BlockPos(1, 1, 1);
		CrateBlockEntity crate = place(helper, cratePos, LodestoreBlocks.BASIC_CRATE);

		if (signal(helper, cratePos) != 0) {
			throw helper.assertionException("empty crate should read 0 on a comparator");
		}

		crate.contents().setItem(0, new ItemStack(Items.COBBLESTONE, 64));
		crate.setChanged();

		if (signal(helper, cratePos) <= 0) {
			throw helper.assertionException("crate holds items but comparator still reads 0");
		}

		// Each slot is measured against its own capacity, and every slot here holds exactly one.
		fillStorage(crate, Items.COBBLESTONE);

		if (signal(helper, cratePos) != 15) {
			throw helper.assertionException("a completely full crate should read 15, reads "
					+ signal(helper, cratePos));
		}

		helper.succeed();
	}

	// --- stacking ----------------------------------------------------------

	/**
	 * A crate that has been placed and broken must stack with one straight from the creative
	 * menu.
	 *
	 * <p>Breaking a container copies its implicit components onto the dropped item, and
	 * {@code BaseContainerBlockEntity} writes {@code minecraft:container} unconditionally — an
	 * empty crate still produces an empty container component. An item carrying that does not
	 * match one that has never had it, so the two sit in separate inventory slots and will not
	 * merge. Vanilla avoids this by giving shulker boxes the same component by default.
	 */
	@GameTest(maxTicks = 100)
	public void brokenBasicCrateStacksWithAFreshOne(GameTestHelper helper) {
		assertStacksWithFresh(helper, LodestoreBlocks.BASIC_CRATE);
	}

	@GameTest(maxTicks = 100)
	public void brokenSturdyCrateStacksWithAFreshOne(GameTestHelper helper) {
		assertStacksWithFresh(helper, LodestoreBlocks.STURDY_CRATE);
	}

	@GameTest(maxTicks = 100)
	public void brokenReinforcedCrateStacksWithAFreshOne(GameTestHelper helper) {
		assertStacksWithFresh(helper, LodestoreBlocks.REINFORCED_CRATE);
	}

	private static void assertStacksWithFresh(GameTestHelper helper, Block block) {
		BlockPos pos = new BlockPos(1, 1, 1);
		place(helper, pos, block);  // placed and broken, never filled
		destroy(helper, pos);

		helper.runAtTickTime(5, () -> {
			ItemStack dropped = drops(helper, pos).stream()
					.filter(stack -> stack.is(block.asItem()))
					.findFirst()
					.orElseThrow(() -> helper.assertionException("the crate did not drop at all"));

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

	/**
	 * A lock put on a crate that already holds things claims all of them, however many kinds —
	 * not only the first, and not only the most numerous.
	 */
	@GameTest
	public void lockClaimsEverythingTheCrateAlreadyHolds(GameTestHelper helper) {
		CrateBlockEntity crate = place(helper, new BlockPos(1, 1, 1), LodestoreBlocks.BASIC_CRATE);
		crate.contents().setItem(0, new ItemStack(Items.COBBLESTONE, 12));
		crate.contents().setItem(14, new ItemStack(Items.DIRT, 3));
		crate.setChanged();

		if (!crate.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE))) {
			throw helper.assertionException("the lock upgrade would not install");
		}

		expectClaims(helper, crate, Items.COBBLESTONE, Items.DIRT);
		helper.succeed();
	}

	/**
	 * A lock on an empty crate has nothing to claim yet, so it takes the first item in, and only
	 * that. Locking to "nothing" would make the crate refuse everything, the one item included.
	 */
	@GameTest
	public void lockOnAnEmptyCrateClaimsTheFirstItemIn(GameTestHelper helper) {
		CrateBlockEntity crate = place(helper, new BlockPos(1, 1, 1), LodestoreBlocks.BASIC_CRATE);
		crate.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));

		if (!crate.lockedItems().isEmpty()) {
			throw helper.assertionException("a lock on an empty crate claimed "
					+ describe(crate.lockedItems().getFirst()) + " out of nowhere");
		}

		expect(helper, crate.canStore(0, new ItemStack(Items.REDSTONE)), true,
				"an empty locked crate must take its first item");

		crate.contents().setItem(0, new ItemStack(Items.REDSTONE, 4));
		crate.setChanged();

		expectClaims(helper, crate, Items.REDSTONE);
		expect(helper, crate.canStore(5, new ItemStack(Items.DIAMOND)), false,
				"once the first item is in, the crate must refuse a second kind");

		helper.succeed();
	}

	/** The lock covers the whole crate: every slot, empty or not, refuses what it did not claim. */
	@GameTest
	public void aLockedCrateRefusesEverythingElse(GameTestHelper helper) {
		CrateBlockEntity crate = place(helper, new BlockPos(1, 1, 1), LodestoreBlocks.BASIC_CRATE);
		crate.contents().setItem(0, new ItemStack(Items.COBBLESTONE, 12));
		crate.setChanged();

		expect(helper, crate.canStore(0, new ItemStack(Items.DIRT)), true,
				"an unlocked crate should take anything; the test proves nothing otherwise");

		crate.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));

		if (crate.lockedSlots().size() != crate.storageSize()) {
			throw helper.assertionException("the lock should cover all " + crate.storageSize()
					+ " slots, covers " + crate.lockedSlots().size());
		}

		for (int slot : new int[] {0, 1, crate.storageSize() - 1}) {
			expect(helper, crate.canStore(slot, new ItemStack(Items.COBBLESTONE)), true,
					"slot " + slot + " must accept what the lock claimed");
			expect(helper, crate.canStore(slot, new ItemStack(Items.DIRT)), false,
					"slot " + slot + " must refuse anything else");
		}

		helper.succeed();
	}

	/**
	 * A claim is on an item, not a stack: a crate locked to bows takes a worn bow and an enchanted
	 * one, through both doors, and still refuses everything that is not a bow.
	 *
	 * <p>The claim used to require the same components, so a crate locked with one bow inside
	 * refused every other bow the player owned. The pair: the dirt, so the lock is still a lock.
	 */
	@GameTest
	public void aLockedCrateTakesTheSameItemWhateverItsComponents(GameTestHelper helper) {
		CrateBlockEntity crate = place(helper, new BlockPos(1, 1, 1), LodestoreBlocks.BASIC_CRATE);
		crate.contents().setItem(0, new ItemStack(Items.BOW));
		crate.setChanged();
		crate.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));
		expectClaims(helper, crate, Items.BOW);

		ItemStack worn = new ItemStack(Items.BOW);
		worn.setDamageValue(100);
		ItemStack enchanted = new ItemStack(Items.BOW);
		enchanted.enchant(helper.getLevel().registryAccess()
				.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.POWER), 3);

		expect(helper, crate.canStore(5, worn), true, "a crate locked to bows must take a worn bow");
		expect(helper, crate.canStore(5, enchanted), true, "a crate locked to bows must take an enchanted bow");
		expect(helper, crate.canStore(5, new ItemStack(Items.DIRT)), false, "a crate locked to bows must still refuse dirt");

		if (!insert(helper, crate, worn).isEmpty() || !insert(helper, crate, enchanted).isEmpty()) {
			throw helper.assertionException("the storage refused a bow the lock should take");
		}

		if (!insert(helper, crate, new ItemStack(Items.DIRT)).is(Items.DIRT)) {
			throw helper.assertionException("the storage took dirt into a crate locked to bows");
		}

		// Three bows that differ in their components are three stacks: the slots keep them apart.
		if (total(crate, Items.BOW) != 3 || crate.contents().visible(1).isEmpty() || crate.contents().visible(2).isEmpty()) {
			throw helper.assertionException("expected three bows in three slots, got " + total(crate, Items.BOW)
					+ " with slots 1 and 2 holding " + describe(crate.contents().visible(1)) + " and "
					+ describe(crate.contents().visible(2)));
		}

		helper.succeed();
	}

	/** Two bows that differ only in their components are one kind to the lock: it claims bows once. */
	@GameTest
	public void theLockClaimsAnItemOnceHoweverItsStacksDiffer(GameTestHelper helper) {
		CrateBlockEntity crate = place(helper, new BlockPos(1, 1, 1), LodestoreBlocks.BASIC_CRATE);
		ItemStack worn = new ItemStack(Items.BOW);
		worn.setDamageValue(100);
		crate.contents().setItem(0, new ItemStack(Items.BOW));
		crate.contents().setItem(1, worn);
		crate.contents().setItem(2, new ItemStack(Items.ARROW, 5));
		crate.setChanged();
		crate.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));

		expectClaims(helper, crate, Items.BOW, Items.ARROW);
		helper.succeed();
	}

	/**
	 * The lock is enforced on the storage's insert for the same reason the nesting rule is: a
	 * filter the chest screen applied alone would be bypassed by piping items in.
	 *
	 * <p>One hopper carries both kinds, so each assertion is the other's control: dirt staying put
	 * would also pass for a crate that refused everything, and cobblestone arriving would also
	 * pass for one that refused nothing.
	 */
	@GameTest(maxTicks = 200)
	public void aHopperIsHeldToTheLock(GameTestHelper helper) {
		BlockPos cratePos = new BlockPos(1, 1, 1);
		CrateBlockEntity crate = place(helper, cratePos, LodestoreBlocks.BASIC_CRATE);
		crate.contents().setItem(0, new ItemStack(Items.COBBLESTONE, 1));
		crate.setChanged();
		crate.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));

		HopperBlockEntity hopper = hopperAbove(helper, cratePos, new ItemStack(Items.DIRT, 64));
		hopper.setItem(1, new ItemStack(Items.COBBLESTONE, 8));

		helper.runAtTickTime(180, () -> {
			if (total(crate, Items.DIRT) != 0) {
				throw helper.assertionException("a hopper pushed dirt past a lock that never claimed it");
			}

			if (hopper.getItem(0).getCount() != 64) {
				throw helper.assertionException("the hopper lost dirt it was never allowed to insert");
			}

			if (total(crate, Items.COBBLESTONE) != 9) {
				throw helper.assertionException("the claimed cobblestone should all have gone in, crate holds "
						+ total(crate, Items.COBBLESTONE));
			}

			helper.succeed();
		});
	}

	/**
	 * "The first item in" has to hold within a single transfer. A pipe may insert several kinds in
	 * one transaction before anything is claimed, and only the first of them may land.
	 */
	@GameTest
	public void oneTransferCannotSlipTwoKindsPastAnEmptyLock(GameTestHelper helper) {
		CrateBlockEntity crate = place(helper, new BlockPos(1, 1, 1), LodestoreBlocks.BASIC_CRATE);
		crate.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));

		long cobblestone;
		long dirt;

		try (Transaction transaction = Transaction.openOuter()) {
			cobblestone = crate.storage().insert(ItemVariant.of(Items.COBBLESTONE), 5, transaction);
			dirt = crate.storage().insert(ItemVariant.of(Items.DIRT), 5, transaction);
			transaction.commit();
		}

		if (cobblestone != 5) {
			throw helper.assertionException("the first kind in should have been taken, took " + cobblestone);
		}

		if (dirt != 0 || total(crate, Items.DIRT) != 0) {
			throw helper.assertionException("a second kind got into an empty locked crate in the same transfer");
		}

		expectClaims(helper, crate, Items.COBBLESTONE);
		helper.succeed();
	}

	/**
	 * A pipe that simulates an insert — opens a transaction, inserts, aborts — must not lock an
	 * empty crate to an item that never arrived. Only a committed insert claims.
	 *
	 * <p>The lock does nothing itself to earn this: the crate's storage reports a change once per
	 * outer transaction, on commit, and the claim is made there. This pins that, so a change to the
	 * storage that reported changes as they happened turns this red rather than quietly locking
	 * crates to phantoms.
	 */
	@GameTest
	public void aSimulatedInsertClaimsNothing(GameTestHelper helper) {
		CrateBlockEntity crate = place(helper, new BlockPos(1, 1, 1), LodestoreBlocks.BASIC_CRATE);
		crate.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));

		try (Transaction transaction = Transaction.openOuter()) {
			crate.storage().insert(ItemVariant.of(Items.DIAMOND), 5, transaction);
			transaction.abort();
		}

		if (!crate.lockedItems().isEmpty()) {
			throw helper.assertionException("an aborted insert locked the crate to "
					+ describe(crate.lockedItems().getFirst()));
		}

		ItemStack left = insert(crate.storage(), new ItemStack(Items.REDSTONE, 5));

		if (!left.isEmpty()) {
			throw helper.assertionException("the real insert after the simulated one left " + left.getCount());
		}

		expectClaims(helper, crate, Items.REDSTONE);
		helper.succeed();
	}

	/** Taking the lock off forgets what it claimed, so a lock put back on claims afresh. */
	@GameTest
	public void removingTheLockForgetsItsClaim(GameTestHelper helper) {
		CrateBlockEntity crate = place(helper, new BlockPos(1, 1, 1), LodestoreBlocks.BASIC_CRATE);
		crate.contents().setItem(0, new ItemStack(Items.COBBLESTONE, 4));
		crate.setChanged();
		crate.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));
		expectClaims(helper, crate, Items.COBBLESTONE);

		crate.upgradeContainer().removeItem(0, 1);

		if (!crate.lockedItems().isEmpty()) {
			throw helper.assertionException("the claim outlived the lock");
		}

		expect(helper, crate.canStore(1, new ItemStack(Items.DIRT)), true,
				"without its lock the crate should take anything again");

		crate.contents().setItem(1, new ItemStack(Items.DIRT, 4));
		crate.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));

		expectClaims(helper, crate, Items.COBBLESTONE, Items.DIRT);
		helper.succeed();
	}

	/**
	 * Upgrades are not inventory, so {@code PersistenceSelfCheck} — which only knows how to fill a
	 * {@code Container} — does not cover them. They get their own round trip here.
	 */
	@GameTest
	public void upgradeStateSurvivesASaveAndLoad(GameTestHelper helper) {
		BlockPos pos = new BlockPos(1, 1, 1);
		CrateBlockEntity crate = place(helper, pos, LodestoreBlocks.BASIC_CRATE);
		crate.contents().setItem(0, new ItemStack(Items.COBBLESTONE, 12));
		crate.setChanged();
		crate.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));

		// Emptied before the save, so the claim can only come back from the claim itself and not
		// be quietly recaptured from the contents.
		crate.contents().setItem(0, ItemStack.EMPTY);
		crate.setChanged();

		CompoundTag written = crate.saveCustomOnly(helper.getLevel().registryAccess());

		CrateBlockEntity reloaded = new CrateBlockEntity(
				helper.absolutePos(pos), LodestoreBlocks.BASIC_CRATE.defaultBlockState());
		reloaded.loadWithComponents(TagValueInput.create(
				ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), written));
		// Without a level the upgrade registry is unreachable and every upgrade reads as inert,
		// so the claim below would come back empty for the wrong reason.
		reloaded.setLevel(helper.getLevel());

		if (reloaded.upgrades().size() != 1 || !reloaded.upgrades().getFirst().is(LodestoreItems.LOCK_UPGRADE)) {
			throw helper.assertionException("the installed upgrade did not survive a save and load, got "
					+ reloaded.upgrades().size() + " upgrade(s)");
		}

		expectClaims(helper, reloaded, Items.COBBLESTONE);
		helper.succeed();
	}

	/**
	 * Upgrades stay in the crate when it is broken, for every tier — the basic one included, whose
	 * contents spill. They ride on the dropped item in their own component, never loose beside it.
	 */
	@GameTest(maxTicks = 100)
	public void upgradesStayInABrokenBasicCrate(GameTestHelper helper) {
		assertUpgradesStay(helper, LodestoreBlocks.BASIC_CRATE);
	}

	@GameTest(maxTicks = 100)
	public void upgradesStayInABrokenSturdyCrate(GameTestHelper helper) {
		assertUpgradesStay(helper, LodestoreBlocks.STURDY_CRATE);
	}

	@GameTest(maxTicks = 100)
	public void upgradesStayInABrokenReinforcedCrate(GameTestHelper helper) {
		assertUpgradesStay(helper, LodestoreBlocks.REINFORCED_CRATE);
	}

	private static void assertUpgradesStay(GameTestHelper helper, Block block) {
		BlockPos pos = new BlockPos(1, 1, 1);
		CrateBlockEntity crate = place(helper, pos, block);
		crate.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));

		destroy(helper, pos);

		helper.runAtTickTime(5, () -> {
			List<ItemStack> drops = drops(helper, pos);
			long loose = count(drops, LodestoreItems.LOCK_UPGRADE);

			if (loose != 0) {
				throw helper.assertionException("breaking a " + block.getName().getString() + " dropped " + loose
						+ " lock upgrade(s) beside it; upgrades should stay in the crate");
			}

			ItemStack dropped = droppedCrate(helper, drops, block);
			List<ItemStack> carried = carriedUpgrades(dropped);

			if (carried.size() != 1 || !carried.getFirst().is(LodestoreItems.LOCK_UPGRADE)) {
				throw helper.assertionException("the dropped " + block.getName().getString()
						+ " should carry its lock upgrade, carries " + carried.size() + " upgrade(s)");
			}

			helper.succeed();
		});
	}

	/**
	 * A broken crate placed back down is the crate it was: its upgrades installed, and its lock
	 * still claiming what it claimed. Breaking is not taking the lock off, so the claim survives —
	 * on a basic crate too, whose contents spilled, exactly as if it had been emptied by hand.
	 */
	@GameTest(maxTicks = 100)
	public void aBrokenBasicCrateComesBackLocked(GameTestHelper helper) {
		assertComesBackLocked(helper, LodestoreBlocks.BASIC_CRATE);
	}

	@GameTest(maxTicks = 100)
	public void aBrokenSturdyCrateComesBackLocked(GameTestHelper helper) {
		assertComesBackLocked(helper, LodestoreBlocks.STURDY_CRATE);
	}

	private static void assertComesBackLocked(GameTestHelper helper, Block block) {
		BlockPos pos = new BlockPos(1, 1, 1);
		CrateBlockEntity crate = place(helper, pos, block);
		crate.contents().setItem(0, new ItemStack(Items.COBBLESTONE, 12));
		crate.setChanged();
		crate.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));
		expectClaims(helper, crate, Items.COBBLESTONE);

		destroy(helper, pos);

		helper.runAtTickTime(5, () -> {
			ItemStack dropped = droppedCrate(helper, drops(helper, pos), block);
			CrateBlockEntity placed = placeFrom(helper, new BlockPos(3, 1, 1), dropped);

			if (placed.upgrades().size() != 1 || !placed.upgrades().getFirst().is(LodestoreItems.LOCK_UPGRADE)) {
				throw helper.assertionException("the placed " + block.getName().getString()
						+ " came back with " + placed.upgrades().size() + " upgrade(s), not its lock");
			}

			expectClaims(helper, placed, Items.COBBLESTONE);
			expect(helper, placed.canStore(1, new ItemStack(Items.DIRT)), false,
					"the placed crate takes dirt; its lock forgot its claim");
			expect(helper, placed.canStore(1, new ItemStack(Items.COBBLESTONE)), true,
					"the placed crate refuses the cobblestone it is locked to");
			helper.succeed();
		});
	}

	/**
	 * Two crates fitted alike must stack, or a player who breaks two locked crates ends up with two
	 * piles of identical items for no visible reason — the component compares by value.
	 */
	@GameTest(maxTicks = 100)
	public void brokenCratesFittedAlikeStack(GameTestHelper helper) {
		BlockPos first = new BlockPos(1, 1, 1);
		BlockPos second = new BlockPos(1, 1, 4);

		for (BlockPos pos : List.of(first, second)) {
			place(helper, pos, LodestoreBlocks.STURDY_CRATE).installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));
			destroy(helper, pos);
		}

		helper.runAtTickTime(5, () -> {
			List<ItemStack> crates = drops(helper, first).stream()
					.filter(stack -> stack.is(LodestoreBlocks.STURDY_CRATE.asItem()))
					.toList();

			if (crates.size() != 2) {
				throw helper.assertionException("expected two dropped crates, found " + crates.size());
			}

			if (!ItemStack.isSameItemSameComponents(crates.get(0), crates.get(1))) {
				throw helper.assertionException("two crates fitted with one lock each will not stack: "
						+ crates.get(0).getComponentsPatch() + " against " + crates.get(1).getComponentsPatch());
			}

			helper.succeed();
		});
	}

	/**
	 * An explosion leaves a basic crate behind rather than destroying it with its upgrades inside.
	 * The loot table used to drop the crate only if it survived the blast, which was harmless while
	 * upgrades popped out loose; now that they stay in the crate it would destroy them. Shulker
	 * boxes have no such condition, for the same reason.
	 *
	 * <p>Asked of the loot table directly with an enormous blast radius, under which the old
	 * condition dropped the crate one time in a thousand.
	 */
	@GameTest
	public void aBasicCrateSurvivesAnExplosion(GameTestHelper helper) {
		BlockPos pos = new BlockPos(1, 1, 1);
		CrateBlockEntity crate = place(helper, pos, LodestoreBlocks.BASIC_CRATE);
		crate.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));

		LootParams.Builder params = new LootParams.Builder(helper.getLevel())
				.withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(helper.absolutePos(pos)))
				.withParameter(LootContextParams.TOOL, ItemStack.EMPTY)
				.withParameter(LootContextParams.BLOCK_ENTITY, crate)
				.withParameter(LootContextParams.EXPLOSION_RADIUS, 1000.0f);

		List<ItemStack> drops = crate.getBlockState().getDrops(params);
		ItemStack dropped = droppedCrate(helper, drops, LodestoreBlocks.BASIC_CRATE);

		if (carriedUpgrades(dropped).size() != 1) {
			throw helper.assertionException("the basic crate survived the blast without its lock upgrade");
		}

		helper.succeed();
	}

	/** One of each: a second lock is refused rather than silently swallowed. */
	@GameTest
	public void aCrateRefusesADuplicateUpgrade(GameTestHelper helper) {
		CrateBlockEntity crate = place(helper, new BlockPos(1, 1, 1), LodestoreBlocks.BASIC_CRATE);

		expect(helper, crate.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE)), true,
				"the first lock upgrade should install");
		expect(helper, crate.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE)), false,
				"a second lock upgrade should be refused");

		if (crate.upgrades().size() != 1) {
			throw helper.assertionException("crate ended up with " + crate.upgrades().size() + " upgrades");
		}

		helper.succeed();
	}


	// --- voiding -----------------------------------------------------------

	/**
	 * The behaviour the upgrade exists for: a crate with no room left takes the item anyway and
	 * destroys it, rather than refusing it.
	 *
	 * <p>Goes through {@code ItemStorage.SIDED}, the exact lookup Fabric's hopper and dropper
	 * patches make, and asserts on what the storage accepts rather than on any internal state.
	 */
	@GameTest
	public void aFullVoidingCrateAcceptsAndDiscards(GameTestHelper helper) {
		CrateBlockEntity crate = voidingCrateFullOf(helper, Items.COBBLESTONE);
		long before = total(crate, Items.COBBLESTONE);

		ItemStack left = insert(helper, crate, new ItemStack(Items.COBBLESTONE, 64));

		if (!left.isEmpty()) {
			throw helper.assertionException("a full voiding crate refused " + left.getCount()
					+ " of the item it is voiding");
		}

		if (total(crate, Items.COBBLESTONE) != before) {
			throw helper.assertionException("the voided items were stored rather than destroyed: "
					+ before + " before, " + total(crate, Items.COBBLESTONE) + " after");
		}

		helper.succeed();
	}

	/**
	 * The point of voiding: the machine upstream keeps running. A hopper that backs up is the
	 * failure this upgrade exists to prevent, and a real hopper is the only honest way to test it.
	 */
	@GameTest(maxTicks = 160)
	public void aHopperKeepsDrainingIntoAFullVoidingCrate(GameTestHelper helper) {
		BlockPos cratePos = new BlockPos(1, 1, 1);
		CrateBlockEntity crate = voidingCrateFullOf(helper, cratePos, Items.COBBLESTONE);
		long before = total(crate, Items.COBBLESTONE);

		HopperBlockEntity hopper = hopperAbove(helper, cratePos, new ItemStack(Items.COBBLESTONE, 5));

		helper.runAtTickTime(140, () -> {
			if (!hopper.getItem(0).isEmpty()) {
				throw helper.assertionException("the hopper backed up against a voiding crate, "
						+ hopper.getItem(0).getCount() + " still held");
			}

			if (total(crate, Items.COBBLESTONE) != before) {
				throw helper.assertionException("the crate grew past full while voiding: " + before
						+ " before, " + total(crate, Items.COBBLESTONE) + " after");
			}

			helper.succeed();
		});
	}

	/** The control for the test above: without the upgrade, a full crate still backs a hopper up. */
	@GameTest(maxTicks = 160)
	public void aFullCrateWithoutTheUpgradeStillRefuses(GameTestHelper helper) {
		BlockPos cratePos = new BlockPos(1, 1, 1);
		CrateBlockEntity crate = place(helper, cratePos, LodestoreBlocks.BASIC_CRATE);
		fillStorage(crate, Items.COBBLESTONE);

		HopperBlockEntity hopper = hopperAbove(helper, cratePos, new ItemStack(Items.COBBLESTONE, 5));

		helper.runAtTickTime(140, () -> {
			if (hopper.getItem(0).getCount() != 5) {
				throw helper.assertionException("a full crate with no void upgrade swallowed "
						+ (5 - hopper.getItem(0).getCount()) + " items");
			}

			helper.succeed();
		});
	}

	/** Voiding is the last resort, not the first: a crate with room still stores what arrives. */
	@GameTest
	public void aVoidingCrateStoresWhileItHasRoom(GameTestHelper helper) {
		CrateBlockEntity crate = place(helper, new BlockPos(1, 1, 1), LodestoreBlocks.BASIC_CRATE);
		crate.contents().setItem(0, new ItemStack(Items.COBBLESTONE, 1));
		crate.setChanged();
		crate.installUpgrade(new ItemStack(LodestoreItems.VOID_UPGRADE));

		ItemStack left = insert(helper, crate, new ItemStack(Items.COBBLESTONE, 32));

		if (!left.isEmpty()) {
			throw helper.assertionException("a crate with room refused " + left.getCount());
		}

		if (total(crate, Items.COBBLESTONE) != 33) {
			throw helper.assertionException("a voiding crate with room should have stored all 33, holds "
					+ total(crate, Items.COBBLESTONE));
		}

		helper.succeed();
	}

	/**
	 * Voiding only ever applies to the item the crate is locked to. A misrouted item is refused
	 * and backs the system up, which is recoverable; destroying it would not be.
	 */
	@GameTest
	public void aVoidingCrateRefusesWhatItIsNotLockedTo(GameTestHelper helper) {
		CrateBlockEntity crate = voidingCrateFullOf(helper, Items.COBBLESTONE);

		ItemStack left = insert(helper, crate, new ItemStack(Items.DIAMOND, 7));

		if (left.getCount() != 7) {
			throw helper.assertionException("a crate voiding cobblestone destroyed " + (7 - left.getCount())
					+ " diamonds it was never locked to");
		}

		if (total(crate, Items.DIAMOND) != 0) {
			throw helper.assertionException("the diamonds ended up inside the crate");
		}

		helper.succeed();
	}

	/**
	 * A crate both locked and voiding destroys the overflow of what it claimed, and still refuses
	 * what it did not — the lock is not loosened by the void, nor the void by the lock.
	 */
	@GameTest
	public void aLockedVoidingCrateVoidsOnlyWhatItClaimed(GameTestHelper helper) {
		CrateBlockEntity crate = place(helper, new BlockPos(1, 1, 1), LodestoreBlocks.STURDY_CRATE);
		fillStorage(crate, Items.COBBLESTONE);
		crate.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));
		crate.installUpgrade(new ItemStack(LodestoreItems.VOID_UPGRADE));
		long before = total(crate, Items.COBBLESTONE);

		ItemStack cobblestone = insert(helper, crate, new ItemStack(Items.COBBLESTONE, 64));

		if (!cobblestone.isEmpty() || total(crate, Items.COBBLESTONE) != before) {
			throw helper.assertionException("a full crate locked to cobblestone stopped voiding it");
		}

		ItemStack diamonds = insert(helper, crate, new ItemStack(Items.DIAMOND, 7));

		if (diamonds.getCount() != 7) {
			throw helper.assertionException("a crate locked to cobblestone destroyed "
					+ (7 - diamonds.getCount()) + " diamonds");
		}

		helper.succeed();
	}

	/**
	 * Upgrade state is outside the inventory, so {@code PersistenceSelfCheck} does not cover it.
	 * This round-trips the crate and then exercises the behaviour on the far side, because an
	 * upgrade that reloads as an inert item would still pass a test that only counted upgrades.
	 */
	@GameTest
	public void voidingSurvivesASaveAndLoad(GameTestHelper helper) {
		BlockPos pos = new BlockPos(1, 1, 1);
		CrateBlockEntity crate = voidingCrateFullOf(helper, pos, Items.COBBLESTONE);

		CompoundTag written = crate.saveCustomOnly(helper.getLevel().registryAccess());

		CrateBlockEntity reloaded = new CrateBlockEntity(
				helper.absolutePos(pos), LodestoreBlocks.BASIC_CRATE.defaultBlockState());
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
			throw helper.assertionException("a reloaded voiding crate stopped voiding");
		}

		helper.succeed();
	}

	/**
	 * Lock and void are separate items, so a crate with room may carry both.
	 *
	 * <p>Reinforced, not basic: upgrade slots are per tier now, and a basic crate has one.
	 */
	@GameTest
	public void aCrateTakesBothUpgradesAtOnce(GameTestHelper helper) {
		CrateBlockEntity crate = place(helper, new BlockPos(1, 1, 1), LodestoreBlocks.REINFORCED_CRATE);

		expect(helper, crate.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE)), true,
				"the lock upgrade should install");
		expect(helper, crate.installUpgrade(new ItemStack(LodestoreItems.VOID_UPGRADE)), true,
				"the void upgrade should install alongside a lock");

		if (crate.upgrades().size() != 2) {
			throw helper.assertionException("expected both upgrades, crate holds " + crate.upgrades().size());
		}

		helper.succeed();
	}

	/** The tier buys upgrade room, so the tiers must actually differ in how much they have. */
	@GameTest
	public void upgradeSlotsRiseWithTheTier(GameTestHelper helper) {
		int basic = place(helper, new BlockPos(1, 1, 1), LodestoreBlocks.BASIC_CRATE).upgradeSlots();
		int sturdy = place(helper, new BlockPos(2, 1, 1), LodestoreBlocks.STURDY_CRATE).upgradeSlots();
		int reinforced = place(helper, new BlockPos(3, 1, 1), LodestoreBlocks.REINFORCED_CRATE).upgradeSlots();

		if (!(basic < sturdy && sturdy < reinforced)) {
			throw helper.assertionException("upgrade slots should rise with the tier, got "
					+ basic + ", " + sturdy + ", " + reinforced);
		}

		helper.succeed();
	}

	/** A crate refuses an upgrade past its tier's count rather than silently swallowing it. */
	@GameTest
	public void aCrateRefusesUpgradesPastItsTierCount(GameTestHelper helper) {
		CrateBlockEntity crate = place(helper, new BlockPos(1, 1, 1), LodestoreBlocks.BASIC_CRATE);

		if (crate.upgradeSlots() != 1) {
			throw helper.assertionException("this test assumes a basic crate has one upgrade slot, it has "
					+ crate.upgradeSlots());
		}

		expect(helper, crate.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE)), true,
				"the first upgrade should fill the basic crate's only slot");
		expect(helper, crate.installUpgrade(new ItemStack(LodestoreItems.VOID_UPGRADE)), false,
				"a second upgrade should be refused once the tier's slots are full");

		if (crate.upgrades().size() != 1) {
			throw helper.assertionException("basic crate ended up with " + crate.upgrades().size() + " upgrades");
		}

		helper.succeed();
	}

	// --- what the face shows -------------------------------------------------

	/**
	 * The slot chosen through the lock decides what the face shows, over whatever there is most
	 * of, and the count is that item's total across the crate rather than the one slot's.
	 */
	@GameTest
	public void theChosenSlotIsWhatTheFaceShows(GameTestHelper helper) {
		CrateBlockEntity crate = place(helper, new BlockPos(1, 1, 1), LodestoreBlocks.BASIC_CRATE);
		crate.contents().setItem(0, new ItemStack(Items.REDSTONE, 10));
		crate.contents().setItem(1, new ItemStack(Items.DIAMOND, 50));
		crate.contents().setItem(2, new ItemStack(Items.REDSTONE, 5));
		crate.setChanged();
		crate.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));

		if (!crate.previewItem().is(Items.DIAMOND)) {
			throw helper.assertionException("with nothing chosen the face should show the diamonds, shows "
					+ describe(crate.previewItem()));
		}

		expect(helper, crate.chooseDisplaySlot(2), true, "a slot holding something should be choosable");

		if (!crate.previewItem().is(Items.REDSTONE) || crate.previewCount() != 15) {
			throw helper.assertionException("the face should show all 15 redstone, shows "
					+ crate.previewCount() + " " + describe(crate.previewItem()));
		}

		expect(helper, crate.chooseDisplaySlot(2), true, "choosing the shown slot again should let go of it");

		if (crate.displaySlot() != -1 || !crate.previewItem().is(Items.DIAMOND)) {
			throw helper.assertionException("letting go should put the most numerous item back on the face");
		}

		helper.succeed();
	}

	/** Only a slot with something in it can be chosen, and only while a lock offers the choice. */
	@GameTest
	public void aDisplaySlotNeedsALockAndAnItem(GameTestHelper helper) {
		CrateBlockEntity crate = place(helper, new BlockPos(1, 1, 1), LodestoreBlocks.BASIC_CRATE);
		crate.contents().setItem(0, new ItemStack(Items.COBBLESTONE, 4));
		crate.setChanged();

		expect(helper, crate.chooseDisplaySlot(0), false, "without a lock there is no choice to make");

		crate.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));

		expect(helper, crate.chooseDisplaySlot(5), false, "an empty slot has nothing to show");
		expect(helper, crate.chooseDisplaySlot(0), true, "a slot holding something should be choosable");

		crate.upgradeContainer().removeItem(0, 1);

		if (crate.displaySlot() != -1) {
			throw helper.assertionException("taking the lock off should forget the slot chosen through it");
		}

		helper.succeed();
	}

	/**
	 * An emptied locked crate goes on naming the item its chosen slot showed — not merely the
	 * first thing it was locked to — and names only that one, rather than cycling the claim.
	 */
	@GameTest
	public void anEmptiedCrateKeepsNamingTheChosenItem(GameTestHelper helper) {
		CrateBlockEntity crate = place(helper, new BlockPos(1, 1, 1), LodestoreBlocks.BASIC_CRATE);
		crate.contents().setItem(0, new ItemStack(Items.COBBLESTONE, 4));
		crate.contents().setItem(1, new ItemStack(Items.DIRT, 4));
		crate.setChanged();
		crate.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));
		crate.chooseDisplaySlot(1);

		crate.contents().setItem(0, ItemStack.EMPTY);
		crate.contents().setItem(1, ItemStack.EMPTY);
		crate.setChanged();

		if (!crate.previewItem().isEmpty()) {
			throw helper.assertionException("an empty crate should have no preview, has "
					+ describe(crate.previewItem()));
		}

		if (!crate.ghostItem().is(Items.DIRT)) {
			throw helper.assertionException("the emptied face should still name dirt, names "
					+ describe(crate.ghostItem()));
		}

		helper.succeed();
	}

	/** The claim and the display choice are not inventory, so they need their own round trip. */
	@GameTest
	public void theDisplayChoiceSurvivesASaveAndLoad(GameTestHelper helper) {
		BlockPos pos = new BlockPos(1, 1, 1);
		CrateBlockEntity crate = place(helper, pos, LodestoreBlocks.REINFORCED_CRATE);
		crate.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));
		crate.contents().setItem(30, new ItemStack(Items.COBBLESTONE, 4));
		crate.setChanged();
		crate.chooseDisplaySlot(30);

		CompoundTag written = crate.saveCustomOnly(helper.getLevel().registryAccess());

		CrateBlockEntity reloaded = new CrateBlockEntity(
				helper.absolutePos(pos), LodestoreBlocks.REINFORCED_CRATE.defaultBlockState());
		reloaded.setLevel(helper.getLevel());
		reloaded.loadWithComponents(TagValueInput.create(
				ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), written));

		if (reloaded.displaySlot() != 30) {
			throw helper.assertionException("the slot chosen for the face did not survive a save and load, got "
					+ reloaded.displaySlot());
		}

		expectClaims(helper, reloaded, Items.COBBLESTONE);
		helper.succeed();
	}

	private static void expectClaims(GameTestHelper helper, CrateBlockEntity crate,
			net.minecraft.world.item.Item... expected) {
		List<ItemStack> claimed = crate.lockedItems();

		if (claimed.size() != expected.length) {
			throw helper.assertionException("expected the lock to claim " + expected.length + " kind(s), it claims "
					+ claimed.stream().map(CrateGameTests::describe).toList());
		}

		for (net.minecraft.world.item.Item item : expected) {
			if (claimed.stream().noneMatch(stack -> stack.is(item))) {
				throw helper.assertionException("the lock should have claimed "
						+ describe(new ItemStack(item)) + ", it claims "
						+ claimed.stream().map(CrateGameTests::describe).toList());
			}
		}
	}


	// --- helpers -----------------------------------------------------------

	private static CrateBlockEntity voidingCrateFullOf(GameTestHelper helper,
			net.minecraft.world.item.Item item) {
		return voidingCrateFullOf(helper, new BlockPos(1, 1, 1), item);
	}

	/** A crate filled to capacity, then given the void upgrade — the state voiding applies in. */
	private static CrateBlockEntity voidingCrateFullOf(GameTestHelper helper, BlockPos pos,
			net.minecraft.world.item.Item item) {
		CrateBlockEntity crate = place(helper, pos, LodestoreBlocks.BASIC_CRATE);
		fillStorage(crate, item);
		crate.installUpgrade(new ItemStack(LodestoreItems.VOID_UPGRADE));
		return crate;
	}

	/** Fills every storage slot to one full stack. */
	private static void fillStorage(CrateBlockEntity crate, net.minecraft.world.item.Item item) {
		int max = new ItemStack(item).getMaxStackSize();

		for (int slot = 0; slot < crate.storageSize(); slot++) {
			crate.contents().setItem(slot, new ItemStack(item, max));
		}

		crate.setChanged();
	}

	/** Counts an item across every slot, parked remainders included. */
	private static long total(CrateBlockEntity crate, net.minecraft.world.item.Item item) {
		long found = 0;

		for (int slot = 0; slot < crate.storageSize(); slot++) {
			if (crate.contents().visible(slot).is(item)) {
				found += crate.contents().total(slot);
			}
		}

		return found;
	}

	/**
	 * Inserts through {@code ItemStorage.SIDED} at the crate's position — the lookup a hopper,
	 * dropper or pipe makes — and returns what was not accepted, in the shape a hopper would hold.
	 */
	private static ItemStack insert(GameTestHelper helper, CrateBlockEntity crate, ItemStack stack) {
		Storage<ItemVariant> storage = ItemStorage.SIDED.find(helper.getLevel(), crate.getBlockPos(), Direction.UP);

		if (storage == null) {
			throw helper.assertionException("no Storage<ItemVariant> at the crate");
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

	private static HopperBlockEntity hopperAbove(GameTestHelper helper, BlockPos cratePos, ItemStack load) {
		helper.setBlock(cratePos.above(), Blocks.HOPPER.defaultBlockState());
		HopperBlockEntity hopper = helper.getBlockEntity(cratePos.above(), HopperBlockEntity.class);
		hopper.setItem(0, load);
		return hopper;
	}

	private static String describe(ItemStack stack) {
		return stack.isEmpty() ? "nothing" : stack.getHoverName().getString();
	}

	private static CrateBlockEntity place(GameTestHelper helper, BlockPos pos, Block block) {
		helper.setBlock(pos, block.defaultBlockState());
		return helper.getBlockEntity(pos, CrateBlockEntity.class);
	}

	private static void destroy(GameTestHelper helper, BlockPos pos) {
		// helper.destroyBlock does not run the loot pipeline, so nothing would drop at all.
		helper.getLevel().destroyBlock(helper.absolutePos(pos), true, null, 512);
	}

	/** Places a crate item the way {@code BlockItem#place} does: the block, then the item's components. */
	private static CrateBlockEntity placeFrom(GameTestHelper helper, BlockPos pos, ItemStack stack) {
		Block block = ((net.minecraft.world.item.BlockItem) stack.getItem()).getBlock();
		CrateBlockEntity crate = place(helper, pos, block);
		crate.applyComponentsFromItemStack(stack);
		crate.setChanged();
		return crate;
	}

	private static ItemStack droppedCrate(GameTestHelper helper, List<ItemStack> drops, Block block) {
		return drops.stream()
				.filter(stack -> stack.is(block.asItem()))
				.findFirst()
				.orElseThrow(() -> helper.assertionException("the " + block.getName().getString() + " did not drop at all"));
	}

	private static List<ItemStack> carriedUpgrades(ItemStack crate) {
		return crate.getOrDefault(LodestoreComponents.CRATE_UPGRADES, InstalledUpgrades.EMPTY).upgradeStacks();
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
		CrateBlockEntity crate = place(helper, new BlockPos(1, 1, 1), block);

		if (crate.storageSize() != expected) {
			throw helper.assertionException(block.getName().getString() + " has "
					+ crate.storageSize() + " storage slots, expected " + expected);
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
