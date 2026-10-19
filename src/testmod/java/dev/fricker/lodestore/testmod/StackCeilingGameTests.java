package dev.fricker.lodestore.testmod;

import java.util.List;

import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.gametest.framework.GameTestHelper;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.transfer.v1.item.ContainerStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;

/**
 * Why the storage layer exists.
 *
 * <p>A stack upgrade — targeted slots that hold more than a normal stack — was first attempted
 * through vanilla's {@code Container} and abandoned there. These tests are what the measurement
 * found, kept as assertions rather than as prose, because every one of them is a limit in Minecraft
 * rather than in Lodestore. They are also the design constraints of the storage layer that was
 * built instead: a crate is no longer a {@code Container}, holds each slot as a legal stack plus a
 * parked count, and never constructs the over-sized stack these tests show cannot survive. If a
 * later version lifts one of them, the test goes red and someone gets to ask whether the layer
 * still needs to work the way it does.
 *
 * <p>The full argument is in the README under "Why the storage layer exists". The short version:
 * raising {@code Container#getMaxStackSize} works for the screen, for pipes and for the comparator,
 * and is ignored by the vanilla hopper and by the {@code minecraft:container} component that
 * carries a crate's contents when it is broken. The second of those destroys items silently.
 *
 * <p>Nothing here touches a crate. That is deliberate: these are vanilla's rules, and a test that
 * went through a crate would be measuring Lodestore's reaction to them instead of the rules
 * themselves. {@code StorageLayerGameTests} is where the crate's reaction is measured.
 */
public class StackCeilingGameTests {
	/** A ceiling well past any item's own, and past the 99 the codecs allow, so both walls show. */
	private static final int RAISED_CEILING = 256;

	/**
	 * The vanilla hopper does not ask the container how much a slot holds.
	 *
	 * <p>This is the finding that stops a stack upgrade on its own, and it is structural rather
	 * than a matter of degree. {@code HopperBlockEntity.tryMoveInItem} computes the room in a slot
	 * as {@code itemStack.getMaxStackSize() - current.getCount()} — the item's own limit, with the
	 * container nowhere in the expression. So a crate can tell the screen and the transfer API
	 * that a slot holds 256, and a hopper will still stop at 64 and then sit there full.
	 *
	 * <p>Fabric's hopper patch does not rescue a container. {@code HopperBlockEntityMixin} takes the
	 * transfer-API route only {@code if (container == null)} — which is exactly why a crate stopped
	 * being one.
	 */
	@GameTest
	public void theHopperIgnoresTheContainersStackCeiling(GameTestHelper helper) {
		RaisedCeilingContainer container = new RaisedCeilingContainer(1);
		container.setItem(0, new ItemStack(Items.COBBLESTONE, 64));

		// Exactly what a hopper above a crate runs, against a container that says 256 is fine.
		ItemStack remainder = HopperBlockEntity.addItem(
				null, container, new ItemStack(Items.COBBLESTONE, 32), Direction.DOWN);

		if (container.getMaxStackSize(new ItemStack(Items.COBBLESTONE)) != RAISED_CEILING) {
			throw helper.assertionException("the test container is not reporting a raised ceiling; "
					+ "it says " + container.getMaxStackSize(new ItemStack(Items.COBBLESTONE)));
		}

		if (remainder.getCount() != 32 || container.getItem(0).getCount() != 64) {
			throw helper.assertionException("the hopper now honours the container ceiling: it moved "
					+ (32 - remainder.getCount()) + " into a slot the container said had room, "
					+ "leaving the slot at " + container.getItem(0).getCount()
					+ ". The storage layer's premise has shifted — see the README.");
		}

		helper.succeed();
	}

	/**
	 * The transfer API, by contrast, honours it — which is the disagreement, not a consolation.
	 *
	 * <p>{@code ContainerSlotWrapper#getCapacity} returns {@code container.getMaxStackSize(stack)},
	 * so a pipe would happily fill a slot to 256 while the hopper bolted to the other side of the
	 * same crate refused to pass 64. Two kinds of automation would disagree about whether the
	 * crate is full, which is worse than neither of them over-stacking.
	 */
	@GameTest
	public void theTransferApiHonoursTheContainersStackCeiling(GameTestHelper helper) {
		RaisedCeilingContainer container = new RaisedCeilingContainer(1);
		container.setItem(0, new ItemStack(Items.COBBLESTONE, 64));

		long accepted;

		try (Transaction transaction = Transaction.openOuter()) {
			accepted = ContainerStorage.of(container, null)
					.insert(ItemVariant.of(Items.COBBLESTONE), 32, transaction);
			transaction.abort();
		}

		if (accepted != 32) {
			throw helper.assertionException("the transfer API no longer honours the container "
					+ "ceiling: it accepted " + accepted + " of 32 into a slot holding 64 of a "
					+ "container that allows " + RAISED_CEILING);
		}

		helper.succeed();
	}

	/**
	 * A stack past the item's own limit does not survive {@code minecraft:container}.
	 *
	 * <p>This is the one that loses items, and it is why the storage layer carries a slot's parked
	 * count in a component of its own rather than in a bigger stack. Sturdy and reinforced crates
	 * carry their contents inside the dropped item through this component; reading it back goes
	 * through {@code ItemStackTemplate#create}, which validates and returns {@code ItemStack.EMPTY}
	 * for anything over-sized. The slot is gone, with a line in the log and nothing else.
	 *
	 * <p>Note the boundary: the item's own maximum, not the 99 the codecs allow. 65 cobblestone is
	 * already destroyed. So the component cannot carry an over-stack of <em>any</em> size.
	 */
	@GameTest
	public void theContainerComponentDestroysAnOverSizedStack(GameTestHelper helper) {
		int itemMax = new ItemStack(Items.COBBLESTONE).getMaxStackSize();

		if (roundTripThroughComponent(itemMax).getCount() != itemMax) {
			throw helper.assertionException("a full normal stack did not survive the container "
					+ "component; the test is measuring the wrong thing");
		}

		for (int count : new int[] {itemMax + 1, 99, 128}) {
			ItemStack back = roundTripThroughComponent(count);

			if (!back.isEmpty()) {
				throw helper.assertionException("minecraft:container now carries " + count
						+ " cobblestone, coming back as " + back.getCount()
						+ ". The storage layer's premise has shifted — see the README.");
			}
		}

		helper.succeed();
	}

	/**
	 * 99 is a hard ceiling on disk, whatever the container says.
	 *
	 * <p>{@code ContainerHelper.saveAllItems} writes through {@code ItemStack.MAP_CODEC}, whose
	 * count field is {@code intRange(1, 99)}. A larger stack does not throw and does not clamp: the
	 * encode fails, the problem reporter swallows it, and the slot is simply absent from the tag.
	 * The crate reloads a slot short and nothing anywhere says why.
	 *
	 * <p>So a count above a stack has to be saved apart from the stack, which is what the storage
	 * layer's remainder table is.
	 */
	@GameTest
	public void overNinetyNineIsSilentlyDroppedOnSave(GameTestHelper helper) {
		if (roundTripThroughDisk(helper, 99).getCount() != 99) {
			throw helper.assertionException("99 no longer survives a save; the ceiling moved down");
		}

		for (int count : new int[] {100, 128, RAISED_CEILING}) {
			ItemStack back = roundTripThroughDisk(helper, count);

			if (!back.isEmpty()) {
				throw helper.assertionException("a stack of " + count + " now survives a save, "
						+ "coming back as " + back.getCount()
						+ ". The storage layer's premise has shifted — see the README.");
			}
		}

		helper.succeed();
	}

	/**
	 * An over-sized stack would not have stayed in the crate.
	 *
	 * <p>{@code AbstractContainerMenu#doClick} takes a slot's whole contents onto the cursor with
	 * {@code slot.tryRemove(clicked.getCount(), Integer.MAX_VALUE, player)} — no container ceiling,
	 * no item limit. One left click and the over-stack is loose: on the cursor, then in an
	 * inventory or on the ground as an item entity. At that point it is past every check a crate
	 * could make, and over 99 it cannot be written at all, so it is destroyed by the next save.
	 * The storage layer's answer is that a slot never holds more than a legal stack to take.
	 */
	@GameTest
	public void anOverSizedStackEscapesASlotAndCannotBeSaved(GameTestHelper helper) {
		// Has to be the raised-ceiling container: SimpleContainer#setItem calls limitSize against
		// its own ceiling, so a plain one clamps the stack to 64 on the way in and there would be
		// no over-stack left to escape.
		Container container = new RaisedCeilingContainer(1);
		container.setItem(0, new ItemStack(Items.COBBLESTONE, RAISED_CEILING));

		net.minecraft.world.inventory.Slot slot =
				new net.minecraft.world.inventory.Slot(container, 0, 0, 0);
		ItemStack taken = slot.safeTake(RAISED_CEILING, Integer.MAX_VALUE,
				helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL));

		if (taken.getCount() != RAISED_CEILING) {
			throw helper.assertionException("a slot no longer hands over an over-sized stack whole; "
					+ "it gave " + taken.getCount() + " of " + RAISED_CEILING);
		}

		boolean encodable = ItemStack.CODEC
				.encodeStart(helper.getLevel().registryAccess()
						.createSerializationContext(NbtOps.INSTANCE), taken)
				.isSuccess();

		if (encodable) {
			throw helper.assertionException("an escaped stack of " + RAISED_CEILING
					+ " can now be written to disk. The storage layer's premise has shifted — "
					+ "see the README.");
		}

		helper.succeed();
	}

	private static ItemStack roundTripThroughComponent(int count) {
		ItemContainerContents contents =
				ItemContainerContents.fromItems(List.of(new ItemStack(Items.COBBLESTONE, count)));
		NonNullList<ItemStack> out = NonNullList.withSize(1, ItemStack.EMPTY);
		contents.copyInto(out);
		return out.getFirst();
	}

	private static ItemStack roundTripThroughDisk(GameTestHelper helper, int count) {
		NonNullList<ItemStack> items = NonNullList.withSize(1, ItemStack.EMPTY);
		items.set(0, new ItemStack(Items.COBBLESTONE, count));

		TagValueOutput out = TagValueOutput.createWithContext(
				ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
		ContainerHelper.saveAllItems(out, items);
		CompoundTag written = out.buildResult();

		NonNullList<ItemStack> back = NonNullList.withSize(1, ItemStack.EMPTY);
		ContainerHelper.loadAllItems(TagValueInput.create(
				ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), written), back);

		return back.getFirst();
	}

	/**
	 * A container that claims every slot holds {@link #RAISED_CEILING}, through both hooks.
	 *
	 * <p>Both matter and the pair is easy to get wrong: {@code getMaxStackSize()} alone changes
	 * nothing, because the default {@code getMaxStackSize(ItemStack)} is
	 * {@code min(getMaxStackSize(), stack.getMaxStackSize())} and the item's own limit wins. A
	 * first run of this measurement overrode only the first and concluded the hopper was being
	 * obeyed when in fact nothing had been raised at all.
	 *
	 * <p>{@link SimpleContainer#setItem} clamps to the ceiling, which with both overridden is what
	 * lets a slot hold more than a stack in the first place.
	 */
	private static final class RaisedCeilingContainer extends SimpleContainer {
		RaisedCeilingContainer(int size) {
			super(size);
		}

		@Override
		public int getMaxStackSize() {
			return RAISED_CEILING;
		}

		@Override
		public int getMaxStackSize(ItemStack stack) {
			return RAISED_CEILING;
		}
	}
}
