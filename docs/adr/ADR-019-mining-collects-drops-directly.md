# ADR-019: Runtime mining collects drops straight into the avatar inventory

## Status
Accepted (M2)

## Context
Vanilla block breaking spawns ItemEntities. Real players pick them up; the avatar has
no pickup goal and fake players do not tick, so proxy-broken blocks would litter the
world with drops nobody collects. Gathering tasks need the ITEMS, not the litter.

## Decision
`FakePlayerInteractionProxy.breakAndCollect(...)` runs the authentic loot path
(loot table + harvest gate + durability damage) but RETURNS the drop stacks instead of
spawning them; the gather/break executors insert them into the avatar's authoritative
inventory (ADR-004 sync direction). Overflow that does not fit is dropped back into the
world at the mined position rather than deleted. The original `breakBlock(...)`
(spawn semantics) remains for debug/inspection use.

## Consequences
- Gather loops close immediately: break → count check on next scan; no pickup phase,
  no orphaned entities in tests.
- Secondary drops (e.g. gravel flint) are also inserted, preserving vanilla yields.
- Durability is intentionally NOT synced back to the avatar's tool stack in M2
  (the proxy damages a copy). Tracked as M3 work alongside tool-preference policy;
  acceptable because test scenarios provide disposable tools and survival balance is
  not an M2 DoD.
