package dev.squire.server.memory;

import org.junit.jupiter.api.Test;

import dev.squire.server.memory.LocationMemory.Type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 方案 E1/E2：把玩家的说法映射到地点类型。这层必须保守——宁可交给 LLM，
 * 也不要把普通闲聊误判成"这是家"然后写进长期记忆。
 */
class LocationMemoryTest {

	@Test
	void chinesePlaceWordsMapToTypes() {
		assertEquals(Type.HOME, Type.fromPhrase("基地"));
		assertEquals(Type.HOME, Type.fromPhrase("家"));
		assertEquals(Type.WAREHOUSE, Type.fromPhrase("仓库"));
		assertEquals(Type.FARM, Type.fromPhrase("农场"));
		assertEquals(Type.MINE, Type.fromPhrase("矿洞"));
		assertEquals(Type.MINE, Type.fromPhrase("矿井"));
	}

	@Test
	void englishPlaceWordsMapToTypes() {
		assertEquals(Type.HOME, Type.fromPhrase("home"));
		assertEquals(Type.WAREHOUSE, Type.fromPhrase("the storage room"));
		assertEquals(Type.FARM, Type.fromPhrase("wheat farm"));
		assertEquals(Type.MINE, Type.fromPhrase("deep mine"));
	}

	/**
	 * "家" 是单字：用子串匹配会把"那家店""大家"当成家，于是一句闲聊就能覆盖
	 * 玩家真正的家坐标。短词只允许整句精确匹配。
	 */
	@Test
	void shortWordsNeverMatchAsSubstrings() {
		assertEquals(Type.CUSTOM, Type.fromPhrase("那家店"));
		assertEquals(Type.CUSTOM, Type.fromPhrase("大家好"));
		assertFalse(Type.isTypePhrase("那家店"));
	}

	@Test
	void unrelatedPhrasesFallThroughToCustom() {
		assertEquals(Type.CUSTOM, Type.fromPhrase("红石实验室"));
		assertEquals(Type.CUSTOM, Type.fromPhrase(null));
		assertEquals(Type.CUSTOM, Type.fromPhrase("  "));
		assertFalse(Type.isTypePhrase("红石实验室"));
	}

	@Test
	void typePhrasesAreRecognisedForRecallGrammar() {
		assertTrue(Type.isTypePhrase("仓库"));
		assertTrue(Type.isTypePhrase("矿洞"));
		assertTrue(Type.isTypePhrase("基地"));
	}
}
