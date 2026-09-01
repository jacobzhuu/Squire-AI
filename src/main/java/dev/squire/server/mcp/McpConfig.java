package dev.squire.server.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code config/squire/mcp.json} 加载器（方案 I2）：服主不写一行 Java 就能部署和
 * 重载 MCP 服务器。
 *
 * <p>安全边界很明确：<b>服务器地址和启动命令只能来自这个文件</b>，绝不接受聊天里
 * 说出来的地址；信任等级也不在这里给，只写一个 {@code trustKey} 指向已有的
 * {@link McpTrustStore}，配置文件本身无法把一台服务器提权。</p>
 *
 * <p>加载是 fail-soft 的：一条坏配置被跳过并记录，不影响其它服务器，也绝不阻止
 * 服务器启动（方案 13.3）。</p>
 */
public final class McpConfig {

	private static final Logger LOG = LoggerFactory.getLogger(McpConfig.class);

	public static final int SCHEMA_VERSION = 1;
	/** 一次最多连这么多台服务器。 */
	public static final int MAX_SERVERS = 16;
	public static final int DEFAULT_TIMEOUT_SECONDS = 10;
	public static final int MAX_TIMEOUT_SECONDS = 120;

	public enum TransportKind { STDIO, HTTP }

	/**
	 * 一台服务器的配置。
	 *
	 * @param allowlist 允许导入的工具名；空集合表示"全部导入"，仍受信任等级约束
	 * @param trustKey  {@link McpTrustStore} 里的键；配置本身不授予信任
	 */
	public record ServerEntry(String name, TransportKind transport, String endpoint,
			List<String> command, int timeoutSeconds, Set<String> allowlist,
			String trustKey, boolean enabled) {

		public ServerEntry {
			command = command == null ? List.of() : List.copyOf(command);
			allowlist = allowlist == null ? Set.of() : Set.copyOf(allowlist);
			timeoutSeconds = Math.max(1, Math.min(MAX_TIMEOUT_SECONDS, timeoutSeconds));
		}

		/** True when this tool may be imported from this server. */
		public boolean allows(String toolName) {
			return allowlist.isEmpty() || allowlist.contains(toolName);
		}

		public String describe() {
			return name + " [" + transport + "] "
				+ (transport == TransportKind.STDIO ? String.join(" ", command) : endpoint)
				+ " timeout=" + timeoutSeconds + "s"
				+ " allowlist=" + (allowlist.isEmpty() ? "*" : allowlist)
				+ " trustKey=" + (trustKey == null ? name : trustKey)
				+ (enabled ? "" : " (disabled)");
		}
	}

	/** 加载结果：可用的条目 + 被跳过的原因（服主需要看到后者）。 */
	public record LoadResult(List<ServerEntry> servers, List<String> problems) {

		public boolean hasProblems() {
			return !problems.isEmpty();
		}
	}

	private McpConfig() {
	}

	/** Parse the config file. A missing file is not an error — it means "no servers". */
	public static LoadResult load(Path file) {
		List<ServerEntry> servers = new ArrayList<>();
		List<String> problems = new ArrayList<>();
		if (file == null || !Files.exists(file)) {
			return new LoadResult(List.of(), List.of());
		}
		try {
			JsonElement parsed = JsonParser.parseString(Files.readString(file));
			if (!parsed.isJsonObject()) {
				return new LoadResult(List.of(),
					List.of("mcp.json must contain a JSON object"));
			}
			JsonObject root = parsed.getAsJsonObject();
			int version = root.has("version") ? root.get("version").getAsInt() : 1;
			if (version > SCHEMA_VERSION) {
				return new LoadResult(List.of(), List.of("mcp.json schemaVersion "
					+ version + " is newer than supported " + SCHEMA_VERSION
					+ "; no servers loaded"));
			}
			JsonArray array = root.getAsJsonArray("servers");
			if (array == null) {
				return new LoadResult(List.of(), List.of());
			}
			Set<String> names = new LinkedHashSet<>();
			for (JsonElement element : array) {
				if (servers.size() >= MAX_SERVERS) {
					problems.add("more than " + MAX_SERVERS
						+ " servers configured; the rest were ignored");
					break;
				}
				try {
					ServerEntry entry = readServer(element.getAsJsonObject());
					if (!names.add(entry.name())) {
						problems.add("duplicate server name '" + entry.name() + "'");
						continue;
					}
					servers.add(entry);
				} catch (RuntimeException bad) {
					problems.add("skipped a server entry: " + bad.getMessage());
				}
			}
		} catch (Exception e) {
			// 配置坏了只让这个功能降级，绝不阻止服务器启动（方案 13.3）
			problems.add("mcp.json could not be read: " + e);
			LOG.warn("[mcp] config load failed: {}", e.toString());
		}
		return new LoadResult(List.copyOf(servers), List.copyOf(problems));
	}

	private static ServerEntry readServer(JsonObject json) {
		String name = McpToolImporter.sanitizeServerName(
			json.has("name") ? json.get("name").getAsString() : "");
		if (name.isEmpty()) {
			throw new IllegalArgumentException("server needs a usable 'name'");
		}
		TransportKind kind;
		try {
			kind = TransportKind.valueOf(json.get("transport").getAsString()
				.trim().toUpperCase(Locale.ROOT));
		} catch (RuntimeException e) {
			throw new IllegalArgumentException(
				"server '" + name + "' needs transport STDIO or HTTP");
		}
		String endpoint = json.has("url") ? json.get("url").getAsString() : null;
		List<String> command = new ArrayList<>();
		if (json.has("command")) {
			for (JsonElement part : json.getAsJsonArray("command")) {
				command.add(part.getAsString());
			}
		}
		if (kind == TransportKind.HTTP && (endpoint == null || endpoint.isBlank())) {
			throw new IllegalArgumentException("HTTP server '" + name + "' needs a 'url'");
		}
		if (kind == TransportKind.STDIO && command.isEmpty()) {
			throw new IllegalArgumentException(
				"STDIO server '" + name + "' needs a 'command' array");
		}
		Set<String> allowlist = new LinkedHashSet<>();
		if (json.has("allowlist")) {
			for (JsonElement tool : json.getAsJsonArray("allowlist")) {
				allowlist.add(tool.getAsString());
			}
		}
		return new ServerEntry(name, kind, endpoint, command,
			json.has("timeoutSeconds") ? json.get("timeoutSeconds").getAsInt()
				: DEFAULT_TIMEOUT_SECONDS,
			allowlist,
			json.has("trustKey") ? json.get("trustKey").getAsString() : name,
			!json.has("enabled") || json.get("enabled").getAsBoolean());
	}

	/** Build the transport an entry describes. Never connects here. */
	public static McpTransport transportFor(ServerEntry entry) {
		return entry.transport() == TransportKind.HTTP
			? new HttpMcpTransport(entry.endpoint())
			: new StdioMcpTransport(entry.command());
	}

	/** A commented example operators can copy; written only when no file exists. */
	public static String exampleJson() {
		return """
			{
			  "version": 1,
			  "servers": [
			    {
			      "name": "example-http",
			      "transport": "HTTP",
			      "url": "http://127.0.0.1:8080/mcp",
			      "timeoutSeconds": 10,
			      "allowlist": [],
			      "trustKey": "example-http",
			      "enabled": false
			    },
			    {
			      "name": "example-stdio",
			      "transport": "STDIO",
			      "command": ["node", "my-mcp-server.js"],
			      "timeoutSeconds": 10,
			      "allowlist": ["machine_status"],
			      "trustKey": "example-stdio",
			      "enabled": false
			    }
			  ]
			}
			""";
	}
}
