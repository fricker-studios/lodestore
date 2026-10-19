package dev.fricker.lodestore.api.drawer;

/**
 * The behaviours an upgrade can grant.
 *
 * <p>Only the lock is targeted at particular slots; voiding and stacking apply to the whole
 * drawer. Slot targeting is per behaviour rather than per installed upgrade: two locks share one
 * set of covered slots, because the thing a slot is or is not is "locked", not "locked by this
 * item". The menu's button packet, the block entity's target sets and the screen's tints all key
 * off this.
 */
public enum UpgradeBehaviour {
	LOCK,
	VOID,
	STACK;

	/** Whether a player chooses which slots this behaviour covers, or it covers them all. */
	public boolean targetable() {
		return this == LOCK;
	}

	/** Whether {@code upgrade} grants this behaviour. */
	public boolean grantedBy(DrawerUpgrade upgrade) {
		return switch (this) {
			case LOCK -> upgrade.locksContents();
			case VOID -> upgrade.voidsOverflow();
			case STACK -> upgrade.stacks();
		};
	}

	/** The behaviour with this ordinal, or null for anything out of range; packets are untrusted. */
	public static UpgradeBehaviour byId(int id) {
		return id >= 0 && id < values().length ? values()[id] : null;
	}
}
