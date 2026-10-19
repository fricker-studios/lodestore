package dev.fricker.lodestore.storage;

import java.util.ArrayList;
import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * What a drawer holds beyond the stacks anyone can see: per slot, how many more of the same item
 * are parked behind a full stack.
 *
 * <p>This is deliberately a list of counts and nothing else. The item each count refers to is the
 * stack in the same slot of {@code minecraft:container}, on the dropped item, or of the saved
 * {@code Items} list, on disk. Carrying the item here as well would describe an over-stacked
 * drawer's contents twice, and two descriptions can disagree — and a disagreement about how many of
 * something exist is a duplication bug waiting to happen. A count with no stack to attach to is
 * dropped on read, so the worst an inconsistent pair can do is lose what the editor already lost.
 *
 * <p>Why a count has to be stored apart from the stack at all: {@code ItemStack}'s codec caps
 * {@code count} at 99 and the {@code minecraft:container} component validates against the item's
 * own maximum, so over-stacked contents cannot be written as stacks by any route. See the README,
 * "Why the storage layer exists".
 *
 * @param entries one per slot holding a remainder, in slot order
 */
public record DrawerOverflow(List<Entry> entries) {
	/** A slot's parked count. {@code count} is always positive; zero is expressed by absence. */
	public record Entry(int slot, long count) {
		public static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.intRange(0, 255).fieldOf("slot").forGetter(Entry::slot),
				Codec.LONG.fieldOf("count").forGetter(Entry::count)
		).apply(instance, Entry::new));

		public static final StreamCodec<ByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, Entry::slot,
				ByteBufCodecs.VAR_LONG, Entry::count,
				Entry::new);
	}

	public static final DrawerOverflow EMPTY = new DrawerOverflow(List.of());

	public static final Codec<DrawerOverflow> CODEC =
			Entry.CODEC.listOf().xmap(DrawerOverflow::new, DrawerOverflow::entries);

	public static final StreamCodec<ByteBuf, DrawerOverflow> STREAM_CODEC =
			Entry.STREAM_CODEC.apply(ByteBufCodecs.list(256)).map(DrawerOverflow::new, DrawerOverflow::entries);

	public DrawerOverflow {
		entries = List.copyOf(entries);
	}

	/** Builds the sparse list from a dense array of remainders, skipping the zeros. */
	public static DrawerOverflow of(long[] remainders) {
		List<Entry> entries = new ArrayList<>();

		for (int slot = 0; slot < remainders.length; slot++) {
			if (remainders[slot] > 0) {
				entries.add(new Entry(slot, remainders[slot]));
			}
		}

		return entries.isEmpty() ? EMPTY : new DrawerOverflow(entries);
	}

	public boolean isEmpty() {
		return entries.isEmpty();
	}

	/** Everything parked, summed across slots — what the tooltip has to say. */
	public long total() {
		long total = 0;

		for (Entry entry : entries) {
			total += Math.max(0, entry.count());
		}

		return total;
	}
}
