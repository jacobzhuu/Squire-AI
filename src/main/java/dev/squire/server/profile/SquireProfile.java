package dev.squire.server.profile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;

/**
 * 一只伙伴的「档案事实」：职业、能力、熟练度、性格、自主程度、行为参数。
 *
 * <p>刻意不和身体快照混在 {@code AgentRecord} 的字段里：AgentRecord 的背包/血量/位置
 * 由 {@code snapshotFromEntity} 频繁覆写，而档案事实的来源是玩家的选择和长期积累，
 * 混在一起迟早会被某次快照顺手清掉。</p>
 *
 * <p><b>字段演进约定（ADR-047）</b>：往这里加字段一律 additive-optional——
 * 读用 {@code contains} 保护、写只在非默认值时写。只有「结构性变化」才值得升
 * {@code SquireAgentStateStore} 的 schema 版本。</p>
 *
 * <p><b>不存派生值。</b>能力槽数量由熟练度累计算出来，不进 NBT。存下来的派生值
 * 迟早和来源漂开，而来源本身已经存了。</p>
 */
public final class SquireProfile {

	/** 待命就是原地站住；区域内走动由独立的巡逻模式承担。 */
	public static final int DEFAULT_STAY_RADIUS = 0;
	/** 换职业/换能力的冷却，20 tick = 1 秒，这里是 5 分钟。 */
	public static final long RESPEC_COOLDOWN_TICKS = 6000L;
	/** 巡逻点数量上限：再多玩家自己也记不住他在走哪条线。 */
	public static final int MAX_PATROL_POINTS = 8;

	// ------------------------------------------------------------------ 档案

	/** 职业 id；null = 尚未选择职业。 */
	public String roleId;
	/** 自主程度 id；默认 standard。 */
	public String autonomy = AutonomyLevel.STANDARD.id();
	/** 已解锁的能力 id。换职业不会失去——这是组小队的杠杆。 */
	public final Set<String> unlockedAbilities = new LinkedHashSet<>();
	/** 已装备的能力 id，下标即槽位。受槽位数量限制。 */
	public final List<String> equippedAbilities = new ArrayList<>();
	/** trackId → 累计。只在任务终态成功时增加。 */
	public final Map<String, Long> proficiency = new LinkedHashMap<>();
	/** trackId → 今天已经赚了多少（防挂机软上限用）。 */
	public final Map<String, Long> dailyEarned = new LinkedHashMap<>();
	/** dailyEarned 对应的 MC 天。换天时清零。 */
	public long dailyResetDay = -1L;
	/** 性格特质：首次召唤随机产生，之后可整组洗练并随档案持久化。 */
	public final List<String> traits = new ArrayList<>();
	/** 已解锁的蓝图 id（账号级解锁，不是物品）。 */
	public final Set<String> unlockedBlueprints = new LinkedHashSet<>();
	/**
	 * 玩家存下来的蓝图预设：名字 → 蓝图 id（工程师 Lv.9 起）。
	 *
	 * <p>存 id 而不是存一份参数快照，是因为 id 本身<b>就是</b>那份参数
	 * （见 {@code ProjectSpec.blueprintId}）。这样「再建一个我之前那个生存屋」
	 * 只需要一次字符串查表，不必反序列化任何东西。</p>
	 */
	public final Map<String, String> blueprintPresets = new LinkedHashMap<>();
	/** 预设数量上限：再多玩家自己也记不住名字。 */
	public static final int MAX_BLUEPRINT_PRESETS = 12;
	/** 巡逻点位，按顺序走。为空时退回绕锚点转圈。 */
	public final List<GlobalPos> patrolPoints = new ArrayList<>();
	/** 走到第几个巡逻点了。 */
	public int patrolCursor;
	/** 待命模式下允许自由活动的半径。 */
	public int stayRadius = DEFAULT_STAY_RADIUS;
	/** 上一次换职业/换能力的 tick，用于冷却。 */
	public long lastRespecTick = Long.MIN_VALUE;
	/**
	 * 职业进度（守卫 / 工程师，1–10 级）。
	 *
	 * <p>和上面那套 Role/Track 是<b>两条正交</b>的系统：Role 决定他能装哪些能力、
	 * 有几个槽；职业决定他在本行里学会了多少种做法。旧存档读进来这里就是「没有职业」，
	 * 一件已经会的事都不会少。</p>
	 */
	public final dev.squire.server.profession.ProfessionData profession =
		new dev.squire.server.profession.ProfessionData();

	// ------------------------------------------------------------------ 派生

	public Role role() {
		return Role.byId(roleId);
	}

	public AutonomyLevel autonomyLevel() {
		return AutonomyLevel.byIdOrDefault(autonomy);
	}

	public List<Trait> traitList() {
		List<Trait> out = new ArrayList<>();
		for (String id : traits) {
			Trait trait = Trait.byId(id);
			if (trait != null) {
				out.add(trait);
			}
		}
		return List.copyOf(out);
	}

	public boolean hasTrait(Trait trait) {
		return traits.contains(trait.id());
	}

	public long proficiencyOf(Track track) {
		return proficiency.getOrDefault(track.id(), 0L);
	}

	/** 本职业的等级；没有职业时永远是 1。 */
	public int level() {
		Role role = role();
		if (role == null || role.track() == null) {
			return 1;
		}
		return Track.levelFor(proficiencyOf(role.track()));
	}

	/** 现在有几个能力槽。派生值，不存档。 */
	public int slots() {
		return Role.slotsAtLevel(level());
	}

	public boolean isUnlocked(Ability ability) {
		return ability != null && unlockedAbilities.contains(ability.id());
	}

	/** 装备在槽里吗。基础能力不占槽，所以这里对它们永远是 false。 */
	public boolean isEquipped(Ability ability) {
		return ability != null && equippedAbilities.contains(ability.id());
	}

	/**
	 * <b>服务端动手之前唯一该问的问题</b>：他会这个吗？
	 *
	 * <p>基础能力永远会——选一个职业绝不该让伙伴突然不会做他昨天还会做的事；
	 * 进阶能力必须真的装在槽里。</p>
	 */
	public boolean can(Ability ability) {
		if (ability == null || !ability.available()) {
			return false;
		}
		return ability.basic() || isEquipped(ability);
	}

	public List<Ability> equippedList() {
		List<Ability> out = new ArrayList<>();
		for (String id : equippedAbilities) {
			Ability ability = Ability.byId(id);
			if (ability != null) {
				out.add(ability);
			}
		}
		return List.copyOf(out);
	}

	// ------------------------------------------------------------------ 变更

	/**
	 * 按当前熟练度补齐<b>本职业</b>已经够格的能力。别的职业练过的东西不会丢，
	 * 也不会因为换了职业被重新锁上。
	 *
	 * @return 这一次新解锁的能力（用来发聊天提示；空表示没有新东西）
	 */
	public List<Ability> refreshUnlocks() {
		Role role = role();
		if (role == null || !role.available()) {
			return List.of();
		}
		int level = level();
		List<Ability> fresh = new ArrayList<>();
		for (Ability ability : Ability.of(role)) {
			if (ability.basic() || !ability.available()) {
				continue; // 基础能力不需要解锁；未落地的不假装存在
			}
			if (ability.unlockLevel() <= level && unlockedAbilities.add(ability.id())) {
				fresh.add(ability);
			}
		}
		return List.copyOf(fresh);
	}

	/**
	 * 把已装备列表裁到当前槽位数以内，并丢掉不再合法的条目。
	 *
	 * <p>降级不会发生（熟练度只增不减），但换职业会让第 4 槽的「本职业限定」条件
	 * 失效——那一格必须被摘掉，否则一个护卫会带着建筑师的第 4 槽能力。</p>
	 *
	 * @return 被摘掉的能力
	 */
	public List<Ability> pruneEquipped() {
		List<Ability> removed = new ArrayList<>();
		Role role = role();
		int slots = slots();
		for (int i = equippedAbilities.size() - 1; i >= 0; i--) {
			Ability ability = Ability.byId(equippedAbilities.get(i));
			boolean illegal = ability == null
				|| ability.basic()
				|| !ability.available()
				|| !unlockedAbilities.contains(ability.id())
				|| i >= slots
				|| (Role.slotIsRoleLocked(i) && ability.role() != role);
			if (illegal) {
				equippedAbilities.remove(i);
				if (ability != null) {
					removed.add(ability);
				}
			}
		}
		return List.copyOf(removed);
	}

	/**
	 * 记一次熟练度。已经过了软上限的部分按 {@link Track#OVERFLOW_RATE} 折算。
	 *
	 * @param day 当前 MC 天，换天时清空当日计数
	 * @return 实际记入的量（可能小于 raw，甚至是 0）
	 */
	public long award(Track track, long raw, long day) {
		if (track == null || raw <= 0) {
			return 0L;
		}
		if (day != dailyResetDay) {
			dailyEarned.clear();
			dailyResetDay = day;
		}
		long earnedToday = dailyEarned.getOrDefault(track.id(), 0L);
		long credited = track.applyDailyCap(earnedToday, raw);
		if (credited <= 0) {
			return 0L;
		}
		dailyEarned.merge(track.id(), credited, Long::sum);
		proficiency.merge(track.id(), credited, Long::sum);
		return credited;
	}

	/**
	 * 撤销回扣：把一次已经记过的建造量扣回来。
	 *
	 * <p>没有这一条，「建 → undo → 建」就是无限刷。当日计数一起扣，否则玩家可以
	 * 用撤销把软上限清出空间。累计值不会被扣成负数。</p>
	 */
	public long revoke(Track track, long amount) {
		if (track == null || amount <= 0) {
			return 0L;
		}
		long have = proficiency.getOrDefault(track.id(), 0L);
		long taken = Math.min(have, amount);
		if (taken <= 0) {
			return 0L;
		}
		proficiency.put(track.id(), have - taken);
		long today = dailyEarned.getOrDefault(track.id(), 0L);
		dailyEarned.put(track.id(), Math.max(0L, today - taken));
		return taken;
	}

	// ------------------------------------------------------------------ NBT

	public NbtCompound writeNbt(NbtCompound c) {
		if (roleId != null) {
			c.putString("roleId", roleId);
		}
		if (!AutonomyLevel.STANDARD.id().equals(autonomy)) {
			c.putString("autonomy", autonomy);
		}
		putStrings(c, "unlocked", unlockedAbilities);
		putStrings(c, "equipped", equippedAbilities);
		putLongs(c, "proficiency", proficiency);
		putLongs(c, "dailyEarned", dailyEarned);
		if (dailyResetDay >= 0) {
			c.putLong("dailyResetDay", dailyResetDay);
		}
		putStrings(c, "traits", traits);
		putStrings(c, "blueprints", unlockedBlueprints);
		if (!blueprintPresets.isEmpty()) {
			NbtCompound presets = new NbtCompound();
			blueprintPresets.forEach(presets::putString);
			c.put("blueprintPresets", presets);
		}
		if (!patrolPoints.isEmpty()) {
			NbtList list = new NbtList();
			for (GlobalPos point : patrolPoints) {
				NbtCompound entry = new NbtCompound();
				entry.putString("dim", point.getDimension().getValue().toString());
				entry.putInt("x", point.getPos().getX());
				entry.putInt("y", point.getPos().getY());
				entry.putInt("z", point.getPos().getZ());
				list.add(entry);
			}
			c.put("patrolPoints", list);
		}
		if (patrolCursor != 0) {
			c.putInt("patrolCursor", patrolCursor);
		}
		if (stayRadius != DEFAULT_STAY_RADIUS) {
			c.putInt("stayRadius", stayRadius);
		}
		if (lastRespecTick != Long.MIN_VALUE) {
			c.putLong("lastRespecTick", lastRespecTick);
		}
		NbtCompound professionNbt = profession.writeNbt();
		if (professionNbt != null) {
			c.put(dev.squire.server.profession.ProfessionData.NBT_KEY, professionNbt);
		}
		return c;
	}

	public void readNbt(NbtCompound c) {
		roleId = c.contains("roleId") ? c.getString("roleId") : null;
		autonomy = c.contains("autonomy") && !c.getString("autonomy").isBlank()
			? c.getString("autonomy") : AutonomyLevel.STANDARD.id();
		readStrings(c, "unlocked", unlockedAbilities);
		equippedAbilities.clear();
		if (c.contains("equipped")) {
			NbtList list = c.getList("equipped", NbtElement.STRING_TYPE);
			for (int i = 0; i < list.size(); i++) {
				equippedAbilities.add(list.getString(i));
			}
		}
		readLongs(c, "proficiency", proficiency);
		readLongs(c, "dailyEarned", dailyEarned);
		dailyResetDay = c.contains("dailyResetDay") ? c.getLong("dailyResetDay") : -1L;
		traits.clear();
		if (c.contains("traits")) {
			NbtList list = c.getList("traits", NbtElement.STRING_TYPE);
			for (int i = 0; i < list.size(); i++) {
				traits.add(list.getString(i));
			}
		}
		readStrings(c, "blueprints", unlockedBlueprints);
		blueprintPresets.clear();
		if (c.contains("blueprintPresets")) {
			NbtCompound presets = c.getCompound("blueprintPresets");
			for (String name : presets.getKeys()) {
				blueprintPresets.put(name, presets.getString(name));
			}
		}
		patrolPoints.clear();
		if (c.contains("patrolPoints")) {
			NbtList list = c.getList("patrolPoints", NbtElement.COMPOUND_TYPE);
			for (int i = 0; i < list.size(); i++) {
				NbtCompound entry = list.getCompound(i);
				try {
					patrolPoints.add(GlobalPos.create(
						RegistryKey.of(RegistryKeys.WORLD,
							new Identifier(entry.getString("dim"))),
						new BlockPos(entry.getInt("x"), entry.getInt("y"),
							entry.getInt("z"))));
				} catch (RuntimeException bad) {
					// 坏的一个点位不该毁掉整条巡逻线，更不该毁掉整份档案
				}
			}
		}
		patrolCursor = c.contains("patrolCursor") ? c.getInt("patrolCursor") : 0;
		// Older saves used an eight-block "stay area".  That overlapped patrol and
		// made a successful stay command look broken, so migrate every old value to
		// the new exact-anchor semantics.
		stayRadius = DEFAULT_STAY_RADIUS;
		lastRespecTick = c.contains("lastRespecTick") ? c.getLong("lastRespecTick")
			: Long.MIN_VALUE;
		profession.readNbt(
			c.contains(dev.squire.server.profession.ProfessionData.NBT_KEY)
				? c.getCompound(dev.squire.server.profession.ProfessionData.NBT_KEY)
				: null);
	}

	// ------------------------------------------------------------------ helpers

	private static void putStrings(NbtCompound c, String key,
			java.util.Collection<String> values) {
		if (values.isEmpty()) {
			return;
		}
		NbtList list = new NbtList();
		for (String value : values) {
			list.add(NbtString.of(value));
		}
		c.put(key, list);
	}

	private static void readStrings(NbtCompound c, String key, Set<String> into) {
		into.clear();
		if (!c.contains(key)) {
			return;
		}
		NbtList list = c.getList(key, NbtElement.STRING_TYPE);
		for (int i = 0; i < list.size(); i++) {
			into.add(list.getString(i));
		}
	}

	private static void putLongs(NbtCompound c, String key, Map<String, Long> values) {
		if (values.isEmpty()) {
			return;
		}
		NbtCompound map = new NbtCompound();
		values.forEach((id, amount) -> {
			if (amount != null && amount > 0) {
				map.putLong(id, amount);
			}
		});
		if (!map.isEmpty()) {
			c.put(key, map);
		}
	}

	private static void readLongs(NbtCompound c, String key, Map<String, Long> into) {
		into.clear();
		if (!c.contains(key)) {
			return;
		}
		NbtCompound map = c.getCompound(key);
		for (String id : map.getKeys()) {
			into.put(id, map.getLong(id));
		}
	}
}
