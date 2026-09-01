# ADR-030: Container logistics run as verified two-phase transactions

## Status
Accepted (work package C3)

## Context
Deposit / withdraw / transfer / sort all move items between two inventories that can
each refuse them: a chest fills up, a furnace slot rejects the wrong item type, a
double chest is really two block entities, a chunk unloads mid-task, a protection mod
says no. Written ad hoc, each of these becomes its own duplication or deletion bug.

## Decision
`ContainerAccess` is the single world-side seam for container work, and every
container executor goes through it.

Resolution is fail-closed and returns a structured code, never a boolean: chunk loaded,
distance within the player-equivalent 6 blocks, the block entity really implements
`Inventory`, and the SAME `ProtectionAdapter` the WorldEditor uses says yes. Double
chests resolve through `ChestBlock.getInventory(...)` so a write cannot land in half
the container.

Moves are two-phase: compute the movable amount with `insertableAmount` first, then
commit both sides. Both inventories are snapshotted before the commit and restored
together if anything throws.

Sorting merges only stacks that are equal in item AND nbt, orders them by a stable
category → id → nbt key, and then RE-COUNTS the container: if the totals do not match
what went in, the whole sort is rolled back and the task fails. Sorting is limited to
chest-like containers, because a furnace's slots carry meaning and "tidying" fuel into
the output slot is not a tidy-up.

## Consequences
- Every container failure the player can hit has a name: `NOT_A_CONTAINER`,
  `UNREACHABLE`, `WORLD_CHANGED`, `PROTECTED_REGION`, `INVENTORY_FULL`,
  `INSUFFICIENT_ITEM`.
- Container tasks are submitted with a goal condition anchored to a baseline read at
  DISPATCH time, so the verifier compares real before/after container contents. A
  container operation that cannot be verified is refused instead of started — an
  unverifiable task would sit in VERIFYING until it timed out.
- Unknown/modded items are ordered last and kept; sorting never discards what it does
  not recognise.
