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
			assertTrue(tier.slots() >= 1, () -> file.getFileName() + " has non-positive slots");
			assertTrue(tier.stackCapacity() >= 1, () -> file.getFileName() + " has non-positive stack_capacity");
		}
	}

	@Test
	void tiersDecodeToTheirIntendedValues() throws IOException {
		assertEquals(new DrawerTier(1, 8), decode("basic"));
		assertEquals(new DrawerTier(1, 32), decode("sturdy"));
		assertEquals(new DrawerTier(1, 128), decode("reinforced"));
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

	@Test
	void rejectsInvalidValues() {
		assertTrue(DrawerTier.CODEC.parse(JsonOps.INSTANCE,
				JsonParser.parseString("{\"slots\":0,\"stack_capacity\":8}")).isError(),
				"slots=0 should be rejected by the codec's range");
		assertTrue(DrawerTier.CODEC.parse(JsonOps.INSTANCE,
				JsonParser.parseString("{\"slots\":1}")).isError(),
				"missing stack_capacity should be rejected");
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
