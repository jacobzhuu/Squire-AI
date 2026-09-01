package dev.squire.gametest;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import dev.squire.server.blueprint.Blueprint;
import dev.squire.server.blueprint.BlueprintStep;
import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.profile.AutonomyLevel;
import dev.squire.server.project.Project;
import dev.squire.server.project.Stage;
import dev.squire.server.runtime.SquireRuntime;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * 第 3 期验收：一句大目标拆成六个阶段，而且<b>卡在谁身上是说得出来的</b>。
 *
 * <p>最要紧的一条断言在交料阶段：它必须 BLOCKED 并列出还缺什么。一个只显示
 * 「进行中」的工程页，和没有这一页是一样的。</p>
 */
public final class M16ProjectGameTests implements FabricGameTest {
	public static final String FLOOR = M0SpikeGameTests.FLOOR;

	/** 一份小到能在 9×4×9 测试场地里跑完的工程蓝图：2×2×2 石壳，掏空一格。 */
	private static final String TINY = "gametest_project_hut";
	private static final String LIGHTING = "gametest_project_lighting";

	private static void registerTiny(SquireRuntime rt) {
		rt.blueprints().registry().register(new Blueprint(TINY, "测试小屋", 1,
			Blueprint.Category.SHELTER, 2, 2, 2,
			List.of(BlueprintStep.place(0, 0, 0, 0, 1, 1, 1, "minecraft:cobblestone",
					"壳", false),
				BlueprintStep.dig(1, 1, 1, 1, 1, 1, 1, "掏空")),
			Set.of()));
	}

	/** A two-cell-high room reproduces the old "first candidate cannot hold a torch" bug. */
	private static void registerLightingRoom(SquireRuntime rt) {
		rt.blueprints().registry().register(new Blueprint(LIGHTING, "测试照明房", 1,
			Blueprint.Category.SHELTER, 3, 3, 3,
			List.of(
				BlueprintStep.place(0, 0, 0, 0, 2, 0, 2,
					"minecraft:cobblestone", "地板", false),
				BlueprintStep.dig(1, 1, 1, 1, 1, 2, 1, "室内空气")),
			Set.of()));
	}

	private static FakePlayer fakeOwner(ServerWorld world, String name) {
		return FakePlayer.get(world,
			new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name));
	}

	private static SquireRuntime runtime(TestContext context) {
		SquireRuntime.ensureInitialized(context.getWorld().getServer());
		return SquireRuntime.get();
	}

	/**
	 * 主人在 (1,2,1)，伙伴站得远一点——备料阶段把东西扔在玩家脚下，
	 * 两人叠在一格时伙伴的自动拾取会把它们当场捡走，
	 * 交料那一步就永远验不到了。
	 */
	private static AvatarEntity summon(TestContext context, SquireRuntime rt,
			FakePlayer owner, BlockPos relative) {
		ServerWorld world = context.getWorld();
		world.spawnEntity(owner);
		Vec3d ownerFeet = Vec3d.ofBottomCenter(context.getAbsolutePos(
			new BlockPos(1, 2, 1)));
		owner.refreshPositionAndAngles(ownerFeet.x, ownerFeet.y, ownerFeet.z, 0.0f, 0.0f);
		AvatarEntity avatar = rt.summonFor(owner);
		Vec3d feet = Vec3d.ofBottomCenter(context.getAbsolutePos(relative));
		avatar.refreshPositionAndAngles(feet.x, feet.y, feet.z, 0.0f, 0.0f);
		avatar.setIdleMode();
		return avatar;
	}

	/**
	 * 把伙伴调到<b>保守</b>档——只有那一档才走「材料先给玩家、再由玩家转交」。
	 * 默认的标准档他自己领料。
	 */
	private static void handOverMode(SquireRuntime rt, AvatarEntity avatar) {
		rt.profileOf(avatar).autonomy = AutonomyLevel.CONSERVATIVE.id();
	}

	private static void cleanUp(SquireRuntime rt, FakePlayer owner) {
		rt.projects().activeOf(owner.getUuid())
			.ifPresent(project -> rt.projects().cancel(project));
		rt.blueprints().activeOf(owner.getUuid())
			.ifPresent(placement -> rt.blueprints().remove(placement.placementId));
		rt.resolveAvatarFor(owner.getUuid()).ifPresent(
			avatar -> rt.scheduler().cancelAgent(avatar.agentId(), "TEST_DONE"));
		rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
	}

	private static Stage stageOf(Project project, Stage.Kind kind) {
		return project.stages().stream().filter(s -> s.kind == kind).findFirst()
			.orElseThrow();
	}

	/**
	 * New workflow: choosing a design creates a zero-write ghost; confirmation starts it.
	 *
	 * <p>确认<b>会预留真实材料</b>（{@code SquireProjectService.confirm} →
	 * {@code planReservation}/{@code commitReservation}），所以调用方必须先让材料真的
	 * 存在。这不是测试脚手架的方便法门，而是产品语义：工程从一开始就把料锁进
	 * 工程物资池，施工中途不会被别的任务挪用，{@code FULFIL_MATERIALS} 也因此
	 * 只做核对。</p>
	 */
	private static void startConfirmed(TestContext context, SquireRuntime rt,
			FakePlayer owner, String blueprintId) {
		var preview = rt.projectStart(owner, blueprintId);
		context.assertTrue(preview.success(), preview.message());
		context.assertTrue(rt.projects().activeOf(owner.getUuid()).isEmpty(),
			"preview must not start a project before confirmation");
		var confirmed = rt.blueprintBuild(owner);
		context.assertTrue(confirmed.success(), confirmed.message());
	}

	/** 把 {@link #TINY} 那一份料交给伙伴，等价于玩家把东西丢在他脚边。 */
	private static void stock(SquireRuntime rt, FakePlayer owner) {
		AvatarEntity avatar = rt.resolveAvatarFor(owner.getUuid()).orElseThrow();
		avatar.items().insert(new ItemStack(Items.COBBLESTONE, 64));
		avatar.items().insert(new ItemStack(Items.TORCH, 8));
	}

	// ================================================== 分解

	/**
	 * 一句大目标 → 六个阶段，而且<b>一件材料都不会凭空出现</b>。
	 *
	 * <p>先验没料时确认会如实拒绝、两边库存和地面上都长不出东西；再把料交给伙伴，
	 * 确认才成立，六个阶段才排出来。这两半合起来才是「工程是真材料做的」这句话：
	 * 只验后一半的话，一个偷偷补料的实现照样能通过。</p>
	 */
	@GameTest(templateName = FLOOR, tickLimit = 400, batchId = "squire-project-decompose")
	public void aProjectDecomposesIntoSixStagesAndNeverConjuresMaterials(
			TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "project-decompose-owner");
		registerTiny(rt);
		UUID[] projectId = new UUID[1];

		context.runAtTick(5, () -> {
			handOverMode(rt, summon(context, rt, owner, new BlockPos(6, 2, 6)));
			// 一件料都没有：预览成立，确认必须如实拒绝。
			context.assertTrue(rt.projectStart(owner, TINY).success(),
				"placing the ghost writes nothing and must always work");
			var refused = rt.blueprintBuild(owner);
			context.assertFalse(refused.success(),
				"confirming without materials must not start a project");
			context.assertTrue(refused.message().contains("圆石"),
				"a refusal must say WHAT is missing, got: " + refused.message());
			context.assertTrue(rt.projects().activeOf(owner.getUuid()).isEmpty(),
				"and no project may exist after a refusal");

			// 而且拒绝的过程里不许凭空生出材料——玩家背包、地面、伙伴背包都不许。
			int dropped = 0;
			for (var item : context.getWorld().getEntitiesByClass(
					net.minecraft.entity.ItemEntity.class,
					owner.getBoundingBox().expand(4.0), e -> true)) {
				if (item.getStack().isOf(Items.COBBLESTONE)) {
					dropped += item.getStack().getCount();
				}
			}
			AvatarEntity avatar = rt.resolveAvatarFor(owner.getUuid()).orElseThrow();
			int carried = dropped + owner.getInventory().count(Items.COBBLESTONE)
				+ avatar.items().countOf(new Identifier("minecraft:cobblestone"));
			context.assertTrue(carried == 0,
				"projects must not conjure materials; found " + carried);

			// 玩家把料交出来之后，同一次确认就成立了。
			stock(rt, owner);
			var confirmed = rt.blueprintBuild(owner);
			context.assertTrue(confirmed.success(), confirmed.message());
			Project project = rt.projects().activeOf(owner.getUuid()).orElseThrow();
			projectId[0] = project.projectId;
			context.assertTrue(project.stages().size() == 6,
				"a project is six stages, got " + project.stages().size());
			context.assertTrue(stageOf(project, Stage.Kind.FULFIL_MATERIALS).state()
					== Stage.State.PENDING, "nothing has run yet");
		});

		context.runAtTick(120, () -> {
			// 按 id 取，不用 activeOf：料齐了之后这个 2×2×2 的小工程跑得很快，
			// 到这一拍它可能已经验收完毕，而 activeOf 只认还在跑的那一个。
			Project project = rt.projects().project(projectId[0]).orElseThrow();
			Stage materials = stageOf(project, Stage.Kind.FULFIL_MATERIALS);
			context.assertTrue(materials.state() == Stage.State.DONE,
				"with the pool reserved the materials stage verifies and passes, was "
					+ materials.state() + " / " + materials.blockedReason());
			cleanUp(rt, owner);
			context.complete();
		});
	}

	/**
	 * 缺料时<b>说得出还缺什么</b>，而且说的是玩家认得的名字；交出去之后自己解开。
	 *
	 * <p>这一条守的是 ADR-042 里那句「工程可以为人停下，并且说得出卡在谁身上」。
	 * 停下的<b>位置</b>随材料模型变过：现在料在确认那一刻就锁进工程物资池，
	 * 所以拦下来的是确认这一步，而不是后面的交料阶段（{@code HAUL} 已经因此
	 * 变成一个恒定跳过的阶段）。但玩家看到的那句话必须一字不改地保持这个标准：
	 * 说出缺什么、缺多少、用中文物品名，而不是一句「不行」或者一个 registry id。</p>
	 */
	@GameTest(templateName = FLOOR, tickLimit = 600, batchId = "squire-project-haul")
	public void aShortProjectSaysWhatIsMissingAndUnblocksWhenHandedOver(
			TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "project-haul-owner");
		registerTiny(rt);
		UUID[] projectId = new UUID[1];

		context.runAtTick(5, () -> {
			handOverMode(rt, summon(context, rt, owner, new BlockPos(6, 2, 6)));
			context.assertTrue(rt.projectStart(owner, TINY).success(), "ghost placed");
			var refused = rt.blueprintBuild(owner);
			context.assertFalse(refused.success(), "no materials, no project");
			context.assertTrue(refused.message().contains("圆石")
					&& !refused.message().contains("cobblestone"),
				"a refusal must say WHAT is missing, in the player's language, got: "
					+ refused.message());
			context.assertTrue(refused.message().contains("火把"),
				"and it must list every shortfall, not just the first, got: "
					+ refused.message());

			// 玩家把料交给他（这里直接放进背包，等价于丢在他脚边被捡起）。
			stock(rt, owner);
			var confirmed = rt.blueprintBuild(owner);
			context.assertTrue(confirmed.success(),
				"handing the materials over must unblock it by itself: "
					+ confirmed.message());
			projectId[0] = rt.projects().activeOf(owner.getUuid()).orElseThrow()
				.projectId;
		});

		context.runAtTick(300, () -> {
			Project project = rt.projects().project(projectId[0]).orElseThrow();
			context.assertTrue(stageOf(project, Stage.Kind.FULFIL_MATERIALS).state()
					== Stage.State.DONE,
				"the reserved pool covers the bill, so the stage verifies and passes");
			context.assertTrue(stageOf(project, Stage.Kind.HAUL).state()
					== Stage.State.SKIPPED,
				"materials moved at confirmation time, so there is nothing left to haul");
			cleanUp(rt, owner);
			context.complete();
		});
	}

	// ================================================== 玩家控制

	/** 暂停之后不再往前走；继续之后接着走。 */
	@GameTest(templateName = FLOOR, tickLimit = 600, batchId = "squire-project-pause")
	public void pauseStopsAdvancingAndResumePicksUpAgain(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "project-pause-owner");
		registerTiny(rt);

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(6, 2, 6));
			avatar.items().insert(new ItemStack(Items.COBBLESTONE, 64));
			avatar.items().insert(new ItemStack(Items.TORCH, 8));
			startConfirmed(context, rt, owner, TINY);
			context.assertTrue(rt.projectPause(owner).success(), "pause works");
		});

		context.runAtTick(150, () -> {
			Project project = rt.projects().activeOf(owner.getUuid()).orElseThrow();
			context.assertTrue(project.state() == Project.State.PAUSED,
				"the project really is paused");
			context.assertTrue(stageOf(project, Stage.Kind.FULFIL_MATERIALS).state()
					== Stage.State.PENDING,
				"a paused project must not advance a single stage");
			rt.projectResume(owner);
		});

		context.runAtTick(300, () -> {
			Project project = rt.projects().activeOf(owner.getUuid()).orElseThrow();
			context.assertTrue(stageOf(project, Stage.Kind.FULFIL_MATERIALS).state()
					== Stage.State.DONE,
				"resuming picks up exactly where it stopped");
			cleanUp(rt, owner);
			context.complete();
		});
	}

	/** Lighting samples usable floor cells and reaches verification instead of hanging. */
	@GameTest(templateName = FLOOR, tickLimit = 600, batchId = "squire-project-lighting")
	public void aStoneRoomLightsAndFinishesInsteadOfSticking(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "project-lighting-owner");
		registerLightingRoom(rt);
		UUID[] projectId = new UUID[1];

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(6, 2, 6));
			avatar.items().insert(new ItemStack(Items.COBBLESTONE, 32));
			avatar.items().insert(new ItemStack(Items.TORCH, 8));
			startConfirmed(context, rt, owner, LIGHTING);
			projectId[0] = rt.projects().activeOf(owner.getUuid()).orElseThrow().projectId;
		});

		context.runAtTick(400, () -> {
			Project project = rt.projects().project(projectId[0]).orElseThrow();
			Stage lighting = stageOf(project, Stage.Kind.LIGHT);
			context.assertTrue(lighting.state() == Stage.State.DONE,
				"lighting must finish, state was " + lighting.state() + ": "
					+ lighting.blockedReason());
			context.assertTrue(project.state() == Project.State.DONE,
				"the verified room must finish the whole project");
			cleanUp(rt, owner);
			context.complete();
		});
	}

	/** 取消把工程和工地一起撤掉——留一个没人管的幽灵轮廓只会让玩家困惑。 */
	@GameTest(templateName = FLOOR, tickLimit = 300, batchId = "squire-project-cancel")
	public void cancelClearsBothTheProjectAndTheSite(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "project-cancel-owner");
		registerTiny(rt);

		context.runAtTick(5, () -> {
			summon(context, rt, owner, new BlockPos(6, 2, 6));
			stock(rt, owner); // 确认要预留真实材料；本条验的是取消，不是缺料
			startConfirmed(context, rt, owner, TINY);
			context.assertTrue(rt.blueprints().activeOf(owner.getUuid()).isPresent(),
				"starting a project places its site");

			context.assertTrue(rt.projectCancel(owner).success(), "cancel works");
			context.assertTrue(rt.projects().activeOf(owner.getUuid()).isEmpty(),
				"the project is gone");
			context.assertTrue(rt.blueprints().activeOf(owner.getUuid()).isEmpty(),
				"and so is the ghost site");
			cleanUp(rt, owner);
			context.complete();
		});
	}

	/** 一次只做一个工程：第二个如实拒绝，并告诉玩家怎么处理第一个。 */
	@GameTest(templateName = FLOOR, tickLimit = 300, batchId = "squire-project-single")
	public void asecondProjectIsRefusedWithAWayOut(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "project-single-owner");
		registerTiny(rt);

		context.runAtTick(5, () -> {
			summon(context, rt, owner, new BlockPos(6, 2, 6));
			rt.projectStart(owner, TINY);
			var second = rt.projectStart(owner, TINY);
			context.assertFalse(second.success(), "one project at a time");
			context.assertTrue(second.message().contains("取消"),
				"every refusal carries a command-free way out: " + second.message());
			cleanUp(rt, owner);
			context.complete();
		});
	}

	/** 一句自然语言就能开工，认不出来时把可选项列出来。 */
	@GameTest(templateName = FLOOR, tickLimit = 300, batchId = "squire-project-phrase")
	public void aPlainSentenceStartsTheProjectAndAnUnknownOneListsTheOptions(
			TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "project-phrase-owner");

		context.runAtTick(5, () -> {
			summon(context, rt, owner, new BlockPos(6, 2, 6));
			var vague = rt.projectStartFromPhrase(owner, "帮我准备一个什么东西");
			context.assertFalse(vague.success(), "an unrecognised target is refused");
			context.assertTrue(vague.message().contains("mine_outpost"),
				"a dead end must list the way out: " + vague.message());

			var known = rt.projectStartFromPhrase(owner, "帮我准备一个矿井前哨站");
			context.assertTrue(known.success(), known.message());
			context.assertTrue(rt.blueprints().activeOf(owner.getUuid()).orElseThrow()
					.blueprintId.equals("mine_outpost"),
				"the phrase must resolve to an adjustable mine outpost preview");
			context.assertTrue(rt.projects().activeOf(owner.getUuid()).isEmpty(),
				"a natural-language request must still wait for preview confirmation");
			cleanUp(rt, owner);
			context.complete();
		});
	}

	/**
	 * <b>默认行为</b>：材料他自己用指令领，不用玩家跑一趟。
	 *
	 * <p>一句「帮我准备一个矿井前哨站」之后还要亲自送货，等于把大目标又拆回了手工活。
	 * 想要那道手续的人把自主程度调到「保守」即可。</p>
	 */
	@GameTest(templateName = FLOOR, tickLimit = 600, batchId = "squire-project-selfsupply")
	public void byDefaultNothingIsRoutedThroughThePlayer(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "project-selfsupply-owner");
		registerTiny(rt);
		UUID[] projectId = new UUID[1];

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(6, 2, 6));
			context.assertTrue(rt.profileOf(avatar).autonomyLevel()
					== AutonomyLevel.STANDARD, "standard is the default");
			// 料全在伙伴身上，玩家背包是空的：默认档下工程从头到尾不该经过玩家的手。
			stock(rt, owner);
			startConfirmed(context, rt, owner, TINY);
			projectId[0] = rt.projects().activeOf(owner.getUuid()).orElseThrow()
				.projectId;
			context.assertTrue(owner.getInventory().count(Items.COBBLESTONE) == 0,
				"the owner starts with nothing and must not be handed anything");
		});

		context.runAtTick(200, () -> {
			Project project = rt.projects().project(projectId[0]).orElseThrow();
			context.assertTrue(stageOf(project, Stage.Kind.FULFIL_MATERIALS).state()
					== Stage.State.DONE,
				"the companion's own stock is enough; nothing waits on the player");
			// 他不需要你：玩家那边从头到尾没出现过材料。
			// （不能改成“他背包里有料”——到这一拍他早就把料砦进墙里了。）
			int toThePlayer = owner.getInventory().count(Items.COBBLESTONE);
			for (var item : context.getWorld().getEntitiesByClass(
					net.minecraft.entity.ItemEntity.class,
					owner.getBoundingBox().expand(4.0), e -> true)) {
				if (item.getStack().isOf(Items.COBBLESTONE)) {
					toThePlayer += item.getStack().getCount();
				}
			}
			context.assertTrue(toThePlayer == 0,
				"on the default setting nothing should be routed through the player, "
					+ "found " + toThePlayer);
			cleanUp(rt, owner);
			context.complete();
		});
	}

}
