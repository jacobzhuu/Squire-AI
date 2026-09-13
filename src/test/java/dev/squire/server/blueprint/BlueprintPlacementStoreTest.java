package dev.squire.server.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * 摆放要跨重启存活。丢一个摆放不像丢一次操作那么便宜：施工任务重启后只带着一个
 * {@code placementId}，摆放没了它就只能如实失败，玩家看到的是一栋盖到一半、
 * 再也接不上的房子。
 *
 * <p>所以这里除了往返，还盯两件事：未来版本必须 fail-closed <b>并且停止写入</b>
 * （旧 jar 不能把新档案覆盖回去），一行坏数据只能拖垮它自己。</p>
 */
class BlueprintPlacementStoreTest {

	@TempDir
	Path dir;

	private Path file() {
		return dir.resolve("blueprints.json");
	}

	@Test void geometryCheckpointFailureIsReportedBeforeFunding() throws IOException {
		Path blocked = dir.resolve("not-a-directory"); Files.writeString(blocked, "retain this file");
		var store = new BlueprintPlacementStore(() -> blocked.resolve("blueprints.json"));
		assertFalse(store.save(List.of()));
		assertEquals("retain this file", Files.readString(blocked));
		assertTrue(new BlueprintPlacementStore(this::file).save(List.of()));
	}

	private BlueprintPlacement placement(String blueprintId, BlockPos origin,
			Direction facing, BlueprintPlacement.State state) {
		BlueprintPlacement p = new BlueprintPlacement(UUID.randomUUID(),
			UUID.randomUUID(), UUID.randomUUID(), blueprintId,
			"minecraft:overworld", origin, facing, 1234L);
		p.setState(state);
		return p;
	}

	@Test
	void aPlacementRoundTripsWithEveryFieldThatDrivesTheShape() {
		BlueprintPlacement original = placement("watchtower", new BlockPos(10, 64, -30),
			Direction.EAST, BlueprintPlacement.State.READY);
		assertTrue(original.setMaterial("roof", "squire:stone_bricks"));
		assertTrue(original.setMaterial("frame", "squire:oak"));
		original.setState(BlueprintPlacement.State.BUILDING);
		original.authorizeLevel(7);
		original.markLegacyFullPrice();
		new BlueprintPlacementStore(this::file).save(List.of(original));

		List<BlueprintPlacement> loaded = new BlueprintPlacementStore(this::file).load();
		assertEquals(1, loaded.size());
		BlueprintPlacement back = loaded.get(0);
		assertEquals(original.placementId, back.placementId);
		assertEquals(original.ownerId, back.ownerId);
		assertEquals(original.agentId, back.agentId);
		assertEquals("watchtower", back.blueprintId);
		assertEquals(new BlockPos(10, 64, -30), back.origin);
		assertEquals(Direction.EAST, back.facing, "朝向决定旋转，错一格门就开在墙里");
		assertEquals(BlueprintPlacement.State.BUILDING, back.state());
		assertEquals(1234L, back.createdTick);
		assertEquals(7, back.authorizedLevel());
		assertTrue(back.legacyFullPrice(), "pre-cost-plan projects retain their original zero-waste bill");
		assertEquals(Map.of("roof", "squire:stone_bricks", "frame", "squire:oak"),
			back.materials(), "每个摆放实例保留自己的材料调色板");
	}

	@Test
	void legacyVariableShapePlacementsAreSkipped() throws IOException {
		BlueprintPlacement old = placement("generated_house/oak/7x7x4/gable",
			BlockPos.ORIGIN, Direction.NORTH, BlueprintPlacement.State.GHOST);
		new BlueprintPlacementStore(this::file).save(List.of(old));
		Files.writeString(file(), Files.readString(file(), StandardCharsets.UTF_8)
			.replace("\"version\":9", "\"version\":1"), StandardCharsets.UTF_8);

		assertTrue(new BlueprintPlacementStore(this::file).load().isEmpty(),
			"旧坐标形状不能套到新版固定建筑上继续施工");
	}

	@Test
	void aFutureSchemaIsRefusedAndTurnsTheStoreReadOnly() throws IOException {
		Files.writeString(file(), "{\"version\": 99, \"placements\": []}",
			StandardCharsets.UTF_8);
		BlueprintPlacementStore store = new BlueprintPlacementStore(this::file);
		assertTrue(store.load().isEmpty());
		assertFalse(store.isWritable(),
			"读不懂就绝不能写回去——旧 jar 覆盖掉的是玩家正在盖的建筑");

		store.save(List.of(placement("shelter_wood", BlockPos.ORIGIN,
			Direction.NORTH, BlueprintPlacement.State.GHOST)));
		assertTrue(Files.readString(file()).contains("99"), "文件没有被覆盖");
	}

	@Test
	void aCorruptRowDoesNotSinkTheHealthyOnes() throws IOException {
		BlueprintPlacement good = placement("storage_shed", new BlockPos(1, 2, 3),
			Direction.SOUTH, BlueprintPlacement.State.GHOST);
		new BlueprintPlacementStore(this::file).save(List.of(good));
		String json = Files.readString(file());
		json = json.replace("{\"placementId\"",
			"{\"placementId\": \"not-a-uuid\"}, {\"placementId\"");
		Files.writeString(file(), json, StandardCharsets.UTF_8);

		List<BlueprintPlacement> loaded = new BlueprintPlacementStore(this::file).load();
		assertEquals(1, loaded.size(), "坏行只跳过它自己");
		assertEquals("storage_shed", loaded.get(0).blueprintId);
	}

	@Test
	void anUnknownStateFallsBackToGhostRatherThanFailingTheRow() throws IOException {
		BlueprintPlacement good = placement("shelter_wood", BlockPos.ORIGIN,
			Direction.NORTH, BlueprintPlacement.State.READY);
		new BlueprintPlacementStore(this::file).save(List.of(good));
		Files.writeString(file(),
			Files.readString(file()).replace("\"READY\"", "\"FROM_THE_FUTURE\""),
			StandardCharsets.UTF_8);

		List<BlueprintPlacement> loaded = new BlueprintPlacementStore(this::file).load();
		assertEquals(1, loaded.size());
		assertEquals(BlueprintPlacement.State.GHOST, loaded.get(0).state(),
			"认不出的状态退回幽灵：世界零改变的那一档最安全");
	}

	@Test
	void loadingNothingIsNotAnError() {
		assertTrue(new BlueprintPlacementStore(this::file).load().isEmpty());
	}

	@Test
	void committedGeometryIsContentAddressedAndCorruptionNeverOverwritesEscrowReferences() throws IOException {
		var p = placement("keepitlevel_residence", BlockPos.ORIGIN, Direction.NORTH, BlueprintPlacement.State.BUILDING);
		var bounds = new dev.squire.server.world.BoundedRegion(BlockPos.ORIGIN, new BlockPos(2, 3, 2));
		var access = new ConstructionAccessPlan(List.of(), bounds, "", BlockPos.ORIGIN);
		p.snapshot(new Blueprint.Resolved(bounds, List.of(), List.of(), access), true);
		var store = new BlueprintPlacementStore(this::file); store.save(List.of(p));
		var first = com.google.gson.JsonParser.parseString(Files.readString(file())).getAsJsonObject().getAsJsonArray("placements").get(0).getAsJsonObject();
		assertTrue(first.has("snapshotRef")); assertFalse(first.has("snapshot"));
		Path geometry = dir.resolve("blueprint_snapshots").resolve(first.get("snapshotRef").getAsString() + ".json.gz");
		byte[] original = Files.readAllBytes(geometry);
		access.cleanup = true; store.save(List.of(p));
		org.junit.jupiter.api.Assertions.assertArrayEquals(original, Files.readAllBytes(geometry), "mutable progress never rewrites static geometry");
		assertTrue(new BlueprintPlacementStore(this::file).load().get(0).snapshot().access().cleanup);
		Files.write(geometry, new byte[]{1, 2, 3});
		var broken = new BlueprintPlacementStore(this::file); assertTrue(broken.load().isEmpty()); assertFalse(broken.isWritable());
		String protectedFile = Files.readString(file()); broken.save(List.of()); assertEquals(protectedFile, Files.readString(file()));
	}
}
