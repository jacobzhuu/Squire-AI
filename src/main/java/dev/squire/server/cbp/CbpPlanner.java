package dev.squire.server.cbp;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * 白名单模板 → 结构化 {@link CbpSpec}（方案 H2）。
 *
 * <p>模型能做的只有点名模板 + 给一个锚点，它无法自己写命令：每条命令都由这里按
 * {@code CbpCommandPolicy} 允许的形状生成，再由 spec 校验一次。这样"LLM 塞进一条
 * raw command"在结构上就不可能发生。</p>
 *
 * <p>装置是真的：有朝向、有 conditional 链、有红石输入（阳光传感器/拉杆）、有可见
 * 输出（红石灯）和一块说明牌，玩家可以直接打开命令方块修改里面的命令。</p>
 */
public final class CbpPlanner {

	/** 内置模板。 */
	public enum Template {
		/** 真实命令方块昼夜控制器：阳光传感器驱动，昼夜各触发一条命令。 */
		DAY_NIGHT_CONTROLLER,
		/** 可编辑的脉冲/重复命令教学装置：拉杆 + 重复命令方块 + 条件链。 */
		PULSE_TEACHING_DEVICE;

		public static Template parse(String raw) {
			if (raw == null) {
				return null;
			}
			String text = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_')
				.replace(' ', '_');
			for (Template template : values()) {
				if (template.name().equals(text)) {
					return template;
				}
			}
			return null;
		}

		/** 中文/自然语言说法 → 模板；认不出来就返回 null，由上层反问。 */
		public static Template fromPhrase(String raw) {
			if (raw == null) {
				return null;
			}
			String lower = raw.toLowerCase(Locale.ROOT);
			if (lower.contains("昼夜") || lower.contains("日夜")
					|| lower.contains("白天黑夜") || lower.contains("day/night")
					|| lower.contains("day night")) {
				return DAY_NIGHT_CONTROLLER;
			}
			if (lower.contains("脉冲") || lower.contains("重复")
					|| lower.contains("教学") || lower.contains("pulse")
					|| lower.contains("teaching")) {
				return PULSE_TEACHING_DEVICE;
			}
			return null;
		}

		public String displayName() {
			return switch (this) {
				case DAY_NIGHT_CONTROLLER -> "真实命令方块昼夜控制器";
				case PULSE_TEACHING_DEVICE -> "脉冲/重复命令教学装置";
			};
		}
	}

	private CbpPlanner() {
	}

	/**
	 * 生成一个模板的 spec。
	 *
	 * @param anchor 装置左下角；调用方负责保证它在 owner 的 workspace 内
	 */
	public static CbpSpec plan(Template template, UUID ownerId, UUID agentId,
			String name, BlockPos anchor) {
		if (template == null || anchor == null) {
			throw new IllegalArgumentException("template and anchor are required");
		}
		return switch (template) {
			case DAY_NIGHT_CONTROLLER -> dayNightController(ownerId, agentId, name, anchor);
			case PULSE_TEACHING_DEVICE -> pulseTeachingDevice(ownerId, agentId, name, anchor);
		};
	}

	/**
	 * 昼夜控制器：阳光传感器 → 命令方块（白天给玩家一点面包），反相后 → 第二个命令
	 * 方块（夜里给夜视）。命令都在白名单形状内，玩家可以直接改成自己想要的。
	 *
	 * <pre>
	 *  y+1:  [sign]
	 *  y  :  [daylight_detector][command_block][chain_command_block][redstone_lamp]
	 * </pre>
	 */
	private static CbpSpec dayNightController(UUID ownerId, UUID agentId, String name,
			BlockPos anchor) {
		BlockPos sensor = anchor;
		BlockPos dayBlock = anchor.east();
		BlockPos nightBlock = anchor.east(2);
		BlockPos lamp = anchor.east(3);
		BlockPos sign = anchor.up();

		return CbpSpec.builder(ownerId, agentId, name)
			.support(sensor, "minecraft:daylight_detector", null, null)
			.entry(dayBlock, "minecraft:command_block",
				"effect give @p regeneration 10 0", false, false, Direction.EAST, null)
			.entry(nightBlock, "minecraft:chain_command_block",
				"effect give @p night_vision 30 0", false, true, Direction.EAST, null)
			.support(lamp, "minecraft:redstone_lamp", null, null)
			.support(sign, "minecraft:oak_sign", Direction.NORTH, "昼夜控制器")
			.build();
	}

	/**
	 * 教学装置：拉杆手动触发一个重复命令方块，后面挂一个条件链命令方块。玩家可以
	 * 打开任意一个方块看命令、改命令、看条件链怎么生效。
	 *
	 * <pre>
	 *  y+1:  [sign]
	 *  y  :  [lever][repeating_command_block][chain_command_block(conditional)]
	 * </pre>
	 */
	private static CbpSpec pulseTeachingDevice(UUID ownerId, UUID agentId, String name,
			BlockPos anchor) {
		BlockPos lever = anchor;
		BlockPos repeater = anchor.east();
		BlockPos chained = anchor.east(2);
		BlockPos sign = anchor.up();

		return CbpSpec.builder(ownerId, agentId, name)
			.support(lever, "minecraft:lever", null, null)
			.entry(repeater, "minecraft:repeating_command_block",
				"give @p bread 1", false, false, Direction.EAST, null)
			.entry(chained, "minecraft:chain_command_block",
				"effect give @p speed 15 0", false, true, Direction.EAST, null)
			.support(sign, "minecraft:oak_sign", Direction.NORTH, "脉冲教学装置")
			.build();
	}

	/**
	 * 玩家可读的逐块预览（方案 H3）：位置、类型、命令摘要、auto/conditional、朝向。
	 */
	public static String describe(CbpSpec spec, String dimensionId) {
		StringBuilder text = new StringBuilder();
		text.append("命令方块工程：").append(spec.name()).append('\n');
		text.append("维度：").append(dimensionId).append('\n');
		text.append("范围：").append(spec.footprint()).append('\n');
		text.append("方块：").append(spec.entries().size()).append(" 个（其中命令方块 ")
			.append(spec.commandBlockCount()).append(" 个）\n");
		for (CbpSpec.Entry entry : spec.entries()) {
			text.append(" - (").append(entry.pos().getX()).append(", ")
				.append(entry.pos().getY()).append(", ").append(entry.pos().getZ())
				.append(") ").append(shortType(entry.blockType()));
			if (entry.facing() != null) {
				text.append(" 朝").append(entry.facing().getName());
			}
			if (entry.isCommandBlock()) {
				text.append(entry.auto() ? " [auto]" : " [needs redstone]");
				if (entry.conditional()) {
					text.append(" [conditional]");
				}
				text.append("：").append(entry.command());
			} else if (entry.signText() != null) {
				text.append("：“").append(entry.signText()).append('”');
			}
			text.append('\n');
		}
		text.append("放置后方块是惰性的（没有红石信号就不会执行），你可以直接打开它们修改命令。\n");
		text.append("可撤销条目：").append(spec.entries().size())
			.append("（/squire cbp remove 精确恢复原区域）");
		return text.toString();
	}

	private static String shortType(String blockType) {
		return blockType.startsWith("minecraft:")
			? blockType.substring("minecraft:".length()) : blockType;
	}

	/** Every template name, for honest refusals. */
	public static String supportedTemplates() {
		StringBuilder text = new StringBuilder();
		for (Template template : Template.values()) {
			if (text.length() > 0) {
				text.append("、");
			}
			text.append(template.name()).append("（").append(template.displayName())
				.append("）");
		}
		return text.toString();
	}

	/** Sanity helper for callers that want the footprint before building the spec. */
	public static List<BlockPos> footprintOf(Template template, BlockPos anchor) {
		return plan(template, UUID.randomUUID(), UUID.randomUUID(), "preview", anchor)
			.entries().stream().map(CbpSpec.Entry::pos).toList();
	}
}
