# Security policy

Lodestore is a client- and server-side Minecraft mod. The most likely security-relevant bugs
are ones a player on a multiplayer server could abuse: duplicating items, reading or writing
storage they should not be able to reach, crashing the server with a crafted packet, or
corrupting another player's data.

## Supported versions

Only the latest release on the [releases page](https://github.com/fricker-studios/lodestore/releases)
receives fixes. There are no long-term support branches.

## Reporting a vulnerability

Please do **not** open a public issue for anything exploitable on a multiplayer server.

Use GitHub's private reporting instead:
**[Report a vulnerability](https://github.com/fricker-studios/lodestore/security/advisories/new)**.
That opens a draft advisory that only the maintainer can see.

Include the Lodestore, Minecraft, and Fabric Loader versions, what a player can do, and the
steps to do it. A log or a small world that demonstrates it is a big help.

You can expect an acknowledgement within a week. Once a fix ships, the advisory is published
with credit to you unless you ask otherwise.

Ordinary bugs, including singleplayer item loss, go through the normal
[bug report form](https://github.com/fricker-studios/lodestore/issues/new/choose).
