package dev.squire.server.profile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * 「能力清单必须与实现对齐」——这条原则的自动守卫。
 *
 * <p>面板上摆一个点了没反应的按钮，比没有这个按钮伤害大得多：玩家会照着清单去练、
 * 去装、去用，然后什么都没发生，而且不知道是自己搞错了还是模组坏了。所以这里反过来
 * 检查每一条<b>声明为已落地</b>的能力：它的枚举常量必须在 {@code Ability.java}
 * 以外的生产代码里真的被引用过——也就是真的门住了某件事。</p>
 *
 * <p>还没落地的一律 {@code available=false}，那是合法状态；这条测试只拒绝
 * 「说自己做完了、其实没人用它」。</p>
 */
class AbilityCatalogTest {

	private static final Path SOURCE_ROOT = Path.of("src", "main", "java");

	private static List<String> productionSources() throws IOException {
		List<String> out = new ArrayList<>();
		try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
			for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
				if (file.getFileName().toString().equals("Ability.java")) {
					continue; // 定义处不算「用到」
				}
				out.add(Files.readString(file, StandardCharsets.UTF_8));
			}
		}
		return out;
	}

	@Test
	void everyAvailableAbilityIsActuallyCheckedSomewhere() throws IOException {
		List<String> sources = productionSources();
		for (Ability ability : Ability.values()) {
			if (!ability.available() || ability.basic()) {
				continue; // 基础能力由 can() 统一放行，不需要逐个引用
			}
			boolean referenced = sources.stream()
				.anyMatch(source -> source.contains("Ability." + ability.name()));
			assertTrue(referenced, () -> "「" + ability.displayName()
				+ "」声明为已落地，但生产代码里没有任何地方检查 Ability."
				+ ability.name() + " —— 玩家装上它不会有任何反应");
		}
	}

	@Test
	void everyAbilityAndRoleHasALabelInBothLocales() throws IOException {
		String en = Files.readString(Path.of("src", "main", "resources", "assets",
			"squire", "lang", "en_us.json"), StandardCharsets.UTF_8);
		String zh = Files.readString(Path.of("src", "main", "resources", "assets",
			"squire", "lang", "zh_cn.json"), StandardCharsets.UTF_8);
		for (Ability ability : Ability.values()) {
			assertTrue(en.contains('"' + ability.nameKey() + '"'),
				() -> "en_us 缺少 " + ability.nameKey());
			assertTrue(zh.contains('"' + ability.nameKey() + '"'),
				() -> "zh_cn 缺少 " + ability.nameKey());
		}
		for (Role role : Role.values()) {
			assertTrue(en.contains('"' + role.nameKey() + '"'),
				() -> "en_us 缺少 " + role.nameKey());
			assertTrue(zh.contains('"' + role.nameKey() + '"'),
				() -> "zh_cn 缺少 " + role.nameKey());
		}
		for (Trait trait : Trait.values()) {
			assertTrue(zh.contains('"' + trait.nameKey() + '"'),
				() -> "zh_cn 缺少 " + trait.nameKey());
			assertTrue(en.contains('"' + trait.nameKey() + '"'),
				() -> "en_us 缺少 " + trait.nameKey());
			assertTrue(zh.contains('"' + trait.descriptionKey() + '"'),
				() -> "zh_cn 缺少 " + trait.descriptionKey());
			assertTrue(en.contains('"' + trait.descriptionKey() + '"'),
				() -> "en_us 缺少 " + trait.descriptionKey());
			for (int i = 0; i < trait.effectLineCount(); i++) {
				String key = trait.effectKey(i);
				assertTrue(zh.contains('"' + key + '"'), () -> "zh_cn 缺少 " + key);
				assertTrue(en.contains('"' + key + '"'), () -> "en_us 缺少 " + key);
			}
		}
		for (AutonomyLevel level : AutonomyLevel.values()) {
			assertTrue(zh.contains('"' + level.nameKey() + '"'),
				() -> "zh_cn 缺少 " + level.nameKey());
		}
	}

	/**
	 * 中文名在两个地方各写了一份（枚举里给聊天用、lang 里给面板用），
	 * 所以必须有人盯着它们别说两句不同的话。
	 */
	@Test
	void theChineseLabelInTheEnumMatchesTheLangFile() throws IOException {
		String zh = Files.readString(Path.of("src", "main", "resources", "assets",
			"squire", "lang", "zh_cn.json"), StandardCharsets.UTF_8);
		for (Ability ability : Ability.values()) {
			assertTrue(zh.contains('"' + ability.nameKey() + "\": \""
					+ ability.displayName() + '"'),
				() -> "面板和聊天对「" + ability.id() + "」的叫法不一致");
		}
		for (Role role : Role.values()) {
			if (!role.available()) {
				continue; // 未开放的职业在面板上会带一个「（未开放）」后缀
			}
			assertTrue(zh.contains('"' + role.nameKey() + "\": \""
					+ role.displayName() + '"'),
				() -> "面板和聊天对「" + role.id() + "」的叫法不一致");
		}
	}

	@Test
	void everyRoleThatIsOpenHasABasicAbilityToStandOn() {
		for (Role role : Role.values()) {
			if (!role.available()) {
				continue;
			}
			assertTrue(Ability.of(role).stream().anyMatch(Ability::basic),
				() -> role.id() + " 一上来什么都不会，选它没有意义");
			assertFalse(role.track() == null,
				() -> role.id() + " 没有熟练度轨道，永远升不了级");
		}
	}

	@Test
	void equippableListHoldsOnlyLandedAdvancedAbilities() {
		for (Ability ability : Ability.equippable()) {
			assertFalse(ability.basic(), "基础能力不占槽");
			assertTrue(ability.available(), "没落地的东西不该出现在可装备清单里");
		}
	}
}
