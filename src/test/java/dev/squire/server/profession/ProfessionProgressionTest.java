package dev.squire.server.profession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import com.google.gson.JsonParser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 职业与成长系统的规则闸门。
 *
 * <p>这一整份测试的目的只有一个：<b>设计文档里写给玩家的每一条承诺，都要有一段代码
 * 真的在执行它</b>。等级曲线、晋升材料、溢出上限、防刷衰减、能力解锁等级——任何一条
 * 被顺手改掉，这里就会红。</p>
 */
class ProfessionProgressionTest {

	private final ProfessionConfig config = ProfessionConfig.defaults();
	private final ProfessionService service = new ProfessionService(config);

	private ProfessionData guardAt(int level, int xp) {
		ProfessionData data = new ProfessionData();
		data.setProfession(SquireProfession.GUARD);
		data.level = level;
		data.xp = xp;
		return data;
	}

	// ================================================================== 等级曲线

	@Nested
	@DisplayName("等级与经验曲线（设计文档 §4.2）")
	class Curve {

		@Test
		@DisplayName("每一级的经验条与设计表逐格一致")
		void xpTableMatchesDesign() {
			int[] expected = {100, 200, 350, 550, 800, 1100, 1500, 2000, 2800};
			for (int level = 1; level <= 9; level++) {
				assertEquals(expected[level - 1], config.xpToNext(level),
					"Lv." + level + " → Lv." + (level + 1));
			}
		}

		@Test
		@DisplayName("满级的经验条是 0，不显示成「还差一点」")
		void maxLevelNeedsNothing() {
			assertEquals(0, config.xpToNext(SquireProfession.MAX_LEVEL));
			assertEquals(0, guardAt(10, 0).xpNeeded(config));
		}

		@Test
		@DisplayName("1 级练到 10 级总共 9400 点")
		void totalIsNineThousandFourHundred() {
			int total = 0;
			for (int level = 1; level <= 9; level++) {
				total += config.xpToNext(level);
			}
			assertEquals(9400, total);
		}
	}

	// ================================================================== 记账

	@Nested
	@DisplayName("经验记账与溢出（设计文档 §5）")
	class Xp {

		@Test
		@DisplayName("经验满了也绝不自动升级")
		void neverAutoPromotes() {
			ProfessionData data = guardAt(1, 0);
			service.award(data, 999);
			assertEquals(1, data.level, "等级必须由玩家亲手点");
			assertTrue(data.xpFull(config));
		}

		@Test
		@DisplayName("设计文档 §5 的例子：520/550 拿到 150 点 → 满条 + 溢出 120")
		void designDocExample() {
			ProfessionData data = guardAt(4, 520);
			ProfessionService.XpOutcome outcome = service.award(data, 150);

			assertEquals(550, data.xp);
			assertEquals(550, outcome.needed());
			assertEquals(120, data.overflowXp);
			assertEquals(0, outcome.wasted());
			assertTrue(outcome.justBecameReady());
		}

		@Test
		@DisplayName("晋升之后溢出转进新的经验条：Lv.5 是 120 / 800")
		void overflowCarriesIntoNextBar() {
			ProfessionData data = guardAt(4, 520);
			service.award(data, 150);
			ProfessionService.PromotionOutcome promoted = service.promote(data);

			assertTrue(promoted.promoted());
			assertEquals(5, data.level);
			assertEquals(120, data.xp);
			assertEquals(800, promoted.newNeeded());
			assertEquals(0, data.overflowXp);
		}

		@Test
		@DisplayName("溢出上限 = 下一级需求的 25%，多的如实报成浪费")
		void overflowIsCappedAtQuarterOfNextLevel() {
			ProfessionData data = guardAt(4, 550);   // 条已经满
			ProfessionService.XpOutcome outcome = service.award(data, 1000);

			assertEquals(200, data.overflowXp, "800 × 25%");
			assertEquals(800, outcome.wasted());
			assertTrue(outcome.overflowed());
		}

		@Test
		@DisplayName("攒满溢出也只能升一级，不会连升")
		void cappedOverflowCannotChainPromote() {
			ProfessionData data = guardAt(4, 550);
			service.award(data, 100000);
			service.promote(data);

			assertEquals(5, data.level);
			assertFalse(data.xpFull(config), "200 / 800 不该又是满的");
		}

		@Test
		@DisplayName("满级之后经验没有去处，如实返回一分没记")
		void maxLevelWastesEverything() {
			ProfessionData data = guardAt(10, 0);
			ProfessionService.XpOutcome outcome = service.award(data, 500);

			assertEquals(0, outcome.credited());
			assertEquals(500, outcome.wasted());
		}

		@Test
		@DisplayName("没有职业的随从不积累职业经验")
		void noProfessionNoXp() {
			ProfessionData data = new ProfessionData();
			assertFalse(service.award(data, 500).anythingHappened());
		}

		@Test
		@DisplayName("死亡不扣职业经验——这个类根本没有扣经验的入口")
		void deathDoesNotRollBack() {
			ProfessionData data = guardAt(6, 400);
			// 唯一会往回走的是玩家自己改行；没有任何「死亡」路径能碰到 xp。
			data.forgetProfession();
			assertNull(data.profession());
			assertEquals(0, data.level, "leaving a profession returns to training Lv0");
		}
	}

	// ================================================================== 晋升

	@Nested
	@DisplayName("晋升材料（设计文档 §4.3）")
	class Promotion {

		@Test
		@DisplayName("Lv.1→10 的材料表与设计表逐条一致")
		void costsMatchDesign() {
			assertCost(2, "minecraft:iron_ingot", 8);
			assertCost(3, "minecraft:gold_ingot", 8);
			assertCost(4, "minecraft:lapis_lazuli", 16);
			assertCost(5, "minecraft:diamond", 2);
			assertCost(6, "minecraft:emerald", 12);
			assertCost(7, "minecraft:diamond", 4);
			assertCost(8, "minecraft:netherite_scrap", 4);
			assertCost(9, "minecraft:diamond", 8);
		}

		private void assertCost(int target, String itemId, int count) {
			List<ProfessionConfig.ItemRequirement> cost =
				config.promotionCost(SquireProfession.GUARD, target);
			assertEquals(1, cost.size(), "Lv." + target);
			assertEquals(itemId, cost.get(0).itemId(), "Lv." + target);
			assertEquals(count, cost.get(0).count(), "Lv." + target);
		}

		@Test
		@DisplayName("Lv.10 是职业专属材料：守卫下界之星，工程师信标")
		void masterCostsAreProfessionSpecific() {
			assertEquals("minecraft:nether_star",
				config.promotionCost(SquireProfession.GUARD, 10).get(0).itemId());
			assertEquals("minecraft:beacon",
				config.promotionCost(SquireProfession.ENGINEER, 10).get(0).itemId());
		}

		@Test
		@DisplayName("经验不够时晋升被拒，而且说得出还差多少")
		void refusesWithoutXp() {
			ProfessionService.PromotionCheck check = service.checkPromotion(guardAt(3, 10));
			assertFalse(check.ok());
			assertTrue(check.reason().contains("还差"), check.reason());
			assertEquals(4, check.targetLevel());
		}

		@Test
		@DisplayName("满级之后不再提供晋升")
		void refusesAtMaxLevel() {
			assertFalse(service.checkPromotion(guardAt(10, 0)).ok());
			assertFalse(service.promote(guardAt(10, 0)).promoted());
		}

		@Test
		@DisplayName("晋升会解锁那一级的能力")
		void promotionUnlocksAbilities() {
			ProfessionData data = guardAt(3, config.xpToNext(3));
			ProfessionService.PromotionOutcome outcome = service.promote(data);

			assertTrue(outcome.promoted());
			assertEquals(List.of(ProfessionAbility.GUARD_BOW_PROFICIENCY),
				outcome.unlocked());
			assertTrue(data.can(ProfessionAbility.GUARD_BOW_PROFICIENCY));
		}
	}

	// ================================================================== 能力清单

	@Nested
	@DisplayName("能力清单必须与实现对齐（§25）")
	class Abilities {

		@Test
		@DisplayName("设计文档列出的能力 id 一个不少、一个不多")
		void idsMatchDesignDocument() {
			// of() 按「解锁等级，再按 id」排序，所以同为 Lv.5 的两条按字典序。
			assertEquals(List.of("guard.basic_melee", "guard.equipment_awareness",
					"guard.threat_evaluation", "guard.bow_proficiency",
					"guard.supply_awareness", "guard.weapon_switching",
					"guard.shield_proficiency", "guard.intercept", "guard.combat_stance",
					"guard.high_threat_awareness", "guard.guardian_protocol"),
				ProfessionAbility.of(SquireProfession.GUARD).stream()
					.map(ProfessionAbility::id).toList());
			assertEquals(List.of("engineer.basic_blueprint", "engineer.template_library_1",
					"engineer.blueprint_rotation", "engineer.material_regions",
					"engineer.structural_variants", "engineer.multi_floor",
					"engineer.blueprint_mirror", "engineer.optional_modules",
					"engineer.modular_blueprint", "engineer.blueprint_preset_library",
					"engineer.compound_blueprint"),
				ProfessionAbility.of(SquireProfession.ENGINEER).stream()
					.map(ProfessionAbility::id).toList());
		}

		@Test
		@DisplayName("解锁等级都落在 1..10 之内")
		void unlockLevelsAreInRange() {
			for (ProfessionAbility ability : ProfessionAbility.values()) {
				assertTrue(ability.unlockLevel() >= SquireProfession.MIN_LEVEL
						&& ability.unlockLevel() <= SquireProfession.MAX_LEVEL,
					ability.id());
			}
		}

		@Test
		@DisplayName("每一条都有真的说清做什么的一句话")
		void everyAbilityExplainsItself() {
			for (ProfessionAbility ability : ProfessionAbility.values()) {
				assertFalse(ability.displayName().isBlank(), ability.id());
				assertFalse(ability.summary().isBlank(), ability.id());
			}
		}

		@Test
		@DisplayName("别的职业的能力不会因为等级够了就会")
		void abilitiesDoNotLeakAcrossProfessions() {
			ProfessionData engineer = new ProfessionData();
			engineer.setProfession(SquireProfession.ENGINEER);
			engineer.level = 10;

			assertTrue(engineer.can(ProfessionAbility.ENGINEER_MULTI_FLOOR));
			assertFalse(engineer.can(ProfessionAbility.GUARD_SHIELD_PROFICIENCY));
		}

		@Test
		@DisplayName("下一个还没解锁的能力找得出来（面板要显示 Next Unlock）")
		void nextUnlockIsDiscoverable() {
			assertSame(ProfessionAbility.GUARD_INTERCEPT,
				ProfessionAbility.nextAfter(SquireProfession.GUARD, 6));
			assertNull(ProfessionAbility.nextAfter(SquireProfession.GUARD, 10));
		}
	}

	// ================================================================== 守卫经验

	@Nested
	@DisplayName("守卫经验与防刷（设计文档 §10）")
	class Guard {

		@Test
		@DisplayName("四档怪的基础经验是 2 / 4 / 8 / 20")
		void tierBaseXp() {
			assertEquals(2, GuardXp.award(config, "minecraft:zombie", false, 1, 0).finalXp());
			assertEquals(4, GuardXp.award(config, "minecraft:creeper", false, 1, 0).finalXp());
			assertEquals(8, GuardXp.award(config, "minecraft:blaze", false, 1, 0).finalXp());
			assertEquals(20, GuardXp.award(config, "minecraft:ravager", false, 1, 0).finalXp());
		}

		@Test
		@DisplayName("认不出的模组怪退回第 1 档，绝不假装认识")
		void unknownMobsFallBackToTierOne() {
			assertEquals(1, config.mobTier("somemod:unknown_horror"));
			assertEquals(2, GuardXp.award(config, "somemod:unknown_horror", false, 1, 0)
				.finalXp());
		}

		@Test
		@DisplayName("Boss 经验：监守者 120 / 凋灵 180 / 末影龙 250")
		void bossXp() {
			assertEquals(120, GuardXp.award(config, "minecraft:warden", false, 1, 0).finalXp());
			assertEquals(180, GuardXp.award(config, "minecraft:wither", false, 1, 0).finalXp());
			assertEquals(250,
				GuardXp.award(config, "minecraft:ender_dragon", false, 1, 0).finalXp());
		}

		@Test
		@DisplayName("同一种 Boss 第 2 次减半、第 3 次起只有四分之一")
		void bossRepeatDecay() {
			assertEquals(180, GuardXp.award(config, "minecraft:wither", false, 1, 0).finalXp());
			assertEquals(90, GuardXp.award(config, "minecraft:wither", false, 1, 1).finalXp());
			assertEquals(45, GuardXp.award(config, "minecraft:wither", false, 1, 2).finalXp());
			assertEquals(45, GuardXp.award(config, "minecraft:wither", false, 1, 9).finalXp());
		}

		@Test
		@DisplayName("保护主人 ×1.25")
		void protectionBonus() {
			assertEquals(5, GuardXp.award(config, "minecraft:creeper", true, 1, 0).finalXp());
		}

		@Test
		@DisplayName("五分钟窗口内同类怪：1–10 全额，11–25 半价，26–50 两成，51+ 五分")
		void repeatKillDecay() {
			assertEquals(1.00, GuardXp.repeatMultiplier(config, 1));
			assertEquals(1.00, GuardXp.repeatMultiplier(config, 10));
			assertEquals(0.50, GuardXp.repeatMultiplier(config, 11));
			assertEquals(0.50, GuardXp.repeatMultiplier(config, 25));
			assertEquals(0.20, GuardXp.repeatMultiplier(config, 26));
			assertEquals(0.20, GuardXp.repeatMultiplier(config, 50));
			assertEquals(0.05, GuardXp.repeatMultiplier(config, 51));
			assertEquals(0.05, GuardXp.repeatMultiplier(config, 5000));
		}

		@Test
		@DisplayName("刷怪塔：同一种怪杀到第 51 只，八格苦力怕也只剩 0 经验")
		void mobFarmStopsPaying() {
			assertEquals(0, GuardXp.award(config, "minecraft:creeper", false, 51, 0).finalXp());
		}

		@Test
		@DisplayName("最后一击算参与；站着看不算；打掉 20% 血算")
		void participationGate() {
			assertTrue(GuardXp.participated(config, true, 0, 20));
			assertFalse(GuardXp.participated(config, false, 3.9, 20));
			assertTrue(GuardXp.participated(config, false, 4.0, 20));
		}

		@Test
		@DisplayName("击杀窗口台账会随时间滑走")
		void killWindowSlides() {
			GuardCombatLedger ledger = new GuardCombatLedger();
			UUID agent = UUID.randomUUID();
			long window = config.guardRepeatKillWindowTicks;

			assertEquals(1, ledger.noteKill(agent, "minecraft:zombie", 0L, window));
			assertEquals(2, ledger.noteKill(agent, "minecraft:zombie", 100L, window));
			// 窗口之外的两条被丢掉，重新从 1 起
			assertEquals(1, ledger.noteKill(agent, "minecraft:zombie",
				window + 1000L, window));
			// 别的种类各算各的
			assertEquals(1, ledger.noteKill(agent, "minecraft:skeleton",
				window + 1000L, window));
		}

		@Test
		@DisplayName("参与度台账按目标累计伤害，并记住它有没有威胁过主人")
		void damageLedgerAccumulates() {
			GuardCombatLedger ledger = new GuardCombatLedger();
			UUID agent = UUID.randomUUID();
			UUID victim = UUID.randomUUID();

			ledger.noteDamage(agent, victim, "minecraft:zombie", 20, 3.0, false, 10L);
			ledger.noteDamage(agent, victim, "minecraft:zombie", 20, 2.0, true, 20L);

			GuardCombatLedger.Engagement taken = ledger.take(agent, victim);
			assertNotNull(taken);
			assertEquals(5.0, taken.damageDealt());
			assertTrue(taken.threatenedOwner());
			assertNull(ledger.take(agent, victim), "取走之后不该还能再取一次");
		}

		@Test
		@DisplayName("太久没动静的交战记录会被清掉")
		void staleEngagementsExpire() {
			GuardCombatLedger ledger = new GuardCombatLedger();
			UUID agent = UUID.randomUUID();
			ledger.noteDamage(agent, UUID.randomUUID(), "minecraft:zombie", 20, 1.0,
				false, 0L);
			ledger.pruneEngagements(agent, GuardCombatLedger.ENGAGEMENT_IDLE_TICKS + 1);
			assertTrue(ledger.open(agent).isEmpty());
		}

		@Test
		@DisplayName("生命曲线到 Lv.10 是 30，也就是 15 颗心")
		void hpCurve() {
			assertEquals(20, config.guardMaxHealth(1));
			assertEquals(24, config.guardMaxHealth(5));
			assertEquals(30, config.guardMaxHealth(10));
		}

		@Test
		@DisplayName("高威胁名单认得出监守者、凋灵、末影龙")
		void highThreatList() {
			assertTrue(GuardXp.highThreat(config, "minecraft:warden"));
			assertTrue(GuardXp.highThreat(config, "minecraft:wither"));
			assertTrue(GuardXp.highThreat(config, "minecraft:ender_dragon"));
			assertFalse(GuardXp.highThreat(config, "minecraft:zombie"));
		}
	}

	// ================================================================== 威胁评分

	@Nested
	@DisplayName("威胁评分与追击限制")
	class Threat {

		@Test
		@DisplayName("正在打主人的骷髅，赢过站得更近但谁也没盯的僵尸")
		void attackerOnOwnerWins() {
			double skeleton = ThreatEvaluator.score(new ThreatEvaluator.Candidate(
				true, true, 12, 12, 2, false), 16);
			double zombie = ThreatEvaluator.score(new ThreatEvaluator.Candidate(
				false, false, 2, 2, 1, false), 16);
			assertTrue(skeleton > zombie, skeleton + " vs " + zombie);
		}

		@Test
		@DisplayName("分数接近时，已经在打的那只有迟滞加分，不会来回换目标")
		void hysteresisKeepsTheCurrentTarget() {
			ThreatEvaluator.Candidate current = new ThreatEvaluator.Candidate(
				false, false, 6, 6, 1, true);
			ThreatEvaluator.Candidate other = new ThreatEvaluator.Candidate(
				false, false, 6, 6, 1, false);
			assertTrue(ThreatEvaluator.score(current, 16)
				> ThreatEvaluator.score(other, 16));
		}

		@Test
		@DisplayName("追击上限由姿态决定：防守最短、进攻最长")
		void chaseLimitFollowsStance() {
			assertEquals(12.0, ThreatEvaluator.chaseLimit(config, 16, CombatStance.DEFENSIVE));
			assertEquals(24.0, ThreatEvaluator.chaseLimit(config, 16, CombatStance.BALANCED));
			assertEquals(40.0, ThreatEvaluator.chaseLimit(config, 16, CombatStance.AGGRESSIVE));
		}

		@Test
		@DisplayName("均衡档等于姿态系统出现之前的 1.5 倍，老玩家察觉不到变化")
		void balancedMatchesLegacyBehaviour() {
			assertEquals(1.5, config.chaseFactor(CombatStance.BALANCED));
		}

		@Test
		@DisplayName("主人血量低或者被围住都算危急")
		void ownerEmergency() {
			assertTrue(ThreatEvaluator.ownerInDanger(config, 0.3, 0));
			assertTrue(ThreatEvaluator.ownerInDanger(config, 1.0, 3));
			assertFalse(ThreatEvaluator.ownerInDanger(config, 1.0, 1));
		}
	}

	// ================================================================== 工程师经验

	@Nested
	@DisplayName("工程师经验与防刷（设计文档 §14–§15）")
	class Engineer {

		private EngineerXp.Project project(String template, String size, int floors,
				boolean variant, int modules, boolean compound) {
			return new EngineerXp.Project(template, size, floors, variant, modules,
				compound);
		}

		@Test
		@DisplayName("五档工程的基础经验是 20 / 40 / 70 / 110 / 180")
		void tierBaseXp() {
			for (int tier = 1; tier <= 5; tier++) {
				assertEquals(new int[]{20, 40, 70, 110, 180}[tier - 1],
					config.projectBaseXp(tier));
			}
		}

		@Test
		@DisplayName("简单小屋是第 1 档，普通房子第 2 档")
		void templateTiers() {
			assertEquals(1, EngineerXp.tierOf(config,
				project("shed", "small", 1, false, 0, false)));
			assertEquals(2, EngineerXp.tierOf(config,
				project("house", "small", 1, false, 0, false)));
		}

		@Test
		@DisplayName("多层或大足印抬到第 3 档，带模块第 4 档，复合第 5 档")
		void structureRaisesTier() {
			assertEquals(3, EngineerXp.tierOf(config,
				project("house", "small", 2, false, 0, false)));
			assertEquals(3, EngineerXp.tierOf(config,
				project("house", "large", 1, false, 0, false)));
			assertEquals(4, EngineerXp.tierOf(config,
				project("house", "small", 1, false, 1, false)));
			assertEquals(5, EngineerXp.tierOf(config,
				project("outpost", "large", 1, false, 3, true)));
		}

		@Test
		@DisplayName("规模倍率 1.00 / 1.25 / 1.50 / 1.75，绝对上限 2.0")
		void sizeMultipliers() {
			assertEquals(1.00, config.sizeMultiplier("small"));
			assertEquals(1.25, config.sizeMultiplier("medium"));
			assertEquals(1.50, config.sizeMultiplier("large"));
			assertEquals(1.75, config.sizeMultiplier("very_large"));
			assertEquals(2.0, config.engineerMaxSizeMultiplier);
		}

		@Test
		@DisplayName("模块加成每个 +10%，最多 +40%")
		void moduleBonusIsCapped() {
			int four = EngineerXp.award(config,
				project("house", "small", 1, false, 4, false), 0).finalXp();
			int eight = EngineerXp.award(config,
				project("house", "small", 1, false, 8, false), 0).finalXp();
			assertEquals(four, eight, "第 5 个模块起不该再加");
			assertEquals(Math.round(110 * 1.40), four);
		}

		@Test
		@DisplayName("复合蓝图用更高的 Base XP，不再叠模块倍率")
		void compoundDoesNotStackModules() {
			EngineerXp.Award award = EngineerXp.award(config,
				project("outpost", "large", 1, false, 5, true), 0);
			assertEquals(1.0, award.moduleMultiplier());
			assertEquals(180, award.baseXp());
		}

		@Test
		@DisplayName("重复工程 100% / 75% / 50% / 25% / 10%")
		void repeatDecay() {
			assertEquals(1.00, EngineerXp.repeatMultiplier(config, 0));
			assertEquals(0.75, EngineerXp.repeatMultiplier(config, 1));
			assertEquals(0.50, EngineerXp.repeatMultiplier(config, 2));
			assertEquals(0.25, EngineerXp.repeatMultiplier(config, 3));
			assertEquals(0.10, EngineerXp.repeatMultiplier(config, 4));
			assertEquals(0.10, EngineerXp.repeatMultiplier(config, 40));
		}

		@Test
		@DisplayName("只换木头颜色不算新工程——签名里没有材料")
		void paletteIsNotPartOfTheSignature() {
			assertEquals(project("house", "medium", 2, true, 1, false).signature(),
				project("house", "medium", 2, true, 1, false).signature());
			// 结构一变，签名就该不同
			assertFalse(project("house", "medium", 2, true, 1, false).signature()
				.equals(project("house", "medium", 3, true, 1, false).signature()));
		}

		@Test
		@DisplayName("同款工程连盖，台账真的把倍率压下去")
		void repeatedProjectsPayLess() {
			ProfessionData data = new ProfessionData();
			data.setProfession(SquireProfession.ENGINEER);
			String signature = project("house", "small", 1, false, 0, false).signature();
			long window = config.engineerRepeatWindowTicks;

			assertEquals(0, data.recentProjectCount(signature, 0L, window));
			data.noteProject(signature, 0L);
			assertEquals(1, data.recentProjectCount(signature, 100L, window));
			data.noteProject(signature, 100L);
			assertEquals(2, data.recentProjectCount(signature, 200L, window));
			// 窗口滑走之后重新计
			assertEquals(0, data.recentProjectCount(signature, window + 1000L, window));
		}

		@Test
		@DisplayName("足印边长决定规模档位，按较长的那条边算")
		void sizeClassUsesTheLongerSide() {
			assertEquals("small", EngineerXp.sizeClassFor(9, 9));
			assertEquals("small", EngineerXp.sizeClassFor(13, 5));
			assertEquals("medium", EngineerXp.sizeClassFor(21, 9));
			assertEquals("large", EngineerXp.sizeClassFor(32, 9));
			assertEquals("very_large", EngineerXp.sizeClassFor(48, 48));
		}

		@Test
		@DisplayName("足印上限逐级放宽，Lv.10 到 48")
		void footprintByLevel() {
			assertEquals(9, config.maxFootprint(1));
			assertEquals(13, config.maxFootprint(2));
			assertEquals(17, config.maxFootprint(4));
			assertEquals(21, config.maxFootprint(5));
			assertEquals(32, config.maxFootprint(8));
			assertEquals(48, config.maxFootprint(10));
		}

		@Test
		@DisplayName("取消 / 失败 / 中断的工程一分不给（调用方拿到的是 NOTHING）")
		void cancelledProjectsPayNothing() {
			assertFalse(EngineerXp.award(config, null, 0).anything());
		}
	}

	// ================================================================== 配置化

	@Nested
	@DisplayName("配置化（设计文档 §26）")
	class Configurable {

		@Test
		@DisplayName("平衡数字可以被 JSON 覆盖，没写的项保持默认")
		void jsonOverridesOnlyWhatItMentions() {
			ProfessionConfig custom = ProfessionConfig.fromJson(JsonParser.parseString("""
				{
				  "general": { "overflowXpRatio": 0.5,
				               "xpRequiredPerLevel": [10,20,30,40,50,60,70,80,90] },
				  "guard": { "protectionXpBonus": 2.0,
				             "mobThreatTier": { "somemod:boss_rat": 4 } },
				  "engineer": { "moduleMultiplier": 0.25 }
				}
				""").getAsJsonObject());

			assertEquals(0.5, custom.overflowXpRatio);
			assertEquals(10, custom.xpToNext(1));
			assertEquals(2.0, custom.guardProtectionXpBonus);
			assertEquals(4, custom.mobTier("somemod:boss_rat"));
			assertEquals(0.25, custom.engineerModuleMultiplier);
			// 没提到的一律不动
			assertEquals(30, custom.guardMaxHealth(10));
			assertEquals(180, custom.projectBaseXp(5));
		}

		@Test
		@DisplayName("坏掉的一项只回退这一项，不会产生半张平衡表")
		void brokenEntriesFallBackAlone() {
			ProfessionConfig custom = ProfessionConfig.fromJson(JsonParser.parseString("""
				{ "general": { "xpRequiredPerLevel": [10, "oops", 30] },
				  "guard": { "protectionXpBonus": 1.75 } }
				""").getAsJsonObject());

			assertEquals(100, custom.xpToNext(1), "坏数组整条回退到默认");
			assertEquals(1.75, custom.guardProtectionXpBonus, "同一份文件里好的那项照常生效");
		}

		@Test
		@DisplayName("配置文件不存在是正常状态，直接用默认平衡表")
		void missingFileIsFine() {
			assertSame(ProfessionConfig.defaults(),
				ProfessionConfig.load(java.nio.file.Path.of("no", "such", "file.json")));
		}

		@Test
		@DisplayName("晋升材料可以整表换掉")
		void promotionItemsAreConfigurable() {
			ProfessionConfig custom = ProfessionConfig.fromJson(JsonParser.parseString("""
				{ "general": { "promotionItems": {
				    "2": [ {"item": "minecraft:bread", "count": 3} ] } } }
				""").getAsJsonObject());

			List<ProfessionConfig.ItemRequirement> cost =
				custom.promotionCost(SquireProfession.GUARD, 2);
			assertEquals("minecraft:bread", cost.get(0).itemId());
			assertEquals(3, cost.get(0).count());
		}
	}
}
