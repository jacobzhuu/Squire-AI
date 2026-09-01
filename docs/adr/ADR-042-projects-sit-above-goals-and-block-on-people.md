# ADR-042: A project sits above goals, advances one stage at a time, and is allowed to wait for a person

## Status
Accepted (playability phase 3)

## Context
"Help me set up a mine outpost" is one sentence and about six jobs. Nothing in the stack
could hold it. `GoalCoordinator` was the closest fit and still could not:

- `GoalRecord` is `(one agentId, one subject, a list of taskIds)`. There is no room for a
  sequence of differently-shaped steps, and no room to hand a step to a different squire
  later (phase 4).
- Its `tick()` hardcodes "done when every remaining task completes". A step that has **no
  running task and has not failed** — because it is waiting for the *player* to hand
  materials over — does not exist in that vocabulary. It would look like a stalled goal.
- Its replan branch was written for the now-retired `ACQUIRE_AND_GIVE` shape.

The temptation was a second scheduler. That would have been the wrong answer: two things
deciding what a companion does next is exactly how "the squire ignored my order" bugs are
born.

## Decision
Four layers: **`Project → Stage → Goal → Task`**. Stages compile into ordinary tasks on
the ordinary `TaskScheduler`, so they are preemptable, time out, persist and recover like
everything else. `ProjectCoordinator` adds exactly one scheduling rule:

> compile the next stage only when `scheduler.current(agentId).isEmpty()`.

That aligns with the scheduler's own one-RUNNING-per-agent invariant and means **anything
the player asks for wins over the project's next step** — the companion does not fight its
owner for its own work queue. Parallelism will come from *more agents* (phase 4), not from
a second queue.

Task-backed stages are tracked through `GoalCoordinator.track(...)` — reusing task→goal
correlation, persistence and restart handling. `GoalRecord.Kind.PROJECT_STAGE` is silent
on completion and failure: the project announces at stage boundaries, and two voices
saying the same thing is how a chat log becomes noise.

**Stages may block on a person.** `HAUL` can wait until the companion actually carries
the bill, reporting exactly what is still missing — a state `GoalCoordinator` has no
vocabulary for.

**A failed stage pauses the project; it does not kill it.** The player fixes the problem
and runs `/squire project resume`. A project that vanishes from the list along with its
progress is worse than one that stops and says why.

**Cross-dimension projects are cut.** The site is pinned to one dimension.
`controlledTeleport` is the most fragile code in this repo and hauling across dimensions
would make the stage machine exponentially more complex for approximately no payoff.

## Consequences
- The model gets one new tool, `project.start(blueprintId)` — a template name, nothing
  else. Same shape as `cbp.plan_project`: it may point at a plan, never construct one.
- Progress is shown in three places and **only at stage boundaries**: the nameplate
  (`[施工 3/6]`), chat via `PlayerNotifier` (works for offline owners), and the panel's
  new 工程 tab. Nothing reports per tick. No boss bar and no HUD overlay — both need new
  client rendering, and the panel already had a place to put this.
- `PanelState` went to version 3. Adding the project half was one record field and one
  write/read line, because ADR-046 made the panel a table and phase 2 made the packet
  versioned; the client needed no new button list at all.
- `LightUpExecutor` (`base.torch_up`) exists because `base.lights.set` **flips levers** —
  it presumes a base that already has redstone lamps. A freshly dug outpost needs actual
  torches, out of the companion's own backpack, on the same materials ledger as the walls.
  The placement rules are shared with the excavator's 照明 ability via
  `world.Torchlight`, so "a floating torch gets knocked off by a neighbour update" is
  fixed in one place.
- `ProjectStore` persists **stage states, not task ids**: task ids change when the
  scheduler restores them. A stage that was RUNNING comes back as PENDING and recompiles,
  which is safe because every stage's success condition reads the real world.
- Running out of torches at the lighting stage **blocks** rather than fails, for the same
  reason `HAUL` blocks: it is a thing the player can fix in ten seconds, and submitting a
  task doomed to `INSUFFICIENT_ITEM` would drag the whole project to a stop instead.


## Amendment: the companion fetches its own materials by default

The first cut routed materials through the player: `FULFIL_MATERIALS` gave them to the
*player*, `HAUL` waited for the player to hand them over. The reasoning was that this is
what makes the newly un-banned hauling mean something, and that the causal chain should be
"the player is the source of resources, the companion is the labour".

In play it reads differently. The whole point of saying "帮我准备一个矿井前哨站" is to
**stop** doing the steps by hand; being handed 200 cobblestone and told to walk it back
turns one sentence into an errand. The friction is real, but it is friction the player
should opt into, not the default.

So the default is inverted: materials are fulfilled straight into the companion's own
backpack and `HAUL` passes immediately. **保守 (CONSERVATIVE) autonomy keeps the old
route** — which is exactly what that level already means ("only what you tell me to do"):
whether the materials pass through your hands is now one of the things you tell it.

Nothing about the bill of materials changed. Blocks are still counted honestly, still
extracted from the companion's real inventory one at a time, and it still stops and says
what it is short of.
