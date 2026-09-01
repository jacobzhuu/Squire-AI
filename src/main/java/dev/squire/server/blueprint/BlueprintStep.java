package dev.squire.server.blueprint;

import java.util.LinkedHashMap;
import java.util.Map;

import dev.squire.server.world.BoundedRegion;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * 蓝图里的一条施工步骤：把一个<b>局部坐标</b>盒子整体变成某种方块。
 *
 * <p>坐标是相对蓝图原点（西北下角）的偏移，作者永远按「正面朝北」写；实际摆放时
 * 由 {@link Blueprint#rotate} 绕 Y 轴旋转到玩家给的朝向。这样一份蓝图数据能用在
 * 四个方向，而不是让作者写四份。</p>
 *
 * <p>两个布尔量决定这一步交给谁做、缺料时怎么办：</p>
 * <ul>
 *   <li>{@code negative}——这一步是<b>挖除</b>（掏空内部、门洞、地下室）。
 *       它不消耗材料，交给掘进执行器；{@code blockId} 固定是 {@code minecraft:air}。</li>
 *   <li>{@code optional}——缺料时<b>跳过</b>而不是失败。窗户可以先不装，
 *       墙不行。没有这一位，一栋房子会因为差两块玻璃而整体停在半截。</li>
 * </ul>
 *
 * <p>步骤顺序即覆盖顺序：后面的步骤覆盖前面的。所以「实心盒 → 掏空内部」表达的是
 * 「只砌一层壳」，而不是「先浪费 100 块木板再挖掉」——真正落到执行器手上的是
 * {@link Blueprint#resolve} 展平之后的每格最终目标，见那里的说明。</p>
 */
public record BlueprintStep(int order, int x1, int y1, int z1, int x2, int y2, int z2,
		String blockId, String what, boolean optional, boolean negative,
		String materialSlot, String materialVariant, Map<String, String> properties) {

	public static final String AIR = "minecraft:air";

	public BlueprintStep {
		boolean material = materialSlot != null && !materialSlot.isBlank();
		if (!material && (blockId == null || blockId.isBlank())) {
			throw new IllegalArgumentException("step " + order + " has no blockId");
		}
		if (negative && (!AIR.equals(blockId) || material)) {
			throw new IllegalArgumentException(
				"negative step " + order + " must target air, not " + blockId);
		}
		materialSlot = material ? materialSlot : "";
		materialVariant = materialVariant == null || materialVariant.isBlank()
			? "block" : materialVariant;
		properties = properties == null ? Map.of() : Map.copyOf(properties);
	}

	/** Source-compatible constructor for old tests and data-only callers. */
	public BlueprintStep(int order, int x1, int y1, int z1, int x2, int y2, int z2,
			String blockId, String what, boolean optional, boolean negative) {
		this(order, x1, y1, z1, x2, y2, z2, blockId, what, optional, negative,
			"", "block", Map.of());
	}

	/** 一条挖除步骤。 */
	public static BlueprintStep dig(int order, int x1, int y1, int z1,
			int x2, int y2, int z2, String what) {
		return new BlueprintStep(order, x1, y1, z1, x2, y2, z2, AIR, what, false, true);
	}

	/** 一条放置步骤。 */
	public static BlueprintStep place(int order, int x1, int y1, int z1,
			int x2, int y2, int z2, String blockId, String what, boolean optional) {
		return new BlueprintStep(order, x1, y1, z1, x2, y2, z2, blockId, what,
			optional, false);
	}

	public static BlueprintStep placeState(int order, int x1, int y1, int z1,
			int x2, int y2, int z2, String blockId, String what, boolean optional,
			Map<String, String> properties) {
		return new BlueprintStep(order, x1, y1, z1, x2, y2, z2, blockId, what,
			optional, false, "", "block", properties);
	}

	public static BlueprintStep material(int order, int x1, int y1, int z1,
			int x2, int y2, int z2, String slot, String variant, String what,
			boolean optional) {
		return materialState(order, x1, y1, z1, x2, y2, z2, slot, variant, what,
			optional, Map.of());
	}

	public static BlueprintStep materialState(int order, int x1, int y1, int z1,
			int x2, int y2, int z2, String slot, String variant, String what,
			boolean optional, Map<String, String> properties) {
		return new BlueprintStep(order, x1, y1, z1, x2, y2, z2, "", what,
			optional, false, slot, variant, properties);
	}

	public boolean usesMaterial() {
		return !materialSlot.isEmpty();
	}

	/** Rotate authored horizontal state properties together with their coordinates. */
	public Map<String, String> rotatedProperties(Direction facing) {
		if (properties.isEmpty() || facing == null || facing == Direction.NORTH) {
			return properties;
		}
		Map<String, String> out = new LinkedHashMap<>(properties);
		String rawFacing = out.get("facing");
		Direction propertyFacing = Direction.byName(rawFacing);
		if (propertyFacing != null && propertyFacing.getAxis().isHorizontal()) {
			int turns = switch (facing) {
				case EAST -> 1;
				case SOUTH -> 2;
				case WEST -> 3;
				default -> 0;
			};
			for (int i = 0; i < turns; i++) propertyFacing = propertyFacing.rotateYClockwise();
			out.put("facing", propertyFacing.asString());
		}
		if ((facing == Direction.EAST || facing == Direction.WEST)
				&& ("x".equals(out.get("axis")) || "z".equals(out.get("axis")))) {
			out.put("axis", "x".equals(out.get("axis")) ? "z" : "x");
		}
		return Map.copyOf(out);
	}

	/** 这一步在世界里覆盖的真实区域。 */
	public BoundedRegion region(BlockPos origin, Direction facing, int width, int depth) {
		BlockPos a = Blueprint.rotate(x1, y1, z1, facing, width, depth);
		BlockPos b = Blueprint.rotate(x2, y2, z2, facing, width, depth);
		return BoundedRegion.ofCorners(
			origin.getX() + a.getX(), origin.getY() + a.getY(), origin.getZ() + a.getZ(),
			origin.getX() + b.getX(), origin.getY() + b.getY(), origin.getZ() + b.getZ());
	}
}
