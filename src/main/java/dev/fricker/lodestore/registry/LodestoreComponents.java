package dev.fricker.lodestore.registry;

import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;

import dev.fricker.lodestore.Lodestore;
import dev.fricker.lodestore.storage.DrawerOverflow;

/** Data components Lodestore puts on items. */
public final class LodestoreComponents {
	/**
	 * The counts parked behind a drawer's visible stacks, carried on the dropped item alongside
	 * {@code minecraft:container} for the tiers that keep their contents.
	 *
	 * <p>Synchronised because a drawer in a player's hand is sent to the client, and the tooltip
	 * has to be able to say what the stacks alone do not.
	 */
	public static final DataComponentType<DrawerOverflow> DRAWER_OVERFLOW = DataComponentType.<DrawerOverflow>builder()
			.persistent(DrawerOverflow.CODEC)
			.networkSynchronized(DrawerOverflow.STREAM_CODEC)
			.build();

	private LodestoreComponents() {
	}

	/** Forces class-loading and performs registration. */
	public static void initialize() {
		Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, Lodestore.id("drawer_overflow"), DRAWER_OVERFLOW);
	}
}
