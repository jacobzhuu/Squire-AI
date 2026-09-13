package dev.squire.server.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import dev.squire.server.blueprint.Blueprint;
import dev.squire.server.blueprint.BlueprintManager;
import dev.squire.server.blueprint.BlueprintPlacement;
import dev.squire.server.blueprint.SiteAssessment;
import dev.squire.server.project.Project;
import dev.squire.server.project.ProjectCoordinator;
import dev.squire.server.project.Stage;
import dev.squire.server.security.PermissionNodes;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;

/**
 * 工程的玩家意图层：开工、看进度、暂停、继续、取消。
 *
 * <p>玩家只说一句大目标；分解、排序、分派全在服务端。模型也一样——它只能挑一个
 * 模板名和一个落点，不能自由构造阶段（和 {@code cbp.plan_project} 同一套路）。</p>
 *
 * <p>每一条回复都必须让玩家知道<b>现在轮到谁</b>：轮到他交料就把缺料列出来，
 * 轮到伙伴干活就说在干什么。一个只说「进行中」的工程页，和没有这一页一样。</p>
 */
final class SquireProjectService {

	private final SquireRuntime runtime;

	SquireProjectService(SquireRuntime runtime) {
		this.runtime = runtime;
	}

	private ProjectCoordinator projects() {
		return runtime.projects();
	}

	// ------------------------------------------------------------------ 开工

	public SquireRuntime.ExecutionResult start(ServerPlayerEntity sender,
			String blueprintId) {
		var assigned = runtime.agents().resolveForOwner(sender.getUuid()).orElse(null);
		var assignedProfile = assigned == null ? null : runtime.profileOf(assigned);
		if (assignedProfile == null || assignedProfile.profession.profession()
				!= dev.squire.server.profession.SquireProfession.ENGINEER) {
			return SquireRuntime.ExecutionResult.fail("feedback.engineer_only",
				"[Squire] 工程需要交给工程师。请点名工程师，或从他的面板打开工程页。");
		}
		if (projects().activeOf(sender.getUuid()).isPresent()) {
			return SquireRuntime.ExecutionResult.fail("feedback.project_busy",
				"[Squire] 已经有一个工程在做了。进度和取消入口都在工程页。");
		}
		Blueprint blueprint = runtime.blueprints().registry().byId(blueprintId)
			.orElse(null);
		if (blueprint == null) {
			return SquireRuntime.ExecutionResult.fail("feedback.blueprint_unknown",
				"[Squire] 没有叫「" + blueprintId + "」的蓝图。可在工程页查看模板。");
		}
		// 摆放走蓝图那条路径，连同它的全部校验（保护判定、规模上限、方块是否存在）。
		var placed = runtime.blueprintPlace(sender, blueprint.id());
		if (!placed.success()) {
			return placed;
		}
		return SquireRuntime.ExecutionResult.ok("feedback.project_preview",
			placed.message() + "\n请调整粒子轮廓，确认后再开工。工程页可旋转、平移和转交材料；"
				+ "准备好后在工程页确认建造。");
	}

	/** Confirm the active ghost and create the durable project. */
	public SquireRuntime.ExecutionResult confirm(ServerPlayerEntity sender) {
		if (projects().activeOf(sender.getUuid()).isPresent()) {
			return SquireRuntime.ExecutionResult.fail("feedback.project_busy",
				"[Squire] 已经有一个工程在做了。");
		}
		BlueprintPlacement placement = runtime.blueprints().activeOf(sender.getUuid())
			.orElse(null);
		if (placement == null || placement.state() == BlueprintPlacement.State.BUILDING) {
			return SquireRuntime.ExecutionResult.fail("feedback.blueprint_none",
				"[Squire] 先在工程页选择蓝图并摆好预览。");
		}
		Blueprint blueprint = runtime.blueprints().registry().byId(placement.blueprintId)
			.orElse(null);
		if (dev.squire.server.blueprint.BuildingContentPolicy.current().retired(placement.blueprintId))
			return SquireRuntime.ExecutionResult.fail("feedback.blueprint_retired", "旧自有模板已停止新建，请取消预览后选择开源建筑。");
		if (blueprint == null || !(sender.getWorld()
				instanceof net.minecraft.server.world.ServerWorld world)) {
			return SquireRuntime.ExecutionResult.fail("feedback.project_failed",
				"[Squire] 工地蓝图已经不可用，请撤掉后重选。");
		}
		if (!world.getRegistryKey().getValue().toString().equals(placement.dimensionId)) {
			return SquireRuntime.ExecutionResult.fail("feedback.project_dimension",
				"[Squire] 工地在 " + placement.dimensionId + "，你需要回到那个维度再确认。");
		}
		if (!runtime.permissions().has(sender, PermissionNodes.WORLD_PLACE)
				&& dev.squire.server.blueprint.TerrainLeveling.parse(placement.blueprintId).isEmpty()) {
			return SquireRuntime.ExecutionResult.fail("feedback.permission_denied",
				"[Squire] 你没有让伙伴放置方块的权限。");
		}
		var resolved = runtime.blueprints().resolve(placement).orElse(null);
		boolean terrain = dev.squire.server.blueprint.TerrainLeveling.parse(placement.blueprintId).isPresent();
		if (terrain) {
			var target = runtime.agents().resolveForOwner(sender.getUuid()).orElse(null);
			if (target == null || !target.agentId().equals(placement.agentId))
				return SquireRuntime.ExecutionResult.fail("feedback.terrain_blocked", "请从绑定这份平地预览的工程师面板确认");
			var failure = TerrainLevelingService.checkConfirmation(sender, placement);
			if (failure != null) return failure;
			resolved = placement.snapshot();
		}
		if (resolved == null) {
			return SquireRuntime.ExecutionResult.fail("feedback.project_failed",
				"[Squire] 工地材料配置已经不可用，请撤掉后重选。");
		}
		if (placement.waterSource() != null) {
			var source = placement.waterSource();
			if (!runtime.permissions().has(sender, PermissionNodes.WORLD_BREAK)
					|| source.getSquaredDistance(placement.origin) > 64 * 64 || BlueprintManager.projectBounds(world, resolved).contains(source)
					|| !world.isChunkLoaded(source) || !world.getBlockState(source).isOf(net.minecraft.block.Blocks.WATER) || !world.getFluidState(source).isStill()
					|| !runtime.protectionAdapter.canBreak(world, source, sender.getUuid()).allowed()
					|| !runtime.protectionAdapter.canInteract(world, source, sender.getUuid()).allowed())
				return SquireRuntime.ExecutionResult.fail("feedback.site_unsafe", "指定水源无效、越界、未加载或未获取水权限；请重新指定工程外 64 格内的水源。");
		}
		var toClear = BlueprintManager.pendingClear(world, resolved);
		if (!toClear.isEmpty()
				&& !runtime.permissions().has(sender, PermissionNodes.WORLD_BREAK)) {
			return SquireRuntime.ExecutionResult.fail("feedback.permission_denied",
				"[Squire] 这份蓝图要先清理 " + toClear.size()
					+ " 个方块，但你没有让伙伴破坏方块的权限。");
		}
		var decision = runtime.protectionAdapter.canEditRegion(world,
			BlueprintManager.projectBounds(world, resolved), sender.getUuid());
		if (!decision.allowed()) {
			return SquireRuntime.ExecutionResult.fail("feedback.protected",
				"[Squire] 这块地不允许施工：" + decision.reason());
		}
		var avatar = runtime.agents().resolveByAgentId(placement.agentId).orElse(null);
		if (avatar == null) {
			return SquireRuntime.ExecutionResult.fail("feedback.no_agent",
				"[Squire] 还没有可用的侍从。");
		}
		var profile = runtime.profileOf(avatar);
		if (profile == null || profile.profession.profession()
				!= dev.squire.server.profession.SquireProfession.ENGINEER) {
			return SquireRuntime.ExecutionResult.fail("feedback.engineer_only",
				"[Squire] 这份蓝图没有绑定工程师，请撤掉预览后由工程师重新选择。");
		}
		SiteAssessment assessment = SiteAssessment.assess(world, resolved,
			java.util.Set.of(sender.getUuid(), avatar.getUuid()));
		var unmet = resolved.siteRequirements().stream().filter(r -> !r.kind().equals("terrain_surface")
			&& !r.kind().equals("terrain_air") && !r.satisfied(world)).findFirst().orElse(null);
		if (resolved.access() != null && !resolved.access().valid())
			return SquireRuntime.ExecutionResult.fail("feedback.site_unsafe", resolved.access().failure);
		if (unmet != null) return SquireRuntime.ExecutionResult.fail("feedback.site_unsafe",
			unmet.kind().equals("solid") ? "地基状态已变化，请重新预览以计算自动填补材料。"
				: "这份组件需要水域：" + unmet.pos().toShortString() + "；可在工程页切换人工水域，由工程师挖池并注水。");
		var buildPolicy = EngineerBuildPolicy.evaluate(blueprint, profile.profession, runtime.professionConfig());
		if (!buildPolicy.allowed()) return SquireRuntime.ExecutionResult.fail("feedback.design_locked", buildPolicy.reason());
		if (!assessment.executable()) {
			return SquireRuntime.ExecutionResult.fail("feedback.site_unsafe",
				"[Squire] 这个位置暂时不能开工：\n  · "
					+ String.join("\n  · ", assessment.blockingIssues()));
		}
		var accessProblem = validateAccess(sender, world, resolved);
		if (accessProblem != null) return accessProblem;
		Map<Identifier, Integer> required = BlueprintManager
			.requiredProjectMaterials(world, resolved);
		Reservation reservation = planReservation(sender, avatar, required);
		Project project = new Project(UUID.randomUUID(), sender.getUuid(), avatar.agentId(),
			blueprint.displayName(), blueprint.id(), placement.placementId,
			placement.dimensionId, runtime.currentTick(),
			terrain ? ProjectCoordinator.compileTerrainStages() : ProjectCoordinator.compileStages());
		placement.snapshot(resolved, true);
		if (!runtime.blueprints().save()) {
			placement.snapshot(resolved, false);
			return SquireRuntime.ExecutionResult.fail("feedback.project_recovery_required", "蓝图快照暂不可写，未建立工程也未提取材料；请检查存档目录。");
		}
		projects().put(project);
		if (!projects().beginMutation(project.projectId, "deposit:" + reservation.fromOwner() + ":" + reservation.fromAgent()))
			return SquireRuntime.ExecutionResult.fail("feedback.project_recovery_required", "工程存档暂不可写，未提取材料；请先检查存档与恢复记录。");
		if (!commitAvailableReservation(sender, avatar, reservation)) {
			project.setState(Project.State.PAUSED); projects().completeMutation(project.projectId);
			return SquireRuntime.ExecutionResult.fail("feedback.project_reserve_changed",
				"[Squire] 预留材料时库存发生了变化，没有开工也没有丢失物品，请再试一次。");
		}
		project.reserve(reservation.fromOwner(), reservation.fromAgent());
		if (!projects().completeMutation(project.projectId)) return SquireRuntime.ExecutionResult.fail("feedback.project_recovery_required", "存料结算未能写入，已暂停工程并保留恢复记录。");
		placement.snapshot(resolved, true);
		placement.authorizeLevel(buildPolicy.minLevel());
		Map<Identifier, Integer> missing = project.missingFrom(required);
		if (!missing.isEmpty()) {
			project.setState(Project.State.PAUSED);
			project.currentStage().ifPresent(stage -> stage.block(
				Stage.BlockerCode.MATERIALS_MISSING,
				materialBlockReason(project, required, missing)));
		}
		projects().put(project);
		placement.setState(BlueprintPlacement.State.BUILDING);
		runtime.blueprints().save();
		if (!missing.isEmpty()) {
			return SquireRuntime.ExecutionResult.ok("feedback.project_waiting_materials",
				"[Squire] 已建立「" + project.name + "」工程，本批已存入 "
					+ reservation.total() + " 件材料。\n"
					+ materialBlockReason(project, required, missing)
					+ missingMaterialLines(missing)
					+ "\n材料可以分批带来；每批放进你或侍从的背包后，点击“补料并重试”。"
					+ " 未齐料前不会拆除或放置方块。");
		}
		// 开工是一条新命令：它优先于之前的「待命」。
		String stayNote = runtime.beginOrderedWork(avatar, placement.origin);
		if (terrain) return SquireRuntime.ExecutionResult.ok("feedback.project_started",
			"[Squire] 平地已开工：备料 → 清理 → 填补 → 验收。已预留 " + reservation.total()
				+ " 件真实材料；可在工程页暂停、继续或取消。" + stayNote);
		return SquireRuntime.ExecutionResult.ok("feedback.project_started",
			"[Squire] 好，「" + project.name + "」这个工程我接了。"
				+ "\n分成 " + project.stages().size() + " 步：备料 → 场地准备 → 掘进 → 施工"
				+ " → 点灯 → 验收。"
				+ "\n已从你和侍从的真实库存预留 " + reservation.total()
				+ " 件工程物资，施工不会再被其他任务挪用。"
				+ (assessment.liquidCells() > 0
					? "\n工地积水会在清障与施工过程中自动排掉。" : "")
				+ (assessment.unsupportedFloorCells() > 0
					? "\n悬空地基会先自动补一层承重基础。" : "")
				+ "\n可随时回工程页看进度。" + stayNote);
	}

	/**
	 * 从一句自然语言里认出要盖哪份蓝图。
	 *
	 * <p>解析在<b>服务端</b>而不是 FastPath 里，因为注册表（含数据包加载的蓝图）
	 * 只存在于服务端。认不出来时把可选项列出来——「任何输入都要有出路」。</p>
	 */
	public SquireRuntime.ExecutionResult startFromPhrase(ServerPlayerEntity sender,
			String phrase) {
		String text = phrase == null ? "" : phrase.toLowerCase(java.util.Locale.ROOT);
		String matched = null;
		var catalog = runtime.blueprints().registry().catalog();
		var worker = runtime.agents().resolveForOwner(sender.getUuid()).orElse(null);
		int level = worker == null || runtime.professionOf(worker) == null ? 0 : runtime.professionOf(worker).level;
		for (var variant : catalog.variants()) if (variant.buildable() && text.contains(variant.id())) { matched = variant.id(); break; }
		if (matched == null) for (var family : catalog.families().stream().sorted(java.util.Comparator.comparingInt((dev.squire.server.blueprint.BuildingCatalog.BuildingDefinition f) -> f.name().length()).reversed()).toList()) {
			if (phrase != null && phrase.contains(family.name())) {
				matched = family.variants().stream().filter(v -> v.allowed(level)).findFirst().map(dev.squire.server.blueprint.BuildingCatalog.BuildingVariantDefinition::id).orElse(null);
				if (matched != null) break;
			}
		}
		for (Blueprint blueprint : runtime.blueprints().registry().all()) {
			if (matched != null) break;
			if (dev.squire.server.blueprint.BuildingContentPolicy.current().retired(blueprint.id())) continue;
			if (text.contains(blueprint.id())
					|| phrase != null && phrase.contains(blueprint.displayName())) {
				matched = blueprint.id();
				break;
			}
		}
		if (matched == null) {
			StringBuilder options = new StringBuilder(
				"[Squire] 我没找到当前等级可建的匹配项。可选建筑家族：");
			for (var family : catalog.families()) {
				if (family.variants().stream().anyMatch(v -> v.allowed(level))) options.append("\n· ").append(family.name());
			}
			options.append("\n工程页可以浏览全部家族、Tier 与未开放原因。");
			return SquireRuntime.ExecutionResult.fail("feedback.project_unknown",
				options.toString());
		}
		return start(sender, matched);
	}


	// ------------------------------------------------------------------ 查看

	public SquireRuntime.ExecutionResult status(ServerPlayerEntity sender) {
		Optional<Project> found = projects().activeOf(sender.getUuid());
		if (found.isEmpty()) {
			return SquireRuntime.ExecutionResult.ok("feedback.project_none",
				"[Squire] 现在没有在做的工程。"
					+ "\n可以直接说「帮我准备一个矿井前哨站」，也可以在工程页选择模板。");
		}
		Project project = found.get();
		if (!project.pendingMutation().isEmpty()) return SquireRuntime.ExecutionResult.fail("feedback.project_failed",
			"工程存在未确定的施工结算，已冻结且不会自动退款或补建；请核对恢复记录：" + project.pendingMutation());
		StringBuilder text = new StringBuilder("[Squire] 「").append(project.name)
			.append("」 ").append(project.progress());
		if (project.state() == Project.State.PAUSED) {
			text.append("（已暂停）");
		} else if (project.state() == Project.State.FAILED) {
			text.append("（停住了）");
		}
		for (Stage stage : project.stages()) {
			text.append("\n  ").append(marker(stage)).append(" ")
				.append(stage.displayName());
			if (stage.blockedReason() != null) {
				text.append(" —— ").append(stage.blockerCode() == Stage.BlockerCode.MATERIALS_MISSING
					? liveMaterialStatus(sender, project, stage.blockedReason()) : stage.blockedReason());
			}
		}
		text.append(project.state() == Project.State.RUNNING
			? "\n可在工程页暂停。"
			: "\n可在工程页继续。");
		text.append("不想做了也可在工程页取消。");
		return SquireRuntime.ExecutionResult.ok("feedback.project_status",
			text.toString());
	}

	private String liveMaterialStatus(ServerPlayerEntity owner, Project project, String fallback) {
		var placement = runtime.blueprints().placement(project.placementId).orElse(null);
		if (placement == null) return fallback;
		var avatar = runtime.agents().resolveByAgentId(placement.agentId).orElse(null);
		var resolved = runtime.blueprints().resolve(placement).orElse(null);
		if (resolved == null || avatar == null || !(avatar.getWorld() instanceof ServerWorld world)
				|| !world.getRegistryKey().getValue().toString().equals(placement.dimensionId)) return fallback;
		var missing = BlueprintManager.missingProjectMaterials(world, resolved, owner, avatar,
			project.reservedMaterials());
		return missing.isEmpty() ? "材料已齐（含工程池及双方背包），请在工程页存入本批材料并继续。"
			: "已扣除工程池及双方背包，仍缺：" + missingMaterialLines(missing);
	}

	/** 一眼能看出「做完了 / 在做 / 卡住了 / 还没轮到」。 */
	private static String marker(Stage stage) {
		return switch (stage.state()) {
			case DONE -> "[x]";
			case SKIPPED -> "[-]";
			case RUNNING -> "[>]";
			case BLOCKED -> "[!]";
			case FAILED -> "[X]";
			default -> "[ ]";
		};
	}

	// ------------------------------------------------------------------ 控制

	public SquireRuntime.ExecutionResult pause(ServerPlayerEntity sender) {
		Optional<Project> found = projects().activeOf(sender.getUuid());
		if (found.isEmpty()) {
			return noProject();
		}
		projects().pause(found.get());
		return SquireRuntime.ExecutionResult.ok("feedback.project_paused",
			"[Squire] 「" + found.get().name + "」暂停了，进度都留着。"
				+ "\n准备好后可在工程页继续。");
	}

	public SquireRuntime.ExecutionResult resume(ServerPlayerEntity sender) {
		Optional<Project> found = projects().activeOf(sender.getUuid());
		if (found.isEmpty()) {
			return noProject();
		}
		Project project = found.get();
		if (!project.pendingMutation().isEmpty()) return SquireRuntime.ExecutionResult.fail("feedback.project_failed",
			"未确定的施工结算已冻结，禁止补料、退款或免费补建：" + project.pendingMutation());
		var placement = runtime.blueprints().placement(project.placementId).orElse(null);
		// Projects written before schema v4 did not persist the assigned squire.  Bind
		// them once from the placement so the first manual resume after upgrading is
		// already routed to the right inventory (without waiting for a coordinator tick).
		if (project.agentId() == null && placement != null) {
			project.bindAgentIfMissing(placement.agentId);
		}
		var resolved = placement == null ? null : runtime.blueprints().resolve(placement)
			.orElse(null);
		var avatar = project.agentId() == null ? null
			: runtime.agents().resolveByAgentId(project.agentId()).orElse(null);
		if (resolved == null || avatar == null || !(avatar.getWorld() instanceof ServerWorld world)
				|| !world.getRegistryKey().getValue().toString().equals(placement.dimensionId)) {
			return SquireRuntime.ExecutionResult.fail("feedback.project_failed",
					"[Squire] 工程蓝图或侍从当前不可用，进度仍然保留。");
		}
		boolean cleaning = resolved.access() != null && resolved.access().cancelRequested;
		if (!cleaning && (runtime.professionOf(avatar) == null || runtime.professionOf(avatar).profession()
				!= dev.squire.server.profession.SquireProfession.ENGINEER))
			return SquireRuntime.ExecutionResult.fail("feedback.engineer_only", "工程仍绑定原侍从；请让他恢复工程师职业后继续。");
		if (!cleaning && runtime.professionOf(avatar).level < placement.authorizedLevel())
			return SquireRuntime.ExecutionResult.fail("feedback.design_locked", "这份已确认工程需要工程师 Lv" + placement.authorizedLevel() + "，进度与材料保留。");
		// Older executor versions unlocked the placement at an intermediate stage.
		// A funded project must keep its palette/transform fixed, including on resume.
		if (placement.state() != dev.squire.server.blueprint.BlueprintPlacement.State.BUILDING) {
			placement.setState(dev.squire.server.blueprint.BlueprintPlacement.State.BUILDING);
			runtime.blueprints().save();
		}
		if (project.state() == Project.State.RUNNING && project.currentStage()
				.map(stage -> stage.state() == Stage.State.RUNNING).orElse(false)) {
			return SquireRuntime.ExecutionResult.ok("feedback.project_resumed",
				"[Squire] 工程正在施工，无需重复开工；需要补料时会提示。");
		}
		Map<Identifier, Integer> required = BlueprintManager.requiredProjectMaterials(world, resolved);
		if (placement.committed() && resolved.access() != null && project.currentStage()
				.map(s -> s.blockerCode() == Stage.BlockerCode.NO_REACHABLE_TARGET).orElse(false)) {
			var access = resolved.access();
			if (!access.assistance) { access.assistance = true; access.recoveries++; access.recoveryReason = "RESUME_BLOCKED_ROUTE"; }
			if (!runtime.blueprints().save()) return SquireRuntime.ExecutionResult.fail("feedback.project_failed", "恢复状态暂不可写，工程保持暂停。");
		}
		if (!placement.committed()) {
			var blueprint = runtime.blueprints().registry().byId(placement.blueprintId).orElse(null);
			if (blueprint == null) return SquireRuntime.ExecutionResult.fail("feedback.project_failed", "旧工程的模板已不可用，进度与材料保留。");
			var admission = EngineerBuildPolicy.evaluate(blueprint, runtime.professionOf(avatar), runtime.professionConfig());
			if (!admission.allowed()) return SquireRuntime.ExecutionResult.fail("feedback.design_locked", admission.reason());
			if (resolved.access() != null && !resolved.access().valid())
				return SquireRuntime.ExecutionResult.fail("feedback.site_unsafe", resolved.access().failure);
			// First click on an old unpinned tall project only presents the added footprint.
			if (resolved.access() != null && (resolved.access().temporaryCount() > 0 || resolved.access().excavationCount() > 0)
					&& !"ACCESS_CONFIRMATION_REQUIRED".equals(project.currentStage().map(Stage::blockedReason).orElse(""))) {
				projects().pause(project);
				project.currentStage().ifPresent(stage -> stage.block("ACCESS_CONFIRMATION_REQUIRED"));
				projects().save();
				return SquireRuntime.ExecutionResult.ok("feedback.project_preview", "旧工程需要临时施工通道（蓝色预览），最多外扩 " + dev.squire.server.blueprint.ConstructionAccessPlan.MARGIN + " 格，额外脚手架 "
					+ resolved.access().temporaryCount() + "，开挖 " + resolved.access().excavationCount() + " 格（橙色预览）。再次点击继续即确认这个范围。");
			}
			var accessProblem = validateAccess(sender, world, resolved);
			if (accessProblem != null) return accessProblem;
			placement.authorizeLevel(admission.minLevel());
			placement.snapshot(resolved, true);
			if (!runtime.blueprints().save()) {
				placement.snapshot(resolved, false); project.setState(Project.State.PAUSED);
				return SquireRuntime.ExecutionResult.fail("feedback.project_recovery_required", "蓝图快照暂不可写，工程保持暂停，未提取本批材料。");
			}
		}
		if (resolved.access() != null && resolved.access().cancelRequested) {
			projects().resume(project);
			return SquireRuntime.ExecutionResult.ok("feedback.project_resumed", "继续回收临时施工设施。");
		}
		Map<Identifier, Integer> missing = project.missingFrom(required);
		if (!missing.isEmpty()) {
			Reservation reservation = planReservation(sender, avatar, missing);
			if (reservation.total() == 0) {
				project.setState(Project.State.PAUSED);
				Map<Identifier, Integer> remaining = missing;
				project.currentStage().ifPresent(stage -> stage.block(
					Stage.BlockerCode.MATERIALS_MISSING, materialBlockReason(project, required, remaining)));
				projects().save();
				return missingMaterials(missing,
					"本批没有可存入的缺料。材料可以分批带来；放进你或侍从的背包后再试。"
						+ " 现有施工进度会保留。");
			}
			if (!projects().beginMutation(project.projectId, "deposit:" + reservation.fromOwner() + ":" + reservation.fromAgent()))
				return SquireRuntime.ExecutionResult.fail("feedback.project_recovery_required", "工程存档暂不可写，未提取本批材料。");
			if (!commitAvailableReservation(sender, avatar, reservation)) {
				projects().completeMutation(project.projectId);
				return SquireRuntime.ExecutionResult.fail("feedback.project_reserve_changed",
					"[Squire] 补料时库存发生了变化，工程仍保持原进度，请再试一次。");
			}
			project.reserve(reservation.fromOwner(), reservation.fromAgent());
			if (!projects().completeMutation(project.projectId)) return SquireRuntime.ExecutionResult.fail("feedback.project_recovery_required", "存料结算未能写入，工程已保留恢复记录并暂停。");
			missing = project.missingFrom(required);
			if (!missing.isEmpty()) {
				project.setState(Project.State.PAUSED);
				Map<Identifier, Integer> remaining = missing;
				project.currentStage().ifPresent(stage -> stage.block(
					Stage.BlockerCode.MATERIALS_MISSING,
					materialBlockReason(project, required, remaining)));
				projects().save();
				return SquireRuntime.ExecutionResult.ok(
					"feedback.project_materials_deposited",
					"[Squire] 本批已为「" + project.name + "」存入 "
						+ reservation.total() + " 件材料。\n"
						+ materialBlockReason(project, required, missing)
						+ missingMaterialLines(missing)
						+ "\n可以继续分批补料；材料齐全后会从当前断点施工。");
			}
		}
		project.setSupplyPrepared(true);
		projects().resume(project);
		String resumeNote = runtime.blueprints().placement(project.placementId)
			.map(site -> runtime.beginOrderedWork(avatar, site.origin)).orElse("");
		return SquireRuntime.ExecutionResult.ok("feedback.project_resumed",
			"[Squire] 「" + project.name + "」从当前断点接着做。" + resumeNote);
	}

	/** Replay the reviewed program in order instead of checking every cut against
	 * the unchanged real world. This accepts a safe granite cut after the planned
	 * gravel cut above it, while retaining all fluid, hazard and permission gates. */
	private SquireRuntime.ExecutionResult validateAccess(ServerPlayerEntity sender,
			ServerWorld world, Blueprint.Resolved resolved) {
		var access = resolved.access();
		if (access == null) return null;
		if (!access.valid()) return SquireRuntime.ExecutionResult.fail(
			"feedback.site_unsafe", access.failure);
		Map<net.minecraft.util.math.BlockPos, net.minecraft.block.BlockState> changes =
			new java.util.HashMap<>();
		var view = new dev.squire.server.blueprint.ConstructionBlockView(world, changes);
		for (var work : access.work) {
			var pos = work.cell().pos();
			if (work.excavation()) {
				var state = view.getBlockState(pos);
				if (!dev.squire.server.blueprint.ConstructionAccessPlan.diggable(view, pos))
					return SquireRuntime.ExecutionResult.fail("feedback.site_unsafe",
						"施工通路不能安全开挖 " + state.getBlock().getName().getString()
							+ "：" + pos.toShortString());
				var permission = runtime.protectionAdapter.canBreak(world, pos, sender.getUuid());
				if (!permission.allowed()) return SquireRuntime.ExecutionResult.fail(
					"feedback.protected", "施工通路受到保护：" + pos.toShortString()
						+ (permission.reason() == null ? "" : "（" + permission.reason() + "）"));
				changes.put(pos, net.minecraft.block.Blocks.AIR.getDefaultState());
				continue;
			}
			if (work.temporary()) {
				var state = view.getBlockState(pos);
				if (!state.isAir() && !BlueprintManager.matches(state, work.cell()))
					return SquireRuntime.ExecutionResult.fail("feedback.site_unsafe",
						"临时通道位置被其他方块占用：" + pos.toShortString()
							+ "（" + state.getBlock().getName().getString() + "）");
				var place = runtime.protectionAdapter.canPlace(world, pos, sender.getUuid());
				var reclaim = runtime.protectionAdapter.canBreak(world, pos, sender.getUuid());
				if (!place.allowed() || !reclaim.allowed()) return SquireRuntime.ExecutionResult.fail(
					"feedback.protected", "临时通道无法搭建或回收：" + pos.toShortString());
			}
			for (var cell : dev.squire.server.blueprint.BlueprintAssembly.cells(work.cell(), resolved))
				changes.put(cell.pos(), BlueprintManager.targetState(cell));
		}
		return null;
	}

	// ------------------------------------------------------------------ atomic real-item reservation

	private record Reservation(Map<Identifier, Integer> fromOwner,
		Map<Identifier, Integer> fromAgent, Map<Identifier, Integer> missing) {
		int total() {
			return fromOwner.values().stream().mapToInt(Integer::intValue).sum()
				+ fromAgent.values().stream().mapToInt(Integer::intValue).sum();
		}
	}

	private static Reservation planReservation(ServerPlayerEntity owner,
			dev.squire.server.body.avatar.AvatarEntity avatar,
			Map<Identifier, Integer> required) {
		Map<Identifier, Integer> fromOwner = new LinkedHashMap<>();
		Map<Identifier, Integer> fromAgent = new LinkedHashMap<>();
		Map<Identifier, Integer> missing = new LinkedHashMap<>();
		for (var entry : required.entrySet()) {
			Identifier id = entry.getKey();
			int need = Math.max(0, entry.getValue());
			int agentCount = avatar == null ? 0 : avatar.items().countOf(id);
			int takeAgent = Math.min(need, agentCount);
			if (takeAgent > 0) fromAgent.put(id, takeAgent);
			int left = need - takeAgent;
			int takeOwner = Math.min(left, countPlayer(owner, id));
			if (takeOwner > 0) fromOwner.put(id, takeOwner);
			left -= takeOwner;
			if (left > 0) missing.put(id, left);
		}
		return new Reservation(Map.copyOf(fromOwner), Map.copyOf(fromAgent),
			Map.copyOf(missing));
	}

	private static boolean commitAvailableReservation(ServerPlayerEntity owner,
			dev.squire.server.body.avatar.AvatarEntity avatar, Reservation reservation) {
		List<ItemStack> agentTaken = new ArrayList<>();
		List<ItemStack> ownerTaken = new ArrayList<>();
		for (var entry : reservation.fromAgent().entrySet()) {
			List<ItemStack> taken = avatar.items().extract(entry.getKey(), entry.getValue());
			agentTaken.addAll(taken);
			if (taken.stream().mapToInt(ItemStack::getCount).sum() != entry.getValue()) {
				restore(avatar, owner, agentTaken, ownerTaken);
				return false;
			}
		}
		for (var entry : reservation.fromOwner().entrySet()) {
			List<ItemStack> taken = extractPlayer(owner, entry.getKey(), entry.getValue());
			ownerTaken.addAll(taken);
			if (taken.stream().mapToInt(ItemStack::getCount).sum() != entry.getValue()) {
				restore(avatar, owner, agentTaken, ownerTaken);
				return false;
			}
		}
		owner.getInventory().markDirty();
		owner.currentScreenHandler.sendContentUpdates();
		return true;
	}

	private static int countPlayer(ServerPlayerEntity player, Identifier id) {
		if (player == null || id == null) return 0;
		var item = Registries.ITEM.get(id);
		int total = 0;
		for (int slot = 0; slot < 36; slot++) {
			ItemStack stack = player.getInventory().getStack(slot);
			if (stack.isOf(item)) total += stack.getCount();
		}
		return total;
	}

	private static List<ItemStack> extractPlayer(ServerPlayerEntity player,
			Identifier id, int wanted) {
		List<ItemStack> out = new ArrayList<>();
		var item = Registries.ITEM.get(id);
		int left = wanted;
		for (int slot = 0; slot < 36 && left > 0; slot++) {
			ItemStack stack = player.getInventory().getStack(slot);
			if (!stack.isOf(item)) continue;
			int take = Math.min(left, stack.getCount());
			out.add(stack.split(take));
			left -= take;
		}
		return out;
	}

	private static void restore(dev.squire.server.body.avatar.AvatarEntity avatar,
			ServerPlayerEntity owner, List<ItemStack> agentTaken,
			List<ItemStack> ownerTaken) {
		for (ItemStack stack : agentTaken) avatar.items().insert(stack);
		for (ItemStack stack : ownerTaken) {
			if (!owner.getInventory().insertStack(stack) && !stack.isEmpty()) {
				owner.dropItem(stack, false);
			}
		}
		owner.getInventory().markDirty();
		owner.currentScreenHandler.sendContentUpdates();
	}

	private SquireRuntime.ExecutionResult missingMaterials(
			Map<Identifier, Integer> missing, String nextStep) {
		StringBuilder text = new StringBuilder("[Squire] 工程材料还差：");
		text.append(missingMaterialLines(missing));
		text.append("\n").append(nextStep);
		return SquireRuntime.ExecutionResult.fail("feedback.project_materials_missing",
			text.toString());
	}

	private String materialBlockReason(Project project,
			Map<Identifier, Integer> required, Map<Identifier, Integer> missing) {
		return "工程物资池已预留 " + reservedToward(project, required) + "/"
			+ total(required) + " 件，仍缺 " + total(missing) + " 件。";
	}

	private String missingMaterialLines(Map<Identifier, Integer> missing) {
		StringBuilder text = new StringBuilder();
		for (var entry : missing.entrySet()) {
			text.append("\n  · ").append(runtime.itemAliases().displayName(
				entry.getKey().toString())).append(" ×").append(entry.getValue());
		}
		return text.toString();
	}

	private static int reservedToward(Project project,
			Map<Identifier, Integer> required) {
		int total = 0;
		for (var entry : required.entrySet()) {
			total += Math.min(Math.max(0, entry.getValue()),
				project.reservedCount(entry.getKey()));
		}
		return total;
	}

	private static int total(Map<Identifier, Integer> materials) {
		return materials.values().stream().mapToInt(value -> Math.max(0, value)).sum();
	}

	public SquireRuntime.ExecutionResult forceCancel(ServerPlayerEntity sender) {
		var project = projects().activeOf(sender.getUuid()).orElse(null);
		if (project == null) return noProject();
		if (!projects().forceCancel(project)) return SquireRuntime.ExecutionResult.refused(
			"强制取消未能写入工程存档，请检查磁盘和服务器日志；未执行退款或删除记录。");
		return SquireRuntime.ExecutionResult.ok("feedback.project_force_cancelled",
			"[Squire] 已强制终止「" + project.name + "」，可以创建新工程。已建方块和临时设施保留原地；"
			+ "剩余材料账目与结算记录已封存，不自动退款或回收。");
	}

	public SquireRuntime.ExecutionResult cancel(ServerPlayerEntity sender) {
		Optional<Project> found = projects().activeOf(sender.getUuid());
		if (found.isEmpty()) {
			return noProject();
		}
		Project project = found.get();
		if (!project.pendingMutation().isEmpty()) return SquireRuntime.ExecutionResult.fail("feedback.project_recovery_required",
			"工程有未确定的结算记录（" + project.pendingMutation() + "）；不能自动取消退款。可在工程页点击强制取消，或输入 /squire project cancel force，封存账目并释放工程。");
		projects().cancel(project);
		if (project.active()) return SquireRuntime.ExecutionResult.ok("feedback.project_cancelled",
			"已停止主体施工，正在撤离并回收临时设施；清理受阻时工程页可继续，记录不会丢失。");
		// 工地也一起撤掉：留一个没人管的幽灵轮廓只会让玩家困惑。
		runtime.blueprints().activeOf(sender.getUuid())
			.filter(placement -> placement.placementId.equals(project.placementId))
			.ifPresent(placement -> runtime.blueprints().remove(placement.placementId));
		return SquireRuntime.ExecutionResult.ok("feedback.project_cancelled",
			"[Squire] 「" + project.name + "」不做了。已经盖上去的方块留在原地，"
				+ "需要还原时可以直接对我说「撤销上一步」。");
	}

	private static SquireRuntime.ExecutionResult noProject() {
		return SquireRuntime.ExecutionResult.fail("feedback.project_none",
			"[Squire] 现在没有在做的工程。");
	}
}
