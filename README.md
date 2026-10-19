# Lodestore

Unified storage for Minecraft 26.2 (Fabric): backpacks, drawers, and networked storage,
built on `Storage<ItemVariant>` from `fabric-transfer-api-v1`.

## Toolchain (verified 2026-08-29 against live sources)

| Thing | Value | How it was verified |
|---|---|---|
| Minecraft | `26.2` (released 2026-06-16) | `meta.fabricmc.net/v2/versions/game`, Mojang piston-meta |
| Fabric Loader | `0.19.3` (stable) | `meta.fabricmc.net/v2/versions/loader` |
| Fabric API | `0.158.0+26.2` | `maven.fabricmc.net` maven-metadata |
| Loom plugin | `net.fabricmc.fabric-loom` `1.17.20` | plugin marker POM resolves; Gradle confirmed resolution |
| JDK | **25** (required) | piston-meta `javaVersion.majorVersion = 25` |
| Gradle | 9.5.1 (via wrapper) | wrapper bootstrapped locally |
| Mappings | **none — MC 26.x is unobfuscated** | see below |

### The mappings situation (important)

Minecraft 26.1 was the first release shipped **unobfuscated**. Consequences:

- Yarn stopped publishing after `1.21.11+build.6` (2026-05-27). `yarn/26.2` returns `[]`.
- `intermediary/26.2` resolves to a sentinel `intermediary-0.0.0.jar` (an identity/no-op artifact).
  A genuinely unknown version returns `[]`, so the `0.0.0` is deliberate, not a fallback.
- Mojang no longer ships `client_mappings` / `server_mappings` for 26.x at all.

So there is **no `mappings` line in `build.gradle`** — not Yarn, and not
`loom.officialMojangMappings()` either. There is nothing to map to. The old remapping
`fabric-loom` plugin is replaced by `net.fabricmc.fabric-loom`, which does not remap:

- `modImplementation` → `implementation`
- `remapJar` → `jar`

Minecraft classes are referenced by their official names, with some 26.x renames
(e.g. `net.minecraft.resources.Identifier`, and `ResourceKey#identifier()` rather than
`location()`).

## Prerequisites

JDK 25 is required. Only JDK 17/8 were present on this machine:

```bash
brew install --cask temurin@25
```

## Getting a build

Every pull request and every push to `main` builds the mod and attaches the jar to the workflow
run. Open the run from the Actions tab or the PR's checks, download the `lodestore-<sha>` artifact,
and drop the jar into `.minecraft/mods` next to Fabric API.

Tagged commits publish a GitHub Release with the jar attached:

```bash
git tag v0.2.0 && git push origin v0.2.0
```

The tag is the single source of truth for the version — it is passed through as `-Pversion`, so the
jar filename and the version inside `fabric.mod.json` cannot drift apart.

## Build & run

```bash
./gradlew build
```

```bash
./gradlew runClient
```

Then, in-game: `/give @s lodestore:storage_core`

## Status

Drawers are working containers with a GUI.

| Tier | Behaves like | Size | On break | May contain |
|---|---|---|---|---|
| Basic | chest | 27 | contents spill | anything |
| Sturdy | shulker box | 27 | contents stay inside | no drawers |
| Reinforced | shulker box | 54 | contents stay inside | sturdy drawers only |

Drawers are wood, so an axe is the efficient tool, as it is for a chest or barrel. They still break
without one, just slowly — no tool is required for them to drop.

Right-click opens the vanilla chest screen — the block entity extends `BaseContainerBlockEntity`
and hands back a `ChestMenu`, so there is no custom menu or screen to maintain, and hoppers, pipes
and the transfer API all interoperate with no extra work.

"Keeps contents" is loot-table driven: those tiers copy the implicit `minecraft:container`
component onto the dropped item, exactly as a shulker box does, and suppress the default spill in
`preRemoveSideEffects` so contents cannot be duplicated by doing both.

That copy is unconditional — `BaseContainerBlockEntity` writes the component whether or not the
drawer held anything — so the drawer **item** declares an empty `minecraft:container` of its own.
Without it, a sturdy or reinforced drawer that had been placed once came back carrying a component a
never-placed one did not, and the two would not stack: two piles of identical drawers in the
inventory, for no visible reason. Declaring it makes the copied value equal the item's prototype,
which `PatchedDataComponentMap` drops rather than records. Vanilla gives shulker boxes the same
component for the same reason. A gametest per tier breaks a drawer that was never filled and checks
the drop against a fresh item.

Nesting is blocked through `Container#canPlaceItem`, which covers hoppers as well as the GUI — a
rule enforced only in the UI would be bypassed by piping one in.

### Sprites

Every tier has its own front, side and top texture. They are one family of oak separated by the
hardware bolted to it, which is what the recipes describe: basic is plain, sturdy is a shade darker
with black L-brackets over each corner, and reinforced is iron-banded with rivets. Tone alone would
not survive being seen across a dark room, and hardware alone would not read at a glance.

The middle of each front is a flat recessed panel on purpose. The renderer floats the stored item
across roughly the middle half of the face and draws the count just below it, so anything busy there
competes with the icon and anything light there swallows the count.

| Where | What |
|---|---|
| `src/main/resources/assets/lodestore/textures/` | the 16×16 PNGs, which are the source of truth |
| `tools/sprites/` | the script the originals were generated from |

The script is an authoring aid, not a build step — nothing runs it. Re-running it overwrites the
PNGs, so anything edited by hand afterwards has to go back into the script or it is lost.

### Recipes

| Item | Recipe |
|---|---|
| Basic drawer | planks around an iron nugget, **or** a chest plus an iron nugget |
| Sturdy drawer | a basic drawer surrounded by planks |
| Reinforced drawer | a sturdy drawer surrounded by iron ingots |
| Lock upgrade | gold nuggets around an iron ingot, over a second ingot |

Each recipe ships an advancement that reveals it in the recipe book once you are holding any of its
ingredients — planks or an iron nugget for a basic drawer, an iron ingot or a sturdy drawer for a
reinforced one, and so on. The two basic drawer recipes share a group, so they collapse into a
single recipe book entry rather than appearing twice.

Both are covered by gametests: one crafts each recipe and checks which matched, the other checks
every advancement loads and grants the recipe it is named for. Neither a malformed recipe nor a
malformed advancement fails the build or throws — each is logged and skipped, leaving either a
missing recipe or one that works but can never be discovered.

### Upgrades

Upgrades are datapack-driven, the same way tiers are. An entry at
`data/<pack>/lodestore/drawer_upgrade/<name>.json` is a set of behaviour flags, and an item carries
the key of the entry it installs. A pack adds an upgrade by pairing a new item with a new entry; it
never names an implementation, so the mod ships no branch per upgrade.

A drawer holds up to three upgrades, one of each kind. Right-clicking a drawer while holding one
installs it — the single exception to "right-clicking opens the drawer whatever is in hand", because
an upgrade does nothing else anywhere, whereas that rule exists to stop a drawer reacting to the
building blocks a player happens to be carrying. A drawer that is already upgraded falls through and
opens rather than eating the click.

Upgrades are kept outside the drawer's inventory. In it, they would show up in the chest screen, a
hopper could pull one out, and for the tiers whose contents ride inside the dropped item they would
duplicate along with everything else. They drop as items when the drawer is broken, for every tier —
the `minecraft:container` component only carries the inventory, so nothing else would carry them.

| Upgrade | Does |
|---|---|
| Lock | restricts the drawer to one kind of item |

The lock locks to whatever the drawer already holds most of. On an empty drawer it has nothing to
claim yet, so it takes the first item in — locking to "nothing" would make the drawer refuse
everything, including the item it was installed for. The filter outlives the upgrade being removed,
so re-locking a drawer restores the filter it had rather than silently re-claiming whatever happens
to be inside at the time.

It is enforced in `Container#canPlaceItem`, next to the nesting rule and for the same reason: a
filter applied only by the chest screen would be bypassed by piping items in.

A locked drawer reads differently on the face. Its count is drawn in the lock's own gold rather than
white, and once emptied it keeps showing its filter — drawn smaller, over a count of zero — because
an empty locked drawer is exactly the one that most needs labelling. That is the whole point of
locking a drawer before the items arrive.

### Not yet

Voiding overflow, capacity limits, grouping and sorting. Capacity is the awkward one: size is
structural, decided before the level's registries are reachable, so a capacity upgrade cannot just
be another flag in the registry.

Single-item-type behaviour is partly here — a locked drawer is one — but choosing the displayed item
independently of the filter is not.

The front face shows whichever item the drawer holds most of, counted across every slot holding it.
Which item is displayed will become customisable later.

That preview is synced on its own — one stack and a count — rather than by broadcasting the
inventory, so the payload is the same whether the drawer holds one item or fifty-four stacks, and a
packet only goes out when the face would actually look different.

## Tests

Three layers, in increasing cost and decreasing frequency.

```bash
./gradlew test              # unit — no Minecraft bootstrap
./gradlew runGametest       # server gametests in a real ticking world
./gradlew runClientGametest # drives a real client, writes screenshots
```

**Unit.** `DrawerTierCodecTest` and `DrawerUpgradeCodecTest` guard the datapack↔codec contract. A
renamed field or out-of-range value does not throw at runtime — the registry entry is silently
dropped and drawers fall back — so this is checked in CI rather than discovered in a world. For the
lock the fallback is inert by design, which means a dropped entry turns a drawer the player believes
is filtered into one that quietly accepts anything.

**Server gametests.** Persistence, hopper interop, comparator output, drop-on-break, that a broken
drawer still stacks with a fresh one, and the lock:
that it claims the right item, refuses everything else, cannot be bypassed by a hopper, and does not
swallow what it refuses. These need a real ticking world: a plain dedicated server never block-ticks
hoppers even in force-loaded chunks, so a hopper assertion there passes whether or not the code
works.

**Client gametests.** Two of them. One builds a row of drawers — one of each tier, plus a locked
drawer with stock and a locked drawer that has been emptied — and screenshots it three times into
`build/clientGametest/screenshots/`: the row, a close-up of the tier sprites, and a close-up of the
two lock states. Icon placement, orientation, which sprite ended up on which face and "renders at
all" are failure modes no assertion catches. The other quits to the title screen and
reopens the same save, which is the only way to exercise a real disconnect: server gametests never
round-trip a block entity through a save file, so anything that only breaks across a save and load
is invisible to them.

Not in CI: they drive a real client and would need a virtual display on the runner.

## Guardrails

A container that never writes its contents is silent: nothing throws, nothing looks wrong, and the
items are simply gone after a reload. Drawer contents were lost that way once, past sixteen passing
tests, because none of them round-tripped a block entity through a save.

`PersistenceSelfCheck` fills every registered Lodestore container, writes it, reads it back, and
compares. It runs in two places from one implementation:

- as a gametest, so a regression fails CI;
- once per server start — dedicated or singleplayer — logging an error naming each container that
  would lose items. Silent when healthy.

It iterates whatever is registered, so a backpack or network cell added later is covered without
anyone remembering to extend it. Both paths were verified by reintroducing the original bug and
confirming each one fires.

It covers `Container` contents and nothing else, because filling a container is the only thing it
can do generically. State that lives outside the inventory is invisible to it — a drawer's upgrades
and its lock filter are exactly that, and would be lost on reload just as silently. They have their
own save-and-load round trip in `upgradeStateSurvivesASaveAndLoad` instead. Anything else added
outside the inventory needs the same treatment, or it is not covered at all.

## Source sets

| Set | Ships in the jar | Holds |
|---|---|---|
| `main` | yes | the mod, common to both sides |
| `client` | yes | client-only code, including the drawer renderer |
| `testmod` | **no** | server gametests, `/lodestore_debug` |
| `testmodClient` | **no** | client gametests |

`testmod` and `testmodClient` build a separate `lodestore-testmod` mod that is loaded only by the
gametest run configurations. Keeping them out of `main` is what keeps test classes and the debug
command out of the published artifact — a runtime environment check would still have shipped them.
