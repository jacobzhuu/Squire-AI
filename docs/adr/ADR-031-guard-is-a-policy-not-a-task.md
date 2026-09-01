# ADR-031: Long-term guard is a policy, not a task

## Status
Accepted (work package D1/D2)

## Context
"保护我" compiled to a single `guard.owner` task capped at 24000 ticks. That is about
one Minecraft day, after which the companion silently stopped protecting the player.
A task is also the wrong lifetime unit here: it dies with the entity, does not survive
a chunk unload, and has nothing sensible to do while the owner is offline.

Owner healing had the mirror-image problem. `heal.self` was the only healing path, so
"我快死了" healed the COMPANION — the one entity that was not hurt.

## Decision
`GuardPolicy` is durable state on the `AgentRecord`, not a task: owner, agent, enabled,
radius, threat rules, creation tick. It ends when the player says "停止保护" (or a tool
disables it) and at no other time. `GuardRuntime` ticks it on the server thread —
threat selection, pathing, swing timing, weapon swaps and retreat all in Java, with the
LLM absent from every tick.

While the owner is offline or the body is not materialized the policy simply lies
DORMANT. It is never deleted, because "log off" is not "stop protecting me".

Owner healing moved into its own `OwnerAidExecutor` (`aid.owner`) with its own rules:
it heals the PLAYER using real items out of the companion's backpack, through each
item's own effect path — a splash potion is really thrown and resolved by vanilla, a
drinkable potion and a food item apply their own declared effect lists. With no usable
item it returns `INSUFFICIENT_HEALING_ITEM`. It never conjures one.

## Consequences
- `GuardPolicy.parse` accepts the older `guard:<radius>` string as an enabled,
  all-rules policy. A migration must not quietly cancel an order the player already
  gave.
- Disabling writes `guard:false:…` rather than removing the row, so a restart cannot
  resurrect a guard the player explicitly ended.
- Weapon selection goes through `AvatarInventory.bestWeaponSlot` + `equipFromMain`,
  which moves the weapon. Guard duty cannot duplicate a sword.
- The companion breaks off and returns to the owner below 25% health instead of
  fighting to the death; keeping the owner alive is the point, not winning the fight.
- `guard.start` keeps its bounded-task behaviour by default and takes an explicit
  `persistent` flag for the policy, so the existing tool contract still holds.
