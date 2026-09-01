# Security Model

How Squire lets an AI act in a world without giving up server authority. This is
the map; the tests are the proof (44 GameTests + 178 unit tests at v1.0 RC).

## The pipeline

Every action — from a chat-triggered "follow me" to a 4096-block fill — passes:

```
intent (typed, never raw text)
  → Permission nodes        is this caller allowed this class of action?
  → Policy / risk gates     LOW/MEDIUM/HIGH routing, loop protection, quotas
  → Capability              dimension + region + maxImpact + expiry, single-use
  → Validation              every argument re-checked against the real registry
  → Tool Gateway dispatch   the ONLY execution path
  → Audit log               who called what, with what outcome
```

Skipping any stage is impossible from tool code: the gateway owns dispatch and
there is no second path.

## The six prohibitions, enforced

| Prohibition | Enforcement |
|---|---|
| No LLM raw-command execution | Model output is parsed into typed intents only; free-text command strings are rejected as `MALFORMED_MODEL_OUTPUT` |
| No bypassing the Tool Gateway | Single dispatch entry point; automation/CBP/commands all funnel through it |
| No network I/O on the server thread | Providers/MCP run on their own executors; results settle via `AsyncBridge.runOnServer` (GameTest proves timeout cannot block) |
| No LLM-declared completion | `GoalVerifier` judges tasks against world state; model verdicts are ignored |
| MCP ≠ permission system | Trust store is server-owner data; MCP annotations can only restrict further (ADR-012) |
| No skipped Permission/Policy/Capability/Validation | Pipeline order fixed in code; stage refusals short-circuit before execution |

## High-risk confirmation (§45/ADR-023)

HIGH-risk operations pause for an explicit owner decision:

- The **server** issues a random confirmation id bound to the requesting owner.
- Single-use (`matchesAndConsume`), TTL-bounded (600 ticks default), fail-closed on
  any mismatch.
- CBP materialization additionally requires the fingerprint of the exact spec to
  still match at confirm time.

## World writes & undo (§45/§52)

`WorldEditor` + `UndoJournal` guarantee:

1. Preview/dry-run before accepting plans.
2. Capability numbers checked against *actual* affected cells.
3. Protection adapter consulted per region.
4. Refusal if the operation cannot be journaled (无法生成 Undo → 拒绝执行).
5. Pre-write capture of old block state + block-entity NBT per cell.
6. Frame-budgeted application (≤256 cells/tick).
7. Reverse-order undo restoring containers byte-for-byte.

CBP adds workspace containment (unconditional), inert-until-armed placement,
post-placement verification, and verify-or-undo aborts (ADR-028).

## Killswitch (§63)

One operator action cancels all pending/running tasks and blocks new starts.
Independent of every other flag; tested under load.

## Persistence resilience (§92)

All stores are atomic-write (tmp+move), schema-versioned, and loaded
fail-closed: garbage files start empty instead of crashing; partially-corrupt
files keep their healthy rows. Covered by `PersistenceMigrationTest`.

## Privacy

See [SECURITY.md](../../SECURITY.md): replay ships off, everything written locally
is redacted credential-wise, no telemetry exists.

## Threat-model summary

| Adversary | Blocked by |
|---|---|
| Prompt-injected model | typed intents, permission/capability stack, goal verifier |
| Malicious extension mod | default UNTRUSTED trust, import refusal, annotation-only-tightens rule |
| Malicious MCP server | data-only results, trust gating, timeouts, no instruction channel |
| Hand-edited persistence files | enum validation, fail-closed loaders, schemaVersion |
| Rogue agent body | one-per-owner binding, capability scopes, killswitch |

Residual risks and honest limitations: [SECURITY.md](../../SECURITY.md).
