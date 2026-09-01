package dev.squire.server.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.squire.server.body.avatar.AvatarEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.world.PersistentState;

/**
 * 长期身份事实来源（方案 A1）：按 owner 保存 {@link AgentRecord} 列表。
 *
 * <p>AgentRegistry 只是已加载实体索引（ADR-017）；这里保存"即使实体未加载或被
 * dismiss 也必须存在"的世界级状态。第一次 summon 创建 agentId；以后 summon、
 * dismiss、死亡恢复、跨维度物化和服务器重启都沿用它。</p>
 *
 * <p>schema v2：owner → 记录列表（一位玩家将来可以有多只随从），数据结构先摆正、
 * 数量仍限制为 1；另有 {@link AgentRecord#primary} 标记默认指挥对象。旧版 v1 文件
 * 是扁平记录列表、每条自带 ownerId，读进列表后每个 owner 自然只剩一条——
 * 升版本号防的是反向：旧 jar 读 v2 会按 owner 覆盖、静默吃掉第二只随从。</p>
 *
 * <p>schemaVersion 语义：未知未来版本 fail-closed（拒绝加载并保留原数据），
 * 旧版缺省字段保守迁移。叶子字段一律 additive-optional，不为它们升版本。</p>
 */
public final class SquireAgentStateStore extends PersistentState {

	private static final Logger LOG = LoggerFactory.getLogger(SquireAgentStateStore.class);

	public static final int CURRENT_SCHEMA_VERSION = 2;
	private static final String KEY_SCHEMA_VERSION = "schemaVersion";
	private static final String KEY_RECORDS = "records";

	/** 当前是否物化在世界中。 */
	public record BodyRef(UUID entityUuid, String dimension, BlockPos pos) {
	}

	/**
	 * 一只伙伴的永久档案。agentId 永久不变；entityUuid 是当前实体实例，可变化。
	 */
	public static final class AgentRecord {
		public final UUID ownerId;
		public final UUID agentId;
		public UUID entityUuid;
		public String displayName;
		/** 外观模型：alex / steve（皮肤资料 id 预留）。 */
		public String model = "alex";
		public boolean activeBody;
		public GlobalPos lastPos;
		/** IDLE / FOLLOW / STAY / PATROL（与 AvatarEntity.MovementMode 对应）。 */
		public String movementMode = "IDLE";
		public GlobalPos stayGlobalPos;
		public GlobalPos homeGlobalPos;
		public final List<ItemStack> inventory = new ArrayList<>();
		/**
		 * 他背上那个背包（「精妙背包」之类）。
		 *
		 * <p>必须跟着记录走：重新召唤会丢掉旧身体、按这条记录重建一具新的，
		 * 记录里没有背包就等于玩家的背包连同里面的东西一起没了。</p>
		 */
		public ItemStack backpack = ItemStack.EMPTY;
		public ItemStack mainHand = ItemStack.EMPTY;
		public ItemStack offHand = ItemStack.EMPTY;
		public final ItemStack[] armor = new ItemStack[] {
			ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY };
		public float health = 20.0f;
		/** 长期策略引用（如 GuardPolicy JSON），phase D 填充。 */
		public final List<String> persistentPolicies = new ArrayList<>();
		/**
		 * 同一 owner 的多只随从中谁是默认指挥对象。v2 起落盘；从 v1 迁移的记录
		 * 由 {@link #readNbt} 补上第一位。
		 */
		public boolean primary;
		/** 档案事实（职业/成长/自主程度）。与身体快照分开，快照不会碰它。 */
		public final dev.squire.server.profile.SquireProfile profile =
			new dev.squire.server.profile.SquireProfile();

		public AgentRecord(UUID ownerId, UUID agentId) {
			this.ownerId = ownerId;
			this.agentId = agentId;
			this.displayName = "Squire";
		}

		public void setDisplayName(String name) {
			this.displayName = (name == null || name.isBlank()) ? "Squire" : name;
		}
	}

	/** owner uuid -> 该玩家的全部档案（v2 起为列表；当前仍限 1 条）。 */
	private final Map<UUID, List<AgentRecord>> recordsByOwner = new ConcurrentHashMap<>();
	/** agent uuid -> record（直接索引；不再绕道 owner）。 */
	private final Map<UUID, AgentRecord> recordsByAgent = new ConcurrentHashMap<>();
	/** 加载时检测到的未知未来版本 → fail-closed 只读。 */
	private boolean failClosed;

	// ------------------------------------------------------------------ access

	public static SquireAgentStateStore get(MinecraftServer server) {
		return server.getOverworld().getPersistentStateManager().getOrCreate(
			SquireAgentStateStore::createFromNbt, SquireAgentStateStore::new,
			"squire_agents");
	}

	private static SquireAgentStateStore createFromNbt(NbtCompound nbt) {
		SquireAgentStateStore store = new SquireAgentStateStore();
		store.readNbt(nbt);
		return store;
	}

	/** 测试/诊断用：从 NBT 重建一个独立实例（不注册到 PersistentStateManager）。 */
	public static SquireAgentStateStore createFromNbtPublic(NbtCompound nbt) {
		return createFromNbt(nbt);
	}

	/**
	 * 老接口，语义已收窄为「返回 primary（默认指挥对象）」。v1 时代每位玩家只有一条
	 * 记录，所以现有调用方（summon、M6 生命周期测试）行为不变；多随从开放后，
	 * 「召唤我的随从」指的就是 primary。
	 */
	public Optional<AgentRecord> recordOfOwner(UUID ownerId) {
		List<AgentRecord> records = recordsByOwner.get(ownerId);
		if (records == null || records.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(records.stream().filter(r -> r.primary).findFirst()
			.orElse(records.get(0)));
	}

	/** 该玩家的全部档案；多随从开放前长度恒为 0 或 1。 */
	public List<AgentRecord> recordsOfOwner(UUID ownerId) {
		return List.copyOf(recordsByOwner.getOrDefault(ownerId, List.of()));
	}

	/** 直接按 agentId 索引。多随从时代这是实体加载路径的正确入口。 */
	public Optional<AgentRecord> recordOfAgent(UUID agentId) {
		return Optional.ofNullable(recordsByAgent.get(agentId));
	}

	public List<AgentRecord> allRecords() {
		return recordsByOwner.values().stream().flatMap(List::stream).toList();
	}

	public AgentRecord createRecord(UUID ownerId, String displayName) {
		ensureWritable();
		AgentRecord record = new AgentRecord(ownerId, UUID.randomUUID());
		record.setDisplayName(displayName);
		put(record);
		return record;
	}

	/**
	 * 按 agentId 去重地插入或替换：同 id 覆盖，新 id 追加到该 owner 的列表尾部；
	 * 该 owner 尚无 primary 时这条成为 primary。显式 {@code setPrimary} 可以后改。
	 */
	public synchronized void put(AgentRecord record) {
		ensureWritable();
		List<AgentRecord> records = recordsByOwner.computeIfAbsent(record.ownerId,
			k -> new ArrayList<>());
		records.removeIf(existing -> existing.agentId.equals(record.agentId));
		records.add(record);
		recordsByAgent.put(record.agentId, record);
		if (record.primary || records.stream().noneMatch(r -> r.primary)) {
			for (AgentRecord other : records) {
				other.primary = other.agentId.equals(record.agentId);
			}
		}
		markDirty();
	}

	/** 把默认指挥对象换成同 owner 的另一只随从。 */
	public synchronized void setPrimary(UUID agentId) {
		ensureWritable();
		AgentRecord record = recordsByAgent.get(agentId);
		if (record == null) {
			return;
		}
		List<AgentRecord> records = recordsByOwner.get(record.ownerId);
		if (records == null) {
			return;
		}
		for (AgentRecord other : records) {
			other.primary = other.agentId.equals(agentId);
		}
		markDirty();
	}

	// ------------------------------------------------------------------ snapshots

	/**
	 * 把实体当前状态快照进它的档案（背包/装备/血量/位置/模式/锚点）。
	 * 实体未加载或已 discard 时安全返回 false。
	 */
	public boolean snapshotFromEntity(AvatarEntity avatar) {
		return snapshotFromEntity(avatar, avatar != null && !avatar.isRemoved()
			&& avatar.isAlive());
	}

	/**
	 * Lifecycle-aware snapshot. Chunk-unloaded entities are removed from the live
	 * entity manager but still exist in chunk NBT, so callers pass {@code activeBody=true};
	 * death/dismiss pass false. A stale duplicate can never overwrite the Store's
	 * currently active entity instance.
	 */
	public boolean snapshotFromEntity(AvatarEntity avatar, boolean activeBody) {
		if (avatar == null || avatar.ownerId() == null || failClosed) {
			return false;
		}
		AgentRecord record = recordOfAgent(avatar.agentId())
			.orElseGet(() -> migrateFromLegacy(avatar));
		if (record.entityUuid != null && !record.entityUuid.equals(avatar.getUuid())
				&& record.activeBody) {
			LOG.warn("[squire-store] stale entity {} cannot overwrite active entity {} "
				+ "for agent {}", avatar.getUuid(), record.entityUuid, record.agentId);
			return false;
		}
		record.entityUuid = avatar.getUuid();
		record.activeBody = activeBody;
		if (avatar.getWorld() instanceof ServerWorld world) {
			record.lastPos = GlobalPos.create(world.getRegistryKey(), avatar.getBlockPos());
		}
		record.movementMode = avatar.mode().name();
		record.stayGlobalPos = avatar.stayGlobalPos().orElse(null);
		GlobalPos home = avatar.homeGlobalPos().orElse(null);
		if (home != null) {
			record.homeGlobalPos = home;
		}
		record.inventory.clear();
		for (int i = 0; i < avatar.inventorySize(); i++) {
			ItemStack stack = avatar.mainInventoryStack(i);
			record.inventory.add(stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
		}
		record.backpack = copyOrEmpty(avatar.backpackStack());
		record.mainHand = copyOrEmpty(avatar.getEquippedStack(
			net.minecraft.entity.EquipmentSlot.MAINHAND));
		record.offHand = copyOrEmpty(avatar.getEquippedStack(
			net.minecraft.entity.EquipmentSlot.OFFHAND));
		for (int i = 0; i < 4; i++) {
			record.armor[i] = copyOrEmpty(avatar.getEquippedStack(
				net.minecraft.entity.EquipmentSlot.fromTypeIndex(
					net.minecraft.entity.EquipmentSlot.Type.ARMOR, i)));
		}
		record.health = avatar.getHealth();
		markDirty();
		return true;
	}

	/** Update materialization state through a dirty-tracked API. */
	public void setActiveBody(UUID agentId, boolean active) {
		ensureWritable();
		recordOfAgent(agentId).ifPresent(record -> {
			record.activeBody = active;
			markDirty();
		});
	}

	/** Add or replace a named durable runtime policy (for example persistent Guard). */
	public synchronized void putPersistentPolicy(UUID agentId, String policy) {
		ensureWritable();
		AgentRecord record = recordOfAgent(agentId).orElse(null);
		if (record == null || policy == null || policy.isBlank()) {
			return;
		}
		String key = policy.contains(":") ? policy.substring(0, policy.indexOf(':')) : policy;
		record.persistentPolicies.removeIf(existing -> existing.equals(key)
			|| existing.startsWith(key + ":"));
		record.persistentPolicies.add(policy);
		markDirty();
	}

	private static ItemStack copyOrEmpty(ItemStack stack) {
		return stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
	}

	/**
	 * 13.1 迁移：旧版 Avatar NBT 只有 agentId、owner、home BlockPos 和 9 格背包；
	 * 首次加载时生成档案并沿用原 agentId；名字缺失时生成一次。
	 */
	public AgentRecord migrateFromLegacy(AvatarEntity avatar) {
		ensureWritable();
		if (avatar == null || avatar.ownerId() == null) {
			throw new IllegalArgumentException(
				"cannot migrate an ownerless avatar into the agent store");
		}
		AgentRecord record = new AgentRecord(avatar.ownerId(), avatar.agentId());
		record.setDisplayName("Squire-" + (recordsByAgent.size() + 1));
		put(record);
		LOG.info("[squire-store] migrated legacy avatar {} into a persistent record",
			avatar.agentId());
		snapshotFromEntity(avatar);
		return record;
	}

	// ------------------------------------------------------------------ nbt

	@Override
	public NbtCompound writeNbt(NbtCompound nbt) {
		nbt.putInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION);
		NbtList list = new NbtList();
		for (AgentRecord record : allRecords()) {
			list.add(writeRecord(record));
		}
		nbt.put(KEY_RECORDS, list);
		return nbt;
	}

	private void readNbt(NbtCompound nbt) {
		int version = nbt.contains(KEY_SCHEMA_VERSION)
			? nbt.getInt(KEY_SCHEMA_VERSION) : 0;
		if (version > CURRENT_SCHEMA_VERSION) {
			failClosed = true; // unknown future version: keep data untouched on save
			LOG.error("[squire-store] schemaVersion {} is newer than supported {}; "
				+ "agent store loaded READ-ONLY", version, CURRENT_SCHEMA_VERSION);
			return;
		}
		NbtList list = nbt.getList(KEY_RECORDS, NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < list.size(); i++) {
			try {
				AgentRecord record = readRecord(list.getCompound(i));
				if (record != null) {
					put(record);
				}
			} catch (RuntimeException e) { // one bad row must not sink the rest
				LOG.warn("[squire-store] skipped corrupt agent record: {}", e.toString());
			}
		}
		// v1 文件没有 primary 字段：给每个 owner 的第一条补上，语义与 v1 的
		// 「每位玩家唯一的记录」完全一致。v2+ 文件由 put() 保持恰好一个 primary。
		for (List<AgentRecord> records : recordsByOwner.values()) {
			if (records.stream().noneMatch(r -> r.primary)) {
				records.get(0).primary = true;
			}
		}
	}

	private void ensureWritable() {
		if (failClosed) {
			throw new IllegalStateException(
				"agent store schema is newer than this mod; writes are disabled");
		}
	}

	private static NbtCompound writeRecord(AgentRecord r) {
		NbtCompound c = new NbtCompound();
		c.putUuid("ownerId", r.ownerId);
		c.putUuid("agentId", r.agentId);
		if (r.entityUuid != null) {
			c.putUuid("entityUuid", r.entityUuid);
		}
		c.putString("displayName", r.displayName);
		c.putString("model", r.model);
		c.putBoolean("activeBody", r.activeBody);
		if (r.lastPos != null) {
			c.put("lastPos", writeGlobalPos(r.lastPos));
		}
		c.putString("movementMode", r.movementMode);
		if (r.stayGlobalPos != null) {
			c.put("stayPos", writeGlobalPos(r.stayGlobalPos));
		}
		if (r.homeGlobalPos != null) {
			c.put("homePos", writeGlobalPos(r.homeGlobalPos));
		}
		NbtList inv = new NbtList();
		int slot = 0;
		for (ItemStack stack : r.inventory) {
			if (!stack.isEmpty()) {
				NbtCompound entry = new NbtCompound();
				entry.putByte("Slot", (byte) slot);
				entry.put("stack", stack.writeNbt(new NbtCompound()));
				inv.add(entry);
			}
			slot++;
		}
		c.put("inventory", inv);
		if (!r.backpack.isEmpty()) { // 只在非默认值时写（additive-optional 约定）
			c.put("backpack", r.backpack.writeNbt(new NbtCompound()));
		}
		c.put("mainHand", r.mainHand.writeNbt(new NbtCompound()));
		c.put("offHand", r.offHand.writeNbt(new NbtCompound()));
		for (int i = 0; i < 4; i++) {
			c.put("armor" + i, r.armor[i].writeNbt(new NbtCompound()));
		}
		c.putFloat("health", r.health);
		if (!r.persistentPolicies.isEmpty()) {
			NbtList policies = new NbtList();
			for (String policy : r.persistentPolicies) {
				policies.add(net.minecraft.nbt.NbtString.of(policy));
			}
			c.put("policies", policies);
		}
		if (r.primary) { // 只在非默认值时写（additive-optional 约定）
			c.putBoolean("primary", true);
		}
		c.put("profile", r.profile.writeNbt(new NbtCompound()));
		return c;
	}

	private AgentRecord readRecord(NbtCompound c) {
		AgentRecord r = new AgentRecord(c.getUuid("ownerId"), c.getUuid("agentId"));
		r.entityUuid = c.containsUuid("entityUuid") ? c.getUuid("entityUuid") : null;
		r.setDisplayName(c.getString("displayName"));
		r.model = c.getString("model").isBlank() ? "alex" : c.getString("model");
		r.activeBody = c.getBoolean("activeBody");
		r.lastPos = c.contains("lastPos") ? readGlobalPos(c.getCompound("lastPos")) : null;
		r.movementMode = c.getString("movementMode").isBlank()
			? "IDLE" : c.getString("movementMode");
		r.stayGlobalPos = c.contains("stayPos")
			? readGlobalPos(c.getCompound("stayPos")) : null;
		r.homeGlobalPos = c.contains("homePos")
			? readGlobalPos(c.getCompound("homePos")) : null;
		NbtList inv = c.getList("inventory", NbtElement.COMPOUND_TYPE);
		int maxSlot = AvatarEntity.MAIN_INVENTORY_SIZE - 1;
		for (int s = 0; s <= maxSlot; s++) {
			r.inventory.add(ItemStack.EMPTY);
		}
		for (int i = 0; i < inv.size(); i++) {
			NbtCompound entry = inv.getCompound(i);
			int slot = Byte.toUnsignedInt(entry.getByte("Slot"));
			if (slot < r.inventory.size()) {
				r.inventory.set(slot, ItemStack.fromNbt(entry.getCompound("stack")));
			}
		}
		if (c.contains("backpack")) { // 老档没有这个键，缺省就是没背包
			r.backpack = ItemStack.fromNbt(c.getCompound("backpack"));
		}
		r.mainHand = ItemStack.fromNbt(c.getCompound("mainHand"));
		r.offHand = ItemStack.fromNbt(c.getCompound("offHand"));
		for (int i = 0; i < 4; i++) {
			if (c.contains("armor" + i)) {
				r.armor[i] = ItemStack.fromNbt(c.getCompound("armor" + i));
			}
		}
		r.health = c.getFloat("health");
		NbtList policies = c.getList("policies", NbtElement.STRING_TYPE);
		for (int i = 0; i < policies.size(); i++) {
			r.persistentPolicies.add(policies.getString(i));
		}
		r.primary = c.getBoolean("primary"); // v1 缺省 false，readNbt 兜底补位
		if (c.contains("profile")) {
			r.profile.readNbt(c.getCompound("profile"));
		}
		return r;
	}

	private static NbtCompound writeGlobalPos(GlobalPos pos) {
		NbtCompound c = new NbtCompound();
		c.putString("dimension", pos.getDimension().getValue().toString());
		c.putLong("pos", pos.getPos().asLong());
		return c;
	}

	private static GlobalPos readGlobalPos(NbtCompound c) {
		return GlobalPos.create(RegistryKey.of(net.minecraft.registry.RegistryKeys.WORLD,
			new net.minecraft.util.Identifier(c.getString("dimension"))),
			BlockPos.fromLong(c.getLong("pos")));
	}

	@Override
	public boolean isDirty() {
		return !failClosed && super.isDirty();
	}

	/** True when an unsupported future version forced the read-only mode. */
	public boolean isFailClosed() {
		return failClosed;
	}
}
