# ADR-008: Structured Command Only

## Status
Accepted (spec §4/ADR-008, §46, §47)

## Context
Letting the model emit arbitrary command strings (`execute_command(String)`) is equivalent to
giving it operator rights.

## Decision
The official Core exposes NO raw command tool. Models see typed tools such as
`minecraft.command.give` / `.teleport` / `.fill` with strongly-typed arguments
(`PlayerRef`, `ItemId`, `BlockId`, `BoundedPosition`, `BoundedRegion`, `PositiveInt`,
`SafeEffectId`). Java compiles validated parameters to commands, parses them with Brigadier,
executes, and verifies. Selectors (`@a`, `@e`), arbitrary SNBT and execute strings are rejected
at validation time.

## Consequences
- CommandCompiler fuzz/property tests are mandatory (spec §83).
- COMMAND_DENIED / COMMAND_PARSE_FAILED / COMMAND_FAILED error codes distinguish failure modes.
