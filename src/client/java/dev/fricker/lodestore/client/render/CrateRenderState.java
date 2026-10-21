package dev.fricker.lodestore.client.render;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.core.Direction;

/**
 * Per-frame snapshot of a crate, extracted on the render thread.
 *
 * <p>26.x separates "read the block entity" from "draw it": everything the draw step needs must be
 * copied into here first, so {@code submit} never touches the block entity.
 */
public class CrateRenderState extends BlockEntityRenderState {
	public Direction facing = Direction.NORTH;
	public boolean blank = true;
	/** Whether what is drawn is the lock's filter rather than real contents. */
	public boolean ghost;
	public final ItemStackRenderState item = new ItemStackRenderState();
}
