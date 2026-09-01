# ADR-020: Static planner table for decomposition, RecipeManager for execution

## Status
Accepted (M2)

## Context
Spec section 36 requires crafting to RESOLVE through the server's RecipeManager and
forbid hand-written output granting. But the ACQUISITION planner (deciding that 32
torches ⇒ 8 coal + sticks ⇒ planks ⇒ logs) must run before any inventory exists to
arrange, and must stay pure/testable — instantiating Minecraft recipe objects in a unit
test is impossible.

## Decision
Two layers:
1. `CraftPlanner` (pure, `dev.squire.server.task`): a small built-in knowledge table
   (gather sources, craft rules with yields, a `#minecraft:planks` placeholder resolved
   against owned items) plus recursive deficit planning with depth/cycle guards.
   Unknown items raise `UnsupportedItemException` → NO_RECIPE surfaced honestly; the
   model is never allowed to assume an unsupported capability.
2. `CraftRecipeExecutor` (runtime): resolves candidate recipes from
   `RecipeManager.values()` by output item, arranges a real `CraftingInventory` from
   OWNED item variants, validates via `recipe.matches`, produces via `recipe.craft`,
   then consumes exactly the verified grid inputs from the avatar inventory. The
   planner's assumptions are re-checked against vanilla at every craft.

## Consequences
- M2 supports common primitive chains (logs/planks/sticks/torches, ore drops, dirt/sand/
  stone). Furnace smelting, tags beyond planks, and modded recipes are honest
  NO_RECIPE gaps until M3/M4 extend the tables (or a later ADR moves planning onto
  RecipeManager introspection directly).
- Because execution always re-validates through matches(), planner drift can never
  fabricate items: worst case is a failed task, never a wrong grant.
