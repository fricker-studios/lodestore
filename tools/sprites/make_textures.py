"""Generates Lodestore's block and item sprites.

One-shot authoring aid: the PNGs it writes are the source of truth once committed.
"""
import sys, os
sys.path.insert(0, os.path.dirname(__file__))
from pixels import write_png

# ---------------------------------------------------------------- wood surfaces

OAK_PLANKS = [
    "HLLMLHLMMLLMHLML",
    "MMDMMMDMMDMMMMDM",
    "DMMDMDMMDMMDMDMM",
    "KKKKKKKKKKKKKKKK",
    "LMHLMLLHMLMMLHML",
    "MDMMMDMMMMDMMMMD",
    "MMDMDMMDMMMDMDMM",
    "KKKKKKKKKKKKKKKK",
    "LHMLLMHLMLHMMLML",
    "MMMDMMMDMDMMDMMM",
    "DMDMMDMMMDMDMMDM",
    "KKKKKKKKKKKKKKKK",
    "MLLHMLMLHMLLMMHL",
    "MDMMDMMMDMMDMMMD",
    "DMMDMMDMMDMMDMDM",
    "KKKKKKKKKKKKKKKK",
]

OAK_TOP = [
    "LMMKMLMKLMHKMLMK",
    "MLDKLMHKMDMKLMDK",
    "MHMKDMMKLMMKHLMK",
    "LMDKMHLKMLDKMMLK",
    "MLMKLMDKHMMKLDMK",
    "HMLKMLMKMDLKMLHK",
    "MDMKHMMKLMMKDMMK",
    "LMHKMDLKMLHKMLDK",
    "MLMKLMMKDMLKHMMK",
    "MHDKMLHKMMDKLMMK",
    "LMMKDMMKHLMKMDLK",
    "MDLKMHMKMLDKLMHK",
    "HMMKLMDKLMMKMLMK",
    "MLDKMMLKDMHKMMDK",
    "LMHKDLMKMLMKHLMK",
    "MMMKMLHKLDMKMMLK",
]

# All three tiers are the same oak. Sturdy is that oak stained a shade down, so the
# step up in tier reads tonally as well as by the hardware bolted to it.
DARKEN = str.maketrans({'H': 'M', 'L': 'D', 'M': 'D', 'D': 'K', 'K': 'S', 'S': 'X', 'X': 'X'})
DARK_PLANKS = [row.translate(DARKEN) for row in OAK_PLANKS]
DARK_TOP = [row.translate(DARKEN) for row in OAK_TOP]


def overlay(grid, top, left, patch):
    """Stamps `patch` onto a copy of `grid` at (top, left)."""
    out = [list(row) for row in grid]
    for dr, prow in enumerate(patch):
        for dc, ch in enumerate(prow):
            if ch != '~':  # '~' leaves the underlying pixel alone
                out[top + dr][left + dc] = ch
    return [''.join(row) for row in out]


# ------------------------------------------------------------------- the front
#
# The renderer floats the stored item at roughly texture rows 2-10 and draws the
# count in white just under it, around row 12. So the middle of the face is a
# plain recessed panel: anything busy there competes with the icon, and anything
# light there swallows the count. Tier is signalled entirely by the frame.

PANEL_GRAIN = [
    "KKKDKKKKDK",
    "KKKDKKKKDK",
    "DKKDKKKKDK",
    "KKKKKKKDKK",
    "KKDKKKKDKK",
    "KKDKKKKKKK",
    "KKKKKKKKDK",
    "DKKKKDKKKK",
    "KKKKKDKKKK",
    "KKDKKKKKKK",
]


def front(top_rows, bottom_rows, sides):
    """Builds a drawer face: 2px frame, then a panel inset with a lit bottom-right."""
    assert len(sides) == 12
    rows = list(top_rows)
    for i, (left, right) in enumerate(sides):
        if i == 0:
            panel = "S" * 12           # top of the inset, in shadow
        elif i == 11:
            panel = "S" + "M" * 11     # bottom of the inset, catching light
        else:
            panel = "S" + PANEL_GRAIN[i - 1] + "M"
        rows.append(left + panel + right)
    rows.extend(bottom_rows)
    return rows


BASIC_FRONT = front(
    ["HLMLHMLMMLHMLMLH", "MLDMMLMDLMMDLMDM"],
    ["MDLMMDMLDMMLMDLM", "XSXXSXXSXXSXXSXS"],
    [("ML", "LM"), ("LM", "ML"), ("MD", "LM"), ("LM", "MD"),
     ("ML", "LM"), ("LD", "ML"), ("ML", "LM"), ("LM", "ML"),
     ("MD", "LM"), ("LM", "MD"), ("ML", "LM"), ("LM", "ML")],
)

# Sturdy: the same frame a shade darker, with an L-bracket bolted over each corner —
# where the extra ring of planks in its recipe went.
STURDY_FRONT = front(
    [r.translate(DARKEN) for r in ["HLMLHMLMMLHMLMLH", "MLDMMLMDLMMDLMDM"]],
    [r.translate(DARKEN) for r in ["MDLMMDMLDMMLMDLM", "XSXXSXXSXXSXXSXS"]],
    [(l.translate(DARKEN), r.translate(DARKEN)) for l, r in
     [("ML", "LM"), ("LM", "ML"), ("MD", "LM"), ("LM", "MD"),
      ("ML", "LM"), ("LD", "ML"), ("ML", "LM"), ("LM", "ML"),
      ("MD", "LM"), ("LM", "MD"), ("ML", "LM"), ("LM", "ML")]],
)
_ARM = ["XXXXX", "XXXXX", "XX~~~", "XX~~~", "XX~~~"]
_MIRROR = [r[::-1] for r in _ARM]
for _r, _c, _patch in ((0, 0, _ARM), (0, 11, _MIRROR),
                       (11, 0, _ARM[::-1]), (11, 11, _MIRROR[::-1])):
    STURDY_FRONT = overlay(STURDY_FRONT, _r, _c, _patch)

# Reinforced: iron banding with rivets at the corners and halfway down each side.
REINFORCED_FRONT = front(
    ["GWWGNIIIIIINGWWG", "GWWGIIIIIIIIGWWG"],
    ["GWWGIIIIIIIIGWWG", "GWWGNIIIIIINGWWG"],
    [("GI", "IG"), ("IN", "NI"), ("IN", "NI"), ("GI", "IG"),
     ("WW", "WW"), ("WW", "WW"), ("GI", "IG"), ("IN", "NI"),
     ("IN", "NI"), ("NI", "IN"), ("IN", "NI"), ("GI", "IG")],
)

# ------------------------------------------------------------ sides and tops

BASIC_SIDE, BASIC_TOP = OAK_PLANKS, OAK_TOP

# Battens down the sides, in the same near-black as the brackets on the face.
STURDY_SIDE = overlay(DARK_PLANKS, 0, 2, ["XS"] * 16)
STURDY_SIDE = overlay(STURDY_SIDE, 0, 12, ["SX"] * 16)
STURDY_TOP = overlay(DARK_TOP, 0, 2, ["XS"] * 16)
STURDY_TOP = overlay(STURDY_TOP, 0, 12, ["SX"] * 16)

IRON_BAND = ["GWWGNIIIIIINGWWG", "GWWGIIIIIIIIGWWG"]
REINFORCED_SIDE = overlay(OAK_PLANKS, 0, 0, IRON_BAND)
REINFORCED_SIDE = overlay(REINFORCED_SIDE, 14, 0, IRON_BAND[::-1])

CORNER_PLATE = ["WWG", "WNG", "GGG"]
REINFORCED_TOP = OAK_TOP
for r, c in ((0, 0), (0, 13), (13, 0), (13, 13)):
    REINFORCED_TOP = overlay(REINFORCED_TOP, r, c, CORNER_PLATE)

# ------------------------------------------------------------- upgrade sprites


def plate(inner_rows):
    """An iron carrier plate, bevelled light-to-dark, holding a 12x10 motif."""
    assert len(inner_rows) == 10
    rows = ["." * 16, "." + "W" * 14 + ".", "." + "W" + "N" * 12 + "G" + "."]
    rows += ["." + "W" + inner + "G" + "." for inner in inner_rows]
    rows += ["." + "W" + "N" * 12 + "G" + ".", "." + "G" * 14 + ".", "." * 16]
    return rows


LOCK_UPGRADE = plate([
    "NNNNooooNNNN",
    "NNNoYYYYoNNN",
    "NNNoYooYoNNN",
    "NNNoYNNYoNNN",
    "NooooooooooN",
    "NoYYYYYYYYoN",
    "NoYYYBBYYYoN",
    "NoYYYBBYYYoN",
    "NoYYYYBYYYoN",
    "NooooooooooN",
])

# ------------------------------------------------------------------------ main

BLOCKS = {
    "basic_drawer_front": BASIC_FRONT,
    "basic_drawer_side": BASIC_SIDE,
    "basic_drawer_top": BASIC_TOP,
    "sturdy_drawer_front": STURDY_FRONT,
    "sturdy_drawer_side": STURDY_SIDE,
    "sturdy_drawer_top": STURDY_TOP,
    "reinforced_drawer_front": REINFORCED_FRONT,
    "reinforced_drawer_side": REINFORCED_SIDE,
    "reinforced_drawer_top": REINFORCED_TOP,
}
ITEMS = {"lock_upgrade": LOCK_UPGRADE}

if __name__ == "__main__":
    out = sys.argv[1]
    for sub, group in (("block", BLOCKS), ("item", ITEMS)):
        os.makedirs(os.path.join(out, sub), exist_ok=True)
        for name, grid in group.items():
            assert len(grid) == 16, f"{name}: {len(grid)} rows"
            write_png(os.path.join(out, sub, name + ".png"), grid)
            print(f"  {sub}/{name}.png")
