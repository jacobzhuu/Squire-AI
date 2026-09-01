# ADR-041: Roles are a slot trade-off; growth unlocks abilities, never numbers

## Status
Accepted (playability phase 2)

## Context
The companion could do everything: fight, build, haul, excavate, remember places. Once
configured there was nothing left to decide, so there was no reason to summon a second
one and no long-term goal beyond pressing the same buttons again. Adding a role system
solves that only if the roles actually **cost** something — a role that is a pure buff
is a label, not a choice.

Two failure modes had to be avoided at the same time:

1. **Roles that take things away.** The obvious design — "a Guardian may not build" —
   means picking a role makes the companion *worse* at what it did yesterday. Nobody
   picks that, and existing worlds break the moment they load.
2. **Growth as numbers.** Levels that grant +2 attack turn "raise a companion" into
   "farm a stat", and stats are exactly what a mob farm can automate overnight.

## Decision
**Level-1 abilities are basic**: guard, build from a blueprint, excavate, haul, supply,
mark places. Every companion has them, role or not, and they never occupy a slot.
That is precisely the feature set that existed before this phase, so picking a role can
never subtract.

**Level 2+ abilities are advanced, purely additive, and must be equipped in a slot.**
Slots are `2 → 3 (role level 3) → 4 (role level 5)`, and the 4th slot only accepts the
current role's abilities. Unlocks are **permanent across a role change**, so a squire
that trained as a Builder keeps "double work speed" after becoming a Guardian — at the
price of one of its two or three shared slots. That competition is the whole lever:
a four-slot Builder carrying three build abilities has no room for the Guardian's
focus fire, and the answer is a *second companion*, which is what phase 4 is for.

**Growth is milestone-based** (`{40, 160, 500, 1400, 3500}` per track) and every reward
is an ability or a blueprint — never a combat number. Slot count is **derived** from
proficiency and never persisted; a stored derived value drifts from its source, and the
source is already stored.

**Proficiency is credited in exactly one place**: `TaskScheduler`'s single terminal
branch, and only for `COMPLETED`. Cancelled, failed and timed-out tasks pay nothing, and
a task restored after a restart cannot be counted twice.

Anti-idle has four independent rules; each one alone is bypassable:
1. a per-Minecraft-day soft cap per track, overflow credited at 20% (not zero — someone
   who really did build all day should not go unpaid after lunch);
2. **undo rebate** — undoing an operation takes back the proficiency it earned, daily
   allowance included, otherwise "build → undo → build" is an infinite loop;
3. cells that already hold the target block are never counted (the executors skip them);
4. kills count only hostile mobs, and only while the owner is online.

Traits (rolled once, 1–2, never a contradictory pair) affect **mechanics only** —
retreat threshold, work rate, movement speed, damage taken. Putting personality into the
system prompt would fight the parser's strict-JSON contract and, worse, would be
untestable: "is he more cautious today" has no assertion. A threshold does.

## Consequences
- `Task.WorkReport` exists because the scheduler **reuses `executionState`** to store the
  tick verification began, so an executor's counters are already overwritten by the time
  a task reaches `COMPLETED`. Executors now publish a small immutable summary
  (`kind`, `amount`, `operationId`) when they finish, and the accounting reads that.
  This also decoupled the accounting from executor internals — it no longer pattern-matches
  on `Progress` records.
- `AbilityCatalogTest` scans the production sources and fails if an ability declared
  `available` is never checked anywhere. "The panel says it does something and it does
  nothing" is the failure this project keeps paying for; it is now a red test.
- Abilities that are designed but not yet wired ship `available = false` and are refused
  on equip with a plain reason — the same treatment `Role.FARMER` and
  `AutonomyLevel.AUTONOMOUS` get. As of the behaviour-mode slice, no ability is in that
  state; the mechanism stays for the next one that is.
- **The command page does not change when the role changes.** The phase plan expected it
  to. It does not, because every advanced ability turned out to be a *passive modifier*
  rather than a *new command*, and greying out the basic buttons would mean "picking a
  role made him forget how to work" — the thing this ADR exists to prevent. The panel's
  new 随从 page shows role, level, slots, equipped abilities, traits and autonomy instead.


## Addendum: behaviour modes (same phase, second slice)

The four movement modes were four labels on "stand around". Making them different jobs
raised three decisions worth recording.

**The stay-area gate lives in `AvatarEntity.moveTo`, not in the goal.** A goal only owns
movement it started; a task calling `moveTo` drags the body wherever it likes and the goal
never gets a say. Refusing in `moveTo` is the only place where the refusal becomes a task
failure the scheduler reports and `GoalCoordinator.explain` can turn into a sentence
(`OUT_OF_STAY_AREA` → "那地方超出了他的待命范围"). A silently immobile companion and a broken
one look identical to the player.

**The area is stored separately from `MovementMode`.** `moveTo` sets the mode to `IDLE`,
so a gate reading the mode would only catch the *first* leg of a multi-step task and let
every later leg walk the companion out of the circle. `stayArea` is cleared only when the
player picks a different mode.

**`HomeRoutineGoal` contains no business logic** — it decides "idle, at home, for long
enough" and submits a `P7_IDLE` task. The work happens in `HomeRoutineExecutor`, so it is
preemptable, has a timeout, and shows up in the task log like everything else. Logic
inside the goal would be an invisible second scheduler that nothing can stop.

Consequences:
- `MoveHandle.errorCode()` is now part of the body API. `MoveToExecutor` used to overwrite
  every failure with `PATH_NOT_FOUND` / `AGENT_STUCK`, which would have sent players
  looking for a wall that is not there.
- **Stay changed meaning**: it was "return to within 2 blocks of the anchor", it is now
  "do not leave `stayRadius`". `M6AgentLifecycleGameTests.stayReturnsToAnchorAfterPush`
  asserted the old contract and was updated; `M3SecurityGameTests` used `setStayMode()`
  merely to keep a body still and now uses `setIdleMode()`, because a distant navigation
  task is legitimately refused on stay.
- Patrol without configured waypoints keeps circling its anchor. That backward
  compatibility is not optional: every existing world's patrol looks like that, and an
  upgrade must not leave those companions standing still.
