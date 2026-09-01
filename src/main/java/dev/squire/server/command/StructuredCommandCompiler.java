package dev.squire.server.command;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import dev.squire.server.world.BoundedRegion;
import dev.squire.server.world.ProtectionAdapter;
import net.minecraft.entity.Entity;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * Typed intent -> registry validation -> command compilation -> execution.
 *
 * <p>No caller-controlled selector or raw command crosses this boundary. Every
 * target is bound in Java, and {@link CommandRunner} transparently carries commands
 * longer than the inline limit through a temporary command block.</p>
 */
public final class StructuredCommandCompiler {
	private StructuredCommandCompiler() {
	}

	public record CommandOutcome(boolean success, String errorDetail, int affected,
			boolean viaCommandBlock) {
		public static final CommandOutcome PARSE_FAILED =
			new CommandOutcome(false, "COMMAND_PARSE_FAILED", 0, false);

		/** Source-compatible convenience constructor used by older tests/extensions. */
		public CommandOutcome(boolean success, String errorDetail) {
			this(success, errorDetail, success ? 1 : 0, false);
		}
	}

	/** Typed intent: give a registry-valid item in bounded quantity to the target. */
	/**
	 * 一条附魔。id 与等级都是<b>类型化</b>的，SNBT 由本类在 Java 侧拼装。
	 *
	 * <p>刻意不提供"裸 NBT 字符串"字段：那等于把 SNBT 注入口开给调用方，
	 * 本类"调用方字符串永不跨越边界"的不变量会当场作废。</p>
	 */
	public record EnchantSpec(Identifier enchantmentId, int level) {
		public EnchantSpec {
			requireIdentifier(enchantmentId, "enchantmentId");
			if (level < 1 || level > 255) {
				throw new IllegalArgumentException("level out of range: " + level);
			}
		}
	}

	public record GiveIntent(Identifier itemId, int count, List<EnchantSpec> enchantments) {
		public GiveIntent {
			requireIdentifier(itemId, "itemId");
			if (count < 1 || count > 4096) {
				throw new IllegalArgumentException("count out of range: " + count);
			}
			enchantments = enchantments == null ? List.of() : List.copyOf(enchantments);
			if (enchantments.size() > MAX_ENCHANTMENTS) {
				throw new IllegalArgumentException(
					"too many enchantments: " + enchantments.size());
			}
		}

		public GiveIntent(Identifier itemId, int count) {
			this(itemId, count, List.of());
		}
	}

	/** 一件物品上能挂的附魔条数上限，防止拼出无边界的指令。 */
	public static final int MAX_ENCHANTMENTS = 32;

	/**
	 * 「满配附魔」：把该物品所有<b>适用且互不冲突</b>的附魔按各自最高等级挂上。
	 *
	 * <p>用原版的 {@code isAcceptableItem} / {@code canCombine} 来判定，所以
	 * 锋利与亡灵杀手、精准采集与时运这类互斥组合不会同时出现。诅咒类一律排除
	 * ——玩家说"满配"想要的是最强，不是绑定诅咒。</p>
	 */
	public static List<EnchantSpec> maxEnchantmentsFor(Identifier itemId) {
		net.minecraft.item.Item item = Registries.ITEM.get(itemId);
		net.minecraft.item.ItemStack stack = new net.minecraft.item.ItemStack(item);
		List<net.minecraft.enchantment.Enchantment> chosen = new java.util.ArrayList<>();
		List<EnchantSpec> out = new java.util.ArrayList<>();
		for (net.minecraft.enchantment.Enchantment enchantment
				: Registries.ENCHANTMENT) {
			if (enchantment.isCursed() || !enchantment.isAcceptableItem(stack)) {
				continue;
			}
			boolean compatible = chosen.stream()
				.allMatch(existing -> existing.canCombine(enchantment));
			if (!compatible || out.size() >= MAX_ENCHANTMENTS) {
				continue;
			}
			chosen.add(enchantment);
			out.add(new EnchantSpec(Registries.ENCHANTMENT.getId(enchantment),
				enchantment.getMaxLevel()));
		}
		return List.copyOf(out);
	}

	public static boolean itemExists(Identifier id) {
		return id != null && Registries.ITEM.containsId(id);
	}

	public static String itemCommandName(Item item) {
		return Registries.ITEM.getId(item).toString();
	}

	public static CommandOutcome executeGive(MinecraftServer server,
			ServerPlayerEntity target, GiveIntent intent) {
		return executeGive(server, target, intent, ProtectionAdapter.ALLOW_ALL);
	}

	public static CommandOutcome executeGive(MinecraftServer server,
			ServerPlayerEntity target, GiveIntent intent, ProtectionAdapter protection) {
		if (!itemExists(intent.itemId())) {
			return failed("ITEM_NOT_FOUND:" + intent.itemId());
		}
		String nbt = enchantmentNbt(intent.enchantments());
		if (nbt == null) {
			return failed("ENCHANTMENT_NOT_FOUND");
		}
		String command = String.format(Locale.ROOT, "give @s %s%s %d",
			intent.itemId(), nbt, intent.count());
		return execute(server, target, target.getUuid(), protection, command);
	}

	/**
	 * 把类型化的附魔列表拼成 SNBT；有任何一个附魔 id 不在注册表里就返回 null。
	 * 拼装完全在 Java 侧完成，调用方给不进任何原始字符串。
	 */
	private static String enchantmentNbt(List<EnchantSpec> enchantments) {
		if (enchantments == null || enchantments.isEmpty()) {
			return "";
		}
		StringBuilder nbt = new StringBuilder("{Enchantments:[");
		for (int i = 0; i < enchantments.size(); i++) {
			EnchantSpec spec = enchantments.get(i);
			if (!Registries.ENCHANTMENT.containsId(spec.enchantmentId())) {
				return null;
			}
			if (i > 0) {
				nbt.append(',');
			}
			nbt.append(String.format(Locale.ROOT, "{id:\"%s\",lvl:%ds}",
				spec.enchantmentId(), spec.level()));
		}
		return nbt.append("]}").toString();
	}

	/**
	 * 让<b>伙伴自己</b>拿到物品：在它脚下用 {@code /summon item} 生成一份掉落物，
	 * 由它自己捡起来。原版 {@code /give} 只能给玩家，给不了实体，所以走 summon。
	 *
	 * <p>这一步是"他先用指令给自己，再走过来丢给你"里的第一环。数量按堆叠上限拆分，
	 * 由调用方逐份执行。</p>
	 */
	public record AcquireIntent(Identifier itemId, int count,
			List<EnchantSpec> enchantments) {
		public AcquireIntent {
			requireIdentifier(itemId, "itemId");
			if (count < 1 || count > 64) {
				throw new IllegalArgumentException("count out of range: " + count);
			}
			enchantments = enchantments == null ? List.of() : List.copyOf(enchantments);
			if (enchantments.size() > MAX_ENCHANTMENTS) {
				throw new IllegalArgumentException(
					"too many enchantments: " + enchantments.size());
			}
		}

		public AcquireIntent(Identifier itemId, int count) {
			this(itemId, count, List.of());
		}
	}

	/** 在实体脚下生成一份掉落物，让它自己捡。 */
	public static CommandOutcome executeAcquireForAgent(MinecraftServer server,
			net.minecraft.entity.Entity agent, AcquireIntent intent,
			ProtectionAdapter protection) {
		if (agent == null) {
			return failed("ENTITY_NOT_FOUND");
		}
		if (!itemExists(intent.itemId())) {
			return failed("ITEM_NOT_FOUND:" + intent.itemId());
		}
		String nbt = enchantmentNbt(intent.enchantments());
		if (nbt == null) {
			return failed("ENCHANTMENT_NOT_FOUND");
		}
		String tag = nbt.isEmpty() ? "" : ",tag:" + nbt;
		// 坐标用绝对值：命令方块载体的执行位置和伙伴不是同一个地方。
		String command = String.format(Locale.ROOT,
			"summon minecraft:item %.2f %.2f %.2f "
				+ "{Item:{id:\"%s\",Count:%db%s},PickupDelay:0s}",
			agent.getX(), agent.getY() + 0.5, agent.getZ(),
			intent.itemId(), intent.count(), tag);
		return execute(server, agent, agent.getUuid(), protection, command);
	}

	/** Typed potion/effect request. */
	public record EffectIntent(String effectId, int durationTicks, int amplifier) {
		public EffectIntent {
			if (effectId == null || !effectId.matches("[a-z0-9_.:-]+")) {
				throw new IllegalArgumentException("effectId required");
			}
			if (durationTicks < 1 || durationTicks > 72000) {
				throw new IllegalArgumentException("duration out of range");
			}
			if (amplifier < 0 || amplifier > 4) {
				throw new IllegalArgumentException("amplifier out of range");
			}
		}
	}

	public static boolean effectExists(EffectIntent intent) {
		return Registries.STATUS_EFFECT.containsId(new Identifier(intent.effectId()));
	}

	/** Shared with the CBP command policy so both gates stay in sync. */
	public static final Set<String> ALLOWED_EFFECTS = Set.of(
		"minecraft:speed", "minecraft:haste", "minecraft:strength",
		"minecraft:jump_boost", "minecraft:regeneration", "minecraft:resistance",
		"minecraft:fire_resistance", "minecraft:water_breathing",
		"minecraft:night_vision");

	public static CommandOutcome executeEffect(MinecraftServer server,
			ServerPlayerEntity target, EffectIntent intent) {
		return executeEffect(server, target, intent, ProtectionAdapter.ALLOW_ALL);
	}

	public static CommandOutcome executeEffect(MinecraftServer server,
			ServerPlayerEntity target, EffectIntent intent, ProtectionAdapter protection) {
		if (!ALLOWED_EFFECTS.contains(intent.effectId()) || !effectExists(intent)) {
			return failed("COMMAND_DENIED:effect_not_allowed");
		}
		String command = String.format(Locale.ROOT, "effect give @s %s %d %d",
			intent.effectId(), intent.durationTicks(), intent.amplifier());
		return execute(server, target, target.getUuid(), protection, command);
	}

	/** Teleport only the Java-bound agent body to bounded coordinates. */
	public static CommandOutcome executeAgentTeleport(Entity agent,
			double x, double y, double z) {
		return executeAgentTeleport(agent, x, y, z, agent.getUuid(),
			ProtectionAdapter.ALLOW_ALL);
	}

	public static CommandOutcome executeAgentTeleport(Entity agent,
			double x, double y, double z, UUID actor, ProtectionAdapter protection) {
		double bx = Math.max(-30_000_000, Math.min(30_000_000, x));
		double by = Math.max(-2048, Math.min(2048, y));
		double bz = Math.max(-30_000_000, Math.min(30_000_000, z));
		MinecraftServer server = ((ServerWorld) agent.getWorld()).getServer();
		String command = String.format(Locale.ROOT, "tp @s %.3f %.3f %.3f",
			bx, by, bz);
		return execute(server, agent, actor, protection, command);
	}

	private static final Set<String> SAFE_SUMMONS = Set.of(
		"minecraft:cow", "minecraft:sheep", "minecraft:pig", "minecraft:chicken",
		"minecraft:rabbit", "minecraft:villager", "minecraft:iron_golem",
		"minecraft:snow_golem", "minecraft:wolf", "minecraft:cat");

	public static boolean isSafeSummon(String entityId) {
		return entityId != null && SAFE_SUMMONS.contains(entityId);
	}

	public static CommandOutcome executeSummonSafe(MinecraftServer server,
			Entity anchor, String entityId, double x, double y, double z) {
		return executeSummonSafe(server, anchor, anchor.getUuid(),
			ProtectionAdapter.ALLOW_ALL, entityId, x, y, z);
	}

	public static CommandOutcome executeSummonSafe(MinecraftServer server,
			Entity anchor, UUID actor, ProtectionAdapter protection, String entityId,
			double x, double y, double z) {
		if (!isSafeSummon(entityId)) {
			return failed("COMMAND_DENIED:entity_not_whitelisted");
		}
		if (!Registries.ENTITY_TYPE.containsId(new Identifier(entityId))) {
			return failed("ENTITY_NOT_FOUND:" + entityId);
		}
		String command = String.format(Locale.ROOT, "summon %s %.3f %.3f %.3f",
			entityId, x, y, z);
		return execute(server, anchor, actor, protection, command);
	}

	/** Typed bulk edit compiled to vanilla {@code /fill}. */
	public record FillIntent(BoundedRegion region, Identifier blockId) {
		public FillIntent {
			if (region == null) {
				throw new IllegalArgumentException("region required");
			}
			requireIdentifier(blockId, "blockId");
			if (region.volume() > BoundedRegion.MAX_VOLUME) {
				throw new IllegalArgumentException("region too large: " + region.volume());
			}
		}
	}

	/** Typed one-cell edit compiled to vanilla {@code /setblock}. */
	public record SetBlockIntent(BlockPos pos, Identifier blockId) {
		public SetBlockIntent {
			if (pos == null) {
				throw new IllegalArgumentException("position required");
			}
			requireIdentifier(blockId, "blockId");
		}
	}

	public static boolean blockExists(Identifier id) {
		return id != null && Registries.BLOCK.containsId(id);
	}

	public static CommandOutcome executeFill(MinecraftServer server, Entity anchor,
			UUID actor, ProtectionAdapter protection, FillIntent intent) {
		if (!blockExists(intent.blockId())) {
			return failed("BLOCK_NOT_FOUND:" + intent.blockId());
		}
		ServerWorld world = (ServerWorld) anchor.getWorld();
		ProtectionAdapter guard = protection == null
			? ProtectionAdapter.ALLOW_ALL : protection;
		ProtectionAdapter.PermissionDecision allowed =
			guard.canEditRegion(world, intent.region(), actor);
		if (!allowed.allowed()) {
			return failed("PROTECTED:" + allowed.reason());
		}
		BlockPos min = intent.region().min();
		BlockPos max = intent.region().max();
		String command = String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s",
			min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ(),
			intent.blockId());
		return execute(server, anchor, actor, guard, command);
	}

	public static CommandOutcome executeSetBlock(MinecraftServer server, Entity anchor,
			UUID actor, ProtectionAdapter protection, SetBlockIntent intent) {
		if (!blockExists(intent.blockId())) {
			return failed("BLOCK_NOT_FOUND:" + intent.blockId());
		}
		ServerWorld world = (ServerWorld) anchor.getWorld();
		ProtectionAdapter guard = protection == null
			? ProtectionAdapter.ALLOW_ALL : protection;
		ProtectionAdapter.PermissionDecision allowed =
			guard.canPlace(world, intent.pos(), actor);
		if (!allowed.allowed()) {
			return failed("PROTECTED:" + allowed.reason());
		}
		String command = String.format(Locale.ROOT, "setblock %d %d %d %s",
			intent.pos().getX(), intent.pos().getY(), intent.pos().getZ(),
			intent.blockId());
		return execute(server, anchor, actor, guard, command);
	}

	// ------------------------------------------------------------------ 时间

	/** {@code /time set} 的四个预设，外加任意 tick。 */
	public static final java.util.Map<String, Integer> TIME_PRESETS =
		java.util.Map.of("day", 1000, "noon", 6000, "night", 13000, "midnight", 18000);

	/**
	 * 设置<b>整个服务器</b>的时间。走原版 {@code /time set}，因此审计、保护判定和
	 * 权限闸门与其它结构化指令完全一致。
	 *
	 * @param preset {@code day/noon/night/midnight}，或 {@code null} 表示用 tick
	 */
	public static CommandOutcome executeSetTime(MinecraftServer server, Entity boundEntity,
			UUID actor, ProtectionAdapter protection, String preset, Integer dayTimeTicks) {
		String argument;
		if (preset != null && TIME_PRESETS.containsKey(preset.toLowerCase(Locale.ROOT))) {
			argument = preset.toLowerCase(Locale.ROOT);
		} else if (dayTimeTicks != null && dayTimeTicks >= 0 && dayTimeTicks < 24000) {
			argument = String.valueOf(dayTimeTicks);
		} else {
			return failed("INVALID_ARGUMENT:time_must_be_a_preset_or_0..23999");
		}
		return execute(server, boundEntity, actor, protection, "time set " + argument);
	}

	// ------------------------------------------------------------------ 天气

	/** {@code /weather} 的三种取值。 */
	public static final Set<String> WEATHER_PRESETS = Set.of("clear", "rain", "thunder");

	/**
	 * 设置天气。和 {@link #executeSetTime} 是一对：玩家说完「把时间调成白天」，
	 * 下一句多半就是「把雨停了」。
	 */
	public static CommandOutcome executeSetWeather(MinecraftServer server,
			Entity boundEntity, UUID actor, ProtectionAdapter protection, String preset,
			Integer durationSeconds) {
		String key = preset == null ? "" : preset.toLowerCase(Locale.ROOT);
		if (!WEATHER_PRESETS.contains(key)) {
			return failed("INVALID_ARGUMENT:weather_must_be_clear_rain_or_thunder");
		}
		String command = "weather " + key
			+ (durationSeconds != null && durationSeconds > 0
				? " " + Math.min(durationSeconds, 1_000_000) : "");
		return execute(server, boundEntity, actor, protection, command);
	}

	// ------------------------------------------------------------------ 找结构

	/**
	 * 找最近的结构的结果。
	 *
	 * @param found            找到了吗（没找到不是错误：那片地方可能真的没有）
	 * @param horizontalDistance 水平直线距离，玩家真正关心的数字
	 */
	public record LocateOutcome(boolean found, String matchedId, BlockPos pos,
			int horizontalDistance, String errorDetail) {

		static LocateOutcome error(String detail) {
			return new LocateOutcome(false, null, null, 0, detail);
		}

		static LocateOutcome missing(String id) {
			return new LocateOutcome(false, id, null, 0, null);
		}
	}

	/** 搜索半径（区块）。和原版 {@code /locate} 一致。 */
	private static final int LOCATE_RADIUS_CHUNKS = 100;

	/** 群系搜索半径（格）。和原版 {@code /locate biome} 一致。 */
	private static final int LOCATE_BIOME_RADIUS = 6400;
	private static final int LOCATE_BIOME_STEP = 32;

	/**
	 * 找最近的生物群系。
	 *
	 * <p>「最近的樱花林在哪」和找结构是同一类问题，但走的是另一套查询
	 * （{@code ServerWorld#locateBiome}）。同样：找不到就说找不到。</p>
	 *
	 * @param idOrTag {@code minecraft:cherry_grove} 或标签 {@code #minecraft:is_forest}
	 */
	public static LocateOutcome locateBiome(ServerWorld world, BlockPos center,
			String idOrTag) {
		if (idOrTag == null || idOrTag.isBlank()) {
			return LocateOutcome.error("INVALID_ARGUMENT:biome_required");
		}
		var registry = world.getRegistryManager()
			.get(net.minecraft.registry.RegistryKeys.BIOME);
		java.util.function.Predicate<net.minecraft.registry.entry.RegistryEntry<
			net.minecraft.world.biome.Biome>> predicate;
		if (idOrTag.startsWith("#")) {
			Identifier tagId;
			try {
				tagId = new Identifier(idOrTag.substring(1));
			} catch (RuntimeException e) {
				return LocateOutcome.error("INVALID_ARGUMENT:" + idOrTag);
			}
			var tag = net.minecraft.registry.tag.TagKey.of(
				net.minecraft.registry.RegistryKeys.BIOME, tagId);
			if (registry.getEntryList(tag).isEmpty()) {
				return LocateOutcome.error("UNKNOWN_BIOME:" + idOrTag);
			}
			predicate = entry -> entry.isIn(tag);
		} else {
			Identifier id;
			try {
				id = new Identifier(idOrTag);
			} catch (RuntimeException e) {
				return LocateOutcome.error("INVALID_ARGUMENT:" + idOrTag);
			}
			if (!registry.containsId(id)) {
				return LocateOutcome.error("UNKNOWN_BIOME:" + idOrTag);
			}
			var key = net.minecraft.registry.RegistryKey.of(
				net.minecraft.registry.RegistryKeys.BIOME, id);
			predicate = entry -> entry.matchesKey(key);
		}
		var found = world.locateBiome(predicate, center, LOCATE_BIOME_RADIUS,
			LOCATE_BIOME_STEP, LOCATE_BIOME_STEP);
		if (found == null || found.getFirst() == null) {
			return LocateOutcome.missing(idOrTag);
		}
		BlockPos pos = found.getFirst();
		int dx = pos.getX() - center.getX();
		int dz = pos.getZ() - center.getZ();
		int distance = (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz));
		return new LocateOutcome(true, idOrTag, pos, distance, null);
	}

	/**
	 * 找离 {@code center} 最近的一处结构。
	 *
	 * <p>这条能力以前<b>完全不存在</b>——「最近的远古城市在哪」不是没听懂，是没有任何
	 * 工具能回答它。走的是原版 {@code /locate} 用的同一条生成器查询，所以结果和玩家
	 * 自己敲命令一模一样；找不到就如实说找不到，绝不编一个坐标出来。</p>
	 *
	 * @param idOrTag {@code minecraft:ancient_city} 或标签 {@code #minecraft:village}
	 */
	public static LocateOutcome locateStructure(ServerWorld world, BlockPos center,
			String idOrTag) {
		if (idOrTag == null || idOrTag.isBlank()) {
			return LocateOutcome.error("INVALID_ARGUMENT:structure_required");
		}
		var registry = world.getRegistryManager()
			.get(net.minecraft.registry.RegistryKeys.STRUCTURE);
		net.minecraft.registry.entry.RegistryEntryList<
			net.minecraft.world.gen.structure.Structure> candidates;
		if (idOrTag.startsWith("#")) {
			Identifier tagId;
			try {
				tagId = new Identifier(idOrTag.substring(1));
			} catch (RuntimeException e) {
				return LocateOutcome.error("INVALID_ARGUMENT:" + idOrTag);
			}
			var tag = registry.getEntryList(net.minecraft.registry.tag.TagKey.of(
				net.minecraft.registry.RegistryKeys.STRUCTURE, tagId));
			if (tag.isEmpty()) {
				return LocateOutcome.error("UNKNOWN_STRUCTURE:" + idOrTag);
			}
			candidates = tag.get();
		} else {
			Identifier id;
			try {
				id = new Identifier(idOrTag);
			} catch (RuntimeException e) {
				return LocateOutcome.error("INVALID_ARGUMENT:" + idOrTag);
			}
			var entry = registry.getEntry(net.minecraft.registry.RegistryKey.of(
				net.minecraft.registry.RegistryKeys.STRUCTURE, id));
			if (entry.isEmpty()) {
				return LocateOutcome.error("UNKNOWN_STRUCTURE:" + idOrTag);
			}
			candidates = net.minecraft.registry.entry.RegistryEntryList.of(entry.get());
		}
		var found = world.getChunkManager().getChunkGenerator()
			.locateStructure(world, candidates, center, LOCATE_RADIUS_CHUNKS, false);
		if (found == null) {
			return LocateOutcome.missing(idOrTag);
		}
		BlockPos pos = found.getFirst();
		int dx = pos.getX() - center.getX();
		int dz = pos.getZ() - center.getZ();
		int distance = (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz));
		String matched = registry.getId(found.getSecond().value()) == null
			? idOrTag : registry.getId(found.getSecond().value()).toString();
		return new LocateOutcome(true, matched, pos, distance, null);
	}

	private static CommandOutcome execute(MinecraftServer server, Entity boundEntity,
			UUID actor, ProtectionAdapter protection, String command) {
		ServerWorld world = (ServerWorld) boundEntity.getWorld();
		CommandRunner.Outcome outcome = CommandRunner.run(server, world,
			boundEntity.getBlockPos(), actor, protection, boundEntity, command);
		return new CommandOutcome(outcome.success(), outcome.detail(),
			outcome.affected(), outcome.viaCommandBlock());
	}

	private static CommandOutcome failed(String detail) {
		return new CommandOutcome(false, detail, 0, false);
	}

	private static void requireIdentifier(Identifier id, String name) {
		if (id == null || id.getNamespace().isBlank() || id.getPath().isBlank()) {
			throw new IllegalArgumentException(name + " required");
		}
	}
}
