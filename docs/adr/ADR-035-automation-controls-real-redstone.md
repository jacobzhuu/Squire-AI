# ADR-035: Long-term automation is structured, indefinite, and controls real redstone

## Status
Accepted (work package G)

## Context
The automation engine could run graphs, but nothing a player said could create one.
Building a graph meant calling the Java builder, so "每天晚上在基地开灯" had no route
into it at all. Two further problems made the feature dishonest even when a graph did
exist:

- Every graph carried an implicit two-week TTL. A player who asked for something to
  happen *every night* got it silently switched off a fortnight later. That is not an
  expiry policy; it is a bug with a timer.
- The demo "lights" action was `example:echo("lights-on")`. Nothing in the world
  changed.

## Decision
`AutomationCompiler` turns a NAMED template plus parameters into validated graphs. The
model can pick a template; it cannot describe an arbitrary graph, and it cannot smuggle
a raw command or a Java class through. Everything is whitelisted: trigger kinds, node
kinds, and — critically — the tools a node may drive. No high-risk world-edit, command
or CBP tool appears in that list, because a long-term automation must not become a way
around the confirmation flow.

Validation enforces the node cap, a minimum trigger interval, tool existence, and that
any cycle is paced by a WAIT node.

Creating one goes through the same durable preview/confirm machinery as world edits
(ADR-033): the player sees the triggers, action, location, validity and permissions,
and confirms once. Nothing is registered before that.

Lights are real. The anchor is a **lever**, not a redstone lamp: setting
`RedstoneLampBlock.LIT` directly is a lie, because vanilla's scheduled tick turns an
unpowered lamp straight back off. Flipping the lever — with the neighbour updates
vanilla performs on click, including the face it is mounted on — makes the lamp light
from an actual redstone signal that persists. Scanning is radius-bounded, the base
comes from trusted location memory, every write passes protection, and each run is
recorded in the undo journal.

Recurring graphs now default to `NO_EXPIRY` and run until the owner pauses or removes
them. One-shot graphs may still set an explicit TTL. The engine's enabled flag is
persisted, so an operator enables automation once rather than after every restart.

## Consequences
- Old files are migrated once: a recurring graph carrying exactly the legacy two-week
  TTL becomes indefinite, and the migration is logged. A future schema version starts
  empty rather than downgrading the file.
- Persisting the enabled flag introduced a sharp edge: `setEnabled` saves, and saving
  before `load()` would write an EMPTY graph list over the file — enabling automation
  after a restart would have deleted every automation. Saving is now suppressed until
  the engine has loaded, pinned by
  `togglingTheSwitchBeforeRecoveryNeverWipesTheFile`.
- No lever near the base is an honest refusal, not a graph that quietly does nothing
  every night.
- Ordinary automation places zero command blocks, asserted against the test's own
  structure (an absolute-radius scan reaches into the CBP suite's blocks in the shared
  GameTest world and proves nothing).
