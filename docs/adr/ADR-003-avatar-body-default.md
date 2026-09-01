# ADR-003: AvatarBody as Default Body

## Status
Accepted (spec §4/ADR-003)

## Context
v1 needs a stable physical agent with navigation, goals, combat and persistence.

## Decision
The default agent body is a custom `PathAwareEntity` ("avatar") rendered client-side with a
player-like model (`PlayerEntityModel`) and an explicit AI name tag (`[Squire] <name>`),
using an original bundled skin. Clients without the Squire mod see a vanilla-model placeholder
entity; full visuals require the Squire client.

Rationale: reuse Navigation/Goal/TargetSelector, stable lifecycle, easier combat AI, clean
decoupling from the Task runtime.

## Consequences
- Entity registration, renderer registration and NBT persistence are M1 deliverables.
- Inventory authority lives on the avatar entity (spec §35).
