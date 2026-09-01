package dev.squire.server.ext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 方案 I1：第三方 mod 和 MCP 服务器返回的东西不受我们控制，但它会进日志、进聊天、
 * 进下一轮模型上下文。消毒和渲染是这条路上唯一的闸门。
 */
class ExternalResultHandlingTest {

	// ------------------------------------------------------------------ 消毒

	@Test
	void sensitiveFieldsAreRedactedByName() {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("status", "running");
		data.put("apiKey", "sk-live-abcdef");
		data.put("Authorization", "Bearer xyz");
		data.put("user_password", "hunter2");
		data.put("session-cookie", "abc");

		Map<String, Object> clean = ExternalResultSanitizer.sanitize(data);
		assertEquals("running", clean.get("status"));
		for (String key : List.of("apiKey", "Authorization", "user_password",
				"session-cookie")) {
			assertFalse(String.valueOf(clean.get(key)).contains("sk-live"),
				key + " must not leak");
			assertEquals("«redacted»", clean.get(key), key + " must be redacted");
		}
	}

	@Test
	void nestedSecretsAreRedactedToo() {
		Map<String, Object> inner = new LinkedHashMap<>();
		inner.put("token", "secret-value");
		inner.put("energy", 812);
		Map<String, Object> clean = ExternalResultSanitizer.sanitize(
			Map.of("machine", inner));

		@SuppressWarnings("unchecked")
		Map<String, Object> machine = (Map<String, Object>) clean.get("machine");
		assertEquals("«redacted»", machine.get("token"));
		assertEquals(812, machine.get("energy"));
	}

	@Test
	void oversizedStringsAreTruncatedRatherThanForwardedWhole() {
		String huge = "x".repeat(ExternalResultSanitizer.MAX_STRING_CHARS * 3);
		Map<String, Object> clean = ExternalResultSanitizer.sanitize(Map.of("blob", huge));
		String value = String.valueOf(clean.get("blob"));
		assertTrue(value.length() < huge.length(), "the blob must shrink");
		assertTrue(value.endsWith("«truncated»"),
			"and say so, rather than pretending it was that short");
	}

	@Test
	void deepNestingIsCollapsedInsteadOfRecursingForever() {
		Map<String, Object> deepest = new LinkedHashMap<>();
		deepest.put("bottom", 1);
		Map<String, Object> current = deepest;
		for (int i = 0; i < 30; i++) {
			Map<String, Object> wrapper = new LinkedHashMap<>();
			wrapper.put("level" + i, current);
			current = wrapper;
		}
		Map<String, Object> clean = ExternalResultSanitizer.sanitize(current);
		String rendered = String.valueOf(clean);
		assertTrue(rendered.contains("«nested too deeply»"),
			"the depth limit must be visible in the output");
	}

	@Test
	void hugeCollectionsAndMapsAreBounded() {
		List<Integer> many = new ArrayList<>();
		for (int i = 0; i < ExternalResultSanitizer.MAX_LIST_ITEMS * 4; i++) {
			many.add(i);
		}
		Map<String, Object> wide = new LinkedHashMap<>();
		for (int i = 0; i < ExternalResultSanitizer.MAX_ENTRIES * 3; i++) {
			wide.put("k" + i, i);
		}
		Map<String, Object> clean = ExternalResultSanitizer.sanitize(
			Map.of("list", many, "wide", wide));

		@SuppressWarnings("unchecked")
		List<Object> list = (List<Object>) clean.get("list");
		assertTrue(list.size() <= ExternalResultSanitizer.MAX_LIST_ITEMS + 1,
			"list bounded, was " + list.size());
		assertTrue(String.valueOf(list.get(list.size() - 1)).contains("dropped"));

		@SuppressWarnings("unchecked")
		Map<String, Object> boundedWide = (Map<String, Object>) clean.get("wide");
		assertTrue(boundedWide.size() <= ExternalResultSanitizer.MAX_ENTRIES + 1,
			"map bounded, was " + boundedWide.size());
	}

	@Test
	void anEmptyOrNullResultIsHandledWithoutThrowing() {
		assertTrue(ExternalResultSanitizer.sanitize(null).isEmpty());
		assertTrue(ExternalResultSanitizer.sanitize(Map.of()).isEmpty());
	}

	// ------------------------------------------------------------------ 渲染

	/** I3：玩家问机器状态，应该看到 "running / 812"，不是一坨 JSON。 */
	@Test
	void aQueryResultBecomesSomethingAPlayerCanRead() {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("status", "running");
		data.put("energy", 812);

		String rendered = ResultRenderer.renderSuccess("factory:machine_status", data);
		assertTrue(rendered.contains("status=running"), rendered);
		assertTrue(rendered.contains("energy=812"), rendered);
		assertFalse(rendered.contains("{"), "no raw JSON in the player's chat: " + rendered);
	}

	@Test
	void nestedValuesCollapseToAShapeHintInTheSummary() {
		String rendered = ResultRenderer.renderSuccess("t",
			Map.of("inventory", Map.of("a", 1, "b", 2)));
		assertTrue(rendered.contains("inventory={2 项}"), rendered);
	}

	@Test
	void theSummaryStaysShortEvenForAWideResult() {
		Map<String, Object> wide = new LinkedHashMap<>();
		for (int i = 0; i < 40; i++) {
			wide.put("field" + i, "value-" + i);
		}
		String rendered = ResultRenderer.renderSuccess("t", wide);
		assertTrue(rendered.length() <= ResultRenderer.MAX_SUMMARY_CHARS + 1,
			"summary length " + rendered.length());
		assertTrue(rendered.contains("另有"), "and it says how much it left out");
	}

	@Test
	void failuresExplainThemselvesInPlainLanguage() {
		assertTrue(ResultRenderer.renderFailure("t", "MCP_TIMEOUT", "10s")
			.contains("超时"));
		assertTrue(ResultRenderer.renderFailure("t", "MCP_DISCONNECTED", null)
			.contains("未连接"));
		assertTrue(ResultRenderer.renderFailure("t", "MCP_CIRCUIT_OPEN", null)
			.contains("熔断"));
		assertTrue(ResultRenderer.renderFailure("t", "SOMETHING_NEW", "detail")
			.contains("SOMETHING_NEW"), "unknown codes are shown, not swallowed");
	}

	@Test
	void anEmptySuccessSaysSoRatherThanLookingBroken() {
		assertTrue(ResultRenderer.renderSuccess("t", Map.of()).contains("没有返回数据"));
	}
}
