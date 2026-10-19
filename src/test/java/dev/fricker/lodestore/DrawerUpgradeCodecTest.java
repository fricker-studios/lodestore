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

import dev.fricker.lodestore.api.drawer.DrawerUpgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the contract between the shipped datapack JSON and {@link DrawerUpgrade#CODEC}.
 *
 * <p>Same failure mode as the tiers: a renamed field does not throw, the entry is dropped, and
 * every drawer carrying that upgrade silently falls back to doing nothing. For the lock that means
 * a drawer the player believes is filtered quietly accepting anything.
 *
 * <p>Runs without bootstrapping Minecraft: codecs are pure DataFixerUpper.
 */
class DrawerUpgradeCodecTest {
	private static final Path UPGRADE_DIR = Path.of("src/main/resources/data/lodestore/lodestore/drawer_upgrade");

	@Test
	void shippedUpgradeFilesExist() throws IOException {
		assertTrue(Files.isDirectory(UPGRADE_DIR), "upgrade directory missing: " + UPGRADE_DIR.toAbsolutePath());
		assertFalse(upgradeFiles().isEmpty(), "no drawer upgrade JSON files found");
	}

	@Test
	void everyShippedUpgradeDecodes() throws IOException {
		for (Path file : upgradeFiles()) {
			JsonElement json = JsonParser.parseString(Files.readString(file));
			DataResult<DrawerUpgrade> result = DrawerUpgrade.CODEC.parse(JsonOps.INSTANCE, json);

			assertTrue(result.isSuccess(),
					() -> "failed to decode " + file.getFileName() + ": "
							+ result.error().map(Object::toString).orElse("?"));
		}
	}

	@Test
	void theLockUpgradeActuallyLocks() throws IOException {
		assertEquals(new DrawerUpgrade(true, false), decode("lock"),
				"the shipped lock upgrade must set locks_contents, or it is an inert item");
	}

	/**
	 * The void upgrade locks as well as voids. Voiding is only ever applied to the item a drawer
	 * is locked to, so an entry that voided without locking would be inert — and an item that
	 * destroys things being quietly inert is the worse half of that bargain to get wrong.
	 */
	@Test
	void theVoidUpgradeVoidsAndLocks() throws IOException {
		assertEquals(new DrawerUpgrade(true, true), decode("void"),
				"the shipped void upgrade must set both locks_contents and voids_overflow");
	}

	/**
	 * Upgrades written before voiding existed must keep decoding, so the flag has to be optional
	 * and default to the harmless reading.
	 */
	@Test
	void anUpgradeWithoutTheVoidFlagDoesNotVoid() {
		DrawerUpgrade parsed = DrawerUpgrade.CODEC
				.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"locks_contents\":true}"))
				.getOrThrow();

		assertFalse(parsed.voidsOverflow(), "a missing voids_overflow must not destroy anything");
	}

	/** The fallback has to be inert: an unresolved upgrade must not filter anything. */
	@Test
	void theFallbackDoesNothing() {
		assertFalse(DrawerUpgrade.FALLBACK.locksContents(),
				"an unresolved upgrade must not lock a drawer the player never locked");
		assertFalse(DrawerUpgrade.FALLBACK.voidsOverflow(),
				"an unresolved upgrade must not destroy items the player never asked it to");
	}

	@Test
	void roundTripsThroughTheCodec() throws IOException {
		for (Path file : upgradeFiles()) {
			DrawerUpgrade original = DrawerUpgrade.CODEC
					.parse(JsonOps.INSTANCE, JsonParser.parseString(Files.readString(file))).getOrThrow();
			JsonElement encoded = DrawerUpgrade.CODEC.encodeStart(JsonOps.INSTANCE, original).getOrThrow();

			assertEquals(original, DrawerUpgrade.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow(),
					() -> "round trip changed " + file.getFileName());
		}
	}

	@Test
	void rejectsInvalidValues() {
		assertTrue(DrawerUpgrade.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{}")).isError(),
				"a missing locks_contents should be rejected rather than defaulting to inert");
		assertTrue(DrawerUpgrade.CODEC.parse(JsonOps.INSTANCE,
						JsonParser.parseString("{\"locks_contents\":\"yes\"}")).isError(),
				"a non-boolean locks_contents should be rejected");
	}

	private static DrawerUpgrade decode(String name) throws IOException {
		Path file = UPGRADE_DIR.resolve(name + ".json");
		return DrawerUpgrade.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(Files.readString(file))).getOrThrow();
	}

	private static List<Path> upgradeFiles() throws IOException {
		try (Stream<Path> stream = Files.list(UPGRADE_DIR)) {
			return new ArrayList<>(stream.filter(p -> p.toString().endsWith(".json")).toList());
		}
	}
}
