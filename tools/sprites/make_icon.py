#!/usr/bin/env python3
"""The project icon: the sturdy crate as the inventory draws it, at logo size.

    python3 tools/sprites/make_icon.py [docs/modrinth/icon.png] [--size 512]

Minecraft draws a block item in a slot with the `gui` display transform every block model
inherits: turned 225 degrees about y, tipped 30 degrees about x, and projected flat. That leaves
the top, the north face and the east face in view — the north face at the right, the east at the
left, their vertical edges vertical and the top a rhombus two wide for every one high — lit from
above so the top is brightest, the left face next and the right face darkest. This draws the same
picture from the crate's three textures, sampling each texel without smoothing so the result is
the sprite enlarged and not a blur of it, on a transparent ground with a margin.

Pure stdlib, like the other sprite tools. Re-run after the sturdy crate's textures change.
"""
import argparse
import math
import os
import struct
import sys
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pixels import PALETTE  # noqa: E402
import make_textures as textures  # noqa: E402

# How much of the light each visible face gets, as the inventory shades them.
TOP, LEFT, RIGHT = 1.0, 0.80, 0.62

# Where a unit step along each axis lands on screen (x right, y up) after the gui transform:
# the x and z edges fall away left at a 1:2 slope, the y edge stays vertical.
EX = (-math.sqrt(0.5), -math.sqrt(0.5) * math.sin(math.radians(30)))
EY = (0.0, math.cos(math.radians(30)))
EZ = (-math.sqrt(0.5), math.sqrt(0.5) * math.sin(math.radians(30)))


def add(*vectors):
    return tuple(sum(v[i] for v in vectors) for i in range(2))


def scale(k, v):
    return (k * v[0], k * v[1])


class Face:
    """A textured square: a screen-space origin plus the screen vectors of its u and v edges."""

    def __init__(self, origin, du, dv, texture, light):
        self.origin, self.du, self.dv, self.texture, self.light = origin, du, dv, texture, light
        # Inverse of the 2x2 [du dv] for mapping a screen point back to (u, v).
        det = du[0] * dv[1] - dv[0] * du[1]
        self.inv = (dv[1] / det, -dv[0] / det, -du[1] / det, du[0] / det)

    def sample(self, point):
        x, y = point[0] - self.origin[0], point[1] - self.origin[1]
        u = self.inv[0] * x + self.inv[1] * y
        v = self.inv[2] * x + self.inv[3] * y

        if not (0 <= u < 1 and 0 <= v < 1):
            return None

        colour = PALETTE[self.texture[int(v * 16)][int(u * 16)]]
        return tuple(min(255, round(c * self.light)) for c in colour[:3]) + (colour[3],)


def faces():
    """The three faces the inventory shows, each with its texture the way the model maps it."""
    half = 0.5
    # Top: u runs with x, v with z, from the north edge.
    top = Face(add(scale(-half, EX), scale(half, EY), scale(-half, EZ)), EX, EZ, textures.STURDY_TOP, TOP)
    # North face, on the right: u runs west, v down.
    north = Face(add(scale(half, EX), scale(half, EY), scale(-half, EZ)), scale(-1, EX), scale(-1, EY),
                 textures.STURDY_FRONT, RIGHT)
    # East face, on the left: u runs north, v down.
    east = Face(add(scale(half, EX), scale(half, EY), scale(half, EZ)), scale(-1, EZ), scale(-1, EY),
                textures.STURDY_SIDE, LEFT)
    return (top, north, east)


def render(size, margin=0.06):
    # The cube is sqrt(2) wide and cos 30 + sin 30 * sqrt(2) high in model units; fit the height.
    height = EY[1] + 2 * abs(EZ[1])
    unit = size * (1 - 2 * margin) / height
    pixels = [[(0, 0, 0, 0)] * size for _ in range(size)]
    visible = faces()

    for row in range(size):
        for col in range(size):
            # Pixel centre, in model units, with y up and the cube centred.
            point = ((col + 0.5 - size / 2) / unit, (size / 2 - row - 0.5) / unit)

            for face in visible:
                colour = face.sample(point)

                if colour is not None:
                    pixels[row][col] = colour
                    break

    return pixels


def write_png(path, pixels):
    size = len(pixels)
    raw = b"".join(b"\x00" + bytes(v for p in row for v in p) for row in pixels)

    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    with open(path, "wb") as fh:
        fh.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
                 + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("out", nargs="?", default="docs/modrinth/icon.png")
    parser.add_argument("--size", type=int, default=512)
    args = parser.parse_args()
    write_png(args.out, render(args.size))
    print(f"  {args.out}  {args.size}x{args.size}")


if __name__ == "__main__":
    main()
