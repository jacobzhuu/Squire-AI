# ADR-007: Tool Gateway as the Only Execution Boundary

## Status
Accepted (spec §4/ADR-007, §27)

## Context
Tools come from Squire Core, third-party mods and MCP servers. Permissions and risk controls
are only meaningful if there is exactly one choke point.

## Decision
Every tool invocation — regardless of origin — passes through `server/tool/ToolGateway`
in this fixed order:

```
Tool Lookup → Schema Validation → Exposure Check → Sender Identity → Permission →
Context Validation → Preconditions → Policy → Risk → Capability → Quota/Budget →
Confirmation → Execute → Postcondition → Audit
```

Any rejection means the tool does not execute. No bypass path exists; internal runtime code
calls tools through the same gateway (RUNTIME_ONLY exposure).

## Consequences
- Even prompt-injected model output cannot execute unauthorized actions (defense layer 3, spec §62).
- Gateway decisions are auditable and replayable (spec §76).
