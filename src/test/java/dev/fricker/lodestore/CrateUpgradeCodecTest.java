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

import dev.fricker.lodestore.api.crate.CrateUpgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the contract between the shipped datapack JSON and {@link CrateUpgrade#CODEC}.
 *
 * <p>Same failure mode as the tiers: a renamed field does not throw, the entry is dropped, and
 * every crate carrying that upgrade silently falls back to doing nothing. For the lock that means
 * a crate the player believes is filtered quietly accepting anything.
 *
 * <p>Runs without bootstrapping Minecraft: codecs are pure DataFixerUpper.
 */
class CrateUpgradeCodecTest {
	private static final Path UPGRADE_DIR = Path.of("src/main/resources/data/lodestore/lodestore/crate_upgrade");

	@Test
	void shippedUpgradeFilesExist() throws IOException {
		assertTrue(Files.isDirectory(UPGRADE_DIR), "upgrade directory missing: " + UPGRADE_DIR.toAbsolutePath());
		assertFalse(upgradeFiles().isEmpty(), "no crate upgrade JSON files found");
	}

	@Test
	void everyShippedUpgradeDecodes() throws IOException {
		for (Path file : upgradeFiles()) {
			JsonElement json = JsonParser.parseString(Files.readString(file));
			DataResult<CrateUpgrade> result = CrateUpgrade.CODEC.parse(JsonOps.INSTANCE, json);

			assertTrue(result.isSuccess(),
					() -> "failed to decode " + file.getFileName() + ": "
							+ result.error().map(Object::toString).orElse("?"));
		}
	}

	@Test
	void theLockUpgradeActuallyLocks() throws IOException {
		assertEquals(new CrateUpgrade(true, false, 1, 1, false, false, false), decode("lock"),
				"the shipped lock upgrade must set locks_contents, or it is an inert item");
	}

	/**
	 * The void upgrade voids without locking.
	 *
	 * <p>It used to have to lock as well, because voiding was a crate-wide switch and the filter
	 * was the only thing keeping it from destroying whatever arrived. Voiding now only ever applies
	 * to what the crate already holds, so the lock flag is no longer what makes it safe.
	 */
	@Test
	void theVoidUpgradeVoidsWithoutLocking() throws IOException {
		assertEquals(new CrateUpgrade(false, true, 1, 1, false, false, false), decode("void"),
				"the shipped void upgrade must void, and no longer needs to lock to be safe");
	}

	/**
	 * The stack upgrade sets its two depths and nothing else; the layer it needs is what the rest
	 * of this change built. Both default to one everywhere they are not written, so every upgrade
	 * that predates them is a one-stack upgrade without saying so.
	 */
	@Test
	void theStackUpgradeStacks() throws IOException {
		assertEquals(new CrateUpgrade(false, false, 4, 8, false, false, false), decode("stack"),
				"the shipped stack upgrade must set stacks_per_slot above one, or it is an inert item");
		assertTrue(decode("stack").stacks());
		assertFalse(decode("lock").stacks(), "a lock written without stacks_per_slot must read as depth one");
	}

	/** Levels are data: 256, 512 and 1,024 of a 64-stack, and 8, 16 and 32 of a tool. */
	@Test
	void stackLevelsGoDeeperThanOneAnother() throws IOException {
		CrateUpgrade one = decode("stack");
		CrateUpgrade two = decode("stack_2");
		CrateUpgrade three = decode("stack_3");

		assertEquals(4, one.stacksPerSlot());
		assertEquals(8, two.stacksPerSlot());
		assertEquals(16, three.stacksPerSlot());
		assertEquals(8, one.unstackablePerSlot());
		assertEquals(16, two.unstackablePerSlot());
		assertEquals(32, three.unstackablePerSlot());
	}

	/**
	 * The lock covers the whole crate, so it has no reach to state, and a pack written when it
	 * did must still load: the old {@code slot_capacity} is ignored rather than rejected.
	 */
	@Test
	void anOldLockWithAReachStillDecodes() {
		DataResult<CrateUpgrade> parsed = CrateUpgrade.CODEC.parse(JsonOps.INSTANCE,
				JsonParser.parseString("{\"locks_contents\":true,\"slot_capacity\":9}"));

		assertTrue(parsed.isSuccess(), () -> "an old lock entry failed to decode: "
				+ parsed.error().map(Object::toString).orElse("?"));
		assertEquals(new CrateUpgrade(true, false, 1, 1, false, false, false), parsed.getOrThrow());
	}

	/**
	 * Upgrades written before voiding existed must keep decoding, so the flag has to be optional
	 * and default to the harmless reading.
	 */
	@Test
	void anUpgradeWithoutTheVoidFlagDoesNotVoid() {
		CrateUpgrade parsed = CrateUpgrade.CODEC
				.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"locks_contents\":true}"))
				.getOrThrow();

		assertFalse(parsed.voidsOverflow(), "a missing voids_overflow must not destroy anything");
	}

	/**
	 * The three upgrades that work on the contents each set exactly their own flag. Each changes
	 * what a crate holds, so a dropped entry would silently stop a crate doing what the player
	 * installed it for, and a flag set by accident would silently start.
	 */
	@Test
	void theRefiningUpgradesEachSetTheirOwnFlag() throws IOException {
		assertEquals(new CrateUpgrade(false, false, 1, 1, true, false, false), decode("compress"),
				"the shipped compress upgrade must compress and nothing else");
		assertEquals(new CrateUpgrade(false, false, 1, 1, false, true, false), decode("disenchant"),
				"the shipped disenchant upgrade must disenchant and nothing else");
		assertEquals(new CrateUpgrade(false, false, 1, 1, false, false, true), decode("repair"),
				"the shipped repair upgrade must repair and nothing else");
	}

	/** Upgrades written before the refining flags existed must keep decoding, and do none of it. */
	@Test
	void anUpgradeWithoutTheRefiningFlagsRefinesNothing() {
		CrateUpgrade parsed = CrateUpgrade.CODEC
				.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"locks_contents\":true}"))
				.getOrThrow();

		assertFalse(parsed.compresses(), "a missing compresses must not craft anything");
		assertFalse(parsed.disenchants(), "a missing disenchants must not strip anything");
		assertFalse(parsed.repairs(), "a missing repairs must not combine anything");
	}

	/** The fallback has to be inert: an unresolved upgrade must not filter anything. */
	@Test
	void theFallbackDoesNothing() {
		assertFalse(CrateUpgrade.FALLBACK.locksContents(),
				"an unresolved upgrade must not lock a crate the player never locked");
		assertFalse(CrateUpgrade.FALLBACK.voidsOverflow(),
				"an unresolved upgrade must not destroy items the player never asked it to");
		assertFalse(CrateUpgrade.FALLBACK.stacks(),
				"an unresolved upgrade must not deepen any slots");
		assertFalse(CrateUpgrade.FALLBACK.compresses() || CrateUpgrade.FALLBACK.disenchants()
				|| CrateUpgrade.FALLBACK.repairs(), "an unresolved upgrade must not change what a crate holds");
	}

	@Test
	void roundTripsThroughTheCodec() throws IOException {
		for (Path file : upgradeFiles()) {
			CrateUpgrade original = CrateUpgrade.CODEC
					.parse(JsonOps.INSTANCE, JsonParser.parseString(Files.readString(file))).getOrThrow();
			JsonElement encoded = CrateUpgrade.CODEC.encodeStart(JsonOps.INSTANCE, original).getOrThrow();

			assertEquals(original, CrateUpgrade.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow(),
					() -> "round trip changed " + file.getFileName());
		}
	}

	@Test
	void rejectsInvalidValues() {
		assertTrue(CrateUpgrade.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{}")).isError(),
				"a missing locks_contents should be rejected rather than defaulting to inert");
		assertTrue(CrateUpgrade.CODEC.parse(JsonOps.INSTANCE,
						JsonParser.parseString("{\"locks_contents\":\"yes\"}")).isError(),
				"a non-boolean locks_contents should be rejected");
	}

	private static CrateUpgrade decode(String name) throws IOException {
		Path file = UPGRADE_DIR.resolve(name + ".json");
		return CrateUpgrade.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(Files.readString(file))).getOrThrow();
	}

	private static List<Path> upgradeFiles() throws IOException {
		try (Stream<Path> stream = Files.list(UPGRADE_DIR)) {
			return new ArrayList<>(stream.filter(p -> p.toString().endsWith(".json")).toList());
		}
	}
}
