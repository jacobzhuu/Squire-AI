package dev.squire.server.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import dev.squire.common.protocol.ToolDescriptor;
import dev.squire.server.profession.ProfessionData;
import dev.squire.server.profession.SquireProfession;

/**
 * 按职业和等级裁剪 LLM 工具目录（设计文档 §23）。
 *
 * <h2>这一份守的是什么</h2>
 * <p>不是「调用会不会被拒」——那是第二道闸，另有测试。这里守的是<b>模型根本看不见</b>：
 * 一个 Lv.0 的随从收到的目录里不该出现「多层」「镜像」「复合蓝图」，否则模型会去想
 * 那些事、会生成注定被拒的调用，玩家看到的就是「他答应了然后什么也没发生」。</p>
 *
 * <h2>职业 + 等级，缺一不可</h2>
 * <p>最容易写错的一条是只判等级。一个 Lv.10 的工程师满足「level >= 8」，
 * 但他永远不该拿到守卫的战斗姿态。每一组隔离测试都在盯这一点。</p>
 */
class ToolGateFilterTest {

	/** 一份和真实注册表同名的假目录：这里只关心名字，参数无所谓。 */
	private static final List<String> CATALOG = List.of(
		// COMMON —— 所有随从都有，包括 Lv.0
		"query.status", "query.inventory", "navigation.come_to_owner",
		"inventory.give", "memory.set_home", "guard.stop", "combat.set_style",
		"aid.owner", "heal.now",
		"minecraft.command.setblock", "worldedit.propose_fill_selection",
		// COMMON —— 基础施工：照现成模板盖，参数化的那一半由 BuildTier 在服务端挡
		"blueprint.place", "blueprint.build", "project.start",
		// TRAINING —— Lv.0 与工程师（自己报尺寸的裸盒子，不是模板）
		"build.structure",
		// GUARD
		"guard.start", "combat.attack_target", "combat.supplies", "combat.set_stance",
		// ENGINEER
		"blueprint.design", "blueprint.set_size", "blueprint.rotate",
		"blueprint.set_material_region", "blueprint.set_variant",
		"blueprint.set_floors", "blueprint.mirror", "blueprint.add_module",
		"blueprint.save_preset", "blueprint.load_preset");

	/**
	 * 工程师专属：<b>参数化设计</b>那一整段。
	 *
	 * <p>基础施工（{@code blueprint.place} / {@code build} / {@code project.start}）
	 * 不在内——它们是所有随从都有的本事，被拒的是参数而不是工具，判据在
	 * {@code BuildTier} + {@code SquireEngineerService.checkBuildTier}。</p>
	 */
	private static final List<String> ENGINEER_ONLY = List.of(
		"blueprint.design", "blueprint.set_size", "blueprint.rotate",
		"blueprint.set_material_region", "blueprint.set_variant",
		"blueprint.set_floors", "blueprint.mirror", "blueprint.add_module",
		"blueprint.save_preset", "blueprint.load_preset");

	/** 守卫的<b>主动</b>职业工具。被动自卫不在这里——那不是工具。 */
	private static final List<String> GUARD_ONLY = List.of(
		"guard.start", "combat.attack_target", "combat.supplies", "combat.set_stance");

	/**
	 * 训练期工具：Lv.0 和工程师能用，守卫不能。
	 *
	 * <p>只剩 {@code build.structure} 一条：它让调用方直接报宽/深/高，尺寸就是参数，
	 * 所以归参数化那一侧。照模板盖房的那三条已经是所有人的基础施工。</p>
	 */
	private static final List<String> TRAINING_BLUEPRINT = List.of("build.structure");

	/** 基础施工：照现成模板盖。Lv.0、守卫、工程师、甚至档案读不出来时都在目录里。 */
	private static final List<String> BASIC_BUILDING = List.of(
		"blueprint.place", "blueprint.build", "project.start");

	/** 真正谁都有的：生存、跟随、背包、查询，以及叫停一条已生效护卫策略。 */
	private static final List<String> TRULY_COMMON = List.of(
		"query.status", "query.inventory", "navigation.come_to_owner",
		"inventory.give", "memory.set_home", "aid.owner", "heal.now", "guard.stop",
		// 照模板盖房和跟随、送东西一样，是随从本来就会的事。
		"blueprint.place", "blueprint.build", "project.start");

	private static List<ToolDescriptor> catalog() {
		List<ToolDescriptor> out = new ArrayList<>();
		for (String name : CATALOG) {
			out.add(new ToolDescriptor(name, "description of " + name, List.of()));
		}
		return out;
	}

	private static ProfessionData untrained() {
		return new ProfessionData();
	}

	private static ProfessionData at(SquireProfession profession, int level) {
		ProfessionData data = new ProfessionData();
		data.setProfession(profession);
		data.level = level;
		return data;
	}

	private static List<String> visible(ProfessionData data) {
		return ToolGate.filter(catalog(), data).stream()
			.map(ToolDescriptor::name).toList();
	}

	// ============================================== §23.2 档案缺失 = fail-closed

	/**
	 * 「查不到档案」和「明确写着还没转职」是两件事。
	 *
	 * <p>以前 TRAINING 类在 {@code data == null} 时放行，理由是「历史行为人人可用」。
	 * 但那等于把<b>身份不明</b>当成新手招待——真正的新手是有档案的，
	 * {@link ProfessionData} 是 profile 的 final 字段，对象一存在就非 null，
	 * 早于 NBT 读取。所以 null 只可能是 agentId 为空、运行时没起来、或者档案里
	 * 根本没这条记录。这几种情况都不该发权限。</p>
	 */
	@Nested
	@DisplayName("§23.2 档案缺失一律拒绝")
	class MissingProfile {

		@Test
		@DisplayName("明确的 NONE Lv.0 可以用训练期蓝图")
		void explicitNoneLevelZeroKeepsTrainingBlueprints() {
			ProfessionData none = untrained();
			assertFalse(none.hasProfession(), "这份档案必须是明确的「还没转职」");
			for (String tool : TRAINING_BLUEPRINT) {
				assertTrue(ToolGate.of(tool).allows(none),
					"明确 NONE Lv.0 的新手必须够得着 " + tool + "，否则新手训练卡死");
			}
		}

		@Test
		@DisplayName("档案缺失不能用训练期蓝图")
		void missingProfileLosesTrainingBlueprints() {
			for (String tool : TRAINING_BLUEPRINT) {
				assertFalse(ToolGate.of(tool).allows(null),
					tool + "：查不到这只随从是谁，就不能当成新手发权限");
			}
		}

		@Test
		@DisplayName("档案缺失不能用任何职业工具")
		void missingProfileLosesEveryProfessionTool() {
			for (String tool : GUARD_ONLY) {
				assertFalse(ToolGate.of(tool).allows(null), tool);
			}
			for (String tool : ENGINEER_ONLY) {
				assertFalse(ToolGate.of(tool).allows(null), tool);
			}
		}

		@Test
		@DisplayName("档案缺失仍然保留真正的通用工具")
		void missingProfileKeepsTrulyCommonTools() {
			for (String tool : TRULY_COMMON) {
				assertTrue(ToolGate.of(tool).allows(null),
					tool + "：查询、跟随、背包、救主、叫停不该因为读不到档案就没了——"
						+ "那会让一只随从彻底不听话，而不是少一条职业能力");
			}
			// 叫停必须活着：这是「任何输入都要有出路」的最后一条退路。
			assertTrue(ToolGate.of("guard.stop").allows(null));
		}

		@Test
		@DisplayName("守卫档案读不出来时，不会降级换到训练期权限")
		void anUnresolvableGuardDoesNotFallBackToTrainingRights() {
			// 一只守卫本来就<b>没有</b>蓝图权限。如果它的档案某一刻读不出来，
			// 结果必须是「更少」而不是「换了一套」——fail-open 的经典事故形状
			// 就是权限在失败瞬间横向漂移。
			// 满级守卫：GUARD_ONLY 里 combat.set_stance 要到 Lv.8 才开，
			// 拿一只 Lv.5 来比会把「等级不够」误当成「fail-closed 生效了」。
			ProfessionData guard = at(SquireProfession.GUARD, 10);
			for (String tool : TRAINING_BLUEPRINT) {
				assertFalse(ToolGate.of(tool).allows(guard), "守卫本来就没有 " + tool);
				assertFalse(ToolGate.of(tool).allows(null),
					tool + "：档案读不出来反而拿到了守卫在正常状态下没有的能力");
			}
			// 而它正常状态下有的东西，在读不出来时也一并没有——严格更少。
			for (String tool : GUARD_ONLY) {
				assertTrue(ToolGate.of(tool).allows(guard));
				assertFalse(ToolGate.of(tool).allows(null));
			}
		}

		@Test
		@DisplayName("档案缺失时可见目录是所有状态里最小的")
		void missingProfileIsTheSmallestCatalogOfAll() {
			int missing = ToolGate.filter(catalog(), null).size();
			assertTrue(missing < visible(untrained()).size(),
				"档案缺失必须严格少于明确的 Lv.0");
			for (var profession : SquireProfession.values()) {
				for (int level = 1; level <= 10; level++) {
					assertTrue(missing <= visible(at(profession, level)).size(),
						profession.id() + " Lv" + level + " 竟然比「查不到」还少");
				}
			}
			// 剩下的必须恰好是真正的通用工具，一条不多。TRULY_COMMON 之外还有三条
			// 显式 COMMON：combat.set_style，以及两条需主人确认的通用命令。
			assertEquals(TRULY_COMMON.size() + 3, missing,
				"档案缺失时剩下的应当只有通用工具");
		}
	}

	// ============================================== §23.2 默认行为与围栏

	@Nested
	@DisplayName("§23.2 没有条目的工具默认 COMMON，但职业命名空间必须显式登记")
	class DefaultPolicy {

		@Test
		@DisplayName("表外的历史工具依旧默认 COMMON，不因这轮改动消失")
		void unlistedHistoricalToolsStayCommon() {
			for (String tool : List.of("query.status", "navigation.move_to",
					"inventory.give", "memory.set_home", "crafting.craft",
					"gather.block", "task.acquire", "container.sort",
					"inventory.pickup_nearby", "base.lights.set")) {
				assertTrue(ToolGate.of(tool).isCommon(), tool + " 不该被这轮收走");
				assertTrue(ToolGate.of(tool).allows(null),
					tool + " 是通用工具，档案缺失也要能用");
			}
		}

		@Test
		@DisplayName("guard.stop 与 combat.set_style 是写出来的 COMMON，不是漏写")
		void deliberateCommonEntriesAreDeclaredNotOmitted() {
			for (String tool : List.of("guard.stop", "combat.set_style")) {
				assertTrue(ToolGate.all().containsKey(tool),
					tool + " 必须在表里有一条明确的 COMMON——"
						+ "「想过之后认为人人可用」和「忘了」在代码里长得一样");
				assertTrue(ToolGate.of(tool).isCommon(), tool);
			}
		}

		@Test
		@DisplayName("职业命名空间下不许有未登记的工具")
		void nothingInAnOwnedNamespaceIsUnclassified() {
			assertTrue(ToolGate.unclassified(CATALOG).isEmpty(),
				"这些工具落在职业命名空间却没有门槛条目，会静悄悄变成人人可用："
					+ ToolGate.unclassified(CATALOG));
		}

		@Test
		@DisplayName("围栏本身是有效的：伪造一条漏写会被抓住")
		void theFenceActuallyCatchesAnOmission() {
			// 不验这一条，上面那条测试可能只是因为 unclassified() 永远返回空。
			List<String> withOmission = new ArrayList<>(CATALOG);
			withOmission.add("blueprint.set_pitch");   // 假想的新工具，忘了加门槛
			withOmission.add("guard.patrol_route");
			assertEquals(List.of("blueprint.set_pitch", "guard.patrol_route"),
				ToolGate.unclassified(withOmission));
			// 而命名空间之外的新工具不该被误报——那些默认 COMMON 是对的。
			assertTrue(ToolGate.unclassified(List.of("query.weather",
				"navigation.follow_path", "memory.forget")).isEmpty());
		}
	}

	// ================================================================== Lv.0

	@Nested
	@DisplayName("Lv.0 未转职")
	class Untrained {

		@Test
		@DisplayName("生存、跟随、背包这些一件都不少")
		void keepsEverySurvivalTool() {
			List<String> seen = visible(untrained());
			// 生存和跟随是每只随从从第一天起就有的，职业系统没有资格收回。
			for (String common : TRULY_COMMON) {
				assertTrue(seen.contains(common), common + " 是通用能力，不该被锁掉");
			}
		}

		@Test
		@DisplayName("看不到守卫的主动职业工具")
		void cannotSeeGuardTools() {
			List<String> seen = visible(untrained());
			for (String guard : GUARD_ONLY) {
				assertFalse(seen.contains(guard),
					guard + " 是守卫的主动能力，Lv.0 不该有");
			}
		}

		@Test
		@DisplayName("有基础施工和训练期工具，没有参数化设计")
		void hasBasicBuildingButNotTheParametricLine() {
			List<String> seen = visible(untrained());
			for (String training : TRAINING_BLUEPRINT) {
				assertTrue(seen.contains(training),
					training + " 是新手训练第五项要用的，锁掉会让训练卡死");
			}
			for (String basic : BASIC_BUILDING) {
				assertTrue(seen.contains(basic),
					basic + " 是照模板盖房，所有随从都有");
			}
			assertFalse(seen.contains("blueprint.design"),
				"参数化设计是工程师的成长线");
		}

		@Test
		@DisplayName("看不到工程师的高级工具")
		void cannotSeeEngineerTools() {
			List<String> seen = visible(untrained());
			for (String engineer : ENGINEER_ONLY) {
				assertFalse(seen.contains(engineer), engineer);
			}
		}

		@Test
		@DisplayName("「造一个三层带塔楼的仓库」——这些 schema 一条都不会发过去")
		void theThreeStoreyWarehouseRequestHasNoToolsToAnswerWith() {
			List<String> seen = visible(untrained());
			assertFalse(seen.contains("blueprint.set_floors"));
			assertFalse(seen.contains("blueprint.add_module"));
			assertFalse(seen.contains("blueprint.design"));
		}
	}

	// ================================================================== 职业隔离

	@Nested
	@DisplayName("职业隔离：等级再高也不越界")
	class Isolation {

		@Test
		@DisplayName("守卫 Lv.1 看不到工程师工具")
		void guardOneSeesNoEngineerTools() {
			List<String> seen = visible(at(SquireProfession.GUARD, 1));
			for (String engineer : ENGINEER_ONLY) {
				assertFalse(seen.contains(engineer), engineer);
			}
		}

		@Test
		@DisplayName("守卫 Lv.10 仍然看不到工程师工具")
		void guardTenStillSeesNoEngineerTools() {
			List<String> seen = visible(at(SquireProfession.GUARD, 10));
			for (String engineer : ENGINEER_ONLY) {
				assertFalse(seen.contains(engineer),
					"满级守卫拿到了 " + engineer + "——判据把等级当成了唯一条件");
			}
		}

		@Test
		@DisplayName("工程师 Lv.1 看不到守卫工具")
		void engineerOneSeesNoGuardTools() {
			List<String> seen = visible(at(SquireProfession.ENGINEER, 1));
			for (String guard : GUARD_ONLY) {
				assertFalse(seen.contains(guard), guard);
			}
		}

		@Test
		@DisplayName("工程师 Lv.10 仍然看不到守卫的战斗姿态")
		void engineerTenNeverGetsCombatStance() {
			List<String> seen = visible(at(SquireProfession.ENGINEER, 10));
			assertFalse(seen.contains("combat.set_stance"),
				"Lv.10 >= 8 就给了战斗姿态——这正是「只看等级」会犯的错");
			assertFalse(seen.contains("combat.supplies"));
		}

		@Test
		@DisplayName("两个职业都保留全部通用工具")
		void bothProfessionsKeepEveryCommonTool() {
			for (var data : List.of(at(SquireProfession.GUARD, 10),
					at(SquireProfession.ENGINEER, 10))) {
				List<String> seen = visible(data);
				for (String common : TRULY_COMMON) {
					assertTrue(seen.contains(common),
						data.profession().id() + " 丢了通用能力 " + common);
				}
			}
		}

		@Test
		@DisplayName("守卫拿不到训练期蓝图——盖房子不是他这条线的事")
		void guardsLoseTheBlueprintTools() {
			List<String> seen = visible(at(SquireProfession.GUARD, 10));
			for (String training : TRAINING_BLUEPRINT) {
				assertFalse(seen.contains(training), training);
			}
		}

		@Test
		@DisplayName("守卫丢掉蓝图工具之后，通用命令能力一件没少")
		void removingBlueprintToolsDoesNotBreakCommonCommands() {
			List<String> seen = visible(at(SquireProfession.GUARD, 10));
			for (String common : TRULY_COMMON) {
				assertTrue(seen.contains(common), common);
			}
			// 通用命令类（要主人确认的那些）照旧保留。
			assertTrue(seen.contains("minecraft.command.setblock"));
			assertTrue(seen.contains("worldedit.propose_fill_selection"));
		}

		@Test
		@DisplayName("工程师保留被动自卫所需的一切，但拿不到主动接战工具")
		void engineersKeepPassiveDefenceButNotActiveCombat() {
			List<String> seen = visible(at(SquireProfession.ENGINEER, 10));
			// 被动自卫（挨打还手、吃药、穿装备）根本不是工具，在实体 AI 里；
			// 这里能验的是「和它相关的通用工具一件没少」。
			assertTrue(seen.contains("heal.now"), "自己回血是生存能力");
			assertTrue(seen.contains("aid.owner"), "救主人是生存能力");
			assertTrue(seen.contains("inventory.give"));
			assertTrue(seen.contains("guard.stop"), "叫停护卫策略永远要有出路");
			assertFalse(seen.contains("guard.start"), "主动发起护卫是守卫的事");
			assertFalse(seen.contains("combat.attack_target"),
				"主动指定目标开打是守卫的事");
		}
	}

	// ================================================================== 等级闸

	@Nested
	@DisplayName("工程师逐级解锁")
	class EngineerLevels {

		private boolean sees(int level, String tool) {
			return visible(at(SquireProfession.ENGINEER, level)).contains(tool);
		}

		@Test
		@DisplayName("Lv.2 看不到旋转，Lv.3 才看得到")
		void rotationAtThree() {
			assertFalse(sees(2, "blueprint.rotate"));
			assertTrue(sees(3, "blueprint.rotate"));
		}

		@Test
		@DisplayName("Lv.4 有材料分区，但还没有结构变体")
		void materialRegionsAtFourVariantsNotYet() {
			assertTrue(sees(4, "blueprint.set_material_region"));
			assertFalse(sees(4, "blueprint.set_variant"));
		}

		@Test
		@DisplayName("Lv.5 结构变体")
		void variantsAtFive() {
			assertTrue(sees(5, "blueprint.set_variant"));
		}

		@Test
		@DisplayName("Lv.6 多层")
		void floorsAtSix() {
			assertFalse(sees(5, "blueprint.set_floors"));
			assertTrue(sees(6, "blueprint.set_floors"));
		}

		@Test
		@DisplayName("Lv.7 镜像与附属模块")
		void mirrorAndModulesAtSeven() {
			assertFalse(sees(6, "blueprint.mirror"));
			assertTrue(sees(7, "blueprint.mirror"));
			assertTrue(sees(7, "blueprint.add_module"));
		}

		@Test
		@DisplayName("Lv.9 预设库")
		void presetsAtNine() {
			assertFalse(sees(8, "blueprint.save_preset"));
			assertTrue(sees(9, "blueprint.save_preset"));
			assertTrue(sees(9, "blueprint.load_preset"));
		}

		@Test
		@DisplayName("Lv.10 拿到这条线上的全部工具")
		void tenHasEverything() {
			for (String engineer : ENGINEER_ONLY) {
				assertTrue(sees(10, engineer), engineer);
			}
		}

		@Test
		@DisplayName("Lv.2 和 Lv.8 没有<b>新</b>工具——能力不必一一对应到工具")
		void someLevelsUnlockNoNewTool() {
			// Lv.8「模块化蓝图」放开的是服务端的模块数量上限，不是一个新动词；
			// Lv.10 同理。硬造一个工具出来只会让目录更长而没有新意思。
			assertEquals(visible(at(SquireProfession.ENGINEER, 7)),
				visible(at(SquireProfession.ENGINEER, 8)));
			assertEquals(visible(at(SquireProfession.ENGINEER, 9)),
				visible(at(SquireProfession.ENGINEER, 10)));
		}
	}

	@Nested
	@DisplayName("守卫逐级解锁")
	class GuardLevels {

		private boolean sees(int level, String tool) {
			return visible(at(SquireProfession.GUARD, level)).contains(tool);
		}

		@Test
		@DisplayName("Lv.1 就有主动接战与长期护卫")
		void activeCombatFromLevelOne() {
			assertTrue(sees(1, "guard.start"));
			assertTrue(sees(1, "combat.attack_target"));
		}

		@Test
		@DisplayName("Lv.5 补给清点")
		void suppliesAtFive() {
			assertFalse(sees(4, "combat.supplies"));
			assertTrue(sees(5, "combat.supplies"));
		}

		@Test
		@DisplayName("Lv.8 战斗姿态")
		void stanceAtEight() {
			assertFalse(sees(7, "combat.set_stance"));
			assertTrue(sees(8, "combat.set_stance"));
		}

		@Test
		@DisplayName("自动行为不做成工具：威胁判断、拦截、守护协议都没有对应条目")
		void automaticBehavioursHaveNoTools() {
			// 玩家不会「调用」拦截。为了凑齐能力表而把它们工具化，只会让模型
			// 去调一个什么都不做的东西。
			for (String name : ToolGate.all().keySet()) {
				assertFalse(name.contains("threat") || name.contains("intercept")
					|| name.contains("protocol"), name + " 不该是一个工具");
			}
		}
	}

	// ================================================================== 动态

	@Nested
	@DisplayName("目录跟着状态走，不缓存")
	class Dynamic {

		@Test
		@DisplayName("Lv.0 转工程师之后目录立刻变")
		void choosingAProfessionChangesTheCatalogAtOnce() {
			ProfessionData data = untrained();
			assertFalse(visible(data).contains("blueprint.design"));

			data.setProfession(SquireProfession.ENGINEER);
			assertTrue(visible(data).contains("blueprint.design"),
				"同一份档案对象改完职业，下一次取目录就该变");
		}

		@Test
		@DisplayName("Lv.2 升到 Lv.3，旋转立刻出现——不必重登")
		void promotingRevealsRotationImmediately() {
			ProfessionData data = at(SquireProfession.ENGINEER, 2);
			assertFalse(visible(data).contains("blueprint.rotate"));

			data.level = 3;
			assertTrue(visible(data).contains("blueprint.rotate"));
		}

		@Test
		@DisplayName("卸任之后职业工具立刻收回")
		void forgettingTakesTheToolsBack() {
			ProfessionData data = at(SquireProfession.ENGINEER, 10);
			assertTrue(visible(data).contains("blueprint.mirror"));

			data.forgetProfession();
			assertFalse(visible(data).contains("blueprint.mirror"));
			assertTrue(visible(data).contains("blueprint.place"),
				"基础施工照旧还在——它从来就不是职业能力");
		}
	}

	// ================================================================== 体积

	@Nested
	@DisplayName("裁剪确实减小了上下文")
	class Size {

		/** 目录序列化成提示词那一段之后有多少字符。 */
		private int chars(ProfessionData data) {
			int total = 0;
			for (ToolDescriptor tool : ToolGate.filter(catalog(), data)) {
				total += tool.name().length() + tool.description().length() + 4;
			}
			return total;
		}

		@Test
		@DisplayName("低等级的目录严格小于全量")
		void lowLevelCatalogsAreSmaller() {
			int full = catalog().size();
			assertTrue(visible(untrained()).size() < full, "Lv.0 该比全量少");
			assertTrue(visible(at(SquireProfession.GUARD, 1)).size() < full);
			assertTrue(visible(at(SquireProfession.ENGINEER, 1)).size() < full);
			assertTrue(chars(untrained()) < chars(at(SquireProfession.ENGINEER, 10)),
				"字符数也要真的更少，不能只是条目数少");
		}

		/**
		 * 转职之后，等级只会让目录变大。
		 *
		 * <p>起点刻意从 Lv.1 而不是未转职算起：<b>转职本身是一次交换，不是纯增长</b>。
		 * 守卫用蓝图换来主动接战，工程师用主动接战换来整条参数化蓝图线——这正是
		 * 职业系统要做的事。把未转职当成基线会得出「守卫 Lv.1 反而退步了」，
		 * 那是断言错了，不是实现错了。</p>
		 */
		@Test
		@DisplayName("转职之后成长是单调的：等级越高，看得见的工具只多不少")
		void theCatalogOnlyGrowsWithLevel() {
			for (var profession : SquireProfession.values()) {
				int previous = visible(at(profession, 1)).size();
				for (int level = 2; level <= 10; level++) {
					int now = visible(at(profession, level)).size();
					assertTrue(now >= previous,
						profession.id() + " Lv" + level + " 反而少了工具");
					previous = now;
				}
			}
		}

		@Test
		@DisplayName("转职是交换：守卫拿主动接战换掉蓝图，两边都真的发生了")
		void choosingAProfessionTradesRatherThanOnlyAdds() {
			List<String> zero = visible(untrained());
			List<String> guard = visible(at(SquireProfession.GUARD, 1));
			// 交换发生在<b>参数化</b>那一侧：守卫拿走自己报尺寸的裸盒子，换来主动接战。
			// 照模板盖房两边都有——那是基础本事，不参与这次交换。
			assertTrue(zero.contains("build.structure")
				&& !guard.contains("build.structure"), "守卫应当失去训练期的裸盒子");
			assertTrue(zero.contains("blueprint.place")
				&& guard.contains("blueprint.place"),
				"照模板盖房两边都该有：面板按钮一直能点，聊天里也必须能说");
			assertTrue(!zero.contains("guard.start")
				&& guard.contains("guard.start"), "守卫应当换来主动护卫");
		}

		/**
		 * 各典型状态下的目录规模。断言只有一条（低等级必须更小），其余数字打印出来
		 * 供人看——这一节的价值在于让「§23 到底省了多少」这个问题有一个可复现的答案。
		 */
		@Test
		@DisplayName("打印各状态的目录规模")
		void reportCatalogSizes() {
			StringBuilder report = new StringBuilder("\n[§23] 目录规模\n");
			report.append(String.format("  %-22s %5s %8s%n", "state", "tools", "chars"));
			record Row(String label, ProfessionData data) { }
			List<Row> rows = new ArrayList<>();
			rows.add(new Row("ALL (before)", null));
			rows.add(new Row("Untrained Lv.0", untrained()));
			for (int level : new int[] {1, 5, 8, 10}) {
				rows.add(new Row("Guard Lv." + level,
					at(SquireProfession.GUARD, level)));
			}
			for (int level : new int[] {1, 3, 5, 7, 10}) {
				rows.add(new Row("Engineer Lv." + level,
					at(SquireProfession.ENGINEER, level)));
			}
			for (Row row : rows) {
				int tools;
				int size;
				if (row.data() == null) {
					// 「过滤之前」这一行要的是<b>原始目录</b>，不能拿
					// chars(null) 充数：§23.2 之后 null 表示「查不到这只随从」，
					// 是 fail-closed 的最小集合，恰好是全量的反面。
					tools = catalog().size();
					size = 0;
					for (ToolDescriptor tool : catalog()) {
						size += tool.name().length() + tool.description().length() + 4;
					}
				} else {
					tools = visible(row.data()).size();
					size = chars(row.data());
				}
				report.append(String.format("  %-22s %5d %8d%n", row.label(), tools,
					size));
			}
			System.out.println(report);
			assertTrue(visible(untrained()).size() < catalog().size());
		}
	}
}
