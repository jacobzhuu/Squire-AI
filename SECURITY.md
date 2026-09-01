# Security Policy

Squire AI is a mod that lets an AI act inside a Minecraft server. That is a
privileged position, so security is a design property, not a feature list. This
document explains the guarantees, the honest limitations, and how to report
problems.

## Supported versions

| Version | Supported |
|---|---|
| 0.1.0 (v1.0 RC) | yes |
| older | no — upgrade before exposing the mod to untrusted players |

## Reporting a vulnerability

Report privately via GitHub **Security Advisories → Report a vulnerability** on
this repository (or contact a maintainer directly if you cannot use GitHub).
Please do not open public issues for exploitable findings.

Include: mod version, Minecraft/Fabric versions, reproduction steps or replay
files (see below), and your assessment of impact. We aim to acknowledge within
**7 days** and will credit reporters by default only.

## The security model in one paragraph

Every action an AI takes flows through one server-side pipeline:
**typed intent → Permission nodes → Policy/risk gates → Capability scope+quota →
argument validation → Tool Gateway dispatch → audit log**, with high-risk world
writes additionally requiring **owner confirmation** (server-issued, single-use,
TTL-bounded) and an **undo journal** captured before the first write. The LLM is
a proposal source; it can never execute raw commands, never bypass the gateway,
never judge task completion, and MCP servers can never raise trust or permissions.
Details: [docs/guides/security-model.md](docs/guides/security-model.md).

## Hard guarantees (enforced + tested)

- No raw command execution by the model: every tool call is a typed, validated object.
- Confirmation requests are owner-bound, single-use and expire (600 s default);
  they cannot be guessed into validity.
- World edits are frame-budgeted, journaled for exact reverse-replay undo, and
  refused entirely if they cannot be journaled.
- CBP (materialized command blocks) exist only inside the owner's explicitly-set
  workspace, stay inert until armed (`powered=false`), verify their baked state,
  and roll back fully on abort.
- Corrupt/legacy persistence files load "best effort, fail closed" (migration tests
  cover garbage files, future versions, mixed corrupt rows).
- Killswitch: operators can instantly cancel all tasks and disable new ones.

## Privacy notice

- **LLM traffic**: when an LLM provider is configured, player chat addressed to a
  squire plus a bounded perception snapshot is sent to that provider. What is sent
  is visible in debug replay (below). Without a provider configured, nothing leaves
  the machine.
- **API keys are server-owner data**: they live in `config/squire/llm.json`
  (optionally as a `${ENV_VAR}` reference so no secret touches disk) and are
  never shown in-game — `/squire admin llm` prints masked status only. Players
  never supply credentials; there is no per-player key surface.
- **MCP traffic**: tool calls to configured MCP servers go to those servers only.
- **Debug replay** (`/squire admin replay on`) writes turn records under
  `config/squire/replay/*.jsonl` for bug reproduction. It ships **OFF**. Records are
  redacted before touching disk — API keys, MCP credentials, passwords/tokens and
  similar fields are replaced with `[REDACTED]` — and files stay on the server
  owner's disk; nothing is transmitted.
- **Audit log** (`config/squire/audit.jsonl`) records who called which tool with what
  outcome — no chat content, no credentials.
- Mojang language resources are never bundled or copied; the Chinese alias table is
  original content maintained in this repository.

## Known limitations (stated honestly)

- Undo journals are in-memory: after a restart, pending undo state is gone. The CBP
  registry persists, so removal of a pre-restart project honestly reports that it
  cannot restore blocks rather than pretending.
- Spawn protection is treated as "owner-adjacent" on integrated servers; dedicated
  servers should layer a region protection mod via the ProtectionAdapter seam.
- Fuzzy/typo item matching and LLM-suggested aliases are deliberately NOT implemented;
  resolution stops at deterministic layers + registry validation.
- The `tick_budget_exceeded` metric key is reserved but has no producer: all world
  writes are frame-capped by construction today.
