package dev.squire.server.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * 玩家通知通道（方案 B2/E/G 共用）：在线立即聊天下发；离线写入待送达队列，
 * 上线时补投。队列持久化到 {@code squire/notifications.json}，重启不丢。
 */
public final class PlayerNotifier {
	private static final Logger LOG = LoggerFactory.getLogger(PlayerNotifier.class);
	private static final int MAX_QUEUED_PER_PLAYER = 64;

	private final MinecraftServer server;
	private final java.util.function.Supplier<Path> fileSupplier;
	private final Map<UUID, Deque<String>> pending = new HashMap<>();

	public PlayerNotifier(MinecraftServer server,
			java.util.function.Supplier<Path> fileSupplier) {
		this.server = server;
		this.fileSupplier = fileSupplier;
	}

	/** Deliver now when online, queue otherwise. Always safe to call on any thread? — server thread only. */
	public void send(UUID playerId, String message) {
		ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
		if (player != null) {
			player.sendMessage(Text.literal(message), false);
			return;
		}
		Deque<String> queue = pending.computeIfAbsent(playerId, k -> new ArrayDeque<>());
		while (queue.size() >= MAX_QUEUED_PER_PLAYER) {
			queue.pollFirst();
		}
		queue.addLast(message);
		save();
	}

	/** Called when a player joins: flush their queued messages (oldest first). */
	public void deliverOnJoin(ServerPlayerEntity player) {
		Deque<String> queue = pending.remove(player.getUuid());
		if (queue == null || queue.isEmpty()) {
			return;
		}
		player.sendMessage(Text.literal("[Squire] 离线期间的消息："), false);
		int delivered = 0;
		for (String message : queue) {
			player.sendMessage(Text.literal("[Squire] " + message), false);
			delivered++;
		}
		LOG.info("[notify] flushed {} queued message(s) to {}", delivered,
			player.getUuid());
		save();
	}

	public int queuedCount() {
		return pending.values().stream().mapToInt(Deque::size).sum();
	}

	// ------------------------------------------------------------------ persistence

	public synchronized void save() {
		try {
			Path file = fileSupplier.get();
			if (file == null) {
				return;
			}
			Files.createDirectories(file.getParent());
			JsonObject root = new JsonObject();
			JsonArray entries = new JsonArray();
			for (Map.Entry<UUID, Deque<String>> e : pending.entrySet()) {
				JsonObject entry = new JsonObject();
				entry.addProperty("player", e.getKey().toString());
				JsonArray msgs = new JsonArray();
				for (String m : e.getValue()) {
					msgs.add(m);
				}
				entry.add("messages", msgs);
				entries.add(entry);
			}
			root.add("queued", entries);
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, root.toString());
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
				StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception e) {
			LOG.warn("[notify] save failed: {}", e.toString());
		}
	}

	public synchronized int load() {
		pending.clear();
		try {
			Path file = fileSupplier.get();
			if (file == null || !Files.exists(file)) {
				return 0;
			}
			JsonObject root = JsonParser.parseString(Files.readString(file))
				.getAsJsonObject();
			int count = 0;
			for (JsonElement el : root.getAsJsonArray("queued")) {
				JsonObject entry = el.getAsJsonObject();
				UUID player = UUID.fromString(entry.get("player").getAsString());
				Deque<String> queue = pending.computeIfAbsent(player,
					k -> new ArrayDeque<>());
				for (JsonElement m : entry.getAsJsonArray("messages")) {
					queue.addLast(m.getAsString());
					count++;
				}
			}
			return count;
		} catch (Exception e) {
			LOG.warn("[notify] recovery failed (starting empty): {}", e.toString());
			return 0;
		}
	}

	/** Snapshot for tests/diagnostics. */
	public List<String> queuedFor(UUID playerId) {
		Deque<String> queue = pending.get(playerId);
		return queue == null ? List.of() : new ArrayList<>(queue);
	}
}
