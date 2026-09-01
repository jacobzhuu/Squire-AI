# ADR-047: The agent store schema only bumps for structural change; leaf fields are additive-optional

## Status
Accepted (playability phase 0)

## Context
`SquireAgentStateStore` v1 held `Map<ownerId, AgentRecord>` — one companion per
player, hard-coded in the in-memory container. Three concrete things were blocked or
broken by that shape:

1. `recordOfAgent(agentId)` resolved through `agentId → owner → recordOfOwner(owner)`.
   With more than one record per owner that chain returns the wrong record.
2. `SquireRuntime.onAvatarLoad` used `recordOfOwner` to decide whether a loading
   entity is authoritative. A second companion's entity would have matched the
   owner's record, failed the identity check, and been **discarded** — data loss, not
   duplicate protection.
3. Per-companion profile facts (role, growth, autonomy — phase 2) had nowhere to live
   that `snapshotFromEntity` could not clobber.

The NBT itself never had the 1:1 assumption: `records` is a flat list and every row
carries its own `ownerId`. Only the in-memory container was 1:1.

## Decision
Schema v2 changes the container to `Map<ownerId, List<AgentRecord>>` plus a direct
`Map<agentId, AgentRecord>` index, and adds two fields to `AgentRecord`:
`primary` (which record is the player's default command target) and `profile`
(a `SquireProfile` shell for role/growth/autonomy facts, separate from body
snapshots). The roster limit stays at one companion per player in this phase — only
the data shape moved.

- `recordOfOwner` keeps its signature; its meaning narrows to "the primary record".
  Existing callers (summon, lifecycle tests) are unchanged.
- `onAvatarLoad` resolves by agentId first. The old owner-level discard rule only
  applies when the agentId is unknown to the store while the owner already has some
  record — the pre-A1 zombie-body case. The stale-body (duplicate instance) check
  stays inside the agentId-hit path.
- Loading a v1 file needs no special case: rows land in the list (one per owner) and
  a post-load pass grants `primary` to each owner's first record. The
  `version > CURRENT → fail-closed` branch is untouched, which is what stops an old
  jar from reading a v2 file and silently overwriting the second companion.
- Leaf fields (and everything phase 2 adds to `SquireProfile`) follow
  **additive-optional**: read with `contains` guards, write only non-default values.
  They never bump the schema version.

## Consequences
- `migrateFromLegacy` counts via the agent index (`recordsByAgent.size() + 1`),
  which equals the old owner count while the 1:1 limit holds.
- `put()` is list-aware (dedupe by agentId, append otherwise, primary management)
  and `synchronized`; `setPrimary(agentId)` repoints the default companion.
- `M12StoreMigrationGameTests` covers: v1 → v2 upgrade (including PATROL mode
  survival through migration), future-version fail-closed + write refusal, corrupt
  row isolation, profile/primary round-trip, and `recordOfAgent` resolving each
  record to itself — the regression test for the discard bug.
- These tests live in the GameTest source set, not plain JUnit: `AgentRecord` embeds
  `ItemStack`, whose class initialisation needs Minecraft's Bootstrap, and the plain
  unit-test classloader cannot bootstrap it (`IllegalAccessError`).
- Opening the roster limit later (phase 4) is a summon/command-layer change plus a
  limit constant — no further schema migration.
