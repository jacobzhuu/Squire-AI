package dev.squire.server.item;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** All recall-bell balance numbers, loaded once when the server starts. */
public final class BellReviveConfig {

	private static final org.slf4j.Logger LOG =
		org.slf4j.LoggerFactory.getLogger(BellReviveConfig.class);
	private static final long TICKS_PER_MINUTE = 20L * 60L;
	private static final int TICKS_PER_SECOND = 20;
	private static final java.util.Set<String> DEFENSIVE_EFFECTS = java.util.Set.of(
		"minecraft:resistance", "minecraft:regeneration", "minecraft:absorption");

	public record EffectSpec(String effectId, int amplifier) {
		public EffectSpec {
			if (!DEFENSIVE_EFFECTS.contains(effectId)) {
				throw new IllegalArgumentException("revive effect is not defensive: " + effectId);
			}
			if (amplifier < 0 || amplifier > 4) {
				throw new IllegalArgumentException("invalid revive effect amplifier");
			}
		}
	}

	public record ReviveBuff(int durationTicks, List<EffectSpec> effects) {
		public ReviveBuff {
			durationTicks = Math.max(0, durationTicks);
			effects = effects == null ? List.of() : List.copyOf(effects);
		}

		public boolean empty() {
			return durationTicks <= 0 || effects.isEmpty();
		}
	}

	public record TierRule(double reviveHealth, long reviveCooldown,
			ReviveBuff reviveBuff, int levelRequirement) {
		public TierRule {
			if (!(reviveHealth > 0.0 && reviveHealth <= 1.0)) {
				throw new IllegalArgumentException("reviveHealth must be in (0,1]");
			}
			if (reviveCooldown < 0L) {
				throw new IllegalArgumentException("reviveCooldown cannot be negative");
			}
			reviveBuff = reviveBuff == null ? new ReviveBuff(0, List.of()) : reviveBuff;
			if (levelRequirement < 0 || levelRequirement > 10) {
				throw new IllegalArgumentException("levelRequirement must be 0..10");
			}
		}
	}

	public record LevelBonus(int minLevel, int maxLevel, double bonusHealth) {
		public LevelBonus {
			if (minLevel < 0 || maxLevel < minLevel || maxLevel > 10
					|| bonusHealth < 0.0 || bonusHealth > 1.0) {
				throw new IllegalArgumentException("invalid revive level bonus band");
			}
		}

		boolean contains(int level) {
			return level >= minLevel && level <= maxLevel;
		}
	}

	private final double maxReviveHealth;
	private final List<LevelBonus> levelBonuses;
	private final EnumMap<BellTier, TierRule> tiers;

	private BellReviveConfig(double maxReviveHealth, List<LevelBonus> levelBonuses,
			Map<BellTier, TierRule> tiers) {
		if (!(maxReviveHealth > 0.0 && maxReviveHealth <= 1.0)) {
			throw new IllegalArgumentException("maxReviveHealth must be in (0,1]");
		}
		this.maxReviveHealth = maxReviveHealth;
		this.levelBonuses = List.copyOf(levelBonuses);
		this.tiers = new EnumMap<>(BellTier.class);
		this.tiers.putAll(tiers);
		for (BellTier tier : BellTier.values()) {
			if (!this.tiers.containsKey(tier)) {
				throw new IllegalArgumentException("missing bell tier: " + tier.id());
			}
		}
	}

	public static BellReviveConfig load(Path path) {
		BellReviveConfig defaults = defaults();
		try {
			if (!Files.exists(path)) {
				Files.createDirectories(path.getParent());
				Files.writeString(path, defaultJson());
				return defaults;
			}
			JsonElement parsed = JsonParser.parseString(Files.readString(path));
			if (!parsed.isJsonObject()) throw new IllegalArgumentException("root is not an object");
			return parse(parsed.getAsJsonObject());
		} catch (IOException | RuntimeException bad) {
			LOG.warn("[squire-bell] cannot load {} ({}); using complete defaults",
				path, bad.toString());
			return defaults;
		}
	}

	public static BellReviveConfig defaults() {
		EnumMap<BellTier, TierRule> rules = new EnumMap<>(BellTier.class);
		rules.put(BellTier.COMMON, new TierRule(0.20, 20 * TICKS_PER_MINUTE,
			new ReviveBuff(0, List.of()), 0));
		rules.put(BellTier.ENHANCED, new TierRule(0.30, 15 * TICKS_PER_MINUTE,
			buff(8, effect("minecraft:resistance", 0)), 5));
		rules.put(BellTier.RESONANT, new TierRule(0.40, 12 * TICKS_PER_MINUTE,
			buff(10, effect("minecraft:resistance", 0),
				effect("minecraft:regeneration", 0)), 7));
		rules.put(BellTier.ROYAL, new TierRule(0.50, 10 * TICKS_PER_MINUTE,
			buff(12, effect("minecraft:resistance", 0),
				effect("minecraft:regeneration", 0),
				effect("minecraft:absorption", 1)), 10));
		return new BellReviveConfig(0.60, List.of(
			new LevelBonus(0, 2, 0.00),
			new LevelBonus(3, 4, 0.05),
			new LevelBonus(5, 6, 0.10),
			new LevelBonus(7, 8, 0.15),
			new LevelBonus(9, 10, 0.20)), rules);
	}

	private static BellReviveConfig parse(JsonObject root) {
		double cap = number(root, "maxReviveHealth");
		List<LevelBonus> bonuses = new ArrayList<>();
		for (JsonElement element : root.getAsJsonArray("levelBonuses")) {
			JsonObject row = element.getAsJsonObject();
			bonuses.add(new LevelBonus(integer(row, "minLevel"),
				integer(row, "maxLevel"), number(row, "bonusHealth")));
		}
		EnumMap<BellTier, TierRule> rules = new EnumMap<>(BellTier.class);
		JsonObject tierRoot = root.getAsJsonObject("tiers");
		for (BellTier tier : BellTier.values()) {
			JsonObject row = tierRoot.getAsJsonObject(tier.id());
			if (row == null) throw new IllegalArgumentException("missing tier " + tier.id());
			JsonObject buffRoot = row.getAsJsonObject("reviveBuff");
			List<EffectSpec> effects = new ArrayList<>();
			if (buffRoot != null && buffRoot.has("effects")) {
				for (JsonElement effect : buffRoot.getAsJsonArray("effects")) {
					JsonObject spec = effect.getAsJsonObject();
					effects.add(new EffectSpec(spec.get("effect").getAsString(),
						integer(spec, "amplifier")));
				}
			}
			int duration = buffRoot == null ? 0 : integer(buffRoot, "durationSeconds");
			rules.put(tier, new TierRule(number(row, "reviveHealth"),
				Math.multiplyExact(row.get("reviveCooldownMinutes").getAsLong(),
					TICKS_PER_MINUTE),
				new ReviveBuff(Math.multiplyExact(duration, TICKS_PER_SECOND), effects),
				integer(row, "levelRequirement")));
		}
		return new BellReviveConfig(cap, bonuses, rules);
	}

	private static double number(JsonObject object, String key) {
		if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) {
			throw new IllegalArgumentException("missing number " + key);
		}
		return object.get(key).getAsDouble();
	}

	private static int integer(JsonObject object, String key) {
		if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) {
			throw new IllegalArgumentException("missing integer " + key);
		}
		return object.get(key).getAsInt();
	}

	private static EffectSpec effect(String id, int amplifier) {
		return new EffectSpec(id, amplifier);
	}

	private static ReviveBuff buff(int seconds, EffectSpec... effects) {
		return new ReviveBuff(seconds * TICKS_PER_SECOND, List.of(effects));
	}

	public TierRule rule(BellTier tier) {
		return tiers.get(tier == null ? BellTier.COMMON : tier);
	}

	public double reviveHealth(BellTier tier, int level) {
		double bonus = levelBonuses.stream().filter(row -> row.contains(level))
			.findFirst().map(LevelBonus::bonusHealth).orElse(0.0);
		return Math.min(maxReviveHealth, rule(tier).reviveHealth() + bonus);
	}

	public long reviveCooldown(BellTier tier) {
		return rule(tier).reviveCooldown();
	}

	/** Highest cumulative defensive package supported by both quality and level. */
	public ReviveBuff reviveBuff(BellTier tier, int level) {
		ReviveBuff chosen = new ReviveBuff(0, List.of());
		for (BellTier candidate : BellTier.values()) {
			TierRule rule = rule(candidate);
			if (tier.atLeast(candidate) && level >= rule.levelRequirement()
					&& !rule.reviveBuff().empty()) {
				chosen = rule.reviveBuff();
			}
		}
		return chosen;
	}

	public double maxReviveHealth() {
		return maxReviveHealth;
	}

	public static String defaultJson() {
		JsonObject root = new JsonObject();
		root.addProperty("maxReviveHealth", 0.60);
		JsonArray bonuses = new JsonArray();
		addBonus(bonuses, 0, 2, 0.00);
		addBonus(bonuses, 3, 4, 0.05);
		addBonus(bonuses, 5, 6, 0.10);
		addBonus(bonuses, 7, 8, 0.15);
		addBonus(bonuses, 9, 10, 0.20);
		root.add("levelBonuses", bonuses);
		JsonObject tiers = new JsonObject();
		addTier(tiers, "common", 0.20, 20, 0, 0, List.of());
		addTier(tiers, "enhanced", 0.30, 15, 5, 8,
			List.of(effect("minecraft:resistance", 0)));
		addTier(tiers, "resonant", 0.40, 12, 7, 10,
			List.of(effect("minecraft:resistance", 0),
				effect("minecraft:regeneration", 0)));
		addTier(tiers, "royal", 0.50, 10, 10, 12,
			List.of(effect("minecraft:resistance", 0),
				effect("minecraft:regeneration", 0),
				effect("minecraft:absorption", 1)));
		root.add("tiers", tiers);
		return new GsonBuilder().setPrettyPrinting().create().toJson(root) + System.lineSeparator();
	}

	private static void addBonus(JsonArray array, int min, int max, double bonus) {
		JsonObject row = new JsonObject();
		row.addProperty("minLevel", min);
		row.addProperty("maxLevel", max);
		row.addProperty("bonusHealth", bonus);
		array.add(row);
	}

	private static void addTier(JsonObject tiers, String id, double health,
			long cooldownMinutes, int requirement, int durationSeconds,
			List<EffectSpec> effects) {
		JsonObject row = new JsonObject();
		row.addProperty("reviveHealth", health);
		row.addProperty("reviveCooldownMinutes", cooldownMinutes);
		row.addProperty("levelRequirement", requirement);
		JsonObject buff = new JsonObject();
		buff.addProperty("durationSeconds", durationSeconds);
		JsonArray list = new JsonArray();
		for (EffectSpec effect : effects) {
			JsonObject spec = new JsonObject();
			spec.addProperty("effect", effect.effectId());
			spec.addProperty("amplifier", effect.amplifier());
			list.add(spec);
		}
		buff.add("effects", list);
		row.add("reviveBuff", buff);
		tiers.add(id, row);
	}
}
