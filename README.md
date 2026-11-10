# Lodestore

Unified storage for Minecraft on Fabric: crates with upgrades, and networked storage, built on
Fabric's `Storage<ItemVariant>` transfer API.

**Download it from [Modrinth](https://modrinth.com/mod/lodestore).** That is where every release is published.

## This repository is for reports and questions

Lodestore's development moved off GitHub in November 2026, at version 0.3.0. This repository
stays open as the place to reach the maintainer:

- **Something is broken, crashes, or lost your items**:
  [file a bug report](https://github.com/fricker-studios/lodestore/issues/new/choose). Attach
  `logs/latest.log` or the crash report.
- **It fights with another mod**: the compatibility form, from the same page.
- **An idea for the mod**: the feature request form.
- **Questions, and things you built**:
  [Discussions](https://github.com/fricker-studios/lodestore/discussions).
- **A player can exploit it on a server**: see [SECURITY.md](SECURITY.md). Do not post it
  publicly.

Reports are read here and fixed in the next release on Modrinth. The issue is updated when
the fix ships.

## Source

Releases from 0.3.1 on are built elsewhere, and their source is not mirrored here. The source of
every version up to 0.3.0 is in this repository's history: browse a tag such as
[`v0.3.0`](https://github.com/fricker-studios/lodestore/tree/v0.3.0). Each release also ships a
`-sources.jar`. Lodestore is MIT licensed; see [LICENSE](LICENSE).

Pull requests are not accepted here, since the code no longer lives here. Describe the change
you have in mind in an issue instead.
