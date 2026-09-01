# ADR-012: MCP Is an Extension Protocol, Not a Permission System

## Status
Accepted (spec §4/ADR-012, §53-57)

## Context
MCP servers advertise tools with annotations (readOnly/destructive/idempotent) and descriptions.
Treating those claims as authorization would let an untrusted external server grant itself rights.

## Decision
Squire alone decides whether to import, expose, allow, rate-limit, scope or execute any MCP tool.
Trust levels: INTERNAL < TRUSTED_MOD < ADMIN_APPROVED_LOCAL < ADMIN_APPROVED_REMOTE < UNTRUSTED
(default) < BLOCKED. MCP annotations are hints only — never permission facts.
MCP module is optional and default-disabled; core runs without it (spec rule 13).

## Consequences
- Imported tools pass the same ToolGateway checks as native tools.
- Tool results are UNTRUSTED_EXTERNAL_DATA: they cannot alter policy/owner/trust/capability (§57).
- Import sanitization enforces schema depth/property/description/payload limits (§54).
