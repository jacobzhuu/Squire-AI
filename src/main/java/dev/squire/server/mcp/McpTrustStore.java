package dev.squire.server.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import dev.squire.server.security.ToolTrust;

/**
 * Persisted per-server trust assignments (spec section 52). The file is the ONLY
 * way a remote server gains trust: anything absent from it is {@link ToolTrust#UNTRUSTED},
 * and trust annotations inside MCP payloads can NEVER raise it ("不允许将 MCP 当作
 * 权限系统").
 *
 * <p>Stored as plain JSON at {@code config/squire/mcp-trust.json}:
 * {@code {"safe-server": "ADMIN_APPROVED_LOCAL", "evil": "BLOCKED"}}.</p>
 */
public final class McpTrustStore {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private final Path file;
	private final Map<String, String> trusts = new LinkedHashMap<>();

	public McpTrustStore(Path file) {
		this.file = file;
		load();
	}

	private void load() {
		if (!Files.exists(file)) {
			return;
		}
		try {
			Map<?, ?> raw = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
				Map.class);
			if (raw != null) {
				for (Map.Entry<?, ?> e : raw.entrySet()) {
					trusts.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
				}
			}
		} catch (Exception e) {
			// corrupt file must never take the server down; treat as empty (fail-closed)
		}
	}

	/** Trust for one server; unknown servers are UNTRUSTED, never inferred otherwise. */
	public synchronized ToolTrust trustOf(String serverName) {
		String raw = trusts.get(serverName);
		if (raw == null) {
			return ToolTrust.UNTRUSTED;
		}
		try {
			return ToolTrust.valueOf(raw);
		} catch (IllegalArgumentException e) {
			return ToolTrust.UNTRUSTED; // garbage in the file cannot grant trust
		}
	}

	/** Admin action: set + persist. @return false when the value was rejected. */
	public synchronized boolean setTrust(String serverName, ToolTrust trust) {
		if (serverName == null || serverName.isBlank() || trust == null) {
			return false;
		}
		trusts.put(serverName, trust.name());
		return save();
	}

	public synchronized Map<String, ToolTrust> snapshot() {
		Map<String, ToolTrust> out = new LinkedHashMap<>();
		trusts.forEach((k, v) -> out.put(k, trustOf(k)));
		return out;
	}

	private boolean save() {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(trusts), StandardCharsets.UTF_8);
			return true;
		} catch (IOException e) {
			return false;
		}
	}
}
