package dev.squire.server.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import dev.squire.server.blueprint.Blueprint;
import dev.squire.server.blueprint.BlueprintManager;
import dev.squire.server.blueprint.BlueprintPlacement;
import dev.squire.server.blueprint.BillOfMaterials;
import dev.squire.server.blueprint.HouseSpec;
import dev.squire.server.blueprint.SiteAssessment;
import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.security.PermissionNodes;
import dev.squire.server.task.RetryPolicy;
import dev.squire.server.task.Task;
import dev.squire.server.task.TaskPriority;
import dev.squire.server.task.executors.BlueprintBuildExecutor;
import dev.squire.server.task.executors.ExcavateExecutor;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * 蓝图的玩家意图层：摆放、看账、兑现、开工、取消。
 *
 * <p>和 {@link SquireBuildService} 是同一个位置上的两代实现。老的
 * {@code buildHouse} 一个 tick 把整栋房子写出来、不消耗任何材料；这里每一步都要
 * 玩家真的凑齐东西，伙伴走过去逐块盖。老路径没有删，它现在只是 FastPath
 * 「盖个房子」的入口，内部改成编译一份蓝图摆放。</p>
 *
 * <p>「任何输入都要有出路」在这一期的落点是缺料：每一条缺料回复都必须带上下一步
 * 能敲的命令（兑现 / 看账 / 取消），而不是只说一句「材料不够」。</p>
 */
final class SquireBlueprintService {

	/** 摆放落在玩家前方几格，别摆在他脸上。 */
	private static final int STANDOFF = 3;

	private final SquireRuntime runtime;

	SquireBlueprintService(SquireRuntime runtime) {
		this.runtime = runtime;
	}

	private BlueprintManager manager() {
		return runtime.blueprints();
	}

	// ------------------------------------------------------------------ 查看

	public SquireRuntime.ExecutionResult list() {
		StringBuilder text = new StringBuilder("[Squire] 可用蓝图：");
		for (Blueprint blueprint : manager().registry().all()) {
			text.append("\n  · ").append(blueprint.id()).append(" —— ")
				.append(blueprint.displayName())
				.append("（tier ").append(blueprint.tier()).append("，")
				.append(blueprint.placedWidth(Direction.NORTH)).append("×")
				.append(blueprint.depth()).append("×").append(blueprint.height())
				.append("）");
		}
		text.append("\n可在侍从面板的工程页选择并摆放。");
		return SquireRuntime.ExecutionResult.ok("feedback.blueprint_list",
			text.toString());
	}

	public SquireRuntime.ExecutionResult status(ServerPlayerEntity sender) {
		Site site = siteOf(sender);
		if (site.failure() != null) {
			return site.failure();
		}
		return SquireRuntime.ExecutionResult.ok("feedback.blueprint_status",
			describe(site, "当前工地"));
	}

	// ------------------------------------------------------------------ 摆放

	public SquireRuntime.ExecutionResult place(ServerPlayerEntity sender,
			String blueprintId) {
		Optional<AvatarEntity> found = runtime.agents().resolveForOwner(sender.getUuid());
		if (found.isEmpty()) {
			return SquireRuntime.ExecutionResult.fail("feedback.no_agent",
				"[Squire] 侍从不在场。请右键召集铃；首次召唤方法可按 K 查看。");
		}
		AvatarEntity avatar = found.get();
		Blueprint blueprint = manager().registry().byId(blueprintId).orElse(null);
		if (blueprint == null) {
			return SquireRuntime.ExecutionResult.fail("feedback.blueprint_unknown",
				"[Squire] 没有叫「" + blueprintId + "」的蓝图。可在工程页查看模板。");
		}
		// 基础施工 / 参数化设计的那条线。放在这里是因为这个方法是<b>所有</b>摆放路径
		// 的唯一漏斗——面板按钮、聊天、命令、模型工具都要从这儿过，所以四条路
		// 不可能再各说各话。分档规则见 BuildTier，判据见 checkBuildTier。
		SquireRuntime.ExecutionResult tier =
			runtime.engineer().checkBuildTier(sender, blueprint.id());
		if (tier != null) {
			return tier;
		}
		// 工程师的尺寸上限（设计文档 §13）。没有工程师职业的随从不受这条限制——
		// 它是一条<b>成长曲线</b>，不是给所有人的新门槛。
		SquireRuntime.ExecutionResult tooLarge =
			runtime.engineer().checkFootprint(sender, blueprint);
		if (tooLarge != null) {
			return tooLarge;
		}
		List<String> unknown = BlueprintManager.unknownBlocks(blueprint,
			manager().registry().materials());
		if (!unknown.isEmpty()) {
			// 数据包写错了方块 id：现在说清楚，比让玩家凑齐材料后才发现强得多。
			return SquireRuntime.ExecutionResult.fail("feedback.blueprint_broken",
				"[Squire] 这份蓝图里有放不下去的方块：" + String.join("、", unknown));
		}
		Optional<BlueprintPlacement> existing = manager().activeOf(sender.getUuid());
		if (existing.isPresent()) {
			return SquireRuntime.ExecutionResult.fail("feedback.blueprint_busy",
				"[Squire] 已经有一个工地了（" + existing.get().blueprintId
					+ "）。请在工程页盖完或取消它。");
		}
		ServerWorld world = (ServerWorld) sender.getWorld();
		Direction facing = sender.getHorizontalFacing();
		BlockPos origin = originFor(sender, blueprint, facing);
		BlueprintPlacement placement = new BlueprintPlacement(UUID.randomUUID(),
			sender.getUuid(), avatar.agentId(), blueprint.id(),
			world.getRegistryKey().getValue().toString(), origin, facing,
			runtime.currentTick(), blueprint.defaultPalette());
		Blueprint.Resolved resolved = blueprint.resolve(origin, facing,
			placement.materials(), manager().registry().materials());
		if (resolved.cellCount() > Blueprint.MAX_CELLS) {
			return SquireRuntime.ExecutionResult.fail("feedback.blueprint_too_large",
				"[Squire] 这份蓝图 " + resolved.cellCount() + " 格，超过了 "
					+ Blueprint.MAX_CELLS + " 格的上限。");
		}
		// 整个包围盒一次性过保护判定，绝不逐块绕过。
		var decision = runtime.protectionAdapter.canEditRegion(world, resolved.bounds(),
			sender.getUuid());
		if (!decision.allowed()) {
			return SquireRuntime.ExecutionResult.fail("feedback.protected",
				"[Squire] 这块地不让动：" + decision.reason());
		}
		manager().put(placement);
		Site site = new Site(placement, blueprint, resolved, world, avatar, null);
		return SquireRuntime.ExecutionResult.ok("feedback.blueprint_placed",
			describe(site, "摆好了（世界还没有任何改变，你看到的是粒子轮廓）"));
	}

	public SquireRuntime.ExecutionResult placeHouse(ServerPlayerEntity sender,
			HouseSpec spec) {
		return place(sender, (spec == null ? HouseSpec.defaults() : spec).blueprintId());
	}

	/** Rotate the ghost around its visual centre. */
	public SquireRuntime.ExecutionResult rotate(ServerPlayerEntity sender) {
		Site site = siteOf(sender);
		if (site.failure() != null) return site.failure();
		if (site.placement().state() == BlueprintPlacement.State.BUILDING) {
			return SquireRuntime.ExecutionResult.fail("feedback.blueprint_committed",
				"[Squire] 已经开工，不能再旋转工地。");
		}
		// 旋转是工程师 Lv.3 的能力。没有工程师职业的随从照旧能转。
		SquireRuntime.ExecutionResult locked = runtime.engineer().checkRotation(sender);
		if (locked != null) {
			return locked;
		}
		Direction next = site.placement().facing.rotateYClockwise();
		Blueprint.Resolved preview = site.blueprint().resolve(site.placement().origin, next,
			site.placement().materials(), manager().registry().materials());
		BlockPos oldMin = site.resolved().bounds().min();
		BlockPos oldMax = site.resolved().bounds().max();
		BlockPos newMin = preview.bounds().min();
		BlockPos newMax = preview.bounds().max();
		int dx = (oldMin.getX() + oldMax.getX() - newMin.getX() - newMax.getX()) / 2;
		int dz = (oldMin.getZ() + oldMax.getZ() - newMin.getZ() - newMax.getZ()) / 2;
		site.placement().relocate(site.placement().origin.add(dx, 0, dz), next);
		manager().save();
		return status(sender);
	}

	/** Move the ghost relative to its facing; values are clamped for safe GUI use. */
	public SquireRuntime.ExecutionResult nudge(ServerPlayerEntity sender, int forward,
			int right) {
		Site site = siteOf(sender);
		if (site.failure() != null) return site.failure();
		if (site.placement().state() == BlueprintPlacement.State.BUILDING) {
			return SquireRuntime.ExecutionResult.fail("feedback.blueprint_committed",
				"[Squire] 已经开工，不能再移动工地。");
		}
		int f = Math.max(-8, Math.min(8, forward));
		int r = Math.max(-8, Math.min(8, right));
		Direction facing = site.placement().facing;
		BlockPos moved = site.placement().origin.offset(facing, f)
			.offset(facing.rotateYClockwise(), r);
		site.placement().relocate(moved, facing);
		manager().save();
		return status(sender);
	}

	/** Recompile an active generated-house ghost with another validated spec. */
	public SquireRuntime.ExecutionResult configureHouse(ServerPlayerEntity sender,
			HouseSpec spec) {
		return reshape(sender, spec.blueprintId(),
			() -> placeHouse(sender, spec));
	}

	/**
	 * 把当前工地换成另一份蓝图，<b>保持落点和摆放身份不变</b>。
	 *
	 * <p>工程师改参数走的就是这条路：改一个屋顶样式不该让工地跳到别的地方去，
	 * 也不该丢掉玩家已经交进去的材料。</p>
	 *
	 * @param whenNothingPlaced 还没有工地时该做什么（一般是直接摆一份新的）
	 */
	public SquireRuntime.ExecutionResult reshape(ServerPlayerEntity sender,
			String blueprintId,
			java.util.function.Supplier<SquireRuntime.ExecutionResult> whenNothingPlaced) {
		Optional<BlueprintPlacement> active = manager().activeOf(sender.getUuid());
		if (active.isEmpty()) return whenNothingPlaced.get();
		BlueprintPlacement placement = active.get();
		Blueprint.Resolved before = manager().resolve(placement).orElse(null);
		if (!placement.changeBlueprint(blueprintId)) {
			return SquireRuntime.ExecutionResult.fail("feedback.blueprint_committed",
				"[Squire] 已经开工，蓝图参数不能再改。");
		}
		Blueprint nextBlueprint = manager().registry().byId(blueprintId).orElse(null);
		if (before != null && nextBlueprint != null) {
			Blueprint.Resolved after = nextBlueprint.resolve(placement.origin,
				placement.facing);
			int dx = (before.bounds().min().getX() + before.bounds().max().getX()
				- after.bounds().min().getX() - after.bounds().max().getX()) / 2;
			int dz = (before.bounds().min().getZ() + before.bounds().max().getZ()
				- after.bounds().min().getZ() - after.bounds().max().getZ()) / 2;
			placement.relocate(placement.origin.add(dx, 0, dz), placement.facing);
		}
		manager().save();
		return status(sender);
	}

	/** Cycle one validated material slot. Positive is next, negative is previous. */
	public SquireRuntime.ExecutionResult cycleMaterial(ServerPlayerEntity sender,
			int slotIndex, int delta) {
		Site site = siteOf(sender);
		if (site.failure() != null) return site.failure();
		if (site.placement().state() == BlueprintPlacement.State.BUILDING) {
			return SquireRuntime.ExecutionResult.fail("feedback.blueprint_committed",
				"[Squire] 已经开工，不能再更换材料。");
		}
		if (slotIndex < 0 || slotIndex >= site.blueprint().materialSlots().size()) {
			return SquireRuntime.ExecutionResult.fail("feedback.blueprint_material_slot",
				"[Squire] 这份蓝图没有该材料槽位。");
		}
		// 分区换材料是工程师 Lv.4 的能力——但只在<b>真的分了区</b>的蓝图上收费：
		// 只有一个槽的老蓝图等于「整栋一种材料」，那是 Lv.1 就有的事。
		if (site.blueprint().materialSlots().size() > 1) {
			SquireRuntime.ExecutionResult locked =
				runtime.engineer().checkMaterialRegions(sender);
			if (locked != null) {
				return locked;
			}
		}
		Blueprint.MaterialSlot slot = site.blueprint().materialSlots().get(slotIndex);
		List<dev.squire.server.blueprint.MaterialFamily> candidates = manager().registry()
			.materials().candidates(slot);
		if (candidates.isEmpty()) {
			return SquireRuntime.ExecutionResult.fail("feedback.blueprint_material_none",
				"[Squire] 没有完整支持“" + slot.displayName() + "”的材料家族。");
		}
		String current = site.placement().materials().getOrDefault(slot.id(),
			slot.defaultFamilyId());
		int index = 0;
		for (int i = 0; i < candidates.size(); i++) {
			if (candidates.get(i).id().equals(current)) { index = i; break; }
		}
		int next = Math.floorMod(index + (delta < 0 ? -1 : 1), candidates.size());
		var family = candidates.get(next);
		site.placement().setMaterial(slot.id(), family.id());
		manager().save();
		return SquireRuntime.ExecutionResult.ok("feedback.blueprint_material_changed",
			"[Squire] " + slot.displayName() + "已改为" + family.displayName() + "。");
	}

	public SquireRuntime.ExecutionResult resetMaterials(ServerPlayerEntity sender) {
		Site site = siteOf(sender);
		if (site.failure() != null) return site.failure();
		if (!site.placement().resetMaterials(site.blueprint().defaultPalette())) {
			return SquireRuntime.ExecutionResult.fail("feedback.blueprint_committed",
				"[Squire] 已经开工，不能再更换材料。");
		}
		manager().save();
		return SquireRuntime.ExecutionResult.ok("feedback.blueprint_material_reset",
			"[Squire] 已恢复这份蓝图的默认材料。");
	}

	/** Transfer only currently missing construction items from the player's inventory. */
	public SquireRuntime.ExecutionResult transferMissing(ServerPlayerEntity sender) {
		Site site = siteOf(sender);
		if (site.failure() != null) return site.failure();
		Map<Identifier, Integer> missing = site.missing();
		if (missing.isEmpty()) {
			return SquireRuntime.ExecutionResult.ok("feedback.blueprint_ready",
				"[Squire] 侍从背包里的材料已经齐了。");
		}
		int movedTotal = 0;
		for (Map.Entry<Identifier, Integer> entry : missing.entrySet()) {
			var item = net.minecraft.registry.Registries.ITEM.get(entry.getKey());
			int needed = entry.getValue();
			for (int slot = 0; slot < 36 && needed > 0; slot++) {
				var source = sender.getInventory().getStack(slot);
				if (source.isEmpty() || source.getItem() != item) continue;
				int move = Math.min(needed, Math.min(source.getCount(),
					site.avatar().items().insertableAmount(source)));
				if (move <= 0) continue;
				var portion = source.split(move);
				var leftover = site.avatar().items().insert(portion);
				int inserted = move - leftover.getCount();
				if (!leftover.isEmpty()) source.increment(leftover.getCount());
				needed -= inserted;
				movedTotal += inserted;
			}
		}
		sender.getInventory().markDirty();
		sender.currentScreenHandler.sendContentUpdates();
		manager().save();
		Map<Identifier, Integer> after = site.missing();
		String suffix = after.isEmpty() ? "材料齐了，可以确认开工。"
			: "仍缺：" + describeMissing(after);
		return SquireRuntime.ExecutionResult.ok("feedback.blueprint_transferred",
			"[Squire] 从你的背包转交了 " + movedTotal + " 件材料。" + suffix);
	}

	/** 摆放落点：玩家正前方推开一段，再回退半个足印让建筑居中。 */
	private static BlockPos originFor(ServerPlayerEntity sender, Blueprint blueprint,
			Direction facing) {
		int w = blueprint.placedWidth(facing);
		int d = blueprint.placedDepth(facing);
		BlockPos front = sender.getBlockPos()
			.offset(facing, STANDOFF + Math.max(w, d) / 2);
		return front.add(-w / 2, 0, -d / 2);
	}

	// ------------------------------------------------------------------ 兑现缺料

	/**
	 * 把缺的材料用指令兑现<b>到伙伴背包</b>。
	 *
	 * <p>这是「原料获取仍走指令兑现」那条边界的落点：伙伴不去野外挖矿伐木，
	 * 但他可以把兑现来的料真的搬到工地、真的一格一格用掉。</p>
	 */
	public SquireRuntime.ExecutionResult fulfil(ServerPlayerEntity sender) {
		Site site = siteOf(sender);
		if (site.failure() != null) {
			return site.failure();
		}
		if (!runtime.permissions().has(sender, PermissionNodes.COMMAND_GIVE)) {
			return SquireRuntime.ExecutionResult.fail("feedback.permission_denied",
				"[Squire] 你没有使用物品指令兑现的权限。");
		}
		Map<Identifier, Integer> missing = site.missing();
		if (missing.isEmpty()) {
			return SquireRuntime.ExecutionResult.ok("feedback.blueprint_ready",
				"[Squire] 材料已经齐了，可以在工程页确认开工。");
		}
		int fulfilled = 0;
		for (Map.Entry<Identifier, Integer> entry : missing.entrySet()) {
			int remaining = entry.getValue();
			int stackSize = Math.max(1, net.minecraft.registry.Registries.ITEM
				.get(entry.getKey()).getMaxCount());
			while (remaining > 0) {
				int chunk = Math.min(remaining, stackSize);
				var outcome = dev.squire.server.command.StructuredCommandCompiler
					.executeAcquireForAgent(runtime.server, site.avatar(),
						new dev.squire.server.command.StructuredCommandCompiler
							.AcquireIntent(entry.getKey(), chunk, List.of()),
						runtime.protectionAdapter);
				if (!outcome.success()) {
					return SquireRuntime.ExecutionResult.fail("feedback.command_failed",
						"[Squire] 取货指令失败：" + outcome.errorDetail());
				}
				remaining -= chunk;
				fulfilled += chunk;
			}
		}
		// 立刻收进背包。等每 10 tick 一次的自动捡拾，下一句 build 会当场判缺料。
		dev.squire.server.task.executors.ContainerExecutors.PickupNearby
			.sweep(site.avatar(), 4.0);
		return SquireRuntime.ExecutionResult.ok("feedback.blueprint_fulfilled",
			"[Squire] 兑现了 " + fulfilled + " 件材料到我背包里。"
				+ "可以在工程页确认开工。");
	}

	// ------------------------------------------------------------------ 开工

	public SquireRuntime.ExecutionResult build(ServerPlayerEntity sender) {
		// All construction now goes through Project so pause/resume/blockers are durable.
		if (sender != null) return runtime.projectConfirm(sender);
		Site site = siteOf(sender);
		if (site.failure() != null) {
			return site.failure();
		}
		if (!runtime.permissions().has(sender, PermissionNodes.WORLD_PLACE)) {
			return SquireRuntime.ExecutionResult.fail("feedback.permission_denied",
				"[Squire] 你没有让伙伴放置方块的权限。");
		}
		List<BlockPos> toClear = BlueprintManager.pendingClear(site.world(),
			site.resolved());
		if (!toClear.isEmpty()
				&& !runtime.permissions().has(sender, PermissionNodes.WORLD_BREAK)) {
			return SquireRuntime.ExecutionResult.fail("feedback.permission_denied",
				"[Squire] 这份蓝图要先挖出 " + toClear.size()
					+ " 格负空间，但你没有让伙伴破坏方块的权限。");
		}
		BillOfMaterials bill = site.bill();
		List<Task> resupply = List.of();
		if (!bill.satisfied()) {
			// 「工地物流」：管家会自己去工地附近的箱子里取料，而不是直接拒绝。
			resupply = planResupply(site, bill, sender);
			if (!resupply.isEmpty()) {
				bill = null; // 取料任务先跑，施工到时候再看真实背包
			}
		}
		if (bill != null && !bill.satisfied()) {
			// 缺料一定要给出路，而不是只说一句「不够」。
			StringBuilder text = new StringBuilder("[Squire] 材料还不够：");
			for (Map.Entry<Identifier, Integer> entry : bill.missing().entrySet()) {
				text.append("\n  · 还差 ").append(entry.getValue()).append(" 个 ")
					.append(runtime.itemAliases().displayName(entry.getKey().toString()));
			}
			text.append("\n可以在工程页从你的背包转交，或把材料丢在我脚边让我捡起来。");
			return SquireRuntime.ExecutionResult.fail("feedback.blueprint_missing",
				text.toString());
		}
		// 玩家刚下了这条命令：它优先于之前的「待命」。
		String stayNote = runtime.beginOrderedWork(site.avatar(),
			site.resolved().bounds().min());
		UUID agentId = site.avatar().agentId();
		String placementId = site.placement().placementId.toString();
		Task excavate = null;
		if (!toClear.isEmpty()) {
			Map<String, Object> params = new LinkedHashMap<>();
			params.put(ExcavateExecutor.PARAM_PLACEMENT_ID, placementId);
			excavate = new Task(agentId, sender.getUuid(), ExcavateExecutor.TYPE,
				TaskPriority.P3_USER_TASK,
				"挖出 " + site.blueprint().displayName() + " 的负空间 "
					+ toClear.size() + " 格",
				null, ExcavateExecutor.blueprintCleared(runtime.runtimeServices,
					site.placement().placementId),
				600L + 40L * toClear.size(), RetryPolicy.DEFAULT, true, "c1", params);
		}
		List<Blueprint.Cell> toPlace = BlueprintManager.pendingPlacements(site.world(),
			site.resolved());
		Map<String, Object> params = new LinkedHashMap<>();
		params.put(BlueprintBuildExecutor.PARAM_PLACEMENT_ID, placementId);
		Task build = new Task(agentId, sender.getUuid(), BlueprintBuildExecutor.TYPE,
			TaskPriority.P3_USER_TASK,
			"按蓝图盖 " + site.blueprint().displayName() + "（" + toPlace.size() + " 格）",
			null, BlueprintBuildExecutor.blueprintBuilt(runtime.runtimeServices,
				site.placement().placementId),
			600L + 40L * Math.max(1, toPlace.size()), RetryPolicy.DEFAULT, true, "c1",
			params);
		// 取料 → 掏空 → 砦墙。顺序靠任务依赖表达，而不是在执行器里再搭状态机。
		for (Task withdraw : resupply) {
			if (excavate != null) {
				excavate.dependsOn(withdraw);
			}
			build.dependsOn(withdraw);
			runtime.scheduler().submit(withdraw, runtime.currentTick());
		}
		if (excavate != null) {
			// 先掏空再砌墙：反过来会把刚盖好的墙又挖掉一遍。
			build.dependsOn(excavate);
			runtime.scheduler().submit(excavate, runtime.currentTick());
		}
		runtime.scheduler().submit(build, runtime.currentTick());
		site.placement().setState(BlueprintPlacement.State.BUILDING);
		manager().save();
		String digNote = excavate == null ? ""
			: "\n先挖 " + toClear.size() + " 格负空间，再砌。";
		String haulNote = resupply.isEmpty() ? ""
			: "\n缺的料我先去附近的箱子里取（" + resupply.size() + " 趟）。";
		return SquireRuntime.ExecutionResult.ok("feedback.blueprint_building",
			"[Squire] 开工了：" + site.blueprint().displayName() + "，"
				+ toPlace.size() + " 格要放。" + haulNote + digNote
				+ "\n中途没料我会停下并告诉你还差什么。完工后直接对我说「撤销」可以还原。"
				+ stayNote);
	}

	/**
	 * 「工地物流」：把缺的料从工地附近的容器里取出来。
	 *
	 * <p>用的是现成的 {@code container.withdraw} 执行器和现成的任务依赖，
	 * 而不是在施工执行器里再搭一个「缺料→去取→回来」的子状态机：
	 * 一条已经被测过的路径胜过一条新的。</p>
	 *
	 * <p>取不全也照发：施工到时候会在真实背包上如实停下来，并告诉玩家还差什么。</p>
	 */
	private List<Task> planResupply(Site site, BillOfMaterials bill,
			ServerPlayerEntity sender) {
		if (!runtime.can(site.avatar(),
				dev.squire.server.profile.Ability.LOGISTICS_SITEWORK)) {
			return List.of();
		}
		Map<Identifier, Integer> missing = bill.missing();
		if (missing.isEmpty()) {
			return List.of();
		}
		List<Task> out = new java.util.ArrayList<>();
		var bounds = site.resolved().bounds();
		BlockPos centre = new BlockPos(
			(bounds.min().getX() + bounds.max().getX()) / 2,
			(bounds.min().getY() + bounds.max().getY()) / 2,
			(bounds.min().getZ() + bounds.max().getZ()) / 2);
		for (Map.Entry<Identifier, Integer> entry : missing.entrySet()) {
			BlockPos chest = findContainerWith(site.world(), centre, entry.getKey());
			if (chest == null) {
				continue;
			}
			Map<String, Object> params = new LinkedHashMap<>();
			params.put(dev.squire.server.task.executors.ContainerExecutors.PARAM_X,
				chest.getX());
			params.put(dev.squire.server.task.executors.ContainerExecutors.PARAM_Y,
				chest.getY());
			params.put(dev.squire.server.task.executors.ContainerExecutors.PARAM_Z,
				chest.getZ());
			params.put(dev.squire.server.task.executors.ContainerExecutors.PARAM_ITEM_ID,
				entry.getKey().toString());
			params.put(dev.squire.server.task.executors.ContainerExecutors.PARAM_COUNT,
				entry.getValue());
			out.add(new Task(site.avatar().agentId(), sender.getUuid(),
				dev.squire.server.task.executors.ContainerExecutors.Withdraw.TYPE,
				TaskPriority.P3_USER_TASK,
				"去箱子里取 " + entry.getValue() + " 个 "
					+ runtime.itemAliases().displayName(entry.getKey().toString()),
				null, null, 1200L, RetryPolicy.DEFAULT, true, "c2", params));
		}
		return List.copyOf(out);
	}

	/** 工地物流的搜索半径。再远就不叫「工地附近」了。 */
	private static final int RESUPPLY_RADIUS = 16;

	/** 工地附近装着这个东西的容器，没有就返回 null。 */
	private static BlockPos findContainerWith(ServerWorld world, BlockPos centre,
			Identifier itemId) {
		var item = net.minecraft.registry.Registries.ITEM.get(itemId);
		BlockPos best = null;
		double bestDistSq = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.iterate(centre.add(-RESUPPLY_RADIUS, -4,
				-RESUPPLY_RADIUS), centre.add(RESUPPLY_RADIUS, 4, RESUPPLY_RADIUS))) {
			if (!(world.getBlockEntity(pos)
					instanceof net.minecraft.inventory.Inventory inventory)) {
				continue;
			}
			if (dev.squire.server.world.ContainerAccess.count(inventory,
					stack -> stack.getItem() == item) <= 0) {
				continue;
			}
			double distSq = pos.getSquaredDistance(centre);
			if (distSq < bestDistSq) {
				bestDistSq = distSq;
				best = pos.toImmutable();
			}
		}
		return best;
	}

	// ------------------------------------------------------------------ 取消

	public SquireRuntime.ExecutionResult cancel(ServerPlayerEntity sender) {
		if (runtime.projects().activeOf(sender.getUuid()).isPresent()) {
			return runtime.projectCancel(sender);
		}
		Optional<BlueprintPlacement> found = manager().activeOf(sender.getUuid());
		if (found.isEmpty()) {
			return SquireRuntime.ExecutionResult.fail("feedback.blueprint_none",
				"[Squire] 你现在没有工地。");
		}
		BlueprintPlacement placement = found.get();
		placement.setState(BlueprintPlacement.State.CANCELLED);
		manager().remove(placement.placementId);
		return SquireRuntime.ExecutionResult.ok("feedback.blueprint_cancelled",
			"[Squire] 工地预览撤掉了，世界没有被修改。");
	}

	/** 管理员：重新读数据包里的蓝图。 */
	public SquireRuntime.ExecutionResult reload() {
		String summary = manager().registry().reload(
			runtime.server.getResourceManager());
		return SquireRuntime.ExecutionResult.ok("feedback.blueprint_reloaded",
			"[Squire] 蓝图已重载：" + summary);
	}

	// ------------------------------------------------------------------ helpers

	/** 一次操作要用到的全部上下文；{@code failure} 非空时其余字段都不可用。 */
	private record Site(BlueprintPlacement placement, Blueprint blueprint,
			Blueprint.Resolved resolved, ServerWorld world, AvatarEntity avatar,
			SquireRuntime.ExecutionResult failure) {

		BillOfMaterials bill() {
			return BlueprintManager.bill(world, resolved, avatar.items());
		}

		Map<Identifier, Integer> missing() {
			return BlueprintManager.missingMaterials(world, resolved, avatar);
		}
	}

	private String describeMissing(Map<Identifier, Integer> missing) {
		if (missing.isEmpty()) {
			return "材料已齐。";
		}
		StringBuilder text = new StringBuilder();
		for (Map.Entry<Identifier, Integer> entry : missing.entrySet()) {
			text.append("\n  · ")
				.append(runtime.itemAliases().displayName(entry.getKey().toString()))
				.append("：还差 ").append(entry.getValue()).append(" 个");
		}
		return text.toString();
	}

	private Site siteOf(ServerPlayerEntity sender) {
		BlueprintPlacement placement = manager().activeOf(sender.getUuid()).orElse(null);
		if (placement == null) {
			return fail(SquireRuntime.ExecutionResult.fail("feedback.blueprint_none",
				"[Squire] 你现在没有工地。可在工程页选择一份模板。"));
		}
		Blueprint blueprint = manager().registry().byId(placement.blueprintId)
			.orElse(null);
		if (blueprint == null) {
			return fail(SquireRuntime.ExecutionResult.fail("feedback.blueprint_unknown",
				"[Squire] 工地上的蓝图「" + placement.blueprintId
					+ "」已经不在注册表里了（数据包换过？）。"
					+ "可在工程页撤掉这个工地。"));
		}
		Optional<AvatarEntity> found = runtime.agents().resolveForOwner(sender.getUuid());
		if (found.isEmpty()) {
			return fail(SquireRuntime.ExecutionResult.fail("feedback.no_agent",
				"[Squire] 侍从不在场。请右键召集铃；首次召唤方法可按 K 查看。"));
		}
		AvatarEntity avatar = found.get();
		if (!(avatar.getWorld() instanceof ServerWorld world)
				|| !world.getRegistryKey().getValue().toString()
					.equals(placement.dimensionId)) {
			return fail(SquireRuntime.ExecutionResult.fail(
				"feedback.blueprint_dimension",
				"[Squire] 工地在 " + placement.dimensionId + "，伙伴不在那个维度。"));
		}
		return new Site(placement, blueprint,
			manager().resolve(placement).orElseThrow(), world, avatar, null);
	}

	private static Site fail(SquireRuntime.ExecutionResult failure) {
		return new Site(null, null, null, null, null, failure);
	}

	private String describe(Site site, String headline) {
		List<BlockPos> toClear = BlueprintManager.pendingClear(site.world(),
			site.resolved());
		StringBuilder text = new StringBuilder("[Squire] ").append(headline)
			.append("：").append(site.blueprint().displayName())
			.append("（").append(site.blueprint().id()).append("）\n");
		text.append("位置：").append(site.resolved().bounds().min().toShortString())
			.append(" 到 ").append(site.resolved().bounds().max().toShortString())
			.append("，朝向 ").append(site.placement().facing.asString()).append("\n");
		text.append("要放 ").append(site.resolved().toPlace().size()).append(" 格");
		if (!toClear.isEmpty()) {
			text.append("，要挖 ").append(toClear.size()).append(" 格");
		}
		Map<Identifier, Integer> missing = site.missing();
		text.append("。材料：").append(describeMissing(missing));
		SiteAssessment assessment = SiteAssessment.assess(site.world(), site.resolved(),
			java.util.Set.of(site.placement().ownerId, site.avatar().getUuid()));
		if (!assessment.issues().isEmpty()) {
			text.append("\n场地检查：");
			for (String issue : assessment.issues()) text.append("\n  · ").append(issue);
		}
		text.append(missing.isEmpty()
			? "\n材料齐了，可在工程页确认开工。"
			: "\n请在工程页一键从你的背包转交材料。");
		text.append("\n不要了也可以在工程页取消。");
		return text.toString();
	}
}
