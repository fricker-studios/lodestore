package dev.fricker.lodestore.block;

import net.minecraft.world.level.block.Block;

/**
 * A connection block: what a storage network is made of.
 *
 * <p>Nothing but a block. It has no block entity, no state and no behaviour of its own; it exists
 * so that {@code StorageNetwork} can tell it from everything else when it walks from a device, and
 * so that two devices side by side do not quietly become one network. Everything the network does,
 * the devices on it do.
 */
public class ConnectionBlock extends Block {
	public ConnectionBlock(Properties properties) {
		super(properties);
	}
}
