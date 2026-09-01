package dev.squire.server.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import dev.squire.server.profession.CombatStance;
import dev.squire.server.profession.ProfessionAbility;
import dev.squire.server.profession.ProfessionConfig;
import dev.squire.server.profession.ProfessionData;
import dev.squire.server.profession.SquireProfession;
import dev.squire.server.profile.SquireProfile;
import dev.squire.server.profile.Trait;

/**
 * 守卫等级真的<b>改变了行为</b>吗。
 *
 * <p>设计文档 §31.1 的那句话是这个职业的全部价值：「装备决定战斗上限，等级决定
 * 他是否会正确使用这些装备」。所以这里逐级验的是<b>开关</b>——到没到那一级，
 * 他会不会去做那件事——而不是任何一个战力数字。</p>
 *
 * <p>还有一条同样重要的红线：<b>没有职业的随从行为一点不变</b>。职业系统上线不该
 * 让一只昨天还会用弓的伙伴今天忘了怎么用。每一组测试都带着这一条的对照。</p>
 */
class GuardProfessionBehaviourTest {

	private final ProfessionConfig config = ProfessionConfig.defaults();

	private static ProfessionData guard(int level) {
		ProfessionData data = new ProfessionData();
		data.setProfession(SquireProfession.GUARD);
		data.level = level;
		return data;
	}

	private static ProfessionData engineer(int level) {
		ProfessionData data = new ProfessionData();
		data.setProfession(SquireProfession.ENGINEER);
		data.level = level;
		return data;
	}

	// ================================================================== 选武器

	@Nested
	@DisplayName("选武器的闸门（Lv.4 弓 / Lv.5 自动切换）")
	class Weapons {

		@Test
		@DisplayName("没有职业的随从闸全开——行为和职业系统上线之前一模一样")
		void noProfessionMeansNoNewLimits() {
			assertSame(CombatStyle.Gates.EVERYTHING, GuardRuntime.weaponGates(null));
			assertSame(CombatStyle.Gates.EVERYTHING,
				GuardRuntime.weaponGates(new ProfessionData()));
		}

		@Test
		@DisplayName("工程师不受守卫的选武器限制")
		void otherProfessionsAreNotGated() {
			assertSame(CombatStyle.Gates.EVERYTHING,
				GuardRuntime.weaponGates(engineer(10)));
		}

		@Test
		@DisplayName("守卫 Lv.1–3 只会近战，绝不自己去翻弓出来")
		void lowLevelGuardsNeverPickUpABow() {
			for (int level = 1; level <= 3; level++) {
				assertFalse(GuardRuntime.weaponGates(guard(level)).bow(),
					"Lv" + level + " 还不会用弓");
			}
		}

		@Test
		@DisplayName("Lv.4 会用弓，但还不会按敌人换武器")
		void levelFourUsesTheBowByDistanceOnly() {
			CombatStyle.Gates gates = GuardRuntime.weaponGates(guard(4));
			assertTrue(gates.bow());
			assertFalse(gates.autoSwitch(), "自动换武器是 Lv.5");
		}

		@Test
		@DisplayName("Lv.5 起两条都开")
		void levelFiveSwitchesWeaponsByTarget() {
			CombatStyle.Gates gates = GuardRuntime.weaponGates(guard(5));
			assertTrue(gates.bow());
			assertTrue(gates.autoSwitch());
		}

		@Test
		@DisplayName("自动换武器永远蕴含会用弓——不会出现「会换但不会用」的怪状态")
		void autoSwitchImpliesBow() {
			assertFalse(CombatStyle.Gates.of(false, true).autoSwitch());
		}

		@Test
		@DisplayName("三条战斗路（护卫 / 自主反击 / 指定目标）问的是同一个闸门")
		void everyCombatPathAsksTheSameGate() {
			// 自主反击和「去打那只怪」原来调的是不带闸门的重载，于是一只 Lv.1 守卫
			// 在那两条路上照样会掏弓——等级限制只拦住了三条路里的一条。
			for (int level = 1; level <= 10; level++) {
				assertEquals(GuardRuntime.weaponGates(guard(level)),
					CombatStyle.gatesFor(guard(level)), "Lv" + level);
			}
			assertSame(CombatStyle.Gates.EVERYTHING, CombatStyle.gatesFor(null));
			assertSame(CombatStyle.Gates.EVERYTHING, CombatStyle.gatesFor(engineer(10)));
		}
	}

	// ============================================================== 面板上那一档

	/**
	 * 面板显示的档位<b>不许说谎</b>。
	 *
	 * <p>这一组守的是「行为页写着什么，他就做什么」。之前这条不成立：档位和「玩家
	 * 亲手插了武器」是两套状态，而战斗里后者压过前者，于是面板写着「只用弓」的他
	 * 攥着剑、写着「只近战」的他举着弓。</p>
	 */
	@Nested
	@DisplayName("面板档位（显示 / 循环）")
	class PanelMode {

		@Test
		@DisplayName("选了明确档位之后，插没插过武器都不改变面板显示")
		void explicitStyleOutranksTheManualWeaponLock() {
			for (boolean locked : new boolean[] {false, true}) {
				assertEquals(CombatStyle.Mode.BOW, CombatStyle.displayMode(
					CombatStyle.Style.RANGED, locked, CombatStyle.Gates.EVERYTHING));
				assertEquals(CombatStyle.Mode.MELEE, CombatStyle.displayMode(
					CombatStyle.Style.MELEE, locked, CombatStyle.Gates.EVERYTHING));
			}
		}

		@Test
		@DisplayName("武器锁只在自动档下是一档独立状态")
		void theLockOnlyMeansSomethingUnderAuto() {
			assertEquals(CombatStyle.Mode.AUTO, CombatStyle.displayMode(
				CombatStyle.Style.AUTO, false, CombatStyle.Gates.EVERYTHING));
			assertEquals(CombatStyle.Mode.MANUAL, CombatStyle.displayMode(
				CombatStyle.Style.AUTO, true, CombatStyle.Gates.EVERYTHING));
		}

		@Test
		@DisplayName("守卫 Lv.1–3 选「只用弓」，面板照实写「未解锁」而不是骗人")
		void lockedBowIsShownAsLocked() {
			for (int level = 1; level <= 3; level++) {
				assertEquals(CombatStyle.Mode.BOW_LOCKED,
					CombatStyle.displayMode(CombatStyle.Style.RANGED, false,
						GuardRuntime.weaponGates(guard(level))),
					"Lv" + level + " 还不会用弓，面板不能显示成会");
			}
			assertEquals(CombatStyle.Mode.BOW,
				CombatStyle.displayMode(CombatStyle.Style.RANGED, false,
					GuardRuntime.weaponGates(guard(4))));
		}

		@Test
		@DisplayName("按钮点一圈：自动 → 用弓 → 近战 → 自动，未解锁那档不多占一下")
		void cyclingWalksTheWholeRing() {
			assertEquals(CombatStyle.Style.RANGED,
				CombatStyle.nextStyle(CombatStyle.Mode.AUTO));
			assertEquals(CombatStyle.Style.MELEE,
				CombatStyle.nextStyle(CombatStyle.Mode.BOW));
			assertEquals(CombatStyle.Style.MELEE,
				CombatStyle.nextStyle(CombatStyle.Mode.BOW_LOCKED));
			assertEquals(CombatStyle.Style.AUTO,
				CombatStyle.nextStyle(CombatStyle.Mode.MELEE));
			assertEquals(CombatStyle.Style.AUTO,
				CombatStyle.nextStyle(CombatStyle.Mode.MANUAL));
		}

		@Test
		@DisplayName("每一档都有对应的界面文案——面板不会画出一个没名字的按钮")
		void everyModeHasALabel() {
			for (CombatStyle.Mode mode : CombatStyle.Mode.values()) {
				assertTrue(LANG.contains("\"squire.gui.combat_mode."
						+ mode.name().toLowerCase(java.util.Locale.ROOT) + "\""),
					mode + " 少了 lang key");
			}
		}
	}

	/** 中文语言文件的原文。客户端就是按 {@code Mode} 的名字拼 key 去查它的。 */
	private static final String LANG = readLang();

	private static String readLang() {
		try {
			return java.nio.file.Files.readString(java.nio.file.Path.of(
				"src/main/resources/assets/squire/lang/zh_cn.json"),
				java.nio.charset.StandardCharsets.UTF_8);
		} catch (java.io.IOException e) {
			throw new IllegalStateException("读不到 zh_cn.json", e);
		}
	}

	// ================================================================== 姿态

	@Nested
	@DisplayName("战斗姿态（Lv.8）")
	class Stance {

		@Test
		@DisplayName("Lv.8 之前一律均衡，也就是姿态系统出现之前的行为")
		void beforeLevelEightEverythingIsBalanced() {
			ProfessionData data = guard(7);
			data.stance = CombatStance.AGGRESSIVE.id();
			assertSame(CombatStance.BALANCED, GuardRuntime.stanceOf(data, config),
				"没解锁就不该生效，哪怕档案里存着一个值");
			assertSame(CombatStance.BALANCED, GuardRuntime.stanceOf(null, config));
		}

		@Test
		@DisplayName("Lv.8 之后玩家选的姿态说了算")
		void levelEightHonoursThePlayersChoice() {
			ProfessionData data = guard(8);
			data.stance = CombatStance.DEFENSIVE.id();
			assertSame(CombatStance.DEFENSIVE, GuardRuntime.stanceOf(data, config));
		}

		@Test
		@DisplayName("性格不得覆盖玩家明确选的姿态")
		void personalityNeverOverridesAnExplicitStance() {
			SquireProfile cautious = new SquireProfile();
			cautious.traits.add(Trait.CAUTIOUS.id());
			ProfessionData data = guard(8);
			data.stance = CombatStance.AGGRESSIVE.id();

			float withStance = GuardRuntime.retreatFraction(cautious, data, config,
				CombatStance.AGGRESSIVE);
			float traitOnly = GuardRuntime.retreatFraction(cautious);
			assertTrue(withStance < traitOnly,
				"选了进攻就该更晚收手，谨慎的性格不该把它拉回去");
		}

		@Test
		@DisplayName("没解锁姿态时，撤退线仍然由性格决定（老行为原样保留）")
		void withoutTheAbilityTraitsStillDecide() {
			SquireProfile cautious = new SquireProfile();
			cautious.traits.add(Trait.CAUTIOUS.id());
			assertEquals(GuardRuntime.retreatFraction(cautious),
				GuardRuntime.retreatFraction(cautious, guard(3), config,
					CombatStance.AGGRESSIVE));
		}
	}

	// ================================================================== 追击

	@Nested
	@DisplayName("追击限制（Lv.3）与主人应急（Lv.7）")
	class Chase {

		@Test
		@DisplayName("Lv.3 之前是写死的 1.5 倍——没有职业的随从也是这个数")
		void beforeLevelThreeTheOldSlackApplies() {
			assertEquals(24.0, GuardRuntime.chaseLimit(null, config, 16,
				CombatStance.BALANCED, false));
			assertEquals(24.0, GuardRuntime.chaseLimit(guard(2), config, 16,
				CombatStance.BALANCED, false));
		}

		@Test
		@DisplayName("Lv.3 起由姿态决定，而均衡档恰好等于老行为")
		void levelThreeSwitchesToTheStanceTable() {
			assertEquals(24.0, GuardRuntime.chaseLimit(guard(3), config, 16,
				CombatStance.BALANCED, false), "均衡 = 1.5 倍 = 老行为");
			assertEquals(12.0, GuardRuntime.chaseLimit(guard(8), config, 16,
				CombatStance.DEFENSIVE, false));
			assertEquals(40.0, GuardRuntime.chaseLimit(guard(8), config, 16,
				CombatStance.AGGRESSIVE, false));
		}

		@Test
		@DisplayName("主人危急时无论哪一档都收回护卫半径以内")
		void anEmergencyPullsHimBack() {
			assertEquals(16.0, GuardRuntime.chaseLimit(guard(10), config, 16,
				CombatStance.AGGRESSIVE, true),
				"那一刻追一只跑远的杂鱼是纯粹的失职");
		}
	}

	// ================================================================== 装备意识

	@Nested
	@DisplayName("装备意识（Lv.2）：手上的武器快断了")
	class Durability {

		@Test
		@DisplayName("不可损坏的东西永远不算「快断了」")
		void indestructibleThingsNeverCount() {
			assertFalse(CombatStyle.nearlyBroken(0, 0), "maxDamage 为 0 = 打不坏");
		}

		@Test
		@DisplayName("剩不到一成耐久才算快断了")
		void onlyTheLastTenthCounts() {
			int max = 250; // 铁剑
			assertFalse(CombatStyle.nearlyBroken(0, max), "全新的不算");
			assertFalse(CombatStyle.nearlyBroken(max / 2, max), "打了一半不算");
			assertFalse(CombatStyle.nearlyBroken(max - 26, max), "剩 26 点还够用");
			assertTrue(CombatStyle.nearlyBroken(max - 25, max), "剩一成就该换了");
			assertTrue(CombatStyle.nearlyBroken(max - 1, max), "还剩 1 点当然要换");
		}

		@Test
		@DisplayName("耐久极小的东西也不会因为取整而永远「快断了」")
		void tinyDurabilitiesStillHaveARunway() {
			assertFalse(CombatStyle.nearlyBroken(0, 5), "5 点耐久的全新工具不该算快断");
			assertTrue(CombatStyle.nearlyBroken(4, 5));
		}
	}

	// ================================================================== 高威胁

	@Nested
	@DisplayName("高威胁判断（Lv.9）")
	class HighThreat {

		@Test
		@DisplayName("到 Lv.9 才认得出监守者这类打不过的东西")
		void theListOnlyMattersAtLevelNine() {
			assertFalse(guard(8).can(ProfessionAbility.GUARD_HIGH_THREAT_AWARENESS));
			assertTrue(guard(9).can(ProfessionAbility.GUARD_HIGH_THREAT_AWARENESS));
		}

		@Test
		@DisplayName("普通怪不在名单上——「更会判断打不过」不等于「什么都不敢打」")
		void ordinaryMobsAreStillFairGame() {
			assertFalse(config.isHighThreat("minecraft:zombie"));
			assertFalse(config.isHighThreat("minecraft:ravager"));
			assertTrue(config.isHighThreat("minecraft:warden"));
		}
	}

	// ================================================================== 生命上限

	@Nested
	@DisplayName("生命上限：等级给的是行为，不是战力")
	class Health {

		@Test
		@DisplayName("从 20 涨到 30，全程一共只多五颗心")
		void theWholeCurveIsFiveHearts() {
			assertEquals(20, config.guardMaxHealth(1));
			assertEquals(30, config.guardMaxHealth(10));
			assertEquals(10, config.guardMaxHealth(10) - config.guardMaxHealth(1),
				"满级也只是 15 颗心——绝不是 Boss 级血量");
		}

		@Test
		@DisplayName("曲线单调不减：没有哪一级升上去反而更脆")
		void theCurveNeverGoesDown() {
			for (int level = 2; level <= 10; level++) {
				assertTrue(config.guardMaxHealth(level)
					>= config.guardMaxHealth(level - 1), "Lv" + level);
			}
		}

		@Test
		@DisplayName("职业附加伤害极小，而且到 Lv.4 为止是 0")
		void bonusDamageStaysTiny() {
			for (int level = 1; level <= 4; level++) {
				assertEquals(0.0, config.guardBonusDamage(level), "Lv" + level);
			}
			assertEquals(1.5, config.guardBonusDamage(10),
				"满级也只有 +1.5，一把石剑的差距都不到");
		}
	}

	// ================================================================== 治疗时机

	@Nested
	@DisplayName("治疗时机（Lv.6）：先吃饭，再喝药")
	class Healing {

		@Test
		@DisplayName("Lv.6 之前进食阈值和没有职业的随从完全一样")
		void beforeLevelSixNothingChanges() {
			float base = dev.squire.server.item.SelfCare.TRIGGER_FRACTION;
			assertEquals(base, dev.squire.server.item.SelfCare
				.triggerFraction(null, config));
			assertEquals(base, dev.squire.server.item.SelfCare
				.triggerFraction(guard(5), config));
		}

		@Test
		@DisplayName("Lv.6 起走配置，而默认值就是那条老阈值——成长不该变成退步")
		void levelSixReadsTheConfigAndDefaultsToTheOldLine() {
			assertEquals(dev.squire.server.item.SelfCare.TRIGGER_FRACTION,
				dev.squire.server.item.SelfCare.triggerFraction(guard(6), config),
				"默认配置下 Lv.6 守卫不该比普通随从更晚吃东西");
			assertEquals(0.84, config.guardFoodHealThreshold);
		}

		@Test
		@DisplayName("服主想要设计文档那条更省粮的曲线，改一个数字就行")
		void ownersCanAskForTheLeanerCurve() {
			ProfessionConfig lean = ProfessionConfig.fromJson(
				com.google.gson.JsonParser.parseString(
					"{\"guard\": {\"foodHealThreshold\": 0.65}}").getAsJsonObject());
			assertEquals(0.65f, dev.squire.server.item.SelfCare
				.triggerFraction(guard(6), lean), 1e-6);
		}

		@Test
		@DisplayName("药水阈值比进食阈值低得多——掉一点血不许开药水")
		void potionsAreStrictlyForEmergencies() {
			assertTrue(config.guardPotionHealThreshold < config.guardFoodHealThreshold,
				"喝药的门槛必须比吃饭低，否则「先吃饭再喝药」就不成立");
			assertTrue(config.guardRetreatThreshold < config.guardPotionHealThreshold,
				"撤退线又比喝药线更低：药也救不回来才该走");
		}

		@Test
		@DisplayName("Lv.6 起用配置里的消耗品冷却，而且比默认更长")
		void theCooldownIsConfigurableAndLonger() {
			assertTrue(config.guardConsumableCooldownTicks
					> dev.squire.server.item.SelfCare.COOLDOWN_TICKS,
				"「战斗中无冷却连续喝药」是设计文档点名禁止的");
		}
	}
}
