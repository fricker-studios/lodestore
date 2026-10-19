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
	STACK;

	/** Whether {@code upgrade} grants this behaviour. */
	public boolean grantedBy(CrateUpgrade upgrade) {
		return switch (this) {
			case LOCK -> upgrade.locksContents();
			case VOID -> upgrade.voidsOverflow();
			case STACK -> upgrade.stacks();
		};
	}
}
