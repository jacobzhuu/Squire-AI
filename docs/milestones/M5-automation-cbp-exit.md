# M5 Exit Report — Long-Term Automation / Explicit CBP Materialization

Date: 2026-08-26 · Spec source of truth: `docs/IMPLEMENTATION_SPEC.md` §49
(M5 deliverables + DoD), §50/§51, §63, §70, §88-adjacent, §94

## Delivered

| Component | Implementation | Evidence |
|---|---|---|
| AutomationGraph data model (§49) | `dev.squire.server.automation`: State machine (ACTIVE/PAUSED/EXPIRED/ERROR), Builder validating trigger/nodes/edges + branch-completeness + condition indices, default TTL 14 game-days, hand-rolled Gson-tree JSON for deterministic restart recovery | round-trip proven in `restartRecoveryRestoresGraphsAndCursor` |
| Triggers | TIME (time-of-day window, once-per-day dedupe), INTERVAL (spacing from creation/last-fire), OWNER_ONLINE (edge-triggered), PLAYER_ENTER_REGION (primed-set diff, re-primes after restart), TASK_EVENT (deduped deque vs scheduler history, type-filtered), MANUAL (owner-only `/fire`) | `AutomationEngineTest` trigger tests |
| Conditions | timeOfDayAfter/before, ownerOnline, playerInRegion — usable as firing guards AND BRANCH verdicts | `conditionsGateTheFiring`, `branchPicksEdgesByConditionVerdict` |
| Nodes | TOOL_CALL → gateway dispatch; CREATE_TASK → scheduler (NO_EXECUTOR if unknown type); BRANCH by condition verdict; WAIT (parks cursor via waitUntilTick); NOTIFY — walked at ≤1 node/tick with the firing's FIRST node running in the trigger tick | unit suite + gametests |
| AutomationEngine | ships disabled (§94); per-owner cap 16, ≤32 nodes, runaway guard 10k steps, TTL expiry; ownership enforced on every control op (admin override); killswitch freezes ticking (§63); atomic JSON persistence (tmp+move, never throws); recovery keeps PAUSED paused, re-primes region dedupe | `AutomationEngineTest` (14 tests) |
| Runtime wiring | `SquireRuntime.buildAutomationEngine`: TOOL_CALL dispatches through the REAL gateway with a full ToolExecutionContext (CallerIdentity.model); notifier → chat/log; WorldProbe over live players/dayTime; file under world save `squire/automations.json`; tick gated on killswitch; load at SERVER_STARTED, save at shutdown | M5a gametests |
| Command surface (§70) | `/squire automation [list\|inspect\|pause\|resume\|remove\|fire]` (player-owned; console sees all) + admin `/squire admin automation enable/disable` | manual smoke + ownership unit tests |
| CbpSpec + CbpCommandPolicy (§50) | spec validation (≤64 blocks, command-block family only, no duplicate positions) and pure command whitelist mirroring structured-command families (`give @p`, beneficial `effect give @p`, summon-safe entities, relative offsets only, statement-injection refused) — shares `ALLOWED_EFFECTS`/`isSafeSummon` with the compiler | `CbpTest` policy/spec tests |
| CbpWorkspace (§50) | per-owner dimension+region, ≤32³ volume, persisted atomically; **workspace-only is an invariant** (no flag disables it) | `workspacePersistsAcrossRestart`, gametest outside-refusal |
| CbpMaterializer (§50 pipeline) | plan → preview → owner confirmation (single-use/TTL'd via ConfirmationService, fingerprint-bound) → capability issue+self-validate+consume → undo-journal-first frame-budgeted placement (16/tick) → verification (block type AND baked command) → verify-or-undo abort → registry registration; blocks placed inert (`powered=false`); disable forces auto=off; remove replays journal newest-first | ADR-028; `CbpTest` (15 tests) |
| CbpRegistry | every placed project listed with positions/footprint/operation id; persisted to `cbp-projects.json`; post-restart removal honestly reports missing undo data instead of pretending | `registryLifecycleAndPersistence` |
| CBP commands | `/squire workspace set <from> <to>\|show\|clear`; `/squire cbp list\|plan <name> <pos> <type> <auto> <command>\|inspect\|disable\|enable\|remove` + admin `/squire admin cbp enable/disable` (§94 default OFF) | gametest flows use the same service calls as these commands |

## DoD evidence (spec §89 M5)

| Requirement | Status | Proof |
|---|---|---|
| “每天晚上在基地开灯”不放隐藏命令方块 | ✅ | GameTest `nightLightsAutomationRunsThroughGatewayWithoutCommandBlocks`: time-triggered graph fires THROUGH the gateway (audit-visible tool call) while the command-block count DELTA around the avatar stays exactly 0 (ADR-009 baseline technique — the harness plants its own blocks) |
| Automation 重启恢复 | ✅ | unit `restartRecoveryRestoresGraphsAndCursor` (PAUSED stays paused, lastFiredDay survives so no double-fire, next-day refire) + GameTest `killswitchDisableAndRecoveryFreezeCorrectly` (in-place pause→save→load→PAUSED→remove) |
| 玩家明确要求 CBP 时可物化 | ✅ | GameTest `confirmedCbpPlacesVerifiesAndUndoRemovesFully`: workspace → plan → owner confirms by id → registry entry appears → COMMAND_BLOCK with `give @p bread 1` baked in and verified |
| CBP 未确认不执行 | ✅ | GameTest `unconfirmedCbpNeverPlacesBlocks` (target stays air across 120 ticks while request pends) + unit `confirmingWithWrongPlayerNeverStartsPlacement` (forged confirm refused, request survives) + expiry test |
| CBP 只在 Workspace | ✅ | GameTest `cbpOutsideTheWorkspaceIsRefused` (refusal names the workspace rule; nothing pending; distant air untouched) + unit `planRefusesEntriesOutsideTheWorkspace` proving zero world access is even needed to refuse |
| CBP 可完整删除与 Undo | ✅ | same full-flow gametest: disable flips auto off → remove replays the journal → block restored to air exactly → registry empty |

**Verification:** 44/44 GameTests green, 142/142 unit tests green
(`build test runGametest` full chain). Three consecutive green full-chain runs
recorded 2026-08-26 at 15:25:01, 15:25:50 and 15:26:41 — each "All 44 required
tests passed", exit code 0. (A fourth earlier run surfaced a latent M4 gametest
fragility — an avatar lost to environment noise mid-test; hardened by
re-summoning a live body for its final dispatch, and the night-lights test now
restores daylight so it stops leaving the shared world hostile for other
structures.)

## Architecture prohibitions upheld

- 不允许 LLM 直接执行 Raw Command — automation TOOL_CALL nodes carry typed
  arguments through the gateway; CBP blocks only ever contain whitelisted
  structured-intent shapes validated before confirmation.
- 不允许绕过 Tool Gateway — automation dispatches via NodeDispatcher wired to
  the real gateway with CallerIdentity.model; there is no direct handler path.
- 不允许网络 I/O 阻塞 Minecraft Server Thread — unchanged in M5 (no new network
  surfaces).
- 不允许让 LLM 决定任务是否真正完成 — CREATE_TASK goes through the scheduler's
  executor/verifier machinery; CBP placement completion is decided by the
  materializer's own verification pass reading real world state.
- 不允许将 MCP 当作权限系统 — unchanged.
- 不允许跳过 Permission / Policy / Capability / Validation — automation runs as
  CallerIdentity.model through all ten gateway stages; CBP adds spec validation,
  workspace containment, protection-region check, confirmation service, and a
  footprint-bounded capability that is self-validated then consumed.
- 不允许把复杂任务简单等同于命令方块 — long-term conditional logic lives ONLY in
  AutomationGraph (gametest proves zero hidden command blocks); command blocks
  exist solely as the explicit, confirmed, registered, removable product
  artifact of §50.

## ADRs added

- ADR-028 CBP materializer invariants: unconditional workspace-only,
  poll-based single-use confirmation, inert-until-armed blocks,
  verify-or-undo abort semantics, persistent registry vs in-memory undo.

## Known simplifications (documented, non-structural)

- Post-restart CBP removal refuses honestly when the in-memory undo journal is
  gone (blocks remain, message tells the player); session-scoped removal is exact.
- The `/squire cbp plan` command surface creates one-block specs; multi-entry
  projects flow through the API (gametested end-to-end). A richer negotiation
  surface can be added without touching security structure.
- CBP registry persists but does not REAPPLY disabled flags after a chunk reload;
  v1 treats disable as a runtime control, not durable NBT state.
