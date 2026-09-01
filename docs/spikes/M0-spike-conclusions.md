# M0 Technical Verification — Spike Conclusions

> Executed 2026-08-26 against the locked stack in docs/version-matrix/mc-1.20.1.md.
> Each spike is executable: unit tests (`./gradlew test`) or GameTests
> (`./gradlew runGametest`), so conclusions stay verifiable forever.

## M0-01 Project Baseline — PASS

- `./gradlew build` green on first full run after toolchain lock.
- Empirical finding #1: **Fabric Loom 1.11.x requires a Java 21+ Gradle daemon**
  (refuses to configure on a Java 17 daemon). Compile target stays `--release 17`.
  Wrapper distribution URL points at the Tencent mirror because
  services.gradle.org times out for JVM downloads in this network (curl works; the
  wrapper's 10s connect timeout does not — raised to 120s + mirror).
- Empirical finding #2: yarn 1.20.1 names the GameTest annotation field
  **`templateName`** (not `structure`) and `TestContext` has **no** succeed()/fail()/
  waitUntil() — completion uses `complete()` / `throwGameTestException()` with
  `forEachRemainingTick` polling. Verified by javap against the loom-mapped jar.
- JUnit 5.10.2 wired via junit-bom; boundary test enforces ADR-016.

## M0-02 Avatar Spike — PASS

Verified by `M0SpikeGameTests.avatarSpawnsAndNavigates` / `avatarNbtRoundTrip`:
- Custom `PathAwareEntity` ("squire:avatar") registers via
  `FabricEntityTypeBuilder.createMob()` + `FabricDefaultAttributeRegistry`.
- `MobEntity.goalSelector`/`targetSelector` are protected final → subclass access works;
  no access widener needed for our own entity.
- Navigation: `getNavigation().startMovingTo(x, y, z, speed)` finds paths on flat ground;
  arrival verified in-test within tick budget.
- NBT round trip of owner UUID + home position via
  writeCustomDataToNbt/readCustomDataFromNbt.

Renderer: player model (`EntityModelLayers.PLAYER`) registered through
`EntityRendererRegistry`; bundled skin texture required at runtime (placeholder generated).

## M0-03 FakePlayer Proxy Spike — PASS

Verified by `fakePlayerBreakDropsAndDurability`, `fakePlayerPlaceConsumesItem`,
`fakePlayerBreakFailsOnAir`, `fakePlayerBreakWithoutToolYieldsNothing`:
- `FabricFakePlayer.get(ServerWorld)` (NO server parameter) reuses instances per
  (world, profile); its `tick()` is empty — confirms fake players never self-tick,
  validating ADR-004's explicit prepare→act→sync design over packet simulation.
- Loot-authentic breaking: `state.getDroppedStacks(builder)` takes the **Builder**
  (build(BLOCK) happens inside); drops spawn via static `Block.dropStack`.
- **Harvest gate must be replicated manually**: vanilla gates block drops on
  `player.canHarvest(state)` *inside* `ServerPlayerInteractionManager.tryBreakBlock`,
  NOT in the loot table. Driving the loot table directly therefore drops cobblestone
  even for bare hands unless the proxy applies
  `!state.isToolRequired() || tool.isSuitableFor(state)` itself.
- Durability: `ItemStack.damage(1, world.random, null)` respects Unbreaking;
  break-at-max check = `getDamage() >= getMaxDamage()` (no `isBroken()` in 1.20.1).
- Placement: real `BlockItem.place(new ItemPlacementContext(fakePlayer, ...))` succeeds
  onto air/replaceable targets and fires modded placement hooks that expect a PlayerEntity.
- **Placement-context rule (bytecode-verified)**: the constructor computes
  `placementPos = hit.pos.offset(side)` and `canReplaceExisting = world[hit.pos].canReplace`;
  `getBlockPos()` returns `hit.pos` when `canReplaceExisting` else `placementPos`. A ray
  aimed at an AIR cell therefore replaces THAT cell (`AIR.canReplace == true`) — vanilla
  client rays always end on a solid face, so synthetic hits must too. Proxy contract:
  place INTO pos requires solid support at `pos.down()`, hit =
  `(center-of-pos, UP, pos.down())`; unsupported placement fails cleanly (GameTest
  `fakePlayerPlaceFailsWithoutSupport` guards this forever).
- **Navigation targets are feet-level**: pass `Vec3d.ofBottomCenter(blockPos)` (integer Y)
  as the move target. A `.toCenterPos()` Y (+0.5) aims the pathfinder at the air cell
  ABOVE the walkable node; the mob then detours off-platform hunting a route to it and
  idles one block below the goal (observed distSq frozen at 2.34 vs threshold 2.25).
- **GameTest structure .snbt dialect**: fabric loads templates via
  `NbtHelper.fromNbtProviderString`, which expects the **datagen dialect**: block cells
  as `data: [{pos: [x,y,z], state: "minecraft:stone"}, ...]` with STRING block ids and a
  string-list `palette`. The vanilla palette-index structure format (`blocks:[{pos,state:0}]`,
  compound palette) parses WITHOUT ERROR but the palette silently comes back EMPTY
  (`getList(key, COMPOUND_TYPE)` returns an empty list on element-type mismatch) — the
  template then has correct size but zero blocks, and tests silently run on the gametest
  world's own ground instead of your platform. Verified by extracting
  `fabric-gametest-api-v1`'s own `empty.snbt` as the golden reference.
- **Template Y convention (empirical)**: the runner renders the template's bottom row at
  relative **Y=1**, one above the getAbsolutePos anchor. With our template (stone at
  datagen y=0), the walkable surface is relative Y=1 and entities stand in relative Y=2;
  all test coordinates are calibrated to this and guarded by assertions that depend on
  template geometry (a wrong assumption fails immediately).
- **`ActionResult.isAccepted()` means `result != PASS` — FAIL is "accepted"!**
  Success checks must compare against SUCCESS/CONSUME/CONSUME_PARTIAL explicitly.
  Caught by GameTest (`placed=true` while world still air), regression-guarded forever.
- Breaking air returns failure without side effects.

## M0-02b Avatar AI policy note

`WanderAroundFarGoal` removed from AvatarEntity during spike verification: vanilla free
roaming walked the avatar off the test platform before the scripted moveTo tick and,
in gameplay terms, contradicts runtime-owned movement (Follow/Stay semantics — the body
must not move unless commanded). Idle look goals retained.

## M0-04 Chat Spike — PASS (manual + code-level)

- `net.fabricmc.fabric.api.message.v1.ServerMessageEvents.CHAT_MESSAGE` fires with a
  **`SignedMessage`** as first argument (NOT Text) — content extracted via
  `message.getContent().getString()`. This bit us once during compile; regression-guarded
  by InputGateway wiring being compile-checked in every build.
- Envelope construction (sender resolved before any NLP) implemented in
  `server/input/InputGateway`; echo path replaced by FastPath in M1.
- Automated chat injection isn't possible from a dedicated-server gametest (chat events
  require a real client packet); acceptance is manual `/say` observation during
  `runServer` smoke test plus the compile-time contract above.

## M0-05 Command Compiler Spike — PASS

Verified by `typedGiveCommandExecutesThroughBrigadier`:
- `MinecraftServer.getCommandManager().getDispatcher()` parse+execute pipeline works from
  mod code using `server.getCommandSource()` (permission level 4).
- Typed-intent validation rejects selector injection (`@a[...]`) before compilation —
  enforced in the `GiveIntent` compact constructor (regex `[A-Za-z0-9_]{1,16}`).
- Item ids validated against the live registry before compiling.
- Brigadier failure surfaces as COMMAND_PARSE_FAILED outcome object, not an exception
  escaping into the server.

## M0-06 Threading Spike — PASS

Verified by `asyncWorkerResultAppliesOnServerThread`:
- `CompletableFuture.supplyAsync(...).thenAccept(state -> server.execute(...))` pattern:
  worker computes pure data, apply step runs on the server thread (asserted via thread
  name), world mutation visible in-test.
- `AsyncBridge` codifies this as the only sanctioned bridge; daemon worker pool cannot
  keep the JVM alive on shutdown.

## M0-07 MCP Decision

Decision recorded in ADR-015: self-built minimal JSON-RPC adapter (Gson + JDK HttpClient),
no MCP SDK dependency. Transport interfaces land in M4 with MockMCP integration tests;
stdio transport additionally exercised manually per docs/mcp/manual-testing.md (M4).

## Environment notes

- Windows 11 + Git Bash minimal PATH: builds must export JAVA_HOME=jdk-21 explicitly.
- Locale zh-CN compiler messages; all build output reviewed for 错误/error patterns.

## M1 Findings (2026-08-26, verified during Playable Companion milestone)

These were discovered while wiring the runtime/FastPath/provider layer; each is
reflected in committed tests:

- **`ActionResult.isAccepted()` counts FAIL** (`result != PASS`). Proxy placement
  checks `== SUCCESS || CONSUME || CONSUME_PARTIAL` explicitly
  (`FakePlayerInteractionProxy.placeBlock`).
- **Vanilla harvest gating lives in `player.canHarvest(state)`**, not loot tables:
  direct loot-table drops need a manual
  `!state.isToolRequired() || tool.isSuitableFor(state)` gate.
- **`ItemPlacementContext` geometry** (bytecode-verified): `placementPos = hit.pos.offset(side)`;
  AIR cells are replaceable, so placing INTO a position requires aiming the ray at the
  supporting face below it.
- **Navigation targets are feet-level**: aim at `Vec3d.ofBottomCenter(pos)`; centered Y
  makes the pathfinder hunt the air cell above and detour off-platform.
- **Structure .snbt must use the fabric datagen dialect** (string states + string-list
  palette); vanilla compound-palette format silently yields an EMPTY palette through
  `NbtHelper.fromNbtProviderString`. Template bottom row renders at relative Y=1.
- **FakePlayer permission level = 0** in gametest/dev servers:
  `ServerPlayerEntity.getPermissionLevel()` → `server.getPermissionLevel(profile)`, and
  `PlayerManager.addToOperators` entries are not observed through that path for fake
  profiles. Role tests subclass `FakePlayer` overriding `getPermissionLevel()` (fabric's
  documented extension point).
- **Gametest batches interleave on one server thread** — any test mutating shared statics
  (`SquireRuntime.init()`, `ProviderRegistry.clear()`) races other tests' registrations.
  Mitigations: registry resolution falls back to authoritative world scans (ADR-017);
  provider-state-dependent scenarios live in ONE test; distinct fake-owner UUID per test.
- **The gametest world persists between runs** — stale entities from prior runs leak into
  entity scans. `runGametest` now depends on a `Delete` task wiping `build/gametest/world`.
- **Entity has no `(Vec3d, yaw, pitch)` refresh overload** — use the
  `(double x, double y, double z, yaw, pitch)` form.
