package dev.squire.server.security;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Resolves which permission nodes a player holds (spec section 61).
 *
 * <p>Admins (permission level ≥ 2) hold every node. Ordinary players start with
 * {@link PermissionNodes#DEFAULT_PLAYER_NODES}; server admins may grant or revoke
 * individual nodes at runtime. Revocation always wins over defaults.</p>
 */
public final class PermissionManager {

	private final Map<UUID, Set<String>> granted = new ConcurrentHashMap<>();
	private final Map<UUID, Set<String>> revoked = new ConcurrentHashMap<>();
	/** Player preferences can disable their own access but never enlarge the server policy. */
	private final Map<UUID, Set<String>> playerDisabled = new ConcurrentHashMap<>();
	/** 可选的持久层；测试和无存档场景下为 null，改动就不落盘。 */
	private volatile PermissionStore store;

	/** Attach the persistence layer. Call once at runtime construction. */
	public void attachStore(PermissionStore store) {
		this.store = store;
	}

	/**
	 * Restore server overrides and player toggles from disk. Default grants stay in
	 * code ({@link PermissionNodes#DEFAULT_PLAYER_NODES}).
	 *
	 * @return number of player entries recovered
	 */
	public int loadFromDisk() {
		if (store == null) {
			return 0;
		}
		return store.load((restoredGranted, restoredRevoked, restoredDisabled) -> {
			granted.putAll(restoredGranted);
			revoked.putAll(restoredRevoked);
			playerDisabled.putAll(restoredDisabled);
		});
	}

	private void persist() {
		if (store != null) {
			store.save(Map.copyOf(granted), Map.copyOf(revoked), Map.copyOf(playerDisabled));
		}
	}

	/** @return true when the player holds {@code node}. */
	public boolean has(ServerPlayerEntity player, String node) {
		return player != null && has(player.getUuid(), player.hasPermissionLevel(2), node);
	}

	/**
	 * Pure overload for tests and non-player callers.
	 *
	 * <p><b>显式收回永远赢，管理员也不例外。</b>以前 {@code isAdmin} 在最前面直接
	 * 返回 true，于是单人存档（玩家永远是 4 级权限）里面板的权限页<b>完全失效</b>：
	 * 点一下确实记下了一条 revoke，但 {@code has} 根本走不到那一行，勾永远是勾。
	 * 玩家看到的就是一排点了没反应的开关。</p>
	 *
	 * <p>现在的语义：管理员默认拥有全部节点，但他<b>亲手关掉</b>的那一个除外——
	 * 这和 ADR-045 里写的「细粒度节点覆盖优先于档位」是同一条规则。</p>
	 */
	public boolean has(UUID playerId, boolean isAdmin, String node) {
		if (!PermissionNodes.isKnown(node)) {
			return false;
		}
		if (revoked.getOrDefault(playerId, Set.of()).contains(node)) {
			return false; // Server policy denies this node, even for operators.
		}
		if (playerDisabled.getOrDefault(playerId, Set.of()).contains(node)) return false;
		if (isAdmin) {
			return true; // admins hold every node they have not switched off
		}
		return granted.getOrDefault(playerId, Set.of()).contains(node)
			|| PermissionNodes.DEFAULT_PLAYER_NODES.contains(node);
	}

	public void grant(UUID playerId, String node) {
		if (!PermissionNodes.isKnown(node)) {
			throw new IllegalArgumentException("unknown node: " + node);
		}
		granted.merge(playerId, Set.of(node), (a, b) -> {
			var merged = new java.util.HashSet<>(a);
			merged.addAll(b);
			return Set.copyOf(merged);
		});
		revoked.computeIfPresent(playerId, (k, set) ->
			set.stream().filter(n -> !n.equals(node)).collect(java.util.stream.Collectors.toSet()));
		persist();
	}

	public void revoke(UUID playerId, String node) {
		if (!PermissionNodes.isKnown(node)) {
			throw new IllegalArgumentException("unknown node: " + node);
		}
		revoked.merge(playerId, Set.of(node), (a, b) -> {
			var merged = new java.util.HashSet<>(a);
			merged.addAll(b);
			return Set.copyOf(merged);
		});
		granted.computeIfPresent(playerId, (k, set) ->
			set.stream().filter(n -> !n.equals(node)).collect(java.util.stream.Collectors.toSet()));
		persist();
	}

	/** Admin command: clear only the server allow/deny override for one player and node. */
	public void resetPolicy(UUID playerId, String node) {
		if (!PermissionNodes.isKnown(node)) throw new IllegalArgumentException("unknown node: " + node);
		granted.computeIfPresent(playerId, (k, set) -> without(set, node));
		revoked.computeIfPresent(playerId, (k, set) -> without(set, node));
		persist();
	}

	/** A player's own toggle may remove access or restore only policy-allowed access. */
	public boolean setPlayerEnabled(UUID playerId, String node, boolean enabled) {
		return setPlayerEnabled(playerId, node, enabled, false);
	}

	public boolean setPlayerEnabled(UUID playerId, String node, boolean enabled, boolean isAdmin) {
		if (!PermissionNodes.isKnown(node)) throw new IllegalArgumentException("unknown node: " + node);
		if (enabled && revoked.getOrDefault(playerId, Set.of()).contains(node)) return false;
		if (enabled && !isAdmin && !granted.getOrDefault(playerId, Set.of()).contains(node)
				&& !PermissionNodes.DEFAULT_PLAYER_NODES.contains(node)) return false;
		if (enabled) {
			playerDisabled.computeIfPresent(playerId, (k, set) -> without(set, node));
		} else {
			playerDisabled.merge(playerId, Set.of(node), PermissionManager::union);
		}
		persist();
		return true;
	}

	private static Set<String> union(Set<String> a, Set<String> b) {
		var merged = new java.util.HashSet<>(a);
		merged.addAll(b);
		return Set.copyOf(merged);
	}

	private static Set<String> without(Set<String> values, String node) {
		return values.stream().filter(n -> !n.equals(node))
			.collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	/** Test/admin helper: clear all explicit grants and revocations. */
	public void reset() {
		granted.clear();
		revoked.clear();
		playerDisabled.clear();
		persist();
	}

	public Map<UUID, Set<String>> snapshotGrants() {
		return Map.copyOf(granted);
	}

	public Map<UUID, Set<String>> snapshotRevocations() { return Map.copyOf(revoked); }
	public Map<UUID, Set<String>> snapshotPlayerDisabled() { return Map.copyOf(playerDisabled); }
}
