# ADR-023: Server-owned high-risk confirmation flow

## Status
Accepted (M3)

## Context
Spec §27/§45: HIGH-risk tool calls from the model layer must be blocked until the
human owner confirms that exact call. The naive design — let the client send a
"confirmed" flag or trust a confirmation id the model relays — makes the client (and
the LLM) the authority, which the spec forbids: 不允许让 LLM 决定任务是否真正完成,
and the security checklist (§82 "Forged client confirmation") demands a forged
confirmation fails closed.

## Decision
The server owns every confirmation end-to-end:

1. The gateway blocks HIGH-risk MODEL calls with `CONFIRMATION_REQUIRED` and never
   executes them.
2. On seeing that block, the orchestrator issues a request in
   `ConfirmationService`: `{confirmId UUID, ownerId, agentId, toolName,
   argumentsFingerprint, issuedAtTick, expiresAtTick}` (TTL 600 ticks). The
   fingerprint is `ToolGateway.fingerprint(arguments)` — the same function the
   gateway re-checks with.
3. Only `/squire confirm <id>` confirms. `ConfirmationService.confirm` fails closed:
   unknown id, someone else's id ("That confirmation belongs to another player.",
   logged as FORGED attempt), expired id.
4. At re-dispatch the gateway's authorizer calls `matchesAndConsume(ownerId,
   toolName, fingerprint, now)`: owner + tool + exact arguments must match, the id
   must be confirmed, unexpired — and it is consumed on use (single-use, no replay).

Because matching happens server-side against the stored fingerprint, an attacker
cannot confirm someone else's request into their own call, and changing one argument
between block and confirm invalidates the match.

## Consequences
- The model cannot self-confirm; it does not even learn usable confirmation ids
  beyond what the orchestrator surfaces to its own owner.
- One confirmation unlocks exactly one dispatch of exactly those arguments.
- GameTest coverage: forged confirm → still blocked for everyone; wrong player
  refused at `/squire confirm`; owner path executes exactly once
  (`M3SecurityGameTests.fillNeedsOwnerConfirmationThenExecutesFramed`).
- Unit coverage: `ConfirmationServiceTest` (single-use, forged fail-closed, expiry,
  tool/argument mismatch).

## Alternatives considered
- Client-signed confirmations — rejected: client trust is exactly what §82 forbids.
- Per-tool cooldown instead of per-call confirmation — rejected: weaker than
  confirming the exact arguments.
- Letting the LLM carry the id back through conversation text — rejected: ids pass
  through the orchestrator result data, not model-controlled channels.
