package dev.squire.server.task.executors;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.squire.server.task.TaskStateStore;

/**
 * 施工中途关服再开，任务必须能重新武装。
 *
 * <p>调度器的规则是：{@code recoverySuccessCondition} 返回 null 就把任务判成
 * {@code SERVER_RESTARTED} 直接丢掉。所以「盖到一半关服，回来房子在、任务没了」
 * 这件事的根因就在这个方法。这里只验它<b>会不会返回条件</b>——条件本身读的是真实
 * 世界方块，那部分由 GameTest 盖。</p>
 *
 * <p>反过来，参数里没有 placementId（或不是合法 UUID）时必须仍然返回 null：
 * 宁可如实失败，也不能重新武装一个永远验不了的任务。</p>
 */
class BlueprintRecoveryConditionTest {

	private static final RuntimeServices SERVICES = new RuntimeServices() {
		@Override
		public net.minecraft.server.MinecraftServer server() {
			return null;
		}

		@Override
		public dev.squire.server.body.avatar.AvatarEntity avatar(UUID agentId) {
			return null;
		}

		@Override
		public long currentTick() {
			return 0L;
		}

		@Override
		public net.minecraft.server.network.ServerPlayerEntity requester(UUID playerId) {
			return null;
		}
	};

	private static TaskStateStore.Snapshot snapshot(String type,
			Map<String, Object> params) {
		return new TaskStateStore.Snapshot(UUID.randomUUID(), UUID.randomUUID(),
			UUID.randomUUID(), type, "P3_USER_TASK", "RUNNING", 0, "restored",
			params, List.of(), 0L, 600L, null, null, 2, 20, true, "c1");
	}

	@Test
	void aPlacementIdIsEnoughToRearmTheBuild() {
		var executor = new BlueprintBuildExecutor(SERVICES);
		assertNotNull(executor.recoverySuccessCondition(snapshot(
			BlueprintBuildExecutor.TYPE,
			Map.of(BlueprintBuildExecutor.PARAM_PLACEMENT_ID,
				UUID.randomUUID().toString()))));
	}

	@Test
	void aPlacementIdIsEnoughToRearmTheExcavation() {
		var executor = new ExcavateExecutor(SERVICES);
		assertNotNull(executor.recoverySuccessCondition(snapshot(
			ExcavateExecutor.TYPE,
			Map.of(ExcavateExecutor.PARAM_PLACEMENT_ID,
				UUID.randomUUID().toString()))));
	}

	@Test
	void aTaskWithoutAPlacementIdFailsClosed() {
		assertNull(new BlueprintBuildExecutor(SERVICES)
			.recoverySuccessCondition(snapshot(BlueprintBuildExecutor.TYPE, Map.of())));
		assertNull(new ExcavateExecutor(SERVICES)
			.recoverySuccessCondition(snapshot(ExcavateExecutor.TYPE, Map.of())));
	}

	@Test
	void aGarbledPlacementIdFailsClosedInsteadOfThrowing() {
		assertNull(new BlueprintBuildExecutor(SERVICES).recoverySuccessCondition(
			snapshot(BlueprintBuildExecutor.TYPE,
				Map.of(BlueprintBuildExecutor.PARAM_PLACEMENT_ID, "not-a-uuid"))));
		assertNull(new ExcavateExecutor(SERVICES).recoverySuccessCondition(
			snapshot(ExcavateExecutor.TYPE,
				Map.of(ExcavateExecutor.PARAM_PLACEMENT_ID, "not-a-uuid"))));
	}

	/**
	 * 顺手补的一条：{@code BuildStructureExecutor} 在第 1 期之前压根没实现这个方法，
	 * 施工中途重启就直接丢任务。参数里两个角点、pattern 和 blockId 一直都在。
	 */
	@Test
	void theOldBuildExecutorAlsoFailsClosedWhenCoordinatesAreMissing() {
		assertNull(new BuildStructureExecutor(SERVICES).recoverySuccessCondition(
			snapshot(BuildStructureExecutor.TYPE,
				Map.of(BuildStructureExecutor.PARAM_BLOCK_ID, "minecraft:stone"))));
	}
}
