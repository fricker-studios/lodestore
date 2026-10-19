package dev.fricker.lodestore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.Test;

import dev.fricker.lodestore.api.drawer.DrawerTier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the contract between the shipped datapack JSON and {@link DrawerTier#CODEC}.
 *
 * <p>A mismatch here (a renamed field, an out-of-range value) does not fail the build or throw at
 * runtime — the entry is silently dropped and every drawer of that tier quietly falls back. That is
 * exactly the kind of failure worth catching in CI rather than in a world.
 *
 * <p>Runs without bootstrapping Minecraft: codecs are pure DataFixerUpper.
 */
class DrawerTierCodecTest {
	private static final Path TIER_DIR = Path.of("src/main/resources/data/lodestore/lodestore/drawer_tier");

	@Test
	void shippedTierFilesExist() throws IOException {
		assertTrue(Files.isDirectory(TIER_DIR), "tier directory missing: " + TIER_DIR.toAbsolutePath());
		assertFalse(tierFiles().isEmpty(), "no drawer tier JSON files found");
	}

	@Test
	void everyShippedTierDecodes() throws IOException {
		for (Path file : tierFiles()) {
			JsonElement json = JsonParser.parseString(Files.readString(file));
			DataResult<DrawerTier> result = DrawerTier.CODEC.parse(JsonOps.INSTANCE, json);

			assertTrue(result.isSuccess(),
					() -> "failed to decode " + file.getFileName() + ": " + result.error().map(Object::toString).orElse("?"));

			DrawerTier tier = result.getOrThrow();
			assertTrue(tier.nestingRank() >= 0, () -> file.getFileName() + " has a negative nesting_rank");
		}
	}

	@Test
	void tiersDecodeToTheirIntendedValues() throws IOException {
		assertEquals(new DrawerTier(false, 1, 1), decode("basic"));
		assertEquals(new DrawerTier(true, 2, 2), decode("sturdy"));
		assertEquals(new DrawerTier(true, 3, 3), decode("reinforced"));
	}

	/**
	 * The nesting rule is ordering-based, so it is only correct if the shipped ranks are ordered.
	 * A pack that flattened them would silently allow a drawer inside itself.
	 */
	@Test
	void nestingRanksAreOrderedAndEnforced() throws IOException {
		DrawerTier basic = decode("basic");
		DrawerTier sturdy = decode("sturdy");
		DrawerTier reinforced = decode("reinforced");

		assertTrue(sturdy.nestingRank() < reinforced.nestingRank(), "sturdy must outrank below reinforced");

		assertFalse(sturdy.canContain(sturdy), "sturdy must refuse a sturdy drawer");
		assertFalse(sturdy.canContain(reinforced), "sturdy must refuse a reinforced drawer");
		assertTrue(reinforced.canContain(sturdy), "reinforced must accept a sturdy drawer");
		assertFalse(reinforced.canContain(reinforced), "reinforced must refuse a reinforced drawer");

		// A basic drawer spills when broken, so nesting into it compounds nothing.
		assertTrue(basic.canContain(reinforced), "basic should accept any drawer");
	}

	@Test
	void roundTripsThroughTheCodec() throws IOException {
		for (Path file : tierFiles()) {
			DrawerTier original = DrawerTier.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(Files.readString(file))).getOrThrow();
			JsonElement encoded = DrawerTier.CODEC.encodeStart(JsonOps.INSTANCE, original).getOrThrow();
			assertEquals(original, DrawerTier.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow(),
					() -> "round trip changed " + file.getFileName());
		}
	}

	/**
	 * Each case is complete except for the one thing under test. A JSON that is wrong in two ways
	 * is rejected whichever check fires first, so it proves nothing about either.
	 */
	@Test
	void rejectsInvalidValues() {
		assertTrue(DrawerTier.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
						"{\"keeps_contents\":true,\"nesting_rank\":-1,\"upgrade_slots\":1}")).isError(),
				"a negative nesting_rank should be rejected by the codec's range");
		assertTrue(DrawerTier.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
						"{\"keeps_contents\":true,\"upgrade_slots\":1}")).isError(),
				"missing nesting_rank should be rejected");
		assertTrue(DrawerTier.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
						"{\"keeps_contents\":true,\"nesting_rank\":1}")).isError(),
				"missing upgrade_slots should be rejected");
		assertTrue(DrawerTier.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
						"{\"keeps_contents\":true,\"nesting_rank\":1,\"upgrade_slots\":"
								+ (DrawerTier.MAX_UPGRADE_SLOTS + 1) + "}")).isError(),
				"upgrade_slots past the screen's capacity should be rejected by the codec's range");
	}

	/**
	 * The upgrade row is laid out in code, so a pack cannot ask for more slots than the screen can
	 * draw. A tier that exceeded the cap would be dropped outright and every drawer of it would
	 * fall back to accepting no upgrades at all.
	 */
	@Test
	void shippedTiersFitTheUpgradeRow() throws IOException {
		for (Path file : tierFiles()) {
			DrawerTier tier = DrawerTier.CODEC
					.parse(JsonOps.INSTANCE, JsonParser.parseString(Files.readString(file))).getOrThrow();

			assertTrue(tier.upgradeSlots() <= DrawerTier.MAX_UPGRADE_SLOTS,
					() -> file.getFileName() + " asks for more upgrade slots than the screen has");
		}

		assertTrue(decode("basic").upgradeSlots() < decode("reinforced").upgradeSlots(),
				"a reinforced drawer should take more upgrades than a basic one, or the tier buys nothing");
	}

	/** An unresolved tier must accept no upgrades rather than silently allowing some. */
	@Test
	void theFallbackAcceptsNoUpgrades() {
		assertEquals(0, DrawerTier.FALLBACK.upgradeSlots());
	}

	private static DrawerTier decode(String name) throws IOException {
		Path file = TIER_DIR.resolve(name + ".json");
		return DrawerTier.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(Files.readString(file))).getOrThrow();
	}

	private static List<Path> tierFiles() throws IOException {
		try (Stream<Path> stream = Files.list(TIER_DIR)) {
			return new ArrayList<>(stream.filter(p -> p.toString().endsWith(".json")).toList());
		}
	}
}
