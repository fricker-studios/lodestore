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
		assertEquals(new DrawerUpgrade(true, false, 9, 1), decode("lock"),
				"the shipped lock upgrade must set locks_contents, or it is an inert item");
	}

	/**
	 * The void upgrade voids without locking.
	 *
	 * <p>It used to have to lock as well, because voiding was a drawer-wide switch and the filter
	 * was the only thing keeping it from destroying whatever arrived. Voiding is now scoped by the
	 * slots it targets, and only ever applies to what one of those slots already holds or has
	 * claimed, so the lock flag is no longer what makes it safe.
	 */
	@Test
	void theVoidUpgradeVoidsWithoutLocking() throws IOException {
		assertEquals(new DrawerUpgrade(false, true, 9, 1), decode("void"),
				"the shipped void upgrade must void, and no longer needs to lock to be safe");
	}

	/**
	 * The stack upgrade sets a depth and nothing else; the layer it needs is what the rest of this
	 * change built. Depth defaults to one everywhere it is not written, so every upgrade that
	 * predates it is a one-stack upgrade without saying so.
	 */
	@Test
	void theStackUpgradeStacks() throws IOException {
		assertEquals(new DrawerUpgrade(false, false, 9, 4), decode("stack"),
				"the shipped stack upgrade must set stacks_per_slot above one, or it is an inert item");
		assertTrue(decode("stack").stacks());
		assertFalse(decode("lock").stacks(), "a lock written without stacks_per_slot must read as depth one");
	}

	/** Levels are data: the same behaviour, reaching further. */
	@Test
	void lockLevelsReachFurtherThanOneAnother() throws IOException {
		int one = decode("lock").slotCapacity();
		int two = decode("lock_2").slotCapacity();
		int three = decode("lock_3").slotCapacity();

		assertTrue(one < two && two < three,
				() -> "lock levels should reach further in turn, got " + one + ", " + two + ", " + three);

		assertEquals(two, one * 2,
				"two level-one locks must reach exactly as far as one level-two, or the arithmetic "
						+ "a player does in their head is wrong");
	}

	/**
	 * An upgrade that does not say how far it reaches reaches nothing. Defaulting to some
	 * non-zero number would hand a pack's half-written entry control of slots nobody granted it.
	 */
	@Test
	void anUpgradeWithoutACapacityReachesNothing() {
		DrawerUpgrade parsed = DrawerUpgrade.CODEC
				.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"locks_contents\":true}"))
				.getOrThrow();

		assertEquals(0, parsed.slotCapacity());
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
		assertEquals(0, DrawerUpgrade.FALLBACK.slotCapacity(),
				"an unresolved upgrade must not reach any slots");
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
