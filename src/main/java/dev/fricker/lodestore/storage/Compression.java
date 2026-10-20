package dev.fricker.lodestore.storage;

import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/**
 * The pathways the compress upgrade follows: which item crafts up into which, and how many it takes.
 *
 * <p>Read off the recipes rather than listed by hand, in two tables. The <em>reversible</em> table
 * holds only pathways that run both ways: nine iron ingots craft a block and the block crafts back
 * into nine ingots, so a crate may compress ingots freely — nothing is lost, and the player can
 * always have the ingots back. Nine bone meal and a bone block are the same. But a bone crafts into
 * bone meal and never back, four quartz become a block that cannot be uncrafted, and so do four
 * amethyst shards — each of those is a decision to destroy the loose form, and a crate must not make
 * it on the player's behalf. Requiring the return recipe is what draws that line, for every item in
 * the game and in every other mod, without a list to keep.
 *
 * <p>The <em>destructive</em> table is that line crossed on purpose: a right-click on the installed
 * upgrade turns it on for one crate. It adds the one-way grids — quartz, amethyst, snow, clay,
 * packed ice — and the one shape of pathway that starts with a split: a bone crafts into three bone
 * meal, and bone meal and the bone block craft into each other, so three bones are a bone block.
 * A split counts only when it lands in a reversible pair, which is what keeps a log from becoming a
 * crafting table: a log splits into planks, but planks and a crafting table are not a pair.
 *
 * <p>Pathways chain: nuggets go to ingots, and the ingots then go to blocks, so a crate full of
 * nuggets ends up holding blocks. {@link #top} is where a chain ends; two items whose chains end in
 * the same place are one <em>family</em>, which is what the lock, the void and the network's notion
 * of a home work in once a compress upgrade is installed: a crate locked to iron blocks takes
 * nuggets and ingots, because that is what it will turn them into.
 *
 * <p>Both tables come from one pass over the crafting recipes, kept until the recipes reload. A
 * recipe counts only when every ingredient is the same single item — a tag of planks is not an
 * item — and only as the crafting table would actually resolve a grid of that item, through
 * {@code RecipeManager#getRecipeFor}, so a recipe that would lose to another in the same grid is
 * never followed.
 */
public final class Compression {
	/**
	 * One rung of a pathway: {@code count} of {@code from} craft into one {@code to}. In the
	 * reversible table, one {@code to} also crafts back into {@code count} of {@code from}.
	 */
	public record Step(Item from, int count, Item to) {
	}

	/** Grids to try, densest first: a 3×3 of nine, then a 2×2 of four. */
	private static final int[] SIDES = {3, 2};
	/** How many rungs a chain may have; nugget to ingot to block is two, and nothing sane is deeper. */
	private static final int MAX_CHAIN = 8;

	/** The two tables, built on first use and dropped when the recipes change. */
	private static @Nullable Tables tables;

	private record Tables(Map<Item, Step> reversible, Map<Item, Step> destructive,
			Map<Item, List<Item>> reversibleDown, Map<Item, List<Item>> destructiveDown) {
		Map<Item, Step> up(boolean destructive) {
			return destructive ? this.destructive : reversible;
		}

		Map<Item, List<Item>> down(boolean destructive) {
			return destructive ? destructiveDown : reversibleDown;
		}
	}

	private Compression() {
	}

	/** Forgets every pathway whenever the recipes may have changed. */
	public static void initialize() {
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> clear());
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> clear());
	}

	static synchronized void clear() {
		tables = null;
	}

	/**
	 * The rung above {@code item}, or null if it compresses no further. With {@code destructive}
	 * false, only a pathway that crafts back; with it true, the one-way ones as well.
	 */
	public static synchronized @Nullable Step stepUp(ServerLevel level, Item item, boolean destructive) {
		return tables(level).up(destructive).get(item);
	}

	/** Where {@code item}'s chain ends: itself, if nothing crafts up from it. */
	public static Item top(ServerLevel level, Item item, boolean destructive) {
		Item at = item;

		for (int rung = 0; rung < MAX_CHAIN; rung++) {
			Step up = stepUp(level, at, destructive);

			if (up == null) {
				break;
			}

			at = up.to();
		}

		return at;
	}

	/** Whether two items lie on one chain — whatever is on either stack beyond the item. */
	public static boolean sameFamily(ServerLevel level, ItemStack a, ItemStack b, boolean destructive) {
		if (ItemStack.isSameItem(a, b)) {
			return true;
		}

		return top(level, a.getItem(), destructive) == top(level, b.getItem(), destructive);
	}

	/**
	 * Every item on {@code item}'s chain, densest first: the block, then the ingot, then the
	 * nugget — and, destructively, the bone below the bone block. Just the item itself when it is
	 * on no chain.
	 */
	public static synchronized List<Item> family(ServerLevel level, Item item, boolean destructive) {
		Map<Item, List<Item>> down = tables(level).down(destructive);
		List<Item> chain = new ArrayList<>();
		Queue<Item> frontier = new ArrayDeque<>();
		frontier.add(top(level, item, destructive));

		while (!frontier.isEmpty() && chain.size() < 64) {
			Item at = frontier.remove();

			if (chain.contains(at)) {
				continue;
			}

			chain.add(at);
			frontier.addAll(down.getOrDefault(at, List.of()));
		}

		return Collections.unmodifiableList(chain);
	}

	private static Tables tables(ServerLevel level) {
		Tables built = tables;

		if (built == null) {
			built = build(level);
			tables = built;
		}

		return built;
	}

	/**
	 * One pass over the crafting recipes.
	 *
	 * <p>Two raw tables first: {@code ups}, a full grid of one item making one plain item, densest
	 * grid winning; and {@code splits}, one item alone making several of one plain item. A
	 * reversible step is an up whose result splits back into exactly what went in. The destructive
	 * table starts from the reversible one, adds every up that has no way back, and then the
	 * composed shape: an item that splits into something with a reversible step up, provided the
	 * split's count divides the grid's and the chain does not come straight back to where it began.
	 */
	private static Tables build(ServerLevel level) {
		RecipeManager recipes = level.getServer().getRecipeManager();
		Map<Item, Step> ups = new IdentityHashMap<>();
		Map<Item, Step> splits = new IdentityHashMap<>();

		for (RecipeHolder<?> holder : recipes.getRecipes()) {
			if (!(holder.value() instanceof CraftingRecipe)) {
				continue;
			}

			PlacementInfo placement = holder.value().placementInfo();

			if (placement.isImpossibleToPlace()) {
				continue;
			}

			Item item = soleItem(placement.ingredients());

			if (item == null) {
				continue;
			}

			int count = placement.ingredients().size();

			if (count == 1) {
				ItemStack result = craft(recipes, level, CraftingInput.of(1, 1, List.of(new ItemStack(item))));

				if (result.getCount() > 1 && plain(result) && !result.is(item)) {
					splits.putIfAbsent(item, new Step(result.getItem(), result.getCount(), item));
				}

				continue;
			}

			for (int side : SIDES) {
				if (count != side * side) {
					continue;
				}

				Step known = ups.get(item);

				if (known != null && known.count() >= count) {
					break;
				}

				ItemStack result = craft(recipes, level,
						CraftingInput.of(side, side, Collections.nCopies(count, new ItemStack(item))));

				if (result.getCount() == 1 && plain(result) && !result.is(item)) {
					ups.put(item, new Step(item, count, result.getItem()));
				}
			}
		}

		Map<Item, Step> reversible = new IdentityHashMap<>();

		for (Step up : ups.values()) {
			Step back = splits.get(up.to());

			if (back != null && back.from() == up.from() && back.count() == up.count()) {
				reversible.put(up.from(), up);
			}
		}

		Map<Item, Step> destructive = new IdentityHashMap<>(reversible);

		for (Step up : ups.values()) {
			destructive.putIfAbsent(up.from(), up);
		}

		// A split's "step" is written backwards — it is the way down from `to` — so from its
		// point of view the item that splits is `to` and what it splits into is `from`.
		for (Step split : splits.values()) {
			Item loose = split.to();
			Item pieces = split.from();
			Step up = reversible.get(pieces);

			if (!destructive.containsKey(loose) && up != null && up.to() != loose
					&& up.count() % split.count() == 0) {
				destructive.put(loose, new Step(loose, up.count() / split.count(), up.to()));
			}
		}

		return new Tables(reversible, destructive, reverse(reversible), reverse(destructive));
	}

	/** Every ingredient the same single item, and that item; null for a tag, a mix, or nothing. */
	private static @Nullable Item soleItem(List<Ingredient> ingredients) {
		Item sole = null;

		for (Ingredient ingredient : ingredients) {
			List<Holder<Item>> items = ingredient.items().toList();

			if (items.size() != 1) {
				return null;
			}

			Item item = items.getFirst().value();

			if (sole == null) {
				sole = item;
			} else if (sole != item) {
				return null;
			}
		}

		return sole;
	}

	private static Map<Item, List<Item>> reverse(Map<Item, Step> ups) {
		Map<Item, List<Item>> down = new HashMap<>();

		for (Step up : ups.values()) {
			down.computeIfAbsent(up.to(), key -> new ArrayList<>()).add(up.from());
		}

		return down;
	}

	private static boolean plain(ItemStack stack) {
		return !stack.isEmpty() && stack.getComponentsPatch().isEmpty();
	}

	/** What a crafting table would make of {@code input}; empty if nothing. */
	private static ItemStack craft(RecipeManager recipes, ServerLevel level, CraftingInput input) {
		Optional<RecipeHolder<CraftingRecipe>> match = recipes.getRecipeFor(RecipeType.CRAFTING, input, level);
		return match.map(holder -> holder.value().assemble(input)).orElse(ItemStack.EMPTY);
	}
}
