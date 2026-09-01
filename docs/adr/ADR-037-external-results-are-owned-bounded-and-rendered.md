# ADR-037: External results are owned, bounded, and rendered

## Status
Accepted (work package I)

## Context
Third-party and MCP tools already reached the gateway with the right trust rules, and
a remote call already returned RUNNING without touching the server thread. Three
things were still missing, and each of them is a way for a player-visible promise to
quietly break:

- A RUNNING call had no owner and no deadline. If the answer never came, the turn
  waiting on it waited forever; if it came, nothing recorded who to wake.
- Payloads arrived essentially unbounded. They flowed into chat, the audit log and the
  next model prompt as raw JSON — including whatever a remote server chose to call
  `apiKey`.
- Servers could only be connected from Java, so an operator could not deploy or reload
  one without a build.

## Decision
`PendingExternalCallRegistry` records every in-flight call: callId → owner, agent,
turn, task, server, tool, deadline. A tick sweep turns overdue calls into a structured
`MCP_TIMEOUT`, a disconnect immediately fails everything in flight for that server, and
a result arriving after its call already timed out is ignored rather than reopening a
settled question. The registry is capped, so one runaway server cannot exhaust memory.

`McpCircuitBreaker` opens per server after repeated failures, so a dead remote costs
one fast `MCP_CIRCUIT_OPEN` instead of a full timeout on every turn. A cooldown then
admits a single probe; success closes the circuit, failure restarts the cooldown.

`ExternalResultSanitizer` bounds size, string length, nesting depth and collection
width, and redacts fields whose NAMES look like credentials. Name-based redaction is
the honest choice here: the value can be any shape, but "this field is called token" is
reliable. Everything removed leaves a visible marker — the output never pretends the
data was always that small.

`ResultRenderer` gives the player one readable line. The full structured (sanitized)
data still reaches the next model turn, so rendering never costs the model information;
it only stops chat from becoming a JSON dump.

`config/squire/mcp.json` is the only place a server address or launch command is
accepted, with `/squire admin mcp list|reload|status`. It names a `trustKey` into the
existing trust store rather than carrying trust itself, so the config file cannot
promote a server.

## Consequences
- The MCP guide now states the exact protocol surface — newline-delimited JSON-RPC over
  STDIO, plain POST over HTTP, `initialize`/`tools/list`/`tools/call` — and names what
  is NOT implemented (SSE, `Content-Length` framing, resources/prompts/sampling).
  Claiming broader support would send operators to debug their own servers for a
  limitation of ours.
- The shipped example config has every server disabled; installing the mod never
  connects anywhere on its own.
- Sanitization runs on the way INTO observations, so the audit log and the model prompt
  see the same bounded view the player's summary was built from.
