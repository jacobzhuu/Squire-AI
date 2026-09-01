package dev.squire.server.task.executors;

import dev.squire.api.body.TargetPosition;
import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.body.avatar.AvatarInventory;
import dev.squire.server.body.proxy.FakePlayerInteractionProxy;
import dev.squire.server.task.Task;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * Single-block world actions exposed to the model (break / place). Both are
 * proximity-gated: the avatar must stand within reach, so remote editing through a
 * model call is impossible (spec section 37).
 */
public final class OneShotWorldExecutors {

	private static final double MAX_REACH_SQ = 25.0;

	private OneShotWorldExecutors() {
	}


	private static BlockPos posOf(Task task) {
		Object x = task.parameters().get("x");
		Object y = task.parameters().get("y");
		Object z = task.parameters().get("z");
		if (!(x instanceof Number xn) || !(y instanceof Number yn) || !(z instanceof Number zn)) {
			return null;
		}
		return new BlockPos(xn.intValue(), yn.intValue(), zn.intValue());
	}

	private static boolean inReach(AvatarEntity avatar, BlockPos pos) {
		return avatar.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5,
			pos.getZ() + 0.5) <= MAX_REACH_SQ;
	}

	// —— 已停用（方向性收缩：真身只保留跟随/护卫/救援，取物改走指令兑现）——
	// 保留代码而非删除，方便日后回退。当前无任何调用方。
//	/** Break one block; drops go into the avatar inventory. */
//	public static final class BreakOne implements dev.squire.server.task.TaskExecutor {
//		private final RuntimeServices services;
//
//		public BreakOne(RuntimeServices services) {
//			this.services = services;
//		}
//
//		@Override
//		public String type() {
//			return "world.break";
//		}
//
//		@Override
//		public void start(Task task) {
//		}
//
//		@Override
//		public StepOutcome tick(Task task, long tick) {
//			AvatarEntity avatar = services.avatar(task.agentId());
//			BlockPos pos = posOf(task);
//			if (avatar == null || !avatar.isAlive() || pos == null) {
//				task.setLastErrorCode("INVALID_ARGUMENT");
//				return StepOutcome.FAILED;
//			}
//			if (!inReach(avatar, pos)) {
//				task.setLastErrorCode("UNREACHABLE");
//				return StepOutcome.FAILED;
//			}
//			ServerWorld world = (ServerWorld) avatar.getWorld();
//			// 方案 C5：单块破坏与批量编辑走同一个保护判定
//			var decision = services.protection().canBreak(world, pos, avatar.ownerId());
//			if (!decision.allowed()) {
//				task.setLastErrorCode("PROTECTED_REGION");
//				return StepOutcome.FAILED;
//			}
//			AvatarInventory items = avatar.items();
//			AvatarInventory.SlotRef toolSlot =
//				items.bestToolSlot(world.getBlockState(pos));
//			ItemStack tool = items.stackAt(toolSlot);
//			var result = FakePlayerInteractionProxy.breakAndCollect(world, pos, tool,
//				used -> {
//				});
//			items.writeBackTool(toolSlot, tool); // 耐久写回真实槽位（方案 C2）
//			if (!result.broken()) {
//				task.setLastErrorCode("BLOCK_NOT_FOUND");
//				return StepOutcome.FAILED;
//			}
//			for (ItemStack drop : result.drops()) {
//				ItemStack leftover = items.insert(drop);
//				if (!leftover.isEmpty()) {
//					net.minecraft.block.Block.dropStack(world, pos, leftover);
//				}
//			}
//			task.setExecutionState(pos);
//			return StepOutcome.WORK_DONE;
//		}
//
//		@Override
//		public void cancel(Task task) {
//		}
//	}

	// —— 已停用（方向性收缩：真身只保留跟随/护卫/救援，取物改走指令兑现）——
	// 保留代码而非删除，方便日后回退。当前无任何调用方。
//	/** Place one block from the avatar inventory. */
//	public static final class PlaceOne implements dev.squire.server.task.TaskExecutor {
//		private final RuntimeServices services;
//
//		public PlaceOne(RuntimeServices services) {
//			this.services = services;
//		}
//
//		@Override
//		public String type() {
//			return "world.place";
//		}
//
//		@Override
//		public void start(Task task) {
//		}
//
//		@Override
//		public StepOutcome tick(Task task, long tick) {
//			AvatarEntity avatar = services.avatar(task.agentId());
//			BlockPos pos = posOf(task);
//			String itemId = task.stringParam("itemId");
//			if (avatar == null || !avatar.isAlive() || pos == null || itemId == null) {
//				task.setLastErrorCode("INVALID_ARGUMENT");
//				return StepOutcome.FAILED;
//			}
//			if (!inReach(avatar, pos)) {
//				task.setLastErrorCode("UNREACHABLE");
//				return StepOutcome.FAILED;
//			}
//			Identifier id = parseId(itemId);
//			if (!avatar.hasAtLeast(id, 1)) {
//				task.setLastErrorCode("INSUFFICIENT_ITEM");
//				return StepOutcome.FAILED;
//			}
//			ServerWorld world = (ServerWorld) avatar.getWorld();
//			var decision = services.protection().canPlace(world, pos, avatar.ownerId());
//			if (!decision.allowed()) {
//				task.setLastErrorCode("PROTECTED_REGION");
//				return StepOutcome.FAILED;
//			}
//			ItemStack held = avatar.extractItems(id, 1).stream().findFirst().orElse(null);
//			if (held == null) {
//				task.setLastErrorCode("INSUFFICIENT_ITEM");
//				return StepOutcome.FAILED;
//			}
//			var result = FakePlayerInteractionProxy.placeBlock(world, pos, held);
//			if (!result.placed()) {
//				avatar.insertStack(held); // give the block back
//				task.setLastErrorCode("PRECONDITION_FAILED");
//				return StepOutcome.FAILED;
//			}
//			if (result.remaining() != null && !result.remaining().isEmpty()) {
//				avatar.insertStack(result.remaining());
//			}
//			task.setExecutionState(pos);
//			return StepOutcome.WORK_DONE;
//		}
//
//		@Override
//		public void cancel(Task task) {
//		}
//	}

	/** Goal condition factory: avatar stands within reach of the block cell. */
	public static dev.squire.server.task.TaskCondition nearBlock(double x, double y, double z) {
		return MoveToExecutor.nearTarget(x, y, z, MAX_REACH_SQ);
	}

	/** Public id parser for handlers outside this package. */
	public static Identifier itemId(String raw) {
		return parseId(raw);
	}

	public static Identifier parseId(String raw) {
		int colon = raw.indexOf(':');
		return colon < 0 ? new Identifier(raw) : new Identifier(raw.substring(0, colon),
			raw.substring(colon + 1));
	}
}