package dev.squire.server.task.executors;

import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.task.Task;
import dev.squire.server.task.TaskCondition;
import dev.squire.server.task.TaskStateStore;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * 把伙伴背包里的真实物品交给请求者：走到跟前，然后<b>扔在他脚边</b>。
 *
 * <p>刻意不做"直接塞进玩家背包"——那样物品凭空出现，玩家看不到任何交接过程，
 * 和作弊 give 没有区别。现在交接是一个能看见的动作：他走过来，抛出物品，你捡起。</p>
 *
 * <p>完成与否始终以<b>玩家背包里的最终数量</b>为准，而不是"伙伴扔出去了"——
 * 东西掉在岩浆里就是没送到，不能算成功。</p>
 */
public final class DeliverToOwnerExecutor implements dev.squire.server.task.TaskExecutor {
	public static final String TYPE = "inventory.deliver";
	public static final String PARAM_ITEM_ID = "itemId";
	public static final String PARAM_TARGET_PLAYER_COUNT = "targetPlayerCount";

	/** 交接距离：得走到这么近才把东西扔出去。 */
	private static final double HANDOFF_RANGE_SQ = 9.0;

	/** 判定"已经放到你脚边"时，围绕玩家扫描掉落物的范围。 */
	private static final double HANDOFF_SCAN_RANGE = 4.0;

	/** 等"自己给自己变出来的东西"落地并被捡起的宽限时间。 */
	private static final long ACQUIRE_GRACE_TICKS = 60L;

	private final RuntimeServices services;

	public DeliverToOwnerExecutor(RuntimeServices services) {
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
		if (task.stringParam(PARAM_ITEM_ID) == null
				|| task.intParam(PARAM_TARGET_PLAYER_COUNT) == null) {
			task.setLastErrorCode("INVALID_ARGUMENT");
			throw new IllegalStateException("delivery needs itemId and targetPlayerCount");
		}
	}

	@Override
	public StepOutcome tick(Task task, long tick) {
		AvatarEntity avatar = services.avatar(task.agentId());
		ServerPlayerEntity player = services.requester(task.requesterId());
		if (avatar == null || !avatar.isAlive() || player == null || !player.isAlive()) {
			task.setLastErrorCode("ENTITY_NOT_FOUND");
			return StepOutcome.FAILED;
		}
		String itemId = task.stringParam(PARAM_ITEM_ID);
		int target = task.intParam(PARAM_TARGET_PLAYER_COUNT);
		Identifier id = parseId(itemId);
		if (playerCount(player, id) >= target) {
			// 交付一次计一件物流工作量（熟练度只在任务终态成功时结算）。
			task.setWorkReport(new Task.WorkReport(
				dev.squire.server.profile.Track.LOGISTICS.id(), 1L, null));
			return StepOutcome.WORK_DONE;
		}
		// 已经扔出去了：伙伴这边的活干完了，交给 verifier 判定是否真的送到
		// （ADR-013：只有 verifier 能把任务判为完成）。这里必须是 WORK_DONE 而不是
		// CONTINUE —— successCondition 只在任务进入 VERIFYING 后才会被求值，
		// 一直 CONTINUE 的话条件永远不会被检查，最后只能超时失败。
		if (task.executionState() != null) {
			// 交付一次计一件物流工作量（熟练度只在任务终态成功时结算）。
			task.setWorkReport(new Task.WorkReport(
				dev.squire.server.profile.Track.LOGISTICS.id(), 1L, null));
			return StepOutcome.WORK_DONE;
		}
		if (!avatar.hasAtLeast(id, 1)) {
			// 东西可能还在路上：伙伴刚用 summon 给自己变出来的掉落物，需要一两 tick
			// 才会出现在世界的实体索引里，然后才被自动捡拾收进背包。这段窗口里
			// 直接判 INSUFFICIENT_ITEM 会把一次正常的取物当场打死。
			if (droppedNear(avatar, id) > 0
					|| tick - task.startedTick() < ACQUIRE_GRACE_TICKS) {
				return StepOutcome.CONTINUE;
			}
			task.setLastErrorCode("INSUFFICIENT_ITEM");
			return StepOutcome.FAILED;
		}
		// 先走到跟前再交东西。伙伴平时就在跟随，绝大多数情况下这一步直接通过；
		// 站得远的时候等他自己走近，而不是隔着半个世界把物品塞进玩家背包。
		if (avatar.squaredDistanceTo(player) > HANDOFF_RANGE_SQ) {
			approach(avatar, player);
			return StepOutcome.CONTINUE;
		}
		// 到了就一次把该给的量全扔出去——这是玩家唯一看得见的"交接"动作。
		// 一次一个地滴会持续几十 tick，看起来像卡住了。
		int owed = target - playerCount(player, id);
		java.util.List<ItemStack> taken = avatar.extractItems(id, owed);
		if (taken.isEmpty()) {
			task.setLastErrorCode("INSUFFICIENT_ITEM");
			return StepOutcome.FAILED;
		}
		for (ItemStack stack : taken) {
			if (avatar.dropForPlayer(stack, player) == null) {
				avatar.insertStack(stack); // 扔不出去就还回背包，绝不凭空销毁
			}
		}
		task.setExecutionState(Boolean.TRUE);
		return StepOutcome.CONTINUE;
	}

	/**
	 * 朝玩家走过去；已经在路上就别反复重发路径请求。
	 *
	 * <p><b>原地待命时绝不移动。</b>玩家把他设成"待命"就是要他钉在原地，任务却
	 * 一路把他拽向玩家，而 StayAnchorGoal 又不断把他拉回锚点——两边打架，看起来
	 * 就是"设了待命还在乱跑"。待命时改为原地等玩家走近，等不到就由超时兜底。</p>
	 */
	private static void approach(AvatarEntity avatar, ServerPlayerEntity player) {
		if (avatar.mode() == AvatarEntity.MovementMode.STAY) {
			return;
		}
		if (avatar.getNavigation().isIdle()) {
			avatar.getNavigation().startMovingTo(player, 1.0);
		}
	}

	@Override
	public void cancel(Task task) {
	}

	@Override
	public TaskCondition recoverySuccessCondition(TaskStateStore.Snapshot snapshot) {
		Object item = snapshot.parameters().get(PARAM_ITEM_ID);
		Object target = snapshot.parameters().get(PARAM_TARGET_PLAYER_COUNT);
		if (!(item instanceof String itemId) || !(target instanceof Number count)) {
			return null;
		}
		return playerHas(services, snapshot.ownerId(), itemId, count.intValue());
	}

	/**
	 * 交付完成的判定：玩家背包里的数量，<b>加上</b>已经躺在他脚边的同种掉落物，
	 * 达到目标数量即算送到。
	 *
	 * <p>只看玩家背包是不对的：伙伴把东西放到你脚下之后，任务就该算完成了——他
	 * 控制不了你弯不弯腰。之前只判背包，结果玩家背包满、或者站着没动的时候，
	 * 一次成功的交接会在 60 秒后被判成 TIMEOUT 失败。</p>
	 */
	public static TaskCondition playerHas(RuntimeServices services, java.util.UUID playerId,
			String itemId, int count) {
		Identifier id = parseId(itemId);
		return TaskCondition.of(ctx -> {
			ServerPlayerEntity player = services.requester(playerId);
			if (player == null) {
				return false;
			}
			return playerCount(player, id) + droppedNear(player, id) >= count;
		}, "player holds (or has at their feet) at least " + count + " " + itemId);
	}

	/** 某个实体脚边同种物品的掉落物总数。 */
	private static int droppedNear(net.minecraft.entity.Entity around, Identifier id) {
		Item item = Registries.ITEM.get(id);
		int total = 0;
		for (var entity : around.getWorld().getEntitiesByClass(
				net.minecraft.entity.ItemEntity.class,
				around.getBoundingBox().expand(HANDOFF_SCAN_RANGE),
				e -> e.isAlive() && e.getStack().getItem() == item)) {
			total += entity.getStack().getCount();
		}
		return total;
	}

	public static int playerCount(ServerPlayerEntity player, Identifier id) {
		Item item = Registries.ITEM.get(id);
		return player.getInventory().count(item);
	}

	private static Identifier parseId(String raw) {
		return raw.contains(":") ? Identifier.of(raw.substring(0, raw.indexOf(':')),
			raw.substring(raw.indexOf(':') + 1)) : Identifier.of("minecraft", raw);
	}
}
