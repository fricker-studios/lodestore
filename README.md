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

Right-click opens the vanilla chest screen — the block entity extends `BaseContainerBlockEntity`
and hands back a `ChestMenu`, so there is no custom menu or screen to maintain, and hoppers, pipes
and the transfer API all interoperate with no extra work.

"Keeps contents" is loot-table driven: those tiers copy the implicit `minecraft:container`
component onto the dropped item, exactly as a shulker box does, and suppress the default spill in
`preRemoveSideEffects` so contents cannot be duplicated by doing both.

Nesting is blocked through `Container#canPlaceItem`, which covers hoppers as well as the GUI — a
rule enforced only in the UI would be bypassed by piping one in.

### Not yet

Single-item-type behaviour, grouping, sorting, limits and the upgrade system. Those are what will
make a drawer more than a chest.

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

**Unit.** `DrawerTierCodecTest` guards the datapack↔codec contract. A renamed field or out-of-range
value does not throw at runtime — the registry entry is silently dropped and drawers fall back — so
this is checked in CI rather than discovered in a world.

**Server gametests.** Hopper interop, comparator output, and drop-on-break. These need a real
ticking world: a plain dedicated server never block-ticks hoppers even in force-loaded chunks, so a
hopper assertion there passes whether or not the code works.

**Client gametests.** Builds a row of filled drawers, points the camera at them and screenshots the
result into `build/clientGametest/screenshots/`. Icon placement, orientation and "renders at all"
are exactly the failure modes no assertion catches. Not in CI: it drives a real client and would
need a virtual display on the runner.

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
