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
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

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
		BlockPos head = buildDummyBody(context, alongX);
		context.getWorld().setBlockState(head, Blocks.CARVED_PUMPKIN.getDefaultState());
		return head;
	}

	private static BlockPos buildDummyBody(TestContext context, boolean alongX) {
		ServerWorld world = context.getWorld();
		BlockPos feet = context.getAbsolutePos(new BlockPos(3, 2, 3));
		BlockPos centre = feet.up();
		BlockPos arm = alongX ? centre.east() : centre.south();
		BlockPos otherArm = alongX ? centre.west() : centre.north();
		world.setBlockState(feet, Blocks.OAK_FENCE.getDefaultState());
		world.setBlockState(centre, Blocks.HAY_BLOCK.getDefaultState());
		world.setBlockState(arm, Blocks.WHITE_WOOL.getDefaultState());
		world.setBlockState(otherArm, Blocks.BLACK_WOOL.getDefaultState());
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
			ItemStack starter = ItemStack.EMPTY;
			for (int slot = 0; slot < first.getInventory().size(); slot++) {
				ItemStack stack = first.getInventory().getStack(slot);
				if (stack.isOf(SquireItems.RECALL_BELL)) {
					starter = stack;
					break;
				}
			}
			context.assertTrue(!starter.isEmpty(), "the starter recall bell must be present");
			context.assertTrue(!starter.getOrCreateNbt().containsUuid(SquireItems.NBT_AGENT),
				"a newly obtained bell must stay unbound until used on a squire");
			rt.executeControl(first, SquireRuntime.ControlIntent.DISMISS);

			FakePlayer second = owner(context.getWorld(), "ritual-z-owner");
			BlockPos secondHead = buildDummy(context, false);
			context.assertTrue(SummoningService.tryActivate(second, secondHead),
				"z-axis training dummy should awaken");
			rt.executeControl(second, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
	}

	@GameTest(templateName = FLOOR, tickLimit = 80)
	public void ritualOnlyConsumesAHeadAfterThePlayerSuccessfullyPlacesIt(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer player = owner(world, "ritual-placement-evidence-owner");
		BlockPos rejectedHead = buildDummyBody(context, true);
		BlockPos rejectedCentre = rejectedHead.down();
		player.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.CARVED_PUMPKIN, 2));
		context.runAtTick(2, () -> {
			BlockHitResult rejectedHit = new BlockHitResult(
				Vec3d.ofCenter(rejectedCentre).add(0, 0.5, 0), Direction.UP,
				rejectedCentre, false);
			UseBlockCallback.EVENT.invoker().interact(player, world, Hand.MAIN_HAND,
				rejectedHit);
			world.setBlockState(rejectedHead, Blocks.CARVED_PUMPKIN.getDefaultState());
		});
		context.runAtTick(3, () -> {
			context.assertTrue(rt.agentStore().recordsOfOwner(player.getUuid()).isEmpty(),
				"an unchanged held stack proves the interaction did not place this head");
			context.assertTrue(world.getBlockState(rejectedHead).isOf(Blocks.CARVED_PUMPKIN),
				"a failed placement attempt must leave the ritual intact");
		});

		BlockPos placedHead = rejectedHead;
		BlockPos placedCentre = placedHead.down();
		player.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.CARVED_PUMPKIN, 2));
		context.runAtTick(5, () -> {
			world.setBlockState(placedHead, Blocks.AIR.getDefaultState());
			BlockHitResult placedHit = new BlockHitResult(
				Vec3d.ofCenter(placedCentre).add(0, 0.5, 0), Direction.UP,
				placedCentre, false);
			UseBlockCallback.EVENT.invoker().interact(player, world, Hand.MAIN_HAND,
				placedHit);
			world.setBlockState(placedHead, Blocks.CARVED_PUMPKIN.getDefaultState());
			player.getStackInHand(Hand.MAIN_HAND).decrement(1);
		});
		context.runAtTick(8, () -> {
			context.assertTrue(rt.agentStore().recordsOfOwner(player.getUuid()).size() == 1,
				"a successfully consumed head should activate the ritual");
			context.assertTrue(world.getBlockState(placedHead).isAir(),
				"the ritual consumes the structure after placement evidence and protection checks");
			rt.executeControl(player, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
	}

	@GameTest(templateName = FLOOR)
	public void secondProfessionSquireCoexistsAndBindsItsOwnBell(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer player = owner(context.getWorld(), "ritual-two-squires-owner");
		context.runAtTick(2, () -> {
			AvatarEntity first = rt.summonFor(player);
			rt.profileOf(first).profession.setProfession(
				dev.squire.server.profession.SquireProfession.ENGINEER);
			rt.persistSnapshot(first);

			BlockPos head = buildDummy(context, false);
			context.assertTrue(SummoningService.tryActivate(player, head),
				"a trained first squire must allow one additional ritual");
			context.assertTrue(rt.agentStore().recordsOfOwner(player.getUuid()).size() == 2,
				"the owner should now have two durable identities");
			context.assertTrue(rt.agents().resolveAllForOwner(player.getUuid()).size() == 2,
				"both bodies must remain active at once");
			AvatarEntity second = rt.agents().resolveAllForOwner(player.getUuid()).stream()
				.filter(candidate -> !candidate.agentId().equals(first.agentId()))
				.findFirst().orElseThrow();
			ItemStack bell = new ItemStack(SquireItems.RECALL_BELL);
			context.assertTrue(rt.bindRecallBell(player, second, bell).success(),
				"an unbound bell should bind to the exact clicked body");
			context.assertTrue(bell.getNbt().getUuid(SquireItems.NBT_AGENT)
				.equals(second.agentId()), "the bell must store the second permanent id");

			BlockPos thirdHead = buildDummy(context, true);
			context.assertTrue(!SummoningService.tryActivate(player, thirdHead),
				"the third ritual must be refused");
			context.assertTrue(context.getWorld().getBlockState(thirdHead)
				.isOf(Blocks.CARVED_PUMPKIN), "a refused third ritual consumes nothing");
			first.discard();
			second.discard();
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
