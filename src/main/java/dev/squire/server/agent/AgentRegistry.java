package dev.squire.server.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import dev.squire.server.body.avatar.AvatarEntity;
import net.minecraft.entity.Entity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;

/**
 * Runtime-owned index of live agents (ADR-013): never persisted itself, always
 * rebuildable from world entities. Entity references are NOT cached across lookups —
 * they are resolved through the server each time so chunk unloads stay safe.
 *
 * <p><b>为什么这里的兜底扫描要限频。</b>解析失败时的兜底是「遍历所有维度的所有已加载
 * 实体」。它作为保险丝没问题，作为每 tick 的常规路径就是灾难：伙伴没召唤、已阵亡或所在
 * 区块卸载时，索引必然命中不了，而 {@code GuardRuntime}、{@code refreshActivityStates}、
 * {@code AutonomyController} 三条每 tick 路径会照样来问——于是一秒二十次扫遍整个世界。
 * 装的模组越多越糟：维度从 3 个变成 5 个，生物类模组又把实体数翻几倍，同一段代码的
 * 成本跟着一起涨。这正是「加了这个 mod 之后整个世界都卡」的机制。</p>
 *
 * <p>所以扫描按 key 限频到 {@link #FULL_SCAN_COOLDOWN_TICKS}。这不会削弱 ADR-013 的
 * 「世界状态权威」——索引的日常维护本来就由 {@code ServerEntityEvents.ENTITY_LOAD/UNLOAD}
 * 负责（见 {@code SquireMod}），兜底只是修复漏网情况，两秒内自愈完全够用。真正需要
 * 「此刻必须是权威答案」的调用点（召唤时的去重）走 {@link #resolveForOwnerNow}。</p>
 */
public final class AgentRegistry {
	private static final org.slf4j.Logger LOG =
		org.slf4j.LoggerFactory.getLogger(AgentRegistry.class);

	/** 同一个 key 两次全量扫描之间至少隔这么多 tick（2 秒）。 */
	private static final int FULL_SCAN_COOLDOWN_TICKS = 40;

	private final MinecraftServer server;
	/** agent entity uuid -> owner uuid (metadata survives while the index lives). */
	private final Map<UUID, UUID> ownersByEntityUuid = new HashMap<>();
	/** owner uuid -> agent entity uuids (a player could own multiple avatars later). */
	private final Map<UUID, List<UUID>> agentEntityUuidsByOwner = new HashMap<>();
	/** 永久 agentId -> 当前实体 uuid（方案 A1：索引随 load/unload 修复）。 */
	private final Map<UUID, UUID> entityUuidByAgentId = new HashMap<>();
	/**
	 * agent entity uuid -> 它所在的维度。
	 *
	 * <p>有了这个，命中路径就是一次 {@code getEntity}，而不是拿同一个 uuid 去挨个维度
	 * 试——后者在单人存档里也要白试四次，在这个整合包里是五次。</p>
	 */
	private final Map<UUID, RegistryKey<World>> worldByEntityUuid = new HashMap<>();

	/** 上次为某个 owner / agentId 付出全量扫描的服务器 tick。 */
	private final Map<UUID, Integer> lastScanByOwner = new HashMap<>();
	private final Map<UUID, Integer> lastScanByAgentId = new HashMap<>();

	public AgentRegistry(MinecraftServer server) {
		this.server = server;
	}

	/** Rebuild the whole index from loaded entities (server start / recovery path). */
	public void rebuildFromWorlds() {
		ownersByEntityUuid.clear();
		agentEntityUuidsByOwner.clear();
		entityUuidByAgentId.clear();
		worldByEntityUuid.clear();
		lastScanByOwner.clear();
		lastScanByAgentId.clear();
		for (ServerWorld world : server.getWorlds()) {
			for (Entity entity : world.iterateEntities()) {
				if (entity instanceof AvatarEntity avatar && avatar.ownerId() != null) {
					index(avatar);
				}
			}
		}
	}

	public void register(AvatarEntity avatar) {
		if (avatar.ownerId() == null) {
			throw new IllegalArgumentException("avatar must have an owner before registration");
		}
		index(avatar);
	}

	/**
	 * 方案 A1：区块加载把伙伴实体带回时修复索引并快照实体；同一 owner/agentId
	 * 出现重复实体时保留索引指向的那个，其余安全移除并记录审计日志。
	 */
	public void onEntityLoad(AvatarEntity avatar) {
		if (avatar.ownerId() == null || avatar.isRemoved()) {
			return;
		}
		UUID existing = entityUuidByAgentId.get(avatar.agentId());
		if (existing != null && !existing.equals(avatar.getUuid())) {
			Entity kept = findEntity(existing);
			if (kept instanceof AvatarEntity alive && alive.isAlive()
					&& !alive.isRemoved()) {
				LOG.warn("[agents] duplicate avatar {} for agent {} discarded; "
					+ "keeping indexed {}", avatar.getUuid(), avatar.agentId(),
					existing);
				dev.squire.SquireMod.LOGGER.info(
					"[Squire][audit] duplicate-avatar discard agent={} removed={}",
					avatar.agentId(), avatar.getUuid());
				avatar.discard();
				return;
			}
		}
		register(avatar);
	}

	/** 方案 A1：卸载只解除运行时引用；长期状态已由 Store 快照。 */
	public void onEntityUnload(AvatarEntity avatar) {
		unregister(avatar.getUuid());
	}

	public void unregister(UUID agentEntityUuid) {
		UUID owner = ownersByEntityUuid.remove(agentEntityUuid);
		worldByEntityUuid.remove(agentEntityUuid);
		if (owner != null) {
			List<UUID> list = agentEntityUuidsByOwner.get(owner);
			if (list != null) {
				list.remove(agentEntityUuid);
				if (list.isEmpty()) {
					agentEntityUuidsByOwner.remove(owner);
				}
			}
		}
		entityUuidByAgentId.values().removeIf(u -> u.equals(agentEntityUuid));
	}

	/**
	 * Resolve the live avatar entity for an owner; empty when offline/unloaded/gone.
	 *
	 * <p>The in-memory index is an OPTIMIZATION, never the source of truth (ADR-013):
	 * on an index miss we fall back to scanning loaded entities and heal the index with
	 * what we find. World state is authoritative even if this index was rebuilt,
	 * cleared or raced against a concurrent registration.</p>
	 *
	 * <p>兜底扫描按 owner 限频（见类文档）。需要保证拿到权威答案的调用点用
	 * {@link #resolveForOwnerNow}。</p>
	 */
	public Optional<AvatarEntity> resolveForOwner(UUID ownerUuid) {
		return resolveForOwner(ownerUuid, true);
	}

	/**
	 * 和 {@link #resolveForOwner} 同义，但兜底扫描不限频。
	 *
	 * <p>给「一个 owner 只能有一只伙伴」这类不变量用：召唤前如果因为限频谎报了
	 * 「没有」，玩家就会凭空多出第二只伙伴。这条路径一局游戏里只走几次，扫就扫。</p>
	 */
	public Optional<AvatarEntity> resolveForOwnerNow(UUID ownerUuid) {
		return resolveForOwner(ownerUuid, false);
	}

	private Optional<AvatarEntity> resolveForOwner(UUID ownerUuid, boolean throttled) {
		List<UUID> ids = agentEntityUuidsByOwner.get(ownerUuid);
		if (ids != null) {
			for (UUID id : ids) {
				if (findEntity(id) instanceof AvatarEntity avatar && avatar.isAlive()) {
					return Optional.of(avatar);
				}
			}
		}
		if (throttled && !mayFullScan(lastScanByOwner, ownerUuid)) {
			return Optional.empty();
		}
		// authoritative fallback: scan loaded entities for this owner's avatar
		for (ServerWorld world : server.getWorlds()) {
			for (Entity entity : world.iterateEntities()) {
				if (entity instanceof AvatarEntity avatar
						&& ownerUuid.equals(avatar.ownerId()) && avatar.isAlive()) {
					index(avatar); // heal the index
					lastScanByOwner.remove(ownerUuid);
					return Optional.of(avatar);
				}
			}
		}
		return Optional.empty();
	}

	/**
	 * Resolve a live avatar by its AGENT id (stable across respawns). Index-first
	 * (方案 A1) with the same scan-heal fallback as {@link #resolveForOwner} (ADR-017),
	 * 兜底同样按 agentId 限频。
	 */
	public Optional<AvatarEntity> resolveByAgentId(UUID agentId) {
		UUID indexed = entityUuidByAgentId.get(agentId);
		if (indexed != null) {
			Entity entity = findEntity(indexed);
			if (entity instanceof AvatarEntity avatar && avatar.isAlive()
					&& !avatar.isRemoved()) {
				return Optional.of(avatar);
			}
			entityUuidByAgentId.remove(agentId, indexed); // stale index entry
		}
		if (!mayFullScan(lastScanByAgentId, agentId)) {
			return Optional.empty();
		}
		for (ServerWorld world : server.getWorlds()) {
			for (Entity entity : world.iterateEntities()) {
				if (entity instanceof AvatarEntity avatar
						&& agentId.equals(avatar.agentId()) && avatar.isAlive()
						&& !avatar.isRemoved()) {
					index(avatar); // heal the index
					lastScanByAgentId.remove(agentId);
					return Optional.of(avatar);
				}
			}
		}
		return Optional.empty();
	}

	/**
	 * 距上次为这个 key 全量扫描是否已经够久。够久就顺手记下这一次，等于「预约」了
	 * 接下来的扫描；扫到了的话调用方会把记录删掉，让下一次未命中能立刻重扫。
	 */
	private boolean mayFullScan(Map<UUID, Integer> lastScanLog, UUID key) {
		int now = server.getTicks();
		Integer last = lastScanLog.get(key);
		if (last != null && now - last < FULL_SCAN_COOLDOWN_TICKS) {
			return false;
		}
		lastScanLog.put(key, now);
		return true;
	}

	/**
	 * 先按索引记下的维度直查；只有索引里没有维度信息时才退回逐维度试。
	 */
	private Entity findEntity(UUID entityUuid) {
		RegistryKey<World> known = worldByEntityUuid.get(entityUuid);
		if (known != null) {
			ServerWorld world = server.getWorld(known);
			if (world != null) {
				Entity entity = world.getEntity(entityUuid);
				if (entity != null) {
					return entity;
				}
			}
			// 记的维度里没有了：可能刚跨了维度，让下面的遍历去找，找到时会重新记。
		}
		for (ServerWorld world : server.getWorlds()) {
			Entity entity = world.getEntity(entityUuid);
			if (entity != null) {
				// 只更正维度这一项，不走 index()：index() 会动
				// agentEntityUuidsByOwner，而调用方可能正在遍历它。
				worldByEntityUuid.put(entityUuid, world.getRegistryKey());
				return entity;
			}
		}
		return null;
	}

	public boolean isOwnerOf(UUID playerUuid, AvatarEntity avatar) {
		return playerUuid.equals(avatar.ownerId());
	}

	public int size() {
		return ownersByEntityUuid.size();
	}

	private void index(AvatarEntity avatar) {
		UUID owner = avatar.ownerId();
		ownersByEntityUuid.put(avatar.getUuid(), owner);
		entityUuidByAgentId.put(avatar.agentId(), avatar.getUuid());
		worldByEntityUuid.put(avatar.getUuid(), avatar.getWorld().getRegistryKey());
		List<UUID> list = agentEntityUuidsByOwner.computeIfAbsent(owner,
			k -> new ArrayList<>());
		if (!list.contains(avatar.getUuid())) {
			list.add(avatar.getUuid());
		}
	}

	/**
	 * 已知的 owner 列表。
	 *
	 * <p>刻意返回<b>拷贝</b>而不是 {@code keySet()} 视图：调用方（如
	 * {@code followAcrossDimensions}）会在遍历过程中做跨维度传送，那会触发
	 * ENTITY_UNLOAD/LOAD，进而在这张表上增删 key——用视图就是一个必然的
	 * {@code ConcurrentModificationException}。每拍几个小对象换这条命，划算。</p>
	 */
	public List<UUID> knownOwners() {
		return Collections.unmodifiableList(new ArrayList<>(agentEntityUuidsByOwner.keySet()));
	}
}
