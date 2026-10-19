"""Shared PNG writer + palette for Lodestore sprite generation."""
import zlib, struct

PALETTE = {
    '.': (0, 0, 0, 0),
    # oak
    'H': (0xC9, 0xA6, 0x6E, 255),
    'L': (0xB5, 0x8D, 0x55, 255),
    'M': (0x9C, 0x76, 0x45, 255),
    'D': (0x82, 0x60, 0x37, 255),
    'K': (0x66, 0x4A, 0x2A, 255),
    'S': (0x4C, 0x37, 0x1F, 255),
    'X': (0x38, 0x28, 0x16, 255),
    # spruce (sturdy frame)
    'h': (0x8A, 0x66, 0x42, 255),
    'm': (0x74, 0x55, 0x36, 255),
    'd': (0x5C, 0x43, 0x2A, 255),
    's': (0x45, 0x32, 0x1F, 255),
    # iron
    'W': (0xE8, 0xE8, 0xE8, 255),
    'I': (0xC4, 0xC4, 0xC4, 255),
    'N': (0x9E, 0x9E, 0x9E, 255),
    'G': (0x74, 0x74, 0x74, 255),
    'B': (0x4E, 0x4E, 0x4E, 255),
    # gold (lock accent)
    'Y': (0xF2, 0xC8, 0x52, 255),
    'O': (0xC9, 0x97, 0x27, 255),
    'o': (0x8E, 0x69, 0x18, 255),
    # void (void upgrade): a purple-lit aperture over near-black
    'P': (0x8A, 0x5C, 0xB8, 255),
    'V': (0x4A, 0x2E, 0x6B, 255),
    'v': (0x17, 0x10, 0x24, 255),
    # copper and diamond, for the higher upgrade levels' carrier plates
    'j': (0xF0, 0xA8, 0x78, 255),
    'k': (0xC9, 0x7A, 0x4E, 255),
    'l': (0x8E, 0x52, 0x30, 255),
    'n': (0x7B, 0xE3, 0xDA, 255),
    'p': (0x4F, 0xC3, 0xB8, 255),
    'q': (0x2E, 0x8C, 0x85, 255),
    # vanilla GUI greys, for screen backgrounds
    'F': (0xFF, 0xFF, 0xFF, 255),  # lit bevel
    'C': (0xC6, 0xC6, 0xC6, 255),  # panel face
    'g': (0x8B, 0x8B, 0x8B, 255),  # slot interior
    'e': (0x55, 0x55, 0x55, 255),  # shaded bevel
    't': (0x37, 0x37, 0x37, 255),  # slot inset
    # redstone (future upgrades)
    'R': (0xE0, 0x4A, 0x3C, 255),
    'r': (0x9E, 0x2A, 0x22, 255),
}


def _assert_palette_unique():
    """Fails loudly if two entries claim the same character.

    PALETTE is a dict literal, so a duplicate key is kept silently and the earlier colour simply
    disappears — repainting whichever sprites used it, in a way nothing else would report. Two
    generators share this palette, so the clash is a question of when, not whether.
    """
    import re
    declared = re.findall(r"^\s{4}'(.)':", open(__file__, encoding="utf-8").read(), re.M)
    seen = set()

    for char in declared:
        assert char not in seen, f"palette character {char!r} is declared twice"
        seen.add(char)

    assert len(declared) == len(PALETTE), "palette entries did not survive parsing"


_assert_palette_unique()


def write_png(path, grid):
    for i, row in enumerate(grid):
        assert len(row) == len(grid[0]), f"{path}: row {i} is {len(row)}, expected {len(grid[0])}"
    h, w = len(grid), len(grid[0])
    raw = b''.join(
        b'\x00' + bytes(v for ch in row for v in PALETTE[ch])
        for row in grid
    )

    def chunk(tag, data):
        return (struct.pack('>I', len(data)) + tag + data
                + struct.pack('>I', zlib.crc32(tag + data) & 0xFFFFFFFF))

    png = (b'\x89PNG\r\n\x1a\n'
           + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
           + chunk(b'IDAT', zlib.compress(raw, 9))
           + chunk(b'IEND', b''))
    with open(path, 'wb') as fh:
        fh.write(png)
