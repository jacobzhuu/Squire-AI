# ADR-034: Undo is durable, permission-checked and conflict-aware

## Status
Accepted (work package F1/F4/F5)

## Context
The undo journal lived in memory. Every entry it held was a promise the mod could not
keep across a restart, and the M3-era ADR admitted as much. It also had no owner, so
there was nothing to list and nothing to bind `/squire undo` to, and it would happily
restore over cells somebody else had changed in the meantime.

## Decision
Journals are persisted to `<world>/squire/undo.nbt`, keyed by operation, carrying
owner, dimension, description, creation tick and expiry alongside the entries. Block
states go through `NbtHelper`, so a state with properties (a stair's facing, a slab's
half) returns exactly rather than as a default state; BlockEntity NBT is re-applied
after `setBlockState`, so a chest comes back with its contents.

Undo is not a privileged back door:

- It re-checks the SAME `ProtectionAdapter` the forward edit used.
- A cell whose current state differs from what the operation wrote is a CONFLICT.
  Undo refuses, shows which cells and how many, and asks for
  `/squire undo <id> confirm` — it never silently clobbers someone's later work.
- Journals are per-owner (16 most recent, ~3 in-game days), so `/squire undo list`
  shows a player their own history and nothing else.

Ordinary building writes to the same journal, so `build.structure` is undoable too.
That building spends REAL materials out of the companion's backpack — running out
stops it with `INSUFFICIENT_ITEM` rather than conjuring the rest — and every cell goes
through the same protection check, with an explicit STOP/SKIP policy and a summary.

Capabilities are re-validated per BATCH, not once at the start (§30/F5). A long
frame-budgeted edit must keep proving its licence, so a revoke, an expiry or a scope
change stops the remaining writes. The licence is revoked the moment the operation
completes, fails or is cancelled.

## Consequences
- The impact re-checked per batch is the operation's TOTAL planned cells, not the
  batch size. Comparing a 256-block batch against a 27-block `maxImpact` made every
  bulk edit abort on its first batch — caught by the M3 fill/undo GameTests.
- An operation that cannot be journaled within the cap is still refused BEFORE it
  writes anything; the guarantee is unchanged, it is now just durable.
- Restoring a journal whose block states no longer resolve drops that journal rather
  than half-restoring it. Claiming an undo the mod cannot perform is worse than
  admitting the history is gone.
