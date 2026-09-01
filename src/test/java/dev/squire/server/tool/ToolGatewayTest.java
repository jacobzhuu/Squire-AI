package dev.squire.server.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import dev.squire.api.body.AgentBody;
import dev.squire.api.body.BodyCapabilities;
import dev.squire.api.body.AgentPhysicalState;
import dev.squire.api.body.EmoteType;
import dev.squire.api.body.InventoryView;
import dev.squire.api.body.MoveHandle;
import dev.squire.api.body.MoveOptions;
import dev.squire.api.body.TargetPosition;
import dev.squire.common.errors.ErrorCode;
import dev.squire.common.protocol.ToolCall;
import dev.squire.common.protocol.ToolResult;

/**
 * Gateway pipeline unit tests (spec section 27). Rejection stages run without any
 * Minecraft object; the full world-facing happy path is covered by M2 GameTests.
 */
class ToolGatewayTest {

	private final AtomicInteger executed = new AtomicInteger();

	private ToolRegistry registryWithPing() {
		ToolRegistry registry = new ToolRegistry();
		ToolDefinition def = ToolDefinition.builder("test.ping")
			.description("ping test tool")
			.arg(ArgDefinition.required("target", ArgDefinition.ArgType.STRING, "who"))
			.arg(ArgDefinition.optional("count", ArgDefinition.ArgType.INT, "n"))
			.permission(AgentPermission.QUERY)
			.risk(RiskLevel.LOW)
			.exposure(ToolExposure.MODEL_PUBLIC)
			.build();
		registry.register(def, (call, ctx) -> {
			executed.incrementAndGet();
			return ToolResult.success(call.callId(), Map.of("echo", call.arguments().get("target")));
		});
		return registry;
	}

	private static CallerIdentity model() {
		return CallerIdentity.model(UUID.randomUUID(), UUID.randomUUID());
	}

	private static ToolCall call(String tool, Map<String, Object> args) {
		return new ToolCall(UUID.randomUUID(), tool, args);
	}

	private static ToolExecutionContext context() {
		AgentBody body = new AgentBody() {
			@Override
			public UUID agentId() {
				return UUID.randomUUID();
			}

			@Override
			public UUID ownerId() {
				return UUID.randomUUID();
			}

			@Override
			public BodyCapabilities capabilities() {
				return null;
			}

			@Override
			public AgentPhysicalState snapshotState() {
				return null;
			}

			@Override
			public MoveHandle moveTo(TargetPosition target, MoveOptions options) {
				return null;
			}

			@Override
			public void stopMoving() {
			}

			@Override
			public InventoryView inventory() {
				return null;
			}

			@Override
			public void lookAt(TargetPosition target) {
			}

			@Override
			public void emote(EmoteType type) {
			}

			@Override
			public boolean alive() {
				return true;
			}
		};
		return new ToolExecutionContext() {
			@Override
			public AgentBody body() {
				return body;
			}

			@Override
			public dev.squire.server.body.avatar.AvatarEntity avatar() {
				throw new UnsupportedOperationException("unit test never touches the entity");
			}

			@Override
			public net.minecraft.server.MinecraftServer server() {
				throw new UnsupportedOperationException();
			}

			@Override
			public UUID requesterId() {
				return body.ownerId();
			}

			@Override
			public long tick() {
				return 123L;
			}
		};
	}

	private static ToolResult dispatch(ToolGateway gw, ToolRegistry reg, ToolCall call,
			CallerIdentity caller, boolean granted) {
		return gw.dispatch(call, caller, context(), p -> granted);
	}

	@Test
	void unknownToolIsRejectedWithoutExecution() {
		ToolGateway gw = new ToolGateway(registryWithPing());
		ToolResult r = dispatch(gw, null, call("no.such", Map.of()), model(), true);
		assertEquals(ToolResult.Status.FAILED, r.status());
		assertEquals(ErrorCode.TOOL_NOT_FOUND, r.errorOrNull().orElseThrow().code());
		assertEquals(0, executed.get());
	}

	@Test
	void missingRequiredArgumentIsRejected() {
		ToolGateway gw = new ToolGateway(registryWithPing());
		ToolResult r = dispatch(gw, null, call("test.ping", Map.of()), model(), true);
		assertEquals(ErrorCode.INVALID_ARGUMENT, r.errorOrNull().orElseThrow().code());
		assertEquals(0, executed.get());
	}

	@Test
	void unknownArgumentIsRejected() {
		ToolGateway gw = new ToolGateway(registryWithPing());
		ToolResult r = dispatch(gw, null,
			call("test.ping", Map.of("target", "x", "evil", 1)), model(), true);
		assertEquals(ErrorCode.INVALID_ARGUMENT, r.errorOrNull().orElseThrow().code());
		assertEquals(0, executed.get());
	}

	@Test
	void wrongTypeRejected() {
		ToolGateway gw = new ToolGateway(registryWithPing());
		ToolResult r = dispatch(gw, null,
			call("test.ping", Map.of("target", "x", "count", "many")), model(), true);
		assertEquals(ErrorCode.INVALID_ARGUMENT, r.errorOrNull().orElseThrow().code());
		assertEquals(0, executed.get());
	}

	@Test
	void modelCannotCallPlannerInternalTool() {
		ToolRegistry reg = registryWithPing();
		reg.register(ToolDefinition.builder("internal.plan").exposure(ToolExposure.PLANNER_INTERNAL)
			.build(), (c, x) -> ToolResult.success(c.callId(), Map.of()));
		ToolGateway gw = new ToolGateway(reg);
		ToolResult r = dispatch(gw, reg, call("internal.plan", Map.of()), model(), true);
		assertEquals(ErrorCode.TOOL_NOT_VISIBLE, r.errorOrNull().orElseThrow().code());
		assertEquals(0, executed.get());
	}

	@Test
	void capabilityDeniedStopsBeforeContextUse() {
		ToolGateway gw = new ToolGateway(registryWithPing());
		ToolResult r = dispatch(gw, null, call("test.ping", Map.of("target", "x")), model(), false);
		assertEquals(ErrorCode.PERMISSION_DENIED, r.errorOrNull().orElseThrow().code());
		assertEquals(0, executed.get());
	}

	@Test
	void malformedCallNeverReachesLookup() {
		ToolGateway gw = new ToolGateway(registryWithPing());
		ToolResult r = gw.dispatch(null, model(), null, p -> true);
		assertEquals(ErrorCode.MALFORMED_MODEL_OUTPUT, r.errorOrNull().orElseThrow().code());
	}

	@Test
	void highRiskModelCallNeedsConfirmation() {
		ToolRegistry reg = registryWithPing();
		reg.register(ToolDefinition.builder("world.danger").risk(RiskLevel.HIGH)
			.permission(AgentPermission.WORLD_BREAK).build(),
			(c, x) -> ToolResult.success(c.callId(), Map.of()));
		ToolGateway gw = new ToolGateway(reg);
		ToolResult r = dispatch(gw, reg, call("world.danger", Map.of()),
			CallerIdentity.model(UUID.randomUUID(), UUID.randomUUID()), true);
		assertEquals(ToolResult.Status.BLOCKED, r.status());
		assertEquals(ErrorCode.CONFIRMATION_REQUIRED, r.errorOrNull().orElseThrow().code());
	}

	@Test
	void explicitFastPathUsesTheGatewayButNeedsNoSecondConfirmation() {
		ToolRegistry reg = new ToolRegistry();
		AtomicInteger calls = new AtomicInteger();
		reg.register(ToolDefinition.builder("player.teleport.test")
			.permission(AgentPermission.COMMAND)
			.node("squire.command.teleport")
			.risk(RiskLevel.HIGH)
			.exposure(ToolExposure.MODEL_PUBLIC)
			.build(), (c, x) -> {
				calls.incrementAndGet();
				return ToolResult.success(c.callId(), Map.of());
			});
		ToolGateway gw = new ToolGateway(reg);
		UUID owner = UUID.randomUUID();
		UUID agent = UUID.randomUUID();
		CallerIdentity fastPath = CallerIdentity.fastPath(owner, agent);

		gw.setNodeChecker((player, node) -> false);
		ToolResult denied = dispatch(gw, reg,
			call("player.teleport.test", Map.of()), fastPath, true);
		assertEquals(ErrorCode.PERMISSION_DENIED,
			denied.errorOrNull().orElseThrow().code());
		assertEquals(0, calls.get(), "permission denial must stop before execution");

		gw.setNodeChecker((player, node) -> true);
		gw.setOwnerVerifier((player, squire) -> player.equals(owner)
			&& squire.equals(agent));
		ToolResult allowed = dispatch(gw, reg,
			call("player.teleport.test", Map.of()), fastPath, true);
		assertTrue(allowed.isSuccess(),
			"the explicit continuation utterance is the owner confirmation");
		assertEquals(1, calls.get());
	}

	@Test
	void adminCallerMayRunHighRiskWithoutConfirmation() {
		ToolRegistry reg = registryWithPing();
		reg.register(ToolDefinition.builder("world.danger").risk(RiskLevel.HIGH)
			.permission(AgentPermission.WORLD_BREAK).build(),
			(c, x) -> ToolResult.success(c.callId(), Map.of()));
		ToolGateway gw = new ToolGateway(reg);
		UUID id = UUID.randomUUID();
		ToolResult r = gw.dispatch(call("world.danger", Map.of()),
			new CallerIdentity(id, id, CallerIdentity.CallerKind.ADMIN), context(), p -> true);
		assertTrue(r.isSuccess());
	}

	@Test
	void policyGateCanRefuseAndIsAudited() {
		ToolGateway gw = new ToolGateway(registryWithPing());
		gw.setPolicyGate((def, caller, ctx) -> dev.squire.common.errors.ErrorPayload
			.of(ErrorCode.POLICY_DENIED, "nighttime"));
		ToolResult r = dispatch(gw, null, call("test.ping", Map.of("target", "x")), model(), true);
		assertEquals(ErrorCode.POLICY_DENIED, r.errorOrNull().orElseThrow().code());
		assertEquals(1, gw.audit().size());
		assertFalse(gw.audit().snapshot().get(0).executed());
		assertEquals("policy_denied", gw.audit().snapshot().get(0).outcomeCode());
	}

	@Test
	void happyPathExecutesAuditsAndCountsLoopBudget() {
		ToolGateway gw = new ToolGateway(registryWithPing());
		gw.newTurn();
		for (int i = 0; i < LoopProtector.MAX_EQUIVALENT_CALLS; i++) {
			ToolResult r = dispatch(gw, null, call("test.ping", Map.of("target", "same")),
				model(), true);
			assertTrue(r.isSuccess(), "call " + i + " should pass");
		}
		// one more identical call trips equivalent-call protection
		ToolResult r = dispatch(gw, null, call("test.ping", Map.of("target", "same")),
			model(), true);
		assertEquals(ErrorCode.LOOP_DETECTED, r.errorOrNull().orElseThrow().code());
		// but different arguments are still allowed
		assertTrue(dispatch(gw, null, call("test.ping", Map.of("target", "other")),
			model(), true).isSuccess());
		assertEquals(LoopProtector.MAX_EQUIVALENT_CALLS + 1, executed.get());
		// every attempt audited: successes + the loop rejection
		long executedEntries = gw.audit().snapshot().stream()
			.filter(AuditLog.Entry::executed).count();
		assertEquals(LoopProtector.MAX_EQUIVALENT_CALLS + 1, executedEntries);
	}

	@Test
	void newTurnRestoresBudget() {
		ToolGateway gw = new ToolGateway(registryWithPing());
		for (int i = 0; i < LoopProtector.MAX_EQUIVALENT_CALLS; i++) {
			dispatch(gw, null, call("test.ping", Map.of("target", "t")), model(), true);
		}
		gw.newTurn();
		assertTrue(dispatch(gw, null, call("test.ping", Map.of("target", "t")),
			model(), true).isSuccess());
	}

	@Test
	void handlerExceptionBecomesInternalErrorNotCrash() {
		ToolRegistry reg = new ToolRegistry();
		reg.register(ToolDefinition.builder("boom").build(),
			(c, x) -> {
				throw new IllegalStateException("kaboom");
			});
		ToolGateway gw = new ToolGateway(reg);
		ToolResult r = gw.dispatch(call("boom", Map.of()), CallerIdentity.planner(UUID.randomUUID()),
			context(), p -> true);
		assertEquals(ErrorCode.INTERNAL_ERROR, r.errorOrNull().orElseThrow().code());
	}

	@Test
	void duplicateRegistrationThrows() {
		ToolRegistry reg = registryWithPing();
		reg.register(ToolDefinition.builder("dupe").build(), (c, x) -> null);
		org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
			() -> reg.register(ToolDefinition.builder("dupe").build(), (c, x) -> null));
	}

	@Test
	void descriptorDerivationMapsTypes() {
		ToolDefinition def = ToolDefinition.builder("demo")
			.arg(ArgDefinition.intRange("n", 1, 64, "count"))
			.arg(ArgDefinition.required("id", ArgDefinition.ArgType.ITEM_ID, "item"))
			.build();
		var d = def.descriptor();
		assertEquals("integer", d.parameters().get(0).type());
		assertEquals(1.0, d.parameters().get(0).minimum().doubleValue());
		assertTrue(d.parameters().get(0).required());
		assertEquals("string", d.parameters().get(1).type());
	}
}
