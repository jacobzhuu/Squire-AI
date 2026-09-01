package dev.squire.server.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.squire.server.fastpath.FastPath;
import dev.squire.server.i18n.Vocabulary;

/**
 * 点名判定。
 *
 * <p>这条规则有两半，缺一半都会变成"带上名字反而更糟"：一半是<b>不点名不理会</b>，
 * 另一半是点名之后把名字从正文里<b>摘掉</b>——FastPath 的短语表是整句精确匹配的，
 * 「豆包，跟着我」要是原样送进去，会一路掉到大模型那条路上去。</p>
 */
class AddressingTest {

	private static Vocabulary aliases() {
		return Vocabulary.fromResources(id -> true, id -> true);
	}

	@Test
	void aMessageWithoutTheNameIsNotForHim() {
		Addressing.Result result = Addressing.parse("跟着我", "豆包");
		assertFalse(result.addressed(),
			"聊天框里的话不都是对他说的；不点名就不该被他接走");
	}

	@Test
	void theNameIsStrippedSoThePhraseTableStillMatches() {
		var resolver = aliases();
		for (String spoken : new String[] {
				"豆包，跟着我", "豆包 跟着我", "@豆包 跟着我", "跟着我，豆包", "豆包跟着我"}) {
			Addressing.Result result = Addressing.parse(spoken, "豆包");
			assertTrue(result.addressed(), () -> "should be addressed: " + spoken);
			assertEquals("跟着我", result.text(), () -> "name not stripped from: " + spoken);
			assertTrue(FastPath.match(result.text(), resolver).isPresent(),
				() -> "stripped text must still hit the phrase table: " + spoken);
		}
	}

	@Test
	void callingHisNameAndNothingElseIsStillAnInput() {
		Addressing.Result result = Addressing.parse("豆包", "豆包");
		assertTrue(result.addressed());
		assertFalse(result.hasText(),
			"只叫了一声名字：调用方要给一句回应，而不是当成一条空指令");
	}

	/** 还没改过名的时候也得能叫得动他，否则新玩家第一句话就石沉大海。 */
	@Test
	void theBuiltInCallSignsAlwaysWork() {
		for (String name : new String[] {null, "", "Squire", "豆包"}) {
			assertTrue(Addressing.parse("侍从 回家", name).addressed(), "侍从/" + name);
			assertEquals("回家", Addressing.parse("侍从 回家", name).text());
			assertTrue(Addressing.parse("squire go home", name).addressed(),
				"squire/" + name);
			assertEquals("go home", Addressing.parse("Squire, go home", name).text());
		}
	}

	@Test
	void theNameMatchIsCaseInsensitive() {
		Addressing.Result result = Addressing.parse("DouBao follow me", "doubao");
		assertTrue(result.addressed());
		assertEquals("follow me", result.text());
	}

	/** 名字夹在句子中间时，两截拼回去不能把中文挤出一个空格。 */
	@Test
	void aNameInTheMiddleRejoinsCleanly() {
		assertEquals("帮我拿点东西",
			Addressing.parse("帮我豆包拿点东西", "豆包").text());
	}

	@Test
	void blankInputIsNeverAddressed() {
		assertFalse(Addressing.parse("", "豆包").addressed());
		assertFalse(Addressing.parse(null, "豆包").addressed());
	}
}
