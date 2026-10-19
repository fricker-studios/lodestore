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

| Tier | Behaves like | Size | Upgrade slots | On break | May contain |
|---|---|---|---|---|---|
| Basic | chest | 27 | 1 | contents spill | anything |
| Sturdy | shulker box | 27 | 2 | contents stay inside | no drawers |
| Reinforced | shulker box | 54 | 3 | contents stay inside | sturdy drawers only |

Drawers are wood, so an axe is the efficient tool, as it is for a chest or barrel. They still break
without one, just slowly — no tool is required for them to drop.

Right-click opens Lodestore's own screen — the vanilla chest grid plus a column of upgrade slots.
The block entity still extends `BaseContainerBlockEntity` and is still a plain `Container`, so
hoppers, pipes and the transfer API interoperate with no extra work; see [The screen](#the-screen)
for why the vanilla `ChestMenu` it used to hand back was given up.

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

Upgrades share a carrier: both sit on the same bevelled iron plate, so they read as one family of
hardware rather than two unrelated trinkets, and only the motif differs — a gold padlock against a
dark aperture. The two are deliberately unlike at inventory size, which matters when one of them
destroys things.

| Where | What |
|---|---|
| `src/main/resources/assets/lodestore/textures/` | the PNGs, which are the source of truth |
| `tools/sprites/make_textures.py` | blocks and items |
| `tools/sprites/make_gui.py` | the screen's upgrade panel |

The scripts are an authoring aid, not a build step — nothing runs them. Re-running one overwrites
its PNGs, so anything edited by hand afterwards has to go back into the script or it is lost.

Both share one palette, and a palette is a namespace: a Python dict literal keeps the **last** of
any duplicated key without complaint, so when copper and the void upgrade both claimed `v` the void
sprite was quietly repainted and nothing reported it. `pixels.py` now checks its own source for
duplicate characters at import and fails loudly instead.

The upgrade levels are told apart by the metal of their carrier plate — iron, copper, diamond —
rather than by a numeral, which at sixteen pixels would be four pixels tall and unreadable in a
hotbar.

### Recipes

| Item | Recipe |
|---|---|
| Basic drawer | planks around an iron nugget, **or** a chest plus an iron nugget |
| Sturdy drawer | a basic drawer surrounded by planks |
| Reinforced drawer | a sturdy drawer surrounded by iron ingots |
| Lock upgrade | gold nuggets around an iron ingot, over a second ingot |
| Lock upgrade II | a lock upgrade ringed with copper ingots |
| Lock upgrade III | a lock upgrade II ringed with diamonds |
| Void upgrade | obsidian around an iron ingot, over a second ingot |

Each recipe ships an advancement that reveals it in the recipe book once you are holding any of its
ingredients — planks or an iron nugget for a basic drawer, an iron ingot or a sturdy drawer for a
reinforced one, and so on. The two basic drawer recipes share a group, so they collapse into a
single recipe book entry rather than appearing twice.

Both are covered by gametests: one crafts each recipe and checks which matched, the other checks
every advancement loads and grants the recipe it is named for. Neither a malformed recipe nor a
malformed advancement fails the build or throws — each is logged and skipped, leaving either a
missing recipe or one that works but can never be discovered.

### The screen

Right-clicking a drawer opens Lodestore's own screen: the vanilla chest grid, with a column of
upgrade slots beside it.

This is a trade the project made reluctantly. Until upgrades needed managing after they were
installed, drawers handed back a vanilla `ChestMenu` and there was no menu or screen to maintain at
all. There is no way to show upgrade slots without a custom menu, so there is now one — but the body
is still vanilla's own texture, blitted the way vanilla blits it (the grid from the top of the file,
the player-inventory section from a fixed offset), so one texture serves any row count. Only the
upgrade column is ours.

Hoppers, pipes and the transfer API are untouched by this: they talk to the block entity's
`Container`, not to the menu.

The menu adds exactly `rows * 9` storage slots. A voiding drawer's container is one larger than its
storage, and that overflow slot must never appear on screen — it always reads empty and discards
what is put in it.

### Upgrades

Upgrades are datapack-driven, the same way tiers are. An entry at
`data/<pack>/lodestore/drawer_upgrade/<name>.json` is a set of behaviour flags plus a
`slot_capacity`, and an item carries the key of the entry it installs. A pack adds an upgrade by
pairing a new item with a new entry; it never names an implementation, so the mod ships no branch
per upgrade.

| Upgrade | Does | Reaches |
|---|---|---|
| Lock | a covered slot takes one kind of item and nothing else | 9 slots |
| Lock II | the same, further | 18 slots |
| Lock III | the same, further again | 27 slots |
| Void | a covered slot discards what it has no room for | 9 slots |

Both shipped upgrades change *what a covered slot accepts*. Neither changes *how much* it holds, and
that is not an omission waiting to be filled in — see
[Why there is no stack upgrade](#why-there-is-no-stack-upgrade). A behaviour flag can only ever be
as good as the vanilla hook it is enforced through, and the per-slot capacity hook does not exist.

**Levels are data, not code.** A level-two lock is a level-one lock with a larger number in its
JSON. Capacity pools across everything installed, so two level-one locks reach exactly as far as one
level-two, and two level-ones plus a level-two reach four rows. That arithmetic is the point: a
player can work out what they have without consulting a table.

Duplicates are therefore allowed, which is a reversal — a drawer used to refuse a second upgrade of
the same kind. Refusing one now would make the pooling impossible to express.

How many upgrades a drawer holds at once is its tier's business, not the upgrade's: one for basic,
two for sturdy, three for reinforced.

Install by dragging an upgrade into the column, or by right-clicking the drawer while holding one —
the latter is the single exception to "right-clicking opens the drawer whatever is in hand", because
an upgrade does nothing else anywhere, whereas that rule exists to stop a drawer reacting to the
building blocks a player happens to be carrying.

Upgrades are kept outside the drawer's inventory. In it, they would be visible to hoppers, and for
the tiers whose contents ride inside the dropped item they would duplicate along with everything
else. They drop as items when the drawer is broken, for every tier.

#### Choosing which slots an upgrade covers

Right-click an installed upgrade to start choosing. Covered slots are tinted — the lock's gold, the
void's purple — and the upgrade being edited outlines its own. Click a storage slot to add or remove
it; shift-click to act on everything between the last slot clicked and this one. The upgrade's
tooltip says so, because the gesture is not one anything else in the game teaches.

**Choosing nothing covers the first rows, not none of them.** An upgrade that did nothing until its
owner had clicked nine slots would simply look broken, so an empty selection means "as far as this
upgrade reaches, from the start" — one row for a level-one lock, two for a level-two, and so on.
There is no error state for having chosen nothing, because nothing is a valid answer.

Capacity caps the selection rather than growing it: a level-one lock covers nine slots, so taking a
tenth means giving one up first.

The gesture rides vanilla's container-button packet rather than a payload of our own, because that
path is already validated against the container the player actually has open — exactly the check a
bespoke packet would have to reimplement.

Targeting is sent to the client **resolved**. The client deliberately never receives the installed
upgrades, so it cannot work out how far they reach; it is sent the answer instead. The disk keeps
what was actually chosen, so "nothing chosen" stays a default and follows the upgrades if they
change.

#### Locking

A covered slot claims the item it holds, or the first one put into it, and refuses anything else
afterwards. The claim is stored rather than read back from the contents, so it survives the slot
being emptied: an empty locked slot still says what belongs in it, which is the whole reason to lock
a drawer before the items arrive.

It is enforced in `Container#canPlaceItem`, next to the nesting rule and for the same reason: a
filter applied only by the screen would be bypassed by piping items in. Fabric's hopper patch bails
out as soon as vanilla finds a `Container`, and the transfer API wraps any `Container`
automatically, so that one hook covers hoppers, droppers, pipes and the screen alike.

Scoping cuts both ways, and that is deliberate: past an upgrade's reach a hopper may still fill the
drawer with anything. A level-one lock on a 27-slot drawer locks nine slots, not twenty-seven.

The front face draws a locked drawer's claims, cycling through them when there are several, and
tints its count in the lock's gold.

#### Voiding

A covered slot discards what it has no room for, rather than backing the system up.

Voiding used to require the lock flag: it was a drawer-wide switch, and the filter was the only
thing standing between it and destroying whatever arrived. Now that it is scoped to the slots it
covers, and only ever applies to what one of those slots already holds or has claimed, the lock flag
is no longer what makes it safe — so the shipped void upgrade no longer sets it.

The mechanism is a single overflow slot past the end of the storage, and it is worth knowing why:

- **`canPlaceItem` cannot express voiding.** It answers yes or no about a stack; voiding means
  accepting one and discarding it. `HopperBlockEntity.ejectItems` also bails at `isFullContainer`
  before any per-slot check, so a drawer with no room would never be offered the item at all.
- **The overflow slot always reads empty and discards what is written to it**, which is what makes a
  full voiding drawer look like it has room.
- **It is a real index, not one past the end.** The transfer API's sided wrapper indexes a list
  sized from `getContainerSize()` and would throw otherwise.
- **`getContainerSize()` is therefore one larger than the storage.** Anything measuring fullness has
  to use `storageSize()` — the comparator does, which is why a full drawer reads 15 rather than 14.

#### Why there is no stack upgrade

A stack upgrade — covered slots holding more than a normal stack — was designed against the
machinery above, measured, and abandoned. It is the one upgrade the slot-targeting design cannot
carry, and the reasons are in Minecraft rather than in Lodestore. They are kept as assertions in
`StackCeilingGameTests` rather than only as this section, so that a version which lifts one of them
turns a test red instead of leaving a paragraph quietly wrong.

**What governs a slot's capacity depends entirely on who is asking.** Raising
`Container#getMaxStackSize` and its per-stack overload is obeyed by some paths and ignored by
others, and the split is not a matter of degree:

| Path | Asks | Honours the container? |
|---|---|---|
| The screen | `Slot#getMaxStackSize(ItemStack)` | yes |
| Pipes, the transfer API | `ContainerSlotWrapper#getCapacity` → `Container#getMaxStackSize(stack)` | yes |
| The comparator | `Container#getMaxStackSize(stack)` | yes |
| **Hoppers, droppers** | `ItemStack#getMaxStackSize()`, inline | **no** |
| **`minecraft:container` on break** | `ItemStackTemplate#create` → `ItemStack.validateStrict` | **no** |
| **The save file** | `ItemStack.MAP_CODEC`, `count` is `intRange(1, 99)` | **no** |

So there is no answer to "how full is this slot" that the screen, a hopper and a pipe would all
give. That is the thing the feature would have had to deliver and the thing it cannot.

Four findings, each measured rather than reasoned about:

- **The vanilla hopper does not ask the container at all.** `HopperBlockEntity.tryMoveInItem`
  computes the room in a slot as `itemStack.getMaxStackSize() - current.getCount()`, with the
  container nowhere in the expression. A container claiming 256 gets a hopper that fills to 64 and
  then sits there full. Fabric's hopper patch does not help: `HopperBlockEntityMixin` takes the
  transfer-API route only `if (container == null)`, and a drawer is a `Container` on purpose —
  being one is exactly what makes hoppers, pipes and droppers work with no code at all. Measured
  both ways: a slot at 64 took nothing, and the same slot at 32 took exactly 32. Droppers are the
  same path rather than a second one — `DropperBlock.dispenseFrom` calls the very
  `HopperBlockEntity.addItem` the test calls — so one assertion covers both.
- **`minecraft:container` destroys an over-sized stack.** Reading the component back goes through
  `ItemStackTemplate#create`, which validates and returns `ItemStack.EMPTY` for anything past the
  item's own maximum. 65 cobblestone is already gone — so the component cannot carry an over-stack
  of any size, and that component is how sturdy and reinforced drawers keep their contents when
  broken. The slot vanishes with one line in the log. This is the finding that settles it: no care
  on the insertion side can make a feature safe whose contents evaporate when the block is mined.
- **99 is a hard ceiling on disk.** `ContainerHelper.saveAllItems` writes through
  `ItemStack.MAP_CODEC`, whose `count` is `intRange(1, 99)`. A stack of 100 does not throw and does
  not clamp: the encode fails, the problem reporter swallows it, and the slot is simply absent —
  `{Items:[]}`. The drawer reloads a slot short and nothing says why. Even granting the two findings
  above, the feature could only ever have offered 99 against a normal 64, and the hopper could never
  have filled the difference.
- **An over-sized stack would not have stayed in the drawer.** `AbstractContainerMenu#doClick` takes
  a slot's whole contents onto the cursor with `slot.tryRemove(clicked.getCount(), MAX_VALUE,
  player)` — no container ceiling, no item limit. One left click and it is loose in an inventory or
  on the ground, past every check a drawer could make, and above 99 it cannot be written at all, so
  the next save destroys it.

There is also a shape problem underneath the arithmetic. **`Container#getMaxStackSize(ItemStack)`
takes no slot.** A stack upgrade covers *chosen* slots, but the only container-level hook is
per-container, so pipes and the comparator would apply the raised ceiling to every slot in the
drawer while the screen applied it only to the covered ones. The targeting that the lock and the
void upgrade are built on cannot be expressed through `Container` at all.

Doing it properly therefore means one of two things, both of which are larger than an upgrade:
giving drawers a real `Storage<ItemVariant>` and giving up being a plain `Container` — which is what
makes the current interop free — or mixing into `HopperBlockEntity` and into vanilla's item-stack
validation, which is reckless in the second case because that validation is shared with bundles and
crossbows. Neither is worth a stack upgrade on its own; both are worth revisiting when networked
storage arrives and the `Storage<ItemVariant>` question has to be answered anyway.

### Not yet

Grouping, sorting, and stack upgrades — the last for the reasons set out above. Capacity is the
awkward one in a second way too: container size is structural, decided before the level's registries
are reachable, so even a plain *more slots* upgrade cannot just be another flag in the registry the
way lock levels are.

The front face shows whichever item the drawer holds most of, counted across every slot holding it.
Choosing what a drawer displays, independently of what it is locked to, is not here.

That preview is synced on its own — one stack and a count — rather than by broadcasting the
inventory, so the payload is the same whether the drawer holds one item or fifty-four stacks, and a
packet only goes out when the face or the screen would actually look different.


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
is filtered into one that quietly accepts anything. The level arithmetic is checked here too: two
level-one locks must reach exactly as far as one level-two, because that is the sum a player does in
their head.

**Server gametests.** Persistence, hopper interop, comparator output, drop-on-break, that a broken
drawer still stacks with a fresh one, that upgrade slots and lock capacity rise with the tier and
the level, that capacity pools across duplicates, that an unchosen selection covers the first rows
and a chosen one is capped, and the lock:
that it claims the right item, refuses everything else, cannot be bypassed by a hopper, and does not
swallow what it refuses. These need a real ticking world: a plain dedicated server never block-ticks
hoppers even in force-loaded chunks, so a hopper assertion there passes whether or not the code
works.

Voiding is tested against the same bar, in pairs rather than alone: a hopper feeding a full voiding
drawer must empty itself, and the same hopper feeding a drawer *without* the upgrade must not, since
either assertion on its own would pass for a drawer that silently ate everything or one that voided
nothing. Alongside those: that a full voiding drawer accepts and the count does not grow, that a
drawer with room still stores instead of voiding, and that a drawer voiding cobblestone refuses
diamonds rather than destroying them. All of it was checked by disabling voiding and confirming the
right tests — and only those — go red.

`StackCeilingGameTests` is the odd one out: it asserts nothing about Lodestore. It pins the five
vanilla limits that rule out a stack upgrade — that a hopper ignores the container's stack ceiling,
that the transfer API honours it, that `minecraft:container` destroys an over-sized stack, that over
99 is dropped on save, and that an over-sized stack escapes a slot whole and then cannot be written
at all — so the reasoning in
[Why there is no stack upgrade](#why-there-is-no-stack-upgrade) is checked against the game on every
run rather than trusted. Each failure message says a stack upgrade may be possible again, because
that is what a red one would mean. They go through a bare `Container` with a raised ceiling rather
than through a drawer, deliberately: routed through a drawer they would be measuring Lodestore's
reaction to the rules instead of the rules.

Getting that container right is the whole measurement, and it is easy to get wrong. Overriding
`getMaxStackSize()` alone changes nothing, because the default `getMaxStackSize(ItemStack)` is
`min(getMaxStackSize(), stack.getMaxStackSize())` and the item's own limit still wins. The first run
of this measurement overrode only the first and read the result as the hopper obeying the ceiling,
when in truth nothing had been raised at all. The hopper test now asserts the ceiling really is
raised before asserting that the hopper ignored it, and it was confirmed to measure the stack
arithmetic rather than a blanket refusal by starting the slot below the item's own maximum and
watching the hopper fill it to exactly 64 and stop.

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

`voidingSurvivesASaveAndLoad` goes one step further and exercises the behaviour on the far side of
the round trip rather than only counting what came back, because an upgrade that reloads as an inert
item still looks present. It has to put the level back on the reloaded block entity first: without
one the upgrade registry is unreachable and every upgrade reads as doing nothing, so the test would
pass for the wrong reason.

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
