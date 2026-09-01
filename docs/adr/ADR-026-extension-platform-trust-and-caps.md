# ADR-026: Extension Platform Trust Model and Import Caps

## Status
Accepted (spec §55/§58/§59, §88)

## Context
M4 opens the mod to third-party code: `SquireToolProvider`, `SquireSensorProvider`
and `SquireTaskProvider` entrypoints, plus MCP-imported tools. A mis-behaving or
hostile provider must never be able to raise its own privileges, exhaust server
resources through import volume, or destabilize core startup — while honest
extensions still get first-class tools, sensors and task types without touching Core.

## Decision
1. **Trust travels with the source.** Every imported `ToolDefinition` records
   `sourceTrust` (INTERNAL / TRUSTED_MOD / ADMIN_APPROVED_LOCAL /
   ADMIN_APPROVED_REMOTE / UNTRUSTED / BLOCKED, spec §55) and `origin`
   (`core`, `mod:<id>` or `mcp:<server>`). Entrypoint providers import as
   `TRUSTED_MOD` (they already run inside the JVM with full mod power); MCP tools
   import as whatever `McpTrustStore` says — default UNTRUSTED.
2. **Hints classify fail-closed.** Provider/MCP annotations map to permission/risk:
   `readOnlyHint`→QUERY+LOW; otherwise WORLD_PLACE+MEDIUM; `destructiveHint` adds a
   destructive flag + HIGH. Hints can make classification *stricter*, never looser.
3. **Untrusted ⇒ no privileged shape.** The gateway policy stage denies an
   untrusted/blocked tool whose shape is privileged (write/command class,
   WORLD_EDIT node, capability requirement, destructive flag or HIGH risk).
   Untrusted read-only QUERY-class tools stay callable (§88 DoD line 1-2).
4. **Import refusals are REPORTED, not thrown** (§58): duplicates, oversized schemas
   (>32 properties), oversized descriptions (2000 chars), >32 args, >64
   tools/provider, reserved nodes, unsanitary names — each becomes an
   `ExtensionManager.ImportReport` visible via `/squire admin tools`; nothing throws
   into provider code, so one bad extension cannot break startup.
5. **Sensors are bounded by construction** (§59): ≤32 sensors, per-observation
   budget truncation, exception isolation (`degraded:` line) — a throwing sensor
   degrades its own line only.
6. **Custom permission nodes are namespaced and idempotent**: registration is
   refused for `squire.*` reserved nodes (whole tool refused), known-but-ungranted
   by default (§94), re-registers on reload succeed.

## Consequences
- Third-party mods register tools/sensors/tasks through `squire-api` types only;
  zero Core modification (DoD verified by the shipped Example provider).
- The trust gate is one clause in the SAME gateway pipeline — no separate MCP
  code path exists to bypass.
- Import caps bound memory even from hostile servers; refusal reasons are always
  auditable through the Tool Inspector.
