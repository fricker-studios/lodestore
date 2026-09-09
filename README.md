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

**Phase 1 — storage core + drawers.** Complete and building; not yet verified in a running game.

Three datapack-defined tiers (`basic` 8 stacks/slot, `sturdy` 32, `reinforced` 128), each a
`DrawerBlock` whose capacity resolves from `lodestore:drawer_tier` at runtime. Contents are
`ItemVariant + long`, never `ItemStack.count`. Drawers expose `Storage<ItemVariant>` on every side
via `SidedStorageBlockEntity`, so hoppers and any transfer-API mod interoperate for free. No block
entity ticks.

Interaction: right-click with an item deposits; right-click empty-handed withdraws a stack;
sneak + right-click prints contents (a stand-in until the front-face overlay lands).

### Verified in a running server

Checked on a headless dedicated server (26.2, Fabric 0.19.3). `Mappings not present!` in the log is
the expected signature of the unobfuscated setup.

| Check | Result |
|---|---|
| Mod loads, registries populate | `lodestore 0.1.0`, init clean, no errors |
| `ItemStorage.SIDED` lookup | resolves to `CombinedSlottedStorage` — the same path hoppers and other mods use |
| Insert / extract | 300 in, 50 out, correct every time |
| Capacity from datapack | basic **512** (8x64), reinforced **8192** (128x64) — proves the dynamic registry loaded, since the fallback would cap at 64 |
| Capacity clamping | at 250/512, asked for 1000, accepted exactly 262 |
| Transaction rollback | aborted transactions leave the total untouched (512 to 512); no leak |
| `long` counts | persisted as `amount: 4950L`, far past any stack-based model |
| Save / reload | 462 and 4950 both survived a full server restart, and capacity re-resolved correctly |

Verification used `/lodestore_debug <x> <y> <z> <count>`, a dev-only command registered solely when
`FabricLoader.isDevelopmentEnvironment()` is true.

> **Note on hopper testing.** A playerless dedicated server does not block-tick hoppers, even in
> force-loaded chunks — a vanilla chest-to-chest control moved zero items in 70 seconds. Any
> hopper-based verification there passes vacuously. Interop is therefore checked through the
> `ItemStorage.SIDED` lookup directly, which is what the hopper itself calls.

### Known gaps, carried into P1b

- **No client sync.** `getUpdateTag`/`getUpdatePacket` are not implemented, so clients do not see
  drawer contents. Harmless today because nothing renders them; required before the count overlay.
- **No front-face count rendering.** The block entity renderer is the whole of P1b.
- **Recipes do not auto-unlock.** No advancement JSONs ship, so the recipes work but will not appear
  in the recipe book until crafted once.
- **Lock / void / capacity upgrades** are deliberately deferred to P2 with the upgrade framework.

## Tests

```bash
./gradlew test
```

`DrawerTierCodecTest` guards the datapack↔codec contract. A renamed field or out-of-range value
does not throw at runtime — the registry entry is silently dropped and drawers fall back — so this
is checked in CI rather than discovered in a world.
