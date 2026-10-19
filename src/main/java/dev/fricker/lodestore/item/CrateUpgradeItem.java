package dev.fricker.lodestore.item;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

import dev.fricker.lodestore.api.crate.CrateUpgrade;

/**
 * An item that installs a {@link CrateUpgrade} into a crate when right-clicked onto it.
 *
 * <p>Holds only the key. The behaviour behind it is resolved from the datapack-driven registry at
 * the point of use, so a pack can change what an upgrade does without the item changing.
 */
public class CrateUpgradeItem extends Item {
	private final ResourceKey<CrateUpgrade> upgradeKey;

	public CrateUpgradeItem(Properties properties, ResourceKey<CrateUpgrade> upgradeKey) {
		super(properties);
		this.upgradeKey = upgradeKey;
	}

	public ResourceKey<CrateUpgrade> upgradeKey() {
		return upgradeKey;
	}
}
