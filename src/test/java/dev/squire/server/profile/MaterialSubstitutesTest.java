package dev.squire.server.profile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.util.Identifier;

/**
 * 「通用建材」的等价组。
 *
 * <p>这条规则唯一的风险是<b>放宽</b>：只要组里混进一个「差不多的方块」，玩家就会
 * 拿到一栋自己没设计过的房子，而花色不对比缺料更难受，还没法撤销回想要的样子。
 * 所以这里既验「同组能顶替」，也验「不同类的绝不互通」。</p>
 */
class MaterialSubstitutesTest {

	private static Identifier id(String path) {
		return new Identifier("minecraft", path);
	}

	@Test
	void planksSubstituteForEachOther() {
		assertTrue(MaterialSubstitutes.equivalent(id("oak_planks"), id("spruce_planks")));
		assertTrue(MaterialSubstitutes.substitutesFor(id("oak_planks"))
			.contains(id("birch_planks")));
		assertFalse(MaterialSubstitutes.substitutesFor(id("oak_planks"))
			.contains(id("oak_planks")), "自己不在自己的替代清单里");
	}

	@Test
	void commonStoneSubstitutesForEachOther() {
		assertTrue(MaterialSubstitutes.equivalent(id("cobblestone"), id("stone")));
		assertTrue(MaterialSubstitutes.equivalent(id("deepslate"), id("andesite")));
	}

	@Test
	void differentKindsNeverSubstitute() {
		assertFalse(MaterialSubstitutes.equivalent(id("oak_planks"), id("cobblestone")),
			"木板和石头不是同一种建筑");
		assertFalse(MaterialSubstitutes.equivalent(id("stone_bricks"), id("stone")),
			"石砖是加工过的，和毛石不互通");
		assertFalse(MaterialSubstitutes.equivalent(id("oak_log"), id("oak_planks")),
			"原木和木板形状不同");
	}

	@Test
	void thingsWithShapeOrFunctionAreNeverInAGroup() {
		for (String path : new String[] {"glass", "glass_pane", "chest",
				"crafting_table", "oak_door", "torch", "cobblestone_wall"}) {
			assertTrue(MaterialSubstitutes.substitutesFor(id(path)).isEmpty(),
				() -> path + " 不该有替代品：换掉它会改变建筑的用法，不只是花色");
		}
	}

	@Test
	void anythingUnknownSimplyHasNoSubstitute() {
		assertTrue(MaterialSubstitutes.substitutesFor(null).isEmpty());
		assertTrue(MaterialSubstitutes.substitutesFor(
			new Identifier("somemod", "weird_block")).isEmpty(),
			"没有等价物，比乱找一个像的强");
		assertFalse(MaterialSubstitutes.equivalent(null, id("stone")));
	}

	@Test
	void aBlockIsAlwaysEquivalentToItself() {
		assertTrue(MaterialSubstitutes.equivalent(id("chest"), id("chest")));
	}
}
