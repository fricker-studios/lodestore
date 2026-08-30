package dev.fricker.lodestore.block;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.CombinedSlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SidedStorageBlockEntity;

import dev.fricker.lodestore.api.LodestoreRegistries;
import dev.fricker.lodestore.api.drawer.DrawerTier;
import dev.fricker.lodestore.registry.LodestoreBlockEntities;
import dev.fricker.lodestore.storage.DrawerSlot;

/**
 * A drawer.
 *
 * <p>Deliberately has <b>no ticker</b>: drawers are purely reactive. All state changes happen
 * inside a transaction driven by an inserting/extracting party (player, hopper, pipe, or a future
 * Lodestore network), so there is nothing to poll.
 *
 * <p>Implementing {@link SidedStorageBlockEntity} is what gives free interop with hoppers and any
 * other mod that speaks the transfer API — no per-block-entity lookup registration required.
 */
public class DrawerBlockEntity extends BlockEntity implements SidedStorageBlockEntity {
	private final List<DrawerSlot> slots = new ArrayList<>();
	private @Nullable DrawerTier cachedTier;

	public DrawerBlockEntity(BlockPos pos, BlockState state) {
		super(LodestoreBlockEntities.DRAWER, pos, state);
	}

	/**
	 * Resolves this drawer's tier from the level's datapack-driven registry.
	 *
	 * <p>The result is only cached once the level is available; before that we return the fallback
	 * without caching, so a drawer loaded before its registries are ready still picks up its real
	 * tier later.
	 */
	public DrawerTier tier() {
		if (cachedTier != null) {
			return cachedTier;
		}

		ResourceKey<DrawerTier> key = tierKey();

		if (level != null && key != null) {
			cachedTier = level.registryAccess()
					.lookup(LodestoreRegistries.DRAWER_TIER)
					.flatMap(registry -> registry.getOptional(key))
					.orElse(DrawerTier.FALLBACK);
			return cachedTier;
		}

		return DrawerTier.FALLBACK;
	}

	private @Nullable ResourceKey<DrawerTier> tierKey() {
		return getBlockState().getBlock() instanceof DrawerBlock drawer ? drawer.tierKey() : null;
	}

	public long capacityFor(ItemVariant variant) {
		return tier().capacityFor(variant);
	}

	/**
	 * The drawer's slots, grown lazily to the tier's slot count.
	 *
	 * <p>Never shrinks: if a datapack lowers a tier's slot count, already-placed drawers keep their
	 * extra slots rather than silently voiding what was inside them.
	 */
	public List<DrawerSlot> slots() {
		int wanted = Math.max(1, tier().slots());

		while (slots.size() < wanted) {
			slots.add(new DrawerSlot(this));
		}

		return slots;
	}

	public void onContentsChanged() {
		setChanged();

		// Push the new contents to watching clients. Without this the drawer is
		// invisible from the player's seat: a working drawer and a broken one look
		// identical, which is exactly how a silent success reads as a bug.
		if (level != null && !level.isClientSide()) {
			level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
		}
	}

	// --- client sync -------------------------------------------------------
	// Drawers render their contents on the front face, so unlike a chest the
	// client genuinely needs to know what is inside.

	@Override
	public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		return saveWithoutMetadata(registries);
	}

	@Override
	public @Nullable Storage<ItemVariant> getItemStorage(@Nullable Direction side) {
		// Every side exposes the whole drawer; per-side filtering arrives with upgrades in P2.
		return new CombinedSlottedStorage<>(List.copyOf(slots()));
	}

	@Override
	protected void saveAdditional(ValueOutput data) {
		super.saveAdditional(data);
		ValueOutput.ValueOutputList list = data.childrenList("slots");

		for (DrawerSlot slot : slots) {
			slot.writeValue(list.addChild());
		}
	}

	@Override
	protected void loadAdditional(ValueInput data) {
		super.loadAdditional(data);
		// Read the persisted slots directly rather than via slots(), which would force tier
		// resolution before the level is necessarily available.
		slots.clear();

		for (ValueInput child : data.childrenListOrEmpty("slots")) {
			DrawerSlot slot = new DrawerSlot(this);
			slot.readValue(child);
			slots.add(slot);
		}
	}
}
