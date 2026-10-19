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
		assertEquals(new DrawerUpgrade(true, false, 1, 1), decode("lock"),
				"the shipped lock upgrade must set locks_contents, or it is an inert item");
	}

	/**
	 * The void upgrade voids without locking.
	 *
	 * <p>It used to have to lock as well, because voiding was a drawer-wide switch and the filter
	 * was the only thing keeping it from destroying whatever arrived. Voiding now only ever applies
	 * to what the drawer already holds, so the lock flag is no longer what makes it safe.
	 */
	@Test
	void theVoidUpgradeVoidsWithoutLocking() throws IOException {
		assertEquals(new DrawerUpgrade(false, true, 1, 1), decode("void"),
				"the shipped void upgrade must void, and no longer needs to lock to be safe");
	}

	/**
	 * The stack upgrade sets its two depths and nothing else; the layer it needs is what the rest
	 * of this change built. Both default to one everywhere they are not written, so every upgrade
	 * that predates them is a one-stack upgrade without saying so.
	 */
	@Test
	void theStackUpgradeStacks() throws IOException {
		assertEquals(new DrawerUpgrade(false, false, 4, 8), decode("stack"),
				"the shipped stack upgrade must set stacks_per_slot above one, or it is an inert item");
		assertTrue(decode("stack").stacks());
		assertFalse(decode("lock").stacks(), "a lock written without stacks_per_slot must read as depth one");
	}

	/** Levels are data: 256, 512 and 1,024 of a 64-stack, and 8, 16 and 32 of a tool. */
	@Test
	void stackLevelsGoDeeperThanOneAnother() throws IOException {
		DrawerUpgrade one = decode("stack");
		DrawerUpgrade two = decode("stack_2");
		DrawerUpgrade three = decode("stack_3");

		assertEquals(4, one.stacksPerSlot());
		assertEquals(8, two.stacksPerSlot());
		assertEquals(16, three.stacksPerSlot());
		assertEquals(8, one.unstackablePerSlot());
		assertEquals(16, two.unstackablePerSlot());
		assertEquals(32, three.unstackablePerSlot());
	}

	/**
	 * The lock covers the whole drawer, so it has no reach to state, and a pack written when it
	 * did must still load: the old {@code slot_capacity} is ignored rather than rejected.
	 */
	@Test
	void anOldLockWithAReachStillDecodes() {
		DataResult<DrawerUpgrade> parsed = DrawerUpgrade.CODEC.parse(JsonOps.INSTANCE,
				JsonParser.parseString("{\"locks_contents\":true,\"slot_capacity\":9}"));

		assertTrue(parsed.isSuccess(), () -> "an old lock entry failed to decode: "
				+ parsed.error().map(Object::toString).orElse("?"));
		assertEquals(new DrawerUpgrade(true, false, 1, 1), parsed.getOrThrow());
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
		assertFalse(DrawerUpgrade.FALLBACK.stacks(),
				"an unresolved upgrade must not deepen any slots");
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
