# ADR-017: AgentRegistry Is a Rebuildable Index, World State Is Authoritative

## Status
Accepted (M1, extends ADR-013)

## Context
`AgentRegistry` caches which live `AvatarEntity` belongs to which owner. During GameTests
the registry was observed going stale: gametest batches interleave on one server, and any
test calling `SquireRuntime.init()` replaced the whole runtime instance between another
test's summon and its chat. A pure in-memory lookup silently lost registrations — exactly
the failure mode a real chunk unload/restart can produce.

## Decision
1. The registry is an **optimization over authoritative world state**, never the source of truth.
2. `resolveForOwner(owner)` first consults the index; on a miss it **scans loaded entities**
   (`iterateEntities`) for an alive `AvatarEntity` with that owner and heals the index with
   what it finds.
3. `SquireRuntime.init()` rebuilds the index from worlds; callers never need to re-register.
4. One active squire per owner: `summonFor` discards and unregisters any existing body
   before spawning a new one.

## Consequences
- Restarts, dimension changes and concurrent index invalidation self-heal at resolution time.
- Resolution cost in the miss path is O(loaded entities); acceptable at M1 agent counts,
  revisit if fleets grow large.
- Tests must not assume cross-test static state survives: shared mutable registries
  (`ProviderRegistry`, `SquireRuntime`) are exercised inside single tests where ordering matters.
