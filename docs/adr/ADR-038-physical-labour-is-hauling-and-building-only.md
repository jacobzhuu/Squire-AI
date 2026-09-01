# ADR-038: Physical labour is hauling and building, and nothing else

## Status
Accepted (playability phase 1). **Partially rolls back** the "方向性收缩" recorded in
`CHANGELOG.md` under *narrowed companion interaction model*.

## Context
An earlier release narrowed the companion to follow / guard / rescue and moved every
material request onto typed commands. `README.md` non-negotiable principle #6 read
"the companion does not physically mine, craft, smelt, haul or build", and
`OpenAiCompatibleProvider.systemPrompt()` banned the model from asking for any of it.
`OneShotWorldExecutors.BreakOne` / `PlaceOne` and `WorldEditExecutor` were commented
out of `registerExecutors` rather than deleted, explicitly "方便日后回退".

That narrowing solved a real problem — an avatar sent off to mine ore looks broken,
gets stuck on terrain, and produces no legible feedback — but it also cut the loop the
whole mod is supposed to have: explore → resources → base → configure the companion →
work faster → explore further. With nothing physical left to do, building was the one
place resources could have mattered, and `SquireRuntime.executeHouse` wrote an entire
house with `world.setBlockState` inside a single tick while checking and consuming
**nothing**. Materials had no meaning, so neither did gathering them.

The narrowing was written as a ban on *nouns* (mining, hauling, building). That is the
wrong axis: what actually goes wrong is the companion **wandering the world looking for
things**, not the companion **placing a block**.

## Decision
The ban is re-cut along the axis that matters. The companion may:

- **haul** — carry items between the player, containers and a work site;
- **build from a blueprint** — place blocks, one at a time, from its own backpack;
- **excavate negative space** — break blocks, but **only inside a placed
  `BlueprintPlacement`'s own footprint**.

It still may not mine ore, chop trees, farm, craft or smelt in the wild. Raw materials
are still fulfilled by `minecraft.command.give`
(`StructuredCommandCompiler.executeAcquireForAgent`).

The excavation boundary is enforced **server-side**, not by prompt: every cell
`ExcavateExecutor` is about to break is checked against
`ExcavateExecutor.allowed(footprint, pos)`, where the footprint is derived fresh from
the placement's own resolved negative space. An empty footprint forbids everything
rather than degrading to "dig anywhere". The prompt wording is a second line of
defence, and `OpenAiCompatibleProviderTest` pins it.

## Consequences
- `README.md` #6 and the system prompt are rewritten in the same change set as the code,
  so the documented ban and the enforced ban cannot drift.
- `GatherBlockExecutor` stays registered and unchanged — it is still the wild-gathering
  path and is still not reachable from any player-facing entry point. `ExcavateExecutor`
  reuses its internals (tool selection, durability write-back, drop-on-full) but takes
  its target list from a blueprint instead of a world scan, which is both simpler and
  verifiable.
- `OneShotWorldExecutors` and `WorldEditExecutor` stay commented out. This ADR does not
  revive them; single-block model-driven world edits are a different question.
- Anyone tempted to narrow the companion again should narrow **what it may look for**,
  not **what it may touch**. Losing the ability to place a block from its own backpack
  costs the resource loop; losing the ability to wander off looking for ore costs
  nothing.
