package dev.squire.server.help;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.squire.server.fastpath.FastPath;
import dev.squire.server.i18n.Vocabulary;

/**
 * 能力清单是给玩家看的承诺，必须和实现对得上。
 *
 * <p>这条测试的意义：清单上每一句例句都得真的被 FastPath 认出来。否则就会出现
 * 最伤玩家的情况——照着帮助里写的说，伙伴却没反应。以后加能力时如果只改了
 * 实现没改清单（或反过来），这里会立刻红。</p>
 */
class CapabilityGuideTest {

	/** 单元测试里没有真实注册表，别名一律放行；这里验的是句式而不是物品是否存在。 */
	private static Vocabulary aliases() {
		return Vocabulary.fromResources(id -> true, id -> true);
	}

	@Test
	void everyAdvertisedExampleIsActuallyUnderstood() {
		var resolver = aliases();
		for (var capability : CapabilityGuide.all()) {
			for (String example : capability.examples()) {
				if (example.startsWith("/")) {
					continue; // 命令例句走 Brigadier，下面单独一条管
				}
				assertTrue(FastPath.match(example, resolver).isPresent(),
					() -> "帮助里写了「" + example + "」（" + capability.title()
						+ "），但 FastPath 认不出来——玩家照着说会没反应");
			}
		}
	}

	/**
	 * 第 1 期起能力清单里也出现命令例句（蓝图流程天生是多步的，
	 * 塑成一句聊天反而说不清）。它们不经过 FastPath，但至少得是真的 {@code /squire}
	 * 命令——帮助里写一条没人实现的命令，和写一句认不出的聊天一样伤人。
	 */
	@Test
	void commandExamplesAreRealSquireCommands() {
		int commandExamples = 0;
		for (var capability : CapabilityGuide.all()) {
			for (String example : capability.examples()) {
				if (!example.startsWith("/")) {
					continue;
				}
				commandExamples++;
				assertTrue(example.startsWith("/squire "),
					() -> "帮助里的命令例句必须是 /squire 子命令：" + example);
				assertTrue(example.trim().split("\s+").length >= 2,
					() -> "命令例句得带上子命令：" + example);
			}
		}
		assertTrue(commandExamples == 0,
			"普通玩家能力清单不应再把命令当作正常玩法入口");
	}

	@Test
	void askingWhatHeCanDoIsRecognisedInTheObviousPhrasings() {
		var resolver = aliases();
		for (String ask : new String[] {
				"你能做什么", "能做什么", "你会干嘛", "帮助", "help", "你能干什么"}) {
			var intent = FastPath.match(ask, resolver);
			assertTrue(intent.isPresent(), () -> "should match help: " + ask);
			assertTrue(intent.get()
					instanceof dev.squire.server.fastpath.FastPathIntent.Help,
				() -> "should be the help intent: " + ask);
		}
	}

	@Test
	void helpTextListsEveryCapabilityWithSomethingToCopy() {
		String help = CapabilityGuide.helpText();
		for (var capability : CapabilityGuide.all()) {
			assertTrue(help.contains(capability.title()),
				() -> "help text omits: " + capability.title());
			assertTrue(help.contains(capability.examples().get(0)),
				() -> "help text gives no example for: " + capability.title());
		}
		// 面板是主要交互入口，帮助里必须先把它指出来，而不是只教打字。
		assertTrue(help.contains("按 K") && help.contains("右键"),
			"help must lead with the panel, not with chat phrases");
		assertTrue(help.indexOf("面板") < help.indexOf("也可以直接对我说话"),
			"the panel must be introduced before the chat phrase list");
	}

	@Test
	void unknownInputAlwaysOffersAWayForward() {
		// 关键产品要求：任何一句听不懂的话，回复里都要有可执行的下一步，
		// 而不是一句"没听懂"把玩家挂在原地。
		for (String nonsense : new String[] {
				"把那个东西弄一下", "asdfgh", "帮我搞点装备", "去打怪", "我快没血了",
				"造个屋子", "过来一下"}) {
			String reply = CapabilityGuide.didYouMean(nonsense);
			assertFalse(reply.isBlank(), () -> "empty reply for: " + nonsense);
			assertTrue(reply.contains("你能做什么"),
				() -> "reply must point at the full list: " + nonsense);
		}
	}

	@Test
	void didYouMeanSuggestsTheRightPhraseForCommonIntents() {
		assertTrue(CapabilityGuide.didYouMean("帮我搞点装备").contains("给自己装备"),
			"gear wording should suggest the self-equip phrase");
		assertTrue(CapabilityGuide.didYouMean("造个屋子").contains("盖一个房子"),
			"building wording should suggest the house phrase");
		assertTrue(CapabilityGuide.didYouMean("我快没血了").contains("救我"),
			"health wording should suggest the rescue phrase");
	}

	@Test
	void onboardingStaysShortEnoughToRead() {
		String onboarding = CapabilityGuide.onboardingText();
		// 首次召唤糊一屏字等于没说。控制在几行内，并且必须给出"看完整清单"的入口。
		assertTrue(onboarding.lines().count() <= 6,
			"onboarding must stay skimmable, was: " + onboarding.lines().count());
		assertTrue(onboarding.contains("你能做什么"),
			"onboarding must point at the full list");
		// 新玩家第一眼就该知道有面板，而不是以为只能打字。
		assertTrue(onboarding.contains("按 K") && onboarding.contains("右键"),
			"onboarding must surface both ways to open the panel");
	}
}
