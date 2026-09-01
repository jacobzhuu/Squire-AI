# Server Guide

Installation, configuration, defaults, and operations for server owners.

## Requirements

| | |
|---|---|
| Minecraft | 1.20.1 (Java) |
| Fabric Loader | ≥ 0.19.0 |
| Fabric API | 0.92.x line recommended (any 1.20.1 build) |
| Java | ≥ 17 at runtime |

Pinned versions used to develop/test: [docs/version-matrix/README.md](../version-matrix/README.md).

## Install & verify

1. Put the jar in `mods/` alongside Fabric API.
2. Start the server once; the mod logs its startup banner.
3. Verify: `/squire admin metrics` prints the §75 counter snapshot.

## Ship-off-by-default features (§94)

Bounded `minecraft.command.give` fulfilment is enabled for ordinary players. Other
dangerous/optional features remain off out of the box:

```
/squire admin worldedit enable|disable     # bulk world editing by agents
/squire admin commands enable|disable      # server-wide time/weather and optional teleport/summon commands
/squire admin cbp enable|disable           # materialized command blocks
/squire admin automation enable|disable    # player automation graphs
/squire admin replay on|off                # §76 debug replay (privacy notice below)
```

All of these persist for the session and can be flipped back any time.

## The killswitch

```
/squire admin killswitch on
```

Cancels every pending/running task immediately and blocks new task starts while
active. `off` resumes normal operation. This always works, regardless of other flags.

## Configuration & files

Everything lives under two roots:

| Path | Purpose |
|---|---|
| `config/squire/llm.json` | server-admin LLM connection (§23): endpoint, model, API key or `${ENV_VAR}` reference. Absent = degraded mode (FastPath only). |
| `config/squire/mcp-trust.json` | per-server MCP trust assignments (`ADMIN_APPROVED_LOCAL`, `TRUSTED_MOD`, `UNTRUSTED`, `BLOCKED`). Anything absent = `UNTRUSTED`; hand-edited garbage cannot grant trust. |
| `config/squire/audit.jsonl` | append-only tool audit trail (who called what, outcome). |
| `config/squire/replay/replay-session-N.jsonl` | §76 debug replay — OFF by default, redacted, local only. |
| `<world>/squire/workspaces.json` | per-owner CBP workspaces. |
| `<world>/squire/cbp-projects.json` | placed CBP project registry. |
| `<world>/squire/automations.json` | persisted automation graphs. |
| `<world>/squire/turns.json` | versioned structured dialogue goals/plans/clarifications; no raw transcript history. |

Persistence files are independently versioned (`turns.json` is version 2). Legacy
turn version 1 is migrated on read; future versions fail closed and are never overwritten.

### Environment / LLM provider

Without configuration the mod runs in **degraded mode**: FastPath control phrases
("follow me", "stay", …) work, conversational turns answer with a hint that no
LLM is configured. To enable real conversations, create
`config/squire/llm.json` — **a server-admin file; players never supply keys**:

```json
{
  "type": "openai-compatible",
  "baseUrl": "https://api.openai.com/v1",
  "apiKey": "${SQUIRE_API_KEY}",
  "model": "gpt-4o-mini",
  "temperature": 0.7,
  "maxTokens": 2048,
  "timeoutMs": 20000,
  "maxContextTokens": 16384,
  "nativeToolCalls": true,
  "toolCandidateLimit": 16
}
```

- `"apiKey"` accepts a literal secret or an `${ENV_VAR}` reference so the raw
  key can stay out of the file. Unset/blank reference = config refused.
- Any OpenAI-compatible `/chat/completions` endpoint works (OpenAI, vLLM,
  Ollama's compat layer at `http://localhost:11434/v1`, Azure-style gateways).
- Loading is fail-closed: absent/corrupt/incomplete file = degraded mode, one
  log line, no crash.
- The model replies through the server-side strict JSON turn contract; tool
  calls it names still pass every gateway stage (permissions, policy,
  capabilities) and the model can never declare task completion.
- Inspect status without exposing the key: `/squire admin llm`; re-read the
  file after editing: `/squire admin llm reload` (keeps the old provider if
  the new config is invalid).

For an explicit local-first failover chain, use:

```json
{
  "primary": "local",
  "allowLocalToCloudFailover": false,
  "providers": [
    {
      "name": "local",
      "type": "openai-compatible",
      "baseUrl": "http://localhost:11434/v1",
      "apiKey": "local",
      "model": "qwen",
      "dataBoundary": "local",
      "trustDomain": "home",
      "priority": 10
    },
    {
      "name": "cloud",
      "type": "openai-compatible",
      "baseUrl": "https://api.example.com/v1",
      "apiKey": "${SQUIRE_CLOUD_KEY}",
      "model": "cloud-model",
      "dataBoundary": "cloud",
      "trustDomain": "vendor",
      "priority": 20
    }
  ]
}
```

The active endpoint is retried once for empty, truncated or failed responses.
Failover inside one trust domain is automatic. Local input never crosses to a cloud
entry unless `allowLocalToCloudFailover` is explicitly set to `true`.

### FastAPI gateway contract

FastAPI is optional and runs outside the Minecraft process. Point `baseUrl` at a
gateway implementing `POST /v1/chat/completions`. Squire reads `message.content`,
native `tool_calls`, `finish_reason`, `usage` and the response/request id. The gateway
may add RAG or route models, but it must return tool calls rather than execute game
actions; every action is still validated locally by `ToolGateway`.

MCP remains the preferred contract for adding external tools. Imported tools now
carry tags/aliases/read-only metadata and are selected from their own name,
description and schema, so a new MCP namespace no longer needs a hard-coded keyword
entry in Squire.

### Player dialogue controls

```
/squire conversation status
/squire conversation cancel
/squire conversation forget
```

Clarification answers can be spoken directly. Corrections such as “不是村庄，是古城”
or “改成两层” stop the correlated unfinished step and replan; completed world changes
are not silently rolled back.

See also [SECURITY.md](../../SECURITY.md) for key-handling guidance.

## Metrics reference (§75)

`/squire admin metrics` prints fixed counters + duration gauges:

- `fastpath_requests` / `fastpath_hits` / `fastpath_hit_ratio`
- `llm_requests_total`, `llm_failures`, `llm_empty_outputs`,
  `llm_truncated_outputs`, `llm_failovers`, `llm_clarifications`, `llm_tokens`,
  `llm_latency`
- `tool_calls`, `tool_rejected`, `tool_duration`
- `tasks_total`
- `mcp_calls`, `mcp_failures`
- `worldedit_blocks`, `undo_entries`

`tick_budget_exceeded` is reserved with no producer today (writes are frame-capped
by construction).

## Performance notes

- Confirmed world edits compile to bounded vanilla `/fill` or `/setblock`; they do
  not create per-block Undo journal entries. Legacy CBP placement remains capped at 16/tick.
- Commands over 256 characters use a protection-checked temporary command block;
  the previous cell is restored in a `finally` path even when execution fails.
- LLM/MCP I/O runs off-thread; results settle back onto the server thread.
- Automation ticking is bounded per tick; low-priority work yields.

## Compatibility

- Works server-side; the client entrypoint exists but has no required client mod.
- Region-protection mods: Squire consults its ProtectionAdapter seam; vanilla spawn
  protection radius is honored where meaningful. Dedicated servers wanting deep
  integration should provide an adapter (see tool-api guide).
- Known limitation: undo journals are in-memory; restart clears pending undo state
  (CBP removal then refuses honestly rather than half-restoring).

## Upgrading

Versions are pinned (no floating ranges) — see version-matrix for the update
procedure. Read CHANGELOG.md before upgrading; breaking changes to persistence
schemas bump the documented `version` field and ship migration coverage.
