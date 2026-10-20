package dev.fricker.lodestore.storage;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/**
 * The pathways the compress upgrade follows: which item crafts up into which, and how many it takes.
 *
 * <p>Read off the recipes rather than listed by hand, and only where the pathway runs both ways.
 * Nine iron ingots craft a block and the block crafts back into nine ingots, so a crate may
 * compress ingots freely: nothing is lost, and the player can always have the ingots back. Nine
 * bone meal and a bone block are the same. But a bone crafts into bone meal and never back, four
 * quartz become a block that cannot be uncrafted, and so do four amethyst shards — each of those
 * is a decision to destroy the loose form, and a crate must not make it on the player's behalf.
 * Requiring the return recipe is what draws that line, for every item in the game and in every
 * other mod, without a list to keep.
 *
 * <p>Pathways chain: nuggets go to ingots, and the ingots then go to blocks, so a crate full of
 * nuggets ends up holding blocks. {@link #top} is where a chain ends; two items whose chains end in
 * the same place are one <em>family</em>, which is what the lock, the void and the network's notion
 * of a home work in once a compress upgrade is installed: a crate locked to iron blocks takes
 * nuggets and ingots, because that is what it will turn them into.
 *
 * <p>Probing is done through {@code RecipeManager#getRecipeFor} with a grid of the one item —
 * the same lookup a crafting table makes — and then with the result alone, for the way back. That
 * stays on public API, so recipe internals can move without this noticing, and it is exactly how
 * the game itself would answer "what does a grid of nine of these make". It costs a scan of every
 * crafting recipe, so the answer is cached per item until the recipes change.
 */
public final class Compression {
	/**
	 * One rung of a pathway: {@code count} of {@code from} craft into one {@code to}, and one
	 * {@code to} crafts back into {@code count} of {@code from}.
	 */
	public record Step(Item from, int count, Item to) {
	}

	/** Grids to try, densest first: a 3×3 of nine, then a 2×2 of four. */
	private static final int[] SIDES = {3, 2};
	/** How many rungs a chain may have; nugget to ingot to block is two, and nothing sane is deeper. */
	private static final int MAX_CHAIN = 8;

	/** Up and down one rung, per item. Identity-keyed: items are registry singletons. */
	private static final Map<Item, Optional<Step>> UP = new IdentityHashMap<>();
	private static final Map<Item, Optional<Step>> DOWN = new IdentityHashMap<>();

	private Compression() {
	}

	/** Forgets every cached pathway whenever the recipes may have changed. */
	public static void initialize() {
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> clear());
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> clear());
	}

	static synchronized void clear() {
		UP.clear();
		DOWN.clear();
	}

	/** The rung above {@code item}, or null if nothing crafts from a grid of it and back again. */
	public static synchronized @Nullable Step stepUp(ServerLevel level, Item item) {
		return UP.computeIfAbsent(item, key -> Optional.ofNullable(probeUp(level, key))).orElse(null);
	}

	/** The rung below {@code item}: what it uncrafts into, if that crafts back into it. */
	public static synchronized @Nullable Step stepDown(ServerLevel level, Item item) {
		return DOWN.computeIfAbsent(item, key -> Optional.ofNullable(probeDown(level, key))).orElse(null);
	}

	/** Where {@code item}'s chain ends: itself, if nothing crafts up from it. */
	public static Item top(ServerLevel level, Item item) {
		Item at = item;

		for (int rung = 0; rung < MAX_CHAIN; rung++) {
			Step up = stepUp(level, at);

			if (up == null) {
				break;
			}

			at = up.to();
		}

		return at;
	}

	/** Whether two items lie on one chain — whatever is on either stack beyond the item. */
	public static boolean sameFamily(ServerLevel level, ItemStack a, ItemStack b) {
		if (ItemStack.isSameItem(a, b)) {
			return true;
		}

		return top(level, a.getItem()) == top(level, b.getItem());
	}

	/**
	 * Every item on {@code item}'s chain, densest first: the block, then the ingot, then the
	 * nugget. Just the item itself when it is on no chain.
	 */
	public static List<Item> family(ServerLevel level, Item item) {
		List<Item> chain = new ArrayList<>();
		Item at = top(level, item);

		for (int rung = 0; rung < MAX_CHAIN && at != null && !chain.contains(at); rung++) {
			chain.add(at);
			Step down = stepDown(level, at);
			at = down == null ? null : down.from();
		}

		return Collections.unmodifiableList(chain);
	}

	/** A full grid of the item crafts into one plain item, which crafts back into the full grid. */
	private static @Nullable Step probeUp(ServerLevel level, Item item) {
		RecipeManager recipes = level.getServer().getRecipeManager();

		for (int side : SIDES) {
			int count = side * side;
			CraftingInput grid = CraftingInput.of(side, side, Collections.nCopies(count, new ItemStack(item)));
			ItemStack dense = craft(recipes, level, grid);

			if (dense.isEmpty() || dense.getCount() != 1 || !dense.getComponentsPatch().isEmpty() || dense.is(item)) {
				continue;
			}

			if (uncraftsInto(recipes, level, dense.getItem(), item, count)) {
				return new Step(item, count, dense.getItem());
			}
		}

		return null;
	}

	/** The item alone crafts into a grid's worth of one plain item, which crafts back into it. */
	private static @Nullable Step probeDown(ServerLevel level, Item item) {
		RecipeManager recipes = level.getServer().getRecipeManager();
		ItemStack loose = craft(recipes, level, CraftingInput.of(1, 1, List.of(new ItemStack(item))));

		if (loose.isEmpty() || !loose.getComponentsPatch().isEmpty() || loose.is(item)) {
			return null;
		}

		for (int side : SIDES) {
			int count = side * side;

			if (loose.getCount() != count) {
				continue;
			}

			CraftingInput grid = CraftingInput.of(side, side, Collections.nCopies(count, loose.copyWithCount(1)));
			ItemStack back = craft(recipes, level, grid);

			if (back.is(item) && back.getCount() == 1 && back.getComponentsPatch().isEmpty()) {
				return new Step(loose.getItem(), count, item);
			}
		}

		return null;
	}

	private static boolean uncraftsInto(RecipeManager recipes, ServerLevel level, Item dense, Item loose, int count) {
		ItemStack back = craft(recipes, level, CraftingInput.of(1, 1, List.of(new ItemStack(dense))));
		return back.is(loose) && back.getCount() == count && back.getComponentsPatch().isEmpty();
	}

	/** What a crafting table would make of {@code input}; empty if nothing. */
	private static ItemStack craft(RecipeManager recipes, ServerLevel level, CraftingInput input) {
		Optional<RecipeHolder<CraftingRecipe>> match = recipes.getRecipeFor(RecipeType.CRAFTING, input, level);
		return match.map(holder -> holder.value().assemble(input)).orElse(ItemStack.EMPTY);
	}
}
