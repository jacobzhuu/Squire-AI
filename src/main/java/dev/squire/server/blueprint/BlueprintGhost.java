package dev.squire.server.blueprint;

import java.util.ArrayList;
import java.util.List;

import dev.squire.server.world.BoundedRegion;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.joml.Vector3f;

/**
 * 幽灵预览：只用<b>粒子</b>，只发给<b>那一个玩家</b>。
 *
 * <p>另外两条路都被否决过，理由写在这里免得下一个人再试一遍：临时 barrier 或结构
 * 方块会真的改世界，撤销边界当场变脏；BlockDisplay 实体在 1.20.1 有，但一栋 7×7×4
 * 的壳约两百格就是两百个实体，而且不支持半透明。粒子走
 * {@code ServerWorld.spawnParticles(player, ...)}，零新封包、零客户端代码——这个
 * 模组的客户端只有一个 Screen 和一个 Renderer，预览不该打破这一点。</p>
 *
 * <p>从共享的 {@link Blueprint.Resolved} 里抽样实际建筑格，再补包围盒和要挖掉的
 * 负空间。抽样受总粒子预算约束，既能看出门窗和屋顶，也不会把屏幕糊成一片。</p>
 */
public final class BlueprintGhost {

	/** 每次最多发多少个粒子点——超过这个数玩家只会看到一团白雾。 */
	public static final int MAX_POINTS = 320;
	/** 多远之外就不画了。玩家跑远了还在发粒子只是白费带宽。 */
	public static final int VIEW_DISTANCE = 48;
	/** 每隔几 tick 画一次。 */
	public static final int INTERVAL_TICKS = 10;

	/** 未开工。 */
	private static final Vector3f WHITE = new Vector3f(0.95f, 0.95f, 0.95f);
	/** 材料齐了 / 已就位。 */
	private static final Vector3f GREEN = new Vector3f(0.30f, 0.95f, 0.35f);
	/** 缺料。 */
	private static final Vector3f RED = new Vector3f(0.95f, 0.25f, 0.25f);

	private BlueprintGhost() {
	}

	public static void drawAccess(ServerWorld world, ServerPlayerEntity viewer, ConstructionAccessPlan access) {
		if (access.temporaryCount() == 0 && access.excavationCount() == 0) return;
		var dust = new DustParticleEffect(new Vector3f(.25f, .65f, 1f), 1f);
		var digging = new DustParticleEffect(new Vector3f(1f, .55f, .15f), 1f);
		var positions = access.work.stream().filter(w -> w.temporary() || w.excavation()).toList();
		int stride = Math.max(1, (positions.size() + 95) / 96);
		for (int i = 0; i < positions.size(); i += stride) {
			BlockPos p = positions.get(i).cell().pos();
			if (viewer.getBlockPos().getSquaredDistance(p) <= VIEW_DISTANCE * VIEW_DISTANCE)
				world.spawnParticles(viewer, positions.get(i).excavation() ? digging : dust, true, p.getX() + .5, p.getY() + .5, p.getZ() + .5, 1, 0, 0, 0, 0);
		}
		var frame = outline(access.bounds);
		int frameStride = Math.max(1, (frame.size() + 47) / 48);
		for (int i = 0; i < frame.size(); i += frameStride) {
			BlockPos p = frame.get(i);
			if (viewer.getBlockPos().getSquaredDistance(p) <= VIEW_DISTANCE * VIEW_DISTANCE)
				world.spawnParticles(viewer, dust, true, p.getX() + .5, p.getY() + .5, p.getZ() + .5, 1, 0, 0, 0, 0);
		}
	}

	/** 轮廓的颜色语义，由调用方按材料账和摆放状态决定，而不是这里猜。 */
	public enum Tint { PENDING, READY, MISSING }

	/**
	 * 画一次。世界零改变。
	 *
	 * @return 实际发出去的粒子点数（0 表示玩家太远或不在同一维度）
	 */
	public static int draw(ServerWorld world, ServerPlayerEntity viewer,
			BoundedRegion bounds, List<BlockPos> toClear, Tint tint) {
		return draw(world, viewer, new Blueprint.Resolved(bounds, List.of(),
			toClear == null ? List.of() : toClear), tint);
	}

	/** Draw the actual final blueprint cells plus its bounds from the shared resolution. */
	public static int draw(ServerWorld world, ServerPlayerEntity viewer,
			Blueprint.Resolved resolved, Tint tint) {
		if (resolved == null) return 0;
		if (viewer != null && world != null && TerrainLeveling.isTerrain(resolved)) return TerrainLeveling.draw(world, viewer, resolved);
		BoundedRegion bounds = resolved.bounds();
		if (viewer == null || world == null || viewer.getWorld() != world) {
			return 0;
		}
		BlockPos centre = new BlockPos(
			(bounds.min().getX() + bounds.max().getX()) / 2,
			(bounds.min().getY() + bounds.max().getY()) / 2,
			(bounds.min().getZ() + bounds.max().getZ()) / 2);
		if (viewer.getBlockPos().getSquaredDistance(centre)
				> (double) VIEW_DISTANCE * VIEW_DISTANCE) {
			return 0;
		}
		DustParticleEffect dust = new DustParticleEffect(switch (tint) {
			case READY -> GREEN;
			case MISSING -> RED;
			default -> WHITE;
		}, 1.0f);

		List<BlockPos> points = new ArrayList<>();
		for (Blueprint.Cell cell : resolved.toPlace()) points.add(cell.pos());
		int clearBudget = resolved.toClear().isEmpty() ? 0 : MAX_POINTS / 6;
		int structureBudget = MAX_POINTS * 2 / 3;
		int step = Math.max(1, (points.size() + structureBudget - 1) / structureBudget);
		int sent = 0;
		for (int i = 0; i < points.size(); i += step) {
			BlockPos p = points.get(i);
			world.spawnParticles(viewer, dust, true,
				p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, 1, 0, 0, 0, 0);
			sent++;
		}
		List<BlockPos> frame = outline(bounds);
		int frameBudget = Math.max(1, MAX_POINTS - sent - clearBudget);
		int frameStep = Math.max(1, (frame.size() + frameBudget - 1) / frameBudget);
		for (int i = 0; i < frame.size() && sent < MAX_POINTS; i += frameStep) {
			BlockPos p = frame.get(i);
			world.spawnParticles(viewer, dust, true,
				p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, 1, 0, 0, 0, 0);
			sent++;
		}
		// 要挖掉的地方单独用烟：玩家一眼看出「这里会被掏空」，和放置的轮廓不混淆。
		if (!resolved.toClear().isEmpty() && sent < MAX_POINTS) {
			int remaining = MAX_POINTS - sent;
			int digStep = Math.max(1,
				(resolved.toClear().size() + remaining - 1) / remaining);
			for (int i = 0; i < resolved.toClear().size() && sent < MAX_POINTS;
					i += digStep) {
				BlockPos p = resolved.toClear().get(i);
				world.spawnParticles(viewer, ParticleTypes.SMOKE, true,
					p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, 1, 0, 0, 0, 0);
				sent++;
			}
		}
		return sent;
	}

	/**
	 * 包围盒的十二条棱，外加地面那一圈（棱里本来就有底面四条，这里额外把地面轮廓
	 * 采得密一点，因为玩家最先要判断的是「占了我这块地的哪一片」）。
	 */
	public static List<BlockPos> outline(BoundedRegion bounds) {
		BlockPos min = bounds.min();
		BlockPos max = bounds.max();
		List<BlockPos> out = new ArrayList<>();
		for (int y : new int[] {min.getY(), max.getY()}) {
			for (int x = min.getX(); x <= max.getX(); x++) {
				out.add(new BlockPos(x, y, min.getZ()));
				out.add(new BlockPos(x, y, max.getZ()));
			}
			for (int z = min.getZ() + 1; z < max.getZ(); z++) {
				out.add(new BlockPos(min.getX(), y, z));
				out.add(new BlockPos(max.getX(), y, z));
			}
		}
		for (int y = min.getY() + 1; y < max.getY(); y++) {
			out.add(new BlockPos(min.getX(), y, min.getZ()));
			out.add(new BlockPos(max.getX(), y, min.getZ()));
			out.add(new BlockPos(min.getX(), y, max.getZ()));
			out.add(new BlockPos(max.getX(), y, max.getZ()));
		}
		return out;
	}
}
