package dev.squire.server.world;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.squire.server.blueprint.Blueprint;
import dev.squire.server.body.avatar.AvatarEntity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.SideShapeType;
import net.minecraft.item.BlockItem;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.LightType;

/**
 * 用<b>伙伴自己背包里的</b>火把把一片地方点亮。
 *
 * <p>抽出来是因为两条路径要用同一套规则：掘进工的「照明」能力（挖完顺手点上），
 * 和工程里的点灯阶段。两处各写一份的话，「悬空的火把会被相邻更新打掉」这种坑
 * 只会被修一次。</p>
 *
 * <p>三条硬规则：火把真的从背包扣（没有就不点，绝不凭空生成）、只放在
 * 「脚下踩得实、本身是空气」的格子上、每次有上限——点亮一个坑就够了，
 * 不是把它铺成灯带。</p>
 */
public final class Torchlight {

	public static final Identifier TORCH = new Identifier("minecraft:torch");
	/** 低于这个光照才值得插一根。 */
	public static final int DARK_ENOUGH = 8;
	/** Per-pass limit, NOT the whole project's material allowance. */
	public static final int PROJECT_MAX_TORCHES = 24;
	public static final int PROJECT_SPACING = 4;

	private Torchlight() {
	}

	/**
	 * 依次考察候选格子，按 {@code spacing} 抽稀，最多放 {@code max} 根。
	 *
	 * @return 真正放下去的火把数；背包里没有火把时是 0
	 */
	public static int lightUp(ServerWorld world, AvatarEntity avatar,
			Iterable<BlockPos> candidates, int spacing, int max, UndoJournal journal,
			UUID operationId, long tick) {
		return lightUp(world, candidates, spacing, max, journal, operationId, tick,
			() -> avatar != null && !avatar.items().extract(TORCH, 1).isEmpty());
	}

	/** Project variant: the caller supplies the atomic real-item debit. */
	public static int lightUp(ServerWorld world, Iterable<BlockPos> candidates,
			int spacing, int max, UndoJournal journal, UUID operationId, long tick,
			java.util.function.BooleanSupplier takeTorch) {
		return lightUp(world, candidates, spacing, max, journal, operationId, tick, takeTorch, false);
	}
	/** A planned lamp may already be lit by its neighbour while its assigned far corner is dark. */
	public static int lightUpPlanned(ServerWorld world, Iterable<BlockPos> candidates,
			int max, UndoJournal journal, UUID operationId, long tick,
			java.util.function.BooleanSupplier takeTorch) {
		return lightUp(world, candidates, 1, max, journal, operationId, tick, takeTorch, true);
	}
	private static int lightUp(ServerWorld world, Iterable<BlockPos> candidates,
			int spacing, int max, UndoJournal journal, UUID operationId, long tick,
			java.util.function.BooleanSupplier takeTorch, boolean planned) {
		if (world == null || takeTorch == null
				|| !(Registries.ITEM.get(TORCH) instanceof BlockItem torchItem)) {
			return 0;
		}
		int placed = 0;
		int suitableIndex = 0;
		for (BlockPos pos : candidates) {
			if (placed >= max) {
				break;
			}
			if (!(planned ? world.getBlockState(pos).isAir() && Blocks.TORCH.getDefaultState().canPlaceAt(world, pos) : suitable(world, pos))) {
				continue;
			}
			// 间距必须在“真正能插火把的格子”之间计算。石屋的负空间按从高到低
			// 排列时，第一个门洞格下方仍是空气；旧逻辑先跳格再判定，恰好会跳过
			// 下一格唯一可用的位置，随后任务永远卡在 VERIFYING。
			if (suitableIndex++ % Math.max(1, spacing) != 0) {
				continue;
			}
			BlockState before = world.getBlockState(pos);
			BlockState target = torchItem.getBlock().getDefaultState();
			if (!world.setBlockState(pos, target, Block.NOTIFY_ALL)) continue;
			if (!takeTorch.getAsBoolean()) {
				world.setBlockState(pos, before, Block.NOTIFY_ALL);
				break;
			}
			if (journal != null && operationId != null) {
				journal.record(new UndoJournal.Entry(operationId, operationId,
					pos.toImmutable(), before, null, target, tick));
			}
			placed++;
		}
		return placed;
	}

	/**
	 * 整份蓝图中可能需要照明的格子，优先从平面中心向外检查。
	 *
	 * <p>只读 {@code toClear()} 会漏掉由参数化房屋生成器留下的室内空气，因为那些
	 * 格子本来就是空气、无需专门写成“挖除”步骤。使用完整边界后，房间内部才会被
	 * 真正验光；中心优先也避免把第一根火把插在门口。</p>
	 */
	public static List<BlockPos> candidates(Blueprint.Resolved resolved) {
		if (resolved == null) {
			return List.of();
		}
		int centerX = (resolved.bounds().min().getX()
			+ resolved.bounds().max().getX()) / 2;
		int centerZ = (resolved.bounds().min().getZ()
			+ resolved.bounds().max().getZ()) / 2;
		List<BlockPos> out = new ArrayList<>();
		for (BlockPos pos : resolved.bounds().cells()) {
			out.add(pos.toImmutable());
		}
		out.sort(Comparator
			.comparingLong((BlockPos pos) -> {
				long dx = pos.getX() - centerX;
				long dz = pos.getZ() - centerZ;
				return dx * dx + dz * dz;
			})
			.thenComparingInt(BlockPos::getY));
		return List.copyOf(out);
	}

	/** 备料时预留的火把数量；真实点灯仍按世界光照决定实际消耗。 */
	public static int recommendedTorchCount(Blueprint.Resolved resolved) {
		if (resolved == null) {
			return 0;
		}
		long width = resolved.bounds().max().getX()
			- resolved.bounds().min().getX() + 1L;
		long depth = resolved.bounds().max().getZ()
			- resolved.bounds().min().getZ() + 1L;
		return (int) Math.max(1L, Math.min(24L, (width * depth + 35L) / 36L));
	}

	/**
	 * Safe upfront reservation derived from the blueprint's intended solid surfaces.
	 * It deliberately ignores current light because walls and floors may not exist yet.
	 */
	public static int projectTorchRequirement(ServerWorld world,
			Blueprint.Resolved resolved) {
		return projectSpots(world, resolved).size();
	}

	/** Remaining real torches after existing placed lights have changed block light. */
	public static int remainingProjectTorchRequirement(ServerWorld world,
			Blueprint.Resolved resolved) {
		if (world == null || resolved == null) return 0;
		if (brightEnough(world, candidates(resolved))) return 0;
		return (int) projectSpots(world, resolved).stream()
			.filter(pos -> world.getBlockState(pos).isAir() && Blocks.TORCH.getDefaultState().canPlaceAt(world, pos)).count();
	}

	/**
	 * Fixed, geometry-based lighting positions shared by the bill and the executor.
	 * Sampling every fourth dark cell can miss isolated rooms and spend more on retries
	 * than the upfront bill. Instead, greedily cover air paths within torch range. Walls
	 * stop coverage; floors on other levels get their own lights. Only air/torches transmit
	 * estimated light, making the estimate conservative for glass and other transparent blocks.
	 */
	public static List<BlockPos> projectSpots(ServerWorld world, Blueprint.Resolved resolved) {
		if (world == null || resolved == null) return List.of();
		Map<BlockPos, BlockState> planned = new HashMap<>();
		for (BlockPos pos : resolved.toClear()) planned.put(pos, Blocks.AIR.getDefaultState());
		for (Blueprint.Cell cell : resolved.toPlace()) {
			if (!cell.optional()) planned.put(cell.pos(),
				dev.squire.server.blueprint.BlueprintManager.targetState(cell));
		}
		java.util.function.Function<BlockPos, BlockState> stateAt = pos -> {
			BlockState state = planned.get(pos);
			if (state != null) return state;
			state = world.getBlockState(pos);
			// Auto lights must not change the geometry/ordering of the remaining plan.
			return state.isOf(Blocks.TORCH) ? Blocks.AIR.getDefaultState() : state;
		};
		List<BlockPos> cells = candidates(resolved);
		Set<BlockPos> air = new HashSet<>();
		for (BlockPos pos : cells) {
			BlockState state = stateAt.apply(pos);
			if (state.isAir() || state.isOf(Blocks.TORCH)) air.add(pos);
		}
		Set<BlockPos> covered = new HashSet<>();
		List<BlockPos> spots = new ArrayList<>();
		for (BlockPos pos : cells) {
			if (!air.contains(pos) || covered.contains(pos)
					|| !stateAt.apply(pos).isAir()) continue;
			BlockPos below = pos.down();
			// This is TorchBlock.canPlaceAt's exact UP-face support rule (not merely
			// a non-empty collision box, which incorrectly accepts bottom slabs).
			if (!stateAt.apply(below).isSideSolid(world, below, Direction.UP,
					SideShapeType.CENTER)) continue;
			spots.add(pos);
			ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
			Set<BlockPos> visited = new HashSet<>();
			frontier.add(pos);
			visited.add(pos);
			int range = Blocks.TORCH.getDefaultState().getLuminance() - DARK_ENOUGH;
			for (int distance = 0; distance <= range && !frontier.isEmpty(); distance++) {
				int count = frontier.size();
				for (int i = 0; i < count; i++) {
					BlockPos lit = frontier.removeFirst();
					covered.add(lit);
					if (distance == range) continue;
					for (Direction direction : Direction.values()) {
						BlockPos next = lit.offset(direction);
						if (air.contains(next) && visited.add(next)) frontier.addLast(next);
					}
				}
			}
		}
		return List.copyOf(spots);
	}

	/** 这份工程为了后续点灯还缺几根真实火把。 */
	public static int lightingDeficit(ServerWorld world, Blueprint.Resolved resolved,
			AvatarEntity avatar) {
		List<BlockPos> candidates = candidates(resolved);
		if (candidates.isEmpty() || brightEnough(world, candidates)) {
			return 0;
		}
		int carried = avatar == null ? 0 : avatar.items().countOf(TORCH);
		return Math.max(0, projectTorchRequirement(world, resolved) - carried);
	}

	/** 空气、脚下踩得实、而且确实暗。悬空的火把会被相邻更新打掉，白扣一根。 */
	public static boolean suitable(ServerWorld world, BlockPos pos) {
		return world.getBlockState(pos).isAir()
			&& Blocks.TORCH.getDefaultState().canPlaceAt(world, pos)
			&& world.getLightLevel(LightType.BLOCK, pos) < DARK_ENOUGH;
	}

	/** 这一片是不是已经够亮了——点灯阶段的验收条件。 */
	public static boolean brightEnough(ServerWorld world, Iterable<BlockPos> cells) {
		for (BlockPos pos : cells) {
			if (suitable(world, pos)) {
				return false; // 还有一处站得住人却是暗的
			}
		}
		return true;
	}
}
