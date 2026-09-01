# ADR-022: M2 smelting runs recipe-true without a physical furnace block

## Status
Superseded (work package C4 of the playability remediation plan) — see
"Superseded by" below. The M2 decision below is kept for the record.

## Context
Scenario B04 "做一把铁镐" requires iron_ingot, which in 1.20.1 comes from smelting
raw_iron. Filling and tending a real FurnaceBlockEntity needs block placement +
multi-slot container interaction — capabilities that land with the M3 WorldEditor
work, not the M2 task runtime. Deferring smelting entirely would leave an explicit
M2 DoD scenario honestly refusing (NO_RECIPE) despite all its ingredients being
within reach.

## Decision
`SmeltTaskExecutor` resolves inputs and outputs from `RecipeManager`
(`RecipeType.SMELTING`) exactly as vanilla defines them. Each unit: consume 1 input
from the avatar inventory + a 1/8 share of a coal/charcoal fuel unit (vanilla burn
value), wait a simulated cook delay (40 ticks), then insert the recipe's output.
The planner gained SMELT steps (`iron/copper/gold ingot ← raw …`) so
`task.acquire("minecraft:iron_pickaxe", 1)` compiles the full chain:
gather iron_ore → gather coal → smelt ingots → gather wood → craft planks/sticks →
craft pickaxe.

## Consequences
- Recipe facts are never invented: unknown smeltables still resolve through vanilla;
  only fuel handling is simplified to coal/charcoal.
- No furnace block appears in the world; nothing can burn or leak items mid-cook.
  The trade-off is cosmetic realism, not correctness of inventory math.
- When WorldEditor lands (M3), the executor gains an optional "use nearby furnace"
  path; the tool surface and planner stay unchanged.

## Alternatives considered
- Real furnace placement now — rejected: drags M3 world-edit risk into M2 for no
  DoD benefit.
- Planner-only shortcut (grant ingots directly) — rejected: violates the spec rule
  that outputs must come from RecipeManager resolution plus verified consumption.

## Superseded by: real furnace interaction (C4)

`SmeltTaskExecutor` no longer produces output itself. It now finds and REUSES an
existing furnace / blast furnace / smoker within a bounded scan radius, walks into
interaction range, writes the input and a real fuel item into the block entity's
slots, and takes the result out of the output slot once the vanilla furnace ticker
has produced it. Nothing is inserted into the avatar's inventory that a real furnace
did not make.

What this changed beyond the executor:

- Fuel is chosen with `AbstractFurnaceBlockEntity.canUseAsFuel`, so any real fuel
  works instead of a hardcoded coal/charcoal list.
- `RecipeType.BLASTING` and `RecipeType.SMOKING` are supported by matching the device
  actually standing there.
- A furnace someone else is using, an incompatible output slot, or an unloaded chunk
  puts the task into a BOUNDED wait and then fails honestly — it never fabricates the
  result.
- Devices are reused, never placed. Without a furnace in range the task fails with
  `NO_WORKSTATION` (distinct from `NO_RECIPE`), so the player learns what is missing.
- Cook time is now real (200 ticks per item), which is why `TaskCompiler.smeltTimeout`
  budgets it and why a task's timeout counts from when it STARTS rather than from when
  it was queued.
