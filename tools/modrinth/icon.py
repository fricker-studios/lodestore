#!/usr/bin/env python3
"""Sets the Modrinth project's icon from docs/modrinth/icon.png.

    MODRINTH_TOKEN=... icon.py [--file docs/modrinth/icon.png] [--project lodestore]

The picture is the sturdy crate as the inventory draws it, rendered by tools/sprites/make_icon.py.
Modrinth takes an icon by replacing it whole, so this is idempotent in the only sense that
matters: sending the same file again changes nothing. The token needs 'Write projects'.
"""
import argparse
import os
import pathlib
import sys
import urllib.error
import urllib.parse
import urllib.request

API = "https://api.modrinth.com/v2"
USER_AGENT = "fricker-studios/lodestore (github.com/fricker-studios/lodestore)"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--file", default="docs/modrinth/icon.png", type=pathlib.Path)
    parser.add_argument("--project", default=os.environ.get("MODRINTH_PROJECT") or "lodestore",
                        help="the Modrinth project slug or id; defaults to $MODRINTH_PROJECT, then 'lodestore'")
    args = parser.parse_args()

    token = os.environ.get("MODRINTH_TOKEN")

    if not token:
        sys.exit("MODRINTH_TOKEN is not set")

    body = args.file.read_bytes()

    if len(body) > 256 * 1024:
        sys.exit(f"{args.file} is {len(body)} bytes; Modrinth takes icons up to 256 KiB")

    path = f"/project/{urllib.parse.quote(args.project)}/icon?ext={args.file.suffix.lstrip('.')}"
    req = urllib.request.Request(API + path, data=body, method="PATCH", headers={
        "User-Agent": USER_AGENT, "Authorization": token, "Content-Type": f"image/{args.file.suffix.lstrip('.')}",
    })

    try:
        with urllib.request.urlopen(req, timeout=120):
            pass
    except urllib.error.HTTPError as error:
        sys.exit(f"PATCH {path} -> {error.code}: {error.read().decode('utf-8', 'replace')}")

    print(f"set the icon from {args.file} ({len(body)} bytes)")


if __name__ == "__main__":
    main()
