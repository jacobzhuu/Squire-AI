package dev.squire.server.shortcut;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.squire.server.gui.CommandCatalog;
import dev.squire.server.gui.SquireActions;
import dev.squire.server.profession.ProfessionAbility;
import dev.squire.server.profession.SquireProfession;

/**
 * 点一下快捷指令之后会发生什么。
 *
 * <p>这一层守两条：<b>不经过模型</b>，以及<b>不绕过能力闸</b>。两条都是这次改版的
 * 全部理由——原来的快捷存的是一句自然语言，点一下重新送回输入网关，于是它既可能
 * 落到模型上（同一条快捷两次点出不同结果、离线时整个不能用），又绕开了面板按钮
 * 那条「职业 → 能力 → 权限」的判定。</p>
 *
 * <p>真正的执行需要一个跑起来的服务器，单元测试里没有；所以这里钉的是执行路径上
 * 每一环<b>能被静态验证</b>的那一半：绑定式的快捷身上没有任何可以喂给模型的东西、
 * 每一个档位都真的连着一个有 handler 的服务端动作、失效时那道闸真的会拦下来。</p>
 */
class ShortcutRoutingTest {

	private static final UUID PLAYER = UUID.randomUUID();

	private static CommandCatalog.Context at(SquireProfession profession, int level) {
		Set<String> unlocked = new LinkedHashSet<>();
		if (profession != null) {
			for (ProfessionAbility ability : ProfessionAbility.of(profession)) {
				if (ability.unlockLevel() <= level) {
					unlocked.add(ability.id());
				}
			}
		}
		return new CommandCatalog.Context(profession, level, unlocked, false, false);
	}

	/**
	 * 绑定式的快捷身上<b>没有一句话</b>可以喂给模型。
	 *
	 * <p>这是「不调用 LLM」最结实的那个保证：自然语言那条路要的是 {@code phrase}，
	 * 而绑定式的这一条根本不带 phrase，于是不存在「不小心又走回去了」的分支。</p>
	 */
	@Test
	void aBoundShortcutCarriesNothingThatCouldBeSentToAModel() {
		ShortcutStore store = ShortcutStore.inMemory();
		store.bindAt(PLAYER, 0, "备战", "equip.self", "diamond");
		ShortcutStore.Shortcut saved = store.byIndex(PLAYER, 0).orElseThrow();
		assertTrue(saved.bound());
		assertFalse(saved.legacy());
		assertEquals("", saved.phrase(),
			"绑定式的快捷不该带着一句原话——带着就意味着还有一条通往模型的路");
	}

	/**
	 * 每一个能绑的档位都连着一个<b>真的有 handler</b> 的服务端动作。
	 *
	 * <p>执行时 {@code SquireRuntime#runBoundShortcut} 就是查这张表再调那个
	 * handler——和面板上点那个按钮走的是同一个对象。缺一个 handler 的表现是
	 * 点下去悄无声息。</p>
	 */
	@Test
	void everyBindableVariantResolvesToAServerActionWithAHandler() {
		for (CommandCatalog.Entry entry : CommandCatalog.ENTRIES) {
			for (CommandCatalog.Variant variant : entry.variants()) {
				SquireActions.Action action = SquireActions.byId(variant.actionId());
				assertNotNull(action, entry.id() + "/" + variant.arg()
					+ " 指向一个不存在的按钮 " + variant.actionId());
				assertNotNull(action.handler(), entry.id() + "/" + variant.arg()
					+ " 指向的按钮没有 handler，点下去什么都不会发生");
			}
		}
	}

	/**
	 * 一条快捷因为职业/等级变了而失效时，那道闸会拦下来——<b>而不是</b>删掉它。
	 *
	 * <p>失效的快捷留在面板上显示成锁着的：删掉玩家自己攒的东西，比让他看到
	 * 一把锁糟糕得多。而拦下来这件事必须发生在服务端，因为客户端画的锁改个包就没了。</p>
	 */
	@Test
	void aShortcutThatFellOutOfItsProfessionIsRefusedRatherThanDeleted() {
		ShortcutStore store = ShortcutStore.inMemory();
		store.bindAt(PLAYER, 0, "备战", "equip.self", "netherite");
		ShortcutStore.Shortcut saved = store.byIndex(PLAYER, 0).orElseThrow();

		CommandCatalog.Entry entry = CommandCatalog.byId(saved.entryId());
		assertNotNull(entry);
		// 当守卫时点得动。
		assertNull(CommandCatalog.lockOf(entry, at(SquireProfession.GUARD, 1)));
		// 转成工程师之后同一条快捷点不动了，理由说得出来。
		var lock = CommandCatalog.lockOf(entry, at(SquireProfession.ENGINEER, 10));
		assertNotNull(lock, "工程师不该能用守卫的自动装备");
		assertEquals(CommandCatalog.Reason.PROFESSION, lock.reason());
		assertTrue(lock.describe().contains(SquireProfession.GUARD.displayName()));

		// 而这条快捷<b>还在</b>：闸是拒绝执行，不是清理数据。
		assertEquals(1, store.list(PLAYER).size());
		assertEquals("备战", store.byIndex(PLAYER, 0).orElseThrow().name());
	}

	/** 等级不够的那条同理：闸报的等级来自能力表，练到那一级就真的开。 */
	@Test
	void aLevelGatedShortcutCannotBeUsedBelowItsLevel() {
		ProfessionAbility gate = ProfessionAbility.ENGINEER_BLUEPRINT_MIRROR;
		CommandCatalog.Entry probe = new CommandCatalog.Entry("probe", "试验格",
			CommandCatalog.Gate.of(gate), CommandCatalog.Nav.NONE, java.util.List.of());
		assertNotNull(CommandCatalog.lockOf(probe,
			at(SquireProfession.ENGINEER, gate.unlockLevel() - 1)));
		assertNull(CommandCatalog.lockOf(probe,
			at(SquireProfession.ENGINEER, gate.unlockLevel())));
	}

	/**
	 * 执行路径上不许出现 {@code InputGateway}。
	 *
	 * <p>上面几条钉的是数据；这一条钉的是<b>代码</b>。绑定式那条路只要有人图省事
	 * 加一句「认不出就退回去说一遍」，前面所有保证立刻作废，而且没有任何测试会红。
	 * 自然语言那条路仍然存在（{@code runShortcutPhrase}，只服务于老存档），
	 * 所以这里精确地只看 {@code runBoundShortcut} 那个方法体。</p>
	 */
	@Test
	void theBoundExecutionPathNeverReachesTheLanguageModel() throws IOException {
		String body = methodBody(
			Path.of(System.getProperty("user.dir"))
				.resolve("src/main/java/dev/squire/server/runtime/SquireRuntime.java"),
			"private ExecutionResult runBoundShortcut(");
		for (String forbidden : new String[] {"InputGateway", "runShortcutPhrase",
				"conversations()", "provider"}) {
			assertFalse(body.contains(forbidden),
				"runBoundShortcut 里出现了 " + forbidden
					+ "：绑定式快捷必须走服务端动作，不能落到模型上");
		}
		assertTrue(body.contains("CommandCatalog.lockOf"),
			"执行之前必须拿服务端的职业档案过一遍能力闸");
		assertTrue(body.contains("action.handler().run("),
			"执行必须交给和面板按钮同一个 handler，权限在那条路径里判");
	}

	/** 从源码里抠出一个方法体。大括号配平，够用且不会把下一个方法一起吃掉。 */
	private static String methodBody(Path file, String signature) throws IOException {
		String source = Files.readString(file);
		int at = source.indexOf(signature);
		if (at < 0) {
			return fail("找不到方法 " + signature + "：这条测试盯的东西被改名了，"
				+ "请把测试一起改，而不是删掉它");
		}
		int open = source.indexOf('{', at);
		int depth = 0;
		for (int i = open; i < source.length(); i++) {
			char c = source.charAt(i);
			if (c == '{') {
				depth++;
			} else if (c == '}' && --depth == 0) {
				return source.substring(open, i + 1);
			}
		}
		return fail("方法 " + signature + " 的大括号没有配平");
	}
}
