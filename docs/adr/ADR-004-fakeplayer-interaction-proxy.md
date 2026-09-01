# ADR-004: FakePlayer as Interaction Proxy Only

## Status
Accepted (spec §4/ADR-004, verified by spike docs/spikes/M0-03-fakeplayer-proxy.md)

## Context
Block breaking/placing, item use and many modded callbacks require a `PlayerEntity`.

## Decision
Fabric's `FakePlayer` is used only as a short-lived interaction proxy for:
break block, place block, use block, use item, modded player callbacks.
It is never the agent body. The avatar inventory remains authoritative: before acting the
proxy is prepared from avatar state; after acting counts/durability/returns are synced back.
FakePlayer/world references are not cached across ticks.

## Consequences
- `server/body/proxy/FakePlayerInteractionProxy` owns prepare/sync logic.
- GameTests cover: normal break, durability, place consumption, item use, interaction failure,
  chunk unload, agent death interruption (spec §10.3).
