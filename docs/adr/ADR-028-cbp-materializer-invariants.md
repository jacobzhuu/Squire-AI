# ADR-028: CBP Materializer Invariants — Workspace-Only, Inert Blocks, Verify-or-Undo

## Status
Accepted (spec §50/§51, §89, §94; refines ADR-010)

## Context
ADR-010 fixed the policy: a Materialized Command Block Project is an explicit
player-facing artifact with an 11-step pipeline. M5b implemented it. Four
implementation questions ADR-010 did not settle had to be answered in code:
whether "workspace-only" itself is configurable, how the confirmation actually
triggers placement (there is no retrying LLM turn to re-dispatch), what happens
when placement fails halfway, and whether placed projects survive restarts.

## Decision
1. **Workspace-only is unconditional.** There is no flag that disables the
   workspace requirement — `workspaceOnly=true` from §94 is an invariant, not a
   default. `plan()` refuses any spec whose entries fall outside the owner's
   `/squire workspace set` region, and refuses everything when no workspace
   exists ("never builds in unknown underground areas"). The workspace caps at
   `BoundedRegion.MAX_VOLUME` and persists per owner.
2. **Confirmation consumption is poll-based on the server thread.** The
   materializer stores a pending keyed by the confirmation id; each tick it asks
   `ConfirmationService.matchesAndConsume(owner, "cbp.materialize", fingerprint)`.
   This reuses the exact single-use, TTL'd, owner-bound service the gateway uses
   (ADR-023) instead of inventing a second confirmation path, and needs no
   retry loop: the moment the owner confirms, the next tick starts placement.
   Pendings expire silently after the same 600-tick TTL.
3. **Inert until armed.** Placed blocks get `powered=false`; impulse blocks stay
   redstone-gated, so nothing executes by merely existing. `/squire cbp disable`
   additionally forces `auto=off` on every block of a project.
4. **Verify-or-undo abort semantics.** Placement runs frame-budgeted (≤16
   blocks/tick). Every write captures pre-state in the UndoJournal FIRST. Any
   failure mid-placement (world unloaded, missing block entity, verification
   mismatch of type or baked command) replays the journal to restore the exact
   prior world content, revokes the capability and notifies the owner — no
   partial project ever remains.
5. **Capability issued after confirmation, consumed immediately.** The
   capability bounds exactly the footprint + block count for tool
   `cbp.materialize`, is self-validated then bound to the project id — proving
   steps 7→8 of §50 without giving anything replayable to later ticks.
6. **Registry persists; undo data does not (v1).** Projects are listed in
   `cbp-projects.json` across restarts, but the UndoJournal is in-memory, so
   `remove` after a restart honestly reports that undo data is gone instead of
   pretending. Within a session removal restores every cell exactly.
7. **One command whitelist, two gates.** Commands baked into blocks pass the
   same families as structured commands (`give @p …`, beneficial `effect give`,
   summon-safe entities) via `CbpCommandPolicy`, which shares the compiler's
   whitelists rather than duplicating them. Multi-statement strings, absolute
   coordinates in summons, and non-command-block types are refused at spec
   validation — before any confirmation exists.

## Consequences
- The four M5b DoD lines map to code: 玩家明确要求 → plan+confirm flow;
  未确认不执行 → placement gated on matchesAndConsume; 只在 Workspace →
  invariant containment check; 可完整删除与 Undo → journal-first writes +
  remove(). GameTests prove each against a live world.
- Known simplification: post-restart undo is unavailable (documented refusal,
  not silent breakage); multi-entry specs arrive through the API while the
  command surface plans one block per call — both acceptable for v1 and
  revisitable without touching security structure.
