# ADR-024: Layered capability enforcement (gateway coarse, WorldEditor exact)

## Status
Accepted (M3)

## Context
Spec §30 scopes high-risk world edits: a capability names WHO may change WHAT region
of WHICH dimension, by WHEN, up to HOW MANY blocks — single-use, task-bound,
revocable. Two enforcement points are possible: the ToolGateway (generic, sees only
declared tool arguments) and the WorldEditor (sees the resolved plan and the real
world). Neither alone is sufficient: the gateway can be fed a stale/lying
capabilityId, while the WorldEditor alone would let unvalidated callers reach it.

## Decision
Both layers validate, with different strictness:

1. **Gateway stage 10b (coarse).** For tools flagged `requiresCapability`, a MODEL
   dispatch without a just-consumed owner confirmation must carry a syntactically
   valid `capabilityId` argument; the store validates agent binding, tool allowance,
   dimension, declared region containment and impact ceiling. Failure maps to
   `CAPABILITY_REQUIRED` / `CAPABILITY_SCOPE_VIOLATION` before any handler runs.
2. **Confirmed-dispatch exemption.** A just-confirmed HIGH-risk call may omit a
   pre-issued capabilityId — the model has no way to mint one. The handler then
   creates a scoped capability internally (`BuiltinTools.submitEdit`) sized to
   exactly the typed region.
3. **WorldEditor.begin (exact).** Before opening a job the editor re-validates the
   capability against the RESOLVED plan — real dimension key, exact region volume as
   impact, canonical tool name (`minecraft.command.fill|setblock`) — then consumes it
   (binds to the task, marks used). Replay is refused; a consumed id cannot start a
   second operation.

Result: 越界必须拒绝 holds at BOTH layers, and neither layer trusts the other.

## Consequences
- A compromised or buggy caller cannot skip validation by choosing which layer to
  talk to; there is no path to `setBlockState` outside WorldEditor jobs.
- Undo-journal capacity is checked BEFORE execution (`RESOURCE_EXHAUSTED` when the
  edit cannot be journaled), so undoability is part of admission control, not a
  best-effort afterwards (spec §52 无法生成 Undo → 拒绝执行).
- Frame budget: jobs write ≤256 blocks/tick; the task completes via the verifier
  condition (`regionMatches`), never by executor self-declaration.
- Tests: `capabilityScopeViolationRejectsWithZeroWrites` proves out-of-bounds AND
  replay rejections leave zero writes; unit tests cover the store matrix.

## Alternatives considered
- Gateway-only validation — rejected: declared arguments can diverge from the
  resolved plan; also nothing guards direct WorldEditor callers.
- WorldEditor-only validation — rejected: violates 不允许跳过 Validation at the
  gateway boundary; handlers would run before any scoping.
- Capability as pure quota counter — rejected: spec requires region/dimension/task
  binding, not just counts.
