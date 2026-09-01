package dev.squire.server.provider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Server-side LLM connection settings (spec section 23), loaded ONCE from
 * {@code config/squire/llm.json} by the server admin. Players never supply
 * credentials; the key lives in server-owner data only.
 *
 * <p>{@code "apiKey"} accepts either a literal secret or an environment
 * reference of the form {@code "${ENV_NAME}"} so a raw key need not be written
 * to disk. Loading is fail-closed: absent file, corrupt JSON, or any missing /
 * blank required field means NO provider (the mod stays fully usable without
 * one) — never a half-configured client.</p>
 *
 * <p>{@link #toString} masks the key: configs flow through logs and must never
 * leak the secret.</p>
 */
public record LlmConfig(String baseUrl, String apiKey, String model,
		double temperature, int maxTokens, long timeoutMs, long maxContextTokens,
		boolean nativeToolCalls, int toolCandidateLimit) {

	/** 旧签名：默认开启原生 function calling。 */
	public LlmConfig(String baseUrl, String apiKey, String model, double temperature,
			int maxTokens, long timeoutMs, long maxContextTokens) {
		this(baseUrl, apiKey, model, temperature, maxTokens, timeoutMs,
			maxContextTokens, true, ToolRelevance.DEFAULT_CANDIDATE_LIMIT);
	}

	public LlmConfig(String baseUrl, String apiKey, String model, double temperature,
			int maxTokens, long timeoutMs, long maxContextTokens,
			boolean nativeToolCalls) {
		this(baseUrl, apiKey, model, temperature, maxTokens, timeoutMs,
			maxContextTokens, nativeToolCalls, ToolRelevance.DEFAULT_CANDIDATE_LIMIT);
	}

	private static final org.slf4j.Logger LOG =
		org.slf4j.LoggerFactory.getLogger(LlmConfig.class);

	/** Only this adapter type exists today; the field is validated for forward compat. */
	public static final String TYPE = "openai-compatible";

	private static final Pattern ENV_REF = Pattern.compile("^\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}$");

	/** 最近一次加载失败的原因；null 表示没有失败过。仅供诊断展示，绝不含密钥。 */
	private static volatile String lastLoadError = null;

	public static Optional<String> lastLoadError() {
		return Optional.ofNullable(lastLoadError);
	}

	/** 记录并返回"没有配置"——以前所有失败都退化成同一个空 Optional，无从排查。 */
	private static Optional<LlmConfig> reject(String reason) {
		lastLoadError = reason;
		LOG.warn("[Squire] llm.json 不可用：{}", reason);
		return Optional.empty();
	}

	public static Optional<LlmConfig> load(Path file) {
		if (!Files.isRegularFile(file)) {
			lastLoadError = null; // 没放配置文件是正常状态，不是错误
			return Optional.empty();
		}
		try {
			String raw = Files.readString(file);
			JsonElementHolder holder = parse(raw);
			if (holder == null) {
				return reject("不是合法的 JSON 对象");
			}
			if (!holder.obj.has("type")
					|| !TYPE.equals(holder.obj.get("type").getAsString())) {
				return reject("\"type\" 必须是 \"" + TYPE + "\"");
			}
			return fromJsonObject(holder.obj);
		} catch (RuntimeException | java.io.IOException e) {
			return reject(e.getClass().getSimpleName()
				+ (e.getMessage() == null ? "" : "：" + e.getMessage()));
		}
	}

	/** Package-private parser reused by the multi-provider settings loader. */
	static Optional<LlmConfig> fromJsonObject(JsonObject object) {
		try {
			JsonElementHolder holder = new JsonElementHolder(object);
			if (!holder.obj.has("type")
					|| !TYPE.equals(holder.obj.get("type").getAsString())) {
				return reject("\"type\" 必须是 \"" + TYPE + "\"");
			}
			String baseUrl = stringField(holder.obj, "baseUrl");
			String apiKey = expand(stringField(holder.obj, "apiKey"));
			String model = stringField(holder.obj, "model");
			if (baseUrl == null || apiKey == null || model == null) {
				return reject("baseUrl / apiKey / model 三项必填，缺少或为空"
					+ (apiKey == null
						? "（apiKey 用 ${ENV} 形式时，该环境变量必须存在）" : ""));
			}
			baseUrl = stripTrailingSlash(baseUrl);
			if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
				return reject("baseUrl 必须以 http:// 或 https:// 开头");
			}
			double temperature = holder.obj.has("temperature")
					&& holder.obj.get("temperature").isJsonPrimitive()
							? holder.obj.get("temperature").getAsDouble() : 0.7d;
			// 512 太小：复杂请求的 JSON 会被截断成空/非法输出，正好掉进"静默完成"那条路。
			int maxTokens = intField(holder.obj, "maxTokens", 2048);
			long timeoutMs = intField(holder.obj, "timeoutMs", 20_000);
			long maxContextTokens = intField(holder.obj, "maxContextTokens", 16_384);
			// 默认开：DeepSeek / OpenAI 都支持，格式由服务端强制，省掉一整类
			// 「模型输出不是合法 JSON → 重规划」的往返。端点不支持时设成 false，
			// 提示词里的文本契约仍然在，两条路解析出来的是同一个结构。
			boolean nativeToolCalls = !holder.obj.has("nativeToolCalls")
				|| !holder.obj.get("nativeToolCalls").isJsonPrimitive()
				|| holder.obj.get("nativeToolCalls").getAsBoolean();
			int toolCandidateLimit = intField(holder.obj, "toolCandidateLimit",
				ToolRelevance.DEFAULT_CANDIDATE_LIMIT);
			if (temperature < 0d || temperature > 2d || maxTokens <= 0
					|| timeoutMs <= 0 || maxContextTokens <= 0
					|| toolCandidateLimit < 4 || toolCandidateLimit > 64) {
				return reject("temperature 需在 0..2，maxTokens/timeoutMs/maxContextTokens 需为正数，toolCandidateLimit 需在 4..64");
			}
			lastLoadError = null;
			return Optional.of(new LlmConfig(baseUrl, apiKey, model, temperature,
				maxTokens, timeoutMs, maxContextTokens, nativeToolCalls,
				toolCandidateLimit));
		} catch (RuntimeException e) {
			return reject(e.getClass().getSimpleName()
				+ (e.getMessage() == null ? "" : "：" + e.getMessage()));
		}
	}

	/** Never throws: garbage is "no config", matching persistence resilience rules. */
	private static JsonElementHolder parse(String raw) {
		try {
			var element = JsonParser.parseString(raw);
			if (element.isJsonObject()) {
				return new JsonElementHolder(element.getAsJsonObject());
			}
			return null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static final class JsonElementHolder {
		final JsonObject obj;

		JsonElementHolder(JsonObject obj) {
			this.obj = obj;
		}
	}

	private static String stringField(JsonObject obj, String name) {
		if (obj.has(name) && obj.get(name).isJsonPrimitive()) {
			String value = obj.get(name).getAsString();
			if (value != null && !value.isBlank()) {
				return value.trim();
			}
		}
		return null;
	}

	private static int intField(JsonObject obj, String name, int fallback) {
		if (obj.has(name) && obj.get(name).isJsonPrimitive()) {
			try {
				return obj.get(name).getAsInt();
			} catch (RuntimeException e) {
				return fallback;
			}
		}
		return fallback;
	}

	/**
	 * Resolves {@code "${ENV_NAME}"} against the server process environment.
	 * An unset or blank referenced variable fails configuration closed.
	 */
	static String expand(String value) {
		if (value == null) {
			return null;
		}
		Matcher matcher = ENV_REF.matcher(value);
		if (!matcher.matches()) {
			return value;
		}
		String resolved = System.getenv(matcher.group(1));
		return resolved == null || resolved.isBlank() ? null : resolved.trim();
	}

	private static String stripTrailingSlash(String url) {
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

	/** Endpoint derived from the base URL ({@code .../v1} style bases). */
	public String chatCompletionsUrl() {
		return baseUrl + "/chat/completions";
	}

	public String modelsUrl() {
		return baseUrl + "/models";
	}

	@Override
	public String toString() {
		return "LlmConfig[baseUrl=" + baseUrl + ", model=" + model
			+ ", apiKey=" + mask(apiKey) + ", temperature=" + temperature
			+ ", maxTokens=" + maxTokens + "]";
	}

	private static String mask(String secret) {
		if (secret == null || secret.length() < 8) {
			return "***";
		}
		return secret.substring(0, 3) + "***" + secret.substring(secret.length() - 2);
	}
}
