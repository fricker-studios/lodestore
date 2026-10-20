package dev.fricker.lodestore.client.screen;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import dev.fricker.lodestore.menu.InterfaceMenu;
import dev.fricker.lodestore.network.CountFormat;
import dev.fricker.lodestore.network.NetworkView;

/**
 * The interface box's screen: the double chest's body, with the network's view drawn into the grid
 * and a scrollbar beside it.
 *
 * <p>The grid is not slots. The menu has none there — a slot shows one legal stack and a kind of
 * item on a network can number thousands — so this draws the view itself, one cell per kind, the
 * item with its count abbreviated to fit. The exact count is in the tooltip. The player's inventory
 * below is real slots, as in any container.
 *
 * <p>The body is vanilla's own double-chest texture, blitted the way the crate screen blits it, so
 * the two look like one family. Only the scrollbar's strip is drawn here, in vanilla's greys.
 */
public class InterfaceScreen extends AbstractContainerScreen<InterfaceMenu> {
	private static final Identifier CHEST_BACKGROUND =
			Identifier.withDefaultNamespace("textures/gui/container/generic_54.png");
	private static final Identifier SCROLLER = Identifier.withDefaultNamespace("container/creative_inventory/scroller");
	private static final Identifier SCROLLER_DISABLED =
			Identifier.withDefaultNamespace("container/creative_inventory/scroller_disabled");

	private static final int BODY_WIDTH = 176;
	private static final int CHROME_HEIGHT = 114;
	private static final int GRID_X = 8;
	private static final int GRID_Y = 18;
	private static final int CELL = 18;
	private static final int COLUMNS = InterfaceMenu.COLUMNS;
	private static final int ROWS = InterfaceMenu.ROWS;

	/** The strip beside the body that holds the scrollbar. */
	private static final int PANEL_WIDTH = 22;
	private static final int TRACK_X = BODY_WIDTH + 4;
	private static final int TRACK_Y = GRID_Y - 1;
	private static final int TRACK_WIDTH = 14;
	private static final int TRACK_HEIGHT = ROWS * CELL;
	private static final int THUMB_WIDTH = 12;
	private static final int THUMB_HEIGHT = 15;

	/** Vanilla's panel greys, the same four the upgrade panel is drawn in. */
	private static final int FACE = 0xFFC6C6C6;
	private static final int LIT = 0xFFFFFFFF;
	private static final int SHADED = 0xFF555555;
	private static final int INSET = 0xFF373737;
	private static final int SLOT = 0xFF8B8B8B;
	private static final int HOVER = 0x80FFFFFF;
	private static final int LABEL = 0xFF404040;
	private static final float MIN_COUNT_SCALE = 0.6F;

	private int scrollRow;
	private boolean draggingThumb;

	public InterfaceScreen(InterfaceMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title, BODY_WIDTH + PANEL_WIDTH, CHROME_HEIGHT + ROWS * CELL);
		this.inventoryLabelY = this.imageHeight - 94;
	}

	// --- the view --------------------------------------------------------------------

	private List<ItemStack> stacks() {
		return getMenu().stacks();
	}

	/** How many rows the view would take, over the six that show. */
	private int maxScrollRow() {
		int rows = (stacks().size() + COLUMNS - 1) / COLUMNS;
		return Math.max(0, rows - ROWS);
	}

	/** The view entry under the mouse, or -1. Screen coordinates. */
	private int hoveredIndex(double mouseX, double mouseY) {
		double x = mouseX - leftPos - GRID_X;
		double y = mouseY - topPos - GRID_Y;

		if (x < 0 || y < 0 || x >= COLUMNS * CELL || y >= ROWS * CELL) {
			return -1;
		}

		int index = (scrollRow + (int) y / CELL) * COLUMNS + (int) x / CELL;
		return index < stacks().size() ? index : -1;
	}

	// --- drawing ---------------------------------------------------------------------

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(graphics, mouseX, mouseY, partialTick);

		int left = (this.width - this.imageWidth) / 2;
		int top = (this.height - this.imageHeight) / 2;
		int gridHeight = ROWS * CELL + 17;

		graphics.blit(RenderPipelines.GUI_TEXTURED, CHEST_BACKGROUND, left, top,
				0.0f, 0.0f, BODY_WIDTH, gridHeight, 256, 256);
		graphics.blit(RenderPipelines.GUI_TEXTURED, CHEST_BACKGROUND, left, top + gridHeight,
				0.0f, 126.0f, BODY_WIDTH, 96, 256, 256);

		// The strip: face, lit along the top, shaded along the bottom and the far edge.
		int panelLeft = left + BODY_WIDTH;
		int panelRight = panelLeft + PANEL_WIDTH;
		int panelBottom = top + gridHeight + 7;
		graphics.fill(panelLeft, top, panelRight, panelBottom, FACE);
		graphics.fill(panelLeft, top, panelRight - 1, top + 1, LIT);
		graphics.fill(panelLeft, panelBottom - 1, panelRight, panelBottom, SHADED);
		graphics.fill(panelRight - 1, top, panelRight, panelBottom, SHADED);

		// The track, inset like a slot: dark at the top left, lit at the bottom right.
		int trackLeft = left + TRACK_X;
		int trackTop = top + TRACK_Y;
		graphics.fill(trackLeft, trackTop, trackLeft + TRACK_WIDTH, trackTop + TRACK_HEIGHT, INSET);
		graphics.fill(trackLeft + 1, trackTop + 1, trackLeft + TRACK_WIDTH, trackTop + TRACK_HEIGHT, LIT);
		graphics.fill(trackLeft + 1, trackTop + 1, trackLeft + TRACK_WIDTH - 1, trackTop + TRACK_HEIGHT - 1, SLOT);

		int max = maxScrollRow();
		int thumbTop = trackTop + 1 + (max == 0 ? 0 : (TRACK_HEIGHT - 2 - THUMB_HEIGHT) * scrollRow / max);
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, max == 0 ? SCROLLER_DISABLED : SCROLLER,
				trackLeft + 1, thumbTop, THUMB_WIDTH, THUMB_HEIGHT);
	}

	/**
	 * The view, drawn in menu-local coordinates like the labels are.
	 *
	 * <p>Each cell is the item and its count, the count scaled down when it would not fit — the
	 * crate screen's treatment of a slot holding more than a stack, which this is the network-wide
	 * form of. An empty view says why it is empty, since no crates and empty crates look the same
	 * and only one of them is the player's fault.
	 */
	@Override
	protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		super.extractLabels(graphics, mouseX, mouseY);

		NetworkView view = getMenu().view();
		List<ItemStack> stacks = stacks();
		scrollRow = Math.min(scrollRow, maxScrollRow());

		Component crates = Component.translatable("lodestore.interface.crates", view.crates());
		graphics.text(font, crates, BODY_WIDTH - 8 - font.width(crates.getString()), titleLabelY, LABEL, false);

		if (stacks.isEmpty()) {
			Component why = Component.translatable(view.crates() == 0
					? "lodestore.interface.no_crates" : "lodestore.interface.nothing_stored");
			graphics.centeredText(font, why, GRID_X + COLUMNS * CELL / 2,
					GRID_Y + (ROWS * CELL - font.lineHeight) / 2, LABEL);
			return;
		}

		int hovered = getMenu().getCarried().isEmpty() ? hoveredIndex(mouseX, mouseY) : -1;

		for (int row = 0; row < ROWS; row++) {
			for (int column = 0; column < COLUMNS; column++) {
				int index = (scrollRow + row) * COLUMNS + column;

				if (index >= stacks.size()) {
					return;
				}

				int x = GRID_X + column * CELL;
				int y = GRID_Y + row * CELL;

				if (index == hovered) {
					graphics.fill(x, y, x + 16, y + 16, HOVER);
				}

				drawEntry(graphics, stacks.get(index), view.entries().get(index).count(), x, y);
			}
		}
	}

	private void drawEntry(GuiGraphicsExtractor graphics, ItemStack stack, long count, int x, int y) {
		String text = CountFormat.abbreviate(count);
		int width = font.width(text);
		graphics.item(stack, x, y, x + y * imageWidth);

		if (width <= 16) {
			graphics.itemDecorations(font, stack, x, y, text);
			return;
		}

		graphics.itemDecorations(font, stack, x, y, "");
		float scale = Math.max(MIN_COUNT_SCALE, 16.0F / width);
		graphics.pose().pushMatrix();
		graphics.pose().translate(x + 17 - width * scale, y + 9 + (font.lineHeight - 1) * (1.0F - scale));
		graphics.pose().scale(scale, scale);
		graphics.text(font, text, 0, 0, -1, true);
		graphics.pose().popMatrix();
	}

	/** The item's own tooltip, plus the exact count the cell only abbreviates. */
	@Override
	protected void extractTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		super.extractTooltip(graphics, mouseX, mouseY);

		if (!getMenu().getCarried().isEmpty()) {
			return;
		}

		int index = hoveredIndex(mouseX, mouseY);

		if (index < 0) {
			return;
		}

		ItemStack stack = stacks().get(index);
		List<Component> lines = new ArrayList<>(getTooltipFromContainerItem(stack));
		lines.add(Component.translatable("lodestore.interface.stored",
				CountFormat.exact(getMenu().view().entries().get(index).count())).withStyle(ChatFormatting.GRAY));
		graphics.setTooltipForNextFrame(font, lines, stack.getTooltipImage(), mouseX, mouseY);
	}

	// --- scrolling -------------------------------------------------------------------

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
			return true;
		}

		if (maxScrollRow() == 0) {
			return false;
		}

		scrollRow = Mth.clamp(scrollRow - (int) Math.signum(scrollY), 0, maxScrollRow());
		return true;
	}

	private boolean overTrack(double mouseX, double mouseY) {
		return isHovering(TRACK_X, TRACK_Y, TRACK_WIDTH, TRACK_HEIGHT, mouseX, mouseY);
	}

	/** Puts the thumb's middle under the mouse, as far as the rows allow. */
	private void dragThumbTo(double mouseY) {
		double travel = TRACK_HEIGHT - 2 - THUMB_HEIGHT;
		double fraction = (mouseY - (topPos + TRACK_Y + 1) - THUMB_HEIGHT / 2.0) / travel;
		scrollRow = (int) Math.round(Mth.clamp(fraction, 0.0, 1.0) * maxScrollRow());
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 0 && maxScrollRow() > 0 && overTrack(event.x(), event.y())) {
			draggingThumb = true;
			dragThumbTo(event.y());
			return true;
		}

		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (draggingThumb) {
			dragThumbTo(event.y());
			return true;
		}

		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		draggingThumb = false;
		return super.mouseReleased(event);
	}
}
