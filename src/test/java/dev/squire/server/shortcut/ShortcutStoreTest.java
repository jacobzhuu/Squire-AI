package dev.squire.server.shortcut;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * 快捷指令存的是<b>一个动作</b>，不是一句原话。
 *
 * <p>这是这一层最重要的事：原话那条路点一下要重新过输入网关，也就是可能再走一次
 * 模型——同一条快捷两次点出不同结果，离线时整个不能用，而且它绕开了面板按钮那条
 * 「职业 → 能力 → 权限」的判定。现在存的是 {@code entryId + arg}。</p>
 *
 * <p>老存档里的原话仍然读得出来：玩家存了半年的快捷不该因为一次改版凭空消失。</p>
 */
class ShortcutStoreTest {

	private static final UUID PLAYER = UUID.randomUUID();

	@Test
	void savingAndRunningRoundTrips() {
		ShortcutStore store = ShortcutStore.inMemory();
		assertTrue(store.put(PLAYER, "回家吃饭", "回家").success());
		assertEquals("回家", store.byName(PLAYER, "回家吃饭").orElseThrow().phrase());
		assertEquals("回家吃饭", store.byIndex(PLAYER, 0).orElseThrow().name());
		assertEquals(java.util.List.of("回家吃饭"), store.names(PLAYER));
	}

	/**
	 * 面板给的是「第几格」。改名只是改这一格的内容，不能变成又新增一条——
	 * 那正是面板上「点开一格改个名字，结果多出一条」的来源。
	 */
	@Test
	void editingASlotRenamesInPlaceInsteadOfAddingOne() {
		ShortcutStore store = ShortcutStore.inMemory();
		store.putAt(PLAYER, 0, "回家吃饭", "回家");
		assertTrue(store.putAt(PLAYER, 0, "收工", "回家").success());
		assertEquals(1, store.list(PLAYER).size());
		assertEquals("收工", store.byIndex(PLAYER, 0).orElseThrow().name());
		assertEquals(java.util.List.of("回家"), store.phrases(PLAYER));
	}

	/** 空槽（下标越界）就是新增；名字和别人撞了要说清楚，而不是悄悄覆盖别人那条。 */
	@Test
	void anEmptySlotAddsAndAClashingNameIsRefused() {
		ShortcutStore store = ShortcutStore.inMemory();
		store.putAt(PLAYER, 0, "回家吃饭", "回家");
		assertTrue(store.putAt(PLAYER, 5, "备战", "保护我").success(),
			"面板上点的是第 6 格，但存下来是第 2 条");
		assertEquals(2, store.list(PLAYER).size());
		assertFalse(store.putAt(PLAYER, 1, "回家吃饭", "别的事").success(),
			"改成一个已经被占用的名字会让两条都喊不动");
	}

	@Test
	void bothFieldsAreRequiredAndTheMessageSaysSoWithoutMentioningAnEqualsSign() {
		ShortcutStore store = ShortcutStore.inMemory();
		var result = store.putAt(PLAYER, 0, "回家吃饭", "");
		assertFalse(result.success());
		assertFalse(result.message().contains("="),
			"面板上没有等号这个东西，错误信息里也不该冒出来");
	}

	@Test
	void deletingBySlotReportsAnEmptySlotHonestly() {
		ShortcutStore store = ShortcutStore.inMemory();
		store.putAt(PLAYER, 0, "回家吃饭", "回家");
		assertTrue(store.removeAt(PLAYER, 0).success());
		assertFalse(store.removeAt(PLAYER, 0).success());
		assertTrue(store.list(PLAYER).isEmpty());
	}

	/** 名字和内容按下标一一对应；面板的编辑框靠这条回填。 */
	@Test
	void namesAndPhrasesStayAligned() {
		ShortcutStore store = ShortcutStore.inMemory();
		store.putAt(PLAYER, 0, "回家吃饭", "回家");
		store.putAt(PLAYER, 1, "备战", "保护我");
		assertEquals(java.util.List.of("回家吃饭", "备战"), store.names(PLAYER));
		assertEquals(java.util.List.of("回家", "保护我"), store.phrases(PLAYER));
	}

	/** 同名是改写，不是报错——改一句话不该先删再加。 */
	@Test
	void sameNameOverwritesInPlace() {
		ShortcutStore store = ShortcutStore.inMemory();
		store.put(PLAYER, "备战", "保护我");
		store.put(PLAYER, "备战", "给自己装备下界合金套装");
		assertEquals(1, store.list(PLAYER).size());
		assertEquals("给自己装备下界合金套装",
			store.byName(PLAYER, "备战").orElseThrow().phrase());
	}

	@Test
	void nameLookupIsCaseInsensitive() {
		ShortcutStore store = ShortcutStore.inMemory();
		store.put(PLAYER, "GoHome", "回家");
		assertTrue(store.byName(PLAYER, "gohome").isPresent());
	}

	@Test
	void theSlotLimitIsEnforcedWithAnHonestMessage() {
		ShortcutStore store = ShortcutStore.inMemory();
		for (int i = 0; i < ShortcutStore.MAX_PER_PLAYER; i++) {
			assertTrue(store.put(PLAYER, "s" + i, "回家").success());
		}
		var overflow = store.put(PLAYER, "再来一条", "回家");
		assertFalse(overflow.success());
		assertTrue(overflow.message().contains(
			String.valueOf(ShortcutStore.MAX_PER_PLAYER)),
			"说清楚上限是多少，别只说'失败了'");
	}

	@Test
	void emptyHalvesAreRefused() {
		ShortcutStore store = ShortcutStore.inMemory();
		assertFalse(store.put(PLAYER, "", "回家").success());
		assertFalse(store.put(PLAYER, "名字", "  ").success());
		assertFalse(store.remove(PLAYER, "不存在的").success());
	}

	/** 中文输入法下打出全角冒号是常态，因为这个报「格式不对」纯粹是刁难人。 */
	@Test
	void allThreeSeparatorsAreAccepted() {
		for (String line : new String[] {
				"回家吃饭=回家", "回家吃饭：回家", "回家吃饭:回家", " 回家吃饭 = 回家 "}) {
			var parsed = ShortcutStore.parseLine(line).orElseThrow(
				() -> new AssertionError("should parse: " + line));
			assertEquals("回家吃饭", parsed.name(), () -> "name from: " + line);
			assertEquals("回家", parsed.phrase(), () -> "phrase from: " + line);
		}
	}

	@Test
	void aLineWithoutASeparatorIsNotAShortcut() {
		assertTrue(ShortcutStore.parseLine("回家").isEmpty());
		assertTrue(ShortcutStore.parseLine("=回家").isEmpty(), "名字不能为空");
		assertTrue(ShortcutStore.parseLine("回家=").isEmpty(), "内容不能为空");
		assertTrue(ShortcutStore.parseLine(null).isEmpty());
	}

	@Test
	void oversizedInputIsRefusedRatherThanTruncated() {
		ShortcutStore store = ShortcutStore.inMemory();
		assertFalse(store.put(PLAYER, "x".repeat(ShortcutStore.MAX_NAME_LENGTH + 1),
			"回家").success());
		assertFalse(store.put(PLAYER, "名字",
			"x".repeat(ShortcutStore.MAX_PHRASE_LENGTH + 1)).success());
	}

	@Test
	void removingShiftsTheRemainingSlotsUp() {
		ShortcutStore store = ShortcutStore.inMemory();
		store.put(PLAYER, "一", "回家");
		store.put(PLAYER, "二", "跟着我");
		assertTrue(store.remove(PLAYER, "一").success());
		assertEquals("二", store.byIndex(PLAYER, 0).orElseThrow().name());
		assertTrue(store.byIndex(PLAYER, 1).isEmpty());
	}

	@Test
	void playersDoNotSeeEachOthersShortcuts() {
		ShortcutStore store = ShortcutStore.inMemory();
		UUID other = UUID.randomUUID();
		store.put(PLAYER, "备战", "保护我");
		assertTrue(store.list(other).isEmpty());
		assertTrue(store.byName(other, "备战").isEmpty());
	}

	// ------------------------------------------------------------------ 绑定式

	/** 面板存下来的是动作 id 和档位，不是一句话。 */
	@Test
	void bindingASlotStoresAnActionRatherThanAPhrase() {
		ShortcutStore store = ShortcutStore.inMemory();
		assertTrue(store.bindAt(PLAYER, 0, "备战", "equip.self", "diamond").success());
		ShortcutStore.Shortcut saved = store.byIndex(PLAYER, 0).orElseThrow();
		assertTrue(saved.bound());
		assertFalse(saved.legacy());
		assertEquals("equip.self", saved.entryId());
		assertEquals("diamond", saved.arg());
		assertEquals("", saved.phrase(), "绑定式的不该再带着一句原话");
	}

	/** 绑定也是按槽位改写的：改一条不该变成多出一条。 */
	@Test
	void rebindingASlotReplacesItInPlace() {
		ShortcutStore store = ShortcutStore.inMemory();
		store.bindAt(PLAYER, 0, "备战", "equip.self", "iron");
		assertTrue(store.bindAt(PLAYER, 0, "备战", "equip.self", "netherite").success());
		assertEquals(1, store.list(PLAYER).size());
		assertEquals("netherite", store.byIndex(PLAYER, 0).orElseThrow().arg());
	}

	@Test
	void bindingRefusesAnEmptyNameOrAnEmptyAction() {
		ShortcutStore store = ShortcutStore.inMemory();
		assertFalse(store.bindAt(PLAYER, 0, "", "guard.start", "").success());
		assertFalse(store.bindAt(PLAYER, 0, "备战", "", "").success());
		assertTrue(store.list(PLAYER).isEmpty());
	}

	/** 撞名在绑定这条路上一样要说清楚，而不是悄悄覆盖别人那条。 */
	@Test
	void bindingRefusesAClashingName() {
		ShortcutStore store = ShortcutStore.inMemory();
		store.bindAt(PLAYER, 0, "备战", "equip.self", "iron");
		assertFalse(store.bindAt(PLAYER, 1, "备战", "guard.start", "").success());
	}

	/**
	 * 面板同步的那一行：{@code entryId ␟ arg ␟ phrase}，绑定式和老式都能装。
	 *
	 * <p>面板要靠它画「他会做什么」那行字，也要靠它判这一条现在是不是锁着的。</p>
	 */
	@Test
	void specsCarryBothKindsOfShortcut() {
		ShortcutStore store = ShortcutStore.inMemory();
		store.bindAt(PLAYER, 0, "备战", "inventory.give", "64");
		store.putAt(PLAYER, 1, "回家吃饭", "回家");
		assertEquals(java.util.List.of(
				"inventory.give" + ShortcutStore.SPEC_SEPARATOR + "64"
					+ ShortcutStore.SPEC_SEPARATOR,
				ShortcutStore.SPEC_SEPARATOR + ShortcutStore.SPEC_SEPARATOR + "回家"),
			store.specs(PLAYER));
	}

	/** 老式的那一条仍然认得出来，也仍然点得动——不能因为改版把它删掉。 */
	@Test
	void aLegacyPhraseShortcutStaysReadable() {
		ShortcutStore store = ShortcutStore.inMemory();
		store.putAt(PLAYER, 0, "回家吃饭", "回家");
		ShortcutStore.Shortcut saved = store.byIndex(PLAYER, 0).orElseThrow();
		assertTrue(saved.legacy());
		assertFalse(saved.bound());
		assertEquals("回家", saved.phrase());
	}
}
