# Engineer building catalog

KeepItLevel is the content source, not a runtime mod dependency. Squire targets
Fabric / Minecraft 1.20.1 and uses Minecraft `StructureTemplate` for native NBT.
The pinned upstream commit is `74259e3da775f467275adb57b076caefdea84d99`.

## Resource layout

```text
data/squire/
  building_catalog/keepitlevel.json          lightweight 750-variant / 72-family index
  building_catalog/variants/keepitlevel/**   per-variant descriptors
  structures/keepitlevel/**/*.nbt            converted native structures
  blueprint_rules/keepitlevel.json           classification and vanilla replacement rules
  blueprint_rules/keepitlevel-materials.json explicit palette / computed-state bindings
  blueprint_rules/keepitlevel-qualification.json  hash-bound automated test evidence
  blueprint_rules/reports/keepitlevel/**.json.gz   per-coordinate provenance
  building_content_policy.json              retired player IDs; theme access levels
  engineer_progression.json                 ten-level cost / pacing / planning curves
  blueprints/keepitlevel_*.json              six legacy compatibility descriptors
  structurize/keepitlevel/*.blueprint.b64     six unmodified legacy source arrays
  licenses/keepitlevel-mit.txt               complete MIT license
```

The library has 49 functional Tier families (476 standard/deck blueprints), modular
roads, rails, platforms, walls/gates, decorations and fields. These become 72 player
families in nine categories, plus one internal test asset. The tavern has only
Tiers I–III; nonexistent variants are never invented. Plantation buildings and
plantation field components are separate families. Stable IDs are source-derived;
the original six keep their old IDs and descriptors.

The current bundled qualification enables **472 variants**, keeps **277** explicitly
unavailable, and excludes **one** internal test asset from player content. This is
471 native-conversion qualification passes plus the separately tested legacy
blue-glass fountain. No quarantined variant is unlocked by reaching Lv10.

The renewed fluid/scaffolding review enables 33 additional water/site variants
(29 Tier variants), but also quarantines older entries that no longer pass safe
scaffolding access review. Existing confirmed snapshots are not replaced. See
[fluid validation](../engineer-fluid-construction.md) for the current evidence.

Vanilla replacements preserve recognizable building designs, not MineColonies job
logic or every Domum Ornamentum shape. A replacement workstation is an ordinary
vanilla block. Source entities and stored inventories are never imported.

## Runtime flow

Catalog metadata → lazy `BlueprintRepository` → format importer → material bindings
→ unified rotation/translation → resolved Blueprint + site/access review → frozen
`ConstructionCostPlan` → preview, supply escrow and Engineer execution.

Catalog reload reads indexes directly under `data/<namespace>/building_catalog/`;
new packs can add indexes without Java switches. Duplicate IDs and inconsistent
family/category data are rejected. Geometry is parsed only when selected, with an
eight-entry / 131,072-expanded-cell LRU budget. Confirmed jobs own immutable geometry
snapshots; reloading resources never replaces an active project's shape or bill.

Formats: `minecraft:structure_nbt`, independent `structurize:blueprint_v1` compatibility
reader, and `squire:steps`. The importer registry is the extension point for `.schem`;
WorldEdit/Sponge schematic import is **not implemented** and is not claimed supported.
Entities and block-entity payloads are stripped; external mod blocks cannot leak into
playable geometry. Neighbour-derived connection properties are not mistaken for
authored orientation/half/slab type. Vanilla determines their final connections.

## Engineer growth

| Lv | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
|---|---|---|---|---|---|---|---|---|---|---|
| Permanent-material savings | 0% | 5% | 10% | 15% | 20% | 25% | 30% | 35% | 42.5% | 50% |
| Newly unlocked validated variants | 106 | 31 | 49 | 39 | 52 | 38 | 44 | 43 | 42 | 42 |
| Placement interval, ticks | 4 | 3.6 | 3.2 | 2.8 | 2.5 | 2.2 | 1.9 | 1.6 | 1.3 | 1 |
| Flat-ground walking multiplier | 1 | 1.05 | 1.10 | 1.15 | 1.20 | 1.25 | 1.30 | 1.35 | 1.40 | 1.45 |

Tier recommends levels 1/3/5/7/9. Size, height, complexity and category can move a
variant higher; `requiredEngineerLevel` remains the final data-driven authority.
`levelOverrides` can tune particular source files. Lv10 opens the whole **validated**
catalog and all compatible material slots; basic theme editing starts at Lv4.
Unsupported assets remain blocked even at Lv10. Movement never grants flight,
teleportation, wall traversal or increased placing reach.

Level one targets 5 placements/second at 20 TPS; level ten targets 20.
Fractional tick intervals accumulate without banking idle time. Travel, supply,
lighting and cleanup still affect whole-project duration. Excavation retains its
existing level-one batch budget and scales with efficiency (1x to 4x).
The previous 7,150/2,425-tick residence benchmark used the retired waste curve
and is not a measurement of this balance revision.

Obsolete procedural ability descriptions are hidden by `retiredAbilities` in content
policy. Their saved IDs and internal gates remain intact; the player-facing growth
summary and profession command read the actual catalog and progression data.

Permanent materials cost the original amount at Lv1 and save up to 50% at Lv10.
Savings round down cumulatively per item type: 100 planks cost 100 at Lv1 and
50 at Lv10; 101 cost 51 at Lv10. Beds/doors remain atomic, and some operations
consume zero items after their share of savings. Fluid containers are not discounted.
Confirmation freezes the rate and operations. Settled cells cannot regenerate for
free if removed. Existing confirmed snapshots retain their original bill, including
legacy waste rates. Data schema 2 uses savingBasisPoints; schema 1 remains readable.
Temporary
access and lighting remain separate, real-item budgets; only owned temporary blocks
are reclaimed. Supply escrow accepts many backpack-sized deposits without requiring
the whole building to fit in an NPC inventory.

## Compatibility and recovery

The seven old Squire-authored templates and parameterized player creations are
retired by content-policy data. Their factories, dimensions, transforms, palettes,
multi-region bindings and parsers remain available to legacy recovery and tests.
Old self-template projects stop permanent work, reclaim only their recorded temporary
blocks and refund unspent escrow. Existing permanent blocks are not demolished.
Six legacy imported projects keep their geometry; pre-cost-plan jobs migrate with
zero added waste rather than being billed a new novice surcharge.

World state uses `squire/blueprints.json` and `squire/projects.json` schema 7, plus
content-addressed gzip geometry in `squire/blueprint_snapshots/`. Migration preserves
`.pre-v7.bak` files (earlier migration backups are preserved). Escrow and mutable progress share the projects checkpoint.
Mutation intents precede placement, cleanup, deposits and refunds. Interrupted or
unwritable settlement pauses the job; it must not auto-refund or reconstruct an
uncertain transaction. Corrupt/future files become read-only, retaining original
records. Minecraft chunk/player files are separate persistence domains: a hard
process/disk failure still requires comparing the retained intent, inventories and
world before manual recovery. Do not delete a pending intent merely to resume.

## Adding / validating content

For source water, lava buckets, waterlogging and paid artificial water sites, see
[Engineer fluid construction](../engineer-fluid-construction.md). Fluid operations
use CostPlan v2; buckets are exchanged, not destroyed or duplicated. Existing
confirmed projects keep their original geometry and settlement ledger.

Missing solid terrain markers now become sampled, paid foundation work before
excavation. See [automatic ground preparation](../engineer-ground-preparation.md).

1. Verify the upstream license and add complete attribution. Do not import GPL style
   assets just because their format resembles KeepItLevel.
2. Add `.nbt` and its descriptor, then a family/variant entry in a new catalog index.
   Supply stable IDs, category, Tier, level, dimensions, source, author and license.
3. For KeepItLevel regeneration run `python tools/import_keepitlevel.py`. Source
   download is pinned and cached under `build/import-cache/`.
4. Run the M29 parsing audit and M30 assembly/physics/access qualification. Import
   its generated evidence using `python tools/import_keepitlevel.py --qualification
   build/gametest/catalog-qualification.json`. Evidence must match the NBT and rules
   hashes. Failed assets stay unavailable with an explicit reason.
5. Run real NPC end-to-end tests for new building shapes (M16/M32), palette/rotation
   tests, and full-build pacing tests (M31). M30 is a qualification fixture, **not**
   evidence that every asset was constructed by an NPC on arbitrary terrain.
6. `python tools/import_keepitlevel.py --check`; `gradlew.bat build runGametest`.
   Inspect the in-game family/Tier UI and real terrain before releasing the JAR.

The qualification version is `vanilla-assembly-physics-access-v3`: both before and
after each planned operation, the reviewed station must pass vanilla hazard and
collision checks. Fire-adjacent work stations are excluded during planning. Cached
world reads and safety decisions live only within one synchronous planning call;
they are invalidated for hypothetical placement/restoration and never reused on a
later tick. Cached exit paths are revalidated against every proposed placement;
an aggregate two-million-node search budget fails closed. Physical movement still
checks the real world.

For a different MIT pack, add a separate index such as
`data/example/building_catalog/village.json` with `schemaVersion: 1`, a `categories`
array (`id`, `name`) and `variants` records. A variant supplies `id`, `family`,
`familyName`, `displayName`, `category`, `tier`, `variant`, `deck`,
`requiredEngineerLevel`, `complexity`, `blockCount`, `width`, `height`, `depth`,
`status`, `reason`, `descriptor`, `author`, `license`, `style`, `source`,
`upstreamCommit` and `sourceSha256`. Use `UNSUPPORTED` until verified; `ADAPTED` means
tested with documented changes. The descriptor's `format`, `structure`, metadata,
material bindings and site requirements reuse the existing loader. No Java class or
switch needs to be added. Every source must still receive its own license review.

Full MIT notice and original→converted mappings ship in the JAR. No upstream code
or third-party mod runtime dependency is needed.

## Temporary scaffolding

New high-access programs use vanilla `minecraft:scaffolding`, not cobblestone.
The reviewed program includes each real scaffold item in the same preview/supply
bill. Temporary facilities are separate from permanent building waste and are
returned only after a recorded, owned block has actually been recovered.

Routes grow grounded climbing columns and supported platforms. Vanilla distance
and bottom states are calculated on placement; a span at distance 7 is rejected.
Climbing motion is confined to actual dry scaffold cells and uses world collision
resolution. There is no teleport, flight, increased placement reach or no-clip.
Horizontal descents through a cantilever's bottom lip are excluded. Surrounding
hazards and fence/wall collisions remain part of work-station checks.

Cleanup checks both the return route and the support dependencies of connected
scaffolds, including player-owned neighbours. It must not collapse a dependent
bridge just to refund a support. If the player changes a block, it is neither
removed nor refunded; unsafe/protected cleanup pauses with the ledger retained.

Committed older projects keep their original cobblestone access program. Snapshot
loading accepts both materials, and billing/refunds derive from each saved work
cell rather than the current default. New scaffolds do not silently replace old
paid supports, and recovery never exchanges cobblestone for scaffolding.
