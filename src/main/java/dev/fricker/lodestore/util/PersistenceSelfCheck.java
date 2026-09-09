package dev.fricker.lodestore.util;

import java.util.ArrayList;
import java.util.List;

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

/**
 * Checks that every Lodestore container survives being written and read back.
 *
 * <p>Exists because a block entity that never writes its contents is completely silent: no
 * exception, no anomaly, nothing to log. It is only detectable by comparing across a save, which is
 * what this does. Drawer contents were lost this way once — {@code BaseContainerBlockEntity} saves
 * the custom name and lock but not the items, and every vanilla container writes its own.
 *
 * <p>Runs against whatever is registered, so a backpack or network cell added later is covered
 * without anyone remembering to extend it.
 */
public final class PersistenceSelfCheck {
	private static final String NAMESPACE = "lodestore";

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

	private static java.util.Optional<String> check(EntityBlock entityBlock, Block block, Identifier id,
			HolderLookup.Provider registries) {
		BlockState state = block.defaultBlockState();
		BlockEntity original = entityBlock.newBlockEntity(BlockPos.ZERO, state);

		if (!(original instanceof Container container) || container.getContainerSize() == 0) {
			return java.util.Optional.empty();
		}

		// Two distinct stacks at both ends, so a container that saves only part of
		// itself fails as loudly as one that saves nothing.
		int last = container.getContainerSize() - 1;
		container.setItem(0, new ItemStack(Items.DIAMOND, 7));
		container.setItem(last, new ItemStack(Items.EMERALD, 3));

		CompoundTag written = original.saveCustomOnly(registries);

		BlockEntity reloaded = entityBlock.newBlockEntity(BlockPos.ZERO, state);

		if (reloaded == null) {
			return java.util.Optional.of(id + ": could not create a second block entity");
		}

		reloaded.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, registries, written));

		if (!(reloaded instanceof Container restored)) {
			return java.util.Optional.of(id + ": reloaded block entity is not a container");
		}

		if (restored.getItem(0).getCount() != 7 || !restored.getItem(0).is(Items.DIAMOND)) {
			return java.util.Optional.of(id + ": first slot did not survive a save and load (got "
					+ describe(restored.getItem(0)) + ")");
		}

		if (restored.getItem(last).getCount() != 3 || !restored.getItem(last).is(Items.EMERALD)) {
			return java.util.Optional.of(id + ": last slot did not survive a save and load (got "
					+ describe(restored.getItem(last)) + ")");
		}

		return java.util.Optional.empty();
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
