package dev.squire.server.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

/**
 * i18n integrity (spec §90): both locale files parse, cover the SAME key set,
 * and every value is a non-empty translated line.
 */
class LangFilesTest {

	private static Map<String, String> load(String path) {
		try (var in = LangFilesTest.class.getResourceAsStream(path)) {
			assertTrue(in != null, "missing lang resource " + path);
			return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
				.getAsJsonObject().entrySet().stream()
				.collect(Collectors.toMap(Map.Entry::getKey,
					e -> e.getValue().getAsString()));
		} catch (java.io.IOException e) {
			throw new IllegalStateException(e);
		}
	}

	@Test
	void enAndZhCoverIdenticalKeySets() {
		Map<String, String> en = load("/assets/squire/lang/en_us.json");
		Map<String, String> zh = load("/assets/squire/lang/zh_cn.json");
		Set<String> enOnly = new java.util.HashSet<>(en.keySet());
		enOnly.removeAll(zh.keySet());
		Set<String> zhOnly = new java.util.HashSet<>(zh.keySet());
		zhOnly.removeAll(en.keySet());
		assertTrue(enOnly.isEmpty() && zhOnly.isEmpty(),
			"key drift between locales; en-only=" + enOnly + " zh-only=" + zhOnly);
		assertEquals(en.size(), zh.size());
	}

	/**
	 * 聊天行要带前缀，玩家才知道这句话是谁说的。
	 *
	 * <p>只约束会出现在聊天框里的文案。按键名、分类名这类<b>界面标签</b>会出现在
	 * 原版的"控制"设置里，给它们加上"[侍从]"前缀反而是错的。</p>
	 */
	@Test
	void everyChatLineCarriesTheLocalePrefix() {
		Map<String, String> en = load("/assets/squire/lang/en_us.json");
		Map<String, String> zh = load("/assets/squire/lang/zh_cn.json");
		en.entrySet().stream().filter(e -> isChatLine(e.getKey()))
			.forEach(e -> assertTrue(e.getValue().startsWith("[Squire]"), e.getKey()));
		zh.entrySet().stream().filter(e -> isChatLine(e.getKey()))
			.forEach(e -> assertTrue(e.getValue().startsWith("[侍从]"), e.getKey()));
	}

	@Test
	void uiLabelsAreTranslatedAndCarryNoChatPrefix() {
		Map<String, String> en = load("/assets/squire/lang/en_us.json");
		Map<String, String> zh = load("/assets/squire/lang/zh_cn.json");
		for (Map<String, String> locale : java.util.List.of(en, zh)) {
			locale.entrySet().stream().filter(e -> !isChatLine(e.getKey()))
				.forEach(e -> {
					assertFalse(e.getValue().isBlank(), e.getKey());
					assertFalse(e.getValue().startsWith("["), e.getKey()
						+ " is a UI label, it must not carry the chat prefix");
				});
		}
		// 按键绑定必须两个语言都有，否则控制设置里会显示成原始 key。
		for (String key : new String[] {"key.squire.open_panel", "category.squire"}) {
			assertFalse(en.get(key) == null || en.get(key).isBlank(), key);
			assertFalse(zh.get(key) == null || zh.get(key).isBlank(), key);
		}
	}

	/** 聊天文案 vs 界面标签：按键/分类/面板界面标签不算聊天行，其余都会进聊天框。 */
	private static boolean isChatLine(String key) {
		return !key.startsWith("key.") && !key.startsWith("category.")
			&& !key.startsWith("squire.gui.") && !key.startsWith("item.");
	}

	/**
	 * 职业系统的每一个 {@code nameKey()} 都必须在两份语言文件里有对应的一行。
	 *
	 * <p>没有这一条，面板上会直接显示成
	 * {@code squire.gui.profession_ability.guard_intercept} 这种原始 key——
	 * 而那只会在玩家真的练到 Lv.7 的那一刻才被发现。</p>
	 */
	@Test
	void everyProfessionLabelIsTranslated() {
		Map<String, String> en = load("/assets/squire/lang/en_us.json");
		Map<String, String> zh = load("/assets/squire/lang/zh_cn.json");
		java.util.List<String> keys = new java.util.ArrayList<>();
		for (var profession : dev.squire.server.profession.SquireProfession.values()) {
			keys.add(profession.nameKey());
		}
		for (var stance : dev.squire.server.profession.CombatStance.values()) {
			keys.add(stance.nameKey());
		}
		for (var ability : dev.squire.server.profession.ProfessionAbility.values()) {
			keys.add(ability.nameKey());
		}
		for (String key : keys) {
			assertFalse(en.get(key) == null || en.get(key).isBlank(), key + " (en_us)");
			assertFalse(zh.get(key) == null || zh.get(key).isBlank(), key + " (zh_cn)");
		}
	}

	/**
	 * 面板上那几段<b>按枚举查出来</b>的说明，两个语言都必须有。
	 *
	 * <p>这类 key 是拼出来的（{@code "squire.gui.autonomy.note." + level.id()}），
	 * 编译器管不着。漏一条的表现是玩家在行为页上看到一行
	 * {@code squire.gui.autonomy.note.proactive}，而且只有真的切到那一档才会发现。</p>
	 */
	@Test
	void everyPanelNoteKeyedByAnEnumIsTranslated() {
		Map<String, String> en = load("/assets/squire/lang/en_us.json");
		Map<String, String> zh = load("/assets/squire/lang/zh_cn.json");
		java.util.List<String> keys = new java.util.ArrayList<>();
		for (var level : dev.squire.server.profile.AutonomyLevel.values()) {
			keys.add("squire.gui.autonomy.note." + level.id());
		}
		for (var risk : dev.squire.server.gui.SquireActions.Risk.values()) {
			keys.add(dev.squire.server.gui.SquireActions.riskTooltipKey(risk));
		}
		// 指令页：每一格的标签、以及每一种「点不动」的理由。锁上的那句话是玩家
		// 唯一能据此行动的东西，显示成一段原始 key 等于什么都没说。
		for (var entry : dev.squire.server.gui.CommandCatalog.ENTRIES) {
			keys.add(entry.labelKey());
		}
		for (var reason : dev.squire.server.gui.CommandCatalog.Reason.values()) {
			keys.add(new dev.squire.server.gui.CommandCatalog.Lock(reason, null, 0)
				.labelKey());
		}
		// 页签本身。少一条的表现是页签栏上直接写着 squire.gui.tab.command。
		for (String key : dev.squire.client.gui.SquireScreen.tabKeys()) {
			keys.add(key);
		}
		for (var template
				: dev.squire.server.blueprint.ProjectSpec.Template.values()) {
			keys.add("squire.gui.template." + template.id());
		}
		for (String key : keys) {
			assertFalse(en.get(key) == null || en.get(key).isBlank(), key + " (en_us)");
			assertFalse(zh.get(key) == null || zh.get(key).isBlank(), key + " (zh_cn)");
		}
	}

	@Test
	void commandFeedbackKeysExistForEverySurface() {
		Map<String, String> en = load("/assets/squire/lang/en_us.json");
		for (String key : new String[] {
				"squire.cmd.players_only", "squire.cmd.bad_confirm_id",
				"squire.cmd.no_automations", "squire.cmd.workspace_set",
				"squire.cmd.killswitch_active", "squire.cmd.cbp_disabled_server",
				"squire.cmd.metrics_header", "squire.cmd.alias_ok"}) {
			assertFalse(en.get(key) == null || en.get(key).isBlank(), key);
		}
	}
}
