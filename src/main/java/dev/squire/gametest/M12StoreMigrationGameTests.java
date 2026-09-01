package dev.squire.gametest;

import java.util.UUID;

import dev.squire.server.agent.SquireAgentStateStore;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

/**
 * 方案 0.5（schema v2）迁移验收：v1 档案无损升级、未来版本 fail-closed、
 * 坏行不拖垮好行、profile/primary 跨存档回环。
 *
 * <p>这些用例本质是纯 NBT 往返，但 {@code AgentRecord} 字段里躺着 ItemStack，
 * 类初始化需要原版 Bootstrap 先把注册表搭起来——headless 单测的类加载器没有
 * Fabric 的转换，Bootstrap 起不来（IllegalAccessError），所以放在 GameTest 环境跑。
 * 全部用例不碰世界，只借环境。</p>
 */
public final class M12StoreMigrationGameTests implements FabricGameTest {
	public static final String FLOOR = M0SpikeGameTests.FLOOR;

	// ------------------------------------------------------------------ helpers

	/** 与 SquireAgentStateStore.writeRecord 的 v1 字段集保持一致的档案条目。 */
	private static NbtCompound recordNbt(UUID owner, UUID agent, String mode) {
		NbtCompound c = new NbtCompound();
		c.putUuid("ownerId", owner);
		c.putUuid("agentId", agent);
		c.putString("displayName", "Squire");
		c.putString("model", "alex");
		c.putBoolean("activeBody", false);
		c.putString("movementMode", mode);
		c.putFloat("health", 17.5f);
		return c;
	}

	private static NbtCompound storeNbt(int version, NbtCompound... records) {
		NbtCompound root = new NbtCompound();
		root.putInt("schemaVersion", version);
		NbtList list = new NbtList();
		for (NbtCompound record : records) {
			list.add(record);
		}
		root.put("records", list);
		return root;
	}

	// ------------------------------------------------------------------ cases

	@GameTest(templateName = FLOOR, batchId = "squire-store-v1-roster")
	public void v1FileBecomesAOneElementRoster(TestContext context) {
		UUID owner = UUID.randomUUID();
		UUID agent = UUID.randomUUID();
		var store = SquireAgentStateStore.createFromNbtPublic(
			storeNbt(1, recordNbt(owner, agent, "PATROL")));

		context.assertTrue(store.recordsOfOwner(owner).size() == 1,
			"a v1 file must load as exactly one record per owner");
		var primary = store.recordOfOwner(owner).orElseThrow();
		context.assertTrue(agent.equals(primary.agentId), "agentId preserved");
		context.assertTrue(primary.primary, "the single v1 record must become the primary");
		context.assertTrue("PATROL".equals(primary.movementMode),
			"the stored mode must survive migration (it feeds the restore path)");
		context.assertTrue(dev.squire.server.profile.AutonomyLevel.STANDARD
			.id().equals(primary.profile.autonomy),
			"v2-only fields take their defaults from a v1 file");
		context.complete();
	}

	@GameTest(templateName = FLOOR, batchId = "squire-store-future-version")
	public void futureVersionStaysFailClosedAndRefusesWrites(TestContext context) {
		var store = SquireAgentStateStore.createFromNbtPublic(storeNbt(999,
			recordNbt(UUID.randomUUID(), UUID.randomUUID(), "FOLLOW")));

		context.assertTrue(store.isFailClosed(), "future version must fail closed");
		context.assertTrue(store.allRecords().isEmpty(),
			"a refused future schema must not expose half-loaded records");
		var fresh = new SquireAgentStateStore.AgentRecord(
			UUID.randomUUID(), UUID.randomUUID());
		boolean refused;
		try {
			store.put(fresh);
			refused = false;
		} catch (IllegalStateException expected) {
			refused = true;
		}
		context.assertTrue(refused,
			"fail-closed means writes are refused, not silently redirected");
		context.complete();
	}

	@GameTest(templateName = FLOOR, batchId = "squire-store-corrupt-row")
	public void corruptRecordIsSkippedNotFatal(TestContext context) {
		NbtCompound good = recordNbt(UUID.randomUUID(), UUID.randomUUID(), "STAY");
		NbtCompound bad = new NbtCompound();
		bad.putString("ownerId", "not-a-uuid"); // readRecord 在 getUuid 上抛异常
		bad.putString("agentId", "also-bad");

		var store = SquireAgentStateStore.createFromNbtPublic(storeNbt(2, bad, good));

		context.assertTrue(store.allRecords().size() == 1,
			"one corrupt row must not sink the healthy ones");
		context.assertFalse(store.isFailClosed(), "corrupt row is not fatal");
		context.complete();
	}

	@GameTest(templateName = FLOOR, batchId = "squire-store-v2-roundtrip")
	public void v2RoundTripsProfileAndPrimaryFlag(TestContext context) {
		UUID owner = UUID.randomUUID();
		var source = new SquireAgentStateStore();
		var first = source.createRecord(owner, "Aldous");
		first.profile.roleId = "guard";
		first.profile.autonomy = dev.squire.server.profile.AutonomyLevel.PROACTIVE.id();
		var second = new SquireAgentStateStore.AgentRecord(owner, UUID.randomUUID());
		second.setDisplayName("Beatrix");
		source.put(second); // 无显式 primary → first 保持 primary
		context.assertTrue(first.primary, "first record becomes primary");
		context.assertFalse(second.primary, "second record is not primary yet");

		// recordOfAgent 必须走直接索引：按 owner 绕行会把 second 解析成 first——
		// 实体加载路径上那就是把第二只随从当成陌生实体 discard 的同一个 bug。
		context.assertTrue(first == source.recordOfAgent(first.agentId).orElseThrow(),
			"recordOfAgent must use the direct index");
		context.assertTrue(second == source.recordOfAgent(second.agentId).orElseThrow(),
			"the second agent must resolve to its own record");
		context.assertTrue(first == source.recordOfOwner(owner).orElseThrow(),
			"primary resolves through recordOfOwner");

		var reloaded = SquireAgentStateStore.createFromNbtPublic(
			source.writeNbt(new NbtCompound()));
		var loadedFirst = reloaded.recordOfAgent(first.agentId).orElseThrow();
		var loadedSecond = reloaded.recordOfAgent(second.agentId).orElseThrow();
		context.assertTrue(loadedFirst.primary && !loadedSecond.primary,
			"the primary flag must round-trip");
		context.assertTrue("guard".equals(loadedFirst.profile.roleId), "roleId round-trips");
		context.assertTrue(dev.squire.server.profile.AutonomyLevel.PROACTIVE
			.id().equals(loadedFirst.profile.autonomy),
			"autonomy round-trips");
		context.assertTrue("Aldous".equals(loadedFirst.displayName), "name round-trips");
		context.assertTrue("Beatrix".equals(loadedSecond.displayName), "name round-trips");
		context.complete();
	}

	@GameTest(templateName = FLOOR, batchId = "squire-store-set-primary")
	public void setPrimaryRepointsTheDefaultCompanion(TestContext context) {
		UUID owner = UUID.randomUUID();
		var store = new SquireAgentStateStore();
		var first = store.createRecord(owner, "A");
		var second = new SquireAgentStateStore.AgentRecord(owner, UUID.randomUUID());
		store.put(second);
		store.setPrimary(second.agentId);
		context.assertTrue(second == store.recordOfOwner(owner).orElseThrow(),
			"setPrimary repoints recordOfOwner");
		context.assertFalse(first.primary, "previous primary is demoted");
		context.assertTrue(second.primary, "new primary is set");
		context.complete();
	}
}
