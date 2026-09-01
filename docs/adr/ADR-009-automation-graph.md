# ADR-009: AutomationGraph Instead of Hidden Command Blocks

## Status
Accepted (spec §4/ADR-009, §49)

## Context
Long-term automation (timers, conditions, loops, schedules) must not be implemented by
silently placing command blocks in the world — that hides logic from players and bypasses
policy.

## Decision
Long-term automation is expressed as a persisted `AutomationGraph`
(AutomationId/OwnerId/AgentId/Trigger/Conditions/Nodes/Edges/State/TTL/PolicySnapshot)
executed by Java. Triggers: MANUAL, TIME, INTERVAL, OWNER_ONLINE, PLAYER_ENTER_REGION,
TASK_EVENT. Nodes: ToolCall, CreateTask, Branch, Wait, Notify.
Loops require `maxIterations` or `timeout`. Automations support list/inspect/pause/resume/
remove/restart-recovery/audit. Default state: disabled (spec §93).

## Consequences
- "每天晚上在基地开灯" creates an AutomationGraph entry, never a hidden command block (M5 DoD).
- Automation state is persisted with schemaVersion and recovered on restart (§72).
