package dev.fricker.lodestore.api.crate;

/**
 * The behaviours an upgrade can grant.
 *
 * <p>Every one applies to the whole crate. None is aimed at particular slots: the lock used to be,
 * until it came to claim everything the crate holds rather than what a few slots held.
 */
public enum UpgradeBehaviour {
	LOCK,
	VOID,
	STACK,
	/** Crafts what the crate holds up its reversible pathways: nuggets to ingots to blocks. */
	COMPRESS,
	/** Strips enchantments off what goes in and banks the experience for the player. */
	DISENCHANT,
	/** Combines worn, unenchanted tools and armour into fewer, fuller ones. */
	REPAIR;

	/** Whether {@code upgrade} grants this behaviour. */
	public boolean grantedBy(CrateUpgrade upgrade) {
		return switch (this) {
			case LOCK -> upgrade.locksContents();
			case VOID -> upgrade.voidsOverflow();
			case STACK -> upgrade.stacks();
			case COMPRESS -> upgrade.compresses();
			case DISENCHANT -> upgrade.disenchants();
			case REPAIR -> upgrade.repairs();
		};
	}
}
