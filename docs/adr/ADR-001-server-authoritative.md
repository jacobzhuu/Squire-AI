# ADR-001: Server Authoritative

## Status
Accepted (spec §4/ADR-001, implemented M0)

## Context
Squire AI controls an in-world agent that can move, modify world state, hold items and run
automation. Cheating clients or prompt-injected model output must never be able to mutate
game state directly.

## Decision
The integrated server is the single source of truth for: agent state, tasks, tool gateway,
permissions, world modification, inventory, commands, automation, memory persistence,
audit, and LLM/MCP request scheduling.

The client is responsible only for rendering, GUI/HUD, debug visualization and user input.
All C2S packets are re-validated server-side (size, frequency, sender identity, permission).

## Consequences
- Every world write happens on the server thread through the Tool Gateway.
- Client code must never gate behavior on client-computed permission results.
- Networking code lives under `server/network/v1_20_1` (version-coupled) with strict C2S limits.
