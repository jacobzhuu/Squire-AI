package dev.squire.server.input;

import java.util.Locale;

/**
 * Small deterministic dialogue-control router placed before the LLM.
 *
 * <p>It intentionally recognises only high-confidence control/correction shapes;
 * domain intents still go through FastPath or the model. This makes “算了” and
 * “不是村庄，是古城” reliable without fuzzy matching a destructive command.</p>
 */
public final class NaturalLanguageIntentRouter {
	public enum Kind { CANCEL, CORRECTION, CONTINUATION, ORDINARY }

	public record Route(Kind kind, String normalized, double confidence) { }

	private NaturalLanguageIntentRouter() { }

	public static Route route(String raw) {
		String text = normalize(raw);
		if (text.isEmpty()) return new Route(Kind.ORDINARY, text, 0d);
		if (equalsAny(text, "算了", "取消", "别做了", "不用了", "停掉任务",
				"cancel", "never mind", "forget it")) {
			return new Route(Kind.CANCEL, text, 1d);
		}
		if (startsAny(text, "不是", "不对", "改成", "换成", "我说的是",
				"更正", "correction", "i meant", "change it to")) {
			return new Route(Kind.CORRECTION, text, 0.98d);
		}
		if (equalsAny(text, "继续", "接着做", "就这样", "可以", "好的", "对",
				"continue", "go ahead", "yes")) {
			return new Route(Kind.CONTINUATION, text, 0.96d);
		}
		return new Route(Kind.ORDINARY, text, 0.5d);
	}

	private static String normalize(String value) {
		if (value == null) return "";
		return value.toLowerCase(Locale.ROOT).trim()
			.replaceAll("[，。！？!?]+$", "").replaceAll("\\s+", " ");
	}

	private static boolean equalsAny(String text, String... choices) {
		for (String choice : choices) if (text.equals(choice)) return true;
		return false;
	}

	private static boolean startsAny(String text, String... choices) {
		for (String choice : choices) if (text.startsWith(choice)) return true;
		return false;
	}
}
