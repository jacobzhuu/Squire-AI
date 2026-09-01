# ADR-010: Materialized Command Block Project as Explicit Artifact Only

## Status
Accepted (spec §4/ADR-010, §50, §51)

## Context
Real command blocks are a legitimate player-facing product (visible, editable, redstone-
integrable, shareable), but must never be an internal execution mechanism.

## Decision
A Materialized CBP is created only when the player explicitly asks for a real command block
project. Flow: CBP Spec → structured Command Intents → Workspace selection → impact calc →
Preview → Owner Confirmation → Capability → Undo snapshot → place → test → Registry entry.
Default config: `commandBlocks.materializationEnabled=false`, `workspaceOnly=true`.
Command blocks may only be placed inside an authorized Workspace set via `/squire workspace`.

## Consequences
- CBP placement outside a workspace or without confirmation is rejected by policy.
- CBPs are fully removable with exact undo (M5 DoD).
