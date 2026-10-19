package dev.fricker.lodestore.client.screen;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import dev.fricker.lodestore.Lodestore;
import dev.fricker.lodestore.api.LodestoreRegistries;
import dev.fricker.lodestore.api.drawer.DrawerUpgrade;
import dev.fricker.lodestore.api.drawer.UpgradeBehaviour;
import dev.fricker.lodestore.block.DrawerBlockEntity;
import dev.fricker.lodestore.item.DrawerUpgradeItem;
import dev.fricker.lodestore.menu.DrawerMenu;

/**
 * The drawer screen: a vanilla chest body with Lodestore's upgrade column beside it.
 *
 * <p>The body is vanilla's own texture, blitted the way vanilla blits it — the grid from the top of
 * the file and the player-inventory section from a fixed offset — so one texture serves any row
 * count and the drawer keeps looking like the chest it behaves as. Only the upgrade column is ours.
 *
 * <p>Right-clicking an installed lock turns on slot selection for it: the storage slots it covers
 * are tinted, and clicking one adds or removes it. Which slots it covers is read straight off the
 * drawer's own block entity, which every client near the block already has. Voiding and stacking
 * cover the whole drawer and have nothing to choose.
 *
 * <p>With a stack upgrade a slot holds more than the one legal stack it shows, so the count drawn on
 * it is the slot's real total rather than the stack's, scaled down when four digits would not fit.
 */
public class DrawerScreen extends AbstractContainerScreen<DrawerMenu> {
	private static final Identifier CHEST_BACKGROUND =
			Identifier.withDefaultNamespace("textures/gui/container/generic_54.png");
	private static final Identifier UPGRADE_PANEL = Lodestore.id("textures/gui/upgrade_panel.png");

	/** Width of the vanilla chest background; the upgrade column starts here. */
	private static final int BODY_WIDTH = 176;
	private static final int PANEL_WIDTH = 66;
	/**
	 * The panel's border above the first upgrade slot.
	 *
	 * <p>One pixel short of {@link DrawerMenu#FIRST_SLOT_Y}, because a slot's border is painted
	 * above and left of the item it holds rather than on it. Flush, every upgrade icon sits over
	 * its slot's top-left edge with bare slot showing at the bottom right.
	 */
	private static final int PANEL_TOP_CAP = DrawerMenu.FIRST_SLOT_Y - 1;
	private static final int PANEL_BOTTOM_CAP = 8;
	/** Where the panel's bottom cap starts in the texture. Mirrors tools/sprites/make_gui.py. */
	private static final int BOTTOM_BAND_V = 53;
	private static final int PANEL_TEXTURE_WIDTH = 128;
	private static final int PANEL_TEXTURE_HEIGHT = 128;

	/** The vanilla chest's own arithmetic, so the player's inventory lands where it always does. */
	private static final int CHROME_HEIGHT = 114;

	/** The lock's gold, the colour of its sprite's motif, on the slots it covers. */
	private static final int LOCK_TINT = 0x55F2C852;
	/** While choosing, the upgrade being edited is picked out more strongly. */
	private static final int SELECTING_TINT = 0x99FFFFFF;
	/** A count is drawn no narrower than this, or it stops being legible at the usual GUI scale. */
	private static final float MIN_COUNT_SCALE = 0.6F;

	private final int rows;

	/** Which upgrade's slots are being chosen, or null. Purely a client mode. */
	private @Nullable UpgradeBehaviour selecting;
	/** The last slot toggled, so a shift-click can act on everything between it and the next. */
	private int lastToggled = -1;

	public DrawerScreen(DrawerMenu menu, Inventory inventory, Component title) {
		// The size goes through the constructor: imageWidth and imageHeight are final in 26.x,
		// so a screen cannot resize itself after the fact the way it used to.
		super(menu, inventory, title, BODY_WIDTH + PANEL_WIDTH, CHROME_HEIGHT + menu.rows() * 18);

		this.rows = menu.rows();
		this.inventoryLabelY = this.imageHeight - 94;
	}

	/** The drawer itself, which carries the targeting. Null if the chunk went away under us. */
	private @Nullable DrawerBlockEntity drawer() {
		if (minecraft == null || minecraft.level == null) {
			return null;
		}

		return minecraft.level.getBlockEntity(getMenu().pos()) instanceof DrawerBlockEntity drawer
				? drawer : null;
	}

	/** What an upgrade item does, resolved against the synced registry; null for anything else. */
	private @Nullable DrawerUpgrade upgradeOf(ItemStack stack) {
		if (!(stack.getItem() instanceof DrawerUpgradeItem item) || minecraft == null || minecraft.level == null) {
			return null;
		}

		return minecraft.level.registryAccess()
				.lookup(LodestoreRegistries.DRAWER_UPGRADE)
				.flatMap(registry -> registry.getOptional(item.upgradeKey()))
				.orElse(DrawerUpgrade.FALLBACK);
	}

	/** Which targetable behaviour an upgrade item owns. */
	private @Nullable UpgradeBehaviour behaviourOf(ItemStack stack) {
		DrawerUpgrade upgrade = upgradeOf(stack);

		if (upgrade == null) {
			return null;
		}

		for (UpgradeBehaviour behaviour : UpgradeBehaviour.values()) {
			if (behaviour.targetable() && behaviour.grantedBy(upgrade)) {
				return behaviour;
			}
		}

		return null;
	}

	private static boolean stacks(@Nullable DrawerUpgrade upgrade) {
		return upgrade != null && UpgradeBehaviour.STACK.grantedBy(upgrade);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(graphics, mouseX, mouseY, partialTick);

		int left = (this.width - this.imageWidth) / 2;
		int top = (this.height - this.imageHeight) / 2;
		int gridHeight = rows * 18 + 17;

		graphics.blit(RenderPipelines.GUI_TEXTURED, CHEST_BACKGROUND, left, top,
				0.0f, 0.0f, BODY_WIDTH, gridHeight, 256, 256);
		graphics.blit(RenderPipelines.GUI_TEXTURED, CHEST_BACKGROUND, left, top + gridHeight,
				0.0f, 126.0f, BODY_WIDTH, 96, 256, 256);

		// One texture holds the widest case; each drawer blits the rows it actually has.
		int panelLeft = left + BODY_WIDTH;
		int panelBody = PANEL_TOP_CAP + DrawerMenu.upgradeRows(getMenu().upgradeSlots()) * 18;

		graphics.blit(RenderPipelines.GUI_TEXTURED, UPGRADE_PANEL, panelLeft, top,
				0.0f, 0.0f, PANEL_WIDTH, panelBody, PANEL_TEXTURE_WIDTH, PANEL_TEXTURE_HEIGHT);
		graphics.blit(RenderPipelines.GUI_TEXTURED, UPGRADE_PANEL, panelLeft, top + panelBody,
				0.0f, BOTTOM_BAND_V, PANEL_WIDTH, PANEL_BOTTOM_CAP,
				PANEL_TEXTURE_WIDTH, PANEL_TEXTURE_HEIGHT);
	}

	/**
	 * Tints the storage slots the lock covers.
	 *
	 * <p>Drawn here rather than over the slots: this runs before them and in the same menu-local
	 * coordinates, so the tint sits under the items instead of washing them out.
	 */
	@Override
	protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		super.extractLabels(graphics, mouseX, mouseY);

		DrawerBlockEntity drawer = drawer();

		if (drawer == null) {
			return;
		}

		Set<Integer> lockSlots = drawer.lockedSlots();
		Set<Integer> chosenSlots = selecting == null ? Set.of() : drawer.coveredSlots(selecting);

		for (int slot = 0; slot < getMenu().storageEnd(); slot++) {
			int x = 8 + (slot % 9) * 18;
			int y = DrawerMenu.FIRST_SLOT_Y + (slot / 9) * 18;

			if (lockSlots.contains(slot)) {
				graphics.fill(x, y, x + 16, y + 16, LOCK_TINT);
			}

			if (chosenSlots.contains(slot)) {
				// An outline rather than a wash, so the item underneath stays readable.
				graphics.fill(x, y, x + 16, y + 1, SELECTING_TINT);
				graphics.fill(x, y + 15, x + 16, y + 16, SELECTING_TINT);
				graphics.fill(x, y, x + 1, y + 16, SELECTING_TINT);
				graphics.fill(x + 15, y, x + 16, y + 16, SELECTING_TINT);
			}
		}
	}

	/**
	 * Two tooltips of ours.
	 *
	 * <p>On a lock: what right-clicking it does. The gesture is not one anything else in the game
	 * teaches, and an upgrade whose reach can be changed looks exactly like one whose reach cannot.
	 * The hint goes here rather than on the item so it appears where the gesture works, and the
	 * covered count comes with it because "how far does this one actually reach" is the question
	 * the tint is answering.
	 *
	 * <p>On a storage slot of a stacked drawer: how much is in it, out of how much it could hold.
	 * The count drawn on the slot says the first; only this says the second.
	 */
	@Override
	protected List<Component> getTooltipFromContainerItem(ItemStack stack) {
		List<Component> tooltip = super.getTooltipFromContainerItem(stack);
		DrawerMenu menu = getMenu();

		if (hoveredSlot == null) {
			return tooltip;
		}

		DrawerBlockEntity drawer = drawer();

		if (hoveredSlot.index < menu.storageEnd()) {
			int slot = hoveredSlot.index;

			if (drawer == null || (!drawer.stacks() && drawer.remainder(slot) == 0)) {
				return tooltip;
			}

			List<Component> hinted = new ArrayList<>(tooltip);
			hinted.add(Component.translatable("lodestore.slot.stored",
							stack.getCount() + drawer.remainder(slot), drawer.slotCapacity(slot, stack))
					.withStyle(ChatFormatting.GRAY));
			return hinted;
		}

		if (hoveredSlot.index >= menu.upgradeEnd()) {
			return tooltip;
		}

		List<Component> hinted = new ArrayList<>(tooltip);

		// An upgrade the contents still need stays put, and a slot that silently refuses a click
		// looks broken; this is the one line that explains it.
		if (!menu.removable(hoveredSlot.index - menu.storageEnd())) {
			hinted.add(Component.translatable("lodestore.upgrade.needed").withStyle(ChatFormatting.RED));
		}

		// The stack upgrade's right-click gathers the stacks; the lock's chooses slots. Each says so
		// where the gesture works, since neither is one the game teaches.
		if (stacks(upgradeOf(stack))) {
			hinted.add(Component.translatable("lodestore.upgrade.compress").withStyle(ChatFormatting.GRAY));
		}

		UpgradeBehaviour behaviour = behaviourOf(stack);

		if (behaviour == null) {
			return hinted;
		}

		hinted.add(Component.translatable("lodestore.upgrade.adjust_locked").withStyle(ChatFormatting.GRAY));

		if (drawer != null) {
			int covered = drawer.coveredSlots(behaviour).size();
			hinted.add(Component.translatable("lodestore.upgrade.coverage", covered, menu.storageEnd())
					.withStyle(ChatFormatting.DARK_GRAY));
		}

		if (selecting == behaviour) {
			hinted.add(Component.translatable("lodestore.upgrade.selecting").withStyle(ChatFormatting.DARK_GRAY));
		}

		return hinted;
	}

	/**
	 * Draws a stacked drawer's slots with their real totals.
	 *
	 * <p>Vanilla draws the stack's own count, which is at most one legal stack — the slot's whole
	 * point is that there can be more behind it. This runs vanilla's own drawing with the total as
	 * the count text, and when the text is wider than the slot, draws it itself scaled down to
	 * fit, anchored where vanilla anchors a count. A slot in the drag-split preview is left to
	 * vanilla: it is predicting a placement, and the count it shows is the prediction. Only those
	 * slots, though — vanilla is in drag mode from the moment the button goes down on any slot
	 * with something carried, and handing every slot back to it for that moment made the whole
	 * drawer drop to plain counts until the release.
	 */
	@Override
	protected void extractSlot(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY) {
		DrawerBlockEntity drawer = drawer();
		ItemStack stack = slot.getItem();
		boolean dragging = isQuickCrafting && quickCraftSlots.contains(slot);

		if (drawer == null || dragging || slot.index >= getMenu().storageEnd()
				|| stack.isEmpty() || drawer.remainder(slot.index) == 0) {
			super.extractSlot(graphics, slot, mouseX, mouseY);
			return;
		}

		String total = String.valueOf(stack.getCount() + drawer.remainder(slot.index));
		int width = font.width(total);
		int seed = slot.x + slot.y * imageWidth;
		graphics.item(stack, slot.x, slot.y, seed);

		if (width <= 16) {
			graphics.itemDecorations(font, stack, slot.x, slot.y, total);
			return;
		}

		// Bar and cooldown as vanilla draws them, with an empty count; then the count, shrunk.
		graphics.itemDecorations(font, stack, slot.x, slot.y, "");
		float scale = Math.max(MIN_COUNT_SCALE, 16.0F / width);
		graphics.pose().pushMatrix();
		graphics.pose().translate(slot.x + 17 - width * scale, slot.y + 9 + (font.lineHeight - 1) * (1.0F - scale));
		graphics.pose().scale(scale, scale);
		graphics.text(font, total, 0, 0, -1, true);
		graphics.pose().popMatrix();
	}

	/**
	 * Right-click a lock to start choosing its slots; click storage slots to add or remove. Right-
	 * click the stack upgrade to gather the drawer's stacks.
	 *
	 * <p>Shift-click acts on everything between the last slot clicked and this one, so a row or a
	 * block of slots takes two clicks rather than nine.
	 */
	@Override
	protected void slotClicked(Slot slot, int slotId, int button, ContainerInput input) {
		DrawerMenu menu = getMenu();

		if (slot != null && button == 1 && slotId >= menu.storageEnd() && slotId < menu.upgradeEnd()) {
			if (stacks(upgradeOf(slot.getItem()))) {
				// Rides the same validated button packet the lock's targeting does.
				if (minecraft != null && minecraft.gameMode != null) {
					minecraft.gameMode.handleInventoryButtonClick(menu.containerId, DrawerMenu.COMPRESS_BUTTON);
				}

				return;
			}

			UpgradeBehaviour behaviour = behaviourOf(slot.getItem());
			selecting = behaviour == null || behaviour == selecting ? null : behaviour;
			lastToggled = -1;
			return;
		}

		if (selecting != null && slot != null && slotId >= 0 && slotId < menu.storageEnd()) {
			toggle(selecting, slotId, input == ContainerInput.QUICK_MOVE);
			return;
		}

		super.slotClicked(slot, slotId, button, input);
	}

	private void toggle(UpgradeBehaviour behaviour, int slotId, boolean range) {
		if (minecraft == null || minecraft.gameMode == null) {
			return;
		}

		int from = range && lastToggled >= 0 ? lastToggled : slotId;

		for (int slot = Math.min(from, slotId); slot <= Math.max(from, slotId); slot++) {
			minecraft.gameMode.handleInventoryButtonClick(getMenu().containerId,
					DrawerMenu.targetButton(behaviour, slot));
		}

		lastToggled = slotId;
	}
}
