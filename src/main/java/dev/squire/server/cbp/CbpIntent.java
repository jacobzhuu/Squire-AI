package dev.squire.server.cbp;

import java.util.List;
import java.util.Locale;

/**
 * 明确意图门槛（方案 H1）。
 *
 * <p>物化真实命令方块是这个 mod 里唯一会在世界上留下命令方块的动作，所以它绝不能
 * 被"顺便"触发。只有玩家把 <b>真实 / 可见 / 可编辑 / 命令方块 / 红石 / 教学 / 分享</b>
 * 这类词说出口，才算表达了这个意图。</p>
 *
 * <p>"每天晚上开灯"这种普通需求必须走 AutomationGraph；意图不明确时上层要反问玩家，
 * 而不是先写世界再解释。</p>
 */
public final class CbpIntent {

	/** 判定结果。 */
	public enum Verdict {
		/** 玩家明确要求了真实命令方块工程。 */
		EXPLICIT,
		/** 听起来像自动化需求，应该走 AutomationGraph。 */
		PREFER_AUTOMATION,
		/** 说不清楚——反问玩家，绝不写世界。 */
		UNCLEAR
	}

	public record Result(Verdict verdict, String reason) {
		public boolean explicit() {
			return verdict == Verdict.EXPLICIT;
		}
	}

	/** 必须出现其中之一才可能进入物化流程。 */
	private static final List<String> EXPLICIT_MARKERS = List.of(
		"命令方块", "command block", "commandblock",
		"真实的方块", "真方块",
		"可编辑", "可见的装置", "红石装置", "红石电路",
		"教学", "示范", "分享给",
		"cbp");

	/** 这些说法本身不足以构成"要命令方块"，只是形容词。 */
	private static final List<String> WEAK_MARKERS = List.of(
		"真实", "真的", "可见", "红石");

	/** 听起来是"让它自己发生"的普通自动化需求。 */
	private static final List<String> AUTOMATION_MARKERS = List.of(
		"每天", "每晚", "每次", "自动", "定时", "以后都",
		"every day", "every night", "automatically");

	private CbpIntent() {
	}

	/**
	 * 判定一句话是否真的在要求物化命令方块工程。
	 *
	 * @param text 玩家原话（未规范化也可以）
	 */
	public static Result classify(String text) {
		if (text == null || text.isBlank()) {
			return new Result(Verdict.UNCLEAR, "没有可判断的内容");
		}
		String lower = text.trim().toLowerCase(Locale.ROOT);
		boolean explicit = containsAny(lower, EXPLICIT_MARKERS);
		boolean automation = containsAny(lower, AUTOMATION_MARKERS);

		if (explicit) {
			// "每天晚上用命令方块开灯" —— 明确点名了命令方块，玩家要的就是装置
			return new Result(Verdict.EXPLICIT, "玩家明确要求了真实命令方块装置");
		}
		if (automation) {
			return new Result(Verdict.PREFER_AUTOMATION,
				"这听起来是长期自动化需求，用 AutomationGraph 实现，不会放命令方块");
		}
		if (containsAny(lower, WEAK_MARKERS)) {
			return new Result(Verdict.UNCLEAR,
				"你是想要一个真实的、可以自己编辑的命令方块装置，"
					+ "还是只要这件事自动发生？（后者不会在世界里放命令方块）");
		}
		return new Result(Verdict.UNCLEAR, "没听出你要的是命令方块装置还是普通自动化");
	}

	private static boolean containsAny(String text, List<String> needles) {
		for (String needle : needles) {
			if (text.contains(needle)) {
				return true;
			}
		}
		return false;
	}
}
