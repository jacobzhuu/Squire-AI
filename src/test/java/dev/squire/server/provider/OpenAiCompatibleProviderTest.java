package dev.squire.server.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.squire.common.protocol.AgentRequest;
import dev.squire.common.protocol.AgentResponse;
import dev.squire.common.protocol.ToolDescriptor;
import dev.squire.server.provider.HttpTransport.Response;

/**
 * The adapter is a dumb async text pipe (§23): correct request shape, verbatim
 * content extraction, fail-closed errors that never echo the key. All runtime
 * gating stays server-side — asserted here by contract, enforced elsewhere.
 */
class OpenAiCompatibleProviderTest {

	private static final LlmConfig CONFIG = new LlmConfig(
		"https://api.example.com/v1", "sk-test-key-abcdef123456", "test-model",
		0.5d, 400, 8_000L, 12_345L);

	/** Records calls; replays scripted responses; never touches a network. */
	private static final class FakeTransport implements HttpTransport {
		final List<Response> scripted;
		final AtomicInteger postCalls = new AtomicInteger();
		final AtomicInteger getCalls = new AtomicInteger();
		String lastUrl;
		Map<String, String> lastHeaders;
		String lastBody;
		long lastTimeoutMs = -1;
		int getStatus = 200;
		RuntimeException postFailure;

		FakeTransport(Response... responses) {
			this.scripted = List.of(responses);
		}

		@Override
		public CompletableFuture<Response> postJson(String url,
				Map<String, String> headers, String bodyJson, long timeoutMs) {
			this.lastUrl = url;
			this.lastHeaders = headers;
			this.lastBody = bodyJson;
			this.lastTimeoutMs = timeoutMs;
			if (postFailure != null) {
				return CompletableFuture.failedFuture(postFailure);
			}
			return CompletableFuture.completedFuture(
				scripted.get(Math.min(postCalls.getAndIncrement(),
					scripted.size() - 1)));
		}

		@Override
		public CompletableFuture<Response> get(String url,
				Map<String, String> headers, long timeoutMs) {
			getCalls.incrementAndGet();
			assertTrue(headers.containsKey("Authorization"), "health check auths");
			return CompletableFuture.completedFuture(new Response(getStatus, "[]"));
		}
	}

	private static ToolDescriptor tool(String name) {
		return new ToolDescriptor(name, "does " + name + " things",
			List.of(new ToolDescriptor.ParameterDescriptor("target", "string",
				true, "who to " + name, null, null)));
	}

	private static AgentRequest request() {
		return new AgentRequest(UUID.randomUUID(), UUID.randomUUID(),
			UUID.randomUUID(), "[context] hp=18\n[player] hello there");
	}

	private static Response contentResponse(String text) {
		JsonObject message = new JsonObject();
		message.addProperty("role", "assistant");
		message.addProperty("content", text);
		JsonObject choice = new JsonObject();
		choice.add("message", message);
		choice.addProperty("finish_reason", "stop");
		JsonArray choices = new JsonArray();
		choices.add(choice);
		JsonObject root = new JsonObject();
		root.addProperty("id", "cmpl-1");
		root.add("choices", choices);
		return new Response(200, root.toString());
	}

	/**
	 * 原生 function calling：请求里带上 {@code tools}，回复里的 {@code tool_calls}
	 * 被折算成运行时本来就认识的那一种回合 JSON。
	 *
	 * <p>这样格式由服务端强制，少掉一整类「模型正文不是合法 JSON →
	 * MALFORMED_MODEL_OUTPUT → 重规划」的往返；而
	 * {@code FunctionCallingParser} 仍然是"回合长什么样"的唯一定义。</p>
	 */
	@Test
	void nativeToolCallsAreSentAndNormalisedBackIntoTheTurnFormat() {
		JsonObject function = new JsonObject();
		function.addProperty("name", "sq_0_world_set_time");
		function.addProperty("arguments", "{\"preset\":\"day\"}");
		JsonObject entry = new JsonObject();
		entry.addProperty("id", "call_abc");
		entry.addProperty("type", "function");
		entry.add("function", function);
		JsonArray toolCalls = new JsonArray();
		toolCalls.add(entry);
		JsonObject message = new JsonObject();
		message.addProperty("role", "assistant");
		message.addProperty("content", "这就调");
		message.add("tool_calls", toolCalls);

		FakeTransport transport = new FakeTransport(wrap(message));
		OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(CONFIG,
			transport, agentId -> List.of(tool("world.set_time")));
		String text = provider.generate(request()).join().text();

		JsonObject turn = JsonParser.parseString(text).getAsJsonObject();
		assertEquals("这就调", turn.get("say").getAsString());
		JsonObject call = turn.getAsJsonArray("tool_calls").get(0).getAsJsonObject();
		assertEquals("world.set_time", call.get("name").getAsString());
		assertEquals("day", call.getAsJsonObject("arguments").get("preset").getAsString());
		// 解析器必须原样吃下去——这是"只有一种回合格式"的实际含义。
		var parsed = dev.squire.server.cognition.FunctionCallingParser.parse(text);
		assertFalse(parsed.malformed(), "normalised turn must parse cleanly");
		assertEquals("world.set_time", parsed.calls().get(0).toolName());

		JsonObject body = JsonParser.parseString(transport.lastBody).getAsJsonObject();
		assertTrue(body.has("tools"), "native catalog must be sent");
		assertEquals("auto", body.get("tool_choice").getAsString());
		JsonObject sent = body.getAsJsonArray("tools").get(0).getAsJsonObject()
			.getAsJsonObject("function");
		assertEquals("sq_0_world_set_time", sent.get("name").getAsString());
		assertTrue(sent.get("name").getAsString().matches("^[a-zA-Z0-9_-]+$"),
			"wire names must satisfy strict OpenAI-compatible endpoints");
		assertTrue(sent.get("description").getAsString().contains("world.set_time"),
			"the model still sees the canonical runtime name");
		assertEquals("object",
			sent.getAsJsonObject("parameters").get("type").getAsString());
	}

	@Test
	void nativeAliasesAreUniqueSafeAndCappedAtSixtyFourCharacters() {
		String first = OpenAiCompatibleProvider.nativeFunctionName(
			"plugin.example:world.tool.with.dots", 7);
		String collision = OpenAiCompatibleProvider.nativeFunctionName(
			"plugin.example/world/tool/with/dots", 8);
		String longName = OpenAiCompatibleProvider.nativeFunctionName("x".repeat(100), 9);

		assertTrue(first.matches("^[a-zA-Z0-9_-]+$"));
		assertTrue(collision.matches("^[a-zA-Z0-9_-]+$"));
		assertFalse(first.equals(collision), "the request ordinal prevents collisions");
		assertTrue(longName.length() <= 64, "OpenAI function names are capped at 64");
	}

	/** 端点忽略了 tools、退回在正文里写 JSON 时，仍然照常工作。 */
	@Test
	void aTextOnlyReplyStillWorksWhenTheEndpointIgnoresTools() {
		FakeTransport transport = new FakeTransport(contentResponse(
			"{\"say\":\"好\",\"tool_calls\":[{\"id\":\"c1\","
				+ "\"name\":\"world.set_time\",\"arguments\":{\"preset\":\"day\"}}]}"));
		OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(CONFIG,
			transport, agentId -> List.of(tool("world.set_time")));
		var parsed = dev.squire.server.cognition.FunctionCallingParser.parse(
			provider.generate(request()).join().text());
		assertFalse(parsed.malformed());
		assertEquals("world.set_time", parsed.calls().get(0).toolName());
	}

	/** 关掉开关时不发 tools —— 给不支持它的兼容端点留的退路。 */
	@Test
	void theNativeCatalogCanBeTurnedOff() {
		LlmConfig off = new LlmConfig("https://api.example.com/v1", "sk-x", "m",
			0.5d, 400, 8_000L, 12_345L, false);
		FakeTransport transport = new FakeTransport(contentResponse("hi"));
		new OpenAiCompatibleProvider(off, transport, agentId -> List.of(tool("a.b")))
			.generate(request()).join();
		assertFalse(JsonParser.parseString(transport.lastBody).getAsJsonObject()
			.has("tools"));
	}

	private static Response wrap(JsonObject message) {
		JsonObject choice = new JsonObject();
		choice.add("message", message);
		JsonArray choices = new JsonArray();
		choices.add(choice);
		JsonObject root = new JsonObject();
		root.add("choices", choices);
		return new Response(200, root.toString());
	}

	@Test
	void generatePostsCorrectEndpointHeadersAndBody() {
		FakeTransport transport = new FakeTransport(contentResponse("hi!"));
		OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(CONFIG,
			transport, agentId -> List.of(tool("squire.give")));

		String text = provider.generate(request()).join().text();
		assertEquals("hi!", text);
		assertEquals("https://api.example.com/v1/chat/completions", transport.lastUrl);
		assertEquals("Bearer sk-test-key-abcdef123456",
			transport.lastHeaders.get("Authorization"));
		assertEquals(8000L, transport.lastTimeoutMs);

		JsonObject body = JsonParser.parseString(transport.lastBody).getAsJsonObject();
		assertEquals("test-model", body.get("model").getAsString());
		assertEquals(2, body.getAsJsonArray("messages").size());
		assertTrue(body.get("messages").toString().contains("[player] hello there"),
			"user message must carry perception + input");
	}

	@Test
	void systemPromptCarriesContractAndCatalog() {
		FakeTransport transport = new FakeTransport(contentResponse("{}"));
		OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(CONFIG,
			transport, agentId -> List.of(tool("squire.give"), tool("squire.follow")));
		provider.generate(request()).join();

		String system = bodyMessages(transport)[0];
		assertTrue(system.contains("\"tool_calls\""), "wire contract present");
		assertTrue(system.contains("c1, c2"), "id convention present");
		assertTrue(system.contains("squire.give") && system.contains("squire.follow"),
			"catalog listed");
		assertFalse(system.contains("arg target (string, required)"),
			"native function schemas must not be duplicated in the prompt");
		// 提示词是措辞的唯一来源，工具目录一变它就会漂。这两条把第 1 期的边界钉住：
		// 搬运与施工已解禁，但野外采集仍然禁止，挖除只能在蓝图足印内。
		assertTrue(system.contains("blueprint"),
			"the prompt must name the blueprint path, or it drifts from the tool catalog");
		assertTrue(system.contains("chop trees"),
			"the wild-gathering ban must stay spelled out in the prompt");
		assertTrue(system.contains("Never claim a task is done"),
			"no-completion-claim rule present");
	}

	@Test
	void textProtocolStillCarriesArgumentSchemas() {
		LlmConfig textOnly = new LlmConfig("https://api.example.com/v1", "sk-x", "m",
			0.5d, 400, 8_000L, 12_345L, false);
		FakeTransport transport = new FakeTransport(contentResponse("hi"));
		new OpenAiCompatibleProvider(textOnly, transport,
			agentId -> List.of(tool("squire.give"))).generate(request()).join();
		assertTrue(bodyMessages(transport)[0].contains("arg target (string, required)"));
	}

	@Test
	void emptyTruncatedAndUsageSignalsArePreserved() {
		JsonObject message = new JsonObject();
		message.addProperty("role", "assistant");
		message.addProperty("content", "");
		JsonObject choice = new JsonObject();
		choice.add("message", message);
		choice.addProperty("finish_reason", "length");
		JsonObject usage = new JsonObject();
		usage.addProperty("prompt_tokens", 100);
		usage.addProperty("completion_tokens", 20);
		usage.addProperty("total_tokens", 120);
		JsonObject root = new JsonObject();
		root.addProperty("id", "chatcmpl-truncated");
		JsonArray choices = new JsonArray();
		choices.add(choice);
		root.add("choices", choices);
		root.add("usage", usage);

		var response = OpenAiCompatibleProvider.extractResponse(
			new Response(200, root.toString()));
		assertEquals(AgentResponse.Outcome.TRUNCATED, response.outcome());
		assertEquals("length", response.finishReason());
		assertEquals(120, response.usage().totalTokens());
		assertEquals("chatcmpl-truncated", response.requestId());
	}

	@Test
	void aSuccessfulEmptyReplyIsClassifiedInsteadOfSilentlyCompleting() {
		var response = OpenAiCompatibleProvider.extractResponse(contentResponse(""));
		assertEquals(AgentResponse.Outcome.EMPTY_OUTPUT, response.outcome());
		assertFalse(response.hasOutput());
	}

	@Test
	void oversizedContextIsBoundedWhileKeepingGoalAndRecentObservations() {
		LlmConfig small = new LlmConfig("https://api.example.com/v1", "sk-x", "m",
			0.2d, 256, 8_000L, 1_024L, true, 8);
		FakeTransport transport = new FakeTransport(contentResponse("ok"));
		String longMessage = "[player] 给我盖房子\n" + "旧上下文".repeat(2000)
			+ "\n[structured_observations] 最后的可靠结果";
		new OpenAiCompatibleProvider(small, transport,
			agentId -> List.of(tool("blueprint.build")))
			.generate(new AgentRequest(UUID.randomUUID(), UUID.randomUUID(),
				UUID.randomUUID(), longMessage)).join();
		String sent = bodyMessages(transport)[1];
		assertTrue(sent.startsWith("[player] 给我盖房子"));
		assertTrue(sent.contains("[older_context_truncated]"));
		assertTrue(sent.endsWith("最后的可靠结果"));
	}

	/**
	 * 玩家用命名牌把他改成「豆包」之后，模型必须<b>以那个名字自称</b>。
	 *
	 * <p>这条是从一次真实反馈来的：名字当时只出现在感知块的 {@code yourName=} 里，
	 * 而系统提示词第一句写死着 "You are Squire" —— 一句硬写的身份声明压过一条上下文
	 * 事实，于是他一直自称 Squire。身份只能有一个出处。</p>
	 */
	@Test
	void theCompanionsOwnNameReachesTheSystemPrompt() {
		FakeTransport transport = new FakeTransport(contentResponse("好"));
		OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(CONFIG,
			transport, agentId -> List.of());
		provider.generate(new AgentRequest(UUID.randomUUID(), UUID.randomUUID(),
			UUID.randomUUID(), "[player] 你叫什么", "豆包")).join();

		String system = bodyMessages(transport)[0];
		assertTrue(system.contains("豆包"), "the player's chosen name must be in the prompt");
		assertFalse(system.contains("You are Squire"),
			"a hard-coded identity would override the name the player gave him");
	}

	@Test
	void anUnnamedCompanionKeepsTheDefaultIdentity() {
		assertTrue(OpenAiCompatibleProvider.systemPrompt(List.of(), "")
			.contains("You are Squire"), "no name yet: do not invent one");
	}

	@Test
	void emptyCatalogStillStatesContract() {
		String prompt = OpenAiCompatibleProvider.systemPrompt(List.of());
		assertTrue(prompt.contains("tool_calls"));
		assertTrue(prompt.contains("none right now"));
	}

	@Test
	void catalogSupplierConsultedPerRequest() {
		FakeTransport transport = new FakeTransport(contentResponse("a"),
			contentResponse("b"));
		AtomicInteger consults = new AtomicInteger();
		// 目录每次请求现取，而且带着 agentId——它要按<b>这只</b>随从的职业和等级裁剪。
		java.util.function.Function<java.util.UUID, List<ToolDescriptor>> catalog =
			agentId -> {
				consults.incrementAndGet();
				return List.of();
			};
		OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(CONFIG,
			transport, catalog);
		provider.generate(request()).join();
		provider.generate(request()).join();
		assertEquals(2, consults.get(), "late-registered tools must become visible");
	}

	@Test
	void non2xxFailsClosedWithoutEchoingKey() {
		FakeTransport transport = new FakeTransport(
			new Response(401, "{\"error\":\"unauthorized\"}"));
		OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(CONFIG,
			transport, agentId -> List.of());
		Throwable error = failureOf(provider.generate(request()));
		assertTrue(error.getMessage().contains("401"), error::getMessage);
		assertFalse(error.getMessage().contains("sk-test-key"),
			"error must not leak the key");
	}

	@Test
	void garbageReplyBodyFailsClosed() {
		FakeTransport transport = new FakeTransport(new Response(200, "<html>oops"));
		OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(CONFIG,
			transport, agentId -> List.of());
		assertTrue(failureOf(provider.generate(request())).getMessage()
			.contains("unparseable"));
	}

	@Test
	void replyWithoutChoicesFailsClosed() {
		FakeTransport transport = new FakeTransport(
			new Response(200, "{\"id\":\"x\",\"choices\":[]}"));
		OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(CONFIG,
			transport, agentId -> List.of());
		assertTrue(failureOf(provider.generate(request())).getMessage()
			.contains("no choices"));
	}

	@Test
	void transportFailureCompletesExceptionallyNeverBlocks() {
		FakeTransport transport = new FakeTransport();
		transport.postFailure =
			new RuntimeException(new java.io.IOException("cable chewed"));
		OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(CONFIG,
			transport, agentId -> List.of());
		CompletableFuture<AgentResponse> future = provider.generate(request());
		assertTrue(future.isCompletedExceptionally(), "no blocking wait anywhere");
		Throwable error = failureOf(future);
		while (error.getCause() != null) {
			error = error.getCause();
		}
		Throwable root = error; // effectively-final snapshot for the lambda
		assertTrue(root instanceof java.io.IOException, () -> "" + root);
	}

	@Test
	void healthCheckTrueOnlyOn200() {
		FakeTransport transport = new FakeTransport();
		OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(CONFIG,
			transport, agentId -> List.of());
		assertTrue(provider.healthCheck().join());
		assertEquals(1, transport.getCalls.get(), "health probe is one cheap GET");
		transport.getStatus = 503;
		assertFalse(provider.healthCheck().join());
	}

	@Test
	void capabilitiesDeclareFunctionCallingViaProtocol() {
		OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(CONFIG,
			new FakeTransport(), agentId -> List.of());
		assertTrue(provider.capabilities().functionCalling());
		assertFalse(provider.capabilities().streaming());
		assertEquals(12_345L, provider.capabilities().maxContextTokens());
		assertEquals("openai-compatible", provider.id());
	}

	private static Throwable failureOf(CompletableFuture<AgentResponse> future) {
		try {
			future.join();
			throw new AssertionError("expected exceptional completion");
		} catch (CompletionException e) {
			return e.getCause() == null ? e : e.getCause();
		}
	}

	private static String[] bodyMessages(FakeTransport transport) {
		var messages = JsonParser.parseString(transport.lastBody).getAsJsonObject()
			.getAsJsonArray("messages");
		String[] out = new String[messages.size()];
		for (int i = 0; i < messages.size(); i++) {
			out[i] = messages.get(i).getAsJsonObject().get("content").getAsString();
		}
		return out;
	}
}
