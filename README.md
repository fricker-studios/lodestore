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

The tag is the single source of truth for the version. A release passes it through as `-Pversion`,
so the jar filename and the version inside `fabric.mod.json` cannot drift apart; every other build
derives it from the nearest tag with `git describe`, so a test jar three commits past `v0.2.0-rc.1`
is `lodestore-0.2.0-rc.1-3-g<sha>.jar`. There is deliberately no fallback number in
`gradle.properties`: there used to be, it stayed at `0.1.0` after the first release candidate was
tagged, and the test builds quietly shipped under it.

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
| Sturdy | shulker box | 27 | 2 | contents stay inside | empty drawers |
| Reinforced | shulker box | 54 | 3 | contents stay inside | empty drawers, and sturdy ones with contents |

Drawers are wood, so an axe is the efficient tool, as it is for a chest or barrel. They still break
without one, just slowly — no tool is required for them to drop.

Right-click opens Lodestore's own screen — the vanilla chest grid plus a column of upgrade slots;
see [The screen](#the-screen) for why the vanilla `ChestMenu` it used to hand back was given up.

The block entity is deliberately **not** a vanilla `Container`, and no longer extends
`BaseContainerBlockEntity`. It used to be, and that was what made hoppers, droppers, pipes and the
comparator work for free; it was given up so that a slot's capacity could be the drawer's to decide
rather than vanilla's. Hoppers, droppers, crafters and pipes now reach a drawer only through Fabric's
`Storage<ItemVariant>`. The whole of that trade is in [The storage layer](#the-storage-layer), and
the measurements that forced it are in [Why the storage layer exists](#why-the-storage-layer-exists).

"Keeps contents" is loot-table driven: those tiers copy the `minecraft:container` component onto the
dropped item, exactly as a shulker box does, and the drawer skips spilling for them so contents
cannot be duplicated by doing both. Alongside it rides `lodestore:drawer_overflow`, the counts parked
behind full stacks, since `minecraft:container` cannot carry more than a legal stack per slot.

The container copy is unconditional — the drawer writes the component whether or not it held
anything — so the drawer **item** declares an empty `minecraft:container` of its own. Without it, a
sturdy or reinforced drawer that had been placed once came back carrying a component a never-placed
one did not, and the two would not stack: two piles of identical drawers in the inventory, for no
visible reason. Declaring it makes the copied value equal the item's prototype, which
`PatchedDataComponentMap` drops rather than records. Vanilla gives shulker boxes the same component
for the same reason, and the overflow component gets the same default. A gametest per tier breaks a
drawer that was never filled and checks the drop against a fresh item.

Nesting is blocked in `DrawerBlockEntity#canStore`, which both doors into a drawer ask — the
storage's insert, which hoppers and pipes use, and the menu's slot, which the player does. A rule
enforced only in the UI would be bypassed by piping one in; one enforced only on the storage, by a
hand. The rule is about contents: a drawer that keeps its contents refuses a drawer *with contents*
of equal or higher rank, because that is what would let storage compound without limit. An empty
drawer compounds nothing — it is a block item like any other — and every tier takes it. Contents
means either component: stacks in `minecraft:container`, or counts parked in `drawer_overflow`.

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

Hoppers, pipes and the transfer API are untouched by this: they talk to the drawer's storage, not
to the menu.

The storage slots hang off `DrawerContents`, the `Container` the block entity owns, and every one of
them shows exactly one legal stack however much is in the slot behind it. The slot class is the
drawer's own, `DrawerSlot`, because `Slot#getMaxStackSize(ItemStack)` is the one capacity hook in
vanilla that knows which slot it is for: it reports the room left in the slot's *total*, so a click
or a shift-click into a stack-upgraded slot reaches the space behind the visible stack, and
`DrawerContents` parks whatever a legal stack cannot show. Taking out goes one legal stack at a time,
and the slot refills. On a slot a stack upgrade covers, the tooltip says how much is really there.

### Upgrades

Upgrades are datapack-driven, the same way tiers are. An entry at
`data/<pack>/lodestore/drawer_upgrade/<name>.json` is a set of behaviour flags plus a
`slot_capacity` (how many slots a lock reaches) and two depths, `stacks_per_slot` and
`unstackable_per_slot` (how much a slot holds with a stack upgrade; one unless written), and an item
carries the key of the entry it installs. A pack adds an upgrade by
pairing a new item with a new entry; it never names an implementation, so the mod ships no branch
per upgrade.

| Upgrade | Does | Covers |
|---|---|---|
| Lock | a covered slot takes one kind of item and nothing else | 9 slots |
| Lock II | the same, further | 18 slots |
| Lock III | the same, further again | 27 slots |
| Void | the drawer discards what it has no room for | the whole drawer |
| Stack | a slot holds 4 stacks; an item that does not stack, 8 | the whole drawer |
| Stack II | 8 stacks, or 16 of an unstackable | the whole drawer |
| Stack III | 16 stacks, or 32 of an unstackable | the whole drawer |

The lock and the void change *what the drawer accepts*; the stack upgrade changes *how much it
holds*, and it is the one upgrade that could not be another flag enforced through a vanilla hook,
because vanilla has no per-slot capacity hook. It needed [the storage layer](#the-storage-layer),
and [Why the storage layer exists](#why-the-storage-layer-exists) is the evidence for that.

Only the lock is aimed at particular slots. Voiding and stacking are properties of the drawer: a
drawer either discards its overflow or it does not, and either holds deep stacks or it does not, and
a version where a player chose which rows did either turned out to be a thing to configure for no
reason to want to.

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

The storage also refuses upgrades as goods, through both of its doors, and the upgrade slots are
marked *fake* — a vanilla flag that changes only how the screen draws the item, and the first thing
Inventory Profiles Next checks when it decides which slots make up a container's sortable area,
read from its decompiled `AreaTypes`. The two together are what keep a sorter out of the column.
Before them, a sort treated the column as more slots of the same chest, lifted the upgrade out,
found nowhere in the grid that would take it, and left it on the cursor. A shift-clicked upgrade
still goes to the column, as it always did. Should an IPN version stop honouring the flag, its
`config/inventoryprofilesnext/SlotIntegrationHints.json` takes
`{"dev.fricker.lodestore.menu.UpgradeSlot": {"ignore": true}}` and does the same by name.

#### Choosing which slots a lock covers

Right-click an installed lock to start choosing. Covered slots are tinted the lock's gold, and the
lock being edited outlines its own. Click a storage slot to add or remove it; shift-click to act on
everything between the last slot clicked and this one. The lock's tooltip says so, because the
gesture is not one anything else in the game teaches.

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

It is enforced in `DrawerBlockEntity#canStore`, next to the nesting rule and for the same reason: a
filter applied only by the screen would be bypassed by piping items in. Both doors into a drawer ask
it — the storage's insert, which is what hoppers, droppers and pipes reach, and the menu's
`DrawerSlot`, which is what a hand reaches — so one rule covers all of them.

Scoping cuts both ways, and that is deliberate: past an upgrade's reach a hopper may still fill the
drawer with anything. A level-one lock on a 27-slot drawer locks nine slots, not twenty-seven.

The front face draws a locked drawer's claims, cycling through them when there are several, and
tints its count in the lock's gold.

#### Voiding

The drawer discards what it has no room for, rather than backing the system up — but only an item
it already holds somewhere, or a lock has claimed. A misrouted item is refused and backs the system
up, which is recoverable; destroying it would not be.

Voiding used to require the lock flag, when the filter was the only thing standing between it and
destroying whatever arrived. Being limited to what the drawer holds or has claimed is what makes it
safe now, so the shipped void upgrade does not lock. It briefly had a reach of its own, like the
lock's; it covers the whole drawer again, because a drawer that voided some rows and not others was
a thing to configure with no reason to want to.

The mechanism is the storage's own `insert`: it fills what has room, and if anything is left over
and a voided slot has claimed or holds that item, it reports the whole amount accepted and changes
nothing. That is the destruction. It is the last resort, not the first — a drawer with room stores.

It used to be a fake slot. While a drawer was a vanilla `Container`, `canPlaceItem` could only
answer yes or no, and `HopperBlockEntity.ejectItems` gave up at `isFullContainer` before asking any
slot anything, so voiding needed an overflow slot past the end of the storage that always read empty
and discarded what was written to it — with the container one slot larger than the storage and the
comparator having to know the difference. Fabric's hopper patch never asks a storage whether it is
full, so none of that is needed now: the slot, the size mismatch and the comparator caveat are gone.
Inserting into one slot's view directly never voids, because that caller asked a specific slot.

#### Stacking

Every slot holds several stacks, and the drawer gathers the same item into as few of them as it can.

Three levels, and two numbers on each, because items come in two classes. An item that stacks holds
its stack size times `stacks_per_slot` — 256, 512 and 1,024 of a 64-stack, and a 16-stack scales the
same way to 64, 128 and 256. An item that does not stack holds `unstackable_per_slot`: 8, 16 and
32. The second number exists because four times one is a poor upgrade. Neither pools: the deepest
installed level decides, so a level one beside a level three is a level three.

Stacks stay where they are put, and are gathered on request: a stack upgrade going in gathers the
same item into as few slots as the capacity allows — the one moment a player has just asked for
deeper slots and expects the drawer to use them — and right-clicking the installed upgrade does it
again, which its hover text says. A version of this gathered after every change instead, and it
followed a stack a player had just moved with the rest of its slot, which is not what moving a
stack means. Gathering only ever fills slots already holding the item, so an empty locked slot is
never filled by it, and totals are exact. Otherwise the drawer never moves anything of its own
accord except what a slot holds past its capacity: when the stack upgrade is taken out — or a pack lowers a level, or a broken
drawer is placed without its upgrade — the parked items spread back out into legal stacks of their
own, in empty slots, so the drawer looks as it would have without the upgrade. An upgrade the
contents still need cannot be taken out: the slot refuses the pickup, on both sides, and its
tooltip says why. Whatever a pack change leaves with nowhere to go stays parked, over capacity,
where it reports no room and drains normally. Nothing in a drawer ever lowers a count except a
withdrawal.

Hoppers and pipes top up a slot already holding an item before they open a new one, so a line
into a stacked drawer fills it deep rather than wide. A sorter that honours
`Slot#getMaxStackSize` merges to the slot's full capacity too.

What a player sees: the slot draws its real total — 256, not the 64 a stack would say — shrunk when
four digits would not fit, and the tooltip says that total out of what the slot could hold. Clicking
takes one legal stack and the slot refills; shift-click keeps taking for as long as the slot holds
the same item, which is vanilla's own loop and exactly the right one. Placing or shift-clicking in
reaches the space behind the stack. Swapping a different item onto a slot with stacks parked behind
it is refused, because the parked count is a count of *that* item. The front face already counted
across slots and now counts what is parked too. The comparator measures each slot against its own
capacity, so a hopper line into a stacked drawer does not read done until it is.

#### Why the storage layer exists

A stack upgrade — covered slots holding more than a normal stack — was first designed against
vanilla's `Container`, measured, and abandoned there. The reasons are in Minecraft rather than in
Lodestore, and they are kept as assertions in `StackCeilingGameTests` rather than only as this
section, so that a version which lifts one of them turns a test red instead of leaving a paragraph
quietly wrong.

What the measurement settled is not only that a stack upgrade could not be a flag in the upgrade
registry, but what it would take instead: Lodestore's own storage layer, which is what was then
built. This section is the evidence; [The storage layer](#the-storage-layer) is the answer. Read
this before touching drawer capacity for any reason.

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

#### The decision: Lodestore gets its own storage layer

Doing it properly meant one of two things, both larger than an upgrade. **The decision was the
first:** drawers get a real `Storage<ItemVariant>` of their own and stop being a plain
`Container`. That is what shipped; see [The storage layer](#the-storage-layer).

The rejected alternative was mixing into `HopperBlockEntity` and into `ItemStack`'s validation.
Patching the hopper is merely intrusive; patching the validation is not acceptable, because
`validateStrict` and `validateContainedItemSizes` are shared with bundles, crossbows and every
other container component. Widening them to let a drawer over-stack would widen them for a bundle
too, and the blast radius of being wrong there is every stack in the game.

So the cost of the decision was accepted deliberately, and it is a real cost. Being a vanilla
`Container` was what made the interop free: hoppers, droppers, pipes, the comparator and the
`minecraft:container` component all worked with no code at all, and `BaseContainerBlockEntity` gave
us the save format and the shulker-style drop for nothing. Giving that up meant owning all of it,
and the list of what is now owned — with what each piece risks — is in the next section. In
exchange, capacity stopped being vanilla's to dictate, and this is the only route on which a
*per-slot* capacity can even be expressed — which the table above shows `Container` cannot do at
any size.

Two things shaped the design, because both are reasons the answer could not simply be "use the
transfer API":

- **99 is a serialization ceiling, not a `Container` one.** `ItemStack.MAP_CODEC`'s count is
  `intRange(1, 99)` and `Item.ABSOLUTE_MAX_STACK_SIZE` is 99. No `Storage` implementation changes
  that, so over-stacked contents cannot be persisted as `ItemStack`s at all, however they are
  exposed at runtime. A storage layer that holds more than 99 of something has to store a count
  separately from the stack and reconstitute legal stacks on the way out.
- **The hopper reaches a `Container` before it reaches a `Storage`.** Fabric's
  `HopperBlockEntityMixin` takes the transfer-API route only `if (container == null)`. So a drawer
  cannot be both: offering a `Container` for compatibility *and* a `Storage` for capacity means the
  hopper silently keeps using the `Container` and the two disagree again. Whatever a drawer still
  exposes as a `Container` has to be a view that cannot lie about fullness.

Both are answered by the same choice of model, below. The five assertions in `StackCeilingGameTests`
stay as they were: they are now the reasons the layer is shaped the way it is, and a drawer never
constructs the over-sized stack they show cannot survive.

### The storage layer

Each storage slot is **a legal stack plus a parked remainder**. The stack — `visible` — is never
larger than the item's own maximum and is the only `ItemStack` object that exists for the slot: the
menu's slots are it, the save file writes it, and `minecraft:container` carries it. The remainder is
how many more of the same item are parked behind it, as a plain count. Together they are the slot's
total; the slot's capacity is the item's own stack size times the deepest stack upgrade's multiplier,
or that upgrade's own count for an item that does not stack, and simply the stack size without one.

The invariants, restored by every mutation before it returns: a remainder above zero means the
visible stack is full; an empty visible stack means the remainder is zero; the total never exceeds
the capacity except when the capacity has been lowered under it, in which case the slot reports no
room and drains. Rebalancing is done **in place** — vanilla's menu code holds a slot's stack across
a call and mutates it directly, so the object has to stay the one it was handed, and a stack drained
to zero still names its item, which is what makes refilling from the remainder possible.

Why this shape and not a variant with a count: an over-sized stack is never constructed anywhere. The
99 ceiling, the component's validation and the one-click escape are each a way to lose items, and
none of them can be reached from a model that only ever hands out legal stacks. It also leaves the
save format as today's `Items` plus a side table, so a world written before this layer existed loads
with nothing to convert.

**What a drawer exposes to each consumer**, and why none of them can disagree about how full a slot
is — there is one `slotCapacity` function on the block entity, and everything below either calls it
or reads the same two arrays:

| Consumer | Sees | Through |
|---|---|---|
| Hoppers, droppers, crafters, hopper minecarts | the `Storage<ItemVariant>` | `SidedStorageBlockEntity`; vanilla finds no `Container` at the block entity, so Fabric's patches take the transfer-API route |
| Pipes, other mods | the same `Storage` object | `ItemStorage.SIDED` |
| The comparator | Σ total / capacity over the slots | `comparatorSignal()` |
| The screen | `DrawerContents` as a `Container`, one legal stack per slot drawn with the slot's real total; `DrawerSlot` reports the room in the total and refuses a swap over a parked remainder | the menu |
| The dropped item | `minecraft:container` with the visible stacks, `lodestore:drawer_overflow` with the counts | `collectImplicitComponents`, copied by the loot table |
| `PersistenceSelfCheck` | the contents, remainders included | `ContentsHolder` |

`DrawerContents` is the `Container` face and owns the arrays; `DrawerStorage` is the transfer-API
face over the same arrays (one object cannot be both — `Container` and `Storage` are both
`Iterable` of different things). Neither is the block entity, and a gametest asserts the block
entity is not a `Container` and that `HopperBlockEntity.getContainerAt` finds nothing at a drawer,
because a future refactor that made it one again — by delegation, say — would hand every hopper back
to vanilla's arithmetic in complete silence.

**Save format.** `Items`, exactly as before, plus `overflow` — the remainders as a sparse list of
`{slot, count}` — written only when any is non-zero. The same codec is the dropped item's component.
Migration is free: an old world has no `overflow` and loads identically, and a gametest writes the
old shape by hand and proves it. The custom name and the lock keep vanilla's keys; the block entity
now writes them itself.

**Breaking an over-stacked drawer.** For the tiers that keep their contents, the visible stacks ride
in `minecraft:container` as always (all legal, so nothing is validated away) and the remainders in
`lodestore:drawer_overflow`. The overflow is deliberately *counts only*: the item each count refers
to is the stack in the same slot of `minecraft:container`, so the contents are never described
twice, and two descriptions cannot disagree about how many of something exist. A count whose slot
arrives empty is dropped — the worst an inconsistent pair can do is lose what the editor already
lost, never duplicate. Placing the drop back restores the total even if the stack upgrade dropped
separately; the slot is simply over capacity. If something strips the component — another mod, hand
editing — the stacks survive and the remainders do not, and the item's tooltip says how much is
parked so an undercount is never silent. The basic tier spills everything as repeated legal stacks,
which the drawer now does itself: vanilla's default spill fires only for a `Container`.

**What was free and is now owned.** Each with what it risks and how that is checked:

| Was free | Now | Risk, and the test |
|---|---|---|
| Hopper, dropper and crafter interop | `SidedStorageBlockEntity` and `DrawerStorage` | jam or over-fill if room is wrong — gametests both directions, dropper, minecart, each with its control |
| `minecraft:container` on break | ours, plus the overflow component | silent loss — paired per-tier tests, with a remainder |
| Spilling on break | ours | silent loss — paired tests |
| `canPlaceItem` as the one choke point for lock and nesting | `canStore`, asked by the storage's insert and by `DrawerSlot#mayPlace` | a rule in one door only — tests through both |
| The custom name and `minecraft:lock` | copied from `BaseContainerBlockEntity` | a renamed drawer forgetting its name — the migration test |
| `PersistenceSelfCheck` finding drawers at all | a `ContentsHolder` branch | silent coverage loss — the self-check gametest asserts it covers drawers |
| Transaction snapshots | per-slot `SnapshotParticipant`s, plus one that calls `setChanged` once per outer commit | a rollback leaking — a gametest aborts an insert |
| `/item … block` and `/loot replace block` | **lost**; both hard-require `instanceof Container` | map-makers lose two commands on drawers; no mitigation |

### Not yet

Grouping and sorting.

A *more slots* upgrade. Container size is structural, decided before the level's registries are
reachable, so it cannot just be another flag in the registry the way lock levels and stack depth
are. The storage layer answers how much a slot holds, not how many slots there are.

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

The storage layer is tested in `StorageLayerGameTests`, in pairs: a hopper fills a stacked slot past
a stack, and the same slot without the upgrade takes nothing more while the hopper moves on; a
dropper inserts, and keeps its item at a full drawer rather than throwing it; a hopper minecart
extracts; a click takes one legal stack and the slot refills, with the cursor's stack checked
against the item codec; placing onto a stacked slot parks the excess, and onto a plain full one is
refused; a swap onto a parked remainder is refused, and the same swap on a slot with nothing parked
goes through; shift-click in fills through the parked space, and out drains a slot stack by stack;
the menu's slots enforce the lock and nesting, and let an empty drawer in; a basic drawer spills its parked items as legal
stacks, and a sturdy one keeps them in both components and gives them back when placed; removing
the upgrade gathers the stacks when it goes in by either door and a lock does not, and again when
right-clicked while a plain drawer ignores the button; it spreads what was parked into legal stacks
when taken out, cannot be taken out while the contents need it, and goes back in; a moved stack
stays where it is put; an aborted insert leaves no trace; the
comparator measures each slot against its own capacity; the three levels give the right numbers to
a block, a pearl and a sword, and the deepest decides; upgrades are refused as goods through both
doors. Migration gets a pair of its own: a tag written in the old shape by hand
loads unchanged, and the same tag with a remainder table restores the count. And one assertion
guards the premise — the block entity is not a `Container`, and a hopper finds nothing at it.

`StackCeilingGameTests` is the odd one out: it asserts nothing about Lodestore. It pins the five
vanilla limits that shaped the storage layer — that a hopper ignores the container's stack ceiling,
that the transfer API honours it, that `minecraft:container` destroys an over-sized stack, that over
99 is dropped on save, and that an over-sized stack escapes a slot whole and then cannot be written
at all — so the reasoning in
[Why the storage layer exists](#why-the-storage-layer-exists) is checked against the game on every
run rather than trusted. Each failure message says the layer's premise has shifted, because that is
what a red one would mean. They go through a bare `Container` with a raised ceiling rather than
through a drawer, deliberately: routed through a drawer they would be measuring Lodestore's reaction
to the rules instead of the rules.

Getting that container right is the whole measurement, and it is easy to get wrong. Overriding
`getMaxStackSize()` alone changes nothing, because the default `getMaxStackSize(ItemStack)` is
`min(getMaxStackSize(), stack.getMaxStackSize())` and the item's own limit still wins. The first run
of this measurement overrode only the first and read the result as the hopper obeying the ceiling,
when in truth nothing had been raised at all. The hopper test now asserts the ceiling really is
raised before asserting that the hopper ignored it, and it was confirmed to measure the stack
arithmetic rather than a blanket refusal by starting the slot below the item's own maximum and
watching the hopper fill it to exactly 64 and stop.

**Client gametests.** Two of them. One builds a row of drawers — one of each tier, plus a locked
drawer with stock and a locked drawer that has been emptied — and screenshots it into
`build/clientGametest/screenshots/`: the row, a close-up of the tier sprites, a close-up of the two
lock states, the open screen, the upgrade hint, and a stacked slot drawing its real total with the
tooltip saying out of how much. Icon placement, orientation, which sprite
ended up on which face and "renders at all" are failure modes no assertion catches. The other quits to the title screen and
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

It finds two kinds of thing: anything that is still a vanilla `Container`, and anything holding
Lodestore's own `DrawerContents` through `ContentsHolder`. The second branch exists because a
drawer is deliberately no longer a `Container`, and the check used to find containers by
`instanceof` — it would have skipped every drawer in complete silence, which is the exact failure
it is for. The gametest that runs it asserts it covers drawers, so that cannot happen again quietly.
For contents it also parks a remainder behind each stack, because the remainder is saved apart from
the stack and could go missing on its own.

State that lives outside the contents is invisible to it — a drawer's upgrades and its lock filter
are exactly that, and would be lost on reload just as silently. They have their own save-and-load
round trip in `upgradeStateSurvivesASaveAndLoad` instead. Anything else added outside the contents
needs the same treatment, or it is not covered at all.

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
