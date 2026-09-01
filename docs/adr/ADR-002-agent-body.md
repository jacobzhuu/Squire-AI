# ADR-002: AgentBody Abstraction

## Status
Accepted (spec §4/ADR-002)

## Context
Tasks, tools and behaviors need to move/attack/inventory an agent without knowing whether it is
a custom entity, a fake player proxy, or a test double.

## Decision
Upper layers depend only on the `dev.squire.server.body.AgentBody` interface
(`agentId`, `ownerId`, `capabilities()`, `snapshotState()`, `moveTo`, `stopMoving`,
`attack`, `useItem`, `interact`, `inventory()`, `lookAt`, `emote`, `alive()`).
Business logic must not branch on concrete body types (`instanceof CompanionEntity`).
Body differences are declared via `BodyCapabilities`.

Implementations:
- `AvatarBody` — default (v1)
- `ServerPlayerBody` — EXPERIMENTAL, isolated in `server/body/player/`
- `TestBody` — unit/integration tests

## Consequences
- Task/tool implementations compile against interfaces only.
- New body types require no changes above the `body` package.
