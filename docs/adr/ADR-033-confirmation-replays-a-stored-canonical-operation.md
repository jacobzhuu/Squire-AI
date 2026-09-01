# ADR-033: Confirming replays a stored canonical operation

## Status
Accepted (work package F2/F3)

## Context
The M3 confirmation flow proved that a high-risk edit could not run without the owner
saying yes. It did not get the player to a finished edit. The blocked call was thrown
away; `/squire confirm <id>` only flipped a flag, and something had to make the SAME
call again. In practice that meant the player repeated their sentence and hoped the
model produced identical arguments — which is also the window in which a model could
quietly produce *different* ones after the player had approved a preview.

Two things were missing: the operation itself was never stored, and the region it
applied to came from whatever coordinates the model emitted.

## Decision
The unit of confirmation is a durable `PendingOperation`: confirmId, owner, agent,
operation type, **canonical arguments**, fingerprint, the preview text the player was
shown, issue/expiry ticks, and status. It is persisted to the world save, so a preview
survives a restart.

`/squire confirm <id>` replays those stored canonical arguments once, on the server
thread, through the ordinary gateway. The player never repeats the request, and the
model cannot alter what was previewed: changed arguments produce a different
fingerprint, so they are blocked and need a preview and confirmId of their own.

Regions come from a server-owned `SelectionService` (`/squire selection pos1|pos2|
show|clear`), persisted per player and per dimension. "把选定区域铺成石头" resolves the
region on the server; the model only ever names a block. The model-facing
`worldedit.propose_fill_selection` writes nothing — it returns a preview and a
confirmId, and the real HIGH-risk `minecraft.command.fill` is what the confirmation
replays.

The preview answers what a player actually needs to know before saying yes: tool,
dimension, region and volume, cells that would really change, materials, entities and
containers caught inside, and how large the undo would be.

## Consequences
- "Executing" is only ever reported after a real task was submitted. A submission that
  fails records `FAILED` with the structured reason and changes nothing.
- Statuses are terminal and persisted, so a confirmation is single-use: confirming an
  `EXECUTED` operation says so instead of running it twice, and a `DENIED` one can
  never be revived.
- `/squire deny <id>` exists because "ignore it until it expires" is a worse answer
  than letting the player say no.
- One proposal must equal one confirmation. The propose tool is deliberately MEDIUM
  risk: making it HIGH risk *and* preview-issuing made it ask twice, which the
  `modelProposalNeedsExactlyOneConfirmation` GameTest now pins down.
