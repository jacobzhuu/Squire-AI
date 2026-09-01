# Squire AI

A **server-authoritative AI companion** mod for Minecraft Java **1.20.1** (Fabric, Java 17).
Build a training dummy that awakens as a squire which follows you, guards you and rescues you —
under an architecture where *the server, never the language model*, decides what
actually happens in the world.

- Mod id: `squire` · Version: 0.1.0 (v1.0 release candidate)
- License: [Apache-2.0](LICENSE)
- Compatibility: Minecraft ~1.20.1, Fabric Loader ≥0.19.0, Fabric API, Java ≥17
  (full pinned matrix: [docs/version-matrix/README.md](docs/version-matrix/README.md))

## What it does

| Capability | Surface |
|---|---|
| Companion body | Pumpkin-headed training-dummy ritual; persistent recall bell; right-click/K control panel |
| Conversation | Chat or panel — FastPath first, resumable clarification and multi-step LLM planning only when needed |
| Item requests | “give / gather / craft / smelt” wording compiles to bounded, registry-validated `/give` |
| Building | typed `/fill` and `/setblock`, with permission/protection checks and confirmation for high-risk edits |
| Long commands | commands over 256 characters use a temporary command block that is always restored |
| Legacy/admin systems | automation and workspace-bound CBP projects remain available through explicit commands, not companion planning |
| Extensions | third-party tool/sensor/task providers via the `squire` entrypoint + MCP client bridge |

Conversation turns retain only bounded structured goals, plans and references. Empty
or truncated model responses are retried instead of being reported as successful;
local-to-cloud failover is disabled unless a server owner explicitly enables it.

## Non-negotiable architecture

These rules are enforced in code and tested; see
[docs/guides/security-model.md](docs/guides/security-model.md) for how.

1. The LLM never executes raw commands and never bypasses the Tool Gateway.
2. All network I/O is asynchronous — the Minecraft server thread never blocks on the model or MCP.
3. The LLM never declares task completion; the server verifies command outcomes.
4. MCP is a data source, not a permission system.
5. Every write passes Permission → Policy → Capability → Validation, in that order.
6. Growth unlocks abilities and blueprints, never combat numbers; a role never removes
   an ability the companion already had.
7. The companion never mines, chops, farms, crafts or smelts in the wild. Typed commands
   fulfil raw materials. **Hauling and building from a blueprint are the only physical
   labour it is allowed**, the materials must really come out of its own backpack, and
   it will not break a single block outside a placed blueprint's own footprint.

## Getting started (server owner)

```
# requires: Fabric Loader ≥ 0.19.0, Fabric API, Minecraft 1.20.1
./gradlew build          # produces build/libs/squire-*.jar
./gradlew test           # full unit suite
./gradlew runGametest    # 188 deterministic GameTests
```

Drop the jar into `mods/`. Bounded item fulfilment is available to ordinary players;
world editing, optional structured commands, automation and CBP materialization still
require explicit operator enablement (see the [Server Guide](docs/guides/server-guide.md)). Configuration
reference, defaults and file layout are documented there too.

## For players

See the [Player Guide](docs/guides/player-guide.md) — the summon ritual, recall bell,
panel controls, confirmations, and how your workspace protects the rest of the world.
Chinese item aliases (面包 → `minecraft:bread`, 两组火把 → 128 torches) work out of
the box; UI text follows your client language (English / 简体中文).

## For developers

- [Tool API Guide](docs/guides/tool-api-guide.md) — register tools/sensors/tasks from your mod
- [MCP Guide](docs/guides/mcp-guide.md) — connect Model Context Protocol servers
- [Security Model](docs/guides/security-model.md) — the full defense stack
- Architecture decision records: [docs/adr/](docs/adr/) (ADR-001 … ADR-028)
- Implementation history: [docs/milestones/](docs/milestones/) (M0 → M5 exit reports)

## Project status

v1.0 release candidate per `docs/IMPLEMENTATION_SPEC.md`. Known limitations are
stated honestly in the guides (e.g. undo journals live in memory: a restart clears
pending undo state; CBP removal refuses rather than pretend). Security policy and
reporting: [SECURITY.md](SECURITY.md). Contributing: [CONTRIBUTING.md](CONTRIBUTING.md).
