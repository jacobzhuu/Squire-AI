package dev.squire.server.ext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 外部结果的入口消毒（方案 I1）。
 *
 * <p>第三方 mod 和 MCP 服务器返回的东西不受我们控制：它可能很大、嵌套很深、
 * 或者夹带 api key。这些数据会进日志、进玩家聊天、进下一轮模型上下文，所以在
 * 它们被任何人看到之前，先在这里统一限制体积、字符串长度、嵌套深度，并对敏感
 * 字段脱敏。</p>
 *
 * <p>脱敏是按 KEY 判断的：值本身可能是任何格式，但"这个字段叫 token"是可靠信号。
 * 被删掉的东西一律留下明确标记，绝不假装数据本来就长这样。</p>
 */
public final class ExternalResultSanitizer {

	/** 最大嵌套深度；超过的层级折叠成一句说明。 */
	public static final int MAX_DEPTH = 6;
	/** 单个 map 最多保留多少个键。 */
	public static final int MAX_ENTRIES = 64;
	/** 单个列表最多保留多少个元素。 */
	public static final int MAX_LIST_ITEMS = 64;
	/** 单个字符串的最大长度。 */
	public static final int MAX_STRING_CHARS = 2_048;
	/** 整个结果序列化后的最大字符数（调用方用它做最后一道闸）。 */
	public static final int MAX_TOTAL_CHARS = 8_192;

	private static final String REDACTED = "«redacted»";
	private static final String TRUNCATED = "…«truncated»";
	private static final String TOO_DEEP = "«nested too deeply»";

	/** 字段名里出现这些片段就认为是机密。 */
	private static final List<String> SENSITIVE_KEY_PARTS = List.of(
		"password", "passwd", "secret", "token", "apikey", "api_key", "authorization",
		"auth", "credential", "private_key", "privatekey", "session", "cookie",
		"bearer", "signature");

	private ExternalResultSanitizer() {
	}

	/** True when a field with this name must never be shown or logged. */
	public static boolean isSensitiveKey(String key) {
		if (key == null) {
			return false;
		}
		String lower = key.toLowerCase(Locale.ROOT).replace("-", "");
		for (String part : SENSITIVE_KEY_PARTS) {
			if (lower.contains(part.replace("_", ""))) {
				return true;
			}
		}
		return false;
	}

	/** Sanitize a whole result map. Never returns null. */
	public static Map<String, Object> sanitize(Map<String, Object> data) {
		if (data == null || data.isEmpty()) {
			return Map.of();
		}
		Object cleaned = sanitizeValue(data, 0);
		return cleaned instanceof Map<?, ?> map ? castMap(map) : Map.of("value", cleaned);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> castMap(Map<?, ?> map) {
		return (Map<String, Object>) map;
	}

	private static Object sanitizeValue(Object value, int depth) {
		if (value == null) {
			return null;
		}
		if (depth >= MAX_DEPTH) {
			return TOO_DEEP;
		}
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> out = new LinkedHashMap<>();
			int kept = 0;
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				if (kept >= MAX_ENTRIES) {
					out.put("«more»", (map.size() - kept) + " further field(s) dropped");
					break;
				}
				String key = String.valueOf(entry.getKey());
				out.put(key, isSensitiveKey(key)
					? REDACTED : sanitizeValue(entry.getValue(), depth + 1));
				kept++;
			}
			return out;
		}
		if (value instanceof Iterable<?> iterable) {
			List<Object> out = new ArrayList<>();
			int kept = 0;
			int total = 0;
			for (Object item : iterable) {
				total++;
				if (kept < MAX_LIST_ITEMS) {
					out.add(sanitizeValue(item, depth + 1));
					kept++;
				}
			}
			if (total > kept) {
				out.add((total - kept) + " further item(s) dropped");
			}
			return out;
		}
		if (value instanceof Object[] array) {
			return sanitizeValue(List.of(array), depth);
		}
		if (value instanceof String text) {
			return text.length() <= MAX_STRING_CHARS ? text
				: text.substring(0, MAX_STRING_CHARS) + TRUNCATED;
		}
		if (value instanceof Number || value instanceof Boolean) {
			return value;
		}
		// unknown type from a third-party mod: keep its text form, bounded
		return sanitizeValue(String.valueOf(value), depth);
	}

	/** Final size gate for anything about to be logged or sent onward. */
	public static String capTotal(String serialized) {
		if (serialized == null) {
			return "";
		}
		return serialized.length() <= MAX_TOTAL_CHARS ? serialized
			: serialized.substring(0, MAX_TOTAL_CHARS) + TRUNCATED;
	}
}
