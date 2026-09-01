package dev.squire.server.perception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.util.math.BlockPos;

/**
 * 巡检报告的措辞规则。
 *
 * <p>守的是一条容易被写坏的性质：<b>没发现就不说话</b>。一个每到一个巡逻点都汇报
 * 一次「一切正常」的伙伴，第三句起玩家就不看了——真出事那一句也跟着被忽略。
 * 沉默必须是默认，而不是一个可选项。</p>
 *
 * <p>世界扫描那一半在 {@code M15BehaviourGameTests} 里（要真实世界和真实生物）；
 * 这里只验纯记录，所以措辞可以被逐字钉住。</p>
 */
class PatrolReportTest {

	private static PatrolInspector.Report report(int hostiles, int dark, int doors) {
		return new PatrolInspector.Report(hostiles,
			positions(dark), positions(doors));
	}

	private static List<BlockPos> positions(int count) {
		return java.util.stream.IntStream.range(0, count)
			.mapToObj(i -> new BlockPos(i, 64, i)).toList();
	}

	@Test
	void anEmptyReportIsSilent() {
		PatrolInspector.Report quiet = report(0, 0, 0);
		assertFalse(quiet.anythingToSay());
		assertEquals("", quiet.describe(),
			"没发现就不说话——不是说一句「一切正常」");
	}

	@Test
	void anySingleFindingIsWorthSaying() {
		assertTrue(report(1, 0, 0).anythingToSay());
		assertTrue(report(0, 1, 0).anythingToSay());
		assertTrue(report(0, 0, 1).anythingToSay());
	}

	@Test
	void eachKindOfFindingShowsUpInTheWords() {
		assertTrue(report(2, 0, 0).describe().contains("2"));
		assertTrue(report(0, 1, 0).describe().contains("暗"));
		assertTrue(report(0, 0, 1).describe().contains("门"));
	}

	@Test
	void severalFindingsAreJoinedIntoOneLine() {
		String text = report(1, 1, 1).describe();
		assertEquals(1, text.split("\n").length, "巡检永远只说一句话");
		assertTrue(text.endsWith("。"));
		assertTrue(text.contains("；"), "多项发现之间要分隔开");
	}

	@Test
	void aFindingAlwaysCarriesAPlaceToGo() {
		String text = report(0, 1, 0).describe();
		assertTrue(text.contains("0, 64, 0"),
			() -> "报告要带上坐标，否则玩家不知道去哪儿看：" + text);
	}
}
