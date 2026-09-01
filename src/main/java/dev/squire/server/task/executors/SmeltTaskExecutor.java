package dev.squire.server.task.executors;

import java.util.ArrayList;
import java.util.List;

import dev.squire.api.body.MoveHandle;
import dev.squire.api.body.MoveOptions;
import dev.squire.api.body.TargetPosition;
import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.body.avatar.AvatarInventory;
import dev.squire.server.task.Task;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SmokerBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.AbstractCookingRecipe;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeType;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * 真实熔炉熔炼（方案 C4，取代 ADR-022 的模拟实现）。
 *
 * <p>流程：找到/复用一座真实炉子 → 走过去 → 写入 input 与 fuel → 等待
 * {@code AbstractFurnaceBlockEntity} 自己烧出产物 → 从产物槽取走。产物永远由
 * 香草方块实体产生，执行器从不凭空 {@code insertStack} 一个成品。</p>
 *
 * <p>支持 furnace / blast_furnace / smoker 三种配方类型；优先复用现有设备，
 * 绝不自动放置设备（放置需要上层明确取得建造权限）。炉子被别人占用、产物槽物品
 * 不兼容或区块卸载时进入等待，超过上限才失败——不会伪造结果。</p>
 */
public final class SmeltTaskExecutor implements dev.squire.server.task.TaskExecutor {

	private static final org.slf4j.Logger LOG =
		org.slf4j.LoggerFactory.getLogger(SmeltTaskExecutor.class);

	public static final String TYPE = "smelt.item";

	public static final String PARAM_ITEM_ID = "itemId";
	public static final String PARAM_COUNT = "count";

	/** 炉子扫描半径（与采集扫描同量级，绝不无界扫描）。 */
	private static final int SEARCH_RADIUS = 8;
	/** 炉口被占用/区块卸载时的最长等待，超过即如实失败。 */
	private static final int MAX_BLOCKED_TICKS = 600;

	/** 输入 / 燃料 / 产物槽（香草 AbstractFurnaceBlockEntity 布局）。 */
	private static final int SLOT_INPUT = 0;
	private static final int SLOT_FUEL = 1;
	private static final int SLOT_OUTPUT = 2;

	/** 一次熔炼作业的进度。 */
	public record Progress(Identifier outputId, int wanted, Identifier inputId,
			BlockPos furnacePos, MoveHandle handle, int loadedInput, int collected,
			int blockedTicks) {

		Progress withBlocked(int ticks) {
			return new Progress(outputId, wanted, inputId, furnacePos, handle,
				loadedInput, collected, ticks);
		}
	}

	private final RuntimeServices services;

	public SmeltTaskExecutor(RuntimeServices services) {
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
		if (task.stringParam(PARAM_ITEM_ID) == null) {
			task.setLastErrorCode("INVALID_ARGUMENT");
			throw new IllegalStateException("smelt needs itemId");
		}
	}

	@Override
	public StepOutcome tick(Task task, long tick) {
		AvatarEntity avatar = services.avatar(task.agentId());
		if (avatar == null || !avatar.isAlive()) {
			task.setLastErrorCode("ENTITY_NOT_FOUND");
			return StepOutcome.FAILED;
		}
		ServerWorld world = (ServerWorld) avatar.getWorld();
		String outputRaw = task.stringParam(PARAM_ITEM_ID);
		Identifier outputId = parseId(outputRaw);
		Integer absoluteTarget = task.intParam("targetCount");
		int wanted = absoluteTarget != null ? absoluteTarget
			: task.intParam(PARAM_COUNT) == null ? 1 : task.intParam(PARAM_COUNT);

		AvatarInventory items = avatar.items();
		if (items.countOf(outputId) >= wanted) {
			return StepOutcome.WORK_DONE;
		}

		Progress progress = task.executionState() instanceof Progress p ? p : null;
		if (progress == null) {
			Device device = findDevice(world, avatar.getBlockPos(), outputId);
			if (device == null) {
				// 没有可复用的炉子，或根本没有对应配方——如实区分这两种失败
				task.setLastErrorCode(hasAnyCookingRecipe(world, outputId)
					? "NO_WORKSTATION" : "NO_RECIPE");
				return StepOutcome.FAILED;
			}
			Identifier input = resolveInput(world, items, outputId, device.type());
			if (input == null) {
				task.setLastErrorCode("NO_RECIPE");
				return StepOutcome.FAILED;
			}
			progress = new Progress(outputId, wanted, input, device.pos(), null, 0, 0, 0);
			task.setExecutionState(progress);
		}

		BlockPos furnacePos = progress.furnacePos();
		if (!world.getChunkManager().isChunkLoaded(furnacePos.getX() >> 4,
				furnacePos.getZ() >> 4)) {
			return waitOrFail(task, progress, "WORLD_CHANGED");
		}
		if (!(world.getBlockEntity(furnacePos) instanceof AbstractFurnaceBlockEntity furnace)) {
			// 炉子被拆了：重新找一座，而不是继续对着空气汇报进度
			task.setExecutionState(null);
			return StepOutcome.CONTINUE;
		}

		// 距离不够就先走过去（写入炉子必须像玩家一样在交互范围内）
		double distSq = avatar.squaredDistanceTo(furnacePos.getX() + 0.5,
			furnacePos.getY() + 0.5, furnacePos.getZ() + 0.5);
		if (distSq > dev.squire.server.world.ContainerAccess.MAX_INTERACT_DISTANCE_SQ) {
			return navigate(task, avatar, progress);
		}
		var decision = services.protection().canInteract(world, furnacePos,
			avatar.ownerId());
		if (!decision.allowed()) {
			task.setLastErrorCode("PROTECTED_REGION");
			return StepOutcome.FAILED;
		}

		// 1) 先把已经烧好的产物取走
		int collected = progress.collected();
		ItemStack output = furnace.getStack(SLOT_OUTPUT);
		if (!output.isEmpty()) {
			if (output.getItem() != Registries.ITEM.get(outputId)) {
				// 产物槽里是别人的东西：不清空、不覆盖，如实等待/失败
				return waitOrFail(task, progress, "PRECONDITION_FAILED");
			}
			int room = items.insertableAmount(output);
			if (room <= 0) {
				task.setLastErrorCode("INVENTORY_FULL");
				return StepOutcome.FAILED;
			}
			ItemStack taken = furnace.removeStack(SLOT_OUTPUT, room);
			ItemStack leftover = items.insert(taken);
			collected += taken.getCount() - leftover.getCount();
			if (!leftover.isEmpty()) {
				furnace.setStack(SLOT_OUTPUT, leftover);
			}
			furnace.markDirty();
			if (items.countOf(outputId) >= wanted) {
				return StepOutcome.WORK_DONE;
			}
		}

		// 2) 补充输入与燃料
		int stillNeeded = wanted - items.countOf(outputId)
			- furnace.getStack(SLOT_INPUT).getCount();
		if (stillNeeded > 0) {
			ItemStack inputSlot = furnace.getStack(SLOT_INPUT);
			if (!inputSlot.isEmpty()
					&& inputSlot.getItem() != Registries.ITEM.get(progress.inputId())) {
				return waitOrFail(task, progress, "PRECONDITION_FAILED"); // 被别人占用
			}
			int room = Math.min(stillNeeded, inputSlot.isEmpty()
				? furnace.getMaxCountPerStack()
				: inputSlot.getMaxCount() - inputSlot.getCount());
			if (room > 0 && items.hasAtLeast(progress.inputId(), 1)) {
				List<ItemStack> taken = items.extract(progress.inputId(), room);
				int moved = 0;
				for (ItemStack stack : taken) {
					ItemStack current = furnace.getStack(SLOT_INPUT);
					if (current.isEmpty()) {
						furnace.setStack(SLOT_INPUT, stack);
						moved += stack.getCount();
					} else {
						int fits = Math.min(stack.getCount(),
							current.getMaxCount() - current.getCount());
						current.increment(fits);
						moved += fits;
						stack.decrement(fits);
						if (!stack.isEmpty()) {
							items.insert(stack); // 放不下的原样退回背包
						}
					}
				}
				furnace.markDirty();
				progress = new Progress(progress.outputId(), wanted, progress.inputId(),
					furnacePos, progress.handle(), progress.loadedInput() + moved,
					collected, 0);
				task.setExecutionState(progress);
			} else if (furnace.getStack(SLOT_INPUT).isEmpty()
					&& !items.hasAtLeast(progress.inputId(), 1)) {
				task.setLastErrorCode("INSUFFICIENT_ITEM");
				return StepOutcome.FAILED;
			}
		}

		// 3) 燃料：只在炉子既没燃烧也没燃料时补，避免浪费
		if (!furnace.getStack(SLOT_INPUT).isEmpty()
				&& furnace.getStack(SLOT_FUEL).isEmpty() && !isBurning(furnace)) {
			if (!loadFuel(items, furnace)) {
				task.setLastErrorCode("INSUFFICIENT_FUEL");
				return StepOutcome.FAILED;
			}
		}

		if (progress.collected() != collected) {
			task.setExecutionState(new Progress(progress.outputId(), wanted,
				progress.inputId(), furnacePos, progress.handle(),
				progress.loadedInput(), collected, 0));
		}
		// 炉子在自己 tick；下一次调度再来查看产物
		return items.countOf(outputId) >= wanted
			? StepOutcome.WORK_DONE : StepOutcome.CONTINUE;
	}

	// ------------------------------------------------------------------ helpers

	private StepOutcome navigate(Task task, AvatarEntity avatar, Progress progress) {
		MoveHandle handle = progress.handle();
		if (handle != null && handle.state() == MoveHandle.State.FAILED) {
			task.setLastErrorCode(avatar.lastMoveErrorCode() == null
				? "UNREACHABLE" : avatar.lastMoveErrorCode());
			return StepOutcome.FAILED;
		}
		if (handle == null || handle.state() == MoveHandle.State.CANCELLED
				|| handle.state() == MoveHandle.State.ARRIVED) {
			BlockPos stand = GatherBlockExecutor.standableAdjacent(avatar,
				progress.furnacePos());
			BlockPos target = stand == null ? progress.furnacePos() : stand;
			MoveHandle started = avatar.moveTo(new TargetPosition(
				avatar.getWorld().getRegistryKey().getValue().toString(),
				target.getX() + 0.5, target.getY(), target.getZ() + 0.5),
				MoveOptions.WALK);
			if (started.state() == MoveHandle.State.FAILED) {
				task.setLastErrorCode("PATH_NOT_FOUND");
				return StepOutcome.FAILED;
			}
			task.setExecutionState(new Progress(progress.outputId(), progress.wanted(),
				progress.inputId(), progress.furnacePos(), started,
				progress.loadedInput(), progress.collected(), progress.blockedTicks()));
		}
		return StepOutcome.CONTINUE;
	}

	/** Bounded wait: keep going while the blockage may clear, then fail honestly. */
	private StepOutcome waitOrFail(Task task, Progress progress, String errorCode) {
		int blocked = progress.blockedTicks() + 1;
		task.setExecutionState(progress.withBlocked(blocked));
		if (blocked >= MAX_BLOCKED_TICKS) {
			LOG.info("[smelt] {} giving up after {} blocked ticks ({})", task.taskId(),
				blocked, errorCode);
			task.setLastErrorCode(errorCode);
			return StepOutcome.FAILED;
		}
		return StepOutcome.CONTINUE;
	}

	private static boolean isBurning(AbstractFurnaceBlockEntity furnace) {
		// 香草用 PropertyDelegate 暴露燃烧时间；索引 0 = burnTime
		return furnace.getCachedState().getBlock() instanceof net.minecraft.block
			.AbstractFurnaceBlock
			&& furnace.getCachedState().get(net.minecraft.block.AbstractFurnaceBlock.LIT);
	}

	private static boolean loadFuel(AvatarInventory items, AbstractFurnaceBlockEntity furnace) {
		for (int slot = 0; slot < items.size(); slot++) {
			ItemStack stack = items.getStack(slot);
			if (stack.isEmpty() || !AbstractFurnaceBlockEntity.canUseAsFuel(stack)) {
				continue;
			}
			List<ItemStack> taken = items.extractMatching(
				s -> ItemStack.canCombine(s, stack), Math.min(8, stack.getCount()));
			if (taken.isEmpty()) {
				continue;
			}
			ItemStack fuel = taken.get(0);
			for (int i = 1; i < taken.size(); i++) {
				items.insert(taken.get(i));
			}
			furnace.setStack(SLOT_FUEL, fuel);
			furnace.markDirty();
			return true;
		}
		return false;
	}

	/** A reusable cooking device plus the recipe type it runs. */
	private record Device(BlockPos pos, RecipeType<? extends AbstractCookingRecipe> type) {
	}

	private static RecipeType<? extends AbstractCookingRecipe> typeOf(BlockEntity entity) {
		if (entity instanceof BlastFurnaceBlockEntity) {
			return RecipeType.BLASTING;
		}
		if (entity instanceof SmokerBlockEntity) {
			return RecipeType.SMOKING;
		}
		return RecipeType.SMELTING;
	}

	/**
	 * Nearest existing furnace whose recipe type can actually produce the output.
	 * Never places one — reuse only (方案 C4).
	 */
	private static Device findDevice(ServerWorld world, BlockPos center, Identifier output) {
		Device best = null;
		double bestDistSq = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.iterate(
				center.add(-SEARCH_RADIUS, -SEARCH_RADIUS, -SEARCH_RADIUS),
				center.add(SEARCH_RADIUS, SEARCH_RADIUS, SEARCH_RADIUS))) {
			BlockEntity entity = world.getBlockEntity(pos);
			if (!(entity instanceof AbstractFurnaceBlockEntity)) {
				continue;
			}
			var type = typeOf(entity);
			if (!hasRecipe(world, output, type)) {
				continue;
			}
			double distSq = pos.getSquaredDistance(center);
			if (distSq < bestDistSq) {
				bestDistSq = distSq;
				best = new Device(pos.toImmutable(), type);
			}
		}
		return best;
	}

	private static boolean hasRecipe(ServerWorld world, Identifier output,
			RecipeType<? extends AbstractCookingRecipe> type) {
		var target = Registries.ITEM.get(output);
		for (Recipe<?> recipe : world.getServer().getRecipeManager().values()) {
			if (recipe.getType() == type
					&& recipe.getOutput(world.getRegistryManager()).getItem() == target) {
				return true;
			}
		}
		return false;
	}

	private static boolean hasAnyCookingRecipe(ServerWorld world, Identifier output) {
		return hasRecipe(world, output, RecipeType.SMELTING)
			|| hasRecipe(world, output, RecipeType.BLASTING)
			|| hasRecipe(world, output, RecipeType.SMOKING);
	}

	/**
	 * The raw ingredient for {@code out}, resolved against the vanilla RecipeManager.
	 * Candidates are ordered by recipe id (platform iteration order is not guaranteed),
	 * then the first ingredient the avatar actually holds wins — e.g. raw_iron beats
	 * iron_ore for iron_ingot when both recipes exist.
	 */
	private static Identifier resolveInput(ServerWorld world, AvatarInventory items,
			Identifier outputId, RecipeType<? extends AbstractCookingRecipe> type) {
		var target = Registries.ITEM.get(outputId);
		record Candidate(Identifier recipeId, Identifier input) {
		}
		var candidates = new ArrayList<Candidate>();
		for (Recipe<?> recipe : world.getServer().getRecipeManager().values()) {
			if (recipe.getType() == type && recipe instanceof AbstractCookingRecipe cooking
					&& cooking.getOutput(world.getRegistryManager()).getItem() == target
					&& !cooking.getIngredients().isEmpty()) {
				var stacks = cooking.getIngredients().get(0).getMatchingStacks();
				if (stacks.length > 0) {
					candidates.add(new Candidate(recipe.getId(),
						Registries.ITEM.getId(stacks[0].getItem())));
				}
			}
		}
		if (candidates.isEmpty()) {
			return null;
		}
		candidates.sort(java.util.Comparator.comparing(Candidate::recipeId));
		for (Candidate candidate : candidates) {
			if (items.hasAtLeast(candidate.input(), 1)) {
				return candidate.input();
			}
		}
		return candidates.get(0).input(); // canonical fallback when nothing is held yet
	}

	private static Identifier parseId(String raw) {
		int colon = raw.indexOf(':');
		return colon < 0 ? new Identifier(raw) : new Identifier(raw.substring(0, colon),
			raw.substring(colon + 1));
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

	@Override
	public dev.squire.server.task.TaskCondition recoverySuccessCondition(
			dev.squire.server.task.TaskStateStore.Snapshot snapshot) {
		Object item = snapshot.parameters().get(PARAM_ITEM_ID);
		Object target = snapshot.parameters().get("targetCount");
		if (!(item instanceof String itemId) || !(target instanceof Number count)) {
			return null;
		}
		return hasProduced(itemId, count.intValue());
	}

	/** Goal condition factory over the REAL inventory. */
	public static dev.squire.server.task.TaskCondition hasProduced(String itemId, int count) {
		return GatherBlockExecutor.hasItems(itemId, count);
	}
}
