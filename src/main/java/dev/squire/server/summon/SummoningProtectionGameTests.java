package dev.squire.server.summon;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import dev.squire.gametest.M0SpikeGameTests;
import dev.squire.server.runtime.SquireRuntime;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/** Regression coverage for protected blocks consumed by the ritual. */
public final class SummoningProtectionGameTests implements FabricGameTest {
	@GameTest(templateName = M0SpikeGameTests.FLOOR)
	public void protectedRitualBlockRefusesConsumption(TestContext context) {
		SquireRuntime.ensureInitialized(context.getWorld().getServer());
		SquireRuntime runtime = SquireRuntime.get();
		ServerWorld world = context.getWorld();
		FakePlayer player = FakePlayer.get(world, new GameProfile(
			UUID.nameUUIDFromBytes("protected-ritual-owner".getBytes(StandardCharsets.UTF_8)),
			"protected-ritual-owner"));
		BlockPos feet = context.getAbsolutePos(new BlockPos(3, 2, 3));
		BlockPos centre = feet.up();
		BlockPos left = centre.east();
		BlockPos right = centre.west();
		BlockPos head = centre.up();
		world.setBlockState(feet, Blocks.OAK_FENCE.getDefaultState());
		world.setBlockState(centre, Blocks.HAY_BLOCK.getDefaultState());
		world.setBlockState(left, Blocks.WHITE_WOOL.getDefaultState());
		world.setBlockState(right, Blocks.BLACK_WOOL.getDefaultState());
		world.setBlockState(head, Blocks.CARVED_PUMPKIN.getDefaultState());
		BlockPos protectedPos = right;

		context.runAtTick(2, () -> {
			var denyOneCell = new dev.squire.server.world.ProtectionAdapter() {
				private PermissionDecision check(BlockPos pos) {
					return protectedPos.equals(pos)
						? PermissionDecision.deny("claimed by another player")
						: PermissionDecision.allow();
				}
				@Override public PermissionDecision canBreak(ServerWorld w, BlockPos p, UUID a) { return check(p); }
				@Override public PermissionDecision canPlace(ServerWorld w, BlockPos p, UUID a) { return check(p); }
				@Override public PermissionDecision canInteract(ServerWorld w, BlockPos p, UUID a) { return check(p); }
				@Override public PermissionDecision canEditRegion(ServerWorld w,
						dev.squire.server.world.BoundedRegion r, UUID a) { return PermissionDecision.allow(); }
			};
			context.assertTrue(!SummoningService.tryActivate(player, head, denyOneCell),
				"a denied block in the ritual must stop activation");
			context.assertTrue(world.getBlockState(head).isOf(Blocks.CARVED_PUMPKIN)
					&& world.getBlockState(centre).isOf(Blocks.HAY_BLOCK)
					&& world.getBlockState(protectedPos).isOf(Blocks.BLACK_WOOL)
					&& runtime.agentStore().recordsOfOwner(player.getUuid()).isEmpty(),
				"denied rituals keep every block and create no companion");
			context.complete();
		});
	}
}
