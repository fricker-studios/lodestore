package dev.fricker.lodestore.block;

import org.jspecify.annotations.Nullable;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.fabricmc.fabric.api.transfer.v1.item.ContainerStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;

import dev.fricker.lodestore.menu.InterfaceMenu;
import dev.fricker.lodestore.menu.InterfaceMenuData;
import dev.fricker.lodestore.network.NetworkCrateCache;
import dev.fricker.lodestore.network.NetworkView;
import dev.fricker.lodestore.network.StorageNetwork;
import dev.fricker.lodestore.registry.LodestoreBlockEntities;

/**
 * An interface box: a window onto the network, and a mouth for it.
 *
 * <p>Holds nothing. Its screen is a view the menu builds while it is open. What it does on its
 * own is pull: like a hopper, it draws from whatever container sits on top of it — a chest, a
 * hopper, a hopper minecart parked on a rail — one item a tick, into the crate on the network that
 * is the item's home, or failing that an unlocked crate, the network's overflow. Only items the
 * network has a place for are taken; the rest stay where they were, so a minecart full of mixed
 * loot leaves with what had nowhere to go still aboard.
 *
 * <p>A crate on top is not pulled from. Crates are the network's storage, not its feeders, and a
 * crate touching the box is already on its network, so pulling from it would move an item from a
 * crate to the nearest home for it, which may well be the same crate.
 */
public class InterfaceBoxBlockEntity extends BlockEntity implements ExtendedMenuProvider<InterfaceMenuData> {
	private final NetworkCrateCache crates = new NetworkCrateCache();

	public InterfaceBoxBlockEntity(BlockPos pos, BlockState state) {
		super(LodestoreBlockEntities.INTERFACE_BOX, pos, state);
	}

	/** Pulls one item with a home from the container above, if there is one of either. */
	public static void serverTick(Level level, BlockPos pos, BlockState state, InterfaceBoxBlockEntity box) {
		Storage<ItemVariant> source = sourceAbove(level, pos);

		if (source == null) {
			return;
		}

		List<BlockPos> crates = box.crates.get(level, pos);

		if (crates.isEmpty()) {
			return;
		}

		for (StorageView<ItemVariant> view : source) {
			if (view.isResourceBlank() || view.getAmount() <= 0) {
				continue;
			}

			ItemVariant variant = view.getResource();

			// Taken and landed in one transaction, so an item with nowhere to go is never taken at all.
			try (Transaction transaction = Transaction.openOuter()) {
				if (view.extract(variant, 1, transaction) == 1
						&& StorageNetwork.insertOne(level, crates, variant, transaction)) {
					transaction.commit();
					return;
				}
			}
		}
	}

	/**
	 * What sits on top, as a storage: a vanilla container or a container entity, found the way a
	 * hopper finds its source, or else anything offering a storage through its bottom face.
	 */
	private static @Nullable Storage<ItemVariant> sourceAbove(Level level, BlockPos pos) {
		BlockPos above = pos.above();

		if (level.getBlockEntity(above) instanceof CrateBlockEntity) {
			return null;
		}

		Container container = HopperBlockEntity.getContainerAt(level, above);

		if (container != null) {
			return ContainerStorage.of(container, Direction.DOWN);
		}

		return ItemStorage.SIDED.find(level, above, Direction.DOWN);
	}

	/** What the network holds right now. Walks the network; the menu decides how often to ask. */
	public NetworkView view() {
		return level == null ? NetworkView.EMPTY : StorageNetwork.view(level, worldPosition);
	}

	@Override
	public Component getDisplayName() {
		return getBlockState().getBlock().getName();
	}

	@Override
	public @Nullable AbstractContainerMenu createMenu(int syncId, Inventory playerInventory, Player player) {
		return new InterfaceMenu(syncId, playerInventory, this, worldPosition);
	}

	@Override
	public InterfaceMenuData getScreenOpeningData(ServerPlayer player) {
		return new InterfaceMenuData(worldPosition);
	}
}
