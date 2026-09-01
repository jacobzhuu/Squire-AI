package dev.squire.server.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.squire.common.protocol.ToolDescriptor;

/**
 * 按这句话裁剪工具目录。
 *
 * <p>裁剪的收益是首 token 更快、干扰更少；它的风险是<b>漏掉一个本该有的工具</b>——
 * 表现出来就是「他突然不会这件事了」，比慢一点糟糕得多。所以这里的用例大半是在
 * 盯着各种"宁可全发"的退路，而不是盯着裁得多干净。</p>
 */
class ToolRelevanceTest {

	/** 生产环境里实际存在的那些名字（裁剪是按名字前缀做的）。 */
	private static final List<String> REAL_TOOL_NAMES = List.of(
		"query.status", "query.player", "query.inventory", "query.equipment",
		"items.fulfill", "items.edit", "inventory.equip", "inventory.unequip",
		"inventory.acquire_self", "inventory.pickup_nearby", "inventory.give",
		"minecraft.command.give", "minecraft.command.effect",
		"minecraft.command.teleport", "minecraft.command.fill",
		"minecraft.command.setblock", "minecraft.command.summon_safe",
		"world.set_time", "world.set_weather",
		"world.locate_structure", "world.locate_biome",
		"memory.remember_location", "memory.recall_location", "memory.list_locations",
		"memory.forget_location", "memory.set_home", "memory.register_container",
		"container.inspect", "container.sort",
		"guard.start", "guard.stop", "aid.owner", "heal.now",
		"combat.attack_target", "combat.set_style", "entity.scan_nearby",
		"crafting.craft", "crafting.recipe_of",
		"build.structure", "blueprint.place", "blueprint.build", "project.start",
		"worldedit.propose_fill_selection", "navigation.move_to",
		"navigation.come_to_owner", "player.teleport");

	private static List<ToolDescriptor> catalog() {
		List<ToolDescriptor> out = new ArrayList<>();
		for (String name : REAL_TOOL_NAMES) {
			out.add(new ToolDescriptor(name, "does " + name, List.of()));
		}
		return out;
	}

	private static List<String> namesOf(List<ToolDescriptor> tools) {
		return tools.stream().map(ToolDescriptor::name).toList();
	}

	private static List<String> select(String message) {
		return namesOf(ToolRelevance.select(message, catalog(), false));
	}

	// ------------------------------------------------------------------ 裁得准

	@Test
	void aLocationQuestionKeepsTheLocateToolsAndDropsTheRest() {
		var picked = select("[context] hp=20\n[player] 最近的远古城市在哪");
		assertTrue(picked.contains("world.locate_structure"));
		assertTrue(picked.contains("world.locate_biome"));
		assertFalse(picked.contains("items.edit"), "问路用不上改附魔");
		assertFalse(picked.contains("blueprint.build"), "问路用不上施工");
		assertTrue(picked.size() < REAL_TOOL_NAMES.size(), "应该真的裁掉了一些");
	}

	@Test
	void aGearRequestKeepsTheItemTools() {
		var picked = select("[player] 给我一套满配的下界合金套装");
		assertTrue(picked.contains("items.fulfill"));
		assertTrue(picked.contains("items.edit"));
		assertFalse(picked.contains("world.set_weather"));
	}

	@Test
	void aFightRequestKeepsCombatAndAwareness() {
		var picked = select("[player] 打那只苦力怕");
		assertTrue(picked.contains("combat.attack_target"));
		assertTrue(picked.contains("guard.start"));
		assertTrue(picked.contains("entity.scan_nearby"));
	}

	/** 一句话跨两个话题时两边都要带上，不能只挑一个。 */
	@Test
	void aRequestSpanningTwoTopicsKeepsBoth() {
		var picked = select("[player] 把时间调成白天，然后给我一把铁镐");
		assertTrue(picked.contains("world.set_time"));
		assertTrue(picked.contains("items.fulfill"));
	}

	// --------------------------------------------------------------- 宁可全发

	/** 核心查询工具任何时候都在——它们是「答不上来时」的兜底。 */
	@Test
	void theCoreQueryToolsSurviveEveryTrim() {
		for (String message : new String[] {
				"[player] 最近的村庄在哪", "[player] 给我火把", "[player] 打怪"}) {
			var picked = select(message);
			for (String core : new String[] {
					"query.status", "query.player", "query.inventory",
					"query.equipment"}) {
				assertTrue(picked.contains(core),
					() -> core + " must survive: " + message);
			}
		}
	}

	/** 一个话题都没命中时发全量——发空目录等于当场阉割掉全部能力。 */
	@Test
	void anUnrecognisedSentenceKeepsOnlyABoundedSafeCore() {
		for (String message : List.of("[player] 你今天心情怎么样", "[player] asdfgh")) {
			var picked = select(message);
			assertTrue(picked.size() <= ToolRelevance.DEFAULT_CANDIDATE_LIMIT);
			assertTrue(picked.contains("query.status"));
			assertTrue(picked.contains("query.player"));
		}
	}

	/**
	 * 后续轮次（跑过工具、或正在重规划）一律发全量：上一轮之所以要重来，
	 * 很可能正是因为缺了某个工具。
	 */
	@Test
	void followUpTurnsKeepTheObservedToolAndStayBounded() {
		String replan = "[context] hp=20\n[player] 最近的远古城市在哪"
			+ "\n[turn_state] REPLAN; replan 1/2\n[structured_observations]\nerror: x";
		var picked = select(replan);
		assertTrue(picked.contains("world.locate_structure"));
		assertTrue(picked.size() <= ToolRelevance.DEFAULT_CANDIDATE_LIMIT);
	}

	@Test
	void aSmallCatalogIsNeverTrimmed() {
		List<ToolDescriptor> few = catalog().subList(0,
			ToolRelevance.MIN_CATALOG_TO_TRIM - 1);
		assertEquals(few.size(),
			ToolRelevance.select("[player] 最近的村庄在哪", few, false).size());
	}

	@Test
	void theFullFlagAndEmptyInputsAreHandled() {
		assertEquals(REAL_TOOL_NAMES.size(),
			ToolRelevance.select("[player] 给我火把", catalog(), true).size());
		assertTrue(ToolRelevance.select(null, catalog(), false).size()
			<= ToolRelevance.DEFAULT_CANDIDATE_LIMIT);
		assertTrue(ToolRelevance.select("[player] x", List.of(), false).isEmpty());
	}

	@Test
	void externalToolIsSelectedFromItsOwnMetadataWithoutABuiltInTopic() {
		List<ToolDescriptor> tools = new ArrayList<>(catalog());
		tools.add(new ToolDescriptor("weatherbox:forecast", "查询服务器附近天气预报",
			List.of(), List.of("气象"), List.of("天气预报"), true));
		var picked = ToolRelevance.select("[player] 帮我查一下天气预报", tools,
			false, 12).stream().map(ToolDescriptor::name).toList();
		assertTrue(picked.contains("weatherbox:forecast"), picked::toString);
		assertTrue(picked.size() <= 12);
	}

	// ------------------------------------------------------------- 防止漂移

	/**
	 * <b>每个工具都必须至少属于一个话题。</b>
	 *
	 * <p>新加了工具却忘了归类时，它会永远不被选中——玩家看到的是「这个能力时灵时
	 * 不灵」，而且没有任何报错。这条测试让那种疏漏在构建时就红掉。</p>
	 */
	@Test
	void everyToolBelongsToAtLeastOneTopic() {
		var topics = ToolRelevance.topicsOf(catalog());
		List<String> orphans = new ArrayList<>();
		topics.forEach((name, owning) -> {
			if (owning.isEmpty()) {
				orphans.add(name);
			}
		});
		assertTrue(orphans.isEmpty(),
			"这些工具没有归入任何话题，会永远选不中：" + orphans);
	}
}
