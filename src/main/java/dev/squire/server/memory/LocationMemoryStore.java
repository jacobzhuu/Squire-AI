package dev.squire.server.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.world.PersistentState;

/**
 * 长期位置记忆的事实来源（方案 E1）：home、warehouse、farm、mine 和自定义地点，
 * 全部带维度，跨重启保留。
 *
 * <p>解析规则：玩家显式命名 &gt; 自动观察；"上次矿洞"按 type=MINE 的 lastVisitedAt
 * 取最大值；同名冲突时返回候选列表，由上层询问玩家——绝不静默挑一个。</p>
 */
public final class LocationMemoryStore extends PersistentState {

	private static final Logger LOG = LoggerFactory.getLogger(LocationMemoryStore.class);

	public static final int CURRENT_SCHEMA_VERSION = 1;
	private static final String KEY_SCHEMA_VERSION = "schemaVersion";
	private static final String KEY_MEMORIES = "memories";
	/** 每位玩家的记忆上限，防止无界增长。 */
	public static final int MAX_PER_OWNER = 128;

	/** 解析结果：0 个候选=没记住，1 个=唯一，多个=需要玩家澄清。 */
	public record Resolution(List<LocationMemory> candidates) {

		public boolean unique() {
			return candidates.size() == 1;
		}

		public boolean ambiguous() {
			return candidates.size() > 1;
		}

		public boolean empty() {
			return candidates.isEmpty();
		}

		public LocationMemory best() {
			return candidates.isEmpty() ? null : candidates.get(0);
		}
	}

	/** memoryId -> memory。 */
	private final Map<UUID, LocationMemory> memories = new ConcurrentHashMap<>();
	private boolean failClosed;

	public static LocationMemoryStore get(MinecraftServer server) {
		return server.getOverworld().getPersistentStateManager().getOrCreate(
			LocationMemoryStore::createFromNbt, LocationMemoryStore::new,
			"squire_locations");
	}

	private static LocationMemoryStore createFromNbt(NbtCompound nbt) {
		LocationMemoryStore store = new LocationMemoryStore();
		store.readNbt(nbt);
		return store;
	}

	/** 测试/诊断用：从 NBT 重建一个独立实例。 */
	public static LocationMemoryStore createFromNbtPublic(NbtCompound nbt) {
		return createFromNbt(nbt);
	}

	// ------------------------------------------------------------------ 写入

	/**
	 * 记住一个地点。同一 owner + 同类型 + 同名的显式记忆会被覆盖（玩家改主意是常态），
	 * 但不同名字的地点各自独立保存。
	 */
	public LocationMemory remember(LocationMemory memory) {
		ensureWritable();
		Optional<LocationMemory> existing = memories.values().stream()
			.filter(m -> m.ownerId().equals(memory.ownerId())
				&& m.type() == memory.type()
				&& sameName(m.canonicalName(), memory.canonicalName()))
			.findFirst();
		existing.ifPresent(m -> memories.remove(m.memoryId()));
		if (countOf(memory.ownerId()) >= MAX_PER_OWNER) {
			// 满了就淘汰最久没被访问的一条，而不是拒绝新指令
			memories.values().stream()
				.filter(m -> m.ownerId().equals(memory.ownerId()))
				.min(Comparator.comparingLong(LocationMemory::lastVisitedAt))
				.ifPresent(m -> memories.remove(m.memoryId()));
		}
		memories.put(memory.memoryId(), memory);
		markDirty();
		return memory;
	}

	/** 更新一条已有记忆（访问时间、关联容器等）。 */
	public void update(LocationMemory memory) {
		ensureWritable();
		if (memories.containsKey(memory.memoryId())) {
			memories.put(memory.memoryId(), memory);
			markDirty();
		}
	}

	/**
	 * 删除记忆。owner 本人或 admin 才能删（方案 E1）；调用方负责传入已判定的
	 * {@code isAdmin}。
	 */
	public boolean forget(UUID requesterId, boolean isAdmin, UUID memoryId) {
		ensureWritable();
		LocationMemory memory = memories.get(memoryId);
		if (memory == null) {
			return false;
		}
		if (!isAdmin && !memory.ownerId().equals(requesterId)) {
			return false;
		}
		memories.remove(memoryId);
		markDirty();
		return true;
	}

	// ------------------------------------------------------------------ 查询

	public List<LocationMemory> ofOwner(UUID ownerId) {
		return memories.values().stream()
			.filter(m -> m.ownerId().equals(ownerId))
			.sorted(Comparator.comparing(LocationMemory::type)
				.thenComparing(LocationMemory::canonicalName,
					Comparator.nullsLast(Comparator.naturalOrder())))
			.toList();
	}

	public Optional<LocationMemory> byId(UUID memoryId) {
		return Optional.ofNullable(memories.get(memoryId));
	}

	public int countOf(UUID ownerId) {
		return (int) memories.values().stream()
			.filter(m -> m.ownerId().equals(ownerId)).count();
	}

	/**
	 * 按玩家说法解析地点。
	 *
	 * <p>"上次矿洞"这类"最近一次"的说法按 lastVisitedAt 取最大值；其余按名字/别名/
	 * 类型匹配。多个同权重候选一律原样返回，让上层去问，绝不静默选错位置。</p>
	 */
	public Resolution resolve(UUID ownerId, String phrase) {
		if (phrase == null || phrase.isBlank()) {
			return new Resolution(List.of());
		}
		String text = phrase.trim();
		boolean mostRecent = text.startsWith("上次") || text.startsWith("最近")
			|| text.toLowerCase(java.util.Locale.ROOT).startsWith("last ");
		String subject = mostRecent
			? text.replaceFirst("^(上次|最近)", "").replaceFirst("(?i)^last ", "").trim()
			: text;

		List<LocationMemory> matched = new ArrayList<>(memories.values().stream()
			.filter(m -> m.ownerId().equals(ownerId))
			.filter(m -> m.matches(subject))
			.toList());
		if (matched.isEmpty()) {
			return new Resolution(List.of());
		}
		if (mostRecent) {
			matched.sort(Comparator.comparingLong(LocationMemory::lastVisitedAt).reversed());
			return new Resolution(List.of(matched.get(0)));
		}
		// 显式命名优先于自动观察；同来源时最近访问优先
		matched.sort(Comparator.comparingDouble(LocationMemory::confidence).reversed()
			.thenComparing(Comparator.comparingLong(LocationMemory::lastVisitedAt)
				.reversed()));
		// 名字精确命中是唯一的；只按类型命中且有多条时必须澄清
		List<LocationMemory> exact = matched.stream()
			.filter(m -> sameName(m.canonicalName(), subject)).toList();
		if (exact.size() == 1) {
			return new Resolution(exact);
		}
		if (matched.size() > 1
				&& matched.get(0).confidence() == matched.get(1).confidence()) {
			return new Resolution(List.copyOf(matched));
		}
		return new Resolution(List.of(matched.get(0)));
	}

	private static boolean sameName(String a, String b) {
		return a != null && b != null
			&& a.trim().equalsIgnoreCase(b.trim());
	}

	// ------------------------------------------------------------------ nbt

	@Override
	public NbtCompound writeNbt(NbtCompound nbt) {
		nbt.putInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION);
		NbtList list = new NbtList();
		for (LocationMemory memory : memories.values()) {
			list.add(write(memory));
		}
		nbt.put(KEY_MEMORIES, list);
		return nbt;
	}

	private void readNbt(NbtCompound nbt) {
		int version = nbt.contains(KEY_SCHEMA_VERSION) ? nbt.getInt(KEY_SCHEMA_VERSION) : 0;
		if (version > CURRENT_SCHEMA_VERSION) {
			failClosed = true;
			LOG.error("[squire-memory] schemaVersion {} is newer than supported {}; "
				+ "location memory loaded READ-ONLY", version, CURRENT_SCHEMA_VERSION);
			return;
		}
		NbtList list = nbt.getList(KEY_MEMORIES, NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < list.size(); i++) {
			try {
				LocationMemory memory = read(list.getCompound(i));
				memories.put(memory.memoryId(), memory);
			} catch (RuntimeException e) {
				LOG.warn("[squire-memory] skipped corrupt location record: {}", e.toString());
			}
		}
	}

	private void ensureWritable() {
		if (failClosed) {
			throw new IllegalStateException(
				"location memory schema is newer than this mod; writes are disabled");
		}
	}

	private static NbtCompound write(LocationMemory m) {
		NbtCompound c = new NbtCompound();
		c.putUuid("memoryId", m.memoryId());
		c.putUuid("ownerId", m.ownerId());
		if (m.agentId() != null) {
			c.putUuid("agentId", m.agentId());
		}
		c.putString("type", m.type().name());
		c.putString("name", m.canonicalName() == null ? "" : m.canonicalName());
		NbtList aliases = new NbtList();
		m.aliases().forEach(alias -> aliases.add(NbtString.of(alias)));
		c.put("aliases", aliases);
		c.put("pos", writeGlobalPos(m.pos()));
		if (m.containerPos() != null) {
			c.put("containerPos", writeGlobalPos(m.containerPos()));
		}
		c.putInt("radius", m.radius());
		c.putLong("createdAt", m.createdAt());
		c.putLong("lastVisitedAt", m.lastVisitedAt());
		c.putLong("lastConfirmedAt", m.lastConfirmedAt());
		c.putDouble("confidence", m.confidence());
		c.putString("source", m.source().name());
		return c;
	}

	private static LocationMemory read(NbtCompound c) {
		List<String> aliases = new ArrayList<>();
		NbtList list = c.getList("aliases", NbtElement.STRING_TYPE);
		for (int i = 0; i < list.size(); i++) {
			aliases.add(list.getString(i));
		}
		String name = c.getString("name");
		return new LocationMemory(
			c.getUuid("memoryId"),
			c.getUuid("ownerId"),
			c.containsUuid("agentId") ? c.getUuid("agentId") : null,
			parseType(c.getString("type")),
			name.isBlank() ? null : name,
			aliases,
			readGlobalPos(c.getCompound("pos")),
			c.getInt("radius"),
			c.getLong("createdAt"),
			c.getLong("lastVisitedAt"),
			c.getLong("lastConfirmedAt"),
			c.contains("confidence") ? c.getDouble("confidence") : 1.0,
			parseSource(c.getString("source")),
			c.contains("containerPos") ? readGlobalPos(c.getCompound("containerPos")) : null);
	}

	private static LocationMemory.Type parseType(String raw) {
		try {
			return LocationMemory.Type.valueOf(raw);
		} catch (IllegalArgumentException e) {
			return LocationMemory.Type.CUSTOM; // 未知类型保守降级，不丢坐标
		}
	}

	private static LocationMemory.Source parseSource(String raw) {
		try {
			return LocationMemory.Source.valueOf(raw);
		} catch (IllegalArgumentException e) {
			return LocationMemory.Source.OBSERVED;
		}
	}

	private static NbtCompound writeGlobalPos(GlobalPos pos) {
		NbtCompound c = new NbtCompound();
		c.putString("dimension", pos.getDimension().getValue().toString());
		c.putLong("pos", pos.getPos().asLong());
		return c;
	}

	private static GlobalPos readGlobalPos(NbtCompound c) {
		return GlobalPos.create(
			RegistryKey.of(RegistryKeys.WORLD, new Identifier(c.getString("dimension"))),
			BlockPos.fromLong(c.getLong("pos")));
	}

	@Override
	public boolean isDirty() {
		return !failClosed && super.isDirty();
	}

	public boolean isFailClosed() {
		return failClosed;
	}
}
