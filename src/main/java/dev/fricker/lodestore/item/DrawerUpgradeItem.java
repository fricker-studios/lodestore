package dev.fricker.lodestore.item;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

import dev.fricker.lodestore.api.drawer.DrawerUpgrade;

/**
 * An item that installs a {@link DrawerUpgrade} into a drawer when right-clicked onto it.
 *
 * <p>Holds only the key. The behaviour behind it is resolved from the datapack-driven registry at
 * the point of use, so a pack can change what an upgrade does without the item changing.
 */
public class DrawerUpgradeItem extends Item {
	private final ResourceKey<DrawerUpgrade> upgradeKey;

	public DrawerUpgradeItem(Properties properties, ResourceKey<DrawerUpgrade> upgradeKey) {
		super(properties);
		this.upgradeKey = upgradeKey;
	}

	public ResourceKey<DrawerUpgrade> upgradeKey() {
		return upgradeKey;
	}
}
