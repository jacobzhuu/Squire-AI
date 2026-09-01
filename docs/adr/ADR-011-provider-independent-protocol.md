# ADR-011: Provider Independent Agent Protocol

## Status
Accepted (spec §4/ADR-011, §22)

## Context
LLM providers differ in APIs; core task/tool code must not know provider SDK types.

## Decision
Core communicates through its own DTOs (`common.protocol`): AgentRequest, AgentMessage,
AgentResponse, ToolDescriptor, ToolCall, ToolResult, PlanSpec, TaskSpec, Observation,
ProviderUsage. `LlmProvider` implementations (OpenAICompatibleProvider, MockProvider,
optional Anthropic/Ollama) adapt these to vendor formats. Provider SDK types never leak into
`task/`, `tool/`, or `server/`.

## Consequences
- CI runs exclusively on MockProvider (spec §80); real providers need no recompilation to swap.
- Function-calling fallback: strict JSON schema when native tool calling is unavailable;
  MALFORMED_MODEL_OUTPUT retried once, then graceful degradation, never a server crash (§24).
