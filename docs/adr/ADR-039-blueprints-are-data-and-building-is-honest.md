# ADR-039: Blueprints are data; steps are flattened before a single block is placed

## Status
Accepted (playability phase 1)

## Context
`HouseTemplate` fixed the shape of a house in Java so the model could not produce a
solid box with no door. That reasoning still holds, but it made shapes a code change,
and `SquireRuntime.executeHouse` executed the compiled plan **literally**: for each step,
fill the region. The template's steps are deliberately overwrite-style — "build a solid
box", then "hollow the interior", then "cut a door" — because that is how a human
describes a house.

Executed literally with real materials, that costs about 190 oak planks for a 7×7×4
shelter and then throws ~100 of them away. The bill of materials a player is asked to
gather would be nearly double the blocks that end up standing. Since the entire point of
phase 1 is *materials really flow*, a bill that lies is worse than no bill.

The old path avoided the problem by not checking or consuming anything at all.

## Decision
A blueprint is **data**: `Blueprint` + `BlueprintStep` records, loadable from
`data/squire/blueprints/*.json` via `BlueprintCodec`, so a new building is a JSON file
and not a Java change. Five ship built in; a datapack entry with the same id overrides
a built-in rather than being forced to rename.

Before anything is placed, `Blueprint.resolve(origin, facing)` **flattens the steps into
a per-cell final target**: the last step covering a cell wins.

- final target is a real block → `toPlace`, built by `BlueprintBuildExecutor`, one
  `items.extract(itemId, 1)` per cell;
- final target is air *and* a negative step covered the cell → `toClear`, dug by
  `ExcavateExecutor` (on flat ground these are already air, so nothing is dug);
- a cell no step covers is never touched.

So the bill equals the **shell**, not the solid volume, and the number the player is
asked to gather is the number of blocks that will stand. `BillOfMaterials` additionally
discounts cells that already hold the target block, so re-checking a half-built site
reports the remainder rather than the original total.

`HouseTemplate` is not deleted. It becomes the *constructor* for two of the built-ins:
`BlueprintRegistry` compiles it once at the origin facing north, and the resulting
absolute regions are the local-coordinate steps. `HouseTemplateTest` is untouched.
`Blueprint.rotate` reproduces `HouseTemplate.doorPos` direction for direction, which
`BlueprintTest` pins — a rotation off by one puts the door inside a wall, and the player
cannot tell why they cannot get in.

Two independent flags carry the parts of a shape that behave differently:
`negative` (dig, no material) and `optional` (skip when short, e.g. windows). Without
`optional`, a house stops half-finished over two missing glass panes.

## Consequences
- `Blueprint.bounds` is the **union of the step regions**, not `width×height×depth`.
  `mine_outpost` sinks a shaft below its origin; computing bounds from the declared size
  would leave the underground part outside the protection check and outside the ghost —
  i.e. ground moved without ever being judged.
- Restart recovery needs no cell cursor. A task carries only a `placementId`; on restart
  the shape is re-resolved and the world is re-asked which cells are still wrong. That is
  strictly stronger than a cursor: if someone knocks a wall out mid-build, the rebuild
  puts it back, where a cursor would walk past it. The checkpoint carries only the
  placed/skipped counters so the completion summary does not lie.
- `BlueprintBuildExecutor` is a new executor rather than a rework of
  `BuildStructureExecutor`: the latter's `Progress` and `structureBuilt` are single-block
  single-region by construction, that condition is the only evidence for
  `VERIFYING→COMPLETED`, and `M8WorldEditGameTests` depends on it. Its missing
  `recoverySuccessCondition` was back-filled in the same change set — before that, a
  restart mid-build failed the task with `SERVER_RESTARTED` while the half-built
  structure stayed in the world.
- Success conditions read real world blocks, skip `optional` cells and cells protection
  refuses. Otherwise a house short two glass panes sits in `VERIFYING` forever with no
  way out but cancelling.
