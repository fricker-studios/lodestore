package dev.fricker.lodestore.network;

import java.util.Locale;

/**
 * Counts as the interface screen draws them, in the sixteen pixels a slot has.
 *
 * <p>Below a thousand the number itself. From there, thousands as {@code k} and millions as
 * {@code M}, with one decimal while there is room for it to mean anything — {@code 1.5k}, but
 * {@code 15k} — and always rounded <em>down</em>: a slot that says {@code 2k} holds at least two
 * thousand, never 1,999 rounded up. Storage should understate rather than promise.
 */
public final class CountFormat {
	private CountFormat() {
	}

	/** The short form drawn on a slot. */
	public static String abbreviate(long count) {
		if (count < 1_000) {
			return Long.toString(count);
		}

		if (count < 1_000_000) {
			return scaled(count, 1_000, "k");
		}

		if (count < 1_000_000_000) {
			return scaled(count, 1_000_000, "M");
		}

		return scaled(count, 1_000_000_000, "B");
	}

	/** The full number, grouped, for a tooltip. */
	public static String exact(long count) {
		return String.format(Locale.ROOT, "%,d", count);
	}

	private static String scaled(long count, long unit, String suffix) {
		long whole = count / unit;

		if (whole >= 10) {
			return whole + suffix;
		}

		long tenth = (count % unit) * 10 / unit;
		return tenth == 0 ? whole + suffix : whole + "." + tenth + suffix;
	}
}
