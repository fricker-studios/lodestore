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



def overlay(grid, top, left, patch):
    """Stamps `patch` onto a copy of `grid` at (top, left)."""
    out = [list(row) for row in grid]
    for dr, prow in enumerate(patch):
        for dc, ch in enumerate(prow):
            if ch != '~':  # '~' leaves the underlying pixel alone
                out[top + dr][left + dc] = ch
    return [''.join(row) for row in out]


# ------------------------------------------------------------------- the crates
#
# Three tiers, told apart by hardware the way the recipes describe them. Basic is light oak:
# edge boards nailed round horizontal planks, and on the front a terracotta label in a pale
# frame. Sturdy is spruce, a shade darker, with a riveted iron L-bracket over every corner, a
# pale cross-brace on each side, and the label framed in iron. Reinforced is near-black wood
# caged in iron straps, riveted where they cross, the label held by the straps themselves.
#
# The label sits where the renderer floats the stored item and draws the count beneath it, so
# it is flat and a single warm tone: anything busy there competes with the icon, anything pale
# there swallows the white count. Terracotta is dark enough for the count and warm enough that
# an iron ingot or a diamond still pops against it.


def mirror_h(patch):
    return [row[::-1] for row in patch]


def mirror_v(patch):
    return patch[::-1]


def corners(grid, patch):
    """Stamps `patch` over the top-left corner and its mirrors over the other three."""
    n = len(patch)
    grid = overlay(grid, 0, 0, patch)
    grid = overlay(grid, 0, 16 - n, mirror_h(patch))
    grid = overlay(grid, 16 - n, 0, mirror_v(patch))
    grid = overlay(grid, 16 - n, 16 - n, mirror_v(mirror_h(patch)))
    return grid


# Horizontal planks, three rows a board with a dark seam between, in light oak.
OAK_BOARDS = [
    "LHLLMLHLLMLLHLML", "MLMMLMMLMMLMMLMM", "DDDDDDDDDDDDDDDD",
    "LMLHLMLLHLMLLMHL", "MMLMMMLMMMLMMMML", "DDDDDDDDDDDDDDDD",
    "HLMLLHLMLLMHLLML", "LMMLMMMLMMMLMMLM", "DDDDDDDDDDDDDDDD",
    "LLHLMLLMHLLMLHLM", "MLMMMLMMLMMMLMMM", "DDDDDDDDDDDDDDDD",
    "LHLMLLHLLMLHLLML", "MLMMLMMLMMMLMMLM", "DDDDDDDDDDDDDDDD",
    "LMLLHLMLLHLMLLHL",
]

# The same boards in spruce for the sturdy tier, and nearly black for the reinforced one,
# where they are only what shows between the iron.
SPRUCE_BOARDS = [row.translate(str.maketrans({'H': 'm', 'L': 'm', 'M': 'd', 'D': 's'})) for row in OAK_BOARDS]
DEEP_BOARDS = [row.translate(str.maketrans({'H': 'm', 'L': 'd', 'M': 'd', 'D': 's'})) for row in OAK_BOARDS]


def framed(boards, light, mid, seam, nail):
    """Edge boards two pixels wide round the planks, a nail in each corner, a seam inside."""
    g = [list(r) for r in boards]
    for i in range(16):
        for j in (0, 15):
            g[j][i] = light if (i % 4) != 3 else mid
            g[i][j] = light if (i % 4) != 1 else mid
        for j in (1, 14):
            g[j][i] = mid
            g[i][j] = mid
    for r in (1, 14):
        for c in (1, 14):
            g[r][c] = nail
    for i in range(2, 14):
        g[2][i] = seam
        g[13][i] = seam
        g[i][2] = seam
        g[i][13] = seam
    return [''.join(r) for r in g]


def label(grid, frame, size, inner='A', shadow='z', light='Z'):
    """The recessed terracotta panel, centred: a one-pixel frame, a shadow along the top and
    left inside it, a lit edge along the bottom and right."""
    off = (16 - size) // 2
    g = [list(r) for r in grid]
    for i in range(size):
        g[off][off + i] = frame
        g[off + size - 1][off + i] = frame
        g[off + i][off] = frame
        g[off + i][off + size - 1] = frame
    for r in range(off + 1, off + size - 1):
        for c in range(off + 1, off + size - 1):
            g[r][c] = inner
    for i in range(off + 1, off + size - 1):
        g[off + 1][i] = shadow
        g[i][off + 1] = shadow
        g[off + size - 2][i] = light if i > off + 1 else shadow
        g[i][off + size - 2] = light if i > off + 1 else shadow
    return [''.join(r) for r in g]


# Basic: oak, nailed, labelled; the lid's boards run the other way.
BASIC_SIDE = framed(OAK_BOARDS, 'L', 'M', 'K', 'X')
BASIC_TOP = framed([''.join(OAK_BOARDS[x][y] for x in range(16)) for y in range(16)], 'L', 'M', 'K', 'X')
BASIC_FRONT = label(BASIC_SIDE, 'H', 10)

# Sturdy: a gunmetal L-bracket over every corner, five pixels an arm and two thick, with a
# rivet — darker than the spruce it is bolted to, so it reads as old steel against the
# reinforced tier's iron rather than a lighter shade of the same.
BRACKET = [
    "QQQQQ",
    "QGJJx",
    "QJ~~~",
    "QJ~~~",
    "xx~~~",
]
_STURDY_BASE = framed(SPRUCE_BOARDS, 'h', 'm', 's', 's')
STURDY_FRONT = label(corners(_STURDY_BASE, BRACKET), 'J', 10)
for _r, _c in ((3, 3), (3, 12), (12, 3), (12, 12)):
    STURDY_FRONT = overlay(STURDY_FRONT, _r, _c, ["Q"])
STURDY_TOP = corners(_STURDY_BASE, BRACKET)

# Each side carries a cross-brace: two pale boards corner to corner, each with a shadowed edge.
_BRACE = [list('~' * 16) for _ in range(16)]
for _i in range(2, 14):
    for _d, _ch in ((0, 'L'), (1, 'M'), (2, 'd')):
        if 2 <= _i + _d <= 13:
            _BRACE[_i + _d][_i] = _ch
            _BRACE[_i + _d][15 - _i] = _ch
_BRACE = [''.join(r) for r in _BRACE]
STURDY_SIDE = corners(overlay(_STURDY_BASE, 0, 0, _BRACE), BRACKET)


def straps(boards, middle=True):
    """Iron straps round the edge and, unless the front's label is there, across the middle
    both ways, a lit edge on each; where they cross, a rivet with the rust it has shed."""
    g = [list(r) for r in boards]
    bands = [(0, 1), (14, 15)] + ([(7, 8)] if middle else [])
    for a, b in bands:
        for i in range(16):
            g[a][i] = 'N'
            g[b][i] = 'G'
            g[i][a] = 'N'
            g[i][b] = 'G'
    for a, b in bands:
        for c, d in bands:
            g[a][c] = 'N'
            g[a][d] = 'N'
            g[b][c] = 'I'
            g[b][d] = 'l'
    return [''.join(r) for r in g]


# Reinforced: the cage, in an iron a shade under bright. The front's label sits where the
# middle straps would cross, the same size as every tier's, framed in the same iron with a
# rivet at the middle of each side and rust in the corners.
REINFORCED_SIDE = straps(DEEP_BOARDS)
REINFORCED_TOP = straps(DEEP_BOARDS)
REINFORCED_FRONT = label(straps(DEEP_BOARDS, middle=False), 'G', 10)
for _r, _c in ((3, 7), (3, 8), (12, 7), (12, 8), (7, 3), (8, 3), (7, 12), (8, 12)):
    REINFORCED_FRONT = overlay(REINFORCED_FRONT, _r, _c, ["I"])
for _r, _c in ((3, 3), (3, 12), (12, 3), (12, 12)):
    REINFORCED_FRONT = overlay(REINFORCED_FRONT, _r, _c, ["l"])

# The band the network blocks wear, and the plain planks they are built on.
IRON_BAND = ["GWWGNIIIIIINGWWG", "GWWGIIIIIIIIGWWG"]

# ------------------------------------------------------------ network blocks
#
# The network wears the crates' own hardware in other metals. A connection block is spruce with a
# copper bracket over every corner and a copper coupling in the middle of every face, so a run of
# them reads as one line of pipe from any side. The deposit box is a crate-like body in iron straps
# with a gunmetal spout on its front, pointing into the block it feeds. The interface box is an
# iron-plated housing with a screen in a brass bezel on its front, lit teal: what its own screen
# shows when opened.


def bands(boards, lit, body, rivet, middle=False):
    """Straps of a metal round the edge of a face, and across the middle both ways if asked."""
    g = [list(r) for r in boards]
    which = [(0, 1), (14, 15)] + ([(7, 8)] if middle else [])
    for a, b in which:
        for i in range(16):
            g[a][i] = lit
            g[b][i] = body
            g[i][a] = lit
            g[i][b] = body
    for a, b in which:
        for c, d in which:
            g[a][c] = lit
            g[a][d] = lit
            g[b][c] = rivet
            g[b][d] = body
    return [''.join(r) for r in g]


# The connection block: spruce with a copper bracket over every corner, the same on every face,
# since any face may be the one the next block joins. Nothing in the middle — it carries the
# network and does nothing else, and its face says as much.
COPPER_BRACKET = [
    "jjjj",
    "jYkl",
    "jk~~",
    "ll~~",
]
CONNECTION_BLOCK_SIDE = corners(framed(SPRUCE_BOARDS, 'm', 'd', 's', 's'), COPPER_BRACKET)

# The front and back carry a redstone indicator the size of an observer's: a two-pixel lamp in a
# gunmetal bezel, dark red until an item passes through the block, bright while items keep passing.
CONNECTION_BLOCK_END = overlay(CONNECTION_BLOCK_SIDE, 6, 6, [
    "xxxx",
    "xffx",
    "xffx",
    "xxxx",
])
CONNECTION_BLOCK_END_LIT = overlay(CONNECTION_BLOCK_SIDE, 6, 6, [
    "xxxx",
    "xEEx",
    "xEEx",
    "xxxx",
])

# The deposit box is a hopper for the network and is built as one: a crate-like body half a block
# deep with an iron spout on its front, pointing into the block it feeds. The body is oak in iron
# straps — a crate's boards in the network's metal — with its mouth, a dark square opening, on
# the back. The spout is gunmetal: a funnel stepping down to a neck, a machine part on a crate.
# The model is drawn pointing north, so the textures here are its front, back, flanks and spout
# rather than top, side and bottom; the block state turns it the other five ways.
_STRAPPED = bands(OAK_BOARDS, 'I', 'N', 'W')
DEPOSIT_BOX_BACK = overlay(_STRAPPED, 4, 4, [
    "QQQQQQQQ",
    "QxxxxxxJ",
    "QxxxxxxJ",
    "QxxxxxxJ",
    "QxxxxxxJ",
    "QxxxxxxJ",
    "QxxxxxxJ",
    "QJJJJJJJ",
])
DEPOSIT_BOX_FRONT = overlay(_STRAPPED, 2, 2, [
    "IIIIIIIIIIII",
    "INNNNNNNNNNG",
    "INNNNNNNNNNG",
    "INNNNNNNNNNG",
    "INNNNNNNNNNG",
    "INNNNNNNNNNG",
    "INNNNNNNNNNG",
    "INNNNNNNNNNG",
    "INNNNNNNNNNG",
    "INNNNNNNNNNG",
    "INNNNNNNNNNG",
    "IGGGGGGGGGGG",
])
# The flanks are a strip, sixteen by eight, in the top half: a strap, one pixel and riveted, along
# each edge of the body with the boards between. The sides take it turned on end, the top and bottom as it is.
DEPOSIT_BOX_FLANK = (
    ["IWIIIIIIIIIIIIWI"]
    + OAK_BOARDS[3:9]
    + ["GWGGGGGGGGGGGGWG"]
    + ["N" * 16] * 8
)
# The spout's texture holds three pieces, each read by its own patch: the funnel's face, a ring
# from (3, 3) to (13, 13) with the neck's dark end in the middle; the funnel's sides, four wide
# down the left edge; and the neck's sides, four square at the right.
DEPOSIT_BOX_SPOUT = [
    "JJJJJJJJJJJJJJJJ",
    "JJJJJJJJJJJJJJJJ",
    "JJJJJJJJJJJJJJJJ",
    "xJJQQQQQQQQQQJJJ",
    "xJJQJJJJJJJJxJJJ",
    "xJJQJJJJJJJJxJJJ",
    "xJJQJJQQQQJJxJJQ",
    "xJJQJJQxxJJJxJJQ",
    "xJJQJJQxxJJJxJJQ",
    "xJJQJJQJJJJJxJJQ",
    "xJJQJJJJJJJJxJJJ",
    "xJJQJJJJJJJJxJJJ",
    "xJJQxxxxxxxxxJJJ",
    "JJJJJJJJJJJJJJJJ",
    "JJJJJJJJJJJJJJJJ",
    "JJJJJJJJJJJJJJJJ",
]
# The interface box: an iron housing, plated and riveted, with the screen let into the front
# behind a brass bezel. Nine lit cells on dark glass, the grid its own screen draws.
_IRON_HOUSING = framed(["G" * 16] * 16, 'N', 'G', 'B', 'I')
INTERFACE_BOX_FRONT = overlay(_IRON_HOUSING, 2, 2, [
    "YYYYYYYYYYYY",
    "YttttttttttO",
    "YtnptnptnptO",
    "YtpptpptpptO",
    "YttttttttttO",
    "YtnptnptnptO",
    "YtpptpptpptO",
    "YttttttttttO",
    "YtnptnptnptO",
    "YtpptpptpptO",
    "YttttttttttO",
    "YOOOOOOOOOOO",
])
# A brass dial on each side, and a brass plate across the top.
_DIAL = [
    "~YYYY~",
    "YOOOOO",
    "YOYYOO",
    "YOYYOO",
    "YOOOOO",
    "~OOOO~",
]
INTERFACE_BOX_SIDE = overlay(_IRON_HOUSING, 5, 5, _DIAL)
INTERFACE_BOX_TOP = overlay(_IRON_HOUSING, 6, 4, ["YYYYYYYY", "YOOOOOOO", "YOOOOOOO", "OOOOOOOO"])

# A locked crate says so on its face: the four rivets just inside the corners turn gold, the
# lock upgrade's own colour, which the screen also tints a locked slot. Inside the border rather
# than on it so the mark is in the woodwork, not a frame drawn round the block, and the same four
# pixels on every tier so the eye learns one place to look.


def locked(front, colour='Y'):
    """The front with its corner rivets in the lock's colour."""
    g = [list(r) for r in front]
    for r in (1, 14):
        for c in (1, 14):
            g[r][c] = colour
    return [''.join(r) for r in g]


BASIC_FRONT_LOCKED = locked(BASIC_FRONT)
STURDY_FRONT_LOCKED = locked(STURDY_FRONT)
REINFORCED_FRONT_LOCKED = locked(REINFORCED_FRONT)

# ------------------------------------------------------------- upgrade sprites


def hexagon(inner_rows, light="W", mid="N", dark="G"):
    """A hexagonal carrier, point up and point down, holding a 12x10 motif.

    It is not a square: the corners are air, so in a slot or a hotbar an upgrade is a shape before
    it is a picture, and not one more block among blocks. The light falls from the top left, so
    the two upper faces are lit and the two lower faces shaded, as every bevel in the mod is.

    The metal names the upgrade's level: iron, then copper, then diamond. Colour carries that at a
    glance in a hotbar, where a numeral would be four pixels tall and unreadable.

    A motif's 'N' pixels are the carrier showing through, not paint: a motif that reaches the
    carrier's edge leaves the edge alone.
    """
    assert len(inner_rows) == 10 and all(len(r) == 12 for r in inner_rows)
    rows = [
        ".......WW.......",
        ".....WWNNWW.....",
        "...WWNNNNNNWW...",
        ".WWNNNNNNNNNNWW.",
        "WNNNNNNNNNNNNNNG",
        "WNNNNNNNNNNNNNNG",
        "WNNNNNNNNNNNNNNG",
        "WNNNNNNNNNNNNNNG",
        "WNNNNNNNNNNNNNNG",
        "WNNNNNNNNNNNNNNG",
        "WNNNNNNNNNNNNNNG",
        "WNNNNNNNNNNNNNNG",
        ".GGNNNNNNNNNNGG.",
        "...GGNNNNNNGG...",
        ".....GGNNGG.....",
        ".......GG.......",
    ]
    rows = [r.replace("W", light).replace("N", mid).replace("G", dark) for r in rows]
    grid = [list(r) for r in rows]

    for dr, inner in enumerate(inner_rows):
        for dc, ch in enumerate(inner):
            if ch != "N":
                grid[3 + dr][2 + dc] = ch

    return ["".join(r) for r in grid]


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
# same iron the lock sits on, purple at the rim and near-black at the centre.
# A lock and a hole are not easily confused at inventory size, which matters when
# one of the two destroys things.
VOID_UPGRADE = hexagon([
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
# Levels two and three are the same pile on a richer metal, exactly as the lock's levels are.
STACK_UPGRADE = hexagon(STACK_MOTIF)
STACK_UPGRADE_2 = hexagon(STACK_MOTIF, "j", "k", "l")
STACK_UPGRADE_3 = hexagon(STACK_MOTIF, "n", "p", "q")

# The three upgrades that work on the contents each wear a picture of the thing they stand in
# for. Compress is a copper arrow pressing down onto a dark slab: the deposit box's arrow, since
# that is the one "things go this way" mark the mod already has. Disenchant is a bottle o'
# enchanting, because what it takes off an item it keeps as levels, and that bottle is what
# levels look like in the game. Repair is a pickaxe over a full durability bar: the bar is the
# one place a player already watches a tool's health, and this is what it looks like mended.
COMPRESS_UPGRADE = hexagon([
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

DISENCHANT_UPGRADE = hexagon([
    "NNNNN22NNNNN",
    "NNNNNiiNNNNN",
    "NNNNN11NNNNN",
    "NNNNiwwiNNNN",
    "NNNiwwwwiNNN",
    "NNiwwwwwwiNN",
    "NNiwwyyywiNN",
    "NNiwyyyyyiNN",
    "NN1yyyyyy1NN",
    "NNN111111NNN",
])

REPAIR_UPGRADE = hexagon([
    "NNNBBBBBBNNN",
    "NBBWWWWWWBBN",
    "BBWNN22NNWBB",
    "NNNNN22NNNNN",
    "NNNNN22NNNNN",
    "NNNNN22NNNNN",
    "NNNNN33NNNNN",
    "NNNNNNNNNNNN",
    "NwwwwwwwwwwN",
    "NyyyyyyyyyyN",
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
    "basic_crate_front_locked": BASIC_FRONT_LOCKED,
    "sturdy_crate_front_locked": STURDY_FRONT_LOCKED,
    "reinforced_crate_front_locked": REINFORCED_FRONT_LOCKED,
    "connection_block_side": CONNECTION_BLOCK_SIDE,
    "connection_block_end": CONNECTION_BLOCK_END,
    "connection_block_end_lit": CONNECTION_BLOCK_END_LIT,
    "deposit_box_back": DEPOSIT_BOX_BACK,
    "deposit_box_front": DEPOSIT_BOX_FRONT,
    "deposit_box_flank": DEPOSIT_BOX_FLANK,
    "deposit_box_spout": DEPOSIT_BOX_SPOUT,
    "interface_box_front": INTERFACE_BOX_FRONT,
    "interface_box_side": INTERFACE_BOX_SIDE,
    "interface_box_top": INTERFACE_BOX_TOP,
}
LOCK_UPGRADE = hexagon(LOCK_MOTIF)

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
