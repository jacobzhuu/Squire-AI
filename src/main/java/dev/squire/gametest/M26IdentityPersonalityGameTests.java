package dev.squire.gametest;

import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import dev.squire.server.agent.SquireAgentStateStore;
import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.profile.Trait;
import dev.squire.server.runtime.SquirePersonalityService;
import dev.squire.server.runtime.SquireRuntime;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

/** End-to-end identity and personality acceptance tests. */
public final class M26IdentityPersonalityGameTests implements FabricGameTest {
	public static final String FLOOR = M0SpikeGameTests.FLOOR;

	private static FakePlayer owner(TestContext context, String name) {
		FakePlayer player = FakePlayer.get(context.getWorld(),
			new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name));
		context.getWorld().spawnEntity(player);
		return player;
	}

	private static SquireRuntime runtime(TestContext context) {
		SquireRuntime.ensureInitialized(context.getWorld().getServer());
		return SquireRuntime.get();
	}

	private static void cleanUp(SquireRuntime runtime, FakePlayer owner) {
		runtime.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
	}

	@GameTest(templateName = FLOOR, tickLimit = 120, batchId = "squire-identity")
	public void renameIsValidatedPerIdentityAndSurvivesStoreReload(TestContext context) {
		SquireRuntime runtime = runtime(context);
		FakePlayer owner = owner(context, "identity-owner");

		context.runAtTick(5, () -> {
			AvatarEntity avatar = runtime.summonFor(owner);
			context.assertTrue(runtime.renameAgentFromPanel(owner, avatar, "豆包").success(),
				"左上角提交必须走服务端改名成功");
			context.assertTrue("豆包".equals(runtime.displayNameOf(avatar)),
				"状态包读取的长期名字必须立刻变化");

			context.assertFalse(runtime.renameAgentFromPanel(owner, avatar, "   ").success(),
				"服务端拒绝空名字");
			context.assertFalse(runtime.renameAgentFromPanel(owner, avatar,
				"假名\n[管理员]").success(), "服务端拒绝控制字符");
			context.assertTrue("豆包".equals(runtime.displayNameOf(avatar)),
				"失败请求不得污染原身份");

			NbtCompound saved = runtime.agentStore().writeNbt(new NbtCompound());
			var loaded = SquireAgentStateStore.createFromNbtPublic(saved)
				.recordOfAgent(avatar.agentId()).orElseThrow();
			context.assertTrue("豆包".equals(loaded.displayName),
				"世界状态重载后名字仍在同一 agentId 上");

			var secondRecord = runtime.agentStore().createRecord(owner.getUuid(), "二号");
			AvatarEntity second = dev.squire.server.registry.SquireEntities.AVATAR
				.create(context.getWorld());
			context.assertTrue(second != null, "第二个身份的测试身体必须可创建");
			second.assignAgentId(secondRecord.agentId);
			second.setOwner(owner.getUuid());
			context.getWorld().spawnEntity(second);
			context.assertTrue(runtime.renameAgentFromPanel(owner, second, "阿福").success(),
				"面板绑定的第二个 agentId 可以独立改名");
			context.assertTrue("豆包".equals(runtime.displayNameOf(avatar)),
				"第二只改名不能影响第一只");
			context.assertTrue("阿福".equals(runtime.displayNameOf(second)),
				"第二只只修改自己的档案");
			second.discard();
			cleanUp(runtime, owner);
			context.complete();
		});
	}

	@GameTest(templateName = FLOOR, tickLimit = 120, batchId = "squire-personality")
	public void rerollCombinesInventoriesConsumesExactlyFourAndPersists(
			TestContext context) {
		SquireRuntime runtime = runtime(context);
		FakePlayer owner = owner(context, "personality-owner");

		context.runAtTick(5, () -> {
			AvatarEntity avatar = runtime.summonFor(owner);
			var profile = runtime.profileOf(avatar);
			profile.traits.clear();
			profile.traits.add(Trait.CAUTIOUS.id());
			profile.traits.add(Trait.STURDY.id());
			avatar.applyTraits(profile.traits);
			List<String> before = List.copyOf(profile.traits);

			avatar.items().mainInventory().setStack(0,
				new ItemStack(Items.AMETHYST_SHARD, 1));
			owner.getInventory().setStack(0, new ItemStack(Items.AMETHYST_SHARD, 2));
			context.assertTrue(SquirePersonalityService.countShards(owner, avatar) == 3,
				"服务端统计必须合并侍从和玩家背包");
			context.assertFalse(runtime.rerollPersonality(owner, avatar).success(),
				"总数不足 4 时不能洗练");
			context.assertTrue(SquirePersonalityService.countShards(owner, avatar) == 3,
				"不足时一片也不能扣");
			context.assertTrue(before.equals(profile.traits), "不足时原性格不能变化");

			owner.getInventory().setStack(0, new ItemStack(Items.AMETHYST_SHARD, 3));
			context.assertTrue(runtime.rerollPersonality(owner, avatar).success(),
				"两边背包合计正好 4 个必须可以洗练");
			context.assertTrue(SquirePersonalityService.countShards(owner, avatar) == 0,
				"成功后必须正好扣除 4 个");
			context.assertTrue(profile.traits.size() == 2,
				"原来两个 Trait，洗练后仍然两个");
			context.assertTrue(profile.traitList().stream().distinct().count() == 2,
				"两个 Trait 不得重复");
			context.assertFalse(profile.traits.containsAll(before),
				"洗练不能原样返回同一组性格");

			NbtCompound saved = runtime.agentStore().writeNbt(new NbtCompound());
			var loaded = SquireAgentStateStore.createFromNbtPublic(saved)
				.recordOfAgent(avatar.agentId()).orElseThrow();
			context.assertTrue(loaded.profile.traits.equals(profile.traits),
				"性格同步到长期档案并在世界重载后保持");
			cleanUp(runtime, owner);
			context.complete();
		});
	}

	@GameTest(templateName = FLOOR, tickLimit = 120, batchId = "squire-personality")
	public void sturdyAndSwiftApplyRealIdempotentAttributes(TestContext context) {
		SquireRuntime runtime = runtime(context);
		FakePlayer owner = owner(context, "personality-attributes-owner");

		context.runAtTick(5, () -> {
			AvatarEntity avatar = runtime.summonFor(owner);
			avatar.applyTraits(List.of());
			double baseHealth = avatar.getMaxHealth();
			double baseSpeed = avatar.getAttributeValue(
				net.minecraft.entity.attribute.EntityAttributes.GENERIC_MOVEMENT_SPEED);

			avatar.applyTraits(List.of(Trait.STURDY.id(), Trait.SWIFT.id()));
			context.assertTrue(avatar.getMaxHealth() == baseHealth
				+ Trait.STURDY_MAX_HEALTH_BONUS, "皮实必须真实增加 2 点最大生命");
			context.assertTrue(Math.abs(avatar.getAttributeValue(
				net.minecraft.entity.attribute.EntityAttributes.GENERIC_MOVEMENT_SPEED)
				- baseSpeed * Trait.SWIFT_SPEED_FACTOR) < 0.0001,
				"轻捷必须真实增加 15% 移速");

			avatar.applyTraits(List.of(Trait.STURDY.id(), Trait.SWIFT.id()));
			context.assertTrue(avatar.getMaxHealth() == baseHealth
				+ Trait.STURDY_MAX_HEALTH_BONUS, "重复同步不能叠加 Trait 属性");
			cleanUp(runtime, owner);
			context.complete();
		});
	}
}
