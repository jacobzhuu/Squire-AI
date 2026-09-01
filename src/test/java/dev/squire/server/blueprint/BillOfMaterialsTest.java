package dev.squire.server.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.minecraft.util.Identifier;

/**
 * 材料账是「资源真实流动」的账本，玩家会照着它去凑东西。
 *
 * <p>两条最容易写错、也最伤人的性质：已经就位的方块必须从需求里扣掉（否则在半成品
 * 上再看一次账，还是那个吓人的总数），以及缺料数量是「要求减去<b>伙伴身上</b>的」，
 * 不是「要求减去世界上的」。</p>
 */
class BillOfMaterialsTest {

	private static Identifier id(String path) {
		return new Identifier("minecraft", path);
	}

	private static Map<Identifier, Integer> map(Object... pairs) {
		Map<Identifier, Integer> out = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			out.put(id((String) pairs[i]), (Integer) pairs[i + 1]);
		}
		return out;
	}

	@Test
	void missingIsRequiredMinusCarried() {
		BillOfMaterials bill = new BillOfMaterials(
			map("oak_planks", 64, "glass_pane", 12),
			Map.of(),
			map("oak_planks", 32));
		assertEquals(map("oak_planks", 32, "glass_pane", 12), bill.missing());
		assertFalse(bill.satisfied());
	}

	@Test
	void carryingMoreThanNeededNeverGoesNegative() {
		BillOfMaterials bill = new BillOfMaterials(map("stone", 10), Map.of(),
			map("stone", 99));
		assertTrue(bill.missing().isEmpty());
		assertTrue(bill.satisfied());
	}

	@Test
	void anEmptyRequirementIsSatisfiedAndSaysSoInWords() {
		BillOfMaterials bill = new BillOfMaterials(Map.of(), Map.of(), Map.of());
		assertTrue(bill.satisfied());
		assertTrue(bill.describe().contains("不需要"),
			"空清单要说一句话，而不是留一片空白");
	}

	/** 已就位的方块单独记账：它们证明「这一栋盖了一半」，但绝不算进要凑的量。 */
	@Test
	void blocksAlreadyInPlaceAreReportedButNotRequired() {
		BillOfMaterials bill = new BillOfMaterials(map("stone", 4),
			map("stone", 20), map("stone", 4));
		assertEquals(4, bill.totalRequired());
		assertEquals(20, bill.totalPlaced());
		assertTrue(bill.satisfied());
		assertTrue(bill.describe().contains("20"), "半成品的进度要看得见");
	}

	@Test
	void descriptionShowsHaveOverNeedForEveryLine() {
		String text = new BillOfMaterials(map("oak_planks", 64), Map.of(),
			map("oak_planks", 30)).describe();
		assertTrue(text.contains("oak_planks 30/64"), () -> text);
		assertTrue(text.contains("34"), () -> "缺口要直接写出来：" + text);
	}
}
