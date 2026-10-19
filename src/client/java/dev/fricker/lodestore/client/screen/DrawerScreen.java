package dev.fricker.lodestore.client.screen;

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
 * <p>Right-clicking an installed upgrade turns on slot selection for it. For the void upgrade the
 * storage slots it covers are tinted, and clicking one adds or removes it. For the lock, which
 * covers the whole drawer, clicking a slot picks the one whose item the front face shows. What each
 * upgrade has chosen is read straight off the drawer's own block entity, which every client near
 * the block already has.
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

	private static final int BEHAVIOUR_NONE = -1;
	private static final int BEHAVIOUR_LOCK = 0;
	private static final int BEHAVIOUR_VOID = 1;

	/**
	 * The lock's gold on the slot the face shows, and the void upgrade's purple on the slots it
	 * covers, so a tint names the upgrade that put it there.
	 */
	private static final int LOCK_TINT = 0x55F2C852;
	private static final int VOID_TINT = 0x553B1E6E;
	/** While choosing, the upgrade being edited is picked out more strongly than the other. */
	private static final int SELECTING_TINT = 0x99FFFFFF;

	private final int rows;

	/** Which upgrade's slots are being chosen, or {@link #BEHAVIOUR_NONE}. Purely a client mode. */
	private int selecting = BEHAVIOUR_NONE;
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
	private DrawerBlockEntity drawer() {
		if (minecraft == null || minecraft.level == null) {
			return null;
		}

		return minecraft.level.getBlockEntity(getMenu().pos()) instanceof DrawerBlockEntity drawer
				? drawer : null;
	}

	/** Which kind of targeting an upgrade item owns, resolved against the synced registry. */
	private int behaviourOf(ItemStack stack) {
		if (!(stack.getItem() instanceof DrawerUpgradeItem item) || minecraft == null || minecraft.level == null) {
			return BEHAVIOUR_NONE;
		}

		DrawerUpgrade upgrade = minecraft.level.registryAccess()
				.lookup(LodestoreRegistries.DRAWER_UPGRADE)
				.flatMap(registry -> registry.getOptional(item.upgradeKey()))
				.orElse(DrawerUpgrade.FALLBACK);

		if (upgrade.locksContents()) {
			return BEHAVIOUR_LOCK;
		}

		return upgrade.voidsOverflow() ? BEHAVIOUR_VOID : BEHAVIOUR_NONE;
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
	 * Tints the slot the face shows and the storage slots the void upgrade covers.
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

		int displaySlot = drawer.displaySlot();
		Set<Integer> voidSlots = drawer.voidedSlots();

		for (int slot = 0; slot < getMenu().storageEnd(); slot++) {
			int x = 8 + (slot % 9) * 18;
			int y = DrawerMenu.FIRST_SLOT_Y + (slot / 9) * 18;

			if (slot == displaySlot) {
				graphics.fill(x, y, x + 16, y + 16, LOCK_TINT);
			}

			if (voidSlots.contains(slot)) {
				graphics.fill(x, y, x + 16, y + 16, VOID_TINT);
			}

			boolean chosen = selecting == BEHAVIOUR_LOCK ? slot == displaySlot
					: selecting == BEHAVIOUR_VOID && voidSlots.contains(slot);

			if (chosen) {
				// An outline rather than a wash, so the item underneath stays readable.
				graphics.fill(x, y, x + 16, y + 1, SELECTING_TINT);
				graphics.fill(x, y + 15, x + 16, y + 16, SELECTING_TINT);
				graphics.fill(x, y, x + 1, y + 16, SELECTING_TINT);
				graphics.fill(x + 15, y, x + 16, y + 16, SELECTING_TINT);
			}
		}
	}

	/**
	 * Explains what right-clicking an upgrade does, on the upgrade itself.
	 *
	 * <p>The gesture is not one anything else in the game teaches, and an upgrade with something
	 * to choose looks exactly like one without. The hint goes here rather than on the item so it
	 * appears where the gesture works. The void upgrade's covered count comes with it, because
	 * "how far does this one actually reach" is the question its tint is answering; the lock
	 * covers the whole drawer, so it has no reach worth stating.
	 */
	@Override
	protected List<Component> getTooltipFromContainerItem(ItemStack stack) {
		List<Component> tooltip = super.getTooltipFromContainerItem(stack);
		DrawerMenu menu = getMenu();

		if (hoveredSlot == null || hoveredSlot.index < menu.storageEnd() || hoveredSlot.index >= menu.upgradeEnd()) {
			return tooltip;
		}

		int behaviour = behaviourOf(stack);

		if (behaviour == BEHAVIOUR_NONE) {
			return tooltip;
		}

		List<Component> hinted = new ArrayList<>(tooltip);
		boolean lock = behaviour == BEHAVIOUR_LOCK;

		hinted.add(Component.translatable(lock
						? "lodestore.upgrade.choose_display"
						: "lodestore.upgrade.adjust_voided")
				.withStyle(ChatFormatting.GRAY));

		DrawerBlockEntity drawer = drawer();

		if (drawer != null && !lock) {
			hinted.add(Component.translatable("lodestore.upgrade.coverage",
							drawer.voidedSlots().size(), menu.storageEnd())
					.withStyle(ChatFormatting.DARK_GRAY));
		}

		if (selecting == behaviour) {
			hinted.add(Component.translatable(lock
							? "lodestore.upgrade.selecting_display"
							: "lodestore.upgrade.selecting")
					.withStyle(ChatFormatting.DARK_GRAY));
		}

		return hinted;
	}

	/**
	 * Right-click an upgrade to start choosing; then click a storage slot to show it on the face
	 * (the lock) or to add or remove it (the void upgrade).
	 *
	 * <p>For the void upgrade, shift-click acts on everything between the last slot clicked and
	 * this one, so a row or a block of slots takes two clicks rather than nine.
	 */
	@Override
	protected void slotClicked(Slot slot, int slotId, int button, ContainerInput input) {
		DrawerMenu menu = getMenu();

		if (slot != null && button == 1 && slotId >= menu.storageEnd() && slotId < menu.upgradeEnd()) {
			int behaviour = behaviourOf(slot.getItem());
			selecting = behaviour == BEHAVIOUR_NONE || behaviour == selecting ? BEHAVIOUR_NONE : behaviour;
			lastToggled = -1;
			return;
		}

		if (selecting != BEHAVIOUR_NONE && slot != null && slotId >= 0 && slotId < menu.storageEnd()) {
			if (selecting == BEHAVIOUR_LOCK) {
				chooseDisplay(slot, slotId);
			} else {
				toggle(slotId, input == ContainerInput.QUICK_MOVE);
			}

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
			minecraft.gameMode.handleInventoryButtonClick(getMenu().containerId,
					DrawerMenu.targetButton(true, slotId));
		}

		selecting = BEHAVIOUR_NONE;
	}

	private void toggle(int slotId, boolean range) {
		if (minecraft == null || minecraft.gameMode == null) {
			return;
		}

		int from = range && lastToggled >= 0 ? lastToggled : slotId;

		for (int slot = Math.min(from, slotId); slot <= Math.max(from, slotId); slot++) {
			minecraft.gameMode.handleInventoryButtonClick(getMenu().containerId,
					DrawerMenu.targetButton(false, slot));
		}

		lastToggled = slotId;
	}
}
