# ADR-027: MCP Calls Are Async, Data-Only, and Settled Off the Server Thread

## Status
Accepted (spec §53/§54/§56/§57, §88)

## Context
The core prohibitions forbid network I/O on the Minecraft server thread and forbid
MCP from acting as a permission system. A remote call can also hang forever (dead
stdio child, stalled HTTP), return megabytes, or return adversarial text — all of
which used to be crash or injection vectors. Finally, the gametest harness runs the
integrated server far faster than wall-clock 20tps, so real-time timeouts cannot be
awaited in game ticks.

## Decision
1. **Structural asynchrony.** `McpTransport` returns `CompletableFuture`s for
   connect/listTools/callTool; both shipped transports (JDK HttpClient for HTTP,
   blocking-line daemon executor for stdio) never touch the calling thread.
   `handleRemoteCall` validates nothing (the gateway already did), fires the remote
   call and returns RUNNING on the same tick — proven by a same-tick GameTest.
2. **One wall-clock timeout per call** (`orTimeout`, default 10s). Settlement maps:
   TimeoutException → FAILED/MCP_TIMEOUT; any other failure → FAILED/
   MCP_PROTOCOL_ERROR; transport absent at dispatch → immediate MCP_DISCONNECTED.
   Nothing throws into the caller.
3. **Results apply through the completion executor** (production wires
   `AsyncBridge.runOnServer`): settled outcomes land on the server thread as plain
   data events, logged and observable via an outcome listener seam.
4. **Payload cap before entry**: remote text is truncated to 65_536 chars
   (`MAX_RESULT_CHARS`) BEFORE it can enter any result map (§54 payload size).
5. **Results are DATA ONLY** (§57): stored verbatim, never parsed as instructions;
   no code path from result content to permissions, trust, owner, tool visibility,
   capability or killswitch state. Any later tool call re-validates independently.
6. **Testing split by clock**: wall-clock settlement (timeout classification,
   non-blocking <500ms dispatch) is unit-tested with a shortened bridge timeout;
   GameTests prove the same pipeline deterministically by completing/failing the
   mock's pending future directly (the SlowMcp exposes its last pending future for
   this), plus same-tick RUNNING and post-disconnect degradation assertions.

## Consequences
- A dead, slow, lying or flooded MCP server can degrade data quality but cannot
  block a tick, crash the server, or change what the agent may do.
- Trust changes require the admin-owned `config/squire/mcp-trust.json`; a missing
  or corrupt file reads UNTRUSTED (fail-closed).
- The stdio transport launches only admin-configured commands; players and models
  have no surface to set executable/args/env (§56).
