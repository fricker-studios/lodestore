# Contributing to Lodestore

Thanks for your interest. Lodestore is a small, opinionated Fabric mod maintained in spare
time and funded by its players, so the guide below is mostly about not wasting yours.

## Ways to help

- **Report bugs and compatibility problems** through the [issue forms](https://github.com/fricker-studios/lodestore/issues/new/choose).
  The forms ask for versions and a log because a report without them nearly always needs a
  second round.
- **Suggest features** with the feature request form. Say what problem it solves in-game;
  that matters more than the exact block you have in mind.
- **Ask questions or share builds** in [Discussions](https://github.com/fricker-studios/lodestore/discussions).
- **Sponsor the project** with the Sponsor button on the repository page. Sponsorship pays for
  the time that goes into releases and compatibility work.
- **Send a pull request.** Details below.

## Before you write code

Open an issue first for anything bigger than a bug fix or a one-line tweak, and wait for a
nod before investing an evening in it. Lodestore has a specific design (storage that composes
through Fabric's `Storage<ItemVariant>` transfer API, with capacity limits that make the
upgrades matter), and some reasonable-sounding features do not fit it. An issue is the cheap
place to find that out.

Good first contributions are labelled
[`good first issue`](https://github.com/fricker-studios/lodestore/labels/good%20first%20issue).

## Setting up

You need **JDK 25**. The README's [Toolchain](README.md#toolchain-verified-2026-08-29-against-live-sources)
section has the exact Minecraft, Loader, and Fabric API versions and explains why there is no
mappings line: Minecraft 26.x ships unobfuscated.

```bash
./gradlew build        # compiles and runs the unit tests
./gradlew runClient    # launches a dev client with the mod loaded
```

In-game, `/give @s lodestore:basic_crate` or open the Lodestore creative tab.

## Tests

There are three layers, described fully in the README's [Tests](README.md#tests) section:

```bash
./gradlew test               # unit tests, no Minecraft bootstrap
./gradlew runGametest        # server gametests in a real ticking world
./gradlew runClientGametest  # client gametests, writes screenshots
```

A change to how items move or persist needs a gametest, not just a unit test. The README
explains why: a container that never writes its contents is silent, and the project has lost
items that way once already. The gametests in `src/testmod` are written in pairs (the
behaviour on, the behaviour off) so a test cannot pass for the wrong reason. Follow that
pattern.

Anything that adds a new container must survive `PersistenceSelfCheck` (it iterates
everything registered, so you do not need to extend it) and needs its own save-and-load
round trip for any state kept outside the contents.

## Project layout

| Source set | Ships in the jar | What goes there |
|---|---|---|
| `main` | yes | the mod, common to both sides |
| `client` | yes | client-only code such as renderers and screens |
| `testmod` | no | server gametests and the `/lodestore_debug` command |
| `testmodClient` | no | client gametests |

Keep test and debug code out of `main`; the split is what keeps it out of the release jar.

## Pull requests

- One change per PR. Two unrelated fixes are two PRs.
- Branch from `main`. The PR template asks what changed, why, and how you tested it.
- CI builds every PR and comments a download link for the jar, so reviewers (and you) can
  try it in a real game.
- A code owner has to approve before merge; that is enforced by the repository rules, not
  just convention. Review comments are a conversation, not a verdict.
- New blocks, items, and recipes need their lang entries, models, loot tables, and a creative
  tab entry. The build does not check for missing ones; the game just shows purple-and-black.
- If a player- or developer-facing behaviour changes, update the README in the same PR.

Commit messages: a short imperative subject line and, when the *why* is not obvious from the
diff, a body that explains it. The git history is the project's design log.

## Versions and releases

The git tag is the only source of the version. Do not add one to `gradle.properties` or
`fabric.mod.json`; the README explains the bug that rule came from. Releases are cut by the
maintainer by pushing a `v*` tag.

## License

By contributing you agree that your contribution is licensed under the
[MIT License](LICENSE), the same as the rest of the project.
