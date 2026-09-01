package dev.squire.server.task.executors;

import java.util.ArrayList;
import java.util.List;

import dev.squire.api.body.MoveHandle;
import dev.squire.api.body.MoveOptions;
import dev.squire.api.body.TargetPosition;
import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.body.avatar.AvatarInventory;
import dev.squire.server.task.Task;
import dev.squire.server.task.TaskCondition;
import dev.squire.server.world.ContainerAccess;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * 容器物流 Executor（方案 C3）：deposit / withdraw / transfer / sort / pickup_nearby。
 *
 * <p>每个任务先导航到容器（超出交互距离时），再在服务器线程内用
 * {@link ContainerAccess} 做两阶段事务：先算可移动量，再同时提交源与目标；任何
 * 异常都整体回滚。容器位置由上层给定（可见目标、玩家明确坐标或可信位置记忆），
 * 到达后必须重新验证类型、距离、权限和容量。</p>
 */
public final class ContainerExecutors {

	public static final String PARAM_X = "x";
	public static final String PARAM_Y = "y";
	public static final String PARAM_Z = "z";
	public static final String PARAM_ITEM_ID = "itemId";
	public static final String PARAM_COUNT = "count";
	/** 目标容器（transfer 用）。 */
	public static final String PARAM_TO_X = "toX";
	public static final String PARAM_TO_Y = "toY";
	public static final String PARAM_TO_Z = "toZ";
	/** deposit 的筛选模式：ALL / ITEM / ORES。 */
	public static final String PARAM_FILTER = "filter";

	private ContainerExecutors() {
	}

	// ------------------------------------------------------------------ 共享脚手架

	/** NAVIGATE→ACT 两阶段进度。 */
	/** 公开是为了让任务终态上的熟练度记账能读到 {@code moved}。 */
	public record Progress(boolean navigating, MoveHandle handle, int moved) {
	}

	private static BlockPos posOf(Task task, String kx, String ky, String kz) {
		Object x = task.parameters().get(kx);
		Object y = task.parameters().get(ky);
		Object z = task.parameters().get(kz);
		if (!(x instanceof Number xn) || !(y instanceof Number yn)
				|| !(z instanceof Number zn)) {
			return null;
		}
		return new BlockPos(xn.intValue(), yn.intValue(), zn.intValue());
	}

	static Identifier parseId(String raw) {
		int colon = raw.indexOf(':');
		return colon < 0 ? new Identifier(raw) : new Identifier(raw.substring(0, colon),
			raw.substring(colon + 1));
	}

	/** 矿物筛选（"把矿放回仓库"）：矿石、原矿和锭。 */
	static boolean isOre(ItemStack stack) {
		return ContainerAccess.categoryOf(stack) == ContainerAccess.Category.ORE_AND_INGOT;
	}

	/** 任务参数 → stack 谓词。null itemId 且 filter=ALL 表示"全部"。 */
	static java.util.function.Predicate<ItemStack> filterOf(Task task) {
		String itemId = task.stringParam(PARAM_ITEM_ID);
		if (itemId != null) {
			var item = Registries.ITEM.get(parseId(itemId));
			return stack -> stack.getItem() == item;
		}
		String filter = task.stringParam(PARAM_FILTER);
		if ("ORES".equalsIgnoreCase(filter)) {
			return ContainerExecutors::isOre;
		}
		return stack -> true;
	}

	/**
	 * Shared base: walk within interaction range of the container, then run {@link #act}.
	 * Failure codes always come from {@link ContainerAccess}, never from ad-hoc strings.
	 */
	private abstract static class ContainerTaskBase
			implements dev.squire.server.task.TaskExecutor {

		protected final RuntimeServices services;

		ContainerTaskBase(RuntimeServices services) {
			this.services = services;
		}

		@Override
		public void start(Task task) {
			if (posOf(task, PARAM_X, PARAM_Y, PARAM_Z) == null) {
				task.setLastErrorCode("INVALID_ARGUMENT");
				throw new IllegalStateException(type() + " needs container x/y/z");
			}
			task.setExecutionState(new Progress(false, null, 0));
		}

		@Override
		public StepOutcome tick(Task task, long tick) {
			AvatarEntity avatar = services.avatar(task.agentId());
			if (avatar == null || !avatar.isAlive()) {
				task.setLastErrorCode("ENTITY_NOT_FOUND");
				return StepOutcome.FAILED;
			}
			BlockPos pos = posOf(task, PARAM_X, PARAM_Y, PARAM_Z);
			ServerWorld world = (ServerWorld) avatar.getWorld();
			Progress progress = task.executionState() instanceof Progress p ? p
				: new Progress(false, null, 0);

			double distSq = avatar.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5,
				pos.getZ() + 0.5);
			if (distSq > ContainerAccess.MAX_INTERACT_DISTANCE_SQ) {
				return navigate(task, avatar, pos, progress);
			}
			if (progress.navigating()) {
				avatar.stopMoving();
				task.setExecutionState(new Progress(false, null, progress.moved()));
			}
			return act(task, avatar, world, pos);
		}

		private StepOutcome navigate(Task task, AvatarEntity avatar, BlockPos pos,
				Progress progress) {
			MoveHandle handle = progress.handle();
			if (handle != null && handle.state() == MoveHandle.State.FAILED) {
				task.setLastErrorCode(avatar.lastMoveErrorCode() == null
					? "UNREACHABLE" : avatar.lastMoveErrorCode());
				return StepOutcome.FAILED;
			}
			if (handle == null || handle.state() == MoveHandle.State.CANCELLED
					|| handle.state() == MoveHandle.State.ARRIVED) {
				BlockPos stand = GatherBlockExecutor.standableAdjacent(avatar, pos);
				BlockPos target = stand == null ? pos : stand;
				MoveHandle started = avatar.moveTo(new TargetPosition(
					avatar.getWorld().getRegistryKey().getValue().toString(),
					target.getX() + 0.5, target.getY(), target.getZ() + 0.5),
					MoveOptions.WALK);
				if (started.state() == MoveHandle.State.FAILED) {
					task.setLastErrorCode("PATH_NOT_FOUND");
					return StepOutcome.FAILED;
				}
				task.setExecutionState(new Progress(true, started, progress.moved()));
			}
			return StepOutcome.CONTINUE;
		}

		/** Run the actual container work; the avatar is already within reach. */
		protected abstract StepOutcome act(Task task, AvatarEntity avatar,
			ServerWorld world, BlockPos pos);

		@Override
		public void cancel(Task task) {
			if (task.executionState() instanceof Progress progress
					&& progress.handle() != null) {
				progress.handle().cancel();
			}
			AvatarEntity avatar = services.avatar(task.agentId());
			if (avatar != null) {
				avatar.stopMoving();
			}
		}

		/** Resolve + validate; returns null after setting the task error code. */
		protected Inventory open(Task task, AvatarEntity avatar, ServerWorld world,
				BlockPos pos, boolean write) {
			ContainerAccess.Resolved resolved = ContainerAccess.resolve(world, pos,
				avatar.getPos(), avatar.ownerId(), services.protection(), write);
			if (!resolved.ok()) {
				task.setLastErrorCode(resolved.errorCode());
				return null;
			}
			return resolved.inventory();
		}
	}

	// ------------------------------------------------------------------ deposit

	/** Avatar 背包 → 容器。 */
	public static final class Deposit extends ContainerTaskBase {
		public static final String TYPE = "container.deposit";

		public Deposit(RuntimeServices services) {
			super(services);
		}

		@Override
		public String type() {
			return TYPE;
		}

		@Override
		protected StepOutcome act(Task task, AvatarEntity avatar, ServerWorld world,
				BlockPos pos) {
			Inventory container = open(task, avatar, world, pos, true);
			if (container == null) {
				return StepOutcome.FAILED;
			}
			AvatarInventory items = avatar.items();
			var filter = filterOf(task);
			Integer requested = task.intParam(PARAM_COUNT);
			int wanted = requested == null ? Integer.MAX_VALUE : requested;
			if (wanted <= 0) {
				return StepOutcome.WORK_DONE;
			}

			// 阶段 1：只计算可移动量，不改任何状态
			List<ItemStack> candidates = new ArrayList<>();
			int planned = 0;
			for (int slot = 0; slot < items.size() && planned < wanted; slot++) {
				ItemStack stack = items.getStack(slot);
				if (stack.isEmpty() || !filter.test(stack)) {
					continue;
				}
				ItemStack portion = stack.copy();
				portion.setCount(Math.min(stack.getCount(), wanted - planned));
				int fits = ContainerAccess.insertableAmount(container, portion);
				if (fits <= 0) {
					continue;
				}
				portion.setCount(fits);
				candidates.add(portion);
				planned += fits;
			}
			if (planned == 0) {
				task.setLastErrorCode(items.countMatching(filter) == 0
					? "INSUFFICIENT_ITEM" : "INVENTORY_FULL");
				return StepOutcome.FAILED;
			}

			// 阶段 2：两侧一起提交，异常整体回滚
			var avatarBefore = items.snapshot();
			var containerBefore = ContainerAccess.snapshot(container);
			try {
				int moved = 0;
				for (ItemStack portion : candidates) {
					List<ItemStack> taken = items.extractMatching(
						s -> ItemStack.canCombine(s, portion), portion.getCount());
					for (ItemStack stack : taken) {
						ItemStack leftover = ContainerAccess.insert(container, stack);
						moved += stack.getCount() - leftover.getCount();
						if (!leftover.isEmpty()) {
							items.insert(leftover); // 容器在两阶段之间被改小了
						}
					}
				}
				task.setExecutionState(new Progress(false, null, moved));
				task.setWorkReport(new Task.WorkReport(
					dev.squire.server.profile.Track.LOGISTICS.id(), moved, null));
				return moved > 0 ? StepOutcome.WORK_DONE : StepOutcome.FAILED;
			} catch (RuntimeException e) {
				items.restore(avatarBefore);
				ContainerAccess.restore(container, containerBefore);
				task.setLastErrorCode("INTERNAL_ERROR");
				return StepOutcome.FAILED;
			}
		}

	}

	// ------------------------------------------------------------------ withdraw

	/** 容器 → Avatar 背包。 */
	public static final class Withdraw extends ContainerTaskBase {
		public static final String TYPE = "container.withdraw";

		public Withdraw(RuntimeServices services) {
			super(services);
		}

		@Override
		public String type() {
			return TYPE;
		}

		@Override
		protected StepOutcome act(Task task, AvatarEntity avatar, ServerWorld world,
				BlockPos pos) {
			Inventory container = open(task, avatar, world, pos, true);
			if (container == null) {
				return StepOutcome.FAILED;
			}
			AvatarInventory items = avatar.items();
			var filter = filterOf(task);
			Integer requested = task.intParam(PARAM_COUNT);
			int wanted = requested == null ? Integer.MAX_VALUE : requested;
			int available = ContainerAccess.count(container, filter);
			if (available <= 0) {
				task.setLastErrorCode("INSUFFICIENT_ITEM");
				return StepOutcome.FAILED;
			}
			var avatarBefore = items.snapshot();
			var containerBefore = ContainerAccess.snapshot(container);
			try {
				List<ItemStack> taken = ContainerAccess.extract(container, filter,
					Math.min(wanted, available));
				int moved = 0;
				for (ItemStack stack : taken) {
					ItemStack leftover = items.insert(stack);
					moved += stack.getCount() - leftover.getCount();
					if (!leftover.isEmpty()) {
						ContainerAccess.insert(container, leftover); // 背包满则放回
					}
				}
				task.setExecutionState(new Progress(false, null, moved));
				task.setWorkReport(new Task.WorkReport(
					dev.squire.server.profile.Track.LOGISTICS.id(), moved, null));
				if (moved == 0) {
					task.setLastErrorCode("INVENTORY_FULL");
					return StepOutcome.FAILED;
				}
				return StepOutcome.WORK_DONE;
			} catch (RuntimeException e) {
				items.restore(avatarBefore);
				ContainerAccess.restore(container, containerBefore);
				task.setLastErrorCode("INTERNAL_ERROR");
				return StepOutcome.FAILED;
			}
		}
	}

	// ------------------------------------------------------------------ transfer

	/** 容器 A → 容器 B，两端都必须在交互距离内。 */
	public static final class Transfer extends ContainerTaskBase {
		public static final String TYPE = "container.transfer";

		public Transfer(RuntimeServices services) {
			super(services);
		}

		@Override
		public String type() {
			return TYPE;
		}

		@Override
		protected StepOutcome act(Task task, AvatarEntity avatar, ServerWorld world,
				BlockPos pos) {
			BlockPos toPos = posOf(task, PARAM_TO_X, PARAM_TO_Y, PARAM_TO_Z);
			if (toPos == null) {
				task.setLastErrorCode("INVALID_ARGUMENT");
				return StepOutcome.FAILED;
			}
			Inventory source = open(task, avatar, world, pos, true);
			if (source == null) {
				return StepOutcome.FAILED;
			}
			ContainerAccess.Resolved target = ContainerAccess.resolve(world, toPos,
				avatar.getPos(), avatar.ownerId(), services.protection(), true);
			if (!target.ok()) {
				task.setLastErrorCode(target.errorCode());
				return StepOutcome.FAILED;
			}
			var filter = filterOf(task);
			Integer requested = task.intParam(PARAM_COUNT);
			int wanted = requested == null ? Integer.MAX_VALUE : requested;
			int available = ContainerAccess.count(source, filter);
			if (available <= 0) {
				task.setLastErrorCode("INSUFFICIENT_ITEM");
				return StepOutcome.FAILED;
			}
			var sourceBefore = ContainerAccess.snapshot(source);
			var targetBefore = ContainerAccess.snapshot(target.inventory());
			try {
				List<ItemStack> taken = ContainerAccess.extract(source, filter,
					Math.min(wanted, available));
				int moved = 0;
				for (ItemStack stack : taken) {
					ItemStack leftover = ContainerAccess.insert(target.inventory(), stack);
					moved += stack.getCount() - leftover.getCount();
					if (!leftover.isEmpty()) {
						ContainerAccess.insert(source, leftover);
					}
				}
				task.setExecutionState(new Progress(false, null, moved));
				task.setWorkReport(new Task.WorkReport(
					dev.squire.server.profile.Track.LOGISTICS.id(), moved, null));
				if (moved == 0) {
					task.setLastErrorCode("INVENTORY_FULL");
					return StepOutcome.FAILED;
				}
				return StepOutcome.WORK_DONE;
			} catch (RuntimeException e) {
				ContainerAccess.restore(source, sourceBefore);
				ContainerAccess.restore(target.inventory(), targetBefore);
				task.setLastErrorCode("INTERNAL_ERROR");
				return StepOutcome.FAILED;
			}
		}
	}

	// ------------------------------------------------------------------ sort

	/** 就地整理一个箱式容器；总量守恒，NBT 保留，未知物品不删除。 */
	public static final class Sort extends ContainerTaskBase {
		public static final String TYPE = "container.sort";

		public Sort(RuntimeServices services) {
			super(services);
		}

		@Override
		public String type() {
			return TYPE;
		}

		@Override
		protected StepOutcome act(Task task, AvatarEntity avatar, ServerWorld world,
				BlockPos pos) {
			Inventory container = open(task, avatar, world, pos, true);
			if (container == null) {
				return StepOutcome.FAILED;
			}
			if (!ContainerAccess.isSortable(world, pos)) {
				// 熔炉/漏斗等有槽位语义的容器不整理，避免把燃料塞进产物槽
				task.setLastErrorCode("NOT_A_CONTAINER");
				return StepOutcome.FAILED;
			}
			String error = ContainerAccess.sort(container);
			if (error != null) {
				task.setLastErrorCode(error);
				return StepOutcome.FAILED;
			}
			return StepOutcome.WORK_DONE;
		}
	}

	// ------------------------------------------------------------------ pickup nearby

	/**
	 * 捡起附近的掉落物（方案 C2：掉落物通过真实拾取进入背包；背包满时保留世界掉落）。
	 */
	public static final class PickupNearby implements dev.squire.server.task.TaskExecutor {
		public static final String TYPE = "inventory.pickup_nearby";
		public static final String PARAM_RADIUS = "radius";
		private static final double DEFAULT_RADIUS = 6.0;
		private static final double MAX_RADIUS = 16.0;

		private final RuntimeServices services;

		public PickupNearby(RuntimeServices services) {
			this.services = services;
		}

		@Override
		public String type() {
			return TYPE;
		}

		@Override
		public void start(Task task) {
		}

		@Override
		public StepOutcome tick(Task task, long tick) {
			AvatarEntity avatar = services.avatar(task.agentId());
			if (avatar == null || !avatar.isAlive()) {
				task.setLastErrorCode("ENTITY_NOT_FOUND");
				return StepOutcome.FAILED;
			}
			Integer radiusParam = task.intParam(PARAM_RADIUS);
			double radius = Math.min(MAX_RADIUS,
				radiusParam == null ? DEFAULT_RADIUS : radiusParam);
			Result result = sweep(avatar, radius);
			task.setExecutionState(result.picked());
			if (result.picked() == 0 && result.blockedByFullInventory()) {
				task.setLastErrorCode("INVENTORY_FULL");
				return StepOutcome.FAILED;
			}
			return StepOutcome.WORK_DONE;
		}

		/** @param picked 实际收进背包的物品数量 */
		public record Result(int picked, boolean blockedByFullInventory) { }

		/**
		 * 把 {@code radius} 内的掉落物收进伙伴背包。
		 *
		 * <p>抽成静态方法是为了让「每 tick 的自动捡拾」和「显式的捡拾任务」共用同一套
		 * 语义——尤其是背包满时<b>把物品留在世界里而不是销毁</b>这条。两份实现迟早
		 * 会分叉，而分叉的那一天玩家的东西就没了。</p>
		 */
		public static Result sweep(AvatarEntity avatar, double radius) {
			ServerWorld world = (ServerWorld) avatar.getWorld();
			var box = avatar.getBoundingBox().expand(radius);
			AvatarInventory items = avatar.items();
			int picked = 0;
			boolean full = false;
			for (var entity : world.getEntitiesByClass(
					net.minecraft.entity.ItemEntity.class, box,
					e -> e.isAlive() && !e.cannotPickup()
						&& !avatar.recentlyHandedOff(e.getUuid()))) {
				ItemStack stack = entity.getStack();
				ItemStack leftover = items.insert(stack);
				picked += stack.getCount() - leftover.getCount();
				if (leftover.isEmpty()) {
					entity.discard();
				} else {
					// 背包满：物品留在世界里，不销毁
					entity.setStack(leftover);
					full = true;
				}
			}
			return new Result(picked, full);
		}

		@Override
		public void cancel(Task task) {
		}
	}

	// ------------------------------------------------------------------ 目标验证

	/**
	 * 目标校验只看真实世界状态（方案 C3/E3）。这些工厂在提交任务时捕获基线，
	 * Goal Verifier 之后拿最终容器/背包内容对比——Tool 返回 SUCCESS 从不算数。
	 */
	public static TaskCondition containerHoldsAtLeast(RuntimeServices services,
			String dimension, BlockPos pos, java.util.function.Predicate<ItemStack> filter,
			String describe, int atLeast) {
		return TaskCondition.of(ctx -> {
			Inventory inventory = peek(services, dimension, pos);
			return inventory != null && ContainerAccess.count(inventory, filter) >= atLeast;
		}, "container at " + pos.toShortString() + " holds at least " + atLeast + " "
			+ describe);
	}

	public static TaskCondition containerHoldsAtMost(RuntimeServices services,
			String dimension, BlockPos pos, java.util.function.Predicate<ItemStack> filter,
			String describe, int atMost) {
		return TaskCondition.of(ctx -> {
			Inventory inventory = peek(services, dimension, pos);
			return inventory != null && ContainerAccess.count(inventory, filter) <= atMost;
		}, "container at " + pos.toShortString() + " holds at most " + atMost + " "
			+ describe);
	}

	public static TaskCondition avatarHoldsAtMost(RuntimeServices services,
			java.util.UUID agentId, java.util.function.Predicate<ItemStack> filter,
			String describe, int atMost) {
		return TaskCondition.of(ctx -> {
			AvatarEntity avatar = services.avatar(agentId);
			return avatar != null && avatar.items().countMatching(filter) <= atMost;
		}, "agent carries at most " + atMost + " " + describe);
	}

	public static TaskCondition avatarHoldsAtLeast(RuntimeServices services,
			java.util.UUID agentId, java.util.function.Predicate<ItemStack> filter,
			String describe, int atLeast) {
		return TaskCondition.of(ctx -> {
			AvatarEntity avatar = services.avatar(agentId);
			return avatar != null && avatar.items().countMatching(filter) >= atLeast;
		}, "agent carries at least " + atLeast + " " + describe);
	}

	/** Sort verification: nothing gained or lost, and the stacks really got denser. */
	public static TaskCondition sortedWithoutLoss(RuntimeServices services,
			String dimension, BlockPos pos, java.util.Map<String, Integer> expected,
			int maxOccupiedSlots) {
		return TaskCondition.of(ctx -> {
			Inventory inventory = peek(services, dimension, pos);
			if (inventory == null || !ContainerAccess.contents(inventory).equals(expected)) {
				return false;
			}
			int occupied = inventory.size() - ContainerAccess.freeSlots(inventory);
			return occupied <= maxOccupiedSlots;
		}, "container at " + pos.toShortString() + " keeps every item and is tidy");
	}

	/** Pickup verification: the drops are gone from the ground around the agent. */
	public static TaskCondition noDropsNearby(RuntimeServices services,
			java.util.UUID agentId, double radius) {
		return TaskCondition.of(ctx -> {
			AvatarEntity avatar = services.avatar(agentId);
			if (avatar == null) {
				return false;
			}
			return ((ServerWorld) avatar.getWorld()).getEntitiesByClass(
				net.minecraft.entity.ItemEntity.class,
				avatar.getBoundingBox().expand(radius),
				e -> e.isAlive() && !e.cannotPickup()).isEmpty();
		}, "no loose drops within " + radius + " blocks");
	}

	private static Inventory peek(RuntimeServices services, String dimension, BlockPos pos) {
		ServerWorld world = worldOf(services, dimension);
		if (world == null) {
			return null;
		}
		var resolved = ContainerAccess.resolve(world, pos, null, null,
			services.protection(), false);
		return resolved.ok() ? resolved.inventory() : null;
	}

	/** Registry-backed predicate for a single item id (null id = match everything). */
	public static java.util.function.Predicate<ItemStack> itemFilter(String itemId,
			String filterMode) {
		if (itemId != null) {
			var item = Registries.ITEM.get(parseId(itemId));
			return stack -> stack.getItem() == item;
		}
		if ("ORES".equalsIgnoreCase(filterMode)) {
			return ContainerExecutors::isOre;
		}
		return stack -> true;
	}

	// ------------------------------------------------------------------ helpers

	static ServerWorld worldOf(RuntimeServices services, String dimension) {
		for (ServerWorld world : services.server().getWorlds()) {
			if (world.getRegistryKey().getValue().toString().equals(dimension)) {
				return world;
			}
		}
		return null;
	}
}
