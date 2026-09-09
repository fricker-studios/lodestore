package dev.fricker.lodestore.testmod;

import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;

/**
 * Development-only diagnostic. Lives in the test mod source set, so it is not present in the
 * published jar at all.
 *
 * <p>Exercises a drawer through {@link ItemStorage#SIDED} — the exact lookup a hopper, pipe, or
 * third-party mod uses — so interop can be checked without relying on hoppers actually ticking.
 * They do not tick on a playerless dedicated server, which makes hopper-based verification
 * silently useless there.
 *
 * <p>Usage: {@code /lodestore_debug <x> <y> <z> <insertCount>}
 */
public final class DebugCommand {
	private DebugCommand() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(Commands.literal("lodestore_debug")
						.then(Commands.argument("pos", BlockPosArgument.blockPos())
								.then(Commands.argument("insert", IntegerArgumentType.integer(0))
										.executes(ctx -> {
											ServerLevel level = ctx.getSource().getLevel();
											BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
											int toInsert = IntegerArgumentType.getInteger(ctx, "insert");
											return run(ctx.getSource()::sendSystemMessage, level, pos, toInsert);
										})))));
	}

	private static int run(java.util.function.Consumer<Component> out, ServerLevel level, BlockPos pos, int toInsert) {
		Storage<ItemVariant> storage = ItemStorage.SIDED.find(level, pos, Direction.UP);

		if (storage == null) {
			out.accept(Component.literal("LOOKUP: no Storage<ItemVariant> at " + pos));
			return 0;
		}

		out.accept(Component.literal("LOOKUP: ok -> " + storage.getClass().getSimpleName()));

		ItemVariant cobble = ItemVariant.of(Items.COBBLESTONE);
		long inserted;

		try (Transaction tx = Transaction.openOuter()) {
			inserted = storage.insert(cobble, toInsert, tx);
			tx.commit();
		}

		out.accept(Component.literal("INSERT: requested " + toInsert + ", accepted " + inserted));

		// A rolled-back transaction must leave no trace — this is the snapshot contract.
		long beforeRollback = total(storage);

		try (Transaction tx = Transaction.openOuter()) {
			storage.insert(cobble, 1000, tx);
			tx.abort();
		}

		long afterRollback = total(storage);
		out.accept(Component.literal("ROLLBACK: " + beforeRollback + " -> " + afterRollback
				+ (beforeRollback == afterRollback ? " (ok)" : " (LEAKED)")));

		long extracted;

		try (Transaction tx = Transaction.openOuter()) {
			extracted = storage.extract(cobble, 50, tx);
			tx.commit();
		}

		out.accept(Component.literal("EXTRACT: asked 50, got " + extracted));

		for (StorageView<ItemVariant> view : storage) {
			out.accept(Component.literal("VIEW: " + view.getAmount() + " / " + view.getCapacity()
					+ " of " + (view.isResourceBlank() ? "<blank>" : view.getResource().getItem())));
		}

		return 1;
	}

	private static long total(Storage<ItemVariant> storage) {
		long sum = 0;

		for (StorageView<ItemVariant> view : storage) {
			sum += view.getAmount();
		}

		return sum;
	}
}
