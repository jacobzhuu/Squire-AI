package dev.squire.server.task.executors;

import java.util.List;
import java.util.UUID;

import dev.squire.server.blueprint.Blueprint;
import dev.squire.server.blueprint.BlueprintManager;
import dev.squire.server.blueprint.BlueprintPlacement;
import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.task.Task;
import dev.squire.server.task.TaskCondition;
import dev.squire.server.world.Torchlight;
import dev.squire.server.world.UndoJournal;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * 把一份蓝图摆放里外点亮（工程的点灯阶段）。
 *
 * <p>刻意不用 {@code base.lights.set}：那一条是<b>翻拉杆</b>，前提是基地里已经有
 * 红石灯和开关。刚挖出来的矿井前哨站什么都没有，需要的是真的插火把——而且火把
 * 必须来自伙伴自己的背包，和施工用的方块是同一套账。</p>
 *
 * <p>成功条件读真实光照：足印里每一处「站得住人」的格子都不再是暗的。
 * 用执行器自己的计数当凭据，等于允许它插了两根就宣布天下太平。</p>
 */
public final class LightUpExecutor implements dev.squire.server.task.TaskExecutor {

	private static final org.slf4j.Logger LOG =
		org.slf4j.LoggerFactory.getLogger(LightUpExecutor.class);

	public static final String TYPE = "base.torch_up";
	public static final String PARAM_PLACEMENT_ID = "placementId";
	public static final String PARAM_PROJECT_ID = "projectId";

	/** 一趟最多插几根。 */
	private static final int MAX_TORCHES = Torchlight.PROJECT_MAX_TORCHES;
	/** 每隔几格一根。 */
	private static final int SPACING = Torchlight.PROJECT_SPACING;

	public record Progress(UUID placementId, int placed, UUID operationId, long lastPlacementTick) {
		public Progress(UUID placementId, int placed, UUID operationId) { this(placementId, placed, operationId, 0); }
	}

	private final RuntimeServices services;

	public LightUpExecutor(RuntimeServices services) {
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
		task.setExecutionState(new Progress(placementIdOf(task), 0, null));
	}

	@Override
	public StepOutcome tick(Task task, long tick) {
		AvatarEntity avatar = services.avatar(task.agentId());
		if (avatar == null || !(avatar.getWorld() instanceof ServerWorld world)) {
			task.setLastErrorCode("ENTITY_NOT_FOUND");
			return StepOutcome.FAILED;
		}
		List<BlockPos> spots = candidates(placementIdOf(task));
		if (spots.isEmpty()) {
			task.setLastErrorCode("PRECONDITION_FAILED");
			return StepOutcome.FAILED;
		}
		if (Torchlight.brightEnough(world, spots)) return StepOutcome.WORK_DONE;
		UndoJournal journal = journal();
		UUID operationId = journal == null ? null
			: journal.begin(task.taskId(), task.requesterId(),
				world.getRegistryKey().getValue().toString(), "torch up",
				services.currentTick());
		UUID projectId = projectIdOf(task);
		if (projectId != null && !services.beginProjectMutation(projectId, "lighting:" + operationId)) {
			task.setLastErrorCode("CONSTRUCTION_CHECKPOINT_FAILED"); return StepOutcome.FAILED;
		}
		int placed = projectId == null
			? Torchlight.lightUp(world, avatar, spots, SPACING, MAX_TORCHES,
				journal, operationId, tick)
			: Torchlight.lightUpPlanned(world, projectSpots(world, placementIdOf(task)).stream()
				.filter(p -> services.protection().canPlace(world, p, avatar.ownerId()).allowed()).toList(),
				MAX_TORCHES, journal,
				operationId, tick, () -> services.consumeProjectMaterial(projectId,
					Torchlight.TORCH, 1));
		if (journal != null && operationId != null) {
			journal.close(operationId);
		}
		if (projectId != null && !services.completeProjectMutation(projectId)) {
			task.setLastErrorCode("CONSTRUCTION_CHECKPOINT_FAILED"); return StepOutcome.FAILED;
		}
		int previouslyPlaced = task.executionState() instanceof Progress progress
			? progress.placed() : 0;
		long lastPlacement = placed > 0 ? tick : task.executionState() instanceof Progress p ? p.lastPlacementTick() : tick;
		task.setExecutionState(new Progress(placementIdOf(task), previouslyPlaced + placed, operationId, lastPlacement));
		if (placed == 0 && !Torchlight.brightEnough(world, spots)) {
			if (tick - lastPlacement <= 40) return StepOutcome.CONTINUE;
			LOG.warn("Lighting blocked dark={} planned={}", spots.stream().filter(p -> Torchlight.suitable(world, p)).limit(8).map(p -> p + ":" + world.getLightLevel(net.minecraft.world.LightType.BLOCK, p)).toList(),
				projectSpots(world, placementIdOf(task)).stream().limit(12).map(p -> p + ":" + world.getBlockState(p) + ":" + world.getLightLevel(net.minecraft.world.LightType.BLOCK, p)).toList());
			// An obstructed/outdated lighting position is not a material shortage.
			boolean empty = projectId == null ? avatar.items().countOf(Torchlight.TORCH) <= 0
				: services.projectMaterialCount(projectId, Torchlight.TORCH) <= 0;
			task.setLastErrorCode(empty ? "INSUFFICIENT_ITEM" : "LIGHTING_OBSTRUCTED");
			return StepOutcome.FAILED;
		}
		LOG.info("[light] {} placed {} torch(es)", task.taskId(), placed);
		// Light propagation settles on subsequent server ticks. Large projects take
		// multiple bounded passes, without mistaking the per-pass cap for missing stock.
		return placed > 0 ? StepOutcome.CONTINUE : StepOutcome.WORK_DONE;
	}

	private List<BlockPos> projectSpots(ServerWorld world, UUID placementId) {
		return services.blueprints().placement(placementId)
			.flatMap(services.blueprints()::resolve)
			.map(resolved -> Torchlight.projectSpots(world, resolved)).orElse(List.of());
	}

	@Override
	public void cancel(Task task) {
		// 一趟做完，没有需要释放的句柄
	}

	/** 足印里值得插火把的格子：挖出来的负空间，加上建筑内部的地面。 */
	private List<BlockPos> candidates(UUID placementId) {
		BlueprintManager manager = services.blueprints();
		if (manager == null || placementId == null) {
			return List.of();
		}
		BlueprintPlacement placement = manager.placement(placementId).orElse(null);
		if (placement == null) {
			return List.of();
		}
		Blueprint.Resolved resolved = manager.resolve(placement).orElse(null);
		return resolved == null ? List.of() : Torchlight.candidates(resolved);
	}

	private static UUID placementIdOf(Task task) {
		Object raw = task.parameters().get(PARAM_PLACEMENT_ID);
		if (!(raw instanceof String id)) {
			return null;
		}
		try {
			return UUID.fromString(id);
		} catch (IllegalArgumentException notAUuid) {
			return null;
		}
	}

	private static UUID projectIdOf(Task task) {
		Object raw = task.parameters().get(PARAM_PROJECT_ID);
		if (!(raw instanceof String id)) return null;
		try {
			return UUID.fromString(id);
		} catch (IllegalArgumentException bad) {
			return null;
		}
	}

	private UndoJournal journal() {
		var editor = services.worldEditor();
		return editor == null ? null : editor.journal();
	}

	/** 成功条件读真实光照，不读执行器的计数。 */
	public static TaskCondition litUp(RuntimeServices services, UUID placementId) {
		return TaskCondition.of(ctx -> {
			AvatarEntity avatar = services.avatar(ctx.agentId());
			BlueprintManager manager = services.blueprints();
			if (avatar == null || manager == null
					|| !(avatar.getWorld() instanceof ServerWorld world)) {
				return false;
			}
			return manager.placement(placementId)
				.flatMap(manager::resolve)
				.map(resolved -> Torchlight.brightEnough(world,
					Torchlight.candidates(resolved)))
				.orElse(false);
		}, "the site at " + placementId + " is lit");
	}

	@Override
	public TaskCondition recoverySuccessCondition(
			dev.squire.server.task.TaskStateStore.Snapshot snapshot) {
		Object raw = snapshot.parameters().get(PARAM_PLACEMENT_ID);
		if (!(raw instanceof String id)) {
			return null;
		}
		try {
			return litUp(services, UUID.fromString(id));
		} catch (IllegalArgumentException notAUuid) {
			return null;
		}
	}
}
