package dev.squire.server.provider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.UUID;
import java.util.function.Function;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.squire.api.provider.LlmProvider;
import dev.squire.api.provider.ProviderCapabilities;
import dev.squire.common.protocol.AgentRequest;
import dev.squire.common.protocol.AgentResponse;
import dev.squire.common.protocol.AgentResponse.Outcome;
import dev.squire.common.protocol.AgentResponse.Usage;
import dev.squire.common.protocol.ToolCall;
import dev.squire.common.protocol.ToolDescriptor;

/**
 * Spec section 23 built-in adapter for any OpenAI-compatible
 * {@code /chat/completions} endpoint (OpenAI, Azure-style gateways, vLLM,
 * Ollama's compat layer, ...). A dumb text adapter by design: it sends the
 * perception block plus the STRICT output contract, and returns whatever text
 * comes back. The runtime — never this class — parses tool calls
 * ({@code FunctionCallingParser}), gates them through the ToolGateway, and
 * decides outcomes.
 *
 * <p>Threading: every network step is async on the transport's threads; the
 * Minecraft server thread is never touched or blocked (spec section 3).</p>
 */
public final class OpenAiCompatibleProvider implements LlmProvider {

	private static final org.slf4j.Logger LOG =
		org.slf4j.LoggerFactory.getLogger(OpenAiCompatibleProvider.class);

	public static final String ID = "openai-compatible";

	private final String providerId;
	private final LlmConfig config;
	private final HttpTransport transport;
	/**
	 * 每次请求现取（在异步线程上），所以后注册的工具看得到。
	 *
	 * <p>入参是 <b>agentId</b>：目录要按这只随从当前的职业和等级裁剪，而职业会在
	 * 玩家转职、晋升的那一刻变。缓存一份「第一次聊天时的目录」会让玩家升到 Lv.3
	 * 之后还得重启游戏才用得上旋转。</p>
	 */
	private final Function<UUID, List<ToolDescriptor>> modelVisibleTools;

	/**
	 * 能力边界摘要（§23）。几行字，让他在玩家要求一件还没解锁的事时说得出要几级，
	 * 而不是编一个理由。没接线时是空串——最小/测试运行时不需要它。
	 */
	private final Function<UUID, String> capabilitySummary;

	public OpenAiCompatibleProvider(LlmConfig config, HttpTransport transport,
			Function<UUID, List<ToolDescriptor>> modelVisibleTools) {
		this(ID, config, transport, modelVisibleTools, agentId -> "");
	}

	public OpenAiCompatibleProvider(LlmConfig config, HttpTransport transport,
			Function<UUID, List<ToolDescriptor>> modelVisibleTools,
			Function<UUID, String> capabilitySummary) {
		this(ID, config, transport, modelVisibleTools, capabilitySummary);
	}

	public OpenAiCompatibleProvider(String providerId, LlmConfig config,
			HttpTransport transport,
			Function<UUID, List<ToolDescriptor>> modelVisibleTools,
			Function<UUID, String> capabilitySummary) {
		this.providerId = providerId == null || providerId.isBlank() ? ID : providerId;
		this.config = config;
		this.transport = transport;
		this.modelVisibleTools = modelVisibleTools;
		this.capabilitySummary = capabilitySummary;
	}

	@Override
	public String id() {
		return providerId;
	}

	@Override
	public ProviderCapabilities capabilities() {
		return new ProviderCapabilities(true, false, config.maxContextTokens());
	}

	@Override
	public CompletableFuture<AgentResponse> generate(AgentRequest request) {
		PreparedRequest prepared = prepareRequest(request);
		Map<String, String> headers = Map.of(
			"Authorization", "Bearer " + config.apiKey(),
			"Accept", "application/json");
		return transport
			.postJson(config.chatCompletionsUrl(), headers, prepared.body(), config.timeoutMs())
			.thenApply(response -> extractResponse(response, prepared.nativeToInternal()));
	}

	@Override
	public CompletableFuture<Boolean> healthCheck() {
		return transport.get(config.modelsUrl(),
			Map.of("Authorization", "Bearer " + config.apiKey()), config.timeoutMs())
			.handle((response, error) -> response != null && response.status() == 200);
	}

	// ------------------------------------------------------------------ request

	private PreparedRequest prepareRequest(AgentRequest request) {
		JsonObject body = new JsonObject();
		body.addProperty("model", config.model());
		body.addProperty("temperature", config.temperature());
		body.addProperty("max_tokens", config.maxTokens());
		// 只发这句话用得上的那些工具。全量目录已经四十条，其中绝大多数和当前这
		// 一句无关——既拖慢首 token，本身也是干扰。候选是保守评分且有固定上限；
		// 未命中时仍保留安全查询核心，而不是发空目录或无界全量目录。
		// 两层裁剪，顺序不能反：
		//   1. 职业/等级——这只随从<b>根本不会</b>的事（§23，在 currentTools 里做）；
		//   2. 这句话——他会但这次用不上的事。
		// 两层都在序列化<b>之前</b>。先拼四十条 schema 再说其中一半不能用，
		// 既没省下 token 也没减少干扰。
		List<ToolDescriptor> tools = new java.util.ArrayList<>(ToolRelevance.select(
			request.message(), currentTools(request.agentId()), false,
			config.toolCandidateLimit()));
		String userMessage = request.message();
		String summary = summaryFor(request.agentId());
		long inputBudget = Math.max(512L,
			config.maxContextTokens() - config.maxTokens() - 256L);
		while (tools.size() > 4 && estimatedInputTokens(tools, userMessage,
				request.agentName(), summary) > inputBudget) {
			tools.remove(tools.size() - 1);
		}
		userMessage = fitUserMessage(userMessage, Math.max(512,
			(int) Math.min(Integer.MAX_VALUE, inputBudget * 4L
				- systemPrompt(tools, request.agentName(), summary,
					!config.nativeToolCalls()).length())));
		JsonArray messages = new JsonArray();
		messages.add(message("system", systemPrompt(tools, request.agentName(),
			summary, !config.nativeToolCalls())));
		messages.add(message("user", userMessage));
		body.add("messages", messages);
		Map<String, String> nativeToInternal = new LinkedHashMap<>();
		if (config.nativeToolCalls() && !tools.isEmpty()) {
			body.add("tools", toolsJson(tools, nativeToInternal));
			body.addProperty("tool_choice", "auto");
		}
		return new PreparedRequest(body.toString(), Map.copyOf(nativeToInternal));
	}

	private long estimatedInputTokens(List<ToolDescriptor> tools, String userMessage,
			String agentName, String summary) {
		String prompt = systemPrompt(tools, agentName, summary,
			!config.nativeToolCalls());
		int schemaChars = 0;
		for (ToolDescriptor tool : tools) {
			schemaChars += tool.name().length() + tool.description().length() + 64;
			for (ToolDescriptor.ParameterDescriptor p : tool.parameters()) {
				schemaChars += p.name().length()
					+ (p.description() == null ? 0 : p.description().length()) + 48;
			}
		}
		return Math.max(1L, (prompt.length() + userMessage.length() + schemaChars) / 4L);
	}

	/** Preserve the current goal at the head and recent observations at the tail. */
	static String fitUserMessage(String message, int maxChars) {
		if (message == null || message.length() <= maxChars) return message == null ? "" : message;
		if (maxChars < 128) return message.substring(0, Math.max(0, maxChars));
		int head = Math.max(64, maxChars / 3);
		int tail = maxChars - head - 28;
		return message.substring(0, head)
			+ "\n[older_context_truncated]\n"
			+ message.substring(message.length() - Math.max(32, tail));
	}

	/** Request-local because a trimmed catalog can assign different aliases each turn. */
	private record PreparedRequest(String body, Map<String, String> nativeToInternal) { }

	/**
	 * 工具目录的原生 function-calling 形式。
	 *
	 * <p>用原生 {@code tools} 而不是让模型在正文里手写 JSON：格式由服务端强制，
	 * 少掉一整类「输出不是合法 JSON → MALFORMED_MODEL_OUTPUT → 重规划」的往返。
	 * 提示词里的文本契约仍然保留，因为并非所有兼容端点都真的支持 tools——
	 * 两条路解析出来的是同一个结构。</p>
	 */
	private static JsonArray toolsJson(List<ToolDescriptor> tools,
			Map<String, String> nativeToInternal) {
		JsonArray array = new JsonArray();
		for (int toolIndex = 0; toolIndex < tools.size(); toolIndex++) {
			ToolDescriptor tool = tools.get(toolIndex);
			JsonObject properties = new JsonObject();
			JsonArray required = new JsonArray();
			for (ToolDescriptor.ParameterDescriptor p : tool.parameters()) {
				JsonObject schema = new JsonObject();
				schema.addProperty("type", jsonType(p.type()));
				if (p.description() != null && !p.description().isBlank()) {
					schema.addProperty("description", oneLine(p.description()));
				}
				if (p.minimum() != null) {
					schema.addProperty("minimum", p.minimum());
				}
				if (p.maximum() != null) {
					schema.addProperty("maximum", p.maximum());
				}
				properties.add(p.name(), schema);
				if (p.required()) {
					required.add(p.name());
				}
			}
			JsonObject parameters = new JsonObject();
			parameters.addProperty("type", "object");
			parameters.add("properties", properties);
			parameters.add("required", required);

			JsonObject function = new JsonObject();
			String nativeName = nativeFunctionName(tool.name(), toolIndex);
			nativeToInternal.put(nativeName, tool.name());
			function.addProperty("name", nativeName);
			function.addProperty("description", "Runtime tool " + tool.name() + ". "
				+ oneLine(tool.description()));
			function.add("parameters", parameters);

			JsonObject entry = new JsonObject();
			entry.addProperty("type", "function");
			entry.add("function", function);
			array.add(entry);
		}
		return array;
	}

	/**
	 * OpenAI-compatible endpoints do not all accept the same function-name alphabet.
	 * DeepSeek, in particular, rejects the dots and colons used by Squire's internal
	 * namespaces. Give every advertised function a short request-local wire alias;
	 * the ordinal makes aliases collision-free even after punctuation is replaced.
	 */
	static String nativeFunctionName(String internalName, int index) {
		String prefix = "sq_" + index + "_";
		StringBuilder out = new StringBuilder(prefix);
		String source = internalName == null ? "tool" : internalName;
		for (int i = 0; i < source.length() && out.length() < 64; i++) {
			char c = source.charAt(i);
			boolean allowed = c >= 'a' && c <= 'z'
				|| c >= 'A' && c <= 'Z'
				|| c >= '0' && c <= '9'
				|| c == '_' || c == '-';
			out.append(allowed ? c : '_');
		}
		if (out.length() == prefix.length()) {
			out.append("tool");
		}
		return out.toString();
	}

	/** 内部类型名 → JSON Schema 类型。认不出的一律当字符串，绝不猜。 */
	private static String jsonType(String type) {
		if (type == null) {
			return "string";
		}
		return switch (type.toLowerCase(java.util.Locale.ROOT)) {
			case "int", "integer", "long" -> "integer";
			case "double", "float", "number" -> "number";
			case "boolean", "bool" -> "boolean";
			default -> "string";
		};
	}

	private JsonObject message(String role, String content) {
		JsonObject m = new JsonObject();
		m.addProperty("role", role);
		m.addProperty("content", content);
		return m;
	}

	/**
	 * The contract the server-side parser enforces (FunctionCallingParser): one
	 * JSON turn object, only listed tools, no completion claims. Kept here and
	 * nowhere else so prompt and parser cannot drift apart silently — the test
	 * suite asserts the documented tokens appear in this prompt.
	 */
	static String systemPrompt(List<ToolDescriptor> tools) {
		return systemPrompt(tools, null, "", true);
	}

	/**
	 * @param agentName 玩家给他起的名字（命名牌或面板改的），空表示还没起名
	 */
	static String systemPrompt(List<ToolDescriptor> tools, String agentName) {
		return systemPrompt(tools, agentName, "", true);
	}

	/**
	 * @param capabilitySummary 一小段「他现在是什么职业、会到哪一步、下一级解锁什么」。
	 *                          刻意<b>只有几行</b>：把锁着的工具 schema 全塞进来再写一句
	 *                          「不要用」，比不发它们更贵也更容易被模型忽略。空串表示
	 *                          没有职业信息可说。
	 */
	static String systemPrompt(List<ToolDescriptor> tools, String agentName,
			String capabilitySummary) {
		return systemPrompt(tools, agentName, capabilitySummary, true);
	}

	static String systemPrompt(List<ToolDescriptor> tools, String agentName,
			String capabilitySummary, boolean includeArgumentSchemas) {
		StringBuilder sb = new StringBuilder();
		// 名字必须写进<b>系统提示词</b>。以前这里写死 "You are Squire"，而名字只出现在
		// 感知块的 yourName= 里——一句硬写的身份声明压过一条上下文事实，于是玩家把他
		// 命名成「豆包」之后，他还是自称 Squire。身份只能有一个出处。
		String name = agentName == null ? "" : agentName.trim();
		if (name.isEmpty()) {
			sb.append("You are Squire, an in-game companion inside a Minecraft server.\n");
		} else {
			sb.append("Your name is \"").append(name)
				.append("\". You are an in-game companion inside a Minecraft server.\n");
			sb.append("The player named you \"").append(name)
				.append("\"; that IS your name. Answer to it, use it when you refer to ")
				.append("yourself, and never call yourself anything else (not 'Squire', ")
				.append("not an assistant, not a model).\n");
			sb.append("The player addresses you by saying your name, so a message that ")
				.append("contains \"").append(name)
				.append("\" is spoken to you — do not treat the name as part of the task.\n");
		}
		sb.append("Reply with EITHER plain conversational text OR exactly ONE JSON\n");
		sb.append("object and nothing else:\n");
		sb.append("{\"say\": \"optional short text\", \"ask\": \"one focused question or empty\",\n");
		sb.append(" \"plan\": [\"short player-visible step\"], \"tool_calls\": [\n");
		sb.append("  {\"id\": \"c1\", \"name\": \"<tool>\", \"arguments\": {}}]}\n");
		sb.append("Rules: use ONLY the tools listed below; ids go c1, c2, ...;\n");
		sb.append("arguments must match each tool's schema; plain chat needs no JSON.\n");
		sb.append("When native functions are supplied, call their exact API names; "
			+ "the server maps them back to the dotted runtime names below.\n");
		sb.append("You decide intents; the SERVER executes and verifies everything.\n");
		sb.append("If a required target or high-impact parameter is ambiguous, set ask to ONE "
			+ "focused question and emit no tool calls. The next player message resumes this plan.\n");
		sb.append("For compound goals keep plan short. Emit at most ONE world-changing or "
			+ "embodied action per response; wait for its observation before the next action. "
			+ "Independent read-only queries may be grouped.\n");
		sb.append("You are a high-level planner only. Never emit per-tick movement, ")
			.append("combat targeting, or block-by-block instructions; ")
			.append("the Java runtime owns those state machines.\n");
		sb.append("The companion CAN physically haul items and build from a blueprint, ")
			.append("but you never drive it: request blueprint.place / ")
			.append("blueprint.build and the runtime chooses the route, checks ")
			.append("the materials and orders the placements.\n");
		sb.append("For a whole outpost ask for project.start with a blueprint id: ")
			.append("the server decomposes it into materials, hand-over, excavation, ")
			.append("building, lighting and verification and drives them itself. ")
			.append("You never enumerate the steps.\n");
		sb.append("Gear requests go through items.fulfill in ONE call: it understands "
			+ "shape=armor_set (all four pieces), enchant=max ('满配附魔'), and "
			+ "target=agent (the companion wears it). Never split a set into four "
			+ "gives, and never try to express enchantments as NBT.\n");
		sb.append("Items the player ALREADY OWNS are changed with items.edit, never by "
			+ "conjuring a replacement: removing one named enchantment "
			+ "('取消荆棘'), clearing all of them, adding one, or repairing. "
			+ "Say what you will change and let the server report the result.\n");
		sb.append("Raw materials are still fulfilled with minecraft.command.give. ")
			.append("Never ask the companion to mine ore, chop trees, or farm in the ")
			.append("wild - excavation happens ONLY inside a blueprint's own footprint.\n");
		sb.append("Its other embodied behaviours stay narrow: following, guarding, ")
			.append("and owner rescue/healing.\n");
		sb.append("Read-only questions have their own tools - query.player for the "
			+ "PLAYER's health/hunger/held item, query.status and query.inventory for "
			+ "the companion's own, entity.scan_nearby for what is around, "
			+ "crafting.recipe_of for how something is made, and world.locate_structure "
			+ "/ world.locate_biome for where something is. Call them instead of "
			+ "guessing; never state a coordinate, a health value or a recipe that a "
			+ "tool did not return.\n");
		sb.append("When structured_observations are present, treat status/data/error as ")
			.append("authoritative tool results. Use query data in the answer; after a failure ")
			.append("choose a safe alternative and do not repeat an equivalent call.\n");
		sb.append("Never claim a task is done — report what you requested instead.\n");
		// 能力边界摘要（§23）。只有几行，作用是让他在玩家要求一件<b>还没解锁</b>的事
		// 时说得出「我要练到 Lv.5」，而不是编一个理由或者硬用一个不合适的工具。
		if (capabilitySummary != null && !capabilitySummary.isBlank()) {
			sb.append('\n').append(capabilitySummary.strip()).append('\n');
		}
		// 反绕过（§23.1）。通用命令工具（setblock / fill 提案）仍然保留，因为玩家的
		// 普通请求要用它们；但它们不许被拿来<b>模拟一个被等级锁住的能力</b>——
		// 否则职业闸只是让模型多绕一步，而不是真的挡住什么。
		sb.append("If a building capability is not in your tool list, it is locked for "
			+ "this squire. Say which level unlocks it. NEVER emulate it with "
			+ "setblock / fill / worldedit proposals: rotating, mirroring, adding "
			+ "storeys or modules by hand is the same locked capability, not a "
			+ "workaround.\n");
		if (tools.isEmpty()) {
			sb.append("\nAvailable tools: none right now; stay conversational.\n");
			return sb.toString();
		}
		sb.append("\nAvailable tools:\n");
		for (ToolDescriptor tool : tools) {
			sb.append("- ").append(tool.name()).append(": ")
				.append(oneLine(tool.description())).append('\n');
			if (includeArgumentSchemas) {
				for (ToolDescriptor.ParameterDescriptor p : tool.parameters()) {
					sb.append("  arg ").append(p.name()).append(" (").append(p.type())
						.append(p.required() ? ", required" : ", optional")
						.append("): ").append(oneLine(p.description())).append('\n');
				}
			}
		}
		return sb.toString();
	}

	/** 能力摘要拿不到时退回空串——少一段说明远好过因此发不出请求。 */
	private String summaryFor(UUID agentId) {
		try {
			String summary = capabilitySummary.apply(agentId);
			return summary == null ? "" : summary;
		} catch (RuntimeException e) {
			LOG.warn("[provider] capability summary unavailable: {}", e.toString());
			return "";
		}
	}

	private List<ToolDescriptor> currentTools(java.util.UUID agentId) {
		try {
			List<ToolDescriptor> tools = modelVisibleTools.apply(agentId);
			return tools == null ? List.of() : tools;
		} catch (RuntimeException e) {
			LOG.warn("[provider] tool catalog unavailable, sending without tools: {}",
				e.toString());
			return List.of();
		}
	}

	private static String oneLine(String text) {
		return text == null ? "" : text.replace('\n', ' ').trim();
	}

	// ------------------------------------------------------------------- parsing

	/**
	 * Extracts {@code choices[0].message.content}; anything else completes the
	 * chain exceptionally with a message that names the problem but never echoes
	 * credentials or oversized payloads.
	 */
	static String extractContent(HttpTransport.Response response) {
		return extractResponse(response, Map.of()).text();
	}

	private static String extractContent(HttpTransport.Response response,
			Map<String, String> nativeToInternal) {
		return extractResponse(response, nativeToInternal).text();
	}

	static AgentResponse extractResponse(HttpTransport.Response response) {
		return extractResponse(response, Map.of());
	}

	private static AgentResponse extractResponse(HttpTransport.Response response,
			Map<String, String> nativeToInternal) {
		if (response.status() < 200 || response.status() >= 300) {
			throw new ProviderHttpFailure("provider HTTP " + response.status()
				+ ": " + snippet(response.body()));
		}
		try {
			var root = JsonParser.parseString(response.body());
			JsonObject obj = root.getAsJsonObject();
			JsonArray choices = obj.getAsJsonArray("choices");
			if (choices == null || choices.size() == 0) {
				throw new ProviderHttpFailure("provider reply has no choices");
			}
			JsonObject choice = choices.get(0).getAsJsonObject();
			var message = choice.getAsJsonObject("message");
			if (message == null) {
				throw new ProviderHttpFailure("provider reply has no message");
			}
			// 原生 tool_calls 优先：把它折算成运行时本来就认识的那一种回合 JSON，
			// 于是 FunctionCallingParser 仍然是"回合长什么样"的唯一定义，
			// 两条传输方式不会各自漂出一套语义。
			String normalized = normalizeToolCalls(message, nativeToInternal);
			String content = normalized != null ? normalized : contentText(message);
			List<ToolCall> calls = normalized == null ? List.of()
				: dev.squire.server.cognition.FunctionCallingParser.parse(normalized).calls();
			String finishReason = primitiveString(choice, "finish_reason");
			Outcome outcome = switch (finishReason == null ? "" : finishReason) {
				case "length", "max_tokens" -> Outcome.TRUNCATED;
				case "content_filter", "safety" -> Outcome.CONTENT_FILTERED;
				default -> content.isBlank() && calls.isEmpty()
					? Outcome.EMPTY_OUTPUT : Outcome.COMPLETED;
			};
			Usage usage = usage(obj.getAsJsonObject("usage"));
			String requestId = primitiveString(obj, "request_id");
			if (requestId == null) requestId = primitiveString(obj, "id");
			return new AgentResponse(content, calls, finishReason, outcome, usage,
				requestId);
		} catch (ProviderHttpFailure e) {
			throw e;
		} catch (RuntimeException e) {
			throw new ProviderHttpFailure(
				"unparseable provider reply: " + snippet(response.body()));
		}
	}

	private static String contentText(JsonObject message) {
		if (!message.has("content") || message.get("content").isJsonNull()) return "";
		var content = message.get("content");
		if (content.isJsonPrimitive()) return content.getAsString();
		if (!content.isJsonArray()) return "";
		StringBuilder out = new StringBuilder();
		for (var part : content.getAsJsonArray()) {
			if (!part.isJsonObject()) continue;
			JsonObject object = part.getAsJsonObject();
			String text = primitiveString(object, "text");
			if (text != null) out.append(text);
		}
		return out.toString();
	}

	private static Usage usage(JsonObject object) {
		if (object == null) return Usage.unknown();
		return new Usage(longValue(object, "prompt_tokens", "input_tokens"),
			longValue(object, "completion_tokens", "output_tokens"),
			longValue(object, "total_tokens"));
	}

	private static long longValue(JsonObject object, String... names) {
		for (String name : names) {
			if (object.has(name) && object.get(name).isJsonPrimitive()) {
				try { return object.get(name).getAsLong(); }
				catch (RuntimeException ignored) { }
			}
		}
		return 0L;
	}

	private static String primitiveString(JsonObject object, String name) {
		return object != null && object.has(name) && object.get(name).isJsonPrimitive()
			? object.get(name).getAsString() : null;
	}

	/**
	 * 原生 {@code tool_calls} → 运行时的回合 JSON。
	 *
	 * @return 没有原生工具调用时返回 null（调用方退回读 content）
	 */
	static String normalizeToolCalls(JsonObject message) {
		return normalizeToolCalls(message, Map.of());
	}

	private static String normalizeToolCalls(JsonObject message,
			Map<String, String> nativeToInternal) {
		if (!message.has("tool_calls") || !message.get("tool_calls").isJsonArray()) {
			return null;
		}
		JsonArray native_ = message.getAsJsonArray("tool_calls");
		if (native_.size() == 0) {
			return null;
		}
		JsonArray calls = new JsonArray();
		for (int i = 0; i < native_.size(); i++) {
			if (!native_.get(i).isJsonObject()) {
				continue;
			}
			JsonObject entry = native_.get(i).getAsJsonObject();
			JsonObject function = entry.has("function")
					&& entry.get("function").isJsonObject()
				? entry.getAsJsonObject("function") : null;
			if (function == null || !function.has("name")) {
				continue;
			}
			JsonObject call = new JsonObject();
			call.addProperty("id", entry.has("id") && entry.get("id").isJsonPrimitive()
				? entry.get("id").getAsString() : "c" + (i + 1));
			String nativeName = function.get("name").getAsString();
			call.addProperty("name",
				nativeToInternal.getOrDefault(nativeName, nativeName));
			// arguments 按规范是一个 JSON <b>字符串</b>，里面才是对象。
			// 少数端点直接给对象，两种都收下。
			JsonObject arguments = new JsonObject();
			if (function.has("arguments") && !function.get("arguments").isJsonNull()) {
				var raw = function.get("arguments");
				try {
					var parsed = raw.isJsonObject() ? raw
						: JsonParser.parseString(raw.getAsString());
					if (parsed.isJsonObject()) {
						arguments = parsed.getAsJsonObject();
					}
				} catch (RuntimeException e) {
					// 参数不是合法 JSON：留空对象，让 ToolGateway 按缺参数报错，
					// 那条路径的错误信息比在这里瞎猜有用得多。
					LOG.warn("[provider] unparseable tool arguments for {}",
						function.get("name").getAsString());
				}
			}
			call.add("arguments", arguments);
			calls.add(call);
		}
		if (calls.size() == 0) {
			return null;
		}
		JsonObject turn = new JsonObject();
		turn.addProperty("say", message.has("content")
			&& message.get("content").isJsonPrimitive()
				? message.get("content").getAsString() : "");
		turn.add("tool_calls", calls);
		return turn.toString();
	}

	static final class ProviderHttpFailure extends RuntimeException {
		ProviderHttpFailure(String message) {
			super(message);
		}
	}

	private static String snippet(String body) {
		if (body == null) {
			return "(empty body)";
		}
		String flat = body.replace('\n', ' ').trim();
		return flat.length() <= 200 ? flat : flat.substring(0, 200) + "...";
	}
}
