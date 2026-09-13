package dev.squire.gametest;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import dev.squire.common.protocol.ToolCall;
import dev.squire.common.protocol.ToolDescriptor;
import dev.squire.server.blueprint.Blueprint;
import dev.squire.server.blueprint.BlueprintStep;
import dev.squire.server.blueprint.BuildTier;
import dev.squire.server.blueprint.ProjectSpec;
import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.profession.ProfessionData;
import dev.squire.server.profession.SquireProfession;
import dev.squire.server.profession.TrainingMilestone;
import dev.squire.server.runtime.SquireRuntime;
import dev.squire.server.tool.ToolGate;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * §23 在<b>真实注册表</b>上的验收，以及第二道闸。
 *
 * <p>{@code ToolGateFilterTest} 用的是一份手写的假目录，验的是规则本身。
 * 这里换成服务器上真正注册的那四十多个工具——它回答的是另一个问题：
 * <b>裁剪接上去了吗，省下了多少</b>。</p>
 */
public final class M22ToolGateGameTests implements FabricGameTest {

	public static final String FLOOR = M0SpikeGameTests.FLOOR;

	private static SquireRuntime runtime(TestContext context) {
		SquireRuntime.ensureInitialized(context.getWorld().getServer());
		return SquireRuntime.get();
	}

	private static AvatarEntity summon(TestContext context, SquireRuntime rt,
			FakePlayer owner, BlockPos relative) {
		context.getWorld().spawnEntity(owner);
		AvatarEntity avatar = rt.summonFor(owner);
		Vec3d feet = Vec3d.ofBottomCenter(context.getAbsolutePos(relative));
		avatar.refreshPositionAndAngles(feet.x, feet.y, feet.z, 0.0f, 0.0f);
		avatar.setIdleMode();
		return avatar;
	}

	private static FakePlayer fakeOwner(ServerWorld world, String name) {
		return FakePlayer.get(world,
			new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name));
	}

	private static void become(SquireRuntime rt, AvatarEntity avatar,
			SquireProfession profession, int level) {
		for (TrainingMilestone milestone : TrainingMilestone.values()) {
			rt.noteTraining(avatar, milestone);
		}
		ProfessionData data = rt.professionOf(avatar);
		data.setProfession(profession);
		data.level = level;
	}

	/** 目录序列化成提示词那一段之后有多少字符。 */
	private static int chars(List<ToolDescriptor> tools) {
		int total = 0;
		for (ToolDescriptor tool : tools) {
			total += tool.name().length() + tool.description().length() + 4;
			for (var p : tool.parameters()) {
				total += p.name().length()
					+ (p.description() == null ? 0 : p.description().length()) + 12;
			}
		}
		return total;
	}

	/** 一份小到能放进 9×4×9 测试场地的<b>固定</b>模板：分档必须判成 BASIC。 */
	private static final String FIXED = "gametest_toolgate_hut";

	private static void registerFixed(SquireRuntime rt) {
		rt.blueprints().registry().register(new Blueprint(FIXED, "测试小屋", 1,
			Blueprint.Category.SHELTER, 2, 2, 2,
			List.of(BlueprintStep.place(0, 0, 0, 0, 1, 1, 1, "minecraft:cobblestone",
				"壳", false)), java.util.Set.of()));
	}

	/** 工程师设计页产出的那种 id：参数化，分档必须判成 PARAMETRIC。 */
	private static String parametricId() {
		return ProjectSpec.Template.SHED.defaults().blueprintId();
	}

	/**
	 * 「照固定模板盖房」这条路对这只随从通不通。
	 *
	 * <p>走的是 {@code blueprintPlace} —— 面板按钮、聊天、命令、模型工具共用的
	 * 那个漏斗，所以这里验到的就是玩家四条路都会得到的答案。</p>
	 */
	private static void assertBasicBuildWorks(TestContext context, SquireRuntime rt,
			FakePlayer owner, String who) {
		registerFixed(rt); // Each test owns its fixture; reload tests may reset the registry between batches.
		var placed = rt.blueprintPlace(owner, FIXED);
		context.assertTrue(placed.success(),
			who + " 盖不了固定模板了：" + placed.message());
		rt.blueprints().activeOf(owner.getUuid())
			.ifPresent(site -> rt.blueprints().remove(site.placementId));
	}

	/** 参数化蓝图必须在<b>分档</b>这一步就被挡下，而不是走到几何校验才失败。 */
	private static void assertParametricRefused(TestContext context, SquireRuntime rt,
			FakePlayer owner, String who) {
		var refused = rt.blueprintPlace(owner, parametricId());
		context.assertFalse(refused.success(), who + " 拿到了参数化蓝图");
		context.assertTrue(refused.message().contains("停止新建"), who + " must receive the explicit retirement reason: " + refused.message());
		var catalog = rt.blueprintPlace(owner, "keepitlevel_residence");
		context.assertFalse(catalog.success(), who + " cannot build Engineer-only catalog assets");
		context.assertTrue(catalog.message().contains("工程师"), "catalog admission still enforces profession");
	}

	private static void cleanUp(SquireRuntime rt, FakePlayer owner) {
		rt.resolveAvatarFor(owner.getUuid()).ifPresent(
			avatar -> rt.scheduler().cancelAgent(avatar.agentId(), "TEST_DONE"));
		rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
	}

	// ================================================== 真实目录的规模

	/**
	 * 各典型状态下真正会发给模型的目录有多大。
	 *
	 * <p>只断言一条（低等级必须严格更小），其余打印出来——这一条测试存在的意义是让
	 * 「§23 到底省了多少上下文」有一个可复现、会随代码变化的答案，而不是一句估计。</p>
	 */
	@GameTest(templateName = FLOOR, tickLimit = 200, batchId = "squire-toolgate-size")
	public void theTrimmedCatalogIsMeasurablySmaller(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "toolgate-size");

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(1, 2, 1));
			UUID agentId = avatar.agentId();

			List<ToolDescriptor> all = rt.modelVisibleDescriptors();
			StringBuilder report = new StringBuilder("\n[§23] 真实目录规模\n")
				.append(String.format("  %-22s %5s %8s%n", "state", "tools", "chars"))
				.append(String.format("  %-22s %5d %8d%n", "ALL (before)", all.size(),
					chars(all)));

			int untrained = rt.modelVisibleDescriptors(agentId).size();
			int untrainedChars = chars(rt.modelVisibleDescriptors(agentId));
			report.append(String.format("  %-22s %5d %8d%n", "Untrained Lv.0",
				untrained, untrainedChars));

			for (var profession : SquireProfession.values()) {
				for (int level : new int[] {1, 5, 8, 10}) {
					become(rt, avatar, profession, level);
					var visible = rt.modelVisibleDescriptors(agentId);
					report.append(String.format("  %-22s %5d %8d%n",
						profession.id() + " Lv." + level, visible.size(),
						chars(visible)));
				}
			}
			System.out.println(report);

			context.assertTrue(untrained < all.size(),
				"Lv.0 的目录该比全量小，实际 " + untrained + " vs " + all.size());
			context.assertTrue(untrainedChars < chars(all),
				"字符数也该更小，实际 " + untrainedChars + " vs " + chars(all));

			cleanUp(rt, owner);
			context.complete();
		});
	}

	/** 裁剪发生在<b>序列化之前</b>：拿到的目录里就没有那些条目，不是发完再说不能用。 */
	@GameTest(templateName = FLOOR, tickLimit = 200, batchId = "squire-toolgate-visible")
	public void theCatalogHandedToTheModelAlreadyExcludesLockedTools(
			TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "toolgate-visible");

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(1, 2, 1));
			UUID agentId = avatar.agentId();

			become(rt, avatar, SquireProfession.ENGINEER, 2);
			var atTwo = rt.modelVisibleDescriptors(agentId).stream()
				.map(ToolDescriptor::name).toList();
			context.assertFalse(atTwo.contains("blueprint.rotate"),
				"Lv.2 的目录里出现了旋转");
			context.assertFalse(atTwo.contains("blueprint.design"), "retired designer must not be offered to the model");

			// 晋升之后<b>下一次</b>取目录就该变——不需要重登、不需要重召唤。
			rt.professionOf(avatar).level = 3;
			var atThree = rt.modelVisibleDescriptors(agentId).stream()
				.map(ToolDescriptor::name).toList();
			context.assertTrue(atThree.contains("blueprint.rotate"),
				"升到 Lv.3 之后目录没有立刻更新");

			// 满级工程师仍然拿不到守卫的工具。
			rt.professionOf(avatar).level = 10;
			var atTen = rt.modelVisibleDescriptors(agentId).stream()
				.map(ToolDescriptor::name).toList();
			context.assertFalse(atTen.contains("combat.set_stance"),
				"满级工程师拿到了守卫的战斗姿态");

			cleanUp(rt, owner);
			context.complete();
		});
	}

	// ================================================== 第二道闸

	/**
	 * 模型看不见的工具，<b>伪造调用也执行不了</b>。
	 *
	 * <p>第一道闸挡的是模型；这一条验的是第二道闸——请求可以从别处来、目录可以被
	 * 抢跑，所以执行前必须再判一次。</p>
	 */
	@GameTest(templateName = FLOOR, tickLimit = 200, batchId = "squire-toolgate-bypass")
	public void aForgedCallToALockedToolIsRefused(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "toolgate-bypass");

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(1, 2, 1));
			become(rt, avatar, SquireProfession.ENGINEER, 1);

			// Lv.1 工程师伪造一个 Lv.7 的镜像调用。
			var refused = callAsModel(rt, owner, avatar, "blueprint.mirror",
				Map.of("axis", "x"));
			context.assertFalse(refused, "Lv.1 工程师执行了本该锁着的镜像");

			// 等级够了但<b>职业不对</b>：满级工程师调守卫的姿态。
			rt.professionOf(avatar).level = 10;
			var wrongProfession = callAsModel(rt, owner, avatar, "combat.set_stance",
				Map.of("stance", "aggressive"));
			context.assertFalse(wrongProfession,
				"满级工程师执行了守卫的战斗姿态——判据把等级当成了唯一条件");
			context.assertTrue(rt.professionOf(avatar).combatStance()
					== dev.squire.server.profession.CombatStance.BALANCED,
				"被拒绝的调用不该改变任何状态");

			// 通用工具照旧能用——第二道闸不该误伤。
			context.assertTrue(callAsModel(rt, owner, avatar, "query.status", Map.of()),
				"通用工具被第二道闸挡住了");

			cleanUp(rt, owner);
			context.complete();
		});
	}

	/**
	 * 以<b>模型</b>身份走一次完整网关。返回是否执行成功。
	 *
	 * <p>走的是真实的 {@code dispatch}，所以第二道闸和它前后的每一步（曝光、权限、
	 * 风险、配额）都真的跑过——这正是「伪造调用」要验的东西。</p>
	 */
	private static boolean callAsModel(SquireRuntime rt, FakePlayer owner,
			AvatarEntity avatar, String tool, Map<String, Object> args) {
		var call = new ToolCall(UUID.randomUUID(), tool, args);
		var ctx = new dev.squire.server.tool.ToolExecutionContext() {
			@Override
			public dev.squire.api.body.AgentBody body() {
				return avatar;
			}

			@Override
			public AvatarEntity avatar() {
				return avatar;
			}

			@Override
			public net.minecraft.server.MinecraftServer server() {
				return avatar.getServer();
			}

			@Override
			public UUID requesterId() {
				return owner.getUuid();
			}

			@Override
			public long tick() {
				return rt.tickNow();
			}
		};
		var result = rt.gateway().dispatch(call,
			dev.squire.server.tool.CallerIdentity.model(owner.getUuid(),
				avatar.agentId()),
			ctx, rt.capabilitiesOf(avatar.agentId()));
		return result != null && result.status()
			== dev.squire.common.protocol.ToolResult.Status.SUCCESS;
	}

	// ================================================== §23.1 职业边界

	/**
	 * Lv.0 只有训练期蓝图，没有正式工程；也没有守卫的主动接战。
	 *
	 * <p>同时验一条同样重要的事：<b>被动自卫没有被这次收敛碰到</b>。挨打还手、
	 * 吃药、穿装备走的是实体 AI 和通用工具，和职业工具权限是两回事。</p>
	 */
	@GameTest(templateName = FLOOR, tickLimit = 200, batchId = "squire-toolgate-lv0")
	public void anUntrainedSquireOnlyGetsTrainingBlueprints(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "toolgate-lv0");
		registerFixed(rt);

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(1, 2, 1));
			var seen = rt.modelVisibleDescriptors(avatar.agentId()).stream()
				.map(ToolDescriptor::name).toList();

			context.assertTrue(seen.contains("blueprint.place"),
				"新手训练第五项要用它，锁掉训练就卡死了");
			// 基础施工这三条现在人人可见：被拒的是参数，不是工具本身。
			context.assertTrue(seen.contains("project.start"),
				"照固定模板开一个工程是所有随从的基础本事");
			context.assertTrue(seen.contains("blueprint.build"), "盖起来也是");
			// 但参数化设计那一整段仍然要转职。
			for (String engineerOnly : List.of("blueprint.design", "blueprint.rotate",
					"blueprint.set_size", "blueprint.set_floors", "blueprint.mirror",
					"blueprint.add_module", "build.structure")) {
				context.assertFalse(seen.contains(engineerOnly),
					"Lv.0 拿到了参数化工具 " + engineerOnly);
			}
			// 而且分档是在服务端真的判的，不只是目录里少一条。
			assertBasicBuildWorks(context, rt, owner, "Lv.0");
			assertParametricRefused(context, rt, owner, "Lv.0");

			context.assertFalse(seen.contains("guard.start"),
				"主动发起护卫是守卫这条线的能力");
			context.assertFalse(seen.contains("combat.attack_target"),
				"主动指定目标开打也是守卫的");

			// 被动自卫这一侧一件都不能少。
			context.assertTrue(seen.contains("heal.now"), "自己回血");
			context.assertTrue(seen.contains("aid.owner"), "救主人");
			context.assertTrue(seen.contains("guard.stop"), "叫停护卫永远要有出路");

			// 而且伪造调用同样挡得住。
			context.assertFalse(callAsModel(rt, owner, avatar, "guard.start",
				Map.of("radius", 12, "durationTicks", 600)),
				"Lv.0 伪造 guard.start 执行成功了");

			cleanUp(rt, owner);
			context.complete();
		});
	}

	/** 守卫拿不到蓝图工具；工程师拿不到主动接战。两边都保留通用能力。 */
	@GameTest(templateName = FLOOR, tickLimit = 200, batchId = "squire-toolgate-split")
	public void theTwoLinesDoNotOverlap(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "toolgate-split");

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(1, 2, 1));

			become(rt, avatar, SquireProfession.GUARD, 10);
			var guard = rt.modelVisibleDescriptors(avatar.agentId()).stream()
				.map(ToolDescriptor::name).toList();
			// 守卫<b>能</b>照固定模板盖房：面板上那几个按钮本来就一直能点，
			// 而聊天里说同一件事被拒，是玩家完全无法理解的一种不一致。
			for (String basic : List.of("project.start", "blueprint.place",
					"blueprint.build")) {
				context.assertTrue(guard.contains(basic),
					"守卫丢了基础施工 " + basic);
			}
			// 参数化设计仍然是工程师那条线买来的。
			for (String engineerTool : List.of("blueprint.design", "blueprint.rotate",
					"blueprint.set_size", "blueprint.set_floors", "blueprint.mirror",
					"blueprint.add_module", "build.structure")) {
				context.assertFalse(guard.contains(engineerTool),
					"满级守卫拿到了参数化工具 " + engineerTool);
			}
			assertBasicBuildWorks(context, rt, owner, "满级守卫");
			assertParametricRefused(context, rt, owner, "满级守卫");
			// 丢掉蓝图不等于丢掉通用能力。
			for (String common : List.of("heal.now", "aid.owner", "guard.stop",
					"minecraft.command.setblock", "worldedit.propose_fill_selection")) {
				context.assertTrue(guard.contains(common),
					"守卫丢了通用能力 " + common);
			}

			become(rt, avatar, SquireProfession.ENGINEER, 10);
			var engineer = rt.modelVisibleDescriptors(avatar.agentId()).stream()
				.map(ToolDescriptor::name).toList();
			for (String guardTool : List.of("guard.start", "combat.attack_target",
					"combat.supplies", "combat.set_stance")) {
				context.assertFalse(engineer.contains(guardTool),
					"满级工程师拿到了 " + guardTool);
			}
			context.assertTrue(engineer.contains("heal.now"),
				"工程师仍然要能照顾自己");
			context.assertTrue(engineer.contains("blueprint.place"),
				"工程师当然要有基础蓝图");
			context.assertFalse(engineer.contains("blueprint.design"), "master does not resurrect a retired content tool");
			// 工程师不该在<b>分档</b>这一步被挡。后面的几何校验能不能过是另一回事，
			// 所以这里只验「不是因为职业被拒」。
			var parametric = rt.blueprintPlace(owner, parametricId());
			context.assertFalse(parametric.message().contains("参数化蓝图要工程师"),
				"满级工程师被分档挡住了：" + parametric.message());
			rt.blueprints().activeOf(owner.getUuid())
				.ifPresent(site -> rt.blueprints().remove(site.placementId));

			cleanUp(rt, owner);
			context.complete();
		});
	}

	/**
	 * 分档只看蓝图 id，不看谁在问——这条规则本身也要有测试盯着。
	 *
	 * <p>面板「基础施工」那五个按钮送出去的就是下面这几个 id；它们里面<b>任何一个</b>
	 * 被判成参数化，都意味着守卫按了按钮却被拒。</p>
	 */
	@GameTest(templateName = FLOOR, tickLimit = 100, batchId = "squire-toolgate-tier")
	public void thePanelsBasicTemplatesAllClassifyAsBasic(TestContext context) {
		for (String basic : List.of("mine_outpost", "watchtower", "storage_shed",
				dev.squire.server.blueprint.HouseSpec.defaults().blueprintId(),
				dev.squire.server.blueprint.HouseSpec.stoneDefaults().blueprintId(),
				FIXED, "", "no_such_blueprint")) {
			context.assertTrue(BuildTier.of(basic).isBasic(),
				basic + " 被判成了参数化，守卫会在面板上点了被拒");
		}
		for (String parametric : List.of(parametricId(),
				ProjectSpec.Template.OUTPOST.defaults().blueprintId(),
				"generated_house/oak/11x11x5/gable")) {
			context.assertFalse(BuildTier.of(parametric).isBasic(),
				parametric + " 被判成了基础施工，等于把参数化设计发给了所有人");
		}
		context.complete();
	}

	/**
	 * 被动自卫和职业工具权限无关：工程师挨了打照样会还手。
	 *
	 * <p>验的是实体那一侧的判据（{@code AutonomyController.wouldRetaliateAgainst}），
	 * 它根本不看职业——这正是「基础 AI 自卫不属于 LLM Tool 权限」那句话的落点。</p>
	 */
	@GameTest(templateName = FLOOR, tickLimit = 200, batchId = "squire-toolgate-passive")
	public void anEngineerStillDefendsItself(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "toolgate-passive");

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(1, 2, 1));
			become(rt, avatar, SquireProfession.ENGINEER, 10);

			var zombie = net.minecraft.entity.EntityType.ZOMBIE.create(world);
			context.assertTrue(zombie != null, "test zombie");
			Vec3d at = Vec3d.ofBottomCenter(
				context.getAbsolutePos(new BlockPos(1, 2, 2)));
			zombie.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f);
			world.spawnEntity(zombie);

			context.assertTrue(dev.squire.server.runtime.AutonomyController
					.wouldRetaliateAgainst(avatar, zombie),
				"一个满级工程师被僵尸咬了却不还手——被动自卫被职业权限误伤了");

			zombie.discard();
			cleanUp(rt, owner);
			context.complete();
		});
	}

	/** 门槛表本身：每一条都要指向一个真实注册的工具。 */
	@GameTest(templateName = FLOOR, tickLimit = 100, batchId = "squire-toolgate-table")
	public void everyGatedToolNameIsReallyRegistered(TestContext context) {
		SquireRuntime rt = runtime(context);
		var registered = rt.toolRegistry().allDefinitions().stream()
			.map(dev.squire.server.tool.ToolDefinition::name).toList();
		for (String name : ToolGate.all().keySet()) {
			context.assertTrue(registered.contains(name),
				"门槛表里的 " + name + " 没有对应的注册工具——这条门槛永远不会生效");
		}
		context.complete();
	}

	// ============================================ §23.2 围栏与 PLANNER 通道

	/**
	 * 反方向：真实注册表里不许有<b>落在职业命名空间却没定门槛</b>的工具。
	 *
	 * <p>{@code ToolGateFilterTest} 里的同名检查用的是一份手写目录，只能验规则；
	 * 真正会静悄悄出事的是<b>以后新加的工具</b>——那一条只有在真实注册表上跑才拦得住。
	 * 门槛的默认值是 COMMON，所以漏写一行不会报错，只会让一条职业能力人人可用。</p>
	 */
	@GameTest(templateName = FLOOR, tickLimit = 100, batchId = "squire-toolgate-fence")
	public void noRegisteredToolInAnOwnedNamespaceIsUnclassified(TestContext context) {
		SquireRuntime rt = runtime(context);
		var names = rt.toolRegistry().allDefinitions().stream()
			.map(dev.squire.server.tool.ToolDefinition::name).toList();
		List<String> missing = ToolGate.unclassified(names);
		context.assertTrue(missing.isEmpty(),
			"这些工具在职业命名空间里却没有门槛条目，会默认变成人人可用：" + missing
				+ "。结论是 COMMON 也要在 ToolGate 表里写出来。");
		context.complete();
	}

	/**
	 * PLANNER_INTERNAL 通道不是职业边界的旁路（§23.2 审计）。
	 *
	 * <p>这条通道真实存在：{@code AutomationEngine} 的 TOOL_CALL 节点以 PLANNER 身份
	 * 走同一个网关。所以问题不是「模型调不到就没事」，而是<b>自动化能点名哪些工具</b>。
	 * 答案是 {@code AutomationCompiler.ALLOWED_TOOLS} 那张硬白名单，这里逐条验它
	 * 一个职业工具都不含——将来有人往白名单里加东西，这条会红。</p>
	 */
	@GameTest(templateName = FLOOR, tickLimit = 100, batchId = "squire-toolgate-planner")
	public void theAutomationWhitelistCannotReachAnyProfessionTool(TestContext context) {
		for (String name : dev.squire.server.automation.AutomationCompiler.ALLOWED_TOOLS) {
			ToolGate gate = ToolGate.of(name);
			context.assertTrue(gate.isCommon(),
				"自动化白名单里的 " + name + " 是一条职业能力。自动化图以 PLANNER 身份"
					+ "调用，等于绕开了「模型看不见」那道闸——要么把它移出白名单，"
					+ "要么承认这条边界不成立。");
			// 第二道闸对 PLANNER 同样生效，但只有 COMMON 才谈得上「无条件通过」。
			context.assertTrue(gate.allows(null), name);
		}
		context.complete();
	}

	/**
	 * 第二道闸对 PLANNER 身份也生效——不是只挡模型。
	 *
	 * <p>{@code build.structure} 是 PLANNER_INTERNAL，模型看不见它，所以第一道闸
	 * 根本不参与。它现在是训练期能力：守卫用不了。这条测试直接以 PLANNER 身份把
	 * 调用打进网关，验的是「就算绕过第一道闸，第二道闸还在」。</p>
	 */
	@GameTest(templateName = FLOOR, tickLimit = 200, batchId = "squire-toolgate-planner")
	public void aGuardCannotHandBuildEvenAsThePlanner(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "toolgate-planner");

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(1, 2, 1));
			become(rt, avatar, SquireProfession.GUARD, 10);

			context.assertFalse(ToolGate.of("build.structure").allows(
					rt.professionOf(avatar)),
				"满级守卫不该有徒手垒盒子的权限——那是蓝图能力的原始形态");
			// 而同一只随从换成工程师就该有：验的是职业判据，不是「这条永远拒绝」。
			ProfessionData data = rt.professionOf(avatar);
			data.setProfession(SquireProfession.ENGINEER);
			context.assertTrue(ToolGate.of("build.structure").allows(data),
				"工程师必须能用 build.structure，否则这条门槛是死的");

			cleanUp(rt, owner);
			context.complete();
		});
	}
}
