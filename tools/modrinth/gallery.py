#!/usr/bin/env python3
"""Uploads the gallery images in docs/modrinth/gallery/ to the Modrinth project.

    MODRINTH_TOKEN=... gallery.py [--dir docs/modrinth/gallery] [--project lodestore] [--dry-run]

The directory's `gallery.json` lists the images in the order they should appear, each with the
title and description Modrinth shows under it. Idempotent by title: an image whose title the
project's gallery already has is skipped, so the whole set can be re-sent after adding one.
Replacing a picture means removing the old one on Modrinth first, or giving the new one a new
title — a gallery entry's image cannot be changed through the API, only its text.

The images themselves come from the client screenshot test, MarketingScreenshotsClientTest,
copied in from build/clientGametest/screenshots/ once they have been looked at.
"""
import argparse
import json
import os
import pathlib
import sys
import urllib.error
import urllib.parse
import urllib.request

API = "https://api.modrinth.com/v2"
USER_AGENT = "fricker-studios/lodestore (github.com/fricker-studios/lodestore)"


def request(method, path, token, body=None, content_type=None):
    headers = {"User-Agent": USER_AGENT, "Authorization": token}

    if content_type:
        headers["Content-Type"] = content_type

    req = urllib.request.Request(API + path, data=body, method=method, headers=headers)

    try:
        with urllib.request.urlopen(req, timeout=120) as response:
            raw = response.read()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", "replace")
        sys.exit(f"{method} {path} -> {error.code}: {detail}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dir", default="docs/modrinth/gallery", type=pathlib.Path)
    parser.add_argument("--project", default="lodestore", help="the Modrinth project slug or id")
    parser.add_argument("--dry-run", action="store_true", help="print what would be sent and stop")
    args = parser.parse_args()

    manifest = json.loads((args.dir / "gallery.json").read_text(encoding="utf-8"))
    token = os.environ.get("MODRINTH_TOKEN", "")

    for entry in manifest:
        image = args.dir / entry["file"]

        if not image.is_file():
            sys.exit(f"gallery.json names {entry['file']}, which is not in {args.dir}")

    if args.dry_run:
        print(json.dumps(manifest, indent=2))
        return

    if not token:
        sys.exit("MODRINTH_TOKEN is not set")

    project = request("GET", f"/project/{args.project}", token)
    present = {item.get("title") for item in project.get("gallery", [])}

    for ordering, entry in enumerate(manifest):
        if entry["title"] in present:
            print(f"gallery already has '{entry['title']}'; skipped")
            continue

        image = args.dir / entry["file"]
        query = urllib.parse.urlencode({
            "ext": image.suffix.lstrip("."),
            "featured": "true" if entry.get("featured") else "false",
            "title": entry["title"],
            "description": entry.get("description", ""),
            "ordering": entry.get("ordering", ordering),
        })
        request("POST", f"/project/{project['id']}/gallery?{query}", token, image.read_bytes(),
                f"image/{'jpeg' if image.suffix.lower() in ('.jpg', '.jpeg') else image.suffix.lstrip('.')}")
        print(f"uploaded '{entry['title']}' from {entry['file']}")


if __name__ == "__main__":
    main()
