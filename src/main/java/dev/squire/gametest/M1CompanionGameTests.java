package dev.squire.gametest;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import com.mojang.authlib.GameProfile;

import dev.squire.api.provider.LlmProvider;
import dev.squire.api.provider.ProviderCapabilities;
import dev.squire.common.protocol.AgentRequest;
import dev.squire.common.protocol.AgentResponse;
import dev.squire.server.input.InputGateway;
import dev.squire.server.provider.ProviderRegistry;
import dev.squire.server.registry.SquireEntities;
import dev.squire.server.runtime.SquireRuntime;
import dev.squire.server.body.avatar.AvatarEntity;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * M1 Playable Companion GameTests: summon/dismiss lifecycle, FastPath chat routing,
 * role checks (OWNER vs PUBLIC vs ADMIN), home navigation, restart recovery,
 * and LLM-unavailable / mock-provider conversation paths.
 *
 * <p>Each test uses a DISTINCT fake-owner uuid so the shared runtime's registry never
 * leaks agents between tests.</p>
 */
public final class M1CompanionGameTests implements FabricGameTest {
	public static final String FLOOR = M0SpikeGameTests.FLOOR;

	private static FakePlayer fakeOwner(ServerWorld world, String name) {
		return FakePlayer.get(world,
			new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name));
	}

	/** Entity has no (Vec3d, yaw, pitch) overload in yarn 1.20.1. */
	private static void place(net.minecraft.entity.Entity entity, Vec3d feetCenter) {
		entity.refreshPositionAndAngles(feetCenter.x, feetCenter.y, feetCenter.z, 0.0f, 0.0f);
	}

	/**
	 * Regression: summoning while staring straight down used the full rotation
	 * vector for the spawn offset and buried the body inside the ground. The
	 * placement must be horizontal-only and snapped to a standable column.
	 */
	@GameTest(templateName = FLOOR)
	public void summonLookingDownNeverBuriesTheBody(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "look-down-owner");
		place(owner, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 4))));
		owner.setYaw(0.0f);
		owner.setPitch(-89.9f); // crosshair straight into the floor

		context.runAtTick(5, () -> {
			AvatarEntity avatar = rt.summonFor(owner);
			BlockPos feet = avatar.getBlockPos();
			boolean bodyClear =
				world.getBlockState(feet).isAir()
					&& world.getBlockState(feet.up()).isAir();
			boolean onGround =
				world.getBlockState(feet.down()).isSolidBlock(world, feet.down());
			context.assertTrue(bodyClear,
				"body must not spawn inside solid blocks at " + feet);
			context.assertTrue(onGround,
				"avatar must stand ON ground, not float or buried, at " + feet);
			rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
	}

	private static SquireRuntime runtime(TestContext context) {
		SquireRuntime.ensureInitialized(context.getWorld().getServer());
		return SquireRuntime.get();
	}

	// ------------------------------------------------------------------ lifecycle + roles

	@GameTest(templateName = FLOOR)
	public void summonStatusDismissLifecycle(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "lifecycle-owner");
		place(owner, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 4))));

		context.runAtTick(5, () -> {
			// before summon: control intents report the absence clearly
			var before = rt.executeControl(owner, SquireRuntime.ControlIntent.STATUS);
			context.assertTrue(!before.success(), "control before summon must fail");

			AvatarEntity avatar = rt.summonFor(owner);
			context.assertTrue(avatar.isAlive(), "summoned avatar alive");
			context.assertTrue(rt.agents().resolveForOwner(owner.getUuid()).isPresent(),
				"registry must index the summoned avatar");

			var status = rt.executeControl(owner, SquireRuntime.ControlIntent.STAY);
			context.assertTrue(status.success(), "owner STAY succeeds");
			context.assertTrue(avatar.mode() == AvatarEntity.MovementMode.STAY,
				"mode must switch to STAY");

			var dismissed = rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.assertTrue(dismissed.success(), "owner DISMISS succeeds");
			context.assertTrue(!avatar.isAlive(), "dismissed avatar discarded");
			context.assertTrue(rt.agents().resolveForOwner(owner.getUuid()).isEmpty(),
				"registry must drop the dismissed avatar");
			context.complete();
		});
	}

	/** Fabric encourages FakePlayer subclassing for controlled behavior (op-level 4). */
	private static final class AdminFakePlayer extends FakePlayer {
		AdminFakePlayer(ServerWorld world, GameProfile profile) {
			super(world, profile);
		}

		@Override
		protected int getPermissionLevel() {
			return 4;
		}
	}

	@GameTest(templateName = FLOOR)
	public void roleResolutionEnforcesOwnership(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "role-owner");
		FakePlayer stranger = fakeOwner(world, "role-stranger");
		FakePlayer adminLike = new AdminFakePlayer(world,
			new GameProfile(UUID.nameUUIDFromBytes("role-admin".getBytes()), "role-admin"));

		context.runAtTick(5, () -> {
			AvatarEntity avatar = rt.summonFor(owner);

			context.assertTrue(rt.resolveRole(owner, avatar) == SquireRuntime.SenderRole.OWNER,
				"creator must be OWNER");
			context.assertTrue(rt.resolveRole(stranger, avatar) == SquireRuntime.SenderRole.PUBLIC,
				"unrelated sender must be PUBLIC");

			boolean adminSeen = rt.resolveRole(adminLike, avatar) == SquireRuntime.SenderRole.ADMIN;
			context.assertTrue(adminSeen, "op level >= 2 must map to ADMIN");

			// pin a known mode first (首次召唤默认 FOLLOW，方案 A2), then verify a
			// stranger's control intent can neither move nor re-mode someone else's body
			rt.executeControl(owner, SquireRuntime.ControlIntent.STAY);
			var strangerResult = rt.executeControl(stranger, SquireRuntime.ControlIntent.FOLLOW);
			context.assertTrue(!strangerResult.success(),
				"stranger without own squire gets no write access");
			context.assertTrue(avatar.mode() == AvatarEntity.MovementMode.STAY,
				"someone else's chat must not flip MY avatar into follow");
			context.complete();
		});
	}

	// ------------------------------------------------------------------ FastPath chat

	@GameTest(templateName = FLOOR)
	public void fastPathChatDrivesModesWithoutLlm(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "fastpath-owner");

		context.runAtTick(5, () -> {
			AvatarEntity avatar = rt.summonFor(owner);

			InputGateway.acceptChat(owner, "跟着我");
			context.assertTrue(avatar.mode() == AvatarEntity.MovementMode.FOLLOW,
				"'跟着我' must route through FastPath to FOLLOW");

			InputGateway.acceptChat(owner, "待在这里");
			context.assertTrue(avatar.mode() == AvatarEntity.MovementMode.STAY,
				"'待在这里' must route to STAY");
			context.assertTrue(avatar.stayPos().isPresent(), "STAY records anchor position");

			InputGateway.acceptChat(owner, "停止");
			context.assertTrue(avatar.mode() == AvatarEntity.MovementMode.IDLE,
				"'停止' must route to STOP/IDLE");

			// english phrase table parity
			InputGateway.acceptChat(owner, "follow me!");
			context.assertTrue(avatar.mode() == AvatarEntity.MovementMode.FOLLOW,
				"'follow me!' (case/punctuation tolerant) routes to FOLLOW");
			context.complete();
		});
	}

	/**
	 * 公共聊天频道要求先点名。
	 *
	 * <p>命名牌改完名之后，那个名字必须真的成为"叫得动他"的口令：改名写进档案、
	 * 档案的名字被输入网关读到、名字从正文里摘掉之后短语表仍然命中——中间断一环，
	 * 玩家看到的都是同一件事："我给他起了名字，他不理我"。</p>
	 */
	@GameTest(templateName = FLOOR)
	public void chatOnlyReachesHimWhenHeIsCalledByName(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "addressing-owner");

		context.runAtTick(5, () -> {
			AvatarEntity avatar = rt.summonFor(owner);
			rt.executeControl(owner, SquireRuntime.ControlIntent.STAY);
			context.assertTrue(rt.renameAgent(owner, avatar, "豆包").success(),
				"rename must succeed before the name can be a call sign");
			context.assertTrue("豆包".equals(rt.displayNameOf(avatar)),
				"the panel, the prompt and the gateway all read this one name");

			InputGateway.acceptChat(owner, "跟着我", true);
			context.assertTrue(avatar.mode() == AvatarEntity.MovementMode.STAY,
				"unaddressed chat must not command him — that is the whole point");

			InputGateway.acceptChat(owner, "豆包，跟着我", true);
			context.assertTrue(avatar.mode() == AvatarEntity.MovementMode.FOLLOW,
				"calling him by name must reach FastPath with the name stripped off");
			context.complete();
		});
	}

	@GameTest(templateName = FLOOR, tickLimit = 400)
	public void homeReturnNavigatesToRecordedHome(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "home-owner");
		place(owner, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 4))));

		BlockPos homeRel = new BlockPos(7, 2, 1);
		Vec3d homeAbsBottom = Vec3d.ofBottomCenter(context.getAbsolutePos(homeRel));
		java.util.concurrent.atomic.AtomicReference<AvatarEntity> summoned =
			new java.util.concurrent.atomic.AtomicReference<>();

		context.runAtTick(10, () -> {
			AvatarEntity avatar = rt.summonFor(owner);
			summoned.set(avatar);
			// place the avatar far from the intended home first
			place(avatar, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(1, 2, 1))));
			avatar.setHomePos(context.getAbsolutePos(homeRel));
			context.assertTrue(avatar.isAlive(), "avatar alive before home return");
		});

		context.runAtTick(30, () -> {
			InputGateway.acceptChat(owner, "回家");
			context.assertTrue(summoned.get().mode() == AvatarEntity.MovementMode.IDLE,
				"HOME_RETURN issues a move (runtime-owned), not a follow mode");
		});

		context.forEachRemainingTick(() -> {
			AvatarEntity avatar = summoned.get();
			if (avatar != null && avatar.squaredDistanceTo(homeAbsBottom) < 2.25) {
				context.complete();
			}
		});
	}

	// ------------------------------------------------------------------ recovery

	@GameTest(templateName = FLOOR)
	public void restartRecoveryRebuildsRegistryFromWorlds(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		UUID owner = UUID.nameUUIDFromBytes("recovery-owner".getBytes());

		context.runAtTick(5, () -> {
			AvatarEntity avatar = context.spawnEntity(SquireEntities.AVATAR, new BlockPos(2, 2, 2));
			avatar.setOwner(owner);
			avatar.setHomePos(context.getAbsolutePos(new BlockPos(3, 2, 3)));

			// full persistence round trip, like a chunk unload/reload cycle
			NbtCompound nbt = new NbtCompound();
			avatar.writeNbt(nbt);
			avatar.discard();
			// the restored copy is a NEW runtime entity: drop the vanilla entity uuid so the
			// world never sees two live entities claiming one uuid; agent/owner/home persist
			nbt.remove("UUID");

			AvatarEntity restored = context.spawnEntity(SquireEntities.AVATAR, new BlockPos(6, 2, 6));
			restored.readNbt(nbt);
			context.assertTrue(owner.equals(restored.ownerId()),
				"owner survives persistence round trip");

			// simulated restart: a BRAND-NEW registry rebuilt purely from world entities.
			// We deliberately do NOT call SquireRuntime.init() here: gametests run in
			// parallel on one server and replacing the runtime singleton would orphan
			// every sibling test's captured instance mid-run. The registry rebuild IS
			// the recovery logic under test; SquireRuntime.init merely delegates to it.
			dev.squire.server.agent.AgentRegistry fresh =
				new dev.squire.server.agent.AgentRegistry(world.getServer());
			fresh.rebuildFromWorlds();
			Optional<AvatarEntity> found = fresh.resolveForOwner(owner);
			context.assertTrue(found.isPresent(),
				"after simulated restart the registry must rediscover the avatar");
			context.complete();
		});
	}

	// ------------------------------------------------------------------ conversation paths

	/**
	 * Both conversation modes exercised IN ONE test: the global ProviderRegistry is
	 * shared static state, so this test owns an EXCLUSIVE batch — batches run
	 * sequentially, which keeps its registered provider the active one for the whole
	 * run instead of racing sibling tests' registrations.
	 */
	@GameTest(templateName = FLOOR, tickLimit = 200, batchId = "squire-convo-m1")
	public void conversationPathsNoLlmDegradesAndMockCompletesAsync(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "conversation-owner");
		AtomicBoolean providerInvoked = new AtomicBoolean(false);

		// inline provider: proves generate() runs off-thread and completes asynchronously
		LlmProvider inlineMock = new LlmProvider() {
			@Override public String id() { return "inline-mock"; }

			@Override
			public CompletableFuture<AgentResponse> generate(AgentRequest request) {
				return CompletableFuture.supplyAsync(() -> {
					providerInvoked.set(true);
					if (Thread.currentThread().getName().contains("Server")) {
						throw new AssertionError("generate must not run on the server thread");
					}
					return AgentResponse.of("mock reply");
				});
			}

			@Override public ProviderCapabilities capabilities() {
				return ProviderCapabilities.minimal();
			}

			@Override public CompletableFuture<Boolean> healthCheck() {
				return CompletableFuture.completedFuture(true);
			}
		};

		context.runAtTick(5, () -> {
			AvatarEntity avatar = rt.summonFor(owner);

			// phase 1: LLM-unavailable mode (M1 DoD) — unknown phrases degrade gracefully
			ProviderRegistry.clear();
			AvatarEntity.MovementMode modeBeforeChat = avatar.mode();
			InputGateway.acceptChat(owner, "给我32个火把");
			context.assertTrue(avatar.isAlive(), "chat must never harm the agent");
			context.assertTrue(avatar.mode() == modeBeforeChat,
				"unmatched phrase without provider must not change the agent");

			// phase 2: with a provider, plain text reaches it OFF-thread
			ProviderRegistry.register(inlineMock);
			InputGateway.acceptChat(owner, "tell me a joke");
		});

		context.forEachRemainingTick(() -> {
			if (providerInvoked.get()) {
				ProviderRegistry.clear();
				context.complete();
			}
		});
	}
}
