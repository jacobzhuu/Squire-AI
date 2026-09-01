package dev.squire.server.task.executors;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.squire.api.body.MoveHandle;
import dev.squire.api.body.MoveOptions;
import dev.squire.api.body.TargetPosition;
import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.item.ItemEditJournal;
import dev.squire.server.item.ItemTransforms;
import dev.squire.server.nlu.ItemOperation;
import dev.squire.server.task.Task;
import dev.squire.server.task.TaskCondition;
import dev.squire.server.task.TaskStateStore;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * 改动<b>玩家已经有的</b>物品：附魔的增删清、耐久修复。
 *
 * <p>刻意做成一个任务而不是一次同步调用：伙伴得先<b>走到你跟前</b>才动手。隔着半个
 * 世界让你身上的盔甲自己变样，就是这个模组一直在反对的"凭空发生"——交付物品要走
 * 过来，改动物品同样要。</p>
 *
 * <p>动手之前把每一格的原始 {@link ItemStack} 存进 {@link ItemEditJournal}，
 * {@code /squire undo} 可以还原。改动玩家的东西是有后果的，必须有反悔的余地。</p>
 */
public final class ItemEditExecutor implements dev.squire.server.task.TaskExecutor {

	private static final org.slf4j.Logger LOG =
		org.slf4j.LoggerFactory.getLogger(ItemEditExecutor.class);

	public static final String TYPE = "items.edit";

	public static final String PARAM_SCOPE = "scope";
	public static final String PARAM_SELECTOR = "selector";
	public static final String PARAM_SELECTOR_ITEM = "selectorItemId";
	public static final String PARAM_TRANSFORM = "transform";
	public static final String PARAM_ENCHANTMENTS = "enchantments";
	public static final String PARAM_LABEL = "label";

	/** 动手距离：和交接物品一样，得走到伸手可及。 */
	private static final double REACH_SQ = 16.0;

	private final RuntimeServices services;
	private final ItemEditJournal journal;
	private final java.util.function.BiConsumer<UUID, String> notifier;

	public ItemEditExecutor(RuntimeServices services, ItemEditJournal journal,
			java.util.function.BiConsumer<UUID, String> notifier) {
		this.services = services;
		this.journal = journal;
		this.notifier = notifier;
	}

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public boolean requiresBody() {
		return true;
	}

	record Progress(MoveHandle handle, boolean applied) { }

	@Override
	public void start(Task task) {
		if (task.stringParam(PARAM_TRANSFORM) == null) {
			task.setLastErrorCode("INVALID_ARGUMENT");
			throw new IllegalStateException("item edit needs a transform");
		}
		task.setExecutionState(new Progress(null, false));
	}

	@Override
	public StepOutcome tick(Task task, long tick) {
		AvatarEntity avatar = services.avatar(task.agentId());
		ServerPlayerEntity player = services.requester(task.requesterId());
		if (avatar == null || !avatar.isAlive() || player == null || !player.isAlive()) {
			task.setLastErrorCode("ENTITY_NOT_FOUND");
			return StepOutcome.FAILED;
		}
		Progress progress = task.executionState() instanceof Progress p ? p
			: new Progress(null, false);
		if (progress.applied()) {
			return StepOutcome.WORK_DONE;
		}
		if (avatar.squaredDistanceTo(player) > REACH_SQ) {
			return navigate(task, avatar, player, progress);
		}

		ItemOperation.Scope scope = enumOrNull(ItemOperation.Scope.class,
			task.stringParam(PARAM_SCOPE));
		ItemOperation.Transform transform = enumOrNull(ItemOperation.Transform.class,
			task.stringParam(PARAM_TRANSFORM));
		if (scope == null || transform == null) {
			task.setLastErrorCode("INVALID_ARGUMENT");
			return StepOutcome.FAILED;
		}
		List<String> enchantments = splitIds(task.stringParam(PARAM_ENCHANTMENTS));

		List<Target> targets = collect(avatar, player, scope,
			task.stringParam(PARAM_SELECTOR), task.stringParam(PARAM_SELECTOR_ITEM));
		if (targets.isEmpty()) {
			task.setLastErrorCode("NO_MATCHING_ITEM");
			return StepOutcome.FAILED;
		}

		List<ItemEditJournal.SlotSnapshot> snapshots = new ArrayList<>();
		List<String> changed = new ArrayList<>();
		List<String> skipped = new ArrayList<>();
		for (Target target : targets) {
			ItemStack before = target.stack().copy();
			var outcome = ItemTransforms.apply(target.stack(), transform, enchantments);
			String name = target.stack().getItem().toString();
			if (outcome.changed()) {
				snapshots.add(new ItemEditJournal.SlotSnapshot(
					target.slot(), target.index(), before));
				changed.add(name + "：" + outcome.detail());
			} else {
				skipped.add(name + "：" + outcome.detail());
			}
		}
		if (changed.isEmpty()) {
			// 一件都没改动时如实说为什么，别让玩家以为"说了没反应"。
			task.setLastErrorCode("NOTHING_TO_CHANGE");
			notify(task, "[Squire] 没有需要改的：" + String.join("；", skipped));
			return StepOutcome.FAILED;
		}
		sync(player);
		var edit = journal.record(player.getUuid(),
			task.stringParam(PARAM_LABEL) == null ? transform.name()
				: task.stringParam(PARAM_LABEL),
			snapshots, tick);
		LOG.info("[items] edit {} by {} on {} slots ({})", edit.editId(),
			task.agentId(), snapshots.size(), transform);
		String tail = skipped.isEmpty() ? "" : "；跳过：" + String.join("；", skipped);
		notify(task, "[Squire] 改好了（" + changed.size() + " 件）："
			+ String.join("；", changed) + tail
			+ "\n   §7说「撤销」可以还原（仅本次游戏运行期间有效）§r");
		task.setExecutionState(new Progress(null, true));
		task.setWorkReport(new Task.WorkReport(
			dev.squire.server.profile.Track.LOGISTICS.id(), 1L, null));
		return StepOutcome.WORK_DONE;
	}

	private StepOutcome navigate(Task task, AvatarEntity avatar, ServerPlayerEntity player,
			Progress progress) {
		MoveHandle handle = progress.handle();
		if (handle != null && handle.state() == MoveHandle.State.FAILED) {
			task.setLastErrorCode(avatar.lastMoveErrorCode() == null
				? "UNREACHABLE" : avatar.lastMoveErrorCode());
			return StepOutcome.FAILED;
		}
		// 原地待命时不硬拽他走——那会和 StayAnchorGoal 打架。等玩家走近，或者超时。
		if (avatar.mode() == AvatarEntity.MovementMode.STAY) {
			return StepOutcome.CONTINUE;
		}
		if (handle == null || handle.state() != MoveHandle.State.MOVING) {
			MoveHandle started = avatar.moveTo(new TargetPosition(
				avatar.getWorld().getRegistryKey().getValue().toString(),
				player.getX(), player.getY(), player.getZ()), MoveOptions.WALK);
			if (started.state() == MoveHandle.State.FAILED) {
				task.setLastErrorCode("PATH_NOT_FOUND");
				return StepOutcome.FAILED;
			}
			task.setExecutionState(new Progress(started, false));
		}
		return StepOutcome.CONTINUE;
	}

	// ---------------------------------------------------------------- 选中哪些格

	/** 一格：物品本体 + 它在哪，后者是撤销时放回原位所必需的。 */
	private record Target(ItemStack stack, ItemEditJournal.Slot slot, int index) { }

	private static final EquipmentSlot[] ARMOR_SLOTS = {
		EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

	private static List<Target> collect(AvatarEntity avatar, ServerPlayerEntity player,
			ItemOperation.Scope scope, String selectorName, String selectorItemId) {
		var kind = enumOrNull(ItemOperation.Selector.Filter.Kind.class, selectorName);
		if (kind == null) {
			kind = ItemOperation.Selector.Filter.Kind.ARMOR;
		}
		Identifier wanted = selectorItemId == null ? null : parseId(selectorItemId);
		List<Target> out = new ArrayList<>();
		switch (scope) {
			case PLAYER_HELD -> {
				ItemStack held = player.getMainHandStack();
				if (matches(held, wanted, kind)) {
					out.add(new Target(held, ItemEditJournal.Slot.MAIN_HAND,
						player.getInventory().selectedSlot));
				}
			}
			case PLAYER_EQUIPPED -> {
				var inventory = player.getInventory();
				for (int i = 0; i < inventory.armor.size(); i++) {
					ItemStack stack = inventory.armor.get(i);
					if (matches(stack, wanted, kind)) {
						out.add(new Target(stack, ItemEditJournal.Slot.ARMOR, i));
					}
				}
				if (kind == ItemOperation.Selector.Filter.Kind.ALL) {
					ItemStack held = player.getMainHandStack();
					if (matches(held, wanted, kind)) {
						out.add(new Target(held, ItemEditJournal.Slot.MAIN_HAND,
							inventory.selectedSlot));
					}
				}
			}
			case PLAYER_INVENTORY -> {
				var inventory = player.getInventory();
				for (int i = 0; i < inventory.main.size(); i++) {
					ItemStack stack = inventory.main.get(i);
					if (matches(stack, wanted, kind)) {
						out.add(new Target(stack, ItemEditJournal.Slot.INVENTORY, i));
					}
				}
			}
			case AGENT_EQUIPPED -> {
				for (EquipmentSlot slot : ARMOR_SLOTS) {
					ItemStack stack = avatar.getEquippedStack(slot);
					if (matches(stack, wanted, kind)) {
						out.add(new Target(stack, ItemEditJournal.Slot.ARMOR,
							slot.getEntitySlotId()));
					}
				}
				ItemStack held = avatar.getEquippedStack(EquipmentSlot.MAINHAND);
				if (kind != ItemOperation.Selector.Filter.Kind.ARMOR
						&& matches(held, wanted, kind)) {
					out.add(new Target(held, ItemEditJournal.Slot.MAIN_HAND, -1));
				}
			}
			default -> { }
		}
		return out;
	}

	private static boolean matches(ItemStack stack, Identifier wanted,
			ItemOperation.Selector.Filter.Kind kind) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		if (kind == ItemOperation.Selector.Filter.Kind.ITEM) {
			return wanted != null && Registries.ITEM.getId(stack.getItem()).equals(wanted);
		}
		if (kind == ItemOperation.Selector.Filter.Kind.ARMOR) {
			return stack.getItem() instanceof net.minecraft.item.ArmorItem;
		}
		return true;
	}

	// -------------------------------------------------------------------- 杂项

	/** 直接改的是玩家背包里的活对象，必须让客户端重新拿一份。 */
	private static void sync(ServerPlayerEntity player) {
		player.getInventory().markDirty();
		player.playerScreenHandler.sendContentUpdates();
		if (player.currentScreenHandler != player.playerScreenHandler) {
			player.currentScreenHandler.sendContentUpdates();
		}
	}

	private void notify(Task task, String message) {
		if (notifier != null) {
			notifier.accept(task.requesterId(), message);
		}
	}

	private static <E extends Enum<E>> E enumOrNull(Class<E> type, String name) {
		if (name == null) {
			return null;
		}
		try {
			return Enum.valueOf(type, name);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	public static String joinIds(List<String> ids) {
		return ids == null || ids.isEmpty() ? "" : String.join(",", ids);
	}

	private static List<String> splitIds(String raw) {
		if (raw == null || raw.isBlank()) {
			return List.of();
		}
		return List.of(raw.split(","));
	}

	private static Identifier parseId(String raw) {
		try {
			return raw.contains(":") ? new Identifier(raw) : new Identifier("minecraft", raw);
		} catch (RuntimeException e) {
			return null;
		}
	}

	@Override
	public void cancel(Task task) {
		if (task.executionState() instanceof Progress progress && progress.handle() != null) {
			progress.handle().cancel();
		}
		AvatarEntity avatar = services.avatar(task.agentId());
		if (avatar != null) {
			avatar.stopMoving();
		}
	}

	/**
	 * 改动是一次性的、就地生效的，没有"事后还能观测"的世界状态可验。
	 * 用执行器自己记下的完成标记作为验收条件（ADR-013：仍然只有 verifier 能判完成）。
	 */
	public static TaskCondition applied() {
		return TaskCondition.of(ctx -> true, "item edit applied in place");
	}

	@Override
	public TaskCondition recoverySuccessCondition(TaskStateStore.Snapshot snapshot) {
		// 重启后无法确认改动是否已落地，也不能重放（会二次改动）。交给上层判失败。
		return null;
	}
}
