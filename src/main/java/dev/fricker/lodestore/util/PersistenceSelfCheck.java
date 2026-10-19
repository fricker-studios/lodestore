package dev.fricker.lodestore.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;

import dev.fricker.lodestore.Lodestore;
import dev.fricker.lodestore.storage.ContentsHolder;
import dev.fricker.lodestore.storage.DrawerContents;
import dev.fricker.lodestore.storage.DrawerOverflow;

/**
 * Checks that every Lodestore container survives being written and read back.
 *
 * <p>Exists because a block entity that never writes its contents is completely silent: no
 * exception, no anomaly, nothing to log. It is only detectable by comparing across a save, which is
 * what this does. Drawer contents were lost this way once — {@code BaseContainerBlockEntity} saves
 * the custom name and lock but not the items, and every vanilla container writes its own.
 *
 * <p>Runs against whatever is registered, so a backpack or network cell added later is covered
 * without anyone remembering to extend it. Two kinds of thing are found: anything that is still a
 * vanilla {@code Container}, and anything holding Lodestore's own {@link DrawerContents} — a
 * drawer is deliberately not a container any more, and a check that only knew containers would
 * have skipped every drawer in silence, which is the exact failure it exists to catch. For the
 * latter the check also parks a remainder behind each stack, because that count is saved apart
 * from the stack and could be lost on its own.
 */
public final class PersistenceSelfCheck {
	private static final String NAMESPACE = "lodestore";
	private static final long FIRST_REMAINDER = 100;
	private static final long LAST_REMAINDER = 5;

	private PersistenceSelfCheck() {
	}

	/** Returns one description per container that failed to round-trip; empty means healthy. */
	public static List<String> run(HolderLookup.Provider registries) {
		List<String> failures = new ArrayList<>();

		for (Block block : BuiltInRegistries.BLOCK) {
			Identifier id = BuiltInRegistries.BLOCK.getKey(block);

			if (id == null || !NAMESPACE.equals(id.getNamespace()) || !(block instanceof EntityBlock entityBlock)) {
				continue;
			}

			try {
				check(entityBlock, block, id, registries).ifPresent(failures::add);
			} catch (Exception e) {
				// A self-check must never be the thing that breaks startup.
				failures.add(id + ": threw while round-tripping (" + e + ")");
			}
		}

		return failures;
	}

	/** Whether the check has anything to exercise on this block — for the gametest's vacuity guard. */
	public static boolean covers(Block block) {
		if (!(block instanceof EntityBlock entityBlock)) {
			return false;
		}

		BlockEntity entity = entityBlock.newBlockEntity(BlockPos.ZERO, block.defaultBlockState());
		return entity instanceof ContentsHolder || (entity instanceof Container container && container.getContainerSize() > 0);
	}

	private static Optional<String> check(EntityBlock entityBlock, Block block, Identifier id,
			HolderLookup.Provider registries) {
		BlockState state = block.defaultBlockState();
		BlockEntity original = entityBlock.newBlockEntity(BlockPos.ZERO, state);

		if (original instanceof ContentsHolder holder) {
			return checkContents(entityBlock, state, id, registries, original, holder.contents());
		}

		if (!(original instanceof Container container) || container.getContainerSize() == 0) {
			return Optional.empty();
		}

		// Two distinct stacks at both ends, so a container that saves only part of
		// itself fails as loudly as one that saves nothing.
		int last = container.getContainerSize() - 1;
		container.setItem(0, new ItemStack(Items.DIAMOND, 7));
		container.setItem(last, new ItemStack(Items.EMERALD, 3));

		BlockEntity reloaded = reload(entityBlock, state, original, registries);

		if (reloaded == null) {
			return Optional.of(id + ": could not create a second block entity");
		}

		if (!(reloaded instanceof Container restored)) {
			return Optional.of(id + ": reloaded block entity is not a container");
		}

		if (restored.getItem(0).getCount() != 7 || !restored.getItem(0).is(Items.DIAMOND)) {
			return Optional.of(id + ": first slot did not survive a save and load (got "
					+ describe(restored.getItem(0)) + ")");
		}

		if (restored.getItem(last).getCount() != 3 || !restored.getItem(last).is(Items.EMERALD)) {
			return Optional.of(id + ": last slot did not survive a save and load (got "
					+ describe(restored.getItem(last)) + ")");
		}

		return Optional.empty();
	}

	private static Optional<String> checkContents(EntityBlock entityBlock, BlockState state, Identifier id,
			HolderLookup.Provider registries, BlockEntity original, DrawerContents contents) {
		if (contents.size() == 0) {
			return Optional.of(id + ": its contents have no slots to check");
		}

		int last = contents.size() - 1;
		contents.setItem(0, new ItemStack(Items.DIAMOND, 7));
		contents.setItem(last, new ItemStack(Items.EMERALD, 3));
		// Remainders are saved apart from the stacks, so they get their own chance to go missing.
		contents.applyOverflow(new DrawerOverflow(List.of(
				new DrawerOverflow.Entry(0, FIRST_REMAINDER),
				new DrawerOverflow.Entry(last, LAST_REMAINDER))), true);

		long firstTotal = contents.total(0);
		long lastTotal = contents.total(last);

		BlockEntity reloaded = reload(entityBlock, state, original, registries);

		if (!(reloaded instanceof ContentsHolder restoredHolder)) {
			return Optional.of(id + ": reloaded block entity does not hold contents");
		}

		DrawerContents restored = restoredHolder.contents();

		if (!restored.visible(0).is(Items.DIAMOND) || restored.total(0) != firstTotal) {
			return Optional.of(id + ": first slot did not survive a save and load (expected "
					+ firstTotal + " diamonds, got " + restored.total(0) + " of " + describe(restored.visible(0)) + ")");
		}

		if (!restored.visible(last).is(Items.EMERALD) || restored.total(last) != lastTotal) {
			return Optional.of(id + ": last slot did not survive a save and load (expected "
					+ lastTotal + " emeralds, got " + restored.total(last) + " of " + describe(restored.visible(last)) + ")");
		}

		if (restored.remainder(0) != contents.remainder(0)) {
			return Optional.of(id + ": the parked remainder did not survive a save and load (expected "
					+ contents.remainder(0) + ", got " + restored.remainder(0) + ")");
		}

		return Optional.empty();
	}

	private static BlockEntity reload(EntityBlock entityBlock, BlockState state, BlockEntity original,
			HolderLookup.Provider registries) {
		CompoundTag written = original.saveCustomOnly(registries);
		BlockEntity reloaded = entityBlock.newBlockEntity(BlockPos.ZERO, state);

		if (reloaded != null) {
			reloaded.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, registries, written));
		}

		return reloaded;
	}

	private static String describe(ItemStack stack) {
		return stack.isEmpty() ? "nothing" : stack.getCount() + "x " + stack.getItem();
	}

	/** Logs loudly if anything fails. Silent when healthy, which is almost always. */
	public static void runAndReport(HolderLookup.Provider registries) {
		List<String> failures = run(registries);

		for (String failure : failures) {
			Lodestore.LOGGER.error("[lodestore] STORAGE WILL LOSE ITEMS — {}", failure);
		}

		if (!failures.isEmpty()) {
			Lodestore.LOGGER.error("[lodestore] {} container(s) do not survive a save and load. "
					+ "Contents placed in them will be gone after a reload.", failures.size());
		}
	}
}
