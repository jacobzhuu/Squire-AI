# Squire blueprint library

## Architecture and audit

Engineer construction now has one server-authoritative data path:

```text
data-pack descriptor + external template file
       -> BlueprintLoader -> format-specific BlueprintImporter
       -> immutable Blueprint -> Blueprint.resolve(origin, facing, palette)
       -> Preview / bill of materials / site assessment / ConstructionPlan / executor
```

The old fixed buildings were generated in `MedievalBlueprints.java`. They now live
under `data/squire/blueprints/`; adding or replacing one does not require Java.
`HouseSpec` and `ProjectBlueprintFactory` remain only as a compatibility layer for the
existing parameterized Engineer designer.

`Blueprint.Resolved` is the shared contract. Preview samples its real `toPlace()` and
`toClear()` cells, material accounting counts the same cells, and the executor derives
its `ConstructionPlan` from them. Rotation transforms coordinates and block states via
Minecraft's native `BlockState.rotate`, so doors, stairs, rails, axes and connections
stay aligned. Confirmation pins the resolved cells and the reviewed access program in
placement schema v3. Reloading/replacing a resource updates unconfirmed previews and
the catalog, but cannot change a funded building. Schema-v2 projects remain readable;
adding an access footprint to an old project requires an explicit new confirmation.

## Staged project supply

Confirming a preview always creates the durable project. Squire atomically transfers
every currently available required item from the assigned Engineer and the player's
36 inventory slots into the project's persistent material escrow. If the complete bill
does not fit in those inventories at once, the project pauses at
`FULFIL_MATERIALS / MATERIALS_MISSING`; it does not excavate or place any blocks.

The panel action **Deposit this batch** repeats that transfer for only the remaining
bill. Every non-empty batch is saved immediately, can survive a server restart, and is
added to earlier batches. The final batch automatically resumes construction. Cancel
returns all unconsumed escrow to the holder each item came from (dropping only normal
inventory overflow), so the escrow is capacity-independent without creating items.

This deliberately does not pull from arbitrary nearby chests: player inventory and the
assigned Engineer's inventory/backpack are the only authorised sources for now.

## Directory layout

```text
src/main/resources/data/squire/
  blueprint_catalog.json              # deterministic index of bundled descriptors
  blueprints/*.json                    # metadata + steps or external-file reference
  structures/*.nbt                     # vanilla Structure Block exports
  structurize/**/*.blueprint           # raw Structurize v1 gzip NBT (data packs)
  structurize/**/*.blueprint.b64       # Base64 wrapper used by bundled text assets
  material_families/*.json             # optional material-palette families
  licenses/*                            # redistributed-asset notices
```

External packs use normal Minecraft data-pack paths. Descriptors are discovered
recursively below `data/<namespace>/blueprints/` during server startup/reload. An
explicit `id` is recommended. A descriptor with the same id replaces the bundled one
without changing Java or invalidating saved placements.

## Common descriptor metadata

Every importer consumes the same outer descriptor:

```json
{
  "schemaVersion": 1,
  "id": "example:gatehouse",
  "format": "squire:steps",
  "displayName": "Gatehouse",
  "tier": 2,
  "category": "DEFENCE",
  "minEngineerLevel": 4,
  "requiredAbilities": ["build.blueprint"],
  "metadata": {
    "author": "Builder name",
    "source": "https://example.invalid/project",
    "license": "CC-BY-4.0",
    "style": "medieval",
    "description": "Short catalog description",
    "tags": ["gate", "stone"]
  }
}
```

Categories are `HOUSING`, `STORAGE`, `PRODUCTION`, `CIVIC`, `DEFENCE`,
`INFRASTRUCTURE` and `DECORATION`. Legacy `SHELTER` and `MINE` values load as
`HOUSING` and `PRODUCTION`. `EngineerBuildPolicy` computes the actual minimum from
`minEngineerLevel`, the configured footprint limit, and any parametric capabilities.
Both catalog locks and confirmation use this policy. Metadata level 0 preserves only
legacy basic-training blueprints; it does not bypass positive catalog requirements.

## High construction and recovery

Tall projects plan real cobblestone steps/bridges and return routes before confirmation.
Blue particles show the temporary blocks and the work boundary (at most six blocks
outside the building horizontally). Planning never changes world blocks. There is no
flight, teleport, or increased placement reach: the executor navigates normally and
checks actual eye-to-block reach, collision, air, and protection again before each write.
If a route cannot be planned, confirmation reports its position instead of accepting
a project that is already known to be inaccessible.

Temporary cobblestone is included in the same project bill and can be deposited in
batches. The persistent access ledger records paid blocks and their inventory source.
Completion/cancellation removes owned cobblestone top-down and returns the real items.
Changed blocks are preserved without a refund. If access or protection changes, the
project pauses with its remaining cleanup ledger; it does not erase the ledger or
remove someone else's replacement. Save/recovery retains both geometry and the cursor.

Doors/beds are placed as complete two-cell assemblies for one item; double slabs cost
two slab items. Fixtures follow structural supports. Lighting waits for the vanilla
light engine to settle before reporting an obstruction.

## Unified Engineer catalog and imported palettes

Fixed resources and parametric designs share category and unlocked-only filters. The
server sends a typed catalog with stable IDs and a revision; stale clicks cannot select
a different building after reload. Fixed buildings keep their authored dimensions;
only the parametric entries expose the size/floor/module designer. Rotation retains its
existing level gate, and regional material changes retain the Lv4 gate (legacy single
material slots remain compatible). Confirmed geometry and admission level are frozen.

The six KeepItLevel descriptors declare material slots and explicit `materialBindings`:

```json
"materialBindings": [{
  "block": "minecraft:spruce_stairs", "slot": "roof", "variant": "stairs",
  "from": [0,5,0], "to": [255,255,255]
}]
```

Bounds are inclusive, in cropped local coordinates, before rotation. Omit them to bind
all matching blocks. A binding preserves orientation/half/slab state and may only use a
declared slot variant. Furniture below the bound is not recolored as roofing. Adding
a building normally requires only its resource, descriptor, attribution, and (for a
bundled resource) an entry in `blueprint_catalog.json`; data packs are auto-discovered.

## Native steps format

`squire:steps` is compact for hand-authored or generated buildings:

```json
{
  "schemaVersion": 1,
  "id": "example:gatehouse",
  "format": "squire:steps",
  "displayName": "Gatehouse",
  "category": "DEFENCE",
  "minEngineerLevel": 4,
  "size": [9, 8, 7],
  "materialSlots": [{
    "id": "wall", "displayName": "Wall", "type": "WALL",
    "defaultFamily": "squire:stone_bricks", "requiredVariants": ["block"]
  }],
  "steps": [
    {"material":{"slot":"wall","variant":"block"},
     "from":[0,0,0],"to":[8,6,6],"what":"shell"},
    {"dig":true,"from":[1,1,1],"to":[7,5,5],"what":"interior"}
  ]
}
```

Later steps win when boxes overlap. `optional` details may be skipped when materials
are absent. Fixed `block` steps and semantic `material` steps may be mixed. Material
families enumerate their variants, preventing an invalid stairs/slab/window palette.

## Vanilla Structure Template `.nbt`

Put a Minecraft 1.20.1 Structure Block export at
`data/<namespace>/structures/<path>.nbt`, then reference it:

```json
{
  "schemaVersion": 1,
  "id": "example:gatehouse",
  "format": "minecraft:structure_nbt",
  "structure": "example:gatehouse",
  "displayName": "Gatehouse",
  "category": "DEFENCE",
  "minEngineerLevel": 4,
  "airMode": "clear",
  "blockEntityPolicy": "strip",
  "entityPolicy": "strip",
  "metadata": {"author":"Builder","license":"CC0-1.0","style":"medieval"},
  "palette": {
    "minecraft:stone_bricks": {"slot":"wall","variant":"block"}
  }
}
```

Squire limits decompressed NBT to 64 MiB and delegates palette/state decoding to
Minecraft 1.20.1's `StructureTemplate.readNbt`, then immediately converts it to the
common Blueprint. `airMode` defaults to `ignore`; use `clear` only for intentional
negative space. Structure void, structure blocks and jigsaws are ignored.

Entities and block-entity payload NBT are rejected by default. Setting the relevant
policy to `strip` imports only the block-state shell; inventories, commands and entity
data are never applied by an Engineer.

## Structurize v1 `.blueprint`

`structurize:blueprint_v1` imports the mature gzip-NBT block/palette layout without a
Structurize or MineColonies runtime dependency:

```json
{
  "schemaVersion": 1,
  "format": "structurize:blueprint_v1",
  "blueprint": "example:medieval/warehouse1",
  "encoding": "gzip",
  "crop": "content",
  "airMode": "clear",
  "entityPolicy": "strip",
  "blockEntityPolicy": "strip",
  "ignoredBlocks": [
    "structurize:blocksubstitution",
    "structurize:blocksolidsubstitution"
  ],
  "blockMap": {
    "minecolonies:blockminecoloniesrack": {
      "block": "minecraft:barrel",
      "preserveProperties": ["facing"]
    }
  }
}
```

With `encoding: gzip`, Squire reads
`data/example/structurize/medieval/warehouse1.blueprint`. The bundled samples use
`base64-gzip` and `.blueprint.b64` only so the original binary bytes can be stored as
text resources; Base64 is packaging, not another building format. `blockMap` removes
optional mod dependencies while retaining explicitly compatible state properties.
Imported entities/NBT never reach construction.

## Bundled progression and classification

| Engineer level | Category | Buildings unlocked at this level |
|---:|---|---|
| 1 | Housing | 木构乡野住宅、石木山墙住宅 |
| 2 | Housing / Storage / Decoration | 云杉民居、梁架仓储屋、村庄小喷泉 |
| 3 | Production | 边境铁匠铺 |
| 4 | Production / Defence | 加固矿井前哨站、石基瞭望塔 |
| 5 | Production / Civic | 工匠小屋、双层路边旅店 |
| 6 | Defence | 边境守望塔 |
| 7 | Civic | 学者图书馆 |
| 8 | Storage | 大型云杉仓库 |

The catalog UI is generated from the server registry, grouped by category, and shows
locked cards, required level and resolved dimensions. Clicking a card still passes
through the same server-side level check, so a forged button packet cannot bypass it.

The table lists descriptor minimums, not an exemption from size limits. The shared
`EngineerBuildPolicy` also applies configured footprint limits (default maximum side
lengths for Lv1–10: 9, 13, 13, 17, 21, 21, 21, 32, 32, 48) and parametric capabilities.
Rotation unlocks at Lv3 and multi-region palettes at Lv4. Fixed buildings keep their
authored dimensions/floors: the UI offers supported palettes and rotation, never a
parametric resize operation. Imported and parametric entries share one typed catalog,
category/unlocked filters and version-checked stable-ID selection packet. A stale
catalog must refresh before selection; authority remains with the bound Engineer.

## Reviewed high construction and recovery

Tall blueprints (height at least six blocks) receive a bounded walk/build program
before confirmation. Temporary cobblestone stairs/bridges are shown in blue, within
at most six horizontal blocks of the building bounds. They are included in the
same material bill as the permanent cells. No planning operation edits the world.
The worker walks and jumps normally between full-floor stations; placement is still
limited to 4.75 blocks from the actual eyes. Unplannable or protected sites are
refused rather than gaining flight, teleportation or remote placement.

Confirmation pins the resolved geometry, palette, transform, required level and
access program. Preview, escrow accounting, execution and final verification use
this snapshot even after a data-pack reload or a save/reload. Material deposits can
be split across multiple backpack loads. Doors/beds are installed as one paired
operation; upper door halves and bed heads cost zero extra items, double slabs cost
two, and wall torches use torch items without losing their authored block state.

The persisted temporary ledger records only blocks actually paid for and placed by
this project. Cleanup selects a reachable station and preserves the return route
and access to other outstanding supports before each removal. Cobblestone is
credited back to its original material source. Positions that no longer contain the
recorded cobblestone are left alone and are not refunded. Cancellation with outstanding supports runs this
same cleanup before completing; a damaged/protected route pauses with progress and
supplies preserved. Cleanup is deliberately slower than instantaneous structure
placement, and its walking time is included in the task budget.

Old projects without a pinned access snapshot do not automatically expand their
footprint: the first manual resume shows the added blue area/materials and the next
resume confirms it. Optional decor is never relied on as a required walking floor.

## Palette bindings for imported content

`materialBindings` assigns exact imported blocks to declared slot/variant pairs,
optionally within an inclusive local `from`/`to` region, after cropping and before
rotation. State properties survive replacement (stair facing/half, slab type, log
axis). The six bundled MIT examples use descriptor-only adaptations; for example,
the library's roof region starts at local Y11 so interior stairs stay untouched.
Their dirt paths become coarse dirt to prevent overhead construction supports from
changing them into ordinary dirt. Original upstream binary payloads remain unchanged.

## Verification

Run `./gradlew build runGametest` for all unit tests and Minecraft server GameTests.
For iteration, `-PsquireGameTestClass=M16ProjectGameTests` selects the construction
class only; omit it for final verification. Tests fund and construct all six imported
assets in a real server world, compare required blocks against the preview snapshot,
and require an empty temporary ledger. Other coverage includes tower access,
cancellation/recovery/player edits, stale catalog selection and long material bills.

## Format extension points

`BlueprintImporter` is the only format boundary. A future Sponge/WorldEdit importer
can register `worldedit:schem`, decode its palette and VarInt block array, and return
the same Blueprint without touching preview, material accounting, UI or construction.
Sponge Schematic v3 is the preferred future `.schem` target; legacy `.schematic` is
not planned. Squire intentionally does not depend on WorldEdit, Structurize or
MineColonies.

## Research and redistribution

- Structurize's loader/blueprint separation and MineColonies' hierarchical style packs
  informed the design. Their GPL-3.0 code/building repositories were not copied or
  linked.
- Six KeepItLevel buildings are redistributed from pinned commit
  `74259e3da775f467275adb57b076caefdea84d99`. KeepItLevel is MIT licensed by
  Richard Gowen (`@alt_bier`). Exact attribution and license text are in
  `THIRD_PARTY_NOTICES.md` and `data/squire/licenses/keepitlevel-mit.txt`.
- The source `.blueprint` byte streams are retained unchanged inside the Base64
  wrapper. Descriptor-side adaptations replace MineColonies work blocks with vanilla
  blocks, strip entities/block entities and replace fountain water with blue glass
  because the survival executor consumes placeable block items.
- The seven original Squire descriptors retain Apache-2.0 metadata.
- Assets whose license does not permit redistribution remain reference-only. Every
  added third-party asset must record author, canonical source, pinned revision,
  license and any required notice before it enters the bundled catalog.

References:

- Structurize: <https://github.com/ldtteam/Structurize>
- MineColonies schematic styles: <https://github.com/ldtteam/minecolonies-schematics>
- KeepItLevel and MIT license: <https://github.com/gowenrw/keepitlevel_mc_style>
- Yarn 1.20.1 `StructureTemplate`: <https://maven.fabricmc.net/docs/yarn-1.20.1%2Bbuild.1/net/minecraft/structure/StructureTemplate.html>
- Sponge Schematic specification: <https://github.com/SpongePowered/Schematic-Specification>
- WorldEdit schematic documentation: <https://worldedit.enginehub.org/en/latest/usage/clipboard/>

## Adding a building without Java changes

1. Add one descriptor below `data/<namespace>/blueprints/`.
2. Use inline `squire:steps`, a vanilla 1.20.1 `.nbt`, or a Structurize v1
   `.blueprint`; reference the external file from the descriptor.
3. Set category, minimum Engineer level and complete author/source/license/style/tags
   metadata. Add a notice when the license requires one.
4. Declare palette slots/variants or `blockMap` substitutions for every non-vanilla
   dependency. Never import arbitrary entity or block-entity NBT.
5. Reload the server/data packs and check the load summary for skipped descriptors.
6. Verify all four rotations, Preview, material counts and one survival build before
   distributing the pack.

External data-pack buildings need no entry in Squire's bundled catalog and no Java
change: descriptor discovery automatically places them in the server-authored UI.
