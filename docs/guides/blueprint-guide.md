# Blueprint Guide

> Current player content and level rules are documented in the
> [Engineer Building Catalog](engineer-building-catalog.md). The older procedural
> examples below describe retained backend/legacy capabilities, not enabled player templates.

Your squire can build — but only from a blueprint, and only with materials that are
really in its backpack. Nothing is conjured. If you take the planks away halfway
through, it stops and tells you what it is short of.

## The loop

```
/squire blueprint list                    # what can be built
/squire blueprint place watchtower        # a particle outline appears in front of you
/squire blueprint                         # the bill of materials, any time
/squire blueprint fulfil                  # fulfil what is missing into its backpack
/squire blueprint build                   # it walks over and lays the blocks
/squire blueprint cancel                  # drop the site (blocks already laid stay)
/squire undo                              # put everything back
```

Saying **"帮我盖一个房子"** or **"盖一间石头房子"** in chat does the `place` step for
you with the wood or stone shelter.

One site at a time. Finish it or cancel it before placing another.

## Reading the outline

The outline is drawn in particles and only you can see it. It updates every half second
while you are within about 48 blocks.

| What you see | What it means |
|---|---|
| **Green** edges | The bill is satisfied — say the word and it starts |
| **Red** edges | Materials are missing; `/squire blueprint` lists what |
| **White** edges | No companion bound to this site yet |
| **Smoke** | These cells will be **dug out** (interior, doorway, shaft) |

Nothing in the world changes until you run `build`.

## Materials

The bill counts the blocks that will actually **stand**, not the volume of the box.
A blueprint that says "solid box, then hollow the interior" costs you the shell only —
the interior is never filled and then thrown away.

Blocks that are already correct are discounted. If you lay a wall yourself, or come back
to a half-built site, the bill shows the remainder.

Two ways to supply the squire:

1. `/squire blueprint fulfil` — command fulfilment straight into its backpack
   (needs the `squire.command.give` permission).
2. Drop the items at its feet; it picks them up.

Windows and other **optional** parts are skipped when you are short of them, so a house
never stops half-finished over two glass panes. Walls are not optional.

The five built-in designs have fixed geometry, but every placement has its own material
palette. In the panel's **工程** tab, place a design and open **配置材料**. Foundation,
frame, walls, floor, roof and windows can be changed independently before construction.
Changing the roof family changes its full set of blocks together (full blocks, stairs and
slabs); it never changes the roof shape. The list only shows complete, explicitly
whitelisted families, so a selection cannot leave half the derivatives missing.

## Digging

Some blueprints have negative space: a hollow interior, a doorway, the shaft of a mine
outpost. Your squire digs those out with a real tool from its backpack, taking real
durability damage, and the drops go into its inventory (or onto the ground when it is
full).

**It will not break a single block outside the blueprint's own footprint.** That is
enforced by the server on every block, not by asking it nicely. Bedrock and protected
cells are skipped rather than failing the job.

## Built-in blueprints

| id | What it is | Tier |
|---|---|---|
| `shelter_wood` | 9×7 timber cottage with posts, windows, porch and a gabled roof | 1 |
| `shelter_stone` | 9×9 stone-and-timber lodge with a taller gabled roof | 1 |
| `storage_shed` | 9×7 framed storehouse with fixed barrels and chest | 1 |
| `watchtower` | 7×7 stone-base watchtower, sixteen blocks tall | 2 |
| `mine_outpost` | 11×9 fortified workshop with a laddered shaft below it | 2 |

## Handing over the whole job

For a whole outpost, say so instead of driving the steps yourself:

```
帮我准备一个矿井前哨站          # or /squire project start mine_outpost
/squire project                     # the six stages, and which one you are on
/squire project pause | resume | cancel
```

It becomes six stages:

| Stage | Who is working |
|---|---|
| **备料** | the persistent project pool waits for and records real material batches |
| **交料** | already covered by the pool, so this compatibility stage is skipped |
| **掘进** | he digs out the negative space |
| **施工** | he lays the blocks |
| **点灯** | he plants torches from the same reserved project pool |
| **验收** | the server re-reads the world: standing, and not dark |

Confirming the preview creates the project even when the entire bill does not fit in one
inventory load. Everything currently available in your main inventory and the assigned
Engineer's inventory/backpack is moved into a durable project pool. The project waits
without changing the world, lists the remaining bill, and the panel's **存入本批材料**
button accepts each later batch. The final batch automatically starts construction.

The missing-material list is the **remaining** construction bill (including automatic
foundations and planned lighting), minus the project pool, your main inventory/hotbar,
and the assigned Engineer's inventory/backpack. Chat, preview and the project panel use
the same calculation. Giving items to the Engineer immediately updates the list; click
**存入本批材料** (or `/squire project resume`) to commit them and continue a paused
project. The existing handover/build commands also resume the active project. Completed
blocks and previously deposited batches are never charged again.

Optional decoration uses spare carried items only, never supplies reserved for required
construction or lighting. Lighting is planned against the finished geometry before
excavation; reservation and placement use those same positions, including separate
rooms/floors and jobs needing more than one 24-torch pass. Torches need valid vanilla
support (a bottom slab is not enough). Unused lighting allowance is returned on completion.

Cancelling returns every unspent batch to its original holder. The project pool and its
material blocker survive server restarts; it never generates missing materials and does
not automatically pull from unrelated chests.

A stage waiting on you shows `[!]` and says what it needs. A stage that fails **pauses**
the project; fix the problem and `/squire project resume`. Nothing is lost, including
across a restart.

The panel's **工程** tab shows the same thing, and his nameplate carries `[施工 3/6]`
while it runs.

## Adding your own

Blueprints and material families are data. Drop a blueprint JSON file into a datapack at
`data/squire/blueprints/<id>.json` and run `/squire blueprint reload` (operator only).
A file whose id matches a built-in replaces it.

```json
{
  "displayName": "Watch post",
  "tier": 1,
  "category": "DEFENCE",
  "size": [5, 5, 5],
  "materialSlots": [
    {
      "id": "wall",
      "displayName": "Walls",
      "type": "WALL",
      "defaultFamily": "squire:stone_bricks",
      "requiredVariants": ["block"]
    },
    {
      "id": "roof",
      "displayName": "Roof",
      "type": "ROOF",
      "defaultFamily": "squire:spruce",
      "requiredVariants": ["stairs", "slab"]
    }
  ],
  "steps": [
    {"material": {"slot": "wall", "variant": "block"},
     "from": [0, 0, 0], "to": [4, 3, 4],
     "what": "walls and floor"},
    {"material": {"slot": "roof", "variant": "stairs"},
     "from": [0, 4, 0], "to": [4, 4, 4],
     "properties": {"facing": "north"}, "what": "roof"},
    {"dig": true, "from": [1, 1, 1], "to": [3, 3, 3], "what": "hollow it out"},
    {"dig": true, "from": [2, 1, 4], "to": [2, 2, 4], "what": "doorway"},
    {"block": "minecraft:glass_pane", "from": [0, 2, 2], "optional": true,
     "what": "window"}
  ]
}
```

- Coordinates are **local**, relative to the north-west floor corner, always authored
  **facing north**. The server rotates them to whichever way the player is looking, so
  the door ends up facing them.
- Steps run in array order and **later steps win**. That is why "solid box, then hollow"
  works and costs only the shell.
- `"dig": true` (or `"block": "minecraft:air"`) marks a step as excavation — no material,
  and it becomes part of the footprint the squire is allowed to break inside.
- `"optional": true` means "skip this when short" — use it for decoration, never for
  structure.
- A material step names a semantic `slot` and a derivative `variant`. Every family
  offered for that slot must supply all of its `requiredVariants`.
- `properties` stores exact block state such as stair `facing`, slab `type` or log
  `axis`. Horizontal facing and axes rotate with the building.
- `size` is the footprint used for rotation. Steps may reach outside it (a shaft with a
  negative `y`, for instance); the protection check and the preview both use the real
  union of every step.

To add a modded family, declare it explicitly at
`data/<namespace>/material_families/<id>.json`. Registry-name guessing is deliberately
not used:

```json
{
  "displayName": "Copper tiles",
  "types": ["ROOF"],
  "variants": {
    "block": "example:copper_tiles",
    "stairs": "example:copper_tile_stairs",
    "slab": "example:copper_tile_slab"
  }
}
```

The family id above is `<namespace>:<id>`. A family may list several slot types, but it
is only offered when it contains every derivative required by that particular slot.

A file that does not parse is skipped with a log line naming the id and the field —
the rest of the blueprints still load.
