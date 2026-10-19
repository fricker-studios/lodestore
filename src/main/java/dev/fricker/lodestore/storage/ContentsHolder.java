package dev.fricker.lodestore.storage;

/**
 * A block entity whose goods live in a {@link CrateContents}.
 *
 * <p>Exists so that {@code PersistenceSelfCheck} can find Lodestore's storage without anyone
 * remembering to extend it. It used to find every container by {@code instanceof Container}, and a
 * crate is deliberately no longer one — which would have made the self-check skip crates in
 * complete silence, the very failure it exists to catch.
 */
public interface ContentsHolder {
	CrateContents contents();
}
