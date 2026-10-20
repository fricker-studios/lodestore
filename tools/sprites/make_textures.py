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
    """Builds a crate face: 2px frame, then a panel inset with a lit bottom-right."""
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

# ------------------------------------------------------------ network blocks
#
# The network is iron and copper where the crates are oak: a connection block is a dark iron
# plate with a copper conduit crossing it, so a run of them reads as one line from any side, and
# the two devices are crates with that same iron and copper bolted on.

CONNECTION_BLOCK = (
    ["GGGGGGGGGGGGGGGG",
     "GNBBBBBjkBBBBBNG"]
    + ["GBBBBBBjkBBBBBBG"] * 5
    + ["GjjjjjjjjjjjjjjG",
       "GkkkkkkkkkkkkkkG"]
    + ["GBBBBBBjkBBBBBBG"] * 5
    + ["GNBBBBBjkBBBBBNG",
       "GGGGGGGGGGGGGGGG"]
)

# The deposit box is a crate with a hopper's mouth let into its top and a copper arrow pointing
# down each side: things go in here and leave downward, into the network.
_MOUTH = [
    "GGGGGGGGGG",
    "GNNNNNNNNG",
    "GNSSSSSSNG",
    "GNSXXXXSNG",
    "GNSXXXXSNG",
    "GNSXXXXSNG",
    "GNSXXXXSNG",
    "GNSSSSSSNG",
    "GNNNNNNNNG",
    "GGGGGGGGGG",
]
DEPOSIT_BOX_TOP = overlay(OAK_TOP, 3, 3, _MOUTH)

_ARROW = [
    "~~~~~~jk~~~~~~",
    "~~~~~~jk~~~~~~",
    "~~~~~~jk~~~~~~",
    "~~jkkkkkkkkk~~",
    "~~~jkkkkkkk~~~",
    "~~~~jkkkkk~~~~",
    "~~~~~jkkk~~~~~",
    "~~~~~~jk~~~~~~",
]
DEPOSIT_BOX_SIDE = overlay(OAK_PLANKS, 0, 0, IRON_BAND)
DEPOSIT_BOX_SIDE = overlay(DEPOSIT_BOX_SIDE, 4, 1, _ARROW)

# The interface box is a crate whose front panel is a screen: dark glass with a grid of lit
# cells, which is what its own screen shows when opened.
_SCREEN = [
    "tttttttttttt",
    "tCCttCCttCCt",
    "tCCttCCttCCt",
    "tttttttttttt",
    "tCCttCCttCCt",
    "tCCttCCttCCt",
    "tttttttttttt",
    "tCCttCCttCCt",
    "tCCttCCttCCt",
    "tttttttttttt",
]
INTERFACE_BOX_FRONT = overlay(BASIC_FRONT, 3, 2, _SCREEN)
INTERFACE_BOX_SIDE = overlay(OAK_PLANKS, 0, 0, IRON_BAND)

# ------------------------------------------------------------- upgrade sprites


def plate(inner_rows, light="W", mid="N", dark="G"):
    """A carrier plate, bevelled light-to-dark, holding a 12x10 motif.

    The metal names the upgrade's level: iron, then copper, then diamond. Colour carries that at a
    glance in a hotbar, where a numeral would be four pixels tall and unreadable.
    """
    assert len(inner_rows) == 10
    rows = ["." * 16, "." + light * 14 + ".", "." + light + mid * 12 + dark + "."]
    rows += ["." + light + inner.replace("N", mid) + dark + "." for inner in inner_rows]
    rows += ["." + light + mid * 12 + dark + ".", "." + dark * 14 + ".", "." * 16]
    return rows


LOCK_MOTIF = [
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
]

# The void reads as a hole rather than a symbol: a round aperture sunk through the
# same iron plate the lock sits on, purple at the rim and near-black at the centre.
# A lock and a hole are not easily confused at inventory size, which matters when
# one of the two destroys things.
VOID_UPGRADE = plate([
    "NNNNNNNNNNNN",
    "NNNNVVVVNNNN",
    "NNVVvvvvVVNN",
    "NVvvvvvvvvVN",
    "NVvvvvvvvvPN",
    "NVvvvvvvvvPN",
    "NVvvvvvvvvPN",
    "NNVvvvvvvPNN",
    "NNNNVPPPNNNN",
    "NNNNNNNNNNNN",
])

# The stack upgrade reads as a pile: three slabs, the middle one nudged out of line so it is a
# stack of things rather than a grille. Green, because gold is the lock's and purple the void's,
# and the three have to be told apart in a hotbar where a motif is twelve pixels wide.
STACK_MOTIF = [
    "NNNNNNNNNNNN",
    "NNTTTTTTTTuN",
    "NNUUUUUUUUuN",
    "NNNNNNNNNNNN",
    "NTTTTTTTTuNN",
    "NUUUUUUUUuNN",
    "NNNNNNNNNNNN",
    "NNTTTTTTTTuN",
    "NNUUUUUUUUuN",
    "NNNNNNNNNNNN",
]
# Levels two and three are the same pile on a richer plate, exactly as the lock's levels are.
STACK_UPGRADE = plate(STACK_MOTIF)
STACK_UPGRADE_2 = plate(STACK_MOTIF, "j", "k", "l")
STACK_UPGRADE_3 = plate(STACK_MOTIF, "n", "p", "q")

# The three upgrades that work on the contents each wear a picture of the thing they stand in
# for. Compress is a copper arrow pressing down onto a dark slab: the deposit box's arrow, since
# that is the one "things go this way" mark the mod already has. Disenchant is a lapis book with a
# clasp — the enchanting table's blue, on the enchanted book's shape — because what it takes off is
# what that table puts on. Repair is an anvil in silhouette, the block everyone already knows
# repairs things, even though the arithmetic is the crafting grid's.
COMPRESS_UPGRADE = plate([
    "NNNNNjkNNNNN",
    "NNNNNjkNNNNN",
    "NNjkkkkkkkNN",
    "NNNjkkkkkNNN",
    "NNNNjkkkNNNN",
    "NNNNNjkNNNNN",
    "NNNNNNNNNNNN",
    "NBBBBBBBBBBN",
    "NBGGGGGGGGBN",
    "NBBBBBBBBBBN",
])

DISENCHANT_UPGRADE = plate([
    "NNNNNNNNNNNN",
    "NNbbbbbbbbNN",
    "NNbbbbbbbbNN",
    "NNbbbbbbbbNN",
    "NNaaaaaaaaNN",
    "NNbbbbbbbbNN",
    "NNbbbbbbbbNN",
    "NNbbbbbbbbNN",
    "NNcWWWWWWcNN",
    "NNNNNNNNNNNN",
])

REPAIR_UPGRADE = plate([
    "NNNNNNNNNNNN",
    "NNWWWWWWWWWN",
    "NNBBBBBBBBBN",
    "NNNBBBBBBNNN",
    "NNNNNBBBNNNN",
    "NNNNNBBBNNNN",
    "NNNNBBBBBNNN",
    "NNNBBBBBBBNN",
    "NNBBBBBBBBBN",
    "NNNNNNNNNNNN",
])

# ------------------------------------------------------------------------ main

BLOCKS = {
    "basic_crate_front": BASIC_FRONT,
    "basic_crate_side": BASIC_SIDE,
    "basic_crate_top": BASIC_TOP,
    "sturdy_crate_front": STURDY_FRONT,
    "sturdy_crate_side": STURDY_SIDE,
    "sturdy_crate_top": STURDY_TOP,
    "reinforced_crate_front": REINFORCED_FRONT,
    "reinforced_crate_side": REINFORCED_SIDE,
    "reinforced_crate_top": REINFORCED_TOP,
    "connection_block": CONNECTION_BLOCK,
    "deposit_box_top": DEPOSIT_BOX_TOP,
    "deposit_box_side": DEPOSIT_BOX_SIDE,
    "interface_box_front": INTERFACE_BOX_FRONT,
    "interface_box_side": INTERFACE_BOX_SIDE,
}
LOCK_UPGRADE = plate(LOCK_MOTIF)

ITEMS = {
    "lock_upgrade": LOCK_UPGRADE,
    "void_upgrade": VOID_UPGRADE,
    "stack_upgrade": STACK_UPGRADE,
    "stack_upgrade_2": STACK_UPGRADE_2,
    "stack_upgrade_3": STACK_UPGRADE_3,
    "compress_upgrade": COMPRESS_UPGRADE,
    "disenchant_upgrade": DISENCHANT_UPGRADE,
    "repair_upgrade": REPAIR_UPGRADE,
}

if __name__ == "__main__":
    out = sys.argv[1]
    for sub, group in (("block", BLOCKS), ("item", ITEMS)):
        os.makedirs(os.path.join(out, sub), exist_ok=True)
        for name, grid in group.items():
            assert len(grid) == 16, f"{name}: {len(grid)} rows"
            write_png(os.path.join(out, sub, name + ".png"), grid)
            print(f"  {sub}/{name}.png")
