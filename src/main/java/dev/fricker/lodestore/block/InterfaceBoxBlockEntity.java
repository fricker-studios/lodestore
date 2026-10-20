package dev.fricker.lodestore.block;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;

import dev.fricker.lodestore.menu.InterfaceMenu;
import dev.fricker.lodestore.menu.InterfaceMenuData;
import dev.fricker.lodestore.network.NetworkView;
import dev.fricker.lodestore.network.StorageNetwork;
import dev.fricker.lodestore.registry.LodestoreBlockEntities;

/**
 * An interface box: a window onto the network, and the way things will come out of it.
 *
 * <p>Holds nothing and ticks never. It exists so that there is a block to right-click, and so that
 * the menu it opens has a position to walk the network from; the walk itself happens while the
 * menu is open, on the menu's schedule, and the result is sent to whoever is looking. Nothing goes
 * <em>in</em> through it, ever: the deposit box is the network's mouth, and a container set on
 * top of an interface box is left exactly as it was.
 */
public class InterfaceBoxBlockEntity extends BlockEntity implements ExtendedMenuProvider<InterfaceMenuData> {
	public InterfaceBoxBlockEntity(BlockPos pos, BlockState state) {
		super(LodestoreBlockEntities.INTERFACE_BOX, pos, state);
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
