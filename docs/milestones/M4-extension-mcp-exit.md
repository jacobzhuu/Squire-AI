# M4 Exit Report — Extension Platform / MCP Client Bridge

Date: 2026-08-26 · Spec source of truth: `docs/IMPLEMENTATION_SPEC.md` §88 (M4
deliverables + DoD), §53-59, §55, §81, §82, §94

## Delivered

| Component | Implementation | Evidence |
|---|---|---|
| `squire-api` (§58) | `dev.squire.api`: `SquireToolProvider`/`SquireSensorProvider`/`SquireTaskProvider`, `ExternalTool`(+Builder), `ExternalToolResult`(SUCCESS/PARTIAL/RUNNING/FAILED + errorCode), `ToolInvocation`, `ArgSpec`, `TaskTypeSpec`/`Registrar`, `ExternalTaskInvocation`(+Completion handle) | compiled against by the Example provider without touching Core |
| Tool Provider import | `ExtensionManager.registerTool`: duplicate/namespace checks, caps (32 args, 2000-char description, 64 tools/provider), reserved-node refusal, custom-node registration (idempotent), fail-closed classification from hints; refusals become `ImportReport`s — never thrown into provider code | `ExtensionManagerTest` (9 tests) |
| Sensor Provider (§59) | `SensorRegistry`: ≤32 sensors, TreeMap-sorted bounded lines, unhealthy marker, per-sensor exception isolation (`degraded:`) | `SensorRegistryTest` (6 tests); perception appends sensor lines when an agent exists |
| Task Provider | external task bridge: `ext:<type>` tasks, P3_USER_TASK, RetryPolicy.NONE, ≥20-tick timeout floor, verifier-owned completion via bridge-owned outcomes map (scheduler may clobber executionState) | `ExtensionManagerTest.externalTask*`; GameTest `externalTaskTypeRunsToCompletion` |
| Tool Inspector | `/squire admin tools` — exposure/risk/permission/node/capability/trust/origin per tool + import trail | used in M4 gametest diagnostics |
| MCP Client Bridge (§53.1) | async transports (`StdioMcpTransport` process+line daemon, `HttpMcpTransport` JDK HttpClient w/ SSE handling), zero new dependencies; `handleRemoteCall` returns RUNNING same-tick, settles via `orTimeout` → completion executor on server thread; MCP_TIMEOUT vs MCP_PROTOCOL_ERROR vs MCP_DISCONNECTED classification; payload cap 65_536 chars | `McpStackTest` (9 tests incl. <500ms dispatch measurement) |
| MCP Trust Store (§55) | `config/squire/mcp-trust.json`, admin-owned mapping; absent/corrupt/unknown → UNTRUSTED fail-closed | `trustStoreRoundTripAndFailClosedDefaults`, `corruptTrustFileDegradesToUntrusted` |
| MCP import sanitize (§54) | name sanitize `[a-z0-9][a-z0-9_.-]*`, namespace `server:tool`, schema validation (>32 properties / malformed JSON → whole tool refused), description truncation, ≤128 tools/server, annotation→hint classification | `maliciousImportsAreSanitizedOrRefused`, `invalidSchemaRefusesTheWholeTool` |
| Mock MCP (§81) | SafeMcp, MaliciousMcp, SlowMcp, InvalidSchemaMcp, HugePayloadMcp, InjectionMcp in `dev.squire.server.mcp.mock`, driven through the real bridge/importer/gateway | all unit + GameTest coverage below |

## DoD evidence (spec M4)

| Requirement | Status | Proof |
|---|---|---|
| 第三方 Example Mod 可注册 Tool | ✅ | shipped `ExampleSquireProvider` registers tools/sensor/task type through the `"squire"` entrypoint using only `squire-api` types |
| 不修改 Core 即可调用 | ✅ | `example:echo` round-trips gateway→registry→handler with zero core edits: GameTest `exampleModToolCallableWithoutCoreChanges`; custom node gate/regrant cycle: `customNodeGatesExtensionToolUntilGranted` |
| 未信任 MCP write Tool 不可执行 | ✅ | trust gate denies privileged shape for UNTRUSTED/BLOCKED sources: GameTest `untrustedMcpWriteToolDeniedThroughGateway` (POLICY_DENIED, handler never invoked — MaliciousMcp.callTool fails if reached); same clause covers mod-imported write shapes: `untrustedPrivilegedExampleToolDenied` (DANGEROUS_CALLS stays 0) |
| MCP result injection 不改变权限 | ✅ | results stored verbatim as data; no path to permissions/trust/policy: GameTest `injectionResultCannotChangePermissions` (worldedit off, nodes ungranted, killswitch intact after SYSTEM OVERRIDE payload); unit `injectionPayloadStaysVerbatimInertData` |
| MCP disconnect 不崩服 | ✅ | close is tolerant; later calls fail as data: unit `disconnectYieldsImmediateDataLevelFailureNotCrash`; GameTest post-dispatch disconnect → immediate FAILED "not connected" in `mcpTimeoutNeverBlocksThreadAndDisconnectDegrades` |
| MCP timeout 不阻塞主线程 | ✅ | RUNNING returned on the SAME tick (GameTest asserts identical tick before/after dispatch); settlement proven at unit level with 1s timeout (<500ms dispatch, then MCP_TIMEOUT): `remoteCallReturnsRunningImmediatelyThenTimesOut`. Gametest uses deterministic future settlement because the gametest server ticks ≫ wall-clock 20tps — see ADR-027 |

**Verification:** 39/39 GameTests green, 113/113 unit tests green
(`build test runGametest` full chain). Three consecutive green full-chain runs
recorded 2026-08-26 at 13:36, 13:42 and 13:43 — each "All 39 required tests
passed", exit code 0.

## Architecture prohibitions upheld

- 不允许 LLM 直接执行 Raw Command — unchanged; extension/MCP tools go through the
  same typed pipeline.
- 不允许绕过 Tool Gateway — MCP tools are imported INTO the registry and dispatched
  only via the gateway's ten-stage pipeline; there is no direct transport call site
  reachable from agent code.
- 不允许网络 I/O 阻塞 Minecraft Server Thread — structural: transports are fully
  async; handler returns RUNNING same-tick (measured <500ms; gametest asserts
  same-tick).
- 不允许让 LLM 决定任务是否真正完成 — external tasks complete only through the
  runtime verifier reading bridge-owned outcomes.
- 不允许将 MCP 当作权限系统 — trust comes only from the admin-owned Trust Store;
  results/hints can never raise privileges (fail-closed classification).
- 不允许跳过 Permission / Policy / Capability / Validation — imported tools pass
  schema → exposure → capability set → node → owner → context → policy(trust gate)
  → confirmation → quota → capability like every other tool.
- 不允许把复杂任务简单等同于命令方块 — unchanged (explicit CBP materialization is
  M5's deliberate product artifact).

## ADRs added

- ADR-026 extension platform trust model and import caps
- ADR-027 MCP calls are async, data-only, settled off the server thread
  (includes the wall-clock-vs-game-tick testing constraint)

## §82 checklist closure

All 15 security cases now ✅ — the three MCP cases deferred at M3
(annotation spoofing, result prompt injection, oversized schema) are closed by the
tests above; see updated `M3-security-checklist-mapping.md`.

## Known simplifications / notes

- MCP module remains default-off in practice: nothing connects until an admin
  configures a transport registration point; discovery code paths are exercised by
  tests. HTTP allowlists (scheme/host/maxBody) are enforced inside the transport;
  a config-file-driven server list arrives with the M6 release surface.
- The stdio transport caps tool lists at 128 servers/tools and reads one JSON-RPC
  object per line (newline-delimited framing) — sufficient for the mock kit and
  standard line-framed servers; Content-Length framing is not implemented.
- External task types do not retry (RetryPolicy.NONE): the provider owns retries,
  keeping completion authority with the verifier.
- Sensor output shares the perception budget and is truncated with an ellipsis
  marker; sensors cannot enlarge their own budget.
