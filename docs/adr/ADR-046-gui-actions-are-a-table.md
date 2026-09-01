# ADR-046: GUI actions are a table

## Status
Accepted (playability phase 0)

## Context
The panel's buttons were hardcoded twice with no test watching the seam: labels and
coordinates lived in the client (`SquireScreen.init` / `buildActionsPage`), behaviour
lived in a server-side `switch (id)` (`SquireScreenHandler.onButtonClick`), and the
two halves were joined by 17 hand-aligned `BUTTON_*` integer constants. Editing one
side without the other compiled fine and silently produced "the button says X but
clicking it does Y" — the exact failure mode this project keeps building tests
against everywhere else. The permission tab was data-driven but its display names
came from an incomplete `switch` that fell through to the raw node string, and the
three armour-set buttons passed Chinese natural-language phrases
("给自己装备下界合金套装") from server code back into the server's own phrase parser.

Later phases need more pages (project, roster) and per-role button sets. Extending
the switch-and-literal pattern for that would have guaranteed drift.

## Decision
`SquireActions` (in `server/gui`) is now the single source of truth: one
`List<Action>` where each entry carries the button id, its lang key, its page, its
layout (`row, col, cols`), whether clicking closes the screen, the permission node
it toggles (permission page), and the server-side handler. The client renders from
`ofPage(page)`; the server dispatches through `byId(id)`. The legacy `BUTTON_*`
constants remain as the id source and are pinned to the table by `SquireActionsTest`.

- Every label is a lang key (`squire.gui.*`), present in both locales — checked by
  test, and by `LangFilesTest`'s widened "UI labels are not chat lines" rule.
- Permission-node display names derive mechanically from the node id
  (`squire.world.break` → `squire.gui.node.world_break`): the mapping is exhaustive
  by construction instead of a switch someone forgets to extend.
- The armour-set buttons call a typed entry point (`equipSelfSet(material, enchanted)`)
  instead of parsing their own Chinese labels. The phrase parser remains for the
  chat/FastPath path where natural language is actually the input.

## Consequences
- Adding a button is one table entry plus two lang keys; the id, the click behaviour
  and the label can no longer disagree.
- `SquireActionsTest` enforces: unique ids, non-null handlers, `byId` round-trip,
  lang keys present in en+zh, permission actions in exact `TOGGLEABLE_NODES` order
  (that order is also the permissionMask bit order), legacy constants still wired,
  and layout fields inside their row. GUI code is covered by tests for the first time.
- Chat feedback lines (跟着你走 / 已收回权限：…) stay server-side literals with the
  `[Squire]` prefix; only UI labels moved to lang files in phase 0. Moving chat lines
  to translatables means reworking the `say()` prefix contract and `LangFilesTest`'s
  chat-line rule together — deliberately deferred, not forgotten.
