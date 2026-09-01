# ADR-040: The ghost preview is particles — not blocks, not entities

## Status
Accepted (playability phase 1)

## Context
A placed blueprint has to be visible before it is built, or the player is asked to gather
64 planks for a shape they cannot see. Three options were on the table.

## Decision
Server-sent particles, addressed to one player.

- ❌ **Temporary barrier / structure blocks.** They really change the world. The undo
  boundary goes dirty immediately, protection has to judge a preview, and a crash leaves
  the marker blocks standing.
- ❌ **`BlockDisplay` entities.** They exist in 1.20.1, but a 7×7×4 shell is around 200
  cells and therefore ~200 entities, they do not support translucency, and they need
  entity tracking for something that is not part of the world. Worth it only for a
  handful of markers, not a whole shape.
- ✅ **Particles.** `ServerWorld.spawnParticles(ServerPlayerEntity, …)` sends to exactly
  one player from server code. No new packet, no client code — this mod's client side is
  one `Screen` and one `Renderer`, and a preview is not a good reason to break that.

`BlueprintGhost` draws the twelve edges of the bounding box every
`INTERVAL_TICKS` (10), capped at `MAX_POINTS` (320) sampled points, only while the owner
is online, in the same dimension, and within `VIEW_DISTANCE` (48). Drawing every cell
turns the site into fog and hides the very shape it is meant to show.

Colour carries the one fact the player acts on: white when the companion's backpack is
unknown, **red when short of materials**, **green when the bill is satisfied**. Cells
that will be *dug* use `ParticleTypes.SMOKE` instead, so "this will be hollowed out" never
reads as "this will be filled in".

## Consequences
- Preview costs nothing but packets and is impossible to leave behind: stop ticking and
  it is gone. `BlueprintManager.tick` deliberately sits **outside** the killswitch freeze
  — it writes nothing, and freezing it would only hide from the player something they
  already placed.
- The tint is recomputed from the live world and the live backpack each draw, so a player
  filling the companion's backpack watches the outline turn from red to green without
  running any command. That is the phase-1 feedback loop in one glance.
- `BlueprintGhost.outline` is a pure function of a `BoundedRegion`, so the sampling rule
  is unit-testable even though the drawing is not.
- If a future phase wants per-cell colouring (e.g. "this wall is done"), the cap has to
  move with it; the honest ceiling is the particle budget, not the API.
