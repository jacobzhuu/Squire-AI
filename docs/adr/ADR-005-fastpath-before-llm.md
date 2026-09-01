# ADR-005: FastPath Before LLM

## Status
Accepted (spec §4/ADR-005, §14)

## Context
High-frequency, unambiguous, low-risk intents ("跟着我", "停下") must not depend on an
external LLM (latency, cost, availability, quota).

## Decision
`server/fastpath/FastPathEngine` resolves P0 intents locally into typed intents. On a hit the
request never reaches the LLM. FastPath output is a `TypedIntent`, not a world change: it still
flows through Permission → Task Compiler → Tool Gateway.
High-risk intents (clearing areas, killing, mass fill/copy) are forbidden from FastPath and
always go through Planner + Risk/Confirmation.

## Consequences
- All M1 core commands work with LLM disabled/unreachable.
- `fastpath_hit_ratio` metric tracked in Observability (spec §75).
