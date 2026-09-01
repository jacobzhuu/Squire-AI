package dev.squire.server.task.executors;

import java.util.List;

import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.body.avatar.AvatarInventory;
import dev.squire.server.profile.Ability;
import dev.squire.server.profile.AutonomyLevel;
import dev.squire.server.profile.SquireProfile;
import dev.squire.server.profile.Trait;
import dev.squire.server.task.Task;
import dev.squire.server.task.TaskCondition;
import dev.squire.server.world.ContainerAccess;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * 回到基地闲下来之后的例行公事：换上更好的装备，把余料收进箱子。
 *
 * <p><b>业务不写在 Goal 里。</b>{@code HomeRoutineGoal} 只负责判断「他现在闲着、
 * 而且在家门口」，然后提交一条最低优先级的任务；真正做事在这里。这样它照样会被
 * 玩家下的任何指令抢占、照样有超时、照样进任务日志——一件伙伴自己发起的事，
 * 和玩家发起的事走同一条管道，才谈得上「可预测」。</p>
 *
 * <h2>刻意没做的：修装备</h2>
 * <p>修理要砧和经验，而随从没有经验值。做成「凭空修好」就是又一个凭空生成的口子，
 * 做成「消耗玩家的经验」又需要一整套没人要的交互。换一件更好的就够了。</p>
 */
public final class HomeRoutineExecutor implements dev.squire.server.task.TaskExecutor {

	private static final org.slf4j.Logger LOG =
		org.slf4j.LoggerFactory.getLogger(HomeRoutineExecutor.class);

	public static final String TYPE = "home.routine";

	/** 找存放箱子的半径。收工整理是「顺手」，不是一次运输任务。 */
	private static final int CHEST_RADIUS = 8;
	/** 一次最多存几摞，别把一整趟收工变成十秒钟的搬运表演。 */
	private static final int MAX_STACKS_STOWED = 6;

	/** 这一趟到底做了什么，供完工汇总与测试断言。 */
	public record Progress(int upgraded, int stowed) { }

	private final RuntimeServices services;

	public HomeRoutineExecutor(RuntimeServices services) {
		this.services = services;
	}

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public boolean requiresBody() {
		return true;
	}

	@Override
	public void start(Task task) {
		task.setExecutionState(new Progress(0, 0));
	}

	@Override
	public StepOutcome tick(Task task, long tick) {
		AvatarEntity avatar = services.avatar(task.agentId());
		if (avatar == null || !avatar.isAlive()
				|| !(avatar.getWorld() instanceof ServerWorld world)) {
			task.setLastErrorCode("ENTITY_NOT_FOUND");
			return StepOutcome.FAILED;
		}
		SquireProfile profile = services.profile(task.agentId());
		int upgraded = profile != null && profile.can(Ability.LOGISTICS_UPKEEP)
			? upgradeArmour(avatar) : 0;
		int stowed = shouldStow(profile) ? stow(avatar, world) : 0;
		task.setExecutionState(new Progress(upgraded, stowed));
		if (upgraded > 0 || stowed > 0) {
			LOG.info("[home] {} upgraded {} piece(s), stowed {} stack(s)",
				task.taskId(), upgraded, stowed);
		}
		// 一趟就做完。收工整理不该占着 agent 的唯一执行位不放。
		return StepOutcome.WORK_DONE;
	}

	/**
	 * 收工存料的两个来源：「囤积」性格，或者「积极」以上的自主档位。
	 *
	 * <p>两者都是玩家<b>明确选过</b>的：一个来自这只随从的性格（他就是这样的人），
	 * 一个来自面板上的档位。默认档不会动你背包里的任何东西。</p>
	 */
	private static boolean shouldStow(SquireProfile profile) {
		return profile != null && (profile.hasTrait(Trait.HOARDER)
			|| profile.autonomyLevel().atLeast(AutonomyLevel.PROACTIVE));
	}

	/** 背包里有更好的护甲就换上（搬运，绝不复制）。 */
	private static int upgradeArmour(AvatarEntity avatar) {
		AvatarInventory items = avatar.items();
		int changed = 0;
		for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST,
				EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
			AvatarInventory.SlotRef best = items.bestArmorSlot(slot);
			if (best == null || best.isEquipment()) {
				continue; // 身上那件已经是最好的
			}
			if (items.equipFromMain(best.mainIndex(), slot).success()) {
				changed++;
			}
		}
		return changed;
	}

	/**
	 * 把非装备的余料存进最近的箱子。武器护甲和食物留在身上——收工整理不该把他
	 * 自己的家伙也交出去。
	 */
	private int stow(AvatarEntity avatar, ServerWorld world) {
		BlockPos chest = nearestContainer(world, avatar.getBlockPos());
		if (chest == null) {
			return 0;
		}
		// 和其它容器操作同一条路径：同一个交互距离、同一个保护判定。
		var resolved = ContainerAccess.resolve(world, chest, avatar.getPos(),
			avatar.ownerId(), services.protection(), true);
		if (!resolved.ok()) {
			return 0;
		}
		Inventory container = resolved.inventory();
		AvatarInventory items = avatar.items();
		int stowed = 0;
		for (int slot = 0; slot < items.size() && stowed < MAX_STACKS_STOWED; slot++) {
			ItemStack stack = items.getStack(slot);
			if (stack.isEmpty() || keepOnPerson(stack)) {
				continue;
			}
			ItemStack leftover = ContainerAccess.insert(container, stack.copy());
			if (leftover.getCount() == stack.getCount()) {
				continue; // 箱子满了，什么都没进去
			}
			items.setStack(slot, leftover);
			stowed++;
		}
		return stowed;
	}

	/** 武器、护甲、工具和食物永远留在身上。 */
	private static boolean keepOnPerson(ItemStack stack) {
		return stack.getItem() instanceof net.minecraft.item.ArmorItem
			|| stack.getItem() instanceof net.minecraft.item.SwordItem
			|| stack.getItem() instanceof net.minecraft.item.ToolItem
			|| stack.getItem().getFoodComponent() != null;
	}

	private static BlockPos nearestContainer(ServerWorld world, BlockPos centre) {
		BlockPos best = null;
		double bestDistSq = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.iterate(centre.add(-CHEST_RADIUS, -3, -CHEST_RADIUS),
				centre.add(CHEST_RADIUS, 3, CHEST_RADIUS))) {
			if (!(world.getBlockEntity(pos) instanceof Inventory)) {
				continue;
			}
			double distSq = pos.getSquaredDistance(centre);
			if (distSq < bestDistSq) {
				bestDistSq = distSq;
				best = pos.toImmutable();
			}
		}
		return best;
	}

	@Override
	public void cancel(Task task) {
		// 一趟就做完，没有需要释放的句柄
	}

	/**
	 * 收工整理没有「世界里可以复查的结果」——换没换装、存没存料，取决于当时背包里
	 * 有什么。所以成功条件恒真：这条任务的意义是「他回家之后做过一轮」，
	 * 而不是「世界变成了某个样子」。
	 */
	public static TaskCondition routineDone() {
		return TaskCondition.of(ctx -> true, "the companion ran its home routine");
	}

	@Override
	public TaskCondition recoverySuccessCondition(
			dev.squire.server.task.TaskStateStore.Snapshot snapshot) {
		return routineDone();
	}
}
