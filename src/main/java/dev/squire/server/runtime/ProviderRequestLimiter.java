package dev.squire.server.runtime;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Tick-window budget for accepted provider calls, including retries and replans. */
final class ProviderRequestLimiter {
	static final long WINDOW_TICKS = 1200L;
	static final int MAX_PER_PLAYER = 10;
	static final int MAX_PER_SERVER = 40;

	private final Map<UUID, ArrayDeque<Long>> byPlayer = new HashMap<>();
	private final ArrayDeque<Long> server = new ArrayDeque<>();

	boolean tryAcquire(UUID playerId, long tick) {
		expire(server, tick);
		ArrayDeque<Long> player = byPlayer.computeIfAbsent(playerId,
			ignored -> new ArrayDeque<>());
		expire(player, tick);
		if (player.isEmpty()) byPlayer.remove(playerId);
		if (server.size() >= MAX_PER_SERVER) return false;
		player = byPlayer.computeIfAbsent(playerId, ignored -> new ArrayDeque<>());
		if (player.size() >= MAX_PER_PLAYER) return false;
		player.addLast(tick);
		server.addLast(tick);
		return true;
	}

	private static void expire(ArrayDeque<Long> ticks, long now) {
		while (!ticks.isEmpty() && now - ticks.peekFirst() >= WINDOW_TICKS) {
			ticks.removeFirst();
		}
	}
}
