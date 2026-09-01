# ADR-032: Location memory is dimension-aware and never guesses

## Status
Accepted (work package E)

## Context
"回家", "仓库在哪", "上次矿洞在哪" and "把矿放回仓库" all need the same thing: places
the player named, remembered for as long as the world exists. A `BlockPos` is not that
thing — (12, 40, -8) in the Nether and (12, 40, -8) in the Overworld are different
places, and sending a companion to the wrong one is the kind of failure a player never
forgives.

## Decision
`LocationMemoryStore extends PersistentState` holds `LocationMemory` records keyed by
memory id and indexed by owner. Every record carries a `GlobalPos` (dimension +
position), a type (HOME / WAREHOUSE / FARM / MINE / CUSTOM), a canonical name, aliases,
a source and `createdAt` / `lastVisitedAt` / `lastConfirmedAt`.

Resolution rules, in order:

- `PLAYER_EXPLICIT` outranks `TASK_RESULT` outranks `OBSERVED`. What the player said
  beats what the runtime inferred.
- "上次…" / "最近…" / "last …" resolve by the largest `lastVisitedAt` within the type.
- An exact name match wins outright.
- When two candidates are equally trusted, resolution returns BOTH and the caller asks.
  Silently picking one is the failure mode this store exists to prevent.

Phrase → type matching is deliberately conservative. Multi-character words ("仓库",
"矿洞", "农场") match as substrings; the single character "家" only matches an exact
phrase, because substring-matching it turns "那家店在哪" into "my home is here" and
overwrites the player's real home.

A WAREHOUSE memory must bind a real, verified container before it is stored; "把矿放回
仓库" compiles to a deposit task against that container and is verified against the
container's final contents.

## Consequences
- The companion's `home` is written to BOTH the `AgentRecord` (movement anchor) and the
  location memory ("家"), so "回家" and "家在哪" can never disagree.
- FARM and MINE memories are coordinates only. Remembering where a farm is grants no
  permission to break anything in it.
- `forget_location` requires the owner or an admin; a memory is not something another
  player can quietly delete.
- Unknown types from a future version degrade to CUSTOM rather than dropping the
  coordinates, and an unknown `schemaVersion` loads the store read-only.
