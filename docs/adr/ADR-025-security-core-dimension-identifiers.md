# ADR-025: Security core uses Identifier dimensions, not RegistryKey

## Status
Accepted (M3)

## Context
`Capability` / `CapabilityStore` originally typed their dimension bound as
`RegistryKey<World>`. Constructing test keys requires `RegistryKeys.WORLD`, whose
class initialization bootstraps the full vanilla registry tree — impossible in plain
JUnit (the merged dev jar throws `IllegalAccessError` inside `SimpleRegistry` when
registries are built under the app classloader). Moving all security tests into
GameTests would slow the feedback loop for what is pure authorization logic.

## Decision
The security core (Capability, CapabilityStore) and `WorldEditor.EditPlan` type
dimensions as `net.minecraft.util.Identifier` (e.g. `minecraft:overworld`). Callers
at the Minecraft boundary convert once:
`world.getRegistryKey().getValue()`. Equality semantics are unchanged
(Identifier equality == registry key equality for this use).

## Consequences
- Permission nodes, capabilities, quotas, killswitch and confirmation logic are
  fully unit-testable on the JVM (`SecurityFoundationTest`, 12 tests) with zero
  registry bootstrap.
- The world-boundary types (`ServerWorld`, `RegistryKey`) remain everywhere else;
  only the security records changed.
- Risk: a caller could pass a well-typed but fabricated Identifier — irrelevant,
  because capabilities are compared against the live world's actual key at both
  enforcement layers.

## Alternatives considered
- Keep `RegistryKey`, move tests to GameTests — rejected: 12 pure-logic tests would
  each pay server startup cost for no behavioral coverage gain.
- Bootstrap registries in JUnit — rejected after experiment: the merged jar's
  package-private bridges break under the app classloader; fighting remapping is
  out of scope for this project.
- Plain String dimensions — rejected: Identifier keeps namespace/path structure and
  matches the existing convention in task parameters.
