package dev.squire.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 权限页勾选的持久化。
 *
 * <p>玩家在面板上勾掉一个开关、退出重进，期待的是「我改过了」这件事还在。
 * 在此之前 PermissionManager 是纯内存的，重启后静默退回默认值，玩家毫无感知。
 * 这组测试守住：显式 grant/revoke 必须跨「重启」（save → 新 manager → load）成立；
 * 坏文件、未来版本都不能把权限系统拖垮——宁可回默认值，不能拒启动。</p>
 */
class PermissionStoreTest {

	@TempDir
	Path dir;

	private Path file() {
		return dir.resolve("permissions.json");
	}

	/**
	 * 面板的权限页在单人存档里必须真的能用。
	 *
	 * <p>单人开作式时玩家永远是 4 级权限，而 {@code has} 以前把
	 * {@code isAdmin} 放在最前面直接 return true——于是点一下确实记下了 revoke，
	 * 勾却永远是勾，一整页开关全是死的。现在显式收回优先于管理员身份。</p>
	 */
	@Test
	void anAdminCanStillSwitchANodeOff() {
		PermissionManager pm = new PermissionManager();
		UUID op = UUID.randomUUID();
		assertTrue(pm.has(op, true, PermissionNodes.WORLD_BREAK),
			"an operator holds everything by default");

		pm.revoke(op, PermissionNodes.WORLD_BREAK);
		assertFalse(pm.has(op, true, PermissionNodes.WORLD_BREAK),
			"他亲手关掉的那一个，管理员身份不帮他打开");
		assertTrue(pm.has(op, true, PermissionNodes.WORLD_PLACE),
			"其它节点不受影响");

		pm.grant(op, PermissionNodes.WORLD_BREAK);
		assertTrue(pm.has(op, true, PermissionNodes.WORLD_BREAK),
			"再点一下就回来了——开关必须是双向的");
	}

	@Test
	void grantsAndRevocationsSurviveARoundTrip() {
		PermissionManager original = new PermissionManager();
		original.attachStore(new PermissionStore(this::file));
		UUID player = UUID.randomUUID();
		original.grant(player, PermissionNodes.WORLD_EDIT); // 不在默认集里
		original.revoke(player, PermissionNodes.WORLD_PLACE); // 在默认集里
		original.grant(player, PermissionNodes.COMMAND_GIVE);

		PermissionManager restored = new PermissionManager();
		restored.attachStore(new PermissionStore(this::file));
		assertEquals(1, restored.loadFromDisk());
		assertTrue(restored.has(player, false, PermissionNodes.WORLD_EDIT),
			"explicit grant beyond the defaults must survive");
		assertFalse(restored.has(player, false, PermissionNodes.WORLD_PLACE),
			"explicit revoke of a default must survive");
		assertTrue(restored.has(player, false, PermissionNodes.COMMAND_GIVE));
		// 没动过的默认权限不受影响
		assertTrue(restored.has(player, false, PermissionNodes.USE));
	}

	@Test
	void playerPreferenceCannotGrantOrOverrideServerDenialAndPersists() {
		PermissionManager manager = new PermissionManager();
		manager.attachStore(new PermissionStore(this::file));
		UUID player = UUID.randomUUID();
		assertFalse(manager.setPlayerEnabled(player, PermissionNodes.WORLD_EDIT, true),
			"players cannot grant nodes outside server policy");
		assertTrue(manager.setPlayerEnabled(player, PermissionNodes.COMMAND_GIVE, false));
		assertFalse(manager.has(player, false, PermissionNodes.COMMAND_GIVE));
		manager.grant(player, PermissionNodes.WORLD_EDIT);
		assertTrue(manager.setPlayerEnabled(player, PermissionNodes.WORLD_EDIT, false));
		manager.revoke(player, PermissionNodes.COMMAND_GIVE);
		assertFalse(manager.setPlayerEnabled(player, PermissionNodes.COMMAND_GIVE, true),
			"a player cannot re-enable a node denied by the server");
		PermissionManager restored = new PermissionManager();
		restored.attachStore(new PermissionStore(this::file));
		assertEquals(1, restored.loadFromDisk());
		assertFalse(restored.has(player, false, PermissionNodes.COMMAND_GIVE));
		assertFalse(restored.has(player, false, PermissionNodes.WORLD_EDIT));
	}

	@Test
	void legacyUnauthenticatedGrantDoesNotBecomeAnAdminOverride() throws IOException {
		UUID player = UUID.randomUUID();
		Files.writeString(file(), "{\"version\":1,\"granted\":{\"" + player
			+ "\":[\"" + PermissionNodes.WORLD_EDIT + "\"]},\"revoked\":{}}",
			StandardCharsets.UTF_8);
		PermissionManager restored = new PermissionManager();
		restored.attachStore(new PermissionStore(this::file));
		restored.loadFromDisk();
		assertFalse(restored.has(player, false, PermissionNodes.WORLD_EDIT));
	}

	@Test
	void secondPlayerRoundTripsIndependently() {
		PermissionManager original = new PermissionManager();
		original.attachStore(new PermissionStore(this::file));
		UUID alice = UUID.randomUUID();
		UUID bob = UUID.randomUUID();
		original.grant(alice, PermissionNodes.WORLD_EDIT);
		original.revoke(bob, PermissionNodes.WORLD_PLACE);

		PermissionManager restored = new PermissionManager();
		restored.attachStore(new PermissionStore(this::file));
		assertEquals(2, restored.loadFromDisk());
		assertTrue(restored.has(alice, false, PermissionNodes.WORLD_EDIT));
		assertTrue(restored.has(alice, false, PermissionNodes.WORLD_PLACE),
			"alice never revoked this default, it must still hold");
		assertTrue(restored.has(bob, false, PermissionNodes.WORLD_PLACE) == false);
		assertFalse(restored.has(bob, false, PermissionNodes.WORLD_EDIT));
	}

	@Test
	void futureSchemaIsIgnoredAndDefaultsStillApply() throws IOException {
		Files.writeString(file(), "{\"version\":999,\"granted\":{},\"revoked\":{}}",
			StandardCharsets.UTF_8);
		PermissionManager manager = new PermissionManager();
		manager.attachStore(new PermissionStore(this::file));
		assertEquals(0, manager.loadFromDisk());
		UUID player = UUID.randomUUID();
		assertTrue(manager.has(player, false, PermissionNodes.USE),
			"defaults must still apply after a refused load");
	}

	@Test
	void corruptFileDoesNotThrowAndDefaultsStillApply() throws IOException {
		Files.writeString(file(), "{{{not json", StandardCharsets.UTF_8);
		PermissionManager manager = new PermissionManager();
		manager.attachStore(new PermissionStore(this::file));
		assertEquals(0, manager.loadFromDisk());
		assertTrue(manager.has(UUID.randomUUID(), false, PermissionNodes.USE));
	}

	@Test
	void managerWithoutStoreStillWorks() {
		// 测试与无存档场景：不挂 store 也必须能正常授权/收回，只是不落盘。
		PermissionManager manager = new PermissionManager();
		UUID player = UUID.randomUUID();
		manager.grant(player, PermissionNodes.WORLD_EDIT);
		manager.revoke(player, PermissionNodes.WORLD_PLACE);
		assertTrue(manager.has(player, false, PermissionNodes.WORLD_EDIT));
		assertFalse(manager.has(player, false, PermissionNodes.WORLD_PLACE));
		assertEquals(0, manager.loadFromDisk());
		manager.reset();
		assertFalse(manager.has(player, false, PermissionNodes.WORLD_EDIT));
	}
}
