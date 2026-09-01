package dev.squire.server.ext;

import java.util.List;
import java.util.Map;

/**
 * 把外部工具的结构化结果渲染成玩家看得懂的一句话（方案 I1）。
 *
 * <p>玩家问"那台机器怎么样了"，想看到的是"运行中，能量 812"，而不是一坨 JSON。
 * 原始结构化数据仍然完整地进入受限日志和下一轮模型上下文——渲染只影响
 * <b>给玩家看的那一份</b>，不会削减模型能拿到的信息。</p>
 */
public final class ResultRenderer {

	/** 玩家可见摘要的长度上限。 */
	public static final int MAX_SUMMARY_CHARS = 300;
	/** 摘要里最多列几个字段。 */
	private static final int MAX_FIELDS = 6;

	/** 这些键在摘要里优先显示——它们通常就是玩家真正问的东西。 */
	private static final List<String> PREFERRED_KEYS = List.of(
		"status", "state", "energy", "power", "progress", "level", "amount",
		"count", "temperature", "fuel", "health", "message", "result", "value");

	private ResultRenderer() {
	}

	/**
	 * 渲染一次成功的外部调用。
	 *
	 * @param toolName 工具名，出现在摘要开头
	 * @param data     已经过 {@link ExternalResultSanitizer} 消毒的数据
	 */
	public static String renderSuccess(String toolName, Map<String, Object> data) {
		if (data == null || data.isEmpty()) {
			return toolName + "：完成（没有返回数据）";
		}
		StringBuilder text = new StringBuilder(toolName).append("：");
		int shown = 0;
		// 先放玩家最可能关心的字段，再按原顺序补齐
		for (String key : PREFERRED_KEYS) {
			if (shown >= MAX_FIELDS) {
				break;
			}
			if (data.containsKey(key)) {
				append(text, key, data.get(key), shown++);
			}
		}
		for (Map.Entry<String, Object> entry : data.entrySet()) {
			if (shown >= MAX_FIELDS) {
				break;
			}
			if (PREFERRED_KEYS.contains(entry.getKey())) {
				continue;
			}
			append(text, entry.getKey(), entry.getValue(), shown++);
		}
		if (data.size() > shown) {
			text.append("（另有 ").append(data.size() - shown).append(" 项）");
		}
		return cap(text.toString());
	}

	/** 渲染一次失败：错误码 + 人话，玩家至少知道是超时还是没权限。 */
	public static String renderFailure(String toolName, String errorCode,
			String message) {
		String reason = switch (errorCode == null ? "" : errorCode) {
			case "MCP_TIMEOUT" -> "远端超时未响应";
			case "MCP_DISCONNECTED" -> "远端未连接";
			case "MCP_PROTOCOL_ERROR" -> "远端协议错误";
			case "MCP_UNTRUSTED" -> "该外部工具未获信任";
			case "MCP_CIRCUIT_OPEN" -> "该服务器连续失败，已暂时熔断";
			default -> errorCode == null ? "未知错误" : errorCode;
		};
		StringBuilder text = new StringBuilder(toolName).append("：失败（")
			.append(reason).append('）');
		if (message != null && !message.isBlank()) {
			text.append('：').append(message);
		}
		return cap(text.toString());
	}

	/** Still-running call: say so plainly rather than pretending it finished. */
	public static String renderRunning(String toolName) {
		return toolName + "：已发出请求，等待远端返回…";
	}

	private static void append(StringBuilder text, String key, Object value, int index) {
		if (index > 0) {
			text.append("，");
		}
		text.append(key).append('=').append(flatten(value));
	}

	/** Nested values collapse to a shape hint — the summary is not a JSON dump. */
	private static String flatten(Object value) {
		if (value == null) {
			return "null";
		}
		if (value instanceof Map<?, ?> map) {
			return "{" + map.size() + " 项}";
		}
		if (value instanceof Iterable<?> items) {
			int n = 0;
			for (Object ignored : items) {
				n++;
			}
			return "[" + n + " 项]";
		}
		String text = String.valueOf(value);
		return text.length() <= 80 ? text : text.substring(0, 80) + "…";
	}

	private static String cap(String text) {
		return text.length() <= MAX_SUMMARY_CHARS ? text
			: text.substring(0, MAX_SUMMARY_CHARS) + "…";
	}
}
