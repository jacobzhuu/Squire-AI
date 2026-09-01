# ADR-045: Permissions are per-owner; autonomy is per-squire. They are two layers.

## Status
Accepted (phase 0 shipped the storage half; phase 2 shipped the autonomy half)

## Context
Two different questions look similar enough that they get merged, and merging them
produces a control the player cannot reason about:

- **"May this player have the companion break blocks?"** — a permission. It belongs to
  the *player*, is what an operator audits, and must survive a restart.
- **"How much does this companion do without being asked?"** — autonomy. It belongs to
  the *companion*, and a player with four squires will want different answers for the
  guard and for the builder.

Before phase 0 the first question was answered by an in-memory map with no persistence
at all: unticking a box on the panel lasted until the next restart, and then silently
reverted to `DEFAULT_PLAYER_NODES` with no message. The second question had no
representation whatsoever.

## Decision
Two layers, two storage locations, and a defined precedence between them.

- **Per-owner permission nodes** live in `<world>/squire/permissions.json`
  (`PermissionStore`, phase 0). Only explicit grants and revocations are stored, never
  defaults, so the default set stays a code decision and a saved file never pins an old
  one.
- **Per-squire autonomy** lives in `AgentRecord.profile.autonomy` and therefore travels
  with that companion's NBT, additive-optional like every other profile field.

**A fine-grained node override always wins over the autonomy level** — the same
"revoked always wins" semantics `PermissionManager` already had. Otherwise a player who
explicitly unticked "break blocks" and then set autonomy to 积极 would watch the
companion break blocks anyway, which is the worst possible outcome for a permission UI.

`AUTONOMOUS` is defined but `available = false`, and setting it is refused with the
reason stated: it is the only level that spends the player's materials and edits the
world unprompted. `AutonomyLevel.byIdOrDefault` falls back to `STANDARD` for anything
unrecognised — a corrupt field must never promote a companion to a level that has not
shipped.

## Consequences
- The panel's permission page stays exactly what it was (a per-player node list); the new
  随从 page carries autonomy. They are visibly separate because they *are* separate.
- Autonomy gating is expressed as `level.atLeast(...)` at the decision point rather than
  as a set of derived nodes. Deriving nodes from a level would put two writers on the same
  storage and reintroduce the merge this ADR rejects.
- `PanelState` carries both, versioned, so a stale client shows "unknown" instead of
  misreading one as the other.
- Phase 5 (downed / rescue) reads autonomy at exactly one place: whether a companion
  starts a rescue nobody asked for. `CONSERVATIVE` means it waits to be told.
