package dev.fricker.lodestore.client.render;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.core.Direction;

/**
 * Per-frame snapshot of a drawer, extracted on the render thread.
 *
 * <p>26.x separates "read the block entity" from "draw it": everything the draw step needs must be
 * copied into here first, so {@code submit} never touches the block entity.
 */
public class DrawerRenderState extends BlockEntityRenderState {
	public Direction facing = Direction.NORTH;
	public boolean blank = true;
	public long amount;
	public final ItemStackRenderState item = new ItemStackRenderState();
}
