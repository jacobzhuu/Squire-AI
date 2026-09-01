package dev.squire.server.security;

import java.util.Set;

/**
 * Player-facing permission nodes (spec section 61). Roles are just default node
 * sets; the Gateway checks concrete nodes, never role names.
 */
public final class PermissionNodes {
	private PermissionNodes() {
	}

	public static final String USE = "squire.use";
	public static final String TASK_FOLLOW = "squire.task.follow";
	public static final String TASK_GUARD = "squire.task.guard";
	public static final String TASK_ACQUIRE = "squire.task.acquire";
	public static final String TASK_BUILD = "squire.task.build";
	public static final String WORLD_BREAK = "squire.world.break";
	public static final String WORLD_PLACE = "squire.world.place";
	public static final String WORLD_EDIT = "squire.world.edit";
	public static final String COMMAND_GIVE = "squire.command.give";
	public static final String COMMAND_EFFECT = "squire.command.effect";
	public static final String COMMAND_TELEPORT = "squire.command.teleport";
	public static final String COMMAND_WORLDEDIT = "squire.command.worldedit";
	/** 时间设置与结构查找：改的是世界状态，但不落方块。 */
	public static final String COMMAND_WORLD = "squire.command.world";
	public static final String AUTOMATION = "squire.automation";
	public static final String MCP_MANAGE = "squire.mcp.manage";
	public static final String ADMIN = "squire.admin";

	/**
	 * Everything an ordinary player needs for M1/M2 features (spec §94 defaults).
	 *
	 * <p>{@link #COMMAND_EFFECT} 默认开放，但实现只允许把白名单内的正面效果施加给
	 * 请求者。{@link #COMMAND_WORLD} 默认节点让只读的结构/群系查询可用；改变全服
	 * 时间或天气仍同时受 {@code adminCommandsEnabled} 总开关约束。节点授权与服务器
	 * 策略是两道独立闸门，仍可在面板权限页逐项关闭。</p>
	 */
	public static final Set<String> DEFAULT_PLAYER_NODES = Set.of(
		USE, TASK_FOLLOW, TASK_GUARD, TASK_ACQUIRE,
		WORLD_BREAK, WORLD_PLACE, COMMAND_GIVE, COMMAND_EFFECT, COMMAND_WORLD,
		COMMAND_TELEPORT);

	private static final Set<String> ALL_NODES = Set.of(
		USE, TASK_FOLLOW, TASK_GUARD, TASK_ACQUIRE, TASK_BUILD,
		WORLD_BREAK, WORLD_PLACE, WORLD_EDIT,
		COMMAND_GIVE, COMMAND_EFFECT, COMMAND_TELEPORT, COMMAND_WORLDEDIT,
		COMMAND_WORLD, AUTOMATION, MCP_MANAGE, ADMIN);

	/** Nodes contributed by extension mods (M4): known but granted to NO ONE by default. */
	private static final Set<String> CUSTOM_NODES = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/**
	 * Register an extension-provided node as checkable. Reserved {@code squire.*}
	 * namespace is rejected; custom nodes start UNGRANTED for every player (§94
	 * fail-closed default) until an admin grants them explicitly.
	 */
	public static synchronized boolean registerCustom(String node) {
		if (node == null || !node.matches("[a-z0-9_][a-z0-9_.:-]*")
				|| node.startsWith("squire.") || ALL_NODES.contains(node)) {
			return false;
		}
		CUSTOM_NODES.add(node);
		return true; // idempotent: reloads of the same extension keep their node
	}

	public static boolean isKnown(String node) {
		return ALL_NODES.contains(node) || CUSTOM_NODES.contains(node);
	}

	public static Set<String> all() {
		if (CUSTOM_NODES.isEmpty()) {
			return ALL_NODES;
		}
		var merged = new java.util.HashSet<>(ALL_NODES);
		merged.addAll(CUSTOM_NODES);
		return java.util.Set.copyOf(merged);
	}
}
