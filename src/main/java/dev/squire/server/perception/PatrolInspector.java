package dev.squire.server.perception;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.LightType;

/**
 * 巡逻到点时的一次巡检：这附近有什么该让主人知道的。
 *
 * <p>只报<b>三件玩家会真的去处理的事</b>：敌对生物、暗到会刷怪的地面、开着的门。
 * 报得再多就变成噪音——一个每四十 tick 说一次话的伙伴，说的第三句起就没人看了。</p>
 *
 * <p>纯读取：不改世界，不发消息，只把发现整理成一份报告交给调用方。所以它可以被
 * GameTest 直接调用验证，而不必等一整轮巡逻跑完。</p>
 */
public final class PatrolInspector {

	/** 巡检半径。比护卫半径小：这是「顺路看一眼」，不是搜索。 */
	public static final int RADIUS = 12;
	/** 低于这个光照的地面会刷怪。 */
	public static final int DARK_LIGHT_LEVEL = 8;
	/** 每类最多报几处，免得刷屏。 */
	private static final int MAX_PER_KIND = 3;
	/** 扫描地面时的采样步长——逐格扫 25×25 只是白花 getBlockState。 */
	private static final int GROUND_STEP = 3;

	/** 一次巡检的结果。空报告表示「这一带没事」，调用方据此决定要不要说话。 */
	public record Report(int hostiles, List<BlockPos> darkSpots, List<BlockPos> openDoors) {

		public Report {
			darkSpots = List.copyOf(darkSpots);
			openDoors = List.copyOf(openDoors);
		}

		public boolean anythingToSay() {
			return hostiles > 0 || !darkSpots.isEmpty() || !openDoors.isEmpty();
		}

		/** 给玩家看的一句话；没事时返回空串，调用方就该保持安静。 */
		public String describe() {
			if (!anythingToSay()) {
				return "";
			}
			StringBuilder text = new StringBuilder();
			if (hostiles > 0) {
				text.append("附近有 ").append(hostiles).append(" 只敌对生物");
			}
			if (!darkSpots.isEmpty()) {
				text.append(text.length() > 0 ? "；" : "")
					.append(darkSpots.size() >= MAX_PER_KIND ? "多处" : darkSpots.size() + " 处")
					.append("地面太暗（")
					.append(darkSpots.get(0).toShortString()).append(" 一带）");
			}
			if (!openDoors.isEmpty()) {
				text.append(text.length() > 0 ? "；" : "")
					.append(openDoors.size()).append(" 扇门开着（")
					.append(openDoors.get(0).toShortString()).append("）");
			}
			return text.append("。").toString();
		}
	}

	private PatrolInspector() {
	}

	public static Report inspect(ServerWorld world, BlockPos centre) {
		return inspect(world, centre, RADIUS);
	}

	public static Report inspect(ServerWorld world, BlockPos centre, int radius) {
		int hostiles = world.getEntitiesByClass(HostileEntity.class,
			Box.of(net.minecraft.util.math.Vec3d.ofCenter(centre), radius * 2.0,
				radius * 1.5, radius * 2.0),
			net.minecraft.entity.LivingEntity::isAlive).size();

		List<BlockPos> dark = new ArrayList<>();
		List<BlockPos> doors = new ArrayList<>();
		for (int dx = -radius; dx <= radius; dx += GROUND_STEP) {
			for (int dz = -radius; dz <= radius; dz += GROUND_STEP) {
				for (int dy = -3; dy <= 3; dy++) {
					BlockPos pos = centre.add(dx, dy, dz);
					if (dark.size() < MAX_PER_KIND && isSpawnableDark(world, pos)) {
						dark.add(pos.toImmutable());
					}
					if (doors.size() < MAX_PER_KIND && isOpenDoor(world.getBlockState(pos))) {
						doors.add(pos.toImmutable());
					}
				}
			}
		}
		return new Report(hostiles, dark, doors);
	}

	/** 站得住人、而且暗到会刷怪的一格。 */
	private static boolean isSpawnableDark(ServerWorld world, BlockPos pos) {
		if (!world.getBlockState(pos).isAir()) {
			return false;
		}
		if (world.getBlockState(pos.down()).getCollisionShape(world, pos.down())
				.isEmpty()) {
			return false; // 悬空，不是地面
		}
		return world.getLightLevel(LightType.BLOCK, pos) < DARK_LIGHT_LEVEL
			&& world.getLightLevel(LightType.SKY, pos) < DARK_LIGHT_LEVEL;
	}

	private static boolean isOpenDoor(BlockState state) {
		return state.getBlock() instanceof DoorBlock
			&& state.contains(DoorBlock.OPEN) && state.get(DoorBlock.OPEN)
			&& state.contains(DoorBlock.HALF)
			&& state.get(DoorBlock.HALF) == net.minecraft.block.enums.DoubleBlockHalf.LOWER;
	}
}
