# M3 Exit Report — Security / Structured Commands / World Editing

Date: 2026-08-26 · Spec source of truth: `docs/IMPLEMENTATION_SPEC.md` §87 (M3 DoD),
§82 (security checklist), §30/§45/§46/§47/§52/§60/§61/§63/§66/§82/§93/§94

## Delivered

| Component | Implementation | Evidence |
|---|---|---|
| Permission nodes (§61) | `PermissionNodes` (typed constants + defaults), `PermissionManager` (grant/revoke, revocation beats defaults, unknown→false) | `SecurityFoundationTest` |
| Scoped capabilities (§30) | `Capability`, `CapabilityStore` — single-use, task-bound, region/dimension/impact-bounded, TTL, revocable | unit matrix + gametest |
| Quotas (§66) | `QuotaLedger` — 120 tool calls/min rolling window; 100k blocks/hour budget | quota unit tests |
| Killswitch (§63) | `Killswitch` + `SquireRuntime.setKillswitch`: same-tick policy denial, `cancelAll()`, scheduler pause, agents to idle | `killswitchTakesEffectOnTheSameTick` |
| Audit persistence (§27/§93) | `AuditLog.appendJsonl` (Gson-free §96.1), flush cursor never duplicates; flushed every 1200 ticks + at shutdown | `AuditPersistenceTest` (@TempDir) |
| Protection adapter (§60) | `ProtectionAdapter` SPI + `VanillaSpawnProtection` (server.properties radius, level-2 bypass) | exercised by every WorldEditor op |
| Undo journal (§52) | `UndoJournal` — pre-write capture of BlockState + BlockEntity NBT (`createNbtWithId`), newest-first exact restore; capacity check BEFORE execution → else `RESOURCE_EXHAUSTED` | `undoRestoresBlockStateAndChestContents` (chest contents return) |
| WorldEditor (§45) | typed plan → capability re-validation → protection → journal admission → ≤256 blocks/tick frames → verifier completion | fill e2e + scope/replay tests |
| Confirmation flow (§27/§45) | `ConfirmationService` server-owned: id + owner + tool + argument fingerprint, TTL 600t, single-use, forged fails closed | ADR-023 + tests above |
| Structured commands (§46/§47) | six typed tools: `minecraft.command.give/teleport/effect/summon_safe/fill/setblock`; whitelists for effects & summons; Java-side targets only | fuzz-lite + gametests |
| Admin surface | `/squire confirm <id>`, `/squire admin killswitch on/off`, `/squire admin worldedit enable/disable`, `/squire admin commands enable/disable` (level-2 gated) | used by gametest flows |
| Defaults-off (§94) | `worldEditEnabled=false`, `adminCommandsEnabled=false` enforced in the policy gate; default nodes exclude `squire.world.edit`/`squire.admin`/`squire.mcp.manage` | `worldEditsAndAdminCommandsDeniedWhileDisabled` |

## DoD evidence (spec M3)

| Requirement | Status | Proof |
|---|---|---|
| 官方 Core 无 Raw Command 路径 | ✅ | fills/setblocks compile through `WorldEditor.EditPlan`; commands compile via `StructuredCommandCompiler` typed intents only — no model-supplied strings reach Brigadier |
| WorldEdit 越 Capability 边界 100% 拒绝 | ✅ | gateway stage 10b + `WorldEditor.begin` re-validation (ADR-024); out-of-bounds begin refused with **zero blocks changed**; replay refused after consume |
| 非 Owner 无法产生写操作 | ✅ | stage 5c OwnerVerifier on every MODEL write-class call: stranger → `PERMISSION_DENIED`, handler never runs |
| Forged client confirmation 失败 | ✅ | wrong-player `/squire confirm` refused + logged FORGED; forged attempt unlocks nothing for anyone; fingerprint binds confirmation to EXACT arguments |
| `/squire admin killswitch on` 1 tick 生效 | ✅ | activation tick: flag active, scheduler paused, all agent tasks CANCELLED, write → `POLICY_DENIED`, zero writes |
| Undo 精确恢复 BlockState + BlockEntity NBT | ✅ | fill over chest (5 diamonds) → undo restores chest block AND inventory contents; plain states restored too |
| Security tests 100% (权限类结果) | ✅ | §82 mapping: 13/15 covered here, 2 MCP cases deferred to M4 (no surface exists yet) — see `M3-security-checklist-mapping.md` |

**Verification:** 30/30 GameTests green ×3 consecutive full-chain runs
(`build test runGametest`), 85/85 unit tests green.

## Architecture prohibitions upheld

- 不允许 LLM 直接执行 Raw Command — command tools are typed-intent compilers;
  injection shapes are structurally impossible (fuzz-tested).
- 不允许绕过 Tool Gateway — fills reach blocks ONLY via gateway → task →
  WorldEditor job; no other `setBlockState` call sites exist in task code.
- 不允许网络 I/O 阻塞 Server Thread — unchanged (provider off-thread).
- 不允许让 LLM 决定任务是否真正完成 — world.edit completes only when
  `regionMatches` scans the real region.
- 不允许将 MCP 当作权限系统 — permission nodes/capabilities are server-local;
  MCP arrives in M4 behind them.
- 不允许跳过 Permission / Policy / Capability / Validation — pipeline order:
  schema → exposure → capability set → node → owner → context → policy →
  confirmation → quota → capability → execute.
- 不允许把复杂任务简单等同于命令方块 — edits are journaled, frame-budgeted,
  verifier-completed tasks; explicit command-block materialization is M5's
  deliberate product artifact.

## ADRs added

- ADR-023 server-owned high-risk confirmation flow
- ADR-024 layered capability enforcement (gateway coarse, WorldEditor exact)
- ADR-025 security core uses Identifier dimensions (registry-free unit testing)

## Known simplifications / notes

- Smelt recipe input resolution now sorts candidates by recipe id and prefers an
  ingredient the avatar holds — fixes a rare platform-order flake observed once in
  gametests (`INSUFFICIENT_ITEM` when iteration order picked iron_ore over raw_iron).
- Gametest hardening: every global-flag test restores state before any assertion can
  throw, and the shared runtime helper deactivates a leaked killswitch — a failed
  test can no longer poison its successors.
- Spawn protection is vanilla-only for now; region-protection mod integration slots
  into `ProtectionAdapter` without further changes.
- Undo journals are in-memory per operation (spec §52 scope); persistence-grade undo
  history is not an M3 deliverable.
