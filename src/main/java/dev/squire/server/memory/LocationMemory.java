package dev.squire.server.memory;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import net.minecraft.util.math.GlobalPos;

/**
 * 一条长期位置记忆（方案 E1）。{@link GlobalPos} 必须同时包含维度和 BlockPos——
 * 只记 BlockPos 的记忆在下界/末地会把玩家送到完全错误的地方。
 */
public record LocationMemory(UUID memoryId, UUID ownerId, UUID agentId, Type type,
		String canonicalName, List<String> aliases, GlobalPos pos, int radius,
		long createdAt, long lastVisitedAt, long lastConfirmedAt, double confidence,
		Source source, GlobalPos containerPos) {

	public enum Type {
		HOME, WAREHOUSE, FARM, MINE, CUSTOM;

		/**
		 * 只有整句就是这些词时才算 HOME。"家"是单字，用 contains 会把"那家店""大家"
		 * 误判成家；这类短词必须精确匹配。
		 */
		private static final List<String> HOME_EXACT = List.of(
			"家", "我家", "老家", "home", "my home");
		/** 足够长、足够特征化的词，出现在句中就可以判定类型。 */
		private static final List<String> HOME_CONTAINS =
			List.of("基地", "home base", "base");
		private static final List<String> WAREHOUSE_CONTAINS = List.of(
			"仓库", "储藏", "箱子", "warehouse", "storage");
		private static final List<String> FARM_CONTAINS = List.of("农场", "农田", "farm");
		private static final List<String> MINE_CONTAINS = List.of(
			"矿洞", "矿井", "矿场", "mineshaft", "mine");

		/** 中文/英文说法 → 类型。无法判断时返回 CUSTOM。 */
		public static Type fromPhrase(String raw) {
			if (raw == null) {
				return CUSTOM;
			}
			String text = raw.trim().toLowerCase(Locale.ROOT);
			if (HOME_EXACT.contains(text) || containsAny(text, HOME_CONTAINS)) {
				return HOME;
			}
			if (containsAny(text, WAREHOUSE_CONTAINS)) {
				return WAREHOUSE;
			}
			if (containsAny(text, FARM_CONTAINS)) {
				return FARM;
			}
			if (containsAny(text, MINE_CONTAINS)) {
				return MINE;
			}
			return CUSTOM;
		}

		private static boolean containsAny(String text, List<String> needles) {
			for (String needle : needles) {
				if (text.contains(needle)) {
					return true;
				}
			}
			return false;
		}

		/** True when a phrase names this type rather than a specific place. */
		public static boolean isTypePhrase(String raw) {
			return fromPhrase(raw) != CUSTOM;
		}
	}

	/** 记忆来源；玩家显式命名的优先级高于自动观察（方案 E1）。 */
	public enum Source {
		PLAYER_EXPLICIT(1.0), TASK_RESULT(0.7), OBSERVED(0.5);

		public final double baseConfidence;

		Source(double baseConfidence) {
			this.baseConfidence = baseConfidence;
		}
	}

	public LocationMemory {
		aliases = aliases == null ? List.of() : List.copyOf(aliases);
		radius = Math.max(0, radius);
		confidence = Math.max(0.0, Math.min(1.0, confidence));
	}

	public static LocationMemory explicit(UUID ownerId, UUID agentId, Type type,
			String name, GlobalPos pos, long now) {
		return new LocationMemory(UUID.randomUUID(), ownerId, agentId, type, name,
			List.of(), pos, 0, now, now, now, Source.PLAYER_EXPLICIT.baseConfidence,
			Source.PLAYER_EXPLICIT, null);
	}

	public LocationMemory visited(long now) {
		return new LocationMemory(memoryId, ownerId, agentId, type, canonicalName,
			aliases, pos, radius, createdAt, now, lastConfirmedAt, confidence, source,
			containerPos);
	}

	public LocationMemory withContainer(GlobalPos container, long now) {
		return new LocationMemory(memoryId, ownerId, agentId, type, canonicalName,
			aliases, pos, radius, createdAt, lastVisitedAt, now, confidence, source,
			container);
	}

	/** True when the phrase names this memory (canonical name, alias, or its type). */
	public boolean matches(String phrase) {
		if (phrase == null || phrase.isBlank()) {
			return false;
		}
		String text = phrase.trim().toLowerCase(Locale.ROOT);
		if (canonicalName != null && canonicalName.toLowerCase(Locale.ROOT).equals(text)) {
			return true;
		}
		for (String alias : aliases) {
			if (alias.toLowerCase(Locale.ROOT).equals(text)) {
				return true;
			}
		}
		return Type.isTypePhrase(text) && Type.fromPhrase(text) == type;
	}

	public String dimensionId() {
		return pos.getDimension().getValue().toString();
	}

	/** Short player-facing form: "矿洞 @ minecraft:the_nether (12, 40, -8)". */
	public String describe() {
		return (canonicalName == null || canonicalName.isBlank()
			? type.name() : canonicalName)
			+ " @ " + dimensionId() + " (" + pos.getPos().getX() + ", "
			+ pos.getPos().getY() + ", " + pos.getPos().getZ() + ")";
	}
}
