package dev.squire.server.input;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 「这句话是在跟他说吗」。
 *
 * <p>公共聊天频道里绝大多数话不是对侍从说的。在此之前<b>每一句</b>聊天都会被
 * 送进输入网关：多人服里旁人一句「跟着我」就能把别人的侍从支使走，单人里自言自语
 * 也会白白烧一次大模型调用。所以聊天这条路要求先<b>点名</b>——话里带上他的名字，
 * 他才理会。面板输入框、快捷指令和 {@code /squire} 命令都是明确指向他的，
 * 那几条路不走这道闸。</p>
 *
 * <p>点名之后名字本身要从正文里<b>摘掉</b>，否则「豆包，跟着我」不会命中 FastPath
 * 的精确短语表（它按整句匹配），玩家会觉得"带上名字反而不好使了"。</p>
 */
public final class Addressing {

	/** 无论叫什么名字，这几个称呼都算点名。 */
	private static final List<String> BUILT_IN_ALIASES = List.of("侍从", "squire");

	/** 名字两侧允许出现、摘掉名字后要一并去掉的标点。 */
	private static final String SEPARATORS = " \t，,、：:；;。.！!？?~～ー-—@";

	/**
	 * 一次点名判定的结果。
	 *
	 * @param addressed 这句话有没有点他的名
	 * @param text      摘掉名字之后剩下的正文（可能为空，表示"只叫了一声名字"）
	 */
	public record Result(boolean addressed, String text) {

		public boolean hasText() {
			return !text.isBlank();
		}
	}

	private Addressing() {
	}

	/**
	 * @param raw  玩家原话
	 * @param name 他当前的名字，可为空
	 */
	public static Result parse(String raw, String name) {
		if (raw == null || raw.isBlank()) {
			return new Result(false, "");
		}
		String text = raw.trim();
		for (String alias : aliases(name)) {
			int at = indexOfIgnoreCase(text, alias);
			if (at < 0) {
				continue;
			}
			return new Result(true, join(text.substring(0, at),
				text.substring(at + alias.length())));
		}
		return new Result(false, text);
	}

	/** 他所有的称呼，长的排前面——「豆包」和「豆」同时存在时先摘掉长的那个。 */
	private static List<String> aliases(String name) {
		List<String> all = new ArrayList<>();
		if (name != null && !name.isBlank() && !"Squire".equals(name.trim())) {
			all.add(name.trim());
		}
		all.addAll(BUILT_IN_ALIASES);
		all.sort((a, b) -> Integer.compare(b.length(), a.length()));
		return all;
	}

	private static int indexOfIgnoreCase(String haystack, String needle) {
		return haystack.toLowerCase(Locale.ROOT)
			.indexOf(needle.toLowerCase(Locale.ROOT));
	}

	/**
	 * 把名字前后两截接回去。
	 *
	 * <p>接缝上的标点要吃掉（「豆包，跟着我」→「跟着我」）；两截都还有字时，
	 * 只有在两边都是 ASCII 的情况下才补一个空格——中文本来就不写空格，
	 * 补进去会让「帮我 拿点东西」对不上短语表。</p>
	 */
	private static String join(String before, String after) {
		String left = trimEnd(before);
		String right = trimStart(after);
		if (left.isEmpty()) {
			return right;
		}
		if (right.isEmpty()) {
			return left;
		}
		boolean asciiSeam = left.charAt(left.length() - 1) < 128 && right.charAt(0) < 128;
		return left + (asciiSeam ? " " : "") + right;
	}

	private static String trimStart(String text) {
		int i = 0;
		while (i < text.length() && SEPARATORS.indexOf(text.charAt(i)) >= 0) {
			i++;
		}
		return text.substring(i);
	}

	private static String trimEnd(String text) {
		int end = text.length();
		while (end > 0 && SEPARATORS.indexOf(text.charAt(end - 1)) >= 0) {
			end--;
		}
		return text.substring(0, end);
	}
}
