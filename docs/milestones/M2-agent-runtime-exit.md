# M2 Exit Report — Agent Runtime

Date: 2026-08-26 · Spec source of truth: `docs/IMPLEMENTATION_SPEC.md` §86

## Delivered (spec §86 checklist)

| Component | Implementation | Evidence |
|---|---|---|
| PerceptionSnapshot | `server/perception/PerceptionService` | unit + e2e gametest |
| Context Resolver | `server/runtime/ConversationOrchestrator` | `conversationPathsNoLlmDegradesAndMockCompletesAsync` |
| Agent Protocol | `common/protocol/*` (AgentRequest/Response, ToolCall/Result, Observation) | protocol unit tests |
| Function Calling | `server/cognition/FunctionCallingParser` | malformed-output tests |
| TaskGraph | `Task` + dependency chaining in `TaskCompiler` | torch e2e (5-task chain) |
| TaskScheduler | `server/task/TaskScheduler` (priority queue, one-RUNNING-per-agent) | scheduler unit tests |
| ToolDefinition / ToolRegistry | `server/tool/ToolDefinition`, `ArgDefinition` | registry tests |
| ToolGateway | `ToolGateway` (exposure × capability × risk × loop budget) | `ToolGatewayTest`, security gametest |
| ToolResult / ErrorCode | `common/protocol/ToolResult`, `common/errors/ErrorCode` | error-mapping tests |
| Acquire | `task.acquire` builtin → `TaskCompiler.compileAcquire` | e2e, planner tests |
| Craft | `CraftRecipeExecutor` (vanilla `RecipeManager`) + `CraftPlanner` | craft gametest |
| Inventory transfer | `inventory.give` (inline delivery, ADR-018) | e2e stage GAVE→DONE |
| Guard | `GuardTaskExecutor` (`guard.start`) | zombie-defense gametest |
| Combat | `AvatarEntity.attack` (reach/cooldown), COMBAT permission | guard gametest |
| Healing | `HealTaskExecutor` (`heal.now`), ADR-021 | heal gametest |
| Smelting (B04 support) | `SmeltTaskExecutor` + SMELT plan steps, ADR-022 | smelt gametest, planner test |
| Goal Verification | `GoalVerifier` — the ONLY task-completion authority | all executor gametests assert verifier completion |
| Retry/Replan | `RetryPolicy` + per-task avoidance sets in gather (no infinite retry) | unreachable-ore handling in e2e |
| Tool Loop protection | `LoopProtector` per-turn budget | `happyPathExecutesAuditsAndCountsLoopBudget` |

## DoD evidence (spec lines 3055–3065)

| Scenario | Status | Proof |
|---|---|---|
| “给我 32 个火把” Survival 端到端 | ✅ | `acquireAndDeliverTorchesEndToEnd`: chat → ScriptedProvider → parser → gateway → 5-task DAG (gather coal → gather log → planks → sticks → torches) → inline delivery of 32 torches to owner |
| “帮我砍 20 个木头” | ✅ | `woodCuttingIsASingleGatherStep` (planner: single GATHER oak_log×20); gather machinery proven by `gatherExecutorMinesCoalOre` |
| “做一把铁镐” | ✅ | `ironPickaxePlansThroughSmelting` (full chain incl. SMELT step) + `smeltExecutorProducesIngotsFromRawIron` (raw_iron+coal → 3 iron_ingot COMPLETED, inputs consumed) |
| “保护我” | ✅ | `guardTaskDefendsOwnerFromZombie`: gateway-dispatched guard.start kills zombie, guard.owner COMPLETED via verifier, owner survives |
| “我快死了” | ✅ | `healNowRestoresHealthFromInventory`: heal.now consumes golden apples from real inventory, hp ≥ 19, heal.self COMPLETED |
| 模型说“完成”不会改变 Task 状态 | ✅ | `gatewayRefusesUnsafeModelCalls` #1: model `task.complete` → TOOL_NOT_FOUND; only GoalVerifier mutates terminal state |
| malformed Tool Call 不执行 | ✅ | `malformedModelOutputNeverExecutes` (no task, audited as `malformed_model_output`) + `malformedCallNeverReachesLookup` |
| 所有 Tool 经过 Gateway | ✅ | ConversationOrchestrator is the only tool-call path; exposure/capability/risk enforced centrally (`TOOL_NOT_VISIBLE`, `PERMISSION_DENIED`, `CONFIRMATION_REQUIRED`) |
| Security integration tests 通过 | ✅ | gateway security gametest + full `ToolGatewayTest` suite |

**Verification:** 24/24 GameTests green (two consecutive full runs), 60/60 unit tests green.

## Architecture prohibitions upheld

- No raw-command execution path exists for the LLM; every action is a gateway-dispatched
  tool compiled into runtime-owned tasks.
- No network I/O on the server thread: provider calls run off-thread and re-enter via
  `AsyncBridge.runOnServer`.
- The LLM cannot declare completion: task state changes only through GoalVerifier.
- Permission/Capability checked on every dispatch; PLANNER_INTERNAL tools invisible to models.
- Tasks are not command blocks: multi-step plans are verified goal graphs with bounded retries.

## Known simplifications (documented ADRs)

- ADR-021: healing eats food as flat HP (real food saturation semantics revisited later).
- ADR-022: smelting resolves vanilla recipes but no physical furnace block until M3 WorldEditor.
- Gather uses direct drop collection (ADR-019) with per-task avoidance sets for
  unreachable world-gen targets.

## Deferred to M3

Real furnace interaction, permission nodes/policy persistence, killswitch,
WorldEditor + Undo, structured command surface hardening (spec §87).
