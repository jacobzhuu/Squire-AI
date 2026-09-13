package dev.squire.gametest;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.mojang.authlib.GameProfile;

import dev.squire.server.blueprint.Blueprint;
import dev.squire.server.blueprint.BlueprintManager;
import dev.squire.server.blueprint.ConstructionScaffolding;
import dev.squire.server.blueprint.BlueprintStep;
import dev.squire.server.blueprint.BlueprintPlacement;
import dev.squire.server.blueprint.SiteAssessment;
import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.gui.AvatarEquipmentInventory;
import dev.squire.server.gui.SquireScreenHandler;
import dev.squire.server.profile.AutonomyLevel;
import dev.squire.server.profession.SquireProfession;
import dev.squire.server.project.Project;
import dev.squire.server.project.Stage;
import dev.squire.server.runtime.SquireRuntime;
import dev.squire.server.world.Torchlight;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * 第 3 期验收：一句大目标拆成六个阶段，而且<b>卡在谁身上是说得出来的</b>。
 *
 * <p>最要紧的一条断言在交料阶段：它必须 BLOCKED 并列出还缺什么。一个只显示
 * 「进行中」的工程页，和没有这一页是一样的。</p>
 */
public final class M16ProjectGameTests implements FabricGameTest {
	public static final String FLOOR = M0SpikeGameTests.FLOOR;

	/** 一份小到能在 9×4×9 测试场地里跑完的工程蓝图：2×2×2 石壳，掏空一格。 */
	private static final String TINY = "gametest_project_hut";
	private static final String LIGHTING = "gametest_project_lighting";

	private static void registerTiny(SquireRuntime rt) {
		rt.blueprints().registry().register(new Blueprint(TINY, "测试小屋", 1,
			Blueprint.Category.SHELTER, 2, 2, 2,
			List.of(BlueprintStep.place(0, 0, 0, 0, 1, 1, 1, "minecraft:cobblestone",
					"壳", false),
				BlueprintStep.dig(1, 1, 1, 1, 1, 1, 1, "掏空")),
			Set.of()));
	}

	/** A two-cell-high room reproduces the old "first candidate cannot hold a torch" bug. */
	private static void registerLightingRoom(SquireRuntime rt) {
		rt.blueprints().registry().register(new Blueprint(LIGHTING, "测试照明房", 1,
			Blueprint.Category.SHELTER, 3, 3, 3,
			List.of(
				BlueprintStep.place(0, 0, 0, 0, 2, 0, 2,
					"minecraft:cobblestone", "地板", false),
				BlueprintStep.dig(1, 1, 1, 1, 1, 2, 1, "室内空气")),
			Set.of()));
	}

	private static FakePlayer fakeOwner(ServerWorld world, String name) {
		return FakePlayer.get(world,
			new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name));
	}

	private static SquireRuntime runtime(TestContext context) {
		SquireRuntime.ensureInitialized(context.getWorld().getServer());
		return SquireRuntime.get();
	}

	/**
	 * 主人在 (1,2,1)，伙伴站得远一点——备料阶段把东西扔在玩家脚下，
	 * 两人叠在一格时伙伴的自动拾取会把它们当场捡走，
	 * 交料那一步就永远验不到了。
	 */
	private static AvatarEntity summon(TestContext context, SquireRuntime rt,
			FakePlayer owner, BlockPos relative) {
		ServerWorld world = context.getWorld();
		world.spawnEntity(owner);
		Vec3d ownerFeet = Vec3d.ofBottomCenter(context.getAbsolutePos(
			new BlockPos(1, 2, 1)));
		owner.refreshPositionAndAngles(ownerFeet.x, ownerFeet.y, ownerFeet.z, 0.0f, 0.0f);
		AvatarEntity avatar = rt.summonFor(owner);
		rt.profileOf(avatar).profession.setProfession(SquireProfession.ENGINEER);
		rt.profileOf(avatar).profession.level = SquireProfession.MAX_LEVEL;
		Vec3d feet = Vec3d.ofBottomCenter(context.getAbsolutePos(relative));
		avatar.refreshPositionAndAngles(feet.x, feet.y, feet.z, 0.0f, 0.0f);
		avatar.setIdleMode();
		return avatar;
	}

	/**
	 * 把伙伴调到<b>保守</b>档——只有那一档才走「材料先给玩家、再由玩家转交」。
	 * 默认的标准档他自己领料。
	 */
	private static void handOverMode(SquireRuntime rt, AvatarEntity avatar) {
		rt.profileOf(avatar).autonomy = AutonomyLevel.CONSERVATIVE.id();
	}

	private static void cleanUp(SquireRuntime rt, FakePlayer owner) {
		rt.projects().activeOf(owner.getUuid())
			.ifPresent(project -> rt.projects().cancel(project));
		rt.blueprints().activeOf(owner.getUuid())
			.ifPresent(placement -> rt.blueprints().remove(placement.placementId));
		rt.resolveAvatarFor(owner.getUuid()).ifPresent(
			avatar -> rt.scheduler().cancelAgent(avatar.agentId(), "TEST_DONE"));
		rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
	}

	private static Stage stageOf(Project project, Stage.Kind kind) {
		return project.stages().stream().filter(s -> s.kind == kind).findFirst()
			.orElseThrow();
	}

	/**
	 * New workflow: choosing a design creates a zero-write ghost; confirmation starts it.
	 *
	 * <p>确认<b>会预留当前真实存在的材料</b>。料齐时立即开工；不齐时也会建立一个
	 * 暂停的持久工程，等待玩家分批补进工程物资池。使用这个 helper 的测试都已先
	 * 备齐材料，因此预期确认后直接施工。</p>
	 */
	private static void startConfirmed(TestContext context, SquireRuntime rt,
			FakePlayer owner, String blueprintId) {
		var preview = rt.projectStart(owner, blueprintId);
		context.assertTrue(preview.success(), preview.message());
		context.assertTrue(rt.projects().activeOf(owner.getUuid()).isEmpty(),
			"preview must not start a project before confirmation");
		var confirmed = rt.blueprintBuild(owner);
		context.assertTrue(confirmed.success(), confirmed.message());
	}

	/** 把 {@link #TINY} 那一份料交给伙伴，等价于玩家把东西丢在他脚边。 */
	private static void stock(SquireRuntime rt, FakePlayer owner) {
		AvatarEntity avatar = rt.resolveAvatarFor(owner.getUuid()).orElseThrow();
		avatar.items().insert(new ItemStack(Items.COBBLESTONE, 64));
		avatar.items().insert(new ItemStack(Items.TORCH, 8));
	}

	@GameTest(templateName = FLOOR, tickLimit = 2200, batchId = "squire-access-tower")
	public void tallTowerBuildsWithRealStepsAndReclaimsThem(TestContext context) {
		tallTowerBuildsWithRealStepsAndReclaimsThem(context, "access-tower-owner");
	}
	public void tallTowerBuildsWithRealStepsAndReclaimsThem(TestContext context, String ownerName) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), ownerName);
		String id = "gametest_access_tower";
		rt.blueprints().registry().register(new Blueprint(id, "高处施工测试", 1, Blueprint.Category.DEFENCE,
			1, 12, 1, List.of(BlueprintStep.place(0, 0, 0, 0, 0, 11, 0, "minecraft:stone", "tower", false)), Set.of()));
		UUID[] projectId = new UUID[1];
		context.runAtTick(5, () -> {
			var avatar = summon(context, rt, owner, new BlockPos(6, 2, 6));
			context.assertTrue(rt.projectStart(owner, id).success(), "preview placed");
			var placement = rt.blueprints().activeOf(owner.getUuid()).orElseThrow();
			var resolved = rt.blueprints().resolve(placement).orElseThrow();
			var access = resolved.access();
			context.assertTrue(access != null && access.valid(), "plan failed: " + (access == null ? "null" : access.failure));
			context.assertTrue(access.temporaryCount() > 0, "12-block tower needs temporary steps");
			context.assertTrue(access.work.stream().filter(dev.squire.server.blueprint.ConstructionAccessPlan.Work::temporary)
				.allMatch(w -> context.getWorld().getBlockState(w.cell().pos()).isAir()), "preview never writes blocks");
			BlueprintManager.requiredProjectMaterials(context.getWorld(), resolved).forEach((item, count) -> {
				for (int left = count; left > 0; left -= 64) avatar.items().insert(new ItemStack(Registries.ITEM.get(item), Math.min(left, 64)));
			});
			var started = rt.blueprintBuild(owner); context.assertTrue(started.success(), started.message());
			projectId[0] = rt.projects().activeOf(owner.getUuid()).orElseThrow().projectId;
		});
		context.runAtTick(2000, () -> {
			var project = rt.projects().project(projectId[0]).orElseThrow();
			var resolved = rt.blueprints().placement(project.placementId).flatMap(rt.blueprints()::resolve).orElseThrow();
			context.assertTrue(project.state() == Project.State.DONE, "tower did not finish: " + rt.projects().describe(project)
				+ " access=" + resolved.access().cursor + "/" + resolved.access().work.size());
			context.assertTrue(resolved.access().placed.isEmpty(), "temporary ownership cleared");
			for (var w : resolved.access().work) if (w.temporary()) context.assertTrue(context.getWorld().getBlockState(w.cell().pos()).isAir()
				|| ConstructionScaffolding.scaffold(context.getWorld(), w.cell().pos()), "cleanup may only leave a stable scaffold");
			var avatar = rt.agents().resolveByAgentId(project.agentId()).orElseThrow();
			context.assertTrue(avatar.items().countOf(new Identifier("minecraft:scaffolding")) <= resolved.access().temporaryCount(), "cleanup never duplicates temporary items");
			cleanUp(rt, owner); context.complete();
		});
	}


    @GameTest(templateName = FLOOR, tickLimit = 45000, batchId = "squire-access-import-builder")
    public void importedBuilderCompletesInWorld(TestContext c) { importedBuild(c, "keepitlevel_builder_lodge", 0); }
    @GameTest(templateName = FLOOR, tickLimit = 45000, batchId = "squire-access-import-tower")
    public void importedGuardTowerCompletesInWorld(TestContext c) { importedBuild(c, "keepitlevel_guardtower", 1); }
    @GameTest(templateName = FLOOR, tickLimit = 45000, batchId = "squire-access-import-library")
    public void importedLibraryCompletesInWorld(TestContext c) { importedBuild(c, "keepitlevel_library", 2); }
    @GameTest(templateName = FLOOR, tickLimit = 45000, batchId = "squire-access-import-residence")
    public void importedResidenceCompletesInWorld(TestContext c) { importedBuild(c, "keepitlevel_residence", 3); }
    @GameTest(templateName = FLOOR, tickLimit = 45000, batchId = "squire-access-import-warehouse")
    public void importedWarehouseCompletesInWorld(TestContext c) { importedBuild(c, "keepitlevel_warehouse", 4); }
    @GameTest(templateName = FLOOR, tickLimit = 45000, batchId = "squire-access-import-fountain")
    public void importedFountainCompletesInWorld(TestContext c) { importedBuild(c, "keepitlevel_fountain", 5); }

    private static void importedBuild(TestContext context, String id, int index) {
        importedBuild(context, id, index, 10, ignored -> {});
    }
    static void importedBuild(TestContext context, String id, int index, int level, java.util.function.IntConsumer finished) {
        importedBuild(context, id, index, level, (avatar, placement) -> { }, finished);
    }
    static void importedBuild(TestContext context, String id, int index, int level,
            java.util.function.BiConsumer<AvatarEntity, BlueprintPlacement> beforeFunding, java.util.function.IntConsumer finished) {
        importedBuild(context, id, index, level, context.getAbsolutePos(new BlockPos(0, 2, 0)).getY(), beforeFunding, finished);
    }
    static void importedBuild(TestContext context, String id, int index, int level, int siteY,
            java.util.function.BiConsumer<AvatarEntity, BlueprintPlacement> beforeFunding, java.util.function.IntConsumer finished) {
        var rt = runtime(context); var world = context.getWorld();
        var owner = fakeOwner(world, "kit-" + index + "-lv" + level);
        var origin = new BlockPos(4096 + 128 * index, siteY, 4096);
        // Isolated loaded worksite: the small GameTest fixture cannot hold a full imported house.
        for (int x = (origin.getX() - 10) >> 4; x <= (origin.getX() + 36) >> 4; x++)
            for (int z = (origin.getZ() - 10) >> 4; z <= (origin.getZ() + 36) >> 4; z++) world.setChunkForced(x, z, true);
        for (int x = -10; x <= 36; x++) for (int z = -10; z <= 36; z++)
            world.setBlockState(origin.add(x, -1, z), Blocks.STONE.getDefaultState(), 2);
        context.runAtTick(5, () -> {
            owner.refreshPositionAndAngles(origin.getX() - 5.5, origin.getY(), origin.getZ() - 5.5, 0, 0);
            world.spawnEntity(owner);
            var avatar = rt.summonFirstAt(owner, origin.add(-4, 0, -4));
            context.assertTrue(avatar != null, "new Engineer body created at the loaded worksite");
            rt.profileOf(avatar).profession.setProfession(SquireProfession.ENGINEER);
            rt.profileOf(avatar).profession.level = level;
            rt.profileOf(avatar).traits.clear(); // benchmark isolates Engineer level, not random traits
            if (id.startsWith("squire:keepitlevel/")) {
                avatar.items().insert(new ItemStack(Items.DIAMOND_PICKAXE));
                avatar.items().insert(new ItemStack(Items.DIAMOND_AXE));
                avatar.items().insert(new ItemStack(Items.DIAMOND_SHOVEL));
            }
            avatar.setIdleMode();
            // Forced remote chunks promote entity sections asynchronously.
            context.runAtTick(100, () -> {
            context.assertTrue(avatar.isAlive() && !avatar.isRemoved(), id + " body: alive=" + avatar.isAlive() + " removed=" + avatar.isRemoved() + " health=" + avatar.getHealth());
            context.assertTrue(rt.profileOf(avatar).profession.profession() == SquireProfession.ENGINEER, id + " profile changed");
            rt.agents().resolveForOwnerNow(owner.getUuid());
            var start = rt.agents().withTarget(owner.getUuid(), avatar.agentId(), () -> rt.projectStart(owner, id)); context.assertTrue(start.success(), id + ": " + start.message()
                + " lookup=" + rt.agents().resolveByAgentId(avatar.agentId()).map(a -> a.getUuid() + "/" + rt.profileOf(a).profession.profession()).orElse("missing") + " world=" + world.getEntity(avatar.getUuid()));
            var placement = rt.blueprints().activeOf(owner.getUuid()).orElseThrow();
            context.assertTrue(placement.relocate(origin, Direction.NORTH), "relocate ghost");
            beforeFunding.accept(avatar, placement);
            var resolved = rt.blueprints().resolve(placement).orElseThrow();
            context.assertTrue(resolved.access() != null && resolved.access().valid(), id + ": " + (resolved.access() == null ? "no access" : resolved.access().failure));
            var required = BlueprintManager.requiredProjectMaterials(world, resolved);
            // All stock enters through real backpacks; overflow is deposited in multiple batches.
            for (var entry : required.entrySet()) for (int left = entry.getValue(); left > 0;) {
                int count = Math.min(64, left);
                ItemStack remaining = avatar.items().insert(new ItemStack(Registries.ITEM.get(entry.getKey()), count));
                left -= count - remaining.getCount();
                if (!remaining.isEmpty()) {
                    if (rt.projects().activeOf(owner.getUuid()).isEmpty()) rt.blueprintBuild(owner); else rt.projectResume(owner);
                    context.assertTrue(avatar.items().insertableAmount(remaining) > 0, "deposit must free backpack space");
                }
            }
            var funded = rt.projects().activeOf(owner.getUuid()).isEmpty() ? rt.blueprintBuild(owner) : rt.projectResume(owner);
            context.assertTrue(funded.success(), id + ": " + funded.message());
            var project = rt.projects().activeOf(owner.getUuid()).orElseThrow();
            context.assertTrue(project.missingFrom(required).isEmpty(), "exact bill fully funded");
            pollImported(context, rt, owner, project, resolved, 125, ticks -> {
                finished.accept(ticks);
                // Completed matrix fixtures must not keep hundreds of remote chunks
                // ticking throughout later batches. Every fixture has its own index.
                for (int x = (origin.getX() - 10) >> 4; x <= (origin.getX() + 36) >> 4; x++)
                    for (int z = (origin.getZ() - 10) >> 4; z <= (origin.getZ() + 36) >> 4; z++) world.setChunkForced(x, z, false);
                owner.discard();
            });
            });
        });
    }
    private static void pollImported(TestContext context, SquireRuntime rt, FakePlayer owner, Project project,
            Blueprint.Resolved resolved, int tick, java.util.function.IntConsumer finished) {
        context.runAtTick(tick, () -> {
            if (tick % 1000 == 0) org.slf4j.LoggerFactory.getLogger(M16ProjectGameTests.class).info("Import progress {} tick={} cursor={} owned={} worker={}",
                project.blueprintId, tick, resolved.access().cursor, resolved.access().placed.size(),
                rt.agents().resolveByAgentId(project.agentId()).map(a -> a.getPos() + " health=" + a.getHealth()).orElse("missing"));
            context.assertTrue(project.state() != Project.State.PAUSED, project.blueprintId + ": " + rt.projects().describe(project)
                + " cursor=" + resolved.access().cursor + "/" + resolved.access().work.size()
                + " worker=" + rt.agents().resolveByAgentId(project.agentId()).map(a -> a.getPos().toString()).orElse("missing"));
            if (project.state() != Project.State.DONE) {
                context.assertTrue(tick < 44000, "build deadline: " + project.blueprintId + " " + rt.projects().describe(project)
                    + " cursor=" + resolved.access().cursor + "/" + resolved.access().work.size() + " temporary=" + resolved.access().placed.size());
                pollImported(context, rt, owner, project, resolved, tick + 25, finished); return;
            }
            for (var cell : resolved.toPlace()) if (!cell.optional())
                context.assertTrue(BlueprintManager.matches(context.getWorld().getBlockState(cell.pos()), cell), "preview mismatch at " + cell.pos());
            context.assertTrue(resolved.access().placed.isEmpty(), "temporary blocks reclaimed");
            context.assertTrue(project.pendingMutation().isEmpty() && project.reservedMaterials().isEmpty(), "final refunds and settlement completed");
            finished.accept(tick - 100); cleanUp(rt, owner);
            // activeOf deliberately excludes DONE records. Retaining those records
            // here makes every later test checkpoint serialize all previous fixtures.
            rt.blueprints().remove(project.placementId);
            rt.projects().remove(project.projectId);
            context.complete();
        });
    }


    @GameTest(templateName = FLOOR, tickLimit = 4000, batchId = "squire-access-cancel")
    public void accessCancellationSurvivesSnapshotRecoveryAndPreservesPlayerEdits(TestContext context) {
        var rt = runtime(context); var owner = fakeOwner(context.getWorld(), "access-cancel");
        String id = "gametest_cancel_tower";
        rt.blueprints().registry().register(new Blueprint(id, "cancel tower", 1, Blueprint.Category.DEFENCE, 1, 12, 1,
            List.of(BlueprintStep.place(0, 0, 0, 0, 0, 11, 0, "minecraft:stone", "tower", false)), Set.of()));
        context.runAtTick(5, () -> {
            var avatar = summon(context, rt, owner, new BlockPos(6, 2, 6));
            context.assertTrue(rt.projectStart(owner, id).success(), "preview");
            var placement = rt.blueprints().activeOf(owner.getUuid()).orElseThrow();
            var resolved = rt.blueprints().resolve(placement).orElseThrow();
            context.assertTrue(resolved.access().valid(), resolved.access().failure);
            var bill = BlueprintManager.requiredProjectMaterials(context.getWorld(), resolved);
            bill.forEach((item, count) -> { for (int left = count; left > 0; left -= 64)
                avatar.items().insert(new ItemStack(Registries.ITEM.get(item), Math.min(left, 64))); });
            context.assertTrue(rt.blueprintBuild(owner).success(), "confirmed");
            var project = rt.projects().activeOf(owner.getUuid()).orElseThrow();
            pollAccessCancellation(context, rt, owner, avatar, placement, project, bill.getOrDefault(new Identifier("minecraft:scaffolding"), 0), 25);
        });
    }
    private static void pollAccessCancellation(TestContext context, SquireRuntime rt, FakePlayer owner, AvatarEntity avatar,
            BlueprintPlacement placement, Project project, int cobble, int tick) {
        context.runAtTick(tick, () -> {
            var access = placement.snapshot().access();
            context.assertTrue(project.state() != Project.State.PAUSED, rt.projects().describe(project).toString());
            if (access.placed.size() < 3) {
                context.assertTrue(tick < 1800, "three real supports should have been placed");
                pollAccessCancellation(context, rt, owner, avatar, placement, project, cobble, tick + 10); return;
            }
            rt.projects().pause(project);
            // Simulate the disk codec/recovery boundary, then a data-pack replacement.
            var recovered = dev.squire.server.blueprint.ConstructionSnapshotCodec.read(
                dev.squire.server.blueprint.ConstructionSnapshotCodec.write(placement.snapshot()));
            placement.snapshot(recovered, true);
            rt.blueprints().registry().register(new Blueprint(placement.blueprintId, "changed resource", 1,
                Blueprint.Category.DEFENCE, 1, 1, 1, List.of(BlueprintStep.place(0, 0, 0, 0, 0, 0, 0,
                "minecraft:gold_block", "changed", false)), Set.of()));
            context.assertTrue(rt.blueprints().resolve(placement).orElseThrow().toPlace().size() == 12, "committed geometry stays pinned");
            BlockPos changed = recovered.access().placed.keySet().iterator().next();
            context.getWorld().setBlockState(changed, Blocks.GOLD_BLOCK.getDefaultState());
            rt.projects().cancel(project);
            context.assertTrue(project.active(), "cancellation must retain cleanup ledger");
            finishAccessCancellation(context, rt, owner, avatar, project, recovered, changed, cobble, tick + 25);
        });
    }
    private static void finishAccessCancellation(TestContext context, SquireRuntime rt, FakePlayer owner, AvatarEntity avatar,
            Project project, Blueprint.Resolved resolved, BlockPos changed, int cobble, int tick) {
        context.runAtTick(tick, () -> {
            context.assertTrue(project.state() != Project.State.PAUSED, rt.projects().describe(project).toString());
            if (project.active()) {
                context.assertTrue(tick < 3900, "cleanup should finish");
                finishAccessCancellation(context, rt, owner, avatar, project, resolved, changed, cobble, tick + 25); return;
            }
            context.assertTrue(project.state() == Project.State.CANCELLED, "cancel finished");
            context.assertTrue(resolved.access().placed.isEmpty(), "ownership ledger empty");
            context.assertTrue(context.getWorld().getBlockState(changed).isOf(Blocks.GOLD_BLOCK), "player's replacement preserved");
            context.assertTrue(avatar.items().countOf(new Identifier("minecraft:scaffolding")) == cobble - 1, "only surviving owned supports refunded, exactly once");
            cleanUp(rt, owner); context.complete();
        });
    }

	private static SquireScreenHandler panel(FakePlayer owner, AvatarEntity avatar) {
		SquireScreenHandler panel = new SquireScreenHandler(78, owner.getInventory(),
			avatar.items().mainInventory(), new AvatarEquipmentInventory(avatar,
				SquireScreenHandler.EQUIPMENT_ORDER), avatar.backpackSlotInventory(), avatar);
		panel.syncState(owner);
		return panel;
	}

	/** The pre-confirm handover must include foundations, just like confirmation. */
	@GameTest(templateName = FLOOR, tickLimit = 100, batchId = "squire-project-exact-handover")
	public void exactPreviewMaterialsIncludeSupportsAndStart(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "project-exact-handover");
		registerTiny(rt);
		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(6, 2, 6));
			context.assertTrue(rt.projectStart(owner, TINY).success(), "ghost placed");
			var placement = rt.blueprints().activeOf(owner.getUuid()).orElseThrow();
			var resolved = rt.blueprints().resolve(placement).orElseThrow();
			int baseY = resolved.toPlace().stream().mapToInt(cell -> cell.pos().getY()).min().orElseThrow();
			resolved.toPlace().stream().filter(cell -> cell.pos().getY() == baseY)
				.forEach(cell -> context.getWorld().setBlockState(cell.pos().down(), Blocks.AIR.getDefaultState()));
			context.assertTrue(!BlueprintManager.automaticSiteSupports(context.getWorld(), resolved).isEmpty(),
				"fixture must need additional foundations");
			var required = BlueprintManager.requiredProjectMaterials(context.getWorld(), rt.blueprints().resolve(placement).orElseThrow());
			context.assertTrue(BlueprintManager.missingMaterials(context.getWorld(), rt.blueprints().resolve(placement).orElseThrow(), avatar).equals(required),
				"chat handover and project bill must include the same supports and lights");
			required.forEach((id, count) -> owner.getInventory().insertStack(new ItemStack(Registries.ITEM.get(id), count)));
			context.assertTrue(panel(owner, avatar).state().materialLines().isEmpty(),
				"the preview must count materials already carried by the player");
			var transferred = rt.blueprintTransferMissing(owner);
			context.assertTrue(transferred.success(), transferred.message());
			required.forEach((id, count) -> context.assertTrue(avatar.items().countOf(id) == count,
				"handover omitted or overcharged " + id));
			var started = rt.blueprintBuild(owner);
			context.assertTrue(started.success(), started.message());
			Project project = rt.projects().activeOf(owner.getUuid()).orElseThrow();
			context.assertTrue(project.state() == Project.State.RUNNING, "the exact advertised bill must start");
			context.assertTrue(project.reservedMaterials().equals(required), "confirmation must reserve that same bill");
			cleanUp(rt, owner);
			context.complete();
		});
	}

	/** Model a saved mid-build shortfall, give only its delta, and finish in the real world. */
	@GameTest(templateName = FLOOR, tickLimit = 600, batchId = "squire-project-midbuild-topup")
	public void exactMidBuildTopUpClearsUiAndCompletes(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "project-midbuild-topup");
		registerTiny(rt);
		UUID[] projectId = new UUID[1];
		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(6, 2, 6));
			context.assertTrue(rt.projectStart(owner, TINY).success(), "ghost placed");
			var placement = rt.blueprints().activeOf(owner.getUuid()).orElseThrow();
			var resolved = rt.blueprints().resolve(placement).orElseThrow();
			var required = BlueprintManager.requiredProjectMaterials(context.getWorld(), resolved);
			required.forEach((id, count) -> avatar.items().insert(new ItemStack(Registries.ITEM.get(id), count)));
			context.assertTrue(rt.blueprintBuild(owner).success(), "exact stock confirms");
			Project project = rt.projects().activeOf(owner.getUuid()).orElseThrow();
			projectId[0] = project.projectId;
			var cells = BlueprintManager.constructionPlan(context.getWorld(), resolved, true).cells();
			for (Blueprint.Cell cell : cells.stream().filter(cell -> !cell.optional()).limit(3).toList()) {
				context.getWorld().setBlockState(cell.pos(), BlueprintManager.targetState(cell));
				context.assertTrue(project.consume(BlueprintManager.itemId(cell.blockId()), 1), "debit completed work");
			}
			Identifier cobble = new Identifier("minecraft:cobblestone");
			context.assertTrue(project.consume(cobble, 1), "simulate one previously lost construction item");
			for (Stage.Kind kind : List.of(Stage.Kind.FULFIL_MATERIALS, Stage.Kind.HAUL, Stage.Kind.EXCAVATE)) {
				stageOf(project, kind).setState(Stage.State.DONE);
			}
			stageOf(project, Stage.Kind.BUILD).block(Stage.BlockerCode.MATERIALS_MISSING, "stale missing message");
			project.setState(Project.State.PAUSED);
			var shortfall = project.missingFrom(BlueprintManager.requiredProjectMaterials(context.getWorld(), resolved));
			context.assertTrue(shortfall.equals(Map.of(cobble, 1)), "completed blocks must not be billed again: " + shortfall);
			var panel = panel(owner, avatar);
			context.assertTrue(panel.state().materialLines().size() == 1, "show only the one real deficit");
			avatar.items().insert(new ItemStack(Items.COBBLESTONE, 1));
			panel.syncState(owner);
			context.assertTrue(panel.state().materialLines().isEmpty(), "newly handed materials must immediately clear the UI deficit");
			context.assertTrue(panel.state().blockedReason().contains("材料已齐"), "replace stale blocker with ready-to-deposit guidance");
			context.assertTrue(rt.blueprintStatus(owner).message().contains("合计已够"), "chat must count the reserved pool too");
			context.assertTrue(rt.projectStatus(owner).message().contains("材料已齐"), "project chat must replace persisted stale blockers too");
			// Simulate a save produced by the old executor, which unlocked at a stage boundary.
			placement.setState(BlueprintPlacement.State.GHOST);
			var resumed = rt.blueprintTransferMissing(owner);
			context.assertTrue(resumed.success(), resumed.message());
			context.assertTrue(project.state() == Project.State.RUNNING, "handover resumes the same project");
			context.assertTrue(avatar.items().countOf(cobble) == 0, "the new batch must enter escrow, not stay stranded in the backpack");
			context.assertTrue(project.missingFrom(BlueprintManager.requiredProjectMaterials(context.getWorld(), resolved)).isEmpty(),
				"escrow must cover only the remaining work");
			context.assertTrue(placement.state() == BlueprintPlacement.State.BUILDING, "funded plan stays locked");
		});
		context.runAtTick(400, () -> {
			Project project = rt.projects().project(projectId[0]).orElseThrow();
			context.assertTrue(project.state() == Project.State.DONE,
				"one exact top-up must finish without another shortage: " + rt.projects().describe(project));
			var placement = rt.blueprints().placement(project.placementId).orElseThrow();
			var resolved = rt.blueprints().resolve(placement).orElseThrow();
			context.assertTrue(BlueprintManager.pendingPlacements(context.getWorld(), resolved).stream().noneMatch(cell -> !cell.optional()),
				"completion must match the real blueprint blocks");
			cleanUp(rt, owner);
			context.complete();
		});
	}

	@GameTest(templateName = FLOOR, tickLimit = 100, batchId = "squire-project-torch-support")
	public void invalidTorchSupportNeverConsumesAndFailedDebitRollsBack(TestContext context) {
		ServerWorld world = context.getWorld();
		BlockPos pos = context.getAbsolutePos(new BlockPos(4, 3, 4));
		world.setBlockState(pos.down(), Blocks.COBBLESTONE_SLAB.getDefaultState());
		world.setBlockState(pos, Blocks.AIR.getDefaultState());
		java.util.concurrent.atomic.AtomicInteger debits = new java.util.concurrent.atomic.AtomicInteger();
		context.assertFalse(Torchlight.suitable(world, pos), "bottom slabs cannot support standing torches");
		int placed = Torchlight.lightUp(world, List.of(pos), 1, 24, null, null, 0,
			() -> { debits.incrementAndGet(); return true; });
		context.assertTrue(placed == 0 && debits.get() == 0, "no charge for a torch that would pop off");
		world.setBlockState(pos.down(), Blocks.COBBLESTONE.getDefaultState());
		placed = Torchlight.lightUp(world, List.of(pos), 1, 24, null, null, 0, () -> false);
		context.assertTrue(placed == 0 && world.getBlockState(pos).isAir(), "failed material debit must not grant a free torch");
		context.complete();
	}

	/** Isolated rooms defeat index-based spacing; 24 is a batch cap, not a total cap. */
	@GameTest(templateName = FLOOR, tickLimit = 100, batchId = "squire-project-many-lights")
	public void futureRoomsReserveAllLightingAndSpendOnlyThatBudget(TestContext context) {
		ServerWorld world = context.getWorld();
		java.util.ArrayList<BlueprintStep> steps = new java.util.ArrayList<>();
		steps.add(BlueprintStep.place(0, 0, 0, 0, 10, 2, 10, "minecraft:cobblestone", "walls", false));
		for (int x = 1; x <= 9; x += 2) for (int z = 1; z <= 9; z += 2) {
			steps.add(BlueprintStep.dig(steps.size(), x, 1, z, x, 1, z, "room"));
		}
		var resolved = new Blueprint("gametest_many_lights", "lighting", 1,
			Blueprint.Category.SHELTER, 11, 3, 11, steps, Set.of())
			.resolve(context.getAbsolutePos(new BlockPos(0, 2, 0)), Direction.SOUTH);
		for (BlockPos pos : resolved.bounds().cells()) world.setBlockState(pos, Blocks.COBBLESTONE.getDefaultState());
		// The 11x11 fixture extends past the 9x9 template floor. Complete those
		// foundations too, so this really represents the post-construction LIGHT stage.
		BlueprintManager.automaticSiteSupports(world, resolved).forEach(cell ->
			world.setBlockState(cell.pos(), BlueprintManager.targetState(cell)));
		context.assertTrue(Torchlight.brightEnough(world, Torchlight.candidates(resolved)), "initially no usable air exists");
		int budget = BlueprintManager.requiredProjectMaterials(world, resolved).getOrDefault(Torchlight.TORCH, 0);
		context.assertTrue(budget == 25, "all 25 future rooms need upfront torches, not zero or 24: " + budget);
		resolved.toClear().forEach(pos -> world.setBlockState(pos, Blocks.AIR.getDefaultState()));
		var pool = new java.util.concurrent.atomic.AtomicInteger(budget);
		// Batch sites are reused; wait for the enclosed fixture to settle old block light.
		context.runAtTick(10, () -> {
			int afterExcavation = BlueprintManager.requiredProjectMaterials(world, resolved).getOrDefault(Torchlight.TORCH, 0);
			context.assertTrue(afterExcavation == budget,
				"enclosed rooms need the same budget after excavation: " + afterExcavation + " / " + budget);
			int placed = Torchlight.lightUp(world, Torchlight.projectSpots(world, resolved), 1,
				Torchlight.PROJECT_MAX_TORCHES, null, null, 10, () -> pool.getAndDecrement() > 0);
			context.assertTrue(placed == 24 && pool.get() == 1, "first bounded pass: " + placed);
		});
		context.runAtTick(20, () -> {
			var remaining = BlueprintManager.requiredProjectMaterials(world, resolved);
			context.assertTrue(remaining.getOrDefault(Torchlight.TORCH, 0) == 1,
				"already placed lights must not be billed again: " + remaining);
			int last = Torchlight.lightUp(world, Torchlight.projectSpots(world, resolved), 1,
				Torchlight.PROJECT_MAX_TORCHES, null, null, 20, () -> pool.getAndDecrement() > 0);
			context.assertTrue(last == 1 && pool.get() == 0, "second pass uses exactly the reserved remainder");
		});
		context.runAtTick(40, () -> {
			context.assertTrue(Torchlight.brightEnough(world, Torchlight.candidates(resolved)), "all rooms actually lit");
			context.assertTrue(!BlueprintManager.requiredProjectMaterials(world, resolved).containsKey(Torchlight.TORCH),
				"completed lights have no remaining bill");
			context.complete();
		});
	}

	@GameTest(templateName = FLOOR, tickLimit = 600, batchId = "squire-project-optional-escrow")
	public void optionalDecorationsCannotSpendReservedLighting(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "project-optional-escrow");
		String id = "gametest_optional_escrow";
		rt.blueprints().registry().register(new Blueprint(id, "optional torch", 1,
			Blueprint.Category.SHELTER, 3, 3, 3, List.of(
				BlueprintStep.place(0, 0, 0, 0, 2, 0, 2, "minecraft:cobblestone", "floor", false),
				BlueprintStep.place(1, 0, 1, 0, 0, 1, 0, "minecraft:torch", "optional lamp", true)), Set.of()));
		UUID[] projectId = new UUID[1];
		BlockPos[] optionalPos = new BlockPos[1];
		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(6, 2, 6));
			context.assertTrue(rt.projectStart(owner, id).success(), "ghost placed");
			var placement = rt.blueprints().activeOf(owner.getUuid()).orElseThrow();
			var resolved = rt.blueprints().resolve(placement).orElseThrow();
			optionalPos[0] = resolved.toPlace().stream().filter(Blueprint.Cell::optional).findFirst().orElseThrow().pos();
			BlueprintManager.requiredProjectMaterials(context.getWorld(), resolved)
				.forEach((item, count) -> avatar.items().insert(new ItemStack(Registries.ITEM.get(item), count)));
			context.assertTrue(rt.blueprintBuild(owner).success(), "confirm exact required bill");
			projectId[0] = rt.projects().activeOf(owner.getUuid()).orElseThrow().projectId;
		});
		context.runAtTick(400, () -> {
			Project project = rt.projects().project(projectId[0]).orElseThrow();
			context.assertTrue(project.state() == Project.State.DONE, "required work must complete: " + rt.projects().describe(project));
			context.assertTrue(context.getWorld().getBlockState(optionalPos[0]).isAir(),
				"unfunded optional decoration must not spend the required lighting allowance");
			cleanUp(rt, owner);
			context.complete();
		});
	}

	// ================================================== 分解

	/** 积水和一层悬空属于工地整备，不应再要求玩家换地方。 */
	@GameTest(templateName = FLOOR, tickLimit = 400,
		batchId = "squire-project-site-preparation")
	public void waterAndOneLayerGapsArePreparedByTheBuilder(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "project-site-prep-owner");
		registerTiny(rt);
		AtomicReference<List<Blueprint.Cell>> supports =
			new AtomicReference<>(List.of());
		BlockPos[] wetInterior = new BlockPos[1];

		context.runAtTick(5, () -> {
			summon(context, rt, owner, new BlockPos(6, 2, 6));
			context.assertTrue(rt.projectStart(owner, TINY).success(), "ghost placed");
			var placement = rt.blueprints().activeOf(owner.getUuid()).orElseThrow();
			var resolved = rt.blueprints().resolve(placement).orElseThrow();
			int baseY = resolved.toPlace().stream().filter(cell -> !cell.optional())
				.mapToInt(cell -> cell.pos().getY()).min().orElseThrow();
			boolean first = true;
			for (Blueprint.Cell cell : resolved.toPlace()) {
				if (cell.optional() || cell.pos().getY() != baseY) continue;
				context.getWorld().setBlockState(cell.pos().down(), first
					? Blocks.WATER.getDefaultState() : Blocks.AIR.getDefaultState());
				first = false;
			}
			wetInterior[0] = resolved.toClear().get(0);
			context.getWorld().setBlockState(wetInterior[0], Blocks.WATER.getDefaultState());

			// Terrain changed after the ghost opened: compare against the new sampled,
			// paid ground snapshot, not the old building-material support estimate.
			placement.invalidatePreview();
			resolved = rt.blueprints().resolve(placement).orElseThrow();
			supports.set(BlueprintManager.automaticSiteSupports(context.getWorld(),
				resolved));
			SiteAssessment assessment = SiteAssessment.assess(context.getWorld(), resolved,
				Set.of(owner.getUuid(), rt.resolveAvatarFor(owner.getUuid()).orElseThrow()
					.getUuid()));
			context.assertTrue(!supports.get().isEmpty(), "the open base needs supports");
			context.assertTrue(assessment.unsupportedFloorCells() == supports.get().size(),
				"the preview must report exactly what will be prepared");
			context.assertTrue(assessment.liquidCells() > 0,
				"managed liquid must be reported");
			context.assertTrue(assessment.executable(),
				"water and one-layer gaps are automatic preparation, not blockers");

			var builder = rt.resolveAvatarFor(owner.getUuid()).orElseThrow();
			BlueprintManager.requiredProjectMaterials(context.getWorld(), resolved).forEach((item, count) ->
				builder.items().insert(new ItemStack(Registries.ITEM.get(item), count)));
			var confirmed = rt.projectConfirm(owner);
			context.assertTrue(confirmed.success(), confirmed.message());
			context.assertTrue(rt.projects().activeOf(owner.getUuid()).orElseThrow().missingFrom(
				BlueprintManager.requiredProjectMaterials(context.getWorld(), resolved)).isEmpty(), "sampled foundation materials are fully supplied");
		});

		context.runAtTick(350, () -> {
			for (Blueprint.Cell support : supports.get()) {
				context.assertTrue(BlueprintManager.matches(
					context.getWorld().getBlockState(support.pos()), support),
					"the builder must place the promised support at " + support.pos());
			}
			context.assertTrue(context.getWorld().getFluidState(wetInterior[0]).isEmpty(),
				"the excavation stage must drain managed standing water");
			cleanUp(rt, owner);
			context.complete();
		});
	}

	/**
	 * 一句大目标 → 六个阶段，而且<b>一件材料都不会凭空出现</b>。
	 *
	 * <p>先验没料时确认会建立一个零库存、暂停的真实工程，两边库存和地面上都不会
	 * 长出东西；再把料交给伙伴并补料，工程才运行。这两半合起来才是「工程是真材料
	 * 做的」这句话：只验后一半的话，一个偷偷补料的实现照样能通过。</p>
	 */
	@GameTest(templateName = FLOOR, tickLimit = 400, batchId = "squire-project-decompose")
	public void aProjectDecomposesIntoSixStagesAndNeverConjuresMaterials(
			TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "project-decompose-owner");
		registerTiny(rt);
		UUID[] projectId = new UUID[1];

		context.runAtTick(5, () -> {
			handOverMode(rt, summon(context, rt, owner, new BlockPos(6, 2, 6)));
			// 一件料都没有：预览和工程仍成立，但工程必须停在备料阶段。
			context.assertTrue(rt.projectStart(owner, TINY).success(),
				"placing the ghost writes nothing and must always work");
			var confirmed = rt.blueprintBuild(owner);
			context.assertTrue(confirmed.success(), confirmed.message());
			context.assertTrue(confirmed.message().contains("圆石"),
				"the waiting project must say WHAT is missing, got: "
					+ confirmed.message());
			Project waiting = rt.projects().activeOf(owner.getUuid()).orElseThrow();
			projectId[0] = waiting.projectId;
			context.assertTrue(waiting.state() == Project.State.PAUSED,
				"a zero-supply project must wait without changing the world");
			context.assertTrue(stageOf(waiting, Stage.Kind.FULFIL_MATERIALS)
					.blockerCode() == Stage.BlockerCode.MATERIALS_MISSING,
				"the UI needs a stable material blocker code");

			// 而且拒绝的过程里不许凭空生出材料——玩家背包、地面、伙伴背包都不许。
			int dropped = 0;
			for (var item : context.getWorld().getEntitiesByClass(
					net.minecraft.entity.ItemEntity.class,
					owner.getBoundingBox().expand(4.0), e -> true)) {
				if (item.getStack().isOf(Items.COBBLESTONE)) {
					dropped += item.getStack().getCount();
				}
			}
			AvatarEntity avatar = rt.resolveAvatarFor(owner.getUuid()).orElseThrow();
			int carried = dropped + owner.getInventory().count(Items.COBBLESTONE)
				+ avatar.items().countOf(new Identifier("minecraft:cobblestone"));
			context.assertTrue(carried == 0,
				"projects must not conjure materials; found " + carried);

			// 玩家把料交出来之后，从同一个持久工程继续，不用重建蓝图或工地。
			stock(rt, owner);
			var resumed = rt.projectResume(owner);
			context.assertTrue(resumed.success(), resumed.message());
			Project project = rt.projects().project(projectId[0]).orElseThrow();
			context.assertTrue(project.state() == Project.State.RUNNING,
				"supplying the final batch starts the waiting project");
			context.assertTrue(project.stages().size() == 6,
				"a project is six stages, got " + project.stages().size());
			context.assertTrue(stageOf(project, Stage.Kind.FULFIL_MATERIALS).state()
					== Stage.State.PENDING, "nothing has run yet");
		});

		context.runAtTick(120, () -> {
			// 按 id 取，不用 activeOf：料齐了之后这个 2×2×2 的小工程跑得很快，
			// 到这一拍它可能已经验收完毕，而 activeOf 只认还在跑的那一个。
			Project project = rt.projects().project(projectId[0]).orElseThrow();
			Stage materials = stageOf(project, Stage.Kind.FULFIL_MATERIALS);
			context.assertTrue(materials.state() == Stage.State.DONE,
				"with the pool reserved the materials stage verifies and passes, was "
					+ materials.state() + " / " + materials.blockedReason());
			cleanUp(rt, owner);
			context.complete();
		});
	}

	/**
	 * 缺料时<b>说得出还缺什么</b>，而且说的是玩家认得的名字；交出去之后自己解开。
	 *
	 * <p>这一条守的是 ADR-042 里那句「工程可以为人停下，并且说得出卡在谁身上」。
	 * 停下的<b>位置</b>随材料模型变过：现在确认会先建立持久工程，把当前材料锁进
	 * 工程物资池，再停在 {@code FULFIL_MATERIALS} 等待剩余批次（{@code HAUL}
	 * 已经因此变成一个恒定跳过的阶段）。但玩家看到的那句话必须保持这个标准：
	 * 说出缺什么、缺多少、用中文物品名，而不是一句「不行」或者一个 registry id。</p>
	 */
	@GameTest(templateName = FLOOR, tickLimit = 600, batchId = "squire-project-haul")
	public void aShortProjectSaysWhatIsMissingAndUnblocksWhenHandedOver(
			TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "project-haul-owner");
		registerTiny(rt);
		UUID[] projectId = new UUID[1];

		context.runAtTick(5, () -> {
			handOverMode(rt, summon(context, rt, owner, new BlockPos(6, 2, 6)));
			context.assertTrue(rt.projectStart(owner, TINY).success(), "ghost placed");
			var confirmed = rt.blueprintBuild(owner);
			context.assertTrue(confirmed.success(), confirmed.message());
			context.assertTrue(confirmed.message().contains("圆石")
					&& !confirmed.message().contains("cobblestone"),
				"the blocker must say WHAT is missing, in the player's language, got: "
					+ confirmed.message());
			context.assertTrue(confirmed.message().contains("火把"),
				"and it must list every shortfall, not just the first, got: "
					+ confirmed.message());
			Project project = rt.projects().activeOf(owner.getUuid()).orElseThrow();
			projectId[0] = project.projectId;
			context.assertTrue(stageOf(project, Stage.Kind.FULFIL_MATERIALS).state()
					== Stage.State.BLOCKED,
				"the durable project must wait at its material stage");

			// 玩家把料交给他（这里直接放进背包，等价于丢在他脚边被捡起）。
			stock(rt, owner);
			var resumed = rt.projectResume(owner);
			context.assertTrue(resumed.success(),
				"handing the materials over must unblock it by itself: "
					+ resumed.message());
		});

		context.runAtTick(300, () -> {
			Project project = rt.projects().project(projectId[0]).orElseThrow();
			context.assertTrue(stageOf(project, Stage.Kind.FULFIL_MATERIALS).state()
					== Stage.State.DONE,
				"the reserved pool covers the bill, so the stage verifies and passes");
			context.assertTrue(stageOf(project, Stage.Kind.HAUL).state()
					== Stage.State.SKIPPED,
				"materials moved at confirmation time, so there is nothing left to haul");
			cleanUp(rt, owner);
			context.complete();
		});
	}

	/** 多次都装不下全部清单时，每一批仍会累计，最后一批到账才开始施工。 */
	@GameTest(templateName = FLOOR, tickLimit = 400,
		batchId = "squire-project-material-batches")
	public void materialsAccumulateAcrossSeveralDeposits(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "project-batches-owner");
		registerTiny(rt);

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(6, 2, 6));
			avatar.items().insert(new ItemStack(Items.COBBLESTONE, 2));
			context.assertTrue(rt.projectStart(owner, TINY).success(), "ghost placed");
			var first = rt.blueprintBuild(owner);
			context.assertTrue(first.success(), first.message());
			Project project = rt.projects().activeOf(owner.getUuid()).orElseThrow();
			Identifier cobble = new Identifier("minecraft:cobblestone");
			context.assertTrue(project.reservedCount(cobble) == 2,
				"the first real batch must enter escrow");
			context.assertTrue(project.state() == Project.State.PAUSED,
				"a partial batch must not start world edits");

			avatar.items().insert(new ItemStack(Items.COBBLESTONE, 2));
			var second = rt.projectResume(owner);
			context.assertTrue(second.success(), second.message());
			context.assertTrue(project.reservedCount(cobble) == 4,
				"the second batch must accumulate instead of replacing the first");
			context.assertTrue(project.state() == Project.State.PAUSED,
				"still-incomplete escrow remains safely paused");
			context.assertTrue(second.message().contains("本批已"),
				"the UI feedback must acknowledge a partial deposit: " + second.message());

			stock(rt, owner);
			var last = rt.projectResume(owner);
			context.assertTrue(last.success(), last.message());
			var placement = rt.blueprints().activeOf(owner.getUuid()).orElseThrow();
			var resolved = rt.blueprints().resolve(placement).orElseThrow();
			Map<Identifier, Integer> required = BlueprintManager
				.requiredProjectMaterials(context.getWorld(), resolved);
			context.assertTrue(project.missingFrom(required).isEmpty(),
				"the final batch must cover the authoritative blueprint bill");
			context.assertTrue(project.state() == Project.State.RUNNING,
				"only a fully funded project may begin construction");
		});

		context.runAtTick(250, () -> {
			cleanUp(rt, owner);
			context.complete();
		});
	}

	// ================================================== 玩家控制

	/** 暂停之后不再往前走；继续之后接着走。 */
	@GameTest(templateName = FLOOR, tickLimit = 600, batchId = "squire-project-pause")
	public void pauseStopsAdvancingAndResumePicksUpAgain(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "project-pause-owner");
		registerTiny(rt);

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(6, 2, 6));
			avatar.items().insert(new ItemStack(Items.COBBLESTONE, 64));
			avatar.items().insert(new ItemStack(Items.TORCH, 8));
			startConfirmed(context, rt, owner, TINY);
			context.assertTrue(rt.projectPause(owner).success(), "pause works");
		});

		context.runAtTick(150, () -> {
			Project project = rt.projects().activeOf(owner.getUuid()).orElseThrow();
			context.assertTrue(project.state() == Project.State.PAUSED,
				"the project really is paused");
			context.assertTrue(stageOf(project, Stage.Kind.FULFIL_MATERIALS).state()
					== Stage.State.PENDING,
				"a paused project must not advance a single stage");
			rt.projectResume(owner);
		});

		context.runAtTick(300, () -> {
			Project project = rt.projects().activeOf(owner.getUuid()).orElseThrow();
			context.assertTrue(stageOf(project, Stage.Kind.FULFIL_MATERIALS).state()
					== Stage.State.DONE,
				"resuming picks up exactly where it stopped");
			cleanUp(rt, owner);
			context.complete();
		});
	}

	/** Lighting samples usable floor cells and reaches verification instead of hanging. */
	@GameTest(templateName = FLOOR, tickLimit = 600, batchId = "squire-project-lighting")
	public void aStoneRoomLightsAndFinishesInsteadOfSticking(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "project-lighting-owner");
		registerLightingRoom(rt);
		UUID[] projectId = new UUID[1];

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(6, 2, 6));
			avatar.items().insert(new ItemStack(Items.COBBLESTONE, 32));
			avatar.items().insert(new ItemStack(Items.TORCH, 8));
			startConfirmed(context, rt, owner, LIGHTING);
			projectId[0] = rt.projects().activeOf(owner.getUuid()).orElseThrow().projectId;
		});

		context.runAtTick(400, () -> {
			Project project = rt.projects().project(projectId[0]).orElseThrow();
			Stage lighting = stageOf(project, Stage.Kind.LIGHT);
			context.assertTrue(lighting.state() == Stage.State.DONE,
				"lighting must finish, state was " + lighting.state() + ": "
					+ lighting.blockedReason());
			context.assertTrue(project.state() == Project.State.DONE,
				"the verified room must finish the whole project");
			cleanUp(rt, owner);
			context.complete();
		});
	}

	/** 取消把工程和工地一起撤掉——留一个没人管的幽灵轮廓只会让玩家困惑。 */
	@GameTest(templateName = FLOOR, tickLimit = 300, batchId = "squire-project-cancel")
	public void cancelClearsBothTheProjectAndTheSite(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "project-cancel-owner");
		registerTiny(rt);

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(6, 2, 6));
			avatar.items().insert(new ItemStack(Items.COBBLESTONE, 3));
			context.assertTrue(rt.projectStart(owner, TINY).success(), "ghost placed");
			context.assertTrue(rt.blueprintBuild(owner).success(),
				"a partial batch still establishes the project");
			Project project = rt.projects().activeOf(owner.getUuid()).orElseThrow();
			context.assertTrue(project.state() == Project.State.PAUSED,
				"the partially supplied project waits safely");
			context.assertTrue(project.reservedCount(
				new Identifier("minecraft:cobblestone")) == 3,
				"the batch is held in durable escrow");
			context.assertTrue(rt.blueprints().activeOf(owner.getUuid()).isPresent(),
				"starting a project places its site");

			context.assertTrue(rt.projectCancel(owner).success(), "cancel works");
			context.assertTrue(avatar.items().countOf(
				new Identifier("minecraft:cobblestone")) == 3,
				"cancelling must return every unspent partial batch to its source");
			context.assertTrue(rt.projects().activeOf(owner.getUuid()).isEmpty(),
				"the project is gone");
			context.assertTrue(rt.blueprints().activeOf(owner.getUuid()).isEmpty(),
				"and so is the ghost site");
			cleanUp(rt, owner);
			context.complete();
		});
	}

	/** 一次只做一个工程：第二个如实拒绝，并告诉玩家怎么处理第一个。 */
	@GameTest(templateName = FLOOR, tickLimit = 300, batchId = "squire-project-single")
	public void asecondProjectIsRefusedWithAWayOut(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "project-single-owner");
		registerTiny(rt);

		context.runAtTick(5, () -> {
			summon(context, rt, owner, new BlockPos(6, 2, 6));
			rt.projectStart(owner, TINY);
			var second = rt.projectStart(owner, TINY);
			context.assertFalse(second.success(), "one project at a time");
			context.assertTrue(second.message().contains("取消"),
				"every refusal carries a command-free way out: " + second.message());
			cleanUp(rt, owner);
			context.complete();
		});
	}

	/** 一句自然语言就能开工，认不出来时把可选项列出来。 */
	@GameTest(templateName = FLOOR, tickLimit = 300, batchId = "squire-project-phrase")
	public void aPlainSentenceStartsTheProjectAndAnUnknownOneListsTheOptions(
			TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "project-phrase-owner");

		context.runAtTick(5, () -> {
			summon(context, rt, owner, new BlockPos(6, 2, 6));
			var vague = rt.projectStartFromPhrase(owner, "帮我准备一个什么东西");
			context.assertFalse(vague.success(), "an unrecognised target is refused");
			context.assertTrue(vague.message().contains("民居"),
				"a dead end must list the way out: " + vague.message());

			var known = rt.projectStartFromPhrase(owner, "帮我准备一个 keepitlevel_residence");
			context.assertTrue(known.success(), known.message());
			context.assertTrue(rt.blueprints().activeOf(owner.getUuid()).orElseThrow()
					.blueprintId.equals("keepitlevel_residence"),
				"the phrase must resolve to a catalog preview");
			context.assertTrue(rt.projects().activeOf(owner.getUuid()).isEmpty(),
				"a natural-language request must still wait for preview confirmation");
			cleanUp(rt, owner);
			context.complete();
		});
	}

	/**
	 * <b>默认行为</b>：材料他自己用指令领，不用玩家跑一趟。
	 *
	 * <p>一句「帮我准备一个矿井前哨站」之后还要亲自送货，等于把大目标又拆回了手工活。
	 * 想要那道手续的人把自主程度调到「保守」即可。</p>
	 */
	@GameTest(templateName = FLOOR, tickLimit = 600, batchId = "squire-project-selfsupply")
	public void byDefaultNothingIsRoutedThroughThePlayer(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "project-selfsupply-owner");
		registerTiny(rt);
		UUID[] projectId = new UUID[1];

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(6, 2, 6));
			context.assertTrue(rt.profileOf(avatar).autonomyLevel()
					== AutonomyLevel.STANDARD, "standard is the default");
			// 料全在伙伴身上，玩家背包是空的：默认档下工程从头到尾不该经过玩家的手。
			stock(rt, owner);
			startConfirmed(context, rt, owner, TINY);
			projectId[0] = rt.projects().activeOf(owner.getUuid()).orElseThrow()
				.projectId;
			context.assertTrue(owner.getInventory().count(Items.COBBLESTONE) == 0,
				"the owner starts with nothing and must not be handed anything");
		});

		context.runAtTick(200, () -> {
			Project project = rt.projects().project(projectId[0]).orElseThrow();
			context.assertTrue(stageOf(project, Stage.Kind.FULFIL_MATERIALS).state()
					== Stage.State.DONE,
				"the companion's own stock is enough; nothing waits on the player");
			// 他不需要你：玩家那边从头到尾没出现过材料。
			// （不能改成“他背包里有料”——到这一拍他早就把料砦进墙里了。）
			int toThePlayer = owner.getInventory().count(Items.COBBLESTONE);
			for (var item : context.getWorld().getEntitiesByClass(
					net.minecraft.entity.ItemEntity.class,
					owner.getBoundingBox().expand(4.0), e -> true)) {
				if (item.getStack().isOf(Items.COBBLESTONE)) {
					toThePlayer += item.getStack().getCount();
				}
			}
			context.assertTrue(toThePlayer == 0,
				"on the default setting nothing should be routed through the player, "
					+ "found " + toThePlayer);
			cleanUp(rt, owner);
			context.complete();
		});
	}

}
