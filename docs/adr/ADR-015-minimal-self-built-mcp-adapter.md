# ADR-015: Minimal Self-Built MCP Adapter (no MCP SDK dependency)

## Status
Accepted (M0 decision, per spec §84 M0-07 fallback clause)

## Context
Spec M0-07: if the MCP Java SDK conflicts with project dependencies, keep MCP as an
independent module using a self-built minimal protocol adapter that never blocks Core.
The official MCP Java SDK pulls reactive-streams and other transitive dependencies into a
mod jar — high conflict risk with Minecraft/Fabric shading rules (spec §97).

## Decision
Squire implements its own thin MCP client (`dev.squire.mcp.*`):
- JSON-RPC 2.0 over STDIO and Streamable HTTP, encoded with Gson (already shipped by Minecraft).
- Transports built on `java.net.http.HttpClient` (async, JDK built-in) and process streams.
- Supports the subset Squire needs: initialize/handshake, tools/list, tools/call, timeouts,
  cancellation-by-close. No resources/prompts/sampling support in v1.

## Consequences
- Zero new external dependencies; no relocation/shading concerns.
- Protocol drift is mitigated by pinning behavior to the MCP spec version we test against,
  covered by MockMCP integration tests (Safe/Malicious/Slow/InvalidSchema/HugePayload/Injection).
- If full SDK features are ever needed, this adapter can be replaced behind the same
  `McpTransport`/`McpClient` interfaces without touching Core.
