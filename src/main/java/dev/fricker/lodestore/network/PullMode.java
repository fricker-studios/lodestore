package dev.fricker.lodestore.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * How much a click on the interface box's screen asks for, and where it should go — the three
 * gestures a vanilla slot has, since that is what the cells look like.
 */
public enum PullMode {
	/** A left-click: a full stack onto the cursor. */
	STACK,
	/** A right-click: half a stack onto the cursor. */
	HALF,
	/** A shift-click: a full stack straight into the inventory. */
	TO_INVENTORY;

	public static final StreamCodec<ByteBuf, PullMode> STREAM_CODEC =
			ByteBufCodecs.VAR_INT.map(ordinal -> values()[Math.floorMod(ordinal, values().length)], PullMode::ordinal);

	/** Whether what is taken lands on the cursor rather than in the inventory. */
	public boolean toCursor() {
		return this != TO_INVENTORY;
	}
}
