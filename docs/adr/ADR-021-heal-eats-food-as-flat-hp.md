# ADR-021: M2 healing consumes food as flat HP, not full vanilla effects

## Status
Superseded (work package D2 of the playability remediation plan) — see
"Superseded by" below. The M2 decision below is kept for the record.

## Context
The "我快死了" scenario needs a healing path. Vanilla golden apples apply a bundle of
food/saturation/absorption/regeneration effects through player-only mechanics
(`FoodComponent` ticking lives on `PlayerEntity`; the avatar is a mob). Simulating the
full effect stack for a mob body is disproportionate to M2's goal: proving the
SAFETY SHAPE of healing (inventory consumption → body mutation → verifier gate).

## Decision
`HealTaskExecutor` consumes one configured item (default `minecraft:golden_apple`)
from the real inventory per tick and applies a flat 4.0 HP via
`LivingEntity.heal(float)`, until a health-fraction threshold is met. The task is
P1_SURVIVAL and non-interruptible; a consumed-counter valve fails the task honestly
if healing appears ineffective.

## Consequences
- Healing amounts are slightly weaker than vanilla golden apples (no absorption/regen).
- The executor/condition split stays intact: the condition reads only the narrow body
  interface (`snapshotState().health()`), never executor bookkeeping.
- Revisit when an item-effect pass lands (M3+); the tool surface (`heal.now`) will not
  change.

## Superseded by: real item effects (D2)

The flat-HP shortcut is gone. `HealTaskExecutor` now runs the item's OWN effect path
via `ItemStack.finishUsing(World, LivingEntity)`, so a golden apple grants the
regeneration and absorption it really grants and a healing potion applies its real
potion effects — on a mob body, because `LivingEntity.eatFood`/`applyFoodEffects` are
not player-only after all (only saturation/hunger are).

Two behavioural consequences follow, and both are intended:

- Healing is now GRADUAL, not instant. The executor therefore waits out the previous
  item's effect (bounded by `EFFECT_SETTLE_TICKS`) before eating another, instead of
  emptying the backpack into one wound.
- Healing the OWNER is a different job with different safety rules and lives in
  `OwnerAidExecutor` (`aid.owner`). `heal.self` treats only the companion.

The `heal.now` tool surface is unchanged, exactly as this ADR predicted.
