package dev.squire.server.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraft.util.Identifier;

/**
 * 工程跨重启存活。
 *
 * <p>一个盖到一半的矿井前哨站，重开之后应该<b>接着盖</b>，而不是从头再来、也不是
 * 无声消失。这里守住三件事：阶段状态往返、正在跑的那一步退回 PENDING（任务恢复后
 * id 会变，重新编译一次更简单，而每个阶段的成功条件都读真实世界，所以是幂等的）、
 * 以及坏行只拖垮它自己。</p>
 */
class ProjectStoreTest {

	@TempDir
	Path dir;

	private Path file() {
		return dir.resolve("projects.json");
	}

	private static Project project(Project.State state) {
		Project p = new Project(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
			"矿井前哨站",
			"mine_outpost", UUID.randomUUID(), "minecraft:overworld", 1234L,
			ProjectCoordinator.compileStages());
		p.setState(state);
		return p;
	}

	@Test
	void aProjectRoundTripsWithEveryStageState() {
		Project original = project(Project.State.RUNNING);
		Identifier planks = new Identifier("minecraft:oak_planks");
		Identifier torches = new Identifier("minecraft:torch");
		original.reserve(Map.of(planks, 24), Map.of(planks, 8, torches, 4));
		List<Stage> stages = original.stages();
		stages.get(0).setState(Stage.State.DONE);
		stages.get(1).block("还差 32 个 oak_planks");
		stages.get(2).assignTo(UUID.randomUUID());

		new ProjectStore(this::file).save(List.of(original));
		List<Project> loaded = new ProjectStore(this::file).load();

		assertEquals(1, loaded.size());
		Project back = loaded.get(0);
		assertEquals(original.projectId, back.projectId);
		assertEquals(original.ownerId, back.ownerId);
		assertEquals(original.agentId(), back.agentId(), "工程必须保持绑定的施工侍从");
		assertEquals("矿井前哨站", back.name);
		assertEquals("mine_outpost", back.blueprintId);
		assertEquals(original.placementId, back.placementId,
			"工地是工程的全部形状来源，丢了它工程就没意义了");
		assertEquals(Project.State.RUNNING, back.state());
		assertEquals(Stage.State.DONE, back.stages().get(0).state());
		assertEquals(Stage.State.BLOCKED, back.stages().get(1).state());
		assertEquals("还差 32 个 oak_planks", back.stages().get(1).blockedReason(),
			"阻塞原因是玩家唯一能据此行动的东西，必须跟着存");
		assertEquals(stages.get(2).assignedAgentId(),
			back.stages().get(2).assignedAgentId());
		assertTrue(back.supplyPrepared());
		assertEquals(32, back.reservedCount(planks));
		assertEquals(4, back.reservedCount(torches));
		assertEquals(Map.of(planks, 24), back.ownerSupply());
		assertEquals(Map.of(planks, 8, torches, 4), back.agentSupply());
	}

	@Test
	void interruptedSettlementIsQuarantinedWithItsRealEscrow() {
		var original = project(Project.State.RUNNING);
		var item = new Identifier("minecraft:oak_planks");
		original.reserve(Map.of(item, 20), Map.of(item, 3));
		original.pendingMutation("deposit:owner=20:agent=3");
		var progress = new com.google.gson.JsonObject(); progress.addProperty("cursor", 17);
		original.constructionProgress(progress);
		assertTrue(new ProjectStore(this::file).save(List.of(original)));
		var recovered = new ProjectStore(this::file).load().get(0);
		assertEquals(Project.State.PAUSED, recovered.state());
		assertEquals(original.pendingMutation(), recovered.pendingMutation());
		assertEquals(23, recovered.reservedCount(item));
		assertEquals(17, recovered.constructionProgress().get("cursor").getAsInt());
	}

	@Test
	void migrationKeepsAnUntouchedBackup() throws IOException {
		String legacy = "{\"version\":4,\"projects\":[]}";
		Files.writeString(file(), legacy);
		var store = new ProjectStore(this::file); store.load(); store.save(List.of());
		assertEquals(legacy, Files.readString(dir.resolve("projects.json.pre-v9.bak")));
	}

	@Test
	void escrowConsumesAtomicallyAndReportsOnlyTheDelta() {
		Project p = project(Project.State.RUNNING);
		Identifier planks = new Identifier("minecraft:oak_planks");
		p.reserve(Map.of(planks, 5), Map.of(planks, 3));

		assertEquals(Map.of(planks, 2), p.missingFrom(Map.of(planks, 10)));
		assertFalse(p.consume(planks, 9), "a failed debit must not consume a partial stack");
		assertEquals(8, p.reservedCount(planks));
		assertTrue(p.consume(planks, 6));
		assertEquals(2, p.reservedCount(planks));
		assertEquals(0, p.agentSupply().getOrDefault(planks, 0));
		assertEquals(2, p.ownerSupply().getOrDefault(planks, 0));
	}

	@Test
	void partialBatchesAccumulateAndSurviveRestart() {
		Project original = project(Project.State.PAUSED);
		Identifier cobble = new Identifier("minecraft:cobblestone");
		original.reserve(Map.of(), Map.of(cobble, 2));
		original.reserve(Map.of(cobble, 3), Map.of(cobble, 1));
		original.currentStage().orElseThrow().block(Stage.BlockerCode.MATERIALS_MISSING,
			"工程物资池已预留 6/20 件，仍缺 14 件。");

		new ProjectStore(this::file).save(List.of(original));
		Project loaded = new ProjectStore(this::file).load().get(0);

		assertEquals(Project.State.PAUSED, loaded.state());
		assertEquals(6, loaded.reservedCount(cobble),
			"later batches add to the durable pool instead of replacing it");
		assertEquals(Map.of(cobble, 3), loaded.ownerSupply());
		assertEquals(Map.of(cobble, 3), loaded.agentSupply());
		assertEquals(Stage.State.BLOCKED, loaded.currentStage().orElseThrow().state());
		assertEquals(Stage.BlockerCode.MATERIALS_MISSING,
			loaded.currentStage().orElseThrow().blockerCode(),
			"the restored UI must still offer the deposit action");
	}

	@Test
	void aVersionTwoProjectIsPausedUntilRealMaterialsAreReserved() {
		Project original = project(Project.State.RUNNING);
		var row = ProjectStore.write(original);
		row.remove("supplyPrepared");
		row.remove("ownerSupply");
		row.remove("agentSupply");

		Project migrated = ProjectStore.read(row, 2);
		assertEquals(Project.State.PAUSED, migrated.state());
		assertFalse(migrated.supplyPrepared());
		assertTrue(migrated.reservedMaterials().isEmpty());
	}

	@Test
	void aStageThatWasRunningComesBackAsPending() {
		Project original = project(Project.State.RUNNING);
		original.stages().get(3).setState(Stage.State.RUNNING);
		new ProjectStore(this::file).save(List.of(original));

		Project back = new ProjectStore(this::file).load().get(0);
		assertEquals(Stage.State.PENDING, back.stages().get(3).state(),
			"任务恢复之后 id 会变；重新编译一次更简单，而且阶段条件读真实世界，是幂等的");
	}

	@Test
	void sixStagesInTheDocumentedOrder() {
		assertEquals(List.of(Stage.Kind.FULFIL_MATERIALS, Stage.Kind.HAUL,
				Stage.Kind.EXCAVATE, Stage.Kind.BUILD, Stage.Kind.LIGHT,
				Stage.Kind.VERIFY),
			ProjectCoordinator.compileStages().stream().map(s -> s.kind).toList());
	}

	@Test
	void progressCountsDoneAndSkippedAlike() {
		Project p = project(Project.State.RUNNING);
		assertEquals("0/6", p.progress());
		p.stages().get(0).setState(Stage.State.DONE);
		p.stages().get(1).setState(Stage.State.SKIPPED);
		assertEquals("2/6", p.progress(), "跳过的步骤对玩家来说也是「过去了」");
		assertEquals(Stage.Kind.EXCAVATE, p.currentStage().orElseThrow().kind);
	}

	@Test
	void blockingIsNotFailingAndClearsWhenItMovesOn() {
		Stage stage = new Stage(UUID.randomUUID(), Stage.Kind.HAUL);
		stage.block("把材料交给他");
		assertFalse(stage.terminal(), "卡住不是结束——它还在等一件玩家能做的事");
		assertEquals("把材料交给他", stage.blockedReason());
		stage.setState(Stage.State.DONE);
		assertNull(stage.blockedReason(), "往前走了就不该再挂着旧理由");
		assertTrue(stage.terminal());
	}

	@Test
	void aFutureSchemaIsRefusedAndTurnsTheStoreReadOnly() throws IOException {
		Files.writeString(file(), "{\"version\": 99, \"projects\": []}",
			StandardCharsets.UTF_8);
		ProjectStore store = new ProjectStore(this::file);
		assertTrue(store.load().isEmpty());
		assertFalse(store.isWritable(),
			"读不懂就绝不能写回去——旧 jar 覆盖掉的是玩家正在做的工程");
		store.save(List.of(project(Project.State.RUNNING)));
		assertTrue(Files.readString(file()).contains("99"), "文件没有被覆盖");
	}

	@Test
	void aCorruptRowDoesNotSinkTheHealthyOnes() throws IOException {
		new ProjectStore(this::file).save(List.of(project(Project.State.RUNNING)));
		String json = Files.readString(file());
		json = json.replace("{\"projectId\"",
			"{\"projectId\": \"not-a-uuid\"}, {\"projectId\"");
		Files.writeString(file(), json, StandardCharsets.UTF_8);

		List<Project> loaded = new ProjectStore(this::file).load();
		assertEquals(1, loaded.size(), "坏行只跳过它自己");
	}

	@Test
	void anUnknownProjectStateStopsRatherThanRunsOnItsOwn() throws IOException {
		new ProjectStore(this::file).save(List.of(project(Project.State.RUNNING)));
		Files.writeString(file(),
			Files.readString(file()).replace("\"RUNNING\"", "\"FROM_THE_FUTURE\""),
			StandardCharsets.UTF_8);
		Project back = new ProjectStore(this::file).load().get(0);
		assertEquals(Project.State.PAUSED, back.state(),
			"认不出的状态先停住：自己跑起来去改世界是最坏的默认");
	}
}
