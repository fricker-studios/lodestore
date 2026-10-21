package dev.fricker.lodestore.testmod;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.AABB;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;

import dev.fricker.lodestore.block.CrateBlockEntity;
import dev.fricker.lodestore.network.StorageNetwork;
import dev.fricker.lodestore.registry.LodestoreBlocks;
import dev.fricker.lodestore.registry.LodestoreItems;
import dev.fricker.lodestore.storage.Compression;
import dev.fricker.lodestore.storage.Disenchanting;

/**
 * The three upgrades that work on what a crate holds: compress, disenchant and repair.
 *
 * <p>Each changes a count, which nothing else in a crate does, so each is checked against a
 * control that must <em>not</em> change: a one-way pathway beside a two-way one, an enchanted bow
 * beside a plain one, a crate without the upgrade beside one with it. And each is checked for
 * where its result lands — with the same kind already there, not beside it — and for what it does
 * to the lock, the void and the network's notion of a home, which the compress upgrade widens to
 * the item's whole family.
 */
public class RefiningUpgradeGameTests {
	private static final BlockPos CRATE = new BlockPos(1, 1, 1);
	private static final int BOW_MAX = 384;

	// --- compress --------------------------------------------------------------------

	/** Nine ingots make a block; the two left over stay ingots, since nothing makes them a block. */
	@GameTest
	public void nineIngotsBecomeABlockAndTheRestStayLoose(GameTestHelper helper) {
		CrateBlockEntity crate = fitted(helper, LodestoreBlocks.BASIC_CRATE, LodestoreItems.COMPRESS_UPGRADE);
		crate.contents().setItem(0, new ItemStack(Items.IRON_INGOT, 20));
		crate.setChanged();

		expectTotals(helper, crate, "after compressing 20 ingots",
				Items.IRON_BLOCK, 2, Items.IRON_INGOT, 2);
		helper.succeed();
	}

	/** Pathways chain: nuggets go up to ingots and those ingots straight on to a block. */
	@GameTest
	public void nuggetsClimbAllTheWayToBlocks(GameTestHelper helper) {
		CrateBlockEntity crate = fitted(helper, LodestoreBlocks.BASIC_CRATE, LodestoreItems.COMPRESS_UPGRADE);
		crate.contents().setItem(0, new ItemStack(Items.IRON_NUGGET, 64));
		crate.contents().setItem(4, new ItemStack(Items.IRON_NUGGET, 17));
		crate.setChanged();

		expectTotals(helper, crate, "after compressing 81 nuggets",
				Items.IRON_BLOCK, 1, Items.IRON_INGOT, 0, Items.IRON_NUGGET, 0);

		List<Item> family = Compression.family(helper.getLevel(), Items.IRON_NUGGET, false);

		if (!family.equals(List.of(Items.IRON_BLOCK, Items.IRON_INGOT, Items.IRON_NUGGET))) {
			throw helper.assertionException("iron's family should be block, ingot, nugget, is " + family);
		}

		helper.succeed();
	}

	/**
	 * Only a pathway that runs both ways is followed. Bones craft into bone meal and never back,
	 * quartz and amethyst into blocks that cannot be uncrafted: each is a choice to destroy the
	 * loose form, and the crate never makes it. The pair: bone meal and wheat, whose blocks craft
	 * back, are compressed in the same crate at the same moment.
	 */
	@GameTest
	public void aOneWayPathwayIsNeverFollowed(GameTestHelper helper) {
		CrateBlockEntity crate = fitted(helper, LodestoreBlocks.BASIC_CRATE, LodestoreItems.COMPRESS_UPGRADE);
		crate.contents().setItem(0, new ItemStack(Items.BONE, 9));
		crate.contents().setItem(1, new ItemStack(Items.QUARTZ, 4));
		crate.contents().setItem(2, new ItemStack(Items.AMETHYST_SHARD, 4));
		crate.contents().setItem(3, new ItemStack(Items.BONE_MEAL, 9));
		crate.contents().setItem(4, new ItemStack(Items.WHEAT, 9));
		crate.setChanged();

		expectTotals(helper, crate, "with one-way and two-way pathways side by side",
				Items.BONE, 9, Items.BONE_MEAL, 0, Items.QUARTZ, 4, Items.QUARTZ_BLOCK, 0,
				Items.AMETHYST_SHARD, 4, Items.AMETHYST_BLOCK, 0,
				Items.BONE_BLOCK, 1, Items.HAY_BLOCK, 1, Items.WHEAT, 0);

		if (Compression.stepUp(helper.getLevel(), Items.BONE, false) != null) {
			throw helper.assertionException("bone must have no step up: bone meal does not craft back into bone");
		}

		helper.succeed();
	}

	/**
	 * Turned on, destructive compression follows the one-way pathways: four quartz and four shards
	 * into their blocks, and bones — which split into bone meal, which pairs with the bone block —
	 * three to a block, with the odd bone left as a bone. The pair is the test above: the same
	 * items, untouched, with the setting off.
	 */
	@GameTest
	public void destructiveCompressionFollowsTheOneWayPathways(GameTestHelper helper) {
		CrateBlockEntity crate = fitted(helper, LodestoreBlocks.BASIC_CRATE, LodestoreItems.COMPRESS_UPGRADE);
		crate.contents().setItem(0, new ItemStack(Items.BONE, 10));
		crate.contents().setItem(1, new ItemStack(Items.QUARTZ, 4));
		crate.contents().setItem(2, new ItemStack(Items.AMETHYST_SHARD, 4));
		crate.setChanged();

		expectTotals(helper, crate, "with the setting off", Items.BONE, 10, Items.QUARTZ, 4, Items.AMETHYST_SHARD, 4);

		expect(helper, crate.toggleDestructiveCompression(), true, "the compress upgrade should take the setting");
		expect(helper, crate.destructiveCompression(), true, "the setting should be on after one toggle");

		expectTotals(helper, crate, "with the setting on",
				Items.BONE_BLOCK, 3, Items.BONE, 1, Items.BONE_MEAL, 0,
				Items.QUARTZ_BLOCK, 1, Items.QUARTZ, 0,
				Items.AMETHYST_BLOCK, 1, Items.AMETHYST_SHARD, 0);

		CrateBlockEntity plain = place(helper, new BlockPos(3, 1, 1), LodestoreBlocks.BASIC_CRATE);
		expect(helper, plain.toggleDestructiveCompression(), false, "a crate without a compress upgrade has nothing to toggle");
		helper.succeed();
	}

	/**
	 * The split shape is narrow on purpose: a log splits into planks, but planks and a crafting
	 * table are not a pair that craft into each other, so logs stay logs and planks stay planks
	 * even destructively. Iron keeps compressing as before, since the reversible table is a subset.
	 */
	@GameTest
	public void destructiveCompressionNeverMakesCraftingTablesOfLogs(GameTestHelper helper) {
		CrateBlockEntity crate = fitted(helper, LodestoreBlocks.BASIC_CRATE, LodestoreItems.COMPRESS_UPGRADE);
		crate.toggleDestructiveCompression();
		crate.contents().setItem(0, new ItemStack(Items.OAK_LOG, 9));
		crate.contents().setItem(1, new ItemStack(Items.OAK_PLANKS, 9));
		crate.contents().setItem(2, new ItemStack(Items.IRON_INGOT, 9));
		crate.setChanged();

		expectTotals(helper, crate, "destructively, with logs and planks",
				Items.OAK_LOG, 9, Items.OAK_PLANKS, 9, Items.CRAFTING_TABLE, 0, Items.OAK_WOOD, 0,
				Items.IRON_BLOCK, 1, Items.IRON_INGOT, 0);
		helper.succeed();
	}

	/**
	 * The setting is the upgrade's: it survives a save and a break, as the upgrade does, and goes
	 * when the upgrade comes out, so a compress upgrade put into another crate starts off safe.
	 * The menu's button is what a right-click on the screen sends.
	 */
	@GameTest(maxTicks = 100)
	public void theDestructiveSettingBelongsToTheUpgrade(GameTestHelper helper) {
		CrateBlockEntity crate = fitted(helper, LodestoreBlocks.STURDY_CRATE, LodestoreItems.COMPRESS_UPGRADE);
		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		AbstractContainerMenu menu = crate.createMenu(1, player.getInventory(), player);

		if (menu == null) {
			throw helper.assertionException("the crate refused to open");
		}

		expect(helper, menu.clickMenuButton(player, dev.fricker.lodestore.menu.CrateMenu.DESTRUCTIVE_BUTTON), true,
				"the button should toggle the setting");
		expect(helper, crate.destructiveCompression(), true, "the button should have turned the setting on");

		CompoundTag written = crate.saveCustomOnly(helper.getLevel().registryAccess());
		CrateBlockEntity reloaded = new CrateBlockEntity(
				helper.absolutePos(CRATE), LodestoreBlocks.STURDY_CRATE.defaultBlockState());
		reloaded.setLevel(helper.getLevel());
		reloaded.loadWithComponents(TagValueInput.create(
				ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), written));
		expect(helper, reloaded.destructiveCompression(), true, "the setting did not survive a save and load");

		helper.getLevel().destroyBlock(helper.absolutePos(CRATE), true, null, 512);

		helper.runAtTickTime(5, () -> {
			ItemStack dropped = helper.getLevel()
					.getEntitiesOfClass(ItemEntity.class, new AABB(helper.absolutePos(CRATE)).inflate(6.0))
					.stream().map(ItemEntity::getItem)
					.filter(stack -> stack.is(LodestoreBlocks.STURDY_CRATE.asItem()))
					.findFirst()
					.orElseThrow(() -> helper.assertionException("the crate did not drop"));

			CrateBlockEntity placed = place(helper, new BlockPos(3, 1, 1), LodestoreBlocks.STURDY_CRATE);
			placed.applyComponentsFromItemStack(dropped);
			placed.setChanged();
			expect(helper, placed.destructiveCompression(), true, "the setting did not survive the crate being broken and placed");

			placed.upgradeContainer().setItem(0, ItemStack.EMPTY);
			expect(helper, placed.destructiveCompression(), false, "taking the compress upgrade out should forget the setting");
			helper.succeed();
		});
	}

	/** A crate locked to bone blocks takes bones only once it may make bone blocks of them. */
	@GameTest
	public void aLockedBoneBlockCrateTakesBonesOnlyDestructively(GameTestHelper helper) {
		CrateBlockEntity crate = place(helper, CRATE, LodestoreBlocks.STURDY_CRATE);
		crate.contents().setItem(0, new ItemStack(Items.BONE_BLOCK, 1));
		crate.setChanged();
		crate.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));
		crate.installUpgrade(new ItemStack(LodestoreItems.COMPRESS_UPGRADE));

		expect(helper, crate.canStore(5, new ItemStack(Items.BONE_MEAL)), true,
				"bone meal and the bone block craft into each other, so the lock must take bone meal");
		expect(helper, crate.canStore(5, new ItemStack(Items.BONE)), false,
				"bones do not craft back, so without the setting the lock must refuse them");

		crate.toggleDestructiveCompression();

		expect(helper, crate.canStore(5, new ItemStack(Items.BONE)), true,
				"with the setting on, bones are what the crate will make bone blocks of");
		expect(helper, crate.canStore(5, new ItemStack(Items.DIRT)), false, "dirt is still dirt");

		List<Item> family = Compression.family(helper.getLevel(), Items.BONE_BLOCK, true);

		if (!family.contains(Items.BONE) || !family.contains(Items.BONE_MEAL) || family.getFirst() != Items.BONE_BLOCK) {
			throw helper.assertionException("the bone block's destructive family should be block, meal and bone, is " + family);
		}

		if (Compression.family(helper.getLevel(), Items.BONE_BLOCK, false).contains(Items.BONE)) {
			throw helper.assertionException("the reversible family must not reach the bone");
		}

		helper.succeed();
	}

	/** A renamed ingot is not the ingot the recipe names, and is left exactly as it is. */
	@GameTest
	public void anItemWithSomethingOnItIsNotCompressed(GameTestHelper helper) {
		CrateBlockEntity crate = fitted(helper, LodestoreBlocks.BASIC_CRATE, LodestoreItems.COMPRESS_UPGRADE);
		ItemStack named = new ItemStack(Items.IRON_INGOT, 9);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("Keepsake"));
		crate.contents().setItem(0, named);
		crate.setChanged();

		expectTotals(helper, crate, "with renamed ingots", Items.IRON_INGOT, 9, Items.IRON_BLOCK, 0);

		if (!crate.contents().visible(0).has(DataComponents.CUSTOM_NAME)) {
			throw helper.assertionException("the ingots lost their name");
		}

		helper.succeed();
	}

	/** A new block goes in with the blocks already there, not into a slot of its own. */
	@GameTest
	public void compressedBlocksJoinTheBlocksAlreadyThere(GameTestHelper helper) {
		CrateBlockEntity crate = fitted(helper, LodestoreBlocks.STURDY_CRATE, LodestoreItems.STACK_UPGRADE,
				LodestoreItems.COMPRESS_UPGRADE);
		crate.contents().setItem(0, new ItemStack(Items.IRON_BLOCK, 10));
		crate.contents().setItem(5, new ItemStack(Items.IRON_INGOT, 18));
		crate.setChanged();

		if (crate.total(0) != 12 || !crate.contents().visible(0).is(Items.IRON_BLOCK)) {
			throw helper.assertionException("the two new blocks should have joined slot 0's ten, slot 0 holds "
					+ crate.total(0) + " " + describe(crate.contents().visible(0)));
		}

		if (!crate.contents().visible(5).isEmpty()) {
			throw helper.assertionException("the ingot slot should be empty, holds " + describe(crate.contents().visible(5)));
		}

		helper.succeed();
	}

	/**
	 * A crate full to the last slot with ingots has nowhere to put the first block — until a slot
	 * of ingots is drained to make one. 27 stacks of 64 is exactly 192 blocks.
	 */
	@GameTest
	public void aCrateFullOfIngotsStillCompresses(GameTestHelper helper) {
		CrateBlockEntity crate = fitted(helper, LodestoreBlocks.BASIC_CRATE, LodestoreItems.COMPRESS_UPGRADE);

		for (int slot = 0; slot < crate.storageSize(); slot++) {
			crate.contents().setItem(slot, new ItemStack(Items.IRON_INGOT, 64));
		}

		crate.setChanged();
		expectTotals(helper, crate, "after compressing a full crate", Items.IRON_BLOCK, 192, Items.IRON_INGOT, 0);
		helper.succeed();
	}

	/**
	 * A crate locked to iron blocks takes nuggets and ingots once it compresses, through both
	 * doors, and still refuses dirt. The pair: the same lock without the upgrade refuses the nugget.
	 */
	@GameTest(maxTicks = 220)
	public void aLockedBlockCrateTakesNuggetsOnceItCompresses(GameTestHelper helper) {
		CrateBlockEntity crate = place(helper, CRATE, LodestoreBlocks.STURDY_CRATE);
		crate.contents().setItem(0, new ItemStack(Items.IRON_BLOCK, 1));
		crate.setChanged();
		crate.installUpgrade(new ItemStack(LodestoreItems.LOCK_UPGRADE));

		expect(helper, crate.canStore(5, new ItemStack(Items.IRON_NUGGET)), false,
				"without a compress upgrade a crate locked to blocks must refuse a nugget");

		crate.installUpgrade(new ItemStack(LodestoreItems.COMPRESS_UPGRADE));

		expect(helper, crate.canStore(5, new ItemStack(Items.IRON_NUGGET)), true,
				"a compressing crate locked to blocks must take a nugget");
		expect(helper, crate.canStore(5, new ItemStack(Items.IRON_INGOT)), true,
				"a compressing crate locked to blocks must take an ingot");
		expect(helper, crate.canStore(5, new ItemStack(Items.DIRT)), false,
				"a compressing crate locked to blocks must still refuse dirt");
		expect(helper, StorageNetwork.isHome(crate, new ItemStack(Items.IRON_NUGGET)), true,
				"a compressing crate of blocks must be the network's home for nuggets");

		// The storage door: a hopper with nuggets and dirt. The nuggets go in and become an
		// ingot; the dirt stays in the hopper.
		helper.setBlock(CRATE.above(), Blocks.HOPPER.defaultBlockState());
		HopperBlockEntity hopper = helper.getBlockEntity(CRATE.above(), HopperBlockEntity.class);
		hopper.setItem(0, new ItemStack(Items.IRON_NUGGET, 9));
		hopper.setItem(1, new ItemStack(Items.DIRT, 3));

		helper.runAtTickTime(200, () -> {
			expectTotals(helper, crate, "after a hopper fed a locked compressing crate",
					Items.IRON_BLOCK, 1, Items.IRON_INGOT, 1, Items.IRON_NUGGET, 0, Items.DIRT, 0);

			if (hopper.getItem(1).getCount() != 3 || !hopper.getItem(0).isEmpty()) {
				throw helper.assertionException("the hopper should hold its 3 dirt and no nuggets, holds "
						+ describe(hopper.getItem(0)) + " and " + describe(hopper.getItem(1)));
			}

			helper.succeed();
		});
	}

	/**
	 * A full voiding crate of iron blocks is as full of iron as it will be, so with a compress
	 * upgrade it voids nuggets as it would blocks. The pair: without one, the nuggets are refused
	 * and nothing is destroyed.
	 */
	@GameTest
	public void aVoidingCrateVoidsTheWholeFamilyOnceItCompresses(GameTestHelper helper) {
		CrateBlockEntity crate = place(helper, CRATE, LodestoreBlocks.STURDY_CRATE);

		for (int slot = 0; slot < crate.storageSize(); slot++) {
			crate.contents().setItem(slot, new ItemStack(Items.IRON_BLOCK, 64));
		}

		crate.setChanged();
		crate.installUpgrade(new ItemStack(LodestoreItems.VOID_UPGRADE));

		ItemStack refused = insert(helper, crate, new ItemStack(Items.IRON_NUGGET, 9));

		if (refused.getCount() != 9) {
			throw helper.assertionException("a voiding crate of blocks without a compress upgrade destroyed "
					+ (9 - refused.getCount()) + " nuggets it does not hold");
		}

		crate.installUpgrade(new ItemStack(LodestoreItems.COMPRESS_UPGRADE));
		ItemStack voided = insert(helper, crate, new ItemStack(Items.IRON_NUGGET, 9));

		if (!voided.isEmpty()) {
			throw helper.assertionException("a voiding, compressing crate of blocks refused " + voided.getCount() + " nuggets");
		}

		expectTotals(helper, crate, "after voiding nuggets", Items.IRON_BLOCK, 64L * crate.storageSize(), Items.IRON_NUGGET, 0);
		helper.succeed();
	}

	// --- disenchant ------------------------------------------------------------------

	/**
	 * An enchanted sword is stripped and the bank grows by what a grindstone would have paid:
	 * between half the enchantment's minimum cost, rounded up, and the whole of it.
	 */
	@GameTest
	public void anEnchantedSwordIsStrippedAndItsWorthBanked(GameTestHelper helper) {
		CrateBlockEntity crate = fitted(helper, LodestoreBlocks.BASIC_CRATE, LodestoreItems.DISENCHANT_UPGRADE);
		ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
		sword.enchant(enchantment(helper, Enchantments.SHARPNESS), 5);
		int worth = Disenchanting.worth(sword);
		crate.contents().setItem(0, sword);
		crate.setChanged();

		ItemStack held = crate.contents().visible(0);

		if (!held.is(Items.DIAMOND_SWORD) || held.isEnchanted()) {
			throw helper.assertionException("the sword should be in slot 0 with its enchantment gone, slot 0 holds "
					+ describe(held) + (held.isEnchanted() ? " (enchanted)" : ""));
		}

		long banked = crate.storedExperience();

		if (banked < Math.ceil(worth / 2.0) || banked > worth) {
			throw helper.assertionException("Sharpness V is worth " + worth + "; the crate banked " + banked);
		}

		helper.succeed();
	}

	/** An enchanted book with nothing left on it is a book, and goes in with the books. */
	@GameTest
	public void aStrippedBookJoinsTheBooks(GameTestHelper helper) {
		CrateBlockEntity crate = fitted(helper, LodestoreBlocks.BASIC_CRATE, LodestoreItems.DISENCHANT_UPGRADE);
		crate.contents().setItem(3, new ItemStack(Items.BOOK, 5));
		ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
		book.enchant(enchantment(helper, Enchantments.UNBREAKING), 1);
		crate.contents().setItem(0, book);
		crate.setChanged();

		if (!crate.contents().visible(0).isEmpty() || crate.total(3) != 6 || !crate.contents().visible(3).is(Items.BOOK)) {
			throw helper.assertionException("the stripped book should have joined slot 3's five; slot 0 holds "
					+ describe(crate.contents().visible(0)) + ", slot 3 holds " + crate.total(3) + " "
					+ describe(crate.contents().visible(3)));
		}

		expectTotals(helper, crate, "after stripping a book", Items.ENCHANTED_BOOK, 0, Items.BOOK, 6);
		helper.succeed();
	}

	/** Curses stay on, as at a grindstone, and an item with only a curse is worth nothing and left alone. */
	@GameTest
	public void aCurseStaysOn(GameTestHelper helper) {
		CrateBlockEntity crate = fitted(helper, LodestoreBlocks.BASIC_CRATE, LodestoreItems.DISENCHANT_UPGRADE);
		ItemStack cursedAndSharp = new ItemStack(Items.IRON_SWORD);
		cursedAndSharp.enchant(enchantment(helper, Enchantments.VANISHING_CURSE), 1);
		cursedAndSharp.enchant(enchantment(helper, Enchantments.SHARPNESS), 1);
		ItemStack onlyCursed = new ItemStack(Items.IRON_HELMET);
		onlyCursed.enchant(enchantment(helper, Enchantments.BINDING_CURSE), 1);
		crate.contents().setItem(0, cursedAndSharp);
		crate.contents().setItem(1, onlyCursed);
		crate.setChanged();

		Holder<Enchantment> vanishing = enchantment(helper, Enchantments.VANISHING_CURSE);
		Holder<Enchantment> sharpness = enchantment(helper, Enchantments.SHARPNESS);
		ItemStack sword = crate.contents().visible(0);

		if (EnchantmentHelper.getEnchantmentsForCrafting(sword).getLevel(vanishing) != 1) {
			throw helper.assertionException("the curse came off the sword");
		}

		if (EnchantmentHelper.getEnchantmentsForCrafting(sword).getLevel(sharpness) != 0) {
			throw helper.assertionException("Sharpness stayed on the cursed sword");
		}

		if (EnchantmentHelper.getEnchantmentsForCrafting(crate.contents().visible(1))
				.getLevel(enchantment(helper, Enchantments.BINDING_CURSE)) != 1) {
			throw helper.assertionException("the curse came off the helmet");
		}

		if (crate.storedExperience() <= 0) {
			throw helper.assertionException("Sharpness should have been worth something");
		}

		helper.succeed();
	}

	/**
	 * Collecting hands every point to the player and empties the bank; the upgrade cannot be
	 * taken out until then, and can afterwards.
	 */
	@GameTest
	public void collectingHandsOverEverythingAndFreesTheUpgrade(GameTestHelper helper) {
		CrateBlockEntity crate = fitted(helper, LodestoreBlocks.BASIC_CRATE, LodestoreItems.DISENCHANT_UPGRADE);
		ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
		sword.enchant(enchantment(helper, Enchantments.SHARPNESS), 5);
		crate.contents().setItem(0, sword);
		crate.setChanged();
		long banked = crate.storedExperience();

		if (banked <= 0) {
			throw helper.assertionException("nothing was banked; the test is set up wrong");
		}

		expect(helper, crate.canRemoveUpgrade(0), false, "the disenchant upgrade must stay while experience is banked");

		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		AbstractContainerMenu menu = crate.createMenu(1, player.getInventory(), player);

		if (menu == null) {
			throw helper.assertionException("the crate refused to open");
		}

		expect(helper, menu.clickMenuButton(player, dev.fricker.lodestore.menu.CrateMenu.COLLECT_BUTTON), true,
				"the collect button should have something to hand over");

		if (player.totalExperience != banked || crate.storedExperience() != 0) {
			throw helper.assertionException("collecting should have given the player " + banked + " points and emptied "
					+ "the bank; the player has " + player.totalExperience + " and the crate " + crate.storedExperience());
		}

		expect(helper, crate.canRemoveUpgrade(0), true, "the disenchant upgrade must be removable once collected");
		expect(helper, crate.collectExperience(player), false, "an empty bank has nothing to collect");
		helper.succeed();
	}

	/** The bank is not inventory, so it needs its own round trip: through a save, and through the item. */
	@GameTest(maxTicks = 100)
	public void bankedExperienceSurvivesASaveAndABreak(GameTestHelper helper) {
		CrateBlockEntity crate = fitted(helper, LodestoreBlocks.STURDY_CRATE, LodestoreItems.DISENCHANT_UPGRADE);
		ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
		sword.enchant(enchantment(helper, Enchantments.SHARPNESS), 5);
		crate.contents().setItem(0, sword);
		crate.setChanged();
		long banked = crate.storedExperience();

		CompoundTag written = crate.saveCustomOnly(helper.getLevel().registryAccess());
		CrateBlockEntity reloaded = new CrateBlockEntity(
				helper.absolutePos(CRATE), LodestoreBlocks.STURDY_CRATE.defaultBlockState());
		reloaded.setLevel(helper.getLevel());
		reloaded.loadWithComponents(TagValueInput.create(
				ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), written));

		if (reloaded.storedExperience() != banked) {
			throw helper.assertionException("the bank did not survive a save and load: " + banked + " became "
					+ reloaded.storedExperience());
		}

		helper.getLevel().destroyBlock(helper.absolutePos(CRATE), true, null, 512);

		helper.runAtTickTime(5, () -> {
			ItemStack dropped = helper.getLevel()
					.getEntitiesOfClass(ItemEntity.class, new AABB(helper.absolutePos(CRATE)).inflate(6.0))
					.stream().map(ItemEntity::getItem)
					.filter(stack -> stack.is(LodestoreBlocks.STURDY_CRATE.asItem()))
					.findFirst()
					.orElseThrow(() -> helper.assertionException("the crate did not drop"));

			CrateBlockEntity placed = place(helper, new BlockPos(3, 1, 1), LodestoreBlocks.STURDY_CRATE);
			placed.applyComponentsFromItemStack(dropped);
			placed.setChanged();

			if (placed.storedExperience() != banked) {
				throw helper.assertionException("the bank did not survive the crate being broken and placed: "
						+ banked + " became " + placed.storedExperience());
			}

			helper.succeed();
		});
	}

	/** The storage door: a hopper's enchanted sword is stripped on arrival, like a hand's. */
	@GameTest(maxTicks = 220)
	public void aHopperFedSwordIsStrippedOnArrival(GameTestHelper helper) {
		CrateBlockEntity crate = fitted(helper, LodestoreBlocks.BASIC_CRATE, LodestoreItems.DISENCHANT_UPGRADE);
		ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
		sword.enchant(enchantment(helper, Enchantments.SHARPNESS), 3);
		helper.setBlock(CRATE.above(), Blocks.HOPPER.defaultBlockState());
		helper.getBlockEntity(CRATE.above(), HopperBlockEntity.class).setItem(0, sword);

		helper.runAtTickTime(200, () -> {
			if (total(crate, Items.DIAMOND_SWORD) != 1) {
				throw helper.assertionException("the hopper did not move the sword in");
			}

			for (int slot = 0; slot < crate.storageSize(); slot++) {
				if (crate.contents().visible(slot).isEnchanted()) {
					throw helper.assertionException("the sword arrived by hopper and kept its enchantment");
				}
			}

			if (crate.storedExperience() <= 0) {
				throw helper.assertionException("nothing was banked for the hopper's sword");
			}

			helper.succeed();
		});
	}

	// --- repair ----------------------------------------------------------------------

	/** Two bows at half health make one at full: 192 and 192, plus five percent, capped. */
	@GameTest
	public void twoHalfBowsBecomeOneFullBow(GameTestHelper helper) {
		CrateBlockEntity crate = fitted(helper, LodestoreBlocks.BASIC_CRATE, LodestoreItems.REPAIR_UPGRADE);
		crate.contents().setItem(0, bow(BOW_MAX / 2));
		crate.contents().setItem(1, bow(BOW_MAX / 2));
		crate.setChanged();

		expectTotals(helper, crate, "after repairing two half bows", Items.BOW, 1);
		expectDamages(helper, crate, Items.BOW, "after repairing two half bows", 0);
		helper.succeed();
	}

	/** Enchanted items are never combined, since combining would strip them. The pair is the test above. */
	@GameTest
	public void enchantedBowsAreLeftAlone(GameTestHelper helper) {
		CrateBlockEntity crate = fitted(helper, LodestoreBlocks.BASIC_CRATE, LodestoreItems.REPAIR_UPGRADE);
		ItemStack first = bow(BOW_MAX / 2);
		first.enchant(enchantment(helper, Enchantments.POWER), 1);
		ItemStack second = bow(BOW_MAX / 2);
		second.enchant(enchantment(helper, Enchantments.POWER), 1);
		crate.contents().setItem(0, first);
		crate.contents().setItem(1, second);
		crate.setChanged();

		expectTotals(helper, crate, "with two enchanted half bows", Items.BOW, 2);
		expectDamages(helper, crate, Items.BOW, "with two enchanted half bows", BOW_MAX / 2, BOW_MAX / 2);
		helper.succeed();
	}

	/**
	 * Pairs are chosen to waste as little as possible. Iron pickaxes at 225, 150, 125 and 100 of
	 * 250: the 125 and 100 fit together under the maximum and make 237; then every pair would
	 * overshoot, so the two most worn — 150 and 225 — make a full one and the 237 is left as it is.
	 * The naive pairing, fullest with whatever is left, would have thrown away a third pickaxe.
	 */
	@GameTest
	public void repairWastesAsLittleAsItCan(GameTestHelper helper) {
		CrateBlockEntity crate = fitted(helper, LodestoreBlocks.BASIC_CRATE, LodestoreItems.REPAIR_UPGRADE);
		int max = new ItemStack(Items.IRON_PICKAXE).getMaxDamage();

		if (max != 250) {
			throw helper.assertionException("this test assumes an iron pickaxe lasts 250, it lasts " + max);
		}

		crate.contents().setItem(0, worn(Items.IRON_PICKAXE, max - 225));
		crate.contents().setItem(1, worn(Items.IRON_PICKAXE, max - 150));
		crate.contents().setItem(2, worn(Items.IRON_PICKAXE, max - 125));
		crate.contents().setItem(3, worn(Items.IRON_PICKAXE, max - 100));
		crate.setChanged();

		expectTotals(helper, crate, "after repairing four pickaxes", Items.IRON_PICKAXE, 2);
		expectDamages(helper, crate, Items.IRON_PICKAXE, "after repairing four pickaxes", 0, max - 237);
		helper.succeed();
	}

	/** Under a stack upgrade the repaired bow joins the full bows already parked in one slot. */
	@GameTest
	public void aRepairedBowJoinsTheFullBows(GameTestHelper helper) {
		CrateBlockEntity crate = fitted(helper, LodestoreBlocks.STURDY_CRATE, LodestoreItems.STACK_UPGRADE,
				LodestoreItems.REPAIR_UPGRADE);
		crate.contents().setItem(0, new ItemStack(Items.BOW));

		try (Transaction transaction = Transaction.openOuter()) {
			if (crate.storage().getSlot(0).insert(ItemVariant.of(Items.BOW), 2, transaction) != 2) {
				throw helper.assertionException("could not park two more bows in slot 0");
			}

			transaction.commit();
		}

		crate.contents().setItem(1, bow(BOW_MAX / 2));
		crate.contents().setItem(2, bow(BOW_MAX / 2));
		crate.setChanged();

		if (crate.total(0) != 4 || !crate.contents().visible(1).isEmpty() || !crate.contents().visible(2).isEmpty()) {
			throw helper.assertionException("the repaired bow should have joined slot 0's three; slot 0 holds "
					+ crate.total(0) + ", slot 1 " + describe(crate.contents().visible(1)) + ", slot 2 "
					+ describe(crate.contents().visible(2)));
		}

		helper.succeed();
	}

	/** Two bows that differ in anything but their damage are two kinds, and are not combined. */
	@GameTest
	public void aNamedBowIsNotCombinedWithAPlainOne(GameTestHelper helper) {
		CrateBlockEntity crate = fitted(helper, LodestoreBlocks.BASIC_CRATE, LodestoreItems.REPAIR_UPGRADE);
		ItemStack named = bow(BOW_MAX / 2);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("Old Faithful"));
		crate.contents().setItem(0, named);
		crate.contents().setItem(1, bow(BOW_MAX / 2));
		crate.setChanged();

		expectTotals(helper, crate, "with a named and a plain half bow", Items.BOW, 2);
		helper.succeed();
	}

	/** Disenchanting runs first, so two enchanted half bows are stripped and then made one. */
	@GameTest
	public void disenchantingFeedsRepair(GameTestHelper helper) {
		CrateBlockEntity crate = fitted(helper, LodestoreBlocks.STURDY_CRATE, LodestoreItems.DISENCHANT_UPGRADE,
				LodestoreItems.REPAIR_UPGRADE);
		ItemStack first = bow(BOW_MAX / 2);
		first.enchant(enchantment(helper, Enchantments.POWER), 1);
		ItemStack second = bow(BOW_MAX / 2);
		second.enchant(enchantment(helper, Enchantments.POWER), 1);
		crate.contents().setItem(0, first);
		crate.contents().setItem(1, second);
		crate.setChanged();

		expectTotals(helper, crate, "with both upgrades", Items.BOW, 1);
		expectDamages(helper, crate, Items.BOW, "with both upgrades", 0);

		if (crate.storedExperience() <= 0) {
			throw helper.assertionException("the two Power enchantments should have been banked");
		}

		helper.succeed();
	}

	// --- the upgrade column ----------------------------------------------------------

	/** Two, four and six: the tier buys upgrade room, and the screen's panel draws two rows of three. */
	@GameTest
	public void tiersOfferTwoFourAndSixUpgradeSlots(GameTestHelper helper) {
		int basic = place(helper, new BlockPos(1, 1, 1), LodestoreBlocks.BASIC_CRATE).upgradeSlots();
		int sturdy = place(helper, new BlockPos(2, 1, 1), LodestoreBlocks.STURDY_CRATE).upgradeSlots();
		int reinforced = place(helper, new BlockPos(3, 1, 1), LodestoreBlocks.REINFORCED_CRATE).upgradeSlots();

		if (basic != 2 || sturdy != 4 || reinforced != 6) {
			throw helper.assertionException("expected 2, 4 and 6 upgrade slots, got " + basic + ", " + sturdy + ", " + reinforced);
		}

		helper.succeed();
	}

	/** A reinforced crate takes one of each shipped upgrade at once, and the column refuses a second of any. */
	@GameTest
	public void aReinforcedCrateTakesOneOfEveryUpgrade(GameTestHelper helper) {
		CrateBlockEntity crate = fitted(helper, LodestoreBlocks.REINFORCED_CRATE, LodestoreItems.LOCK_UPGRADE,
				LodestoreItems.VOID_UPGRADE, LodestoreItems.STACK_UPGRADE_3, LodestoreItems.COMPRESS_UPGRADE,
				LodestoreItems.DISENCHANT_UPGRADE, LodestoreItems.REPAIR_UPGRADE);

		if (crate.upgrades().size() != 6) {
			throw helper.assertionException("expected all six upgrades installed, got " + crate.upgrades().size());
		}

		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		AbstractContainerMenu menu = crate.createMenu(1, player.getInventory(), player);

		if (menu == null) {
			throw helper.assertionException("the crate refused to open");
		}

		// Take the lock out of the column, then ask the column whether it would take a second
		// void upgrade into the freed slot. It would not; it takes a lock, which is not there.
		crate.upgradeContainer().setItem(0, ItemStack.EMPTY);
		expect(helper, menu.getSlot(crate.storageSize()).mayPlace(new ItemStack(LodestoreItems.VOID_UPGRADE)), false,
				"the column took a second void upgrade");
		expect(helper, menu.getSlot(crate.storageSize()).mayPlace(new ItemStack(LodestoreItems.LOCK_UPGRADE)), true,
				"the column refused a lock no slot holds");
		helper.succeed();
	}

	// --- helpers ---------------------------------------------------------------------

	/** A crate at the usual spot with the given upgrades installed, in order. */
	private static CrateBlockEntity fitted(GameTestHelper helper, Block block, Item... upgrades) {
		CrateBlockEntity crate = place(helper, CRATE, block);

		for (Item upgrade : upgrades) {
			if (!crate.installUpgrade(new ItemStack(upgrade))) {
				throw helper.assertionException(new ItemStack(upgrade).getHoverName().getString() + " would not install");
			}
		}

		return crate;
	}

	private static ItemStack bow(int damage) {
		return worn(Items.BOW, damage);
	}

	private static ItemStack worn(Item item, int damage) {
		ItemStack stack = new ItemStack(item);
		stack.setDamageValue(damage);
		return stack;
	}

	private static Holder<Enchantment> enchantment(GameTestHelper helper, ResourceKey<Enchantment> key) {
		return helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
	}

	/** Asserts the total of each item named, in {@code item, count} pairs. */
	private static void expectTotals(GameTestHelper helper, CrateBlockEntity crate, String when, Object... pairs) {
		for (int i = 0; i < pairs.length; i += 2) {
			Item item = (Item) pairs[i];
			long expected = ((Number) pairs[i + 1]).longValue();
			long actual = total(crate, item);

			if (actual != expected) {
				throw helper.assertionException("expected " + expected + " " + new ItemStack(item).getHoverName().getString()
						+ " " + when + ", found " + actual);
			}
		}
	}

	/** Asserts the damage values of every item of a kind, in slot order, parked copies included. */
	private static void expectDamages(GameTestHelper helper, CrateBlockEntity crate, Item item, String when, int... expected) {
		List<Integer> found = new java.util.ArrayList<>();

		for (int slot = 0; slot < crate.storageSize(); slot++) {
			ItemStack held = crate.contents().visible(slot);

			if (held.is(item)) {
				for (long i = 0; i < crate.total(slot); i++) {
					found.add(held.getDamageValue());
				}
			}
		}

		List<Integer> wanted = new java.util.ArrayList<>();

		for (int damage : expected) {
			wanted.add(damage);
		}

		found.sort(null);
		wanted.sort(null);

		if (!found.equals(wanted)) {
			throw helper.assertionException("expected " + new ItemStack(item).getHoverName().getString() + " damages "
					+ wanted + " " + when + ", found " + found);
		}
	}

	private static long total(CrateBlockEntity crate, Item item) {
		long found = 0;

		for (int slot = 0; slot < crate.storageSize(); slot++) {
			if (crate.contents().visible(slot).is(item)) {
				found += crate.contents().total(slot);
			}
		}

		return found;
	}

	/** Inserts through {@code ItemStorage.SIDED}, as a hopper would, and returns what was not accepted. */
	private static ItemStack insert(GameTestHelper helper, CrateBlockEntity crate, ItemStack stack) {
		Storage<ItemVariant> storage = ItemStorage.SIDED.find(helper.getLevel(), crate.getBlockPos(), Direction.UP);

		if (storage == null) {
			throw helper.assertionException("no Storage<ItemVariant> at the crate");
		}

		long accepted;

		try (Transaction transaction = Transaction.openOuter()) {
			accepted = storage.insert(ItemVariant.of(stack), stack.getCount(), transaction);
			transaction.commit();
		}

		return stack.copyWithCount(stack.getCount() - (int) accepted);
	}

	private static String describe(ItemStack stack) {
		return stack.isEmpty() ? "nothing" : stack.getCount() + "x " + stack.getHoverName().getString();
	}

	private static CrateBlockEntity place(GameTestHelper helper, BlockPos pos, Block block) {
		helper.setBlock(pos, block.defaultBlockState());
		return helper.getBlockEntity(pos, CrateBlockEntity.class);
	}

	private static void expect(GameTestHelper helper, boolean actual, boolean expected, String message) {
		if (actual != expected) {
			throw helper.assertionException(message);
		}
	}
}
