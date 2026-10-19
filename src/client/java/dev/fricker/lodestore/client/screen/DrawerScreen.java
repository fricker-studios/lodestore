package dev.fricker.lodestore.client.screen;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

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
 * <p>Every upgrade covers the whole drawer, so there are no slots to choose for one. What the lock
 * offers instead is the one slot whose item the front face shows: right-click the lock, then click a
 * slot holding something, and that slot is tinted in the lock's gold. Which slot it is comes straight
 * off the drawer's own block entity, which every client near the block already has.
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

	/** The lock's gold, the colour of its sprite's motif, on the slot the face shows. */
	private static final int LOCK_TINT = 0x55F2C852;
	/** While choosing, the slot already chosen is picked out more strongly. */
	private static final int SELECTING_TINT = 0x99FFFFFF;
	/** A count is drawn no narrower than this, or it stops being legible at the usual GUI scale. */
	private static final float MIN_COUNT_SCALE = 0.6F;

	private final int rows;

	/** Whether the next storage slot clicked is to be the one the face shows. Purely a client mode. */
	private boolean choosingDisplay;

	public DrawerScreen(DrawerMenu menu, Inventory inventory, Component title) {
		// The size goes through the constructor: imageWidth and imageHeight are final in 26.x,
		// so a screen cannot resize itself after the fact the way it used to.
		super(menu, inventory, title, BODY_WIDTH + PANEL_WIDTH, CHROME_HEIGHT + menu.rows() * 18);

		this.rows = menu.rows();
		this.inventoryLabelY = this.imageHeight - 94;
	}

	/** The drawer itself, which carries the chosen slot. Null if the chunk went away under us. */
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

	private static boolean stacks(@Nullable DrawerUpgrade upgrade) {
		return upgrade != null && UpgradeBehaviour.STACK.grantedBy(upgrade);
	}

	private static boolean locks(@Nullable DrawerUpgrade upgrade) {
		return upgrade != null && UpgradeBehaviour.LOCK.grantedBy(upgrade);
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
	 * Tints the slot the face shows.
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

		int slot = drawer.displaySlot();

		if (slot < 0 || slot >= getMenu().storageEnd()) {
			return;
		}

		int x = 8 + (slot % 9) * 18;
		int y = DrawerMenu.FIRST_SLOT_Y + (slot / 9) * 18;
		graphics.fill(x, y, x + 16, y + 16, LOCK_TINT);

		if (choosingDisplay) {
			// An outline rather than a wash, so the item underneath stays readable.
			graphics.fill(x, y, x + 16, y + 1, SELECTING_TINT);
			graphics.fill(x, y + 15, x + 16, y + 16, SELECTING_TINT);
			graphics.fill(x, y, x + 1, y + 16, SELECTING_TINT);
			graphics.fill(x + 15, y, x + 16, y + 16, SELECTING_TINT);
		}
	}

	/**
	 * Two tooltips of ours.
	 *
	 * <p>On a lock: what right-clicking it does. The gesture is not one anything else in the game
	 * teaches, and an upgrade with something to choose looks exactly like one without. The hint
	 * goes here rather than on the item so it appears where the gesture works.
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

		// The stack upgrade's right-click sorts the drawer; the lock's chooses the slot the face
		// shows. Each says so where the gesture works, since neither is one the game teaches.
		DrawerUpgrade upgrade = upgradeOf(stack);

		if (stacks(upgrade)) {
			hinted.add(Component.translatable("lodestore.upgrade.compress").withStyle(ChatFormatting.GRAY));
		}

		if (locks(upgrade)) {
			hinted.add(Component.translatable("lodestore.upgrade.choose_display").withStyle(ChatFormatting.GRAY));

			if (choosingDisplay) {
				hinted.add(Component.translatable("lodestore.upgrade.selecting_display")
						.withStyle(ChatFormatting.DARK_GRAY));
			}
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
	 * Right-click the lock, then click a storage slot to show its item on the front face. Right-
	 * click the stack upgrade to sort the drawer.
	 */
	@Override
	protected void slotClicked(Slot slot, int slotId, int button, ContainerInput input) {
		DrawerMenu menu = getMenu();

		if (slot != null && button == 1 && slotId >= menu.storageEnd() && slotId < menu.upgradeEnd()) {
			if (stacks(upgradeOf(slot.getItem()))) {
				// Rides the same validated button packet the lock's display choice does.
				if (minecraft != null && minecraft.gameMode != null) {
					minecraft.gameMode.handleInventoryButtonClick(menu.containerId, DrawerMenu.SORT_BUTTON);
				}

				return;
			}

			choosingDisplay = locks(upgradeOf(slot.getItem())) && !choosingDisplay;
			return;
		}

		if (choosingDisplay && slot != null && slotId >= 0 && slotId < menu.storageEnd()) {
			chooseDisplay(slot, slotId);
			return;
		}

		super.slotClicked(slot, slotId, button, input);
	}

	/**
	 * Picks the slot the front face shows. That is one slot rather than a selection, so picking
	 * ends the mode. An empty slot has nothing to show and is ignored rather than ending it —
	 * unless it is the slot already chosen, which clicking again lets go of.
	 */
	private void chooseDisplay(Slot slot, int slotId) {
		DrawerBlockEntity drawer = drawer();
		boolean current = drawer != null && drawer.displaySlot() == slotId;

		if (!slot.hasItem() && !current) {
			return;
		}

		if (minecraft != null && minecraft.gameMode != null) {
			minecraft.gameMode.handleInventoryButtonClick(getMenu().containerId, DrawerMenu.displayButton(slotId));
		}

		choosingDisplay = false;
	}
}
