# ADR-018: inventory.give executes inline, not as a deferred task

## Status
Accepted (M2)

## Context
"给我 32 个火把" ends with handing the crafted items to the requesting player.
We first implemented delivery as a scheduled task (`inventory.give` type) whose goal
condition compared the avatar's item count against a baseline captured at SUBMIT time.
That baseline is wrong whenever the task queues behind other work (as in the acquire
chain): by the time the task runs, the avatar legitimately holds exactly the items it is
about to hand over, so the submit-time condition can never hold — or holds trivially.

Goal conditions are deliberately narrow (they see only the body interface), so they
cannot read a run-start baseline from executor state either.

## Decision
Delivery is an INSTANT server-thread operation executed directly inside the gateway
handler: extract → insert into the requester's inventory → report SUCCESS / FAILED
(INSUFFICIENT_ITEM | INVENTORY_FULL | ENTITY_NOT_FOUND). No task is created for give.

## Consequences
- The verifier-baseline problem disappears; the synchronous result IS the evidence and
  is audited like every other dispatch.
- Give remains fully gateway-gated (permission INVENTORY_WRITE, risk MEDIUM, exposure
  MODEL_PUBLIC) — no architecture rule is bypassed: the LLM still cannot touch
  inventories except through the pipeline.
- Deferred (task-based) delivery returns in M3 if long-range delivery (mail runs)
  becomes a feature; then the executor itself will own a run-start baseline stored in
  `executionState`, and the goal condition will be expressed relative to the body
  interface only ("avatar count ≤ X"), set by the executor at start() via params.

## Alternatives considered
- Mutable baseline holder shared between handler and condition closure — rejected:
  hidden cross-object coupling inside a safety-critical path.
- Extend TaskEvaluationContext with player access — rejected: violates the "conditions
  are world-read-narrow" boundary without a concrete need yet.
