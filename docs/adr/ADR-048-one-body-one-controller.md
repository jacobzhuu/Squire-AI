# ADR-048: One body, one controller — the standing order is never erased, and the newest command wins

## Status
Accepted (fix during playability phase 3)

## Context
A player reported that a mine-outpost project stopped after the first two stages and the
companion "just stopped working". Reading the movement layer turned up two silent state
losses that had been there since M1 and got worse with every phase that added a task type.

**1. `moveTo` erased the player's standing order.** The first line of
`AvatarEntity.moveTo` was `mode = MovementMode.IDLE`. Any task that moved the body — walk
to a container, walk to a build cell, walk home — permanently discarded 跟随 / 待命 / 巡逻.
Nothing ever put it back. After one task the companion was a body with no orders: it would
not follow, would not return to its anchor, would not patrol. From the outside that is
exactly "他不干活了".

**2. `activeHandle` was never released.** It was set on every `moveTo` and only ever
overwritten. Two goals gated on `activeHandle == null` (`PatrolGoal`, `HomeRoutineGoal`),
so after the first task move they could never start again for the lifetime of that entity.

Neither loss produced an error. Both looked like the companion had gone inert.

On top of that, precedence between the player and the runtime was undefined. `待命`
refused any task target outside its radius (`OUT_OF_STAY_AREA`), including work the player
had *just* ordered — so "stay here" silently vetoed "go build that outpost", and the player
saw a companion that accepted a job and then stood still.

## Decision
**The movement mode is the player's standing order. Only the player changes it.**

A task never writes `mode`. Instead the body exposes `taskDriven()` — true while either a
scheduler task is running for this agent **or** a runtime-issued move handle is still
MOVING. Every movement goal (`FollowOwnerGoal`, `StayAreaGoal`, `PatrolGoal`,
`HomeRoutineGoal`) stands down while it is true and resumes the standing order when it goes
false. Both halves of the condition are needed: task-only misses a direct `moveTo` (going
home), handle-only misses the gaps in a build task's walk-place-walk rhythm, during which
the follow goal would drag the companion off the site.

The handle is released a few ticks after it reaches a terminal state — long enough for the
executor to read the result on its next tick, short enough that patrol and the home routine
come back.

**The most recent player command wins, both ways:**

- A **standing-order command** (跟随 / 待命 / 巡逻 / 回家 / 停下), from chat *or* the panel,
  calls `ownerOverride`: it cancels the agent's running tasks, pauses any project, and says
  how many jobs it put down. A companion that answers "let me finish this building first"
  is indistinguishable from a broken one.
- A **work order** (blueprint build, project start, project resume) calls
  `beginOrderedWork(avatar, target)`: if the companion is on 待命 and the target is outside
  the circle, it gives up 待命 and says so, instead of refusing the job.

待命 still fences movement the companion starts **by itself** (the home routine, patrol,
a project step after the player has walked away), which is what the mode is for.

## Consequences
- `M7LogisticsGameTests.followModeDoesNotCancelATaskNavigation` asserted
  `mode == IDLE` after a runtime move — it had encoded the erasure as intended behaviour.
  It now asserts the stronger property: the order survives, and the goal stands down.
  `M1CompanionGameTests.homeReturnNavigatesToRecordedHome` likewise: 回家 is now an
  explicit standing order (`setIdleMode` — stay put at home) rather than a side effect.
- Three new GameTests pin the contract: a task never erases the standing order, a standing
  command cancels running work, and an ordered job outside the stay area releases 待命
  rather than being refused.
- Two related "silently does nothing" bugs were fixed in the same pass, because they
  produced the same symptom:
  - `ExcavateExecutor` counted every cell it could not harvest as *skipped*. A companion
    with no pickaxe would "finish" an excavation having moved nothing, then hang in
    VERIFYING until timeout. It now fails with `PRECONDITION_FAILED`, and the project's
    掘进 stage checks the tool up front and **blocks** with "给他一把镐".
  - The 点灯 stage submitted a torch task that was certain to fail when the companion
    carried no torches; it now blocks the same way. Both are things the player fixes in
    ten seconds — if they are told.
- Two GameTests were quietly depending on randomness introduced by earlier phases and are
  now deterministic: the legacy patrol test asserted an emergent random walk (it now
  asserts that patrol actually starts), and the heal test derived its starting health from
  the damage formula, which the 皮实 trait perturbs.
