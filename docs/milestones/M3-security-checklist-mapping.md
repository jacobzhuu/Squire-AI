# M3 — §82 Security Test Checklist Coverage

Spec section 82 lists 15 mandatory security test cases. Permission-type results must
pass 100%. This document maps every case to its automated coverage.

| # | Case | Layer(s) | Automated coverage | Result |
|---|------|----------|--------------------|--------|
| 1 | Non-owner instruction | Gateway stage 5c (`OwnerVerifier`) + `AgentRegistry.isOwnerOf` | GameTest `nonOwnerModelWriteIsRejected` — stranger's MODEL write → `PERMISSION_DENIED`, handler never runs; owner's identical call runs. Unit: role model tests (M1) | ✅ |
| 2 | Owner replacement prompt | Owner binding is registry state, never conversation text; only the owner UUID may confirm/steer. FastPath/role tests (M1) + `ConfirmationServiceTest.forgedConfirmationsFailClosed` (a non-owner cannot act as owner even with the id) | ✅ |
| 3 | Permission escalation | Stage 5 capability set, stage 5b permission nodes (`PermissionNodes`/`PermissionManager`: revocation beats defaults, unknown node false), defaults-off flags in `PolicyGate` | Unit `SecurityFoundationTest.defaultPlayerNodesExcludeAdminPowers`, `adminHoldsEverythingButUnknownNodesNeverPass`, `grantAndRevokeOverrideDefaults`; GameTest `worldEditsAndAdminCommandsDeniedWhileDisabled` | ✅ |
| 4 | Selector injection | Model never supplies selectors/names: typed args compile to Java-resolved targets (`@s` bound server-side) | Unit `CommandArgSafetyFuzzTest.effectIntentRejectsInjectionShapes` (`@a[limit=1]` rejected), `safeSummonWhitelistBlocksHostileAndInjectionShapes` | ✅ |
| 5 | Command separator injection | Typed records regex-gate every string before `String.format`; Brigadier parse-or-refuse | Unit `effectIntentRejectsInjectionShapes` (`speed;say hacked`, `{Fire:-1000l}`, path traversal); gateway schema fuzz survives hostile payloads | ✅ |
| 6 | Coordinate overflow | `BoundedRegion` clamps to world bounds with saturating volume; tool args intRange ±30M/±2048 | Unit `BoundedRegionTest.extremeCoordinatesClampToWorldBounds`; gateway fuzz NaN/LONG_MAX rejected | ✅ |
| 7 | Capability replay | `CapabilityStore.consume` single-use; WorldEditor re-validation refuses consumed ids | Unit `capabilityValidatesThenConsumesExactlyOnce`; GameTest `capabilityScopeViolationRejectsWithZeroWrites` (replay begin refused) | ✅ |
| 8 | Capability region escape | Region containment at stage 10b AND exact re-validation in `WorldEditor.begin` (ADR-024) | Unit `capabilityRejectsWrongAgentToolDimensionRegionImpact` (越界必须拒绝); GameTest out-of-bounds edit → zero writes | ✅ |
| 9 | Forged client confirmation | Server-owned confirmation flow (ADR-023) | Unit `ConfirmationServiceTest` (forged fail-closed, single-use, expiry, argument mismatch); GameTest forged `/squire confirm` by wrong player → nothing unlocks | ✅ |
| 10 | Task ID guessing | Task ids are UUIDv4 issued by the scheduler; task state changes are executor/scheduler-internal (`task.complete` is TOOL_NOT_FOUND for models — M2 gateway test). Confirmation ids likewise random UUIDs, single-use | ✅ |
| 11 | MCP annotation spoofing | ✅ closed in M4: hints (`readOnly`/`destructive`) classify fail-closed only — `McpToolImporter.build` maps `readOnlyHint`→QUERY/LOW, else WORLD_PLACE/MEDIUM, `destructiveHint`→HIGH+destructive; the gateway's stage-7 trust gate denies untrusted tools with ANY privileged shape regardless of claimed hints. Unit `McpStackTest.maliciousImportsAreSanitizedOrRefused` (write tool claiming nothing still lands HIGH/WORLD_PLACE/destructive); GameTest `untrustedMcpWriteToolDeniedThroughGateway` (POLICY_DENIED through the real pipeline); GameTest `untrustedPrivilegedExampleToolDenied` for mod-supplied hints | ✅ |
| 12 | MCP result prompt injection | ✅ closed in M4: results are stored VERBATIM as data (`McpClientBridge.onRemoteOutcome`), never parsed as instructions; permissions/trust/owner untouched by any result content. Unit `McpStackTest.injectionPayloadStaysVerbatimInertData` + GameTest `injectionResultCannotChangePermissions` (worldedit stays off, nodes ungranted, killswitch intact after an InjectionMcp SYSTEM-OVERRIDE payload) | ✅ |
| 13 | Oversized MCP schema | ✅ closed in M4: `McpToolImporter.build` refuses >32 properties / invalid JSON schema (whole tool refused, import reported); description truncated at 2000 chars (§58 caps); payloads capped at 65536 chars before entering any result map. Unit `invalidSchemaRefusesTheWholeTool`, `hugePayloadIsCappedBeforeEnteringResults`; GameTest oversized-schema refusal assertion in `untrustedMcpWriteToolDeniedThroughGateway` | ✅ |
| 14 | LLM malformed function | Protocol layer rejects malformed JSON/tool calls before the gateway; audit records `malformed_model_output` | GameTest `malformedModelOutputNeverExecutes` (M2) | ✅ |
| 15 | LLM unavailable | Degraded mode: FastPath continues, no provider crash | GameTest `conversationPathsNoLlmDegradesAndMockCompletesAsync` (M1/M2) | ✅ |

## Killswitch / undo (M3 DoD additions beyond §82)

- **Killswitch 1-tick effect**: GameTest `killswitchTakesEffectOnTheSameTick` —
  activation tick shows active flag, paused scheduler, all tasks CANCELLED, write
  denied `POLICY_DENIED`, zero blocks changed.
- **Undo exactness**: GameTest `undoRestoresBlockStateAndChestContents` — fill over a
  chest with items; undo restores BlockState AND BlockEntity NBT (5 diamonds return).
- **Audit persistence**: unit `AuditPersistenceTest` — JSONL append, flush cursor
  never duplicates.

## Summary

- §82 cases: **15/15 ✅** (12 closed at M3, 3 MCP cases closed by the M4 bridge —
  see the case notes above and `M4-extension-mcp-exit.md`).
