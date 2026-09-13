package dev.squire.server.security;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 权限授予/收回的持久存储。
 *
 * <p>在此之前 {@link PermissionManager} 是纯内存对象：玩家在面板权限页勾掉的开关
 * 重启后静默丢回默认值——玩家以为设置过了，实际每次重启都回到原样，而且没有任何
 * 提示。这份存档让「玩家显式改过的权限」跨重启成立。</p>
 *
 * <p>只存<b>显式的</b>管理员 grant/revoke 和玩家自己的关闭偏好，不存默认值：
 * {@code DEFAULT_PLAYER_NODES} 仍然是代码里的常量，随版本演进，落盘反而会把旧
 * 默认值冻住。</p>
 */
public final class PermissionStore {

	private static final Logger LOG = LoggerFactory.getLogger(PermissionStore.class);
	private static final int VERSION = 2;

	private final Supplier<Path> fileSupplier;
	private volatile boolean writable = true;

	public PermissionStore(Supplier<Path> fileSupplier) {
		this.fileSupplier = fileSupplier;
	}

	/** 写入一份快照。空 map 也照写——「清空了所有显式权限」本身是要记住的事实。 */
	public void save(Map<UUID, Set<String>> granted, Map<UUID, Set<String>> revoked,
			Map<UUID, Set<String>> playerDisabled) {
		if (!writable) {
			return;
		}
		try {
			Path file = fileSupplier.get();
			if (file == null) {
				return;
			}
			Files.createDirectories(file.getParent());
			JsonObject root = new JsonObject();
			root.addProperty("version", VERSION);
			root.add("granted", writeMap(granted));
			root.add("revoked", writeMap(revoked));
			root.add("playerDisabled", writeMap(playerDisabled));
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, root.toString());
			try {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE);
			} catch (java.nio.file.AtomicMoveNotSupportedException e) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (Exception e) {
			LOG.warn("[squire-permissions] save failed: {}", e.toString());
		}
	}

	/**
	 * 读回快照并交给 {@code receiver}。文件不存在、坏 JSON、未来版本都按「没有显式
	 * 权限」处理，绝不抛——权限系统宁可回到默认值，也不能因为一份坏文件拒绝启动。
	 *
	 * @return 恢复的玩家条目数（granted + revoked 的 key 并集）
	 */
	public int load(LoadedPermissions receiver) {
		try {
			Path file = fileSupplier.get();
			if (file == null || !Files.exists(file)) {
				return 0;
			}
			JsonObject root = JsonParser.parseString(Files.readString(file))
				.getAsJsonObject();
			int version = root.has("version") ? root.get("version").getAsInt() : 1;
			if (version > VERSION) {
				writable = false;
				LOG.error("[squire-permissions] future schema {}; stored grants "
					+ "IGNORED, falling back to defaults", root.get("version").getAsInt());
				return 0;
			}
			Map<UUID, Set<String>> granted = readMap(root, "granted");
			Map<UUID, Set<String>> revoked = readMap(root, "revoked");
			Map<UUID, Set<String>> playerDisabled = readMap(root, "playerDisabled");
			// v1 grants came from an unauthenticated player toggle as well as trusted
			// server code. They cannot safely be migrated as admin overrides.
			if (version < 2) {
				if (!granted.isEmpty()) LOG.warn("[squire-permissions] discarded {} legacy grant row(s): v1 grants were not admin-authorized", granted.size());
				granted.clear();
			}
			receiver.accept(granted, revoked, playerDisabled);
			Set<UUID> players = new java.util.HashSet<>(granted.keySet());
		players.addAll(revoked.keySet());
		players.addAll(playerDisabled.keySet());
			return players.size();
		} catch (Exception e) {
			LOG.warn("[squire-permissions] load failed: {}", e.toString());
			return 0;
		}
	}

	@FunctionalInterface
	public interface LoadedPermissions {
		void accept(Map<UUID, Set<String>> granted, Map<UUID, Set<String>> revoked,
				Map<UUID, Set<String>> playerDisabled);
	}

	private static JsonObject writeMap(Map<UUID, Set<String>> map) {
		JsonObject json = new JsonObject();
		for (Map.Entry<UUID, Set<String>> entry : map.entrySet()) {
			if (entry.getValue().isEmpty()) {
				continue;
			}
			json.add(entry.getKey().toString(), toJson(entry.getValue()));
		}
		return json;
	}

	private static com.google.gson.JsonArray toJson(Set<String> values) {
		com.google.gson.JsonArray array = new com.google.gson.JsonArray();
		for (String value : values) {
			array.add(value);
		}
		return array;
	}

	private static Map<UUID, Set<String>> readMap(JsonObject root, String key) {
		Map<UUID, Set<String>> out = new HashMap<>();
		if (!root.has(key) || !root.get(key).isJsonObject()) {
			return out;
		}
		for (Map.Entry<String, com.google.gson.JsonElement> entry : root
				.getAsJsonObject(key).entrySet()) {
			try {
				UUID playerId = UUID.fromString(entry.getKey());
				java.util.Set<String> nodes = new java.util.HashSet<>();
				for (com.google.gson.JsonElement node : entry.getValue()
						.getAsJsonArray()) {
					nodes.add(node.getAsString());
				}
				out.put(playerId, nodes);
			} catch (RuntimeException bad) {
				LOG.warn("[squire-permissions] skipped corrupt row in {}: {}", key,
					bad.toString());
			}
		}
		return out;
	}
}
