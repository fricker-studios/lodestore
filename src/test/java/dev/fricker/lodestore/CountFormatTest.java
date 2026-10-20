package dev.fricker.lodestore;

import org.junit.jupiter.api.Test;

import dev.fricker.lodestore.network.CountFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The count an interface cell draws. Always rounded down: a cell that says {@code 2k} holds at
 * least two thousand, and a cell never claims more than the network has.
 */
class CountFormatTest {
	@Test
	void smallCountsAreThemselves() {
		assertEquals("0", CountFormat.abbreviate(0));
		assertEquals("1", CountFormat.abbreviate(1));
		assertEquals("64", CountFormat.abbreviate(64));
		assertEquals("999", CountFormat.abbreviate(999));
	}

	@Test
	void thousandsKeepOneDecimalWhileThereIsRoom() {
		assertEquals("1k", CountFormat.abbreviate(1_000));
		assertEquals("1k", CountFormat.abbreviate(1_024));
		assertEquals("1.5k", CountFormat.abbreviate(1_500));
		assertEquals("1.2k", CountFormat.abbreviate(1_299));
		assertEquals("9.9k", CountFormat.abbreviate(9_999));
		assertEquals("10k", CountFormat.abbreviate(10_000));
		assertEquals("12k", CountFormat.abbreviate(12_800));
		assertEquals("999k", CountFormat.abbreviate(999_999));
	}

	@Test
	void millionsAndBeyond() {
		assertEquals("1M", CountFormat.abbreviate(1_000_000));
		assertEquals("2.5M", CountFormat.abbreviate(2_500_000));
		assertEquals("42M", CountFormat.abbreviate(42_000_000));
		assertEquals("1.1B", CountFormat.abbreviate(1_100_000_000L));
		assertEquals("12B", CountFormat.abbreviate(12_000_000_000L));
	}

	@Test
	void exactCountsAreGrouped() {
		assertEquals("0", CountFormat.exact(0));
		assertEquals("999", CountFormat.exact(999));
		assertEquals("1,234,567", CountFormat.exact(1_234_567));
	}
}
