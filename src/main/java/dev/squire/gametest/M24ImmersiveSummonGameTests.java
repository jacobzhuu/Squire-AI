package dev.squire.gametest;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.registry.SquireItems;
import dev.squire.server.runtime.SquireRuntime;
import dev.squire.server.summon.SummoningService;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/** The command-free first-summon ritual and persistent recall-bell lifecycle. */
public final class M24ImmersiveSummonGameTests implements FabricGameTest {

	private static final String FLOOR = M0SpikeGameTests.FLOOR;

	private static FakePlayer owner(ServerWorld world, String name) {
		return FakePlayer.get(world, new GameProfile(
			UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name));
	}

	private static SquireRuntime runtime(TestContext context) {
		SquireRuntime.ensureInitialized(context.getWorld().getServer());
		return SquireRuntime.get();
	}

	private static BlockPos buildDummy(TestContext context, boolean alongX) {
		ServerWorld world = context.getWorld();
		BlockPos feet = context.getAbsolutePos(new BlockPos(3, 2, 3));
		BlockPos centre = feet.up();
		BlockPos arm = alongX ? centre.east() : centre.south();
		BlockPos otherArm = alongX ? centre.west() : centre.north();
		world.setBlockState(feet, Blocks.OAK_FENCE.getDefaultState());
		world.setBlockState(centre, Blocks.HAY_BLOCK.getDefaultState());
		world.setBlockState(arm, Blocks.WHITE_WOOL.getDefaultState());
		world.setBlockState(otherArm, Blocks.BLACK_WOOL.getDefaultState());
		world.setBlockState(centre.up(), Blocks.CARVED_PUMPKIN.getDefaultState());
		return centre.up();
	}

	@GameTest(templateName = FLOOR)
	public void trainingDummyAwakensInBothOrientations(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer first = owner(context.getWorld(), "ritual-x-owner");
		context.runAtTick(2, () -> {
			BlockPos head = buildDummy(context, true);
			context.assertTrue(SummoningService.tryActivate(first, head),
				"x-axis training dummy should awaken");
			AvatarEntity avatar = rt.agents().resolveForOwnerNow(first.getUuid())
				.orElseThrow();
			context.assertTrue(!rt.professionOf(avatar).hasProfession(),
				"the ritual must create an untrained level-0 squire");
			context.assertTrue(first.getInventory().count(SquireItems.RECALL_BELL) == 1,
				"the first ritual grants exactly one recall bell");
			rt.executeControl(first, SquireRuntime.ControlIntent.DISMISS);

			FakePlayer second = owner(context.getWorld(), "ritual-z-owner");
			BlockPos secondHead = buildDummy(context, false);
			context.assertTrue(SummoningService.tryActivate(second, secondHead),
				"z-axis training dummy should awaken");
			rt.executeControl(second, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
	}

	@GameTest(templateName = FLOOR)
	public void invalidOrDuplicateRitualNeverConsumesBlocksOrResetsIdentity(
			TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer player = owner(context.getWorld(), "ritual-duplicate-owner");
		context.runAtTick(2, () -> {
			BlockPos head = buildDummy(context, true);
			context.getWorld().setBlockState(head.down().east(), Blocks.AIR.getDefaultState());
			context.assertTrue(!SummoningService.tryActivate(player, head),
				"an incomplete dummy must not awaken");
			context.assertTrue(context.getWorld().getBlockState(head).isOf(
				Blocks.CARVED_PUMPKIN), "failed ritual must leave its blocks intact");

			AvatarEntity original = rt.summonFor(player);
			UUID agentId = original.agentId();
			BlockPos duplicateHead = buildDummy(context, true);
			context.assertTrue(!SummoningService.tryActivate(player, duplicateHead),
				"a player with a record cannot create a second squire");
			context.assertTrue(rt.agentStore().recordOfOwner(player.getUuid())
				.orElseThrow().agentId.equals(agentId), "identity must not be reset");
			context.assertTrue(context.getWorld().getBlockState(duplicateHead)
				.isOf(Blocks.CARVED_PUMPKIN), "duplicate ritual must not consume blocks");
			rt.executeControl(player, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
	}

	@GameTest(templateName = FLOOR)
	public void recallBellRestoresTheSameIdentityAndRejectsOtherOwners(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer player = owner(context.getWorld(), "recall-owner");
		FakePlayer stranger = owner(context.getWorld(), "recall-stranger");
		context.runAtTick(2, () -> {
			AvatarEntity original = rt.summonFor(player);
			UUID agentId = original.agentId();
			ItemStack bell = SquireItems.boundRecallBell(player.getUuid(), agentId);
			context.assertTrue(!rt.recallWithBell(stranger, bell).success(),
				"a stolen bound bell must be rejected");
			rt.executeControl(player, SquireRuntime.ControlIntent.DISMISS);
			context.assertTrue(rt.recallWithBell(player, bell).success(),
				"the owner should recall a dismissed squire");
			AvatarEntity restored = rt.agents().resolveForOwnerNow(player.getUuid())
				.orElseThrow();
			context.assertTrue(restored.agentId().equals(agentId),
				"recall must preserve the long-term identity");
			rt.executeControl(player, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
	}
}
