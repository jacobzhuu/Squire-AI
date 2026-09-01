package dev.squire.gametest;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import com.mojang.authlib.GameProfile;

import dev.squire.server.automation.AutomationGraph;
import dev.squire.server.automation.AutomationNode;
import dev.squire.server.automation.AutomationTrigger;
import dev.squire.server.runtime.SquireRuntime;
import dev.squire.server.tool.AuditLog;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * M5a Automation GameTests (spec §49, §88-adjacent DoD): a time-triggered
 * automation ("turn the base lights on every night") executes THROUGH the gateway
 * and never materializes hidden command blocks (ADR-009); killswitch/disable freeze
 * it; state survives an engine reload (restart recovery).
 */
public final class M5AutomationGameTests implements FabricGameTest {
	public static final String FLOOR = M0SpikeGameTests.FLOOR;

	private static FakePlayer fakeOwner(ServerWorld world, String name) {
		return FakePlayer.get(world,
			new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name));
	}

	private static SquireRuntime runtime(TestContext context) {
		SquireRuntime.ensureInitialized(context.getWorld().getServer());
		SquireRuntime rt = SquireRuntime.get();
		if (rt.killswitch().isActive()) {
			rt.setKillswitch(false);
		}
		rt.automation().setEnabled(false); // tests opt in explicitly
		return rt;
	}

	private static void pollUntil(TestContext context, BooleanSupplier condition,
			int startTick, int intervalTicks, Runnable onSuccess,
			AtomicBoolean done) {
		if (done.get()) {
			return;
		}
		context.runAtTick(startTick, () -> {
			if (done.get()) {
				return;
			}
			if (condition.getAsBoolean()) {
				done.set(true);
				onSuccess.run();
			} else if (startTick + intervalTicks < 5900) {
				pollUntil(context, condition, startTick + intervalTicks, intervalTicks,
					onSuccess, done);
			}
		});
	}

	private interface Body {
		void run(SquireRuntime rt, ServerWorld world, FakePlayer owner,
				dev.squire.server.body.avatar.AvatarEntity avatar);
	}

	private static void withOwner(TestContext context, int atTick, String name,
			Body body) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, name);
		context.runAtTick(atTick, () -> {
			TestSupport.clearHostilesNear(world,
				context.getAbsolutePos(net.minecraft.util.math.BlockPos.ORIGIN), 48.0);
			world.spawnEntity(owner);
			var avatar = rt.summonFor(owner);
			avatar.setStayMode();
			body.run(rt, world, owner, avatar);
		});
	}

	private static void assertEquals(Object expected, Object actual, String message) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(
				message + " (expected=" + expected + ", actual=" + actual + ")");
		}
	}

	private static boolean fired(SquireRuntime rt, String graphName, String marker) {
		return countAudit(rt, graphName, marker) > 0;
	}

	private static int countAudit(SquireRuntime rt, String graphName, String marker) {
		int n = 0;
		for (AuditLog.Entry e : rt.gateway().audit().snapshot()) {
			boolean ours = e.toolName().startsWith(graphName + "/")
				|| e.toolName().startsWith(graphName + "->");
			if ("automation".equals(e.callerKind()) && ours
					&& e.toolName().contains(marker)) {
				n++;
			}
		}
		return n;
	}

	// ------------------------------------- §89: night lights WITHOUT command blocks

	@GameTest(templateName = FLOOR, tickLimit = 600, batchId = "squire-m5-night")
	public void nightLightsAutomationRunsThroughGatewayWithoutCommandBlocks(
			TestContext context) {
		withOwner(context, 5, "m5-night-owner", (rt, world, owner, avatar) -> {
			rt.automation().setEnabled(true);
			var builder = AutomationGraph.builder(owner.getUuid(), avatar.agentId(),
				"night-lights-" + System.identityHashCode(context))
				.trigger(AutomationTrigger.atTimeOfDay(13000, 600));
			UUID call = builder.node(AutomationNode.toolCall("example:echo",
				Map.of("message", "lights-on")));
			builder.edge(call, builder.node(AutomationNode.notify("lights done")));
			AutomationGraph graph = rt.automation().create(builder.build(),
				world.getTime());
			assertTrue(graph != null, "graph created");

			// ADR-009 baseline: how many command blocks exist BEFORE we run anything
			// (the gametest harness itself plants some) — the property is DELTA == 0.
			BlockPos origin = avatar.getBlockPos();
			int cbBefore = countCommandBlocks(world, origin);

			world.setTimeOfDay(12990); // just before the window
			AtomicBoolean done = new AtomicBoolean(false);
			pollUntil(context,
				() -> fired(rt, graph.name(), "->example:echo"),
				30, 10, () -> done.set(true), done);

			context.runAtTick(300, () -> {
				StringBuilder diag = new StringBuilder("enabled=")
					.append(rt.automation().isEnabled())
					.append(" graphs=").append(rt.automation().all().size())
					.append(" worldDayTime=").append(world.getTimeOfDay());
				for (var gg : rt.automation().all()) {
					diag.append(" || ").append(gg.name())
						.append(" state=").append(gg.state())
						.append(" cursor=").append(gg.currentNodeId())
						.append(" lastDay=").append(gg.lastFiredDay())
						.append(" trigger=").append(gg.trigger());
				}
				assertTrue(done.get(),
					"time trigger never fired through the gateway; " + diag);
				assertEquals(cbBefore, countCommandBlocks(world, origin),
					"automation must not place hidden command blocks");
				rt.automation().remove(graph.id(), owner.getUuid(), false);
				rt.automation().setEnabled(false);
				// restore daylight: this test forced night globally, and a permanent
				// night world would let mobs harass every OTHER structure's agents
				world.setTimeOfDay(1000);
				context.complete();
			});
		});
	}

	private static int countCommandBlocks(ServerWorld world, BlockPos center) {
		int n = 0;
		for (BlockPos pos : BlockPos.iterate(center.add(-16, -8, -16),
				center.add(16, 8, 16))) {
			if (world.getBlockState(pos).isOf(Blocks.COMMAND_BLOCK)
					|| world.getBlockState(pos).isOf(Blocks.CHAIN_COMMAND_BLOCK)
					|| world.getBlockState(pos).isOf(Blocks.REPEATING_COMMAND_BLOCK)) {
				n++;
			}
		}
		return n;
	}

	// ------------------------------------------- §63/§94: freezes + recovery

	@GameTest(templateName = FLOOR, tickLimit = 700, batchId = "squire-m5-freeze")
	public void killswitchDisableAndRecoveryFreezeCorrectly(TestContext context) {
		withOwner(context, 5, "m5-freeze-owner", (rt, world, owner, avatar) -> {
			rt.automation().setEnabled(true);
			var builder = AutomationGraph.builder(owner.getUuid(), avatar.agentId(),
				"heartbeat").trigger(AutomationTrigger.every(40));
			UUID note = builder.node(AutomationNode.notify("beat"));
			builder.edge(note, builder.node(AutomationNode.waitTicks(1)));
			AutomationGraph graph = rt.automation().create(builder.build(),
				world.getTime());

			AtomicBoolean firstFired = new AtomicBoolean(false);
			pollUntil(context, () -> fired(rt, graph.name(), "/fire"),
				30, 10, () -> firstFired.set(true), firstFired);

			context.runAtTick(200, () -> {
				assertTrue(firstFired.get(), "interval trigger never fired");
				// killswitch freezes long-term automation too (§63): no new fires
				// across the ~80-tick frozen window that follows
				int firesBefore = countAudit(rt, graph.name(), "/fire");
				rt.setKillswitch(true);
				context.runAtTick(280, () -> {
					assertEquals(firesBefore,
						countAudit(rt, graph.name(), "/fire"),
						"killswitch must freeze automation firing");
					// pause + save + reload = in-place restart recovery (§49)
					assertTrue(rt.automation()
						.pause(graph.id(), owner.getUuid(), false).ok(),
						"pause by owner");
					rt.automation().save();
					assertEquals(1, rt.automation().load(world.getTime()),
						"recovery restores exactly the paused graph");
					assertEquals(AutomationGraph.State.PAUSED,
						rt.automation().get(graph.id()).orElseThrow().state(),
						"PAUSED stays paused across recovery");
					assertTrue(rt.automation()
						.remove(graph.id(), owner.getUuid(), false).ok(),
						"owner removes the recovered graph");
					rt.automation().setEnabled(false);
					rt.setKillswitch(false);
					context.complete();
				});
			});
		});
	}

	private static void assertFalse(boolean value, String message) {
		if (value) {
			throw new AssertionError(message);
		}
	}

	private static void assertTrue(boolean value, String message) {
		if (!value) {
			throw new AssertionError(message);
		}
	}
}
