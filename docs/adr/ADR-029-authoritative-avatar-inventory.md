# ADR-029: One authoritative inventory owns every item mutation

## Status
Accepted (work package C1/C2)

## Context
Items were being moved by whoever needed them moved. `AvatarEntity` exposed
`insertStack`/`extractItems`, executors reached for `SimpleInventory` directly, and
equipment lived only in the vanilla `MobEntity` slots that nothing coordinated with.
Three concrete defects followed from that shape:

- Merging used `ItemStack.areItemsEqual`, which compares the ITEM only. An enchanted,
  damaged, custom-named pickaxe would silently stack onto a plain one and lose its NBT.
- `bestToolFor` built a brand-new `ItemStack` from an item id to mine with, so mining
  wore down a throwaway copy. The pickaxe in the backpack never lost durability, and
  an unbreakable-in-practice tool is an item duplication bug wearing a hat.
- Nothing could roll back a half-finished multi-step move, so a container transfer that
  failed midway left items in neither place.

## Decision
`AvatarInventory` is the ONLY write path for the companion's items: 36 main slots plus
main hand, off hand and the four armour slots, all behind one API.

- Merging uses `ItemStack.canCombine` (item AND nbt), so NBT-bearing stacks stay apart.
- Every operation moves the REAL `ItemStack` object; nothing is rebuilt from an id.
- Tools are addressed by `SlotRef` — a real main-slot index or a real equipment slot —
  so wear is written back to the slot the tool actually came from.
- `snapshot()`/`restore()` give callers a transaction boundary; the container
  executors take one before a two-phase move and roll back on any exception.
- Equipping MOVES an item (backpack → slot, previous piece → backpack) and rolls the
  whole swap back if the backpack cannot hold what came off.

`AvatarEntity`'s old helpers survive as thin delegates so the ~40 existing call sites
did not have to change in one commit; `InventoryView` stays read-only.

## Consequences
- Equipment drop chances are forced to 0. The world-level `AgentRecord` snapshots
  equipment on death, so letting vanilla also roll it onto the ground would duplicate
  every tool.
- Gathering now refuses to break a block it cannot actually harvest (`PRECONDITION_FAILED`)
  rather than destroying it for zero drops.
- Drops that do not fit stay in the world as real item entities and the task reports
  `INVENTORY_FULL`; items are never deleted to make the bookkeeping tidy.
