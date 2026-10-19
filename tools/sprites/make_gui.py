"""Generates the side panel that sits beside the vanilla chest background in the drawer screen.

The screen blits `minecraft:textures/gui/container/generic_54.png` for its body, exactly as the
vanilla chest screen does, and this panel for the upgrade slots and the lock's filter grid. Only
the panel is ours, so only the panel is drawn here.

The panel is two stacked bands, blitted independently so a drawer takes only what it needs: a cap
plus however many upgrade rows its tier has, then the bottom cap. Their heights are mirrored in
DrawerScreen.
"""
import sys, os
sys.path.insert(0, os.path.dirname(__file__))
from pixels import write_png

SLOT = 18
COLUMNS = 3               # DrawerMenu.UPGRADES_PER_ROW and FILTER_COLUMNS
# Where the ITEM sits: DrawerMenu.PANEL_SLOT_X minus the 176-wide chest background.
SLOT_INTERIOR_X = 8
# Vanilla paints a slot's border one pixel up and left of the item it holds, so the 18x18 graphic
# starts one short of the interior. Drawing it flush instead leaves every item sitting over the
# slot's top-left edge with bare slot showing at the bottom right.
SLOT_GRAPHIC_X = SLOT_INTERIOR_X - 1
PANEL_WIDTH = SLOT_GRAPHIC_X + COLUMNS * SLOT + 5
MAX_UPGRADE_ROWS = 2      # ceil(DrawerTier.MAX_UPGRADE_SLOTS / COLUMNS)

# One short of DrawerMenu.FIRST_SLOT_Y, for the same reason SLOT_GRAPHIC_X is one short of the
# interior: the border goes above the item, not on it.
TOP_CAP = 17
BOTTOM_CAP = 8

TEX_W, TEX_H = 128, 128


def face_row():
    """Panel face: blends into the chest background on the left, shaded bevel on the right."""
    return ['C'] * (PANEL_WIDTH - 1) + ['e']


def slot_band(rows):
    """`rows` rows of `COLUMNS` vanilla slots: inset at top-left, lit at bottom-right."""
    out = []
    for _ in range(rows):
        for y in range(SLOT):
            row = face_row()

            for column in range(COLUMNS):
                for x in range(SLOT):
                    if y == 0 or x == 0:
                        ch = 't'
                    elif y == SLOT - 1 or x == SLOT - 1:
                        ch = 'F'
                    else:
                        ch = 'g'
                    row[SLOT_GRAPHIC_X + column * SLOT + x] = ch

            out.append(row)
    return out


def panel():
    rows = [['F'] * (PANEL_WIDTH - 1) + ['e']]          # lit top edge
    rows += [face_row() for _ in range(TOP_CAP - 1)]
    rows += slot_band(MAX_UPGRADE_ROWS)
    rows += [face_row() for _ in range(BOTTOM_CAP - 1)]
    rows.append(['e'] * PANEL_WIDTH)                     # shaded bottom edge
    return rows


if __name__ == "__main__":
    out = sys.argv[1]
    os.makedirs(os.path.join(out, "gui"), exist_ok=True)

    body = panel()
    bottom_v = TOP_CAP + MAX_UPGRADE_ROWS * SLOT
    assert len(body) == bottom_v + BOTTOM_CAP, len(body)
    assert PANEL_WIDTH <= TEX_W and len(body) <= TEX_H

    grid = [''.join(row) + '.' * (TEX_W - PANEL_WIDTH) for row in body]
    grid += ['.' * TEX_W] * (TEX_H - len(grid))

    write_png(os.path.join(out, "gui", "upgrade_panel.png"), grid)
    print(f"  gui/upgrade_panel.png  panel {PANEL_WIDTH}x{len(body)}  bottom_v={bottom_v}")
