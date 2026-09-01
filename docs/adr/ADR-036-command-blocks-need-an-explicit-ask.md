# ADR-036: Materializing command blocks requires an explicit ask

## Status
Accepted (work package H)

## Context
Placing command blocks is the only thing this mod does that leaves command blocks in
someone's world. It is also the thing a player is least likely to want as a side
effect: "每天晚上在基地开灯" is a request for an outcome, not for a machine. ADR-009
already keeps automation free of command blocks; what was missing was a gate that
decides which of the two a sentence is asking for, plus any player-reachable way to
get a real device when they DO want one.

The materializer had the full safety pipeline (workspace, confirmation, capability,
undo) but nothing produced specs, so the feature was unreachable from chat.

## Decision
`CbpIntent` gates entry. Only words that name the artifact — 命令方块 / command block /
可编辑 / 教学 / 红石装置 / CBP — count as an explicit ask. Sentences that describe an
outcome on a schedule are steered to the AutomationGraph and told so. Anything else is
UNCLEAR: the runtime asks the player and writes nothing. Naming command blocks inside
an automation-shaped sentence still counts as explicit — the player asked for the
device by name.

`CbpPlanner` turns a whitelisted template into a spec. The model picks a template and
an anchor; it never writes a command. Every command in a template is generated here in
a shape `CbpCommandPolicy` already allows and is re-validated by `CbpSpec.validate()`,
so "the LLM smuggled in a raw command" is structurally impossible.

The devices are real ones. `CbpSpec` gained a small support-block whitelist — daylight
detector, redstone lamp, lever, sign, stone — so a project can have a redstone input, a
visible output and a label, plus orientation and the conditional flag. Support blocks
may never carry a command; that would be a command block in disguise.

Placement stays a single path: `beginPlacement`. The `/squire confirm` poll and the
`cbp.materialize_project` tool both reach it, and both must first consume a real
confirmation belonging to that player.

## Consequences
- The undo journal for a CBP project is now owner-attributed and closed (persisted), so
  `remove` restores the exact prior blocks after a restart — the case the older code
  had to refuse. Removal accepts conflicts, because removing a project means putting
  the area back.
- Capabilities are revoked when placement finishes, not just when it fails.
- `blockFor` used to fall back to COMMAND_BLOCK for anything it did not recognise. With
  support blocks in specs that quietly turned every detector, lamp and sign into a
  command block. It now resolves through the registry and throws on an unresolvable
  type; the validator has already restricted the input to the two whitelists.
- Templates anchor at the workspace's minimum corner. That is the rule the player-facing
  entry uses, so tests must look there too rather than inventing their own anchor.
