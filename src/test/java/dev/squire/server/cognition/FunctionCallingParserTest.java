package dev.squire.server.cognition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class FunctionCallingParserTest {
	@Test
	void clarificationAndPlanAreStructuredAndCannotExecuteTogether() {
		var turn = FunctionCallingParser.parse(
			"{\"say\":\"我需要确认\",\"ask\":\"墙用什么材料？\","
				+ "\"plan\":[\"确认材料\",\"施工\"],\"tool_calls\":[]}");
		assertFalse(turn.malformed());
		assertEquals("墙用什么材料？", turn.ask());
		assertEquals(List.of("确认材料", "施工"), turn.plan());

		var unsafe = FunctionCallingParser.parse(
			"{\"ask\":\"确认？\",\"tool_calls\":[{\"name\":\"world.set_time\"}]}" );
		assertTrue(unsafe.malformed());
	}

	@Test
	void plainTextIsASayTurn() {
		var turn = FunctionCallingParser.parse("你好，我在这");
		assertFalse(turn.malformed());
		assertEquals("你好，我在这", turn.say());
		assertFalse(turn.hasWork());
	}

	/**
	 * 这几种输入曾经让整个对话回合"成功"结束却一个字都不说：解析结果既不是
	 * malformed，又没有工具调用，say 还是空的，于是 ConversationOrchestrator
	 * 直接 return。解析器这个行为本身没错（空回复确实不是语法错误），但下游
	 * 必须知道它会出现。把这条前提钉死，改解析器时才不会悄悄改掉下游的假设。
	 */
	@Test
	void anEmptyModelReplyIsWellFormedButCarriesNoWorkAndNothingToSay() {
		for (String raw : new String[] {
				null, "", "   ", "\n\n", "{}", "{\"say\":\"\"}",
				"{\"say\":\"\",\"tool_calls\":[]}" }) {
			var turn = FunctionCallingParser.parse(raw);
			assertFalse(turn.malformed(), () -> "should not be malformed: " + raw);
			assertFalse(turn.hasWork(), () -> "should carry no work: " + raw);
			assertTrue(turn.say().isBlank(), () -> "should say nothing: " + raw);
		}
	}

	@Test
	void toolCallWithSayParses() {
		String raw = """
			{"say":"好的","tool_calls":[{"name":"navigation.move_to",
			  "arguments":{"x":10,"y":64,"z":-3}}]}
			""";
		var turn = FunctionCallingParser.parse(raw);
		assertFalse(turn.malformed());
		assertEquals("好的", turn.say());
		assertEquals(1, turn.calls().size());
		assertEquals("navigation.move_to", turn.calls().get(0).toolName());
		Map<String, Object> args = turn.calls().get(0).arguments();
		assertEquals(10L, args.get("x"));
		assertEquals(-3L, args.get("z"));
	}

	@Test
	void multipleCallsPreserveOrder() {
		String raw = """
			{"tool_calls":[
			  {"name":"task.acquire","arguments":{"item":"minecraft:torch","count":32}},
			  {"name":"inventory.give","arguments":{"item":"minecraft:torch","count":32}}]}
			""";
		var turn = FunctionCallingParser.parse(raw);
		assertEquals(2, turn.calls().size());
		assertEquals(32L, turn.calls().get(0).arguments().get("count"));
		assertTrue(turn.calls().get(1).callId() != null);
	}

	@Test
	void brokenJsonIsMalformedNotChatter() {
		var turn = FunctionCallingParser.parse("{\"say\": \"cut off...");
		assertTrue(turn.malformed());
		assertTrue(turn.parseError().contains("JSON"));
		assertTrue(turn.calls().isEmpty());
	}

	@Test
	void callMissingNameIsMalformed() {
		var turn = FunctionCallingParser.parse(
			"""
			{"tool_calls":[{"arguments":{"x":1}}]}
			""");
		assertTrue(turn.malformed());
	}

	@Test
	void fencedJsonIsUnwrapped() {
		var turn = FunctionCallingParser.parse("""
			```json
			{"say":"ok","tool_calls":[]}
			```
			""");
		assertFalse(turn.malformed());
		assertEquals("ok", turn.say());
	}

	@Test
	void blankAndNullDegradeToEmptySay() {
		assertTrue(FunctionCallingParser.parse("").say().isEmpty());
		assertTrue(FunctionCallingParser.parse(null).say().isEmpty());
	}
}
