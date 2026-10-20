#!/usr/bin/env python3
"""Publishes one tagged build of Lodestore as a Modrinth version.

    MODRINTH_TOKEN=... publish.py --tag v0.3.0-rc.1 --jar build/libs/lodestore-0.3.0-rc.1.jar \
        --changelog build/changelog.md [--game-version 26.2] [--project lodestore] [--dry-run]

Idempotent: a version number Modrinth already has is left alone, so re-running a release, or
backfilling every tag at once, never creates a duplicate. The version number is the tag without
its leading v, exactly as the jar is named; a tag with a hyphen in it — a release candidate — is
published as a beta so it never shows as the latest stable download, which is the same rule the
GitHub release uses for its pre-release flag.

Standard library only, so it runs on any runner with a Python and nothing to install. The
Modrinth API wants a multipart form with a JSON part named `data` and the file after it, which
urllib does not build for us, so the body is assembled by hand below.
"""
import argparse
import json
import os
import pathlib
import sys
import urllib.error
import urllib.request
import uuid

API = "https://api.modrinth.com/v2"
USER_AGENT = "fricker-studios/lodestore (github.com/fricker-studios/lodestore)"
FABRIC_API = "P7dR8mSH"


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


def multipart(fields, files):
    """`fields` are name -> string; `files` are name -> (filename, bytes, mime)."""
    boundary = "----lodestore-" + uuid.uuid4().hex
    out = bytearray()

    for name, value in fields.items():
        out += f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n".encode()
        out += value.encode()
        out += b"\r\n"

    for name, (filename, data, mime) in files.items():
        out += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"; "
                f"filename=\"{filename}\"\r\nContent-Type: {mime}\r\n\r\n").encode()
        out += data
        out += b"\r\n"

    out += f"--{boundary}--\r\n".encode()
    return bytes(out), f"multipart/form-data; boundary={boundary}"


def version_type(version):
    if "-rc" in version:
        return "beta"

    return "alpha" if "-" in version else "release"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--tag", required=True, help="the git tag, e.g. v0.3.0-rc.1")
    parser.add_argument("--jar", required=True, type=pathlib.Path, help="the mod jar to upload")
    parser.add_argument("--changelog", type=pathlib.Path, help="a Markdown file; the GitHub release notes")
    parser.add_argument("--game-version", default="26.2", help="the Minecraft version the jar targets")
    parser.add_argument("--project", default="lodestore", help="the Modrinth project slug or id")
    parser.add_argument("--dry-run", action="store_true", help="print what would be sent and stop")
    args = parser.parse_args()

    version = args.tag[1:] if args.tag.startswith("v") else args.tag
    changelog = args.changelog.read_text(encoding="utf-8") if args.changelog and args.changelog.exists() else ""
    token = os.environ.get("MODRINTH_TOKEN", "")

    if not args.jar.is_file():
        sys.exit(f"no jar at {args.jar}")

    data = {
        "name": f"Lodestore {version}",
        "version_number": version,
        "changelog": changelog,
        "dependencies": [{"project_id": FABRIC_API, "dependency_type": "required"}],
        "game_versions": [args.game_version],
        "version_type": version_type(version),
        "loaders": ["fabric"],
        "featured": False,
        "status": "listed",
        "project_id": args.project,
        "file_parts": ["file"],
        "primary_file": "file",
    }

    if args.dry_run:
        print(json.dumps({**data, "file": str(args.jar)}, indent=2))
        return

    if not token:
        sys.exit("MODRINTH_TOKEN is not set")

    # The slug is enough to find the project — a draft project answers only to its owner, which
    # the token is — but a version wants the id.
    project = request("GET", f"/project/{args.project}", token)
    data["project_id"] = project["id"]

    for existing in request("GET", f"/project/{project['id']}/version", token) or []:
        if existing["version_number"] == version:
            print(f"Modrinth already has {version} ({existing['id']}); nothing to do")
            return

    body, content_type = multipart(
        {"data": json.dumps(data)},
        {"file": (args.jar.name, args.jar.read_bytes(), "application/java-archive")})
    created = request("POST", "/version", token, body, content_type)
    print(f"published {version} as {created['id']}: https://modrinth.com/project/{project['slug']}/version/{version}")


if __name__ == "__main__":
    main()
