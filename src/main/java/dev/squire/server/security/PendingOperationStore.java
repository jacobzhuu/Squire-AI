package dev.squire.server.security;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * 高风险待确认操作的持久存储（方案 F3）。
 *
 * <p>PendingOperation 必须跨重启存活：玩家看到 preview 之后去吃个饭、服务器重启了，
 * 回来 {@code /squire confirm <id>} 仍然应该执行的是他当时看过的那件事。</p>
 */
public final class PendingOperationStore {

	private static final org.slf4j.Logger LOG =
		org.slf4j.LoggerFactory.getLogger(PendingOperationStore.class);
	private static final int VERSION = 1;
	/** 每位玩家同时最多这么多条待确认操作，防止刷屏。 */
	public static final int MAX_PENDING_PER_OWNER = 8;

	private final Map<UUID, PendingOperation> operations = new ConcurrentHashMap<>();
	private final Supplier<Path> fileSupplier;
	private volatile boolean writable = true;

	public PendingOperationStore(Supplier<Path> fileSupplier) {
		this.fileSupplier = fileSupplier;
	}

	// ------------------------------------------------------------------ access

	public PendingOperation put(PendingOperation operation) {
		operations.put(operation.confirmId(), operation);
		pruneOwner(operation.ownerId());
		save();
		return operation;
	}

	public Optional<PendingOperation> get(UUID confirmId) {
		return Optional.ofNullable(operations.get(confirmId));
	}

	/** Still-actionable operations for one owner, oldest first. */
	public List<PendingOperation> pendingFor(UUID ownerId, long nowTick) {
		return operations.values().stream()
			.filter(o -> o.ownerId().equals(ownerId) && o.isPending(nowTick))
			.sorted(Comparator.comparingLong(PendingOperation::issuedAtTick))
			.toList();
	}

	public void update(PendingOperation operation) {
		operations.put(operation.confirmId(), operation);
		save();
	}

	public void remove(UUID confirmId) {
		if (operations.remove(confirmId) != null) {
			save();
		}
	}

	/** Mark everything past its deadline EXPIRED; terminal rows are dropped. */
	public int expireAllBefore(long nowTick) {
		int expired = 0;
		boolean dirty = false;
		for (PendingOperation operation : List.copyOf(operations.values())) {
			if (operation.status() == PendingOperation.Status.PENDING
					&& operation.expiresAtTick() <= nowTick) {
				operations.put(operation.confirmId(),
					operation.withStatus(PendingOperation.Status.EXPIRED,
						"not confirmed in time"));
				expired++;
				dirty = true;
			} else if (operation.status() != PendingOperation.Status.PENDING
					&& operation.expiresAtTick() + 6000L <= nowTick) {
				operations.remove(operation.confirmId()); // keep a short audit tail
				dirty = true;
			}
		}
		if (dirty) {
			save();
		}
		return expired;
	}

	private void pruneOwner(UUID ownerId) {
		List<PendingOperation> mine = operations.values().stream()
			.filter(o -> o.ownerId().equals(ownerId))
			.sorted(Comparator.comparingLong(PendingOperation::issuedAtTick).reversed())
			.toList();
		for (int i = MAX_PENDING_PER_OWNER; i < mine.size(); i++) {
			operations.remove(mine.get(i).confirmId());
		}
	}

	// ------------------------------------------------------------------ persistence

	public synchronized void save() {
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
			JsonArray array = new JsonArray();
			for (PendingOperation operation : operations.values()) {
				array.add(write(operation));
			}
			root.add("operations", array);
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, root.toString());
			try {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE);
			} catch (java.nio.file.AtomicMoveNotSupportedException e) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (Exception e) {
			LOG.warn("[confirm] save failed: {}", e.toString());
		}
	}

	public synchronized int load() {
		operations.clear();
		try {
			Path file = fileSupplier.get();
			if (file == null || !Files.exists(file)) {
				return 0;
			}
			JsonObject root = JsonParser.parseString(Files.readString(file))
				.getAsJsonObject();
			if (root.has("version") && root.get("version").getAsInt() > VERSION) {
				writable = false;
				LOG.error("[confirm] future schema {}; pending operations READ-ONLY",
					root.get("version").getAsInt());
				return 0;
			}
			JsonArray array = root.getAsJsonArray("operations");
			if (array == null) {
				return 0;
			}
			for (JsonElement element : array) {
				try {
					PendingOperation operation = read(element.getAsJsonObject());
					operations.put(operation.confirmId(), operation);
				} catch (RuntimeException bad) {
					LOG.warn("[confirm] skipped corrupt row: {}", bad.toString());
				}
			}
		} catch (Exception e) {
			LOG.warn("[confirm] load failed: {}", e.toString());
		}
		return operations.size();
	}

	private static JsonObject write(PendingOperation o) {
		JsonObject json = new JsonObject();
		json.addProperty("confirmId", o.confirmId().toString());
		json.addProperty("ownerId", o.ownerId().toString());
		if (o.agentId() != null) {
			json.addProperty("agentId", o.agentId().toString());
		}
		json.addProperty("operationType", o.operationType());
		json.addProperty("fingerprint", o.fingerprint());
		json.addProperty("preview", o.preview());
		json.addProperty("issuedAtTick", o.issuedAtTick());
		json.addProperty("expiresAtTick", o.expiresAtTick());
		json.addProperty("status", o.status().name());
		if (o.lastError() != null) {
			json.addProperty("lastError", o.lastError());
		}
		JsonObject args = new JsonObject();
		o.canonicalArguments().forEach((key, value) -> {
			if (value instanceof Number number) {
				args.add(key, new JsonPrimitive(number));
			} else if (value instanceof Boolean bool) {
				args.add(key, new JsonPrimitive(bool));
			} else {
				args.add(key, new JsonPrimitive(String.valueOf(value)));
			}
		});
		json.add("canonicalArguments", args);
		return json;
	}

	private static PendingOperation read(JsonObject json) {
		Map<String, Object> args = new LinkedHashMap<>();
		JsonObject stored = json.getAsJsonObject("canonicalArguments");
		if (stored != null) {
			for (String key : stored.keySet()) {
				JsonElement value = stored.get(key);
				if (!value.isJsonPrimitive()) {
					continue;
				}
				JsonPrimitive primitive = value.getAsJsonPrimitive();
				if (primitive.isNumber()) {
					// 坐标/数量在往返后必须仍然是数字：Tool 的参数校验按类型判定
					double number = primitive.getAsDouble();
					args.put(key, number == Math.rint(number)
						? (Object) Integer.valueOf((int) number)
						: (Object) Double.valueOf(number));
				} else if (primitive.isBoolean()) {
					args.put(key, primitive.getAsBoolean());
				} else {
					args.put(key, primitive.getAsString());
				}
			}
		}
		return new PendingOperation(
			UUID.fromString(json.get("confirmId").getAsString()),
			UUID.fromString(json.get("ownerId").getAsString()),
			json.has("agentId") ? UUID.fromString(json.get("agentId").getAsString()) : null,
			json.get("operationType").getAsString(), args,
			json.get("fingerprint").getAsString(),
			json.get("preview").getAsString(),
			json.get("issuedAtTick").getAsLong(),
			json.get("expiresAtTick").getAsLong(),
			PendingOperation.Status.valueOf(json.get("status").getAsString()),
			json.has("lastError") ? json.get("lastError").getAsString() : null);
	}

	/** Diagnostics/tests. */
	public List<PendingOperation> all() {
		return new ArrayList<>(operations.values());
	}
}
