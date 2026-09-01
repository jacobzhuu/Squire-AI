# MCP Guide (server owners)

Squire can connect to [Model Context Protocol](https://modelcontextprotocol.io)
servers and expose their tools to agents as **data-only** capabilities.

## The one rule that matters

> MCP is a data source, not a permission system. (spec §55 / ADR-012/027)

Tool descriptions, annotations and payloads coming from an MCP server are hints
at best. They can make Squire treat a tool *more* restrictively — never less.
Every MCP-derived call still passes the same Permission → Policy → Capability →
Validation pipeline as everything else, and results re-enter the runtime as
plain data, never instructions.

## Connecting a server

Servers are declared in `config/squire/mcp.json`. **That file is the only place a
server address or launch command is accepted** — Squire never takes an endpoint from
chat, and the config file cannot grant trust (it only names a `trustKey` into
`mcp-trust.json`). If the file does not exist, `/squire admin mcp reload` writes an
example with every server `"enabled": false`.

```json
{
  "version": 1,
  "servers": [
    {
      "name": "factory",
      "transport": "HTTP",
      "url": "http://127.0.0.1:8080/mcp",
      "timeoutSeconds": 10,
      "allowlist": ["machine_status"],
      "trustKey": "factory",
      "enabled": true
    },
    {
      "name": "local",
      "transport": "STDIO",
      "command": ["npx", "-y", "@example/mcp-server"],
      "enabled": false
    }
  ]
}
```

Operator commands (permission level 2):

| Command | What it does |
|---|---|
| `/squire admin mcp list` | configured servers, connection state, and any config problems |
| `/squire admin mcp reload` | re-reads the file, disconnects everything, reconnects what is enabled |
| `/squire admin mcp status <server>` | trust, connection, circuit-breaker state, in-flight calls |

A broken entry is skipped with a reason and the healthy servers still load; a bad
config degrades this feature and never blocks server startup.

## Protocol compatibility - what is actually implemented

Be aware of the exact shape Squire speaks, so you can tell whether your server will
work before you start debugging it:

| | Supported | Not supported |
|---|---|---|
| **STDIO** | JSON-RPC 2.0, **newline-delimited** (one JSON object per line) on stdin/stdout | `Content-Length:` header framing (the LSP-style framing some servers use) |
| **HTTP** | JSON-RPC 2.0 over plain `POST` with a JSON response body | Server-Sent Events / streaming responses, session resumption, long-poll |
| **Methods** | `initialize`, `tools/list`, `tools/call` | resources, prompts, sampling, notifications, cancellation |
| **Auth** | whatever your reverse proxy or local process boundary enforces | in-protocol auth negotiation |

`protocolVersion` is announced as `2025-03-26`. A server that only offers SSE, or that
frames STDIO with `Content-Length`, will fail to handshake — that is a real limitation
of this client, not a misconfiguration on your side.

## When a remote call goes wrong

Every failure mode ends as a structured error the model can act on, never a hang:

- **Timeout** — per-server `timeoutSeconds`; the call becomes `MCP_TIMEOUT`. A call
  whose answer never arrives at all is caught by a deadline sweep on the server tick,
  so a turn can never wait forever. A late answer arriving after that is ignored.
- **Disconnect** — every in-flight call for that server immediately becomes
  `MCP_DISCONNECTED`.
- **Repeated failure** — after 5 consecutive failures the server's circuit opens and
  calls fail fast with `MCP_CIRCUIT_OPEN` instead of costing a full timeout each. After
  a cooldown, one probe call is allowed through; success closes the circuit again.

## What players and the model each see

Remote payloads are sanitized before they go anywhere: bounded size, string length,
nesting depth and list/map width, with fields whose names look like credentials
(`token`, `apiKey`, `authorization`, `password`, …) replaced by `«redacted»`. Players
get a short readable summary (`factory:machine_status: status=running, energy=812`);
the full structured data goes to the next model turn and the bounded log, not into chat.

## Registration internals

On connect the bridge:

1. handshakes on the transport worker thread (never the server thread),
2. lists advertised tools,
3. imports them into the tool registry with trust derived from
   `config/squire/mcp-trust.json`,
4. reports the import trail via `/squire admin tools` (Tool Inspector).

A failed handshake means the server is not registered — nothing half-alive lingers.

## Trust assignments (`config/squire/mcp-trust.json`)

```json
{
  "weather": "ADMIN_APPROVED_LOCAL",
  "sketchy-remote": "BLOCKED"
}
```

| Value | Meaning |
|---|---|
| `TRUSTED_MOD` | bundled-level trust |
| `ADMIN_APPROVED_LOCAL` | explicitly approved by this server's owner |
| `ADMIN_APPROVED_REMOTE` | approved, remote origin |
| `UNTRUSTED` | default; write-shaped/high-risk use denied |
| `BLOCKED` | never called |

Anything absent from the file is `UNTRUSTED`. Hand-edited values outside the enum
cannot grant trust (covered by migration tests). Credentials belong in the
environment or your transport setup — never in world/config JSON; replay files
redact credential-shaped fields regardless ([SECURITY.md](../../SECURITY.md)).

## Operations

- Inspect imported tools: `/squire admin tools`
- Metrics: `/squire admin metrics` shows `mcp_calls` / `mcp_failures`
- Timeouts: calls settle asynchronously; a hung server disconnects itself after its
  timeout instead of blocking gameplay (proven by GameTest).

## What an MCP tool can and cannot do

| | |
|---|---|
| ✅ Provide read-only data (weather, prices, docs lookup…) | within UNTRUSTED policy |
| ✅ Propose actions | as suggestions only |
| ❌ Execute raw commands | impossible by architecture |
| ❌ Grant permissions to itself or others | trust store is server-owner data |
| ❌ Bypass confirmation for high-risk operations | confirmations are server-issued |
