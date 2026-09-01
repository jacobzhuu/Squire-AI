package dev.squire.server.fastpath;

import java.util.Optional;

/**
 * 结构化 FastPath 意图（方案 B1）：控制类短语先于 LLM 精确解析；资源/领域句式
 * 在后续工作包扩展。密封接口保证每个意图都有显式类型，而不是裸枚举+散落参数。
 *
 * <p>解析顺序（方案 B1）：文本规范化 → 控制短语精确匹配 → 别名/数量解析 →
 * 领域语法 → 仅在无可信 FastPath 时提交 LLM。</p>
 */
public sealed interface FastPathIntent
		permits FastPathIntent.Control, FastPathIntent.LookAt, FastPathIntent.Emote,
			FastPathIntent.Fulfil, FastPathIntent.Guard, FastPathIntent.GuardStop,
			FastPathIntent.AidOwner,
			FastPathIntent.RecallLocation, FastPathIntent.RememberLocation,
			FastPathIntent.FillSelection, FastPathIntent.BuildHouse,
			FastPathIntent.StartProject, FastPathIntent.ProjectControl,
			FastPathIntent.Help, FastPathIntent.UndoItemEdit,
			FastPathIntent.LocateStructure, FastPathIntent.LocateBiome,
			FastPathIntent.SetTime, FastPathIntent.SetWeather,
			FastPathIntent.GiveEffect, FastPathIntent.WhatIsYourName,
			FastPathIntent.Inspect, FastPathIntent.AttackTarget,
			FastPathIntent.ComeHere, FastPathIntent.TeleportOwner,
			FastPathIntent.SetCombatStyle,
			FastPathIntent.HealSelf {

	/** 基础控制意图（follow/stay/stop/home/status/dismiss）。 */
	record Control(Kind kind) implements FastPathIntent {
		public enum Kind { FOLLOW, STAY, STOP, HOME_RETURN, STATUS, DISMISS }
	}

	/** “看着我” / “看向 <坐标>”。坐标为空表示看主人。 */
	record LookAt(Optional<double[]> target) implements FastPathIntent {
		public static LookAt me() {
			return new LookAt(Optional.empty());
		}

		public static LookAt position(double x, double y, double z) {
			return new LookAt(Optional.of(new double[] {x, y, z}));
		}
	}

	/** “挥手 / 跳一下 / 点头 / 摇头”。 */
	record Emote(dev.squire.api.body.EmoteType type) implements FastPathIntent {
	}

	/**
	 * 一切和物品有关的说法都收敛到这一个意图上：取物（给我 / 帮我砍 / 给自己穿）、
	 * 改动已有装备（取消荆棘 / 清空附魔 / 修一下）。
	 *
	 * <p>载荷是解析好的 {@link dev.squire.server.nlu.ItemOperation}——从哪拿 ×
	 * 哪几件 × 做什么 × 归谁。规则层和 LLM 工具层产出同一个对象、走同一个执行器，
	 * 一种能力才不会只在其中一条路上存在。</p>
	 */
	record Fulfil(dev.squire.server.nlu.ItemOperation operation) implements FastPathIntent {
	}

	/** 「撤销」：还原伙伴最近一次对玩家物品的改动。 */
	record UndoItemEdit() implements FastPathIntent { }

	/** 「最近的远古城市在哪」：只读查询，坐标来自世界生成器，绝不编。 */
	record LocateStructure(String structureId, String spokenName)
			implements FastPathIntent { }

	/** 「用弓打」/「近战就行」：打法偏好，会被记住。 */
	record SetCombatStyle(dev.squire.server.combat.CombatStyle.Style style)
			implements FastPathIntent { }

	/** 「到我身边来」：立刻到跟前，不只是切换成跟随（近就走，远/跨维度就传）。 */
	record ComeHere() implements FastPathIntent { }

	/**
	 * 「把我传送过去」「帮我回主世界」。
	 *
	 * @param dimensionId 目标维度；null 表示不换维度
	 * @param place       记过的地点名；null 表示没点名
	 * @param bring       要不要把伙伴也一起带过去
	 */
	record TeleportOwner(String dimensionId, String place, boolean bring, String label)
			implements FastPathIntent { }

	/** 「最近的樱花林在哪」：群系查找，和结构查找同形。 */
	record LocateBiome(String biomeId, String spokenName) implements FastPathIntent { }

	/** 「把时间调成白天」。{@code preset} 是 day/noon/night/midnight。 */
	record SetTime(String preset) implements FastPathIntent { }

	/** 「把雨停了」。{@code preset} 是 clear/rain/thunder。 */
	record SetWeather(String preset) implements FastPathIntent { }

	/** 「你背包里有什么」/「你身上穿的什么」/「我血量多少」/「附近有怪吗」——只读查询。 */
	record Inspect(Kind kind) implements FastPathIntent {
		public enum Kind { AGENT_INVENTORY, AGENT_EQUIPMENT, OWNER_STATE, NEARBY }
	}

	/** 「打那只苦力怕」。{@code entityId} 为空表示「清掉附近的敌对生物」。 */
	record AttackTarget(String entityId, String spokenName) implements FastPathIntent { }

	/** 「给我加个夜视」：效果 id 已过注册表校验，具体是否允许由白名单最终决定。 */
	record GiveEffect(String effectId, String spokenName, int durationTicks,
			int amplifier) implements FastPathIntent { }

	record Guard(int radius, boolean persistent) implements FastPathIntent { }

	/** “停止保护”：关闭长期 GuardPolicy（方案 D1）。 */
	record GuardStop() implements FastPathIntent { }

	record AidOwner() implements FastPathIntent { }

	record RecallLocation(String typeOrName) implements FastPathIntent { }

	/** “这里是基地/仓库/农场/矿洞”：以玩家当前位置写入长期记忆（方案 E2）。 */
	record RememberLocation(String typeOrName) implements FastPathIntent { }

	/** “把选定区域铺成石头”：区域只来自 server 拥有的选区（方案 F2/B07）。 */
	record FillSelection(String blockId) implements FastPathIntent { }

	/** “帮我盖一个房子”：形状来自内置模板，玩家只挑风格。 */
	record BuildHouse(String styleWord) implements FastPathIntent { }

	/**
	 * 「帮我准备一个矿井前哨站」——一句大目标，服务端拆成六个阶段。
	 *
	 * <p>和 {@link BuildHouse} 一样只把原句带过去：到底是哪份蓝图要查注册表，
	 * 而注册表（含数据包里的）只存在于服务端。</p>
	 */
	record StartProject(String phrase) implements FastPathIntent { }

	/** Durable project controls are deterministic and never delegated to the LLM. */
	record ProjectControl(Kind kind) implements FastPathIntent {
		public enum Kind { PAUSE, RESUME }
	}

	/** “你能做什么”：列出全部能力和例句。玩家最需要、却最容易被忽略的一条。 */
	record Help() implements FastPathIntent { }

	/** 「你叫什么」：他得记得自己的名字，被命名牌改过之后也一样。 */
	record WhatIsYourName() implements FastPathIntent { }

	/** “治疗自己”：让伙伴吃自己背包里的东西回血（区别于 AidOwner 是治玩家）。 */
	record HealSelf() implements FastPathIntent { }
}
