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
		if (blueprint == null || !(sender.getWorld()
				instanceof net.minecraft.server.world.ServerWorld world)) {
			return SquireRuntime.ExecutionResult.fail("feedback.project_failed",
				"[Squire] 工地蓝图已经不可用，请撤掉后重选。");
		}
		if (!world.getRegistryKey().getValue().toString().equals(placement.dimensionId)) {
			return SquireRuntime.ExecutionResult.fail("feedback.project_dimension",
				"[Squire] 工地在 " + placement.dimensionId + "，你需要回到那个维度再确认。");
		}
		if (!runtime.permissions().has(sender, PermissionNodes.WORLD_PLACE)) {
			return SquireRuntime.ExecutionResult.fail("feedback.permission_denied",
				"[Squire] 你没有让伙伴放置方块的权限。");
		}
		var resolved = runtime.blueprints().resolve(placement).orElse(null);
		if (resolved == null) {
			return SquireRuntime.ExecutionResult.fail("feedback.project_failed",
				"[Squire] 工地材料配置已经不可用，请撤掉后重选。");
		}
		var toClear = BlueprintManager.pendingClear(world, resolved);
		if (!toClear.isEmpty()
				&& !runtime.permissions().has(sender, PermissionNodes.WORLD_BREAK)) {
			return SquireRuntime.ExecutionResult.fail("feedback.permission_denied",
				"[Squire] 这份蓝图要先清理 " + toClear.size()
					+ " 个方块，但你没有让伙伴破坏方块的权限。");
		}
		var decision = runtime.protectionAdapter.canEditRegion(world, resolved.bounds(),
			sender.getUuid());
		if (!decision.allowed()) {
			return SquireRuntime.ExecutionResult.fail("feedback.protected",
				"[Squire] 这块地不允许施工：" + decision.reason());
		}
		var avatar = runtime.agents().resolveForOwner(sender.getUuid()).orElse(null);
		if (avatar == null) {
			return SquireRuntime.ExecutionResult.fail("feedback.no_agent",
				"[Squire] 还没有可用的侍从。");
		}
		SiteAssessment assessment = SiteAssessment.assess(world, resolved,
			java.util.Set.of(sender.getUuid(), avatar.getUuid()));
		if (!assessment.executable()) {
			return SquireRuntime.ExecutionResult.fail("feedback.site_unsafe",
				"[Squire] 这个位置暂时不能开工：\n  · "
					+ String.join("\n  · ", assessment.issues()));
		}
		Map<Identifier, Integer> required = BlueprintManager
			.requiredProjectMaterials(world, resolved);
		Reservation reservation = planReservation(sender, avatar, required);
		if (!reservation.missing().isEmpty()) {
			return missingMaterials(reservation.missing(),
				"材料不会先被扣除；补齐后再点确认开工。");
		}
		Project project = new Project(UUID.randomUUID(), sender.getUuid(),
			blueprint.displayName(), blueprint.id(), placement.placementId,
			placement.dimensionId, runtime.currentTick(),
			ProjectCoordinator.compileStages());
		if (!commitReservation(sender, avatar, reservation)) {
			return SquireRuntime.ExecutionResult.fail("feedback.project_reserve_changed",
				"[Squire] 预留材料时库存发生了变化，没有开工也没有丢失物品，请再试一次。");
		}
		project.reserve(reservation.fromOwner(), reservation.fromAgent());
		projects().put(project);
		placement.setState(BlueprintPlacement.State.BUILDING);
		runtime.blueprints().save();
		// 开工是一条新命令：它优先于之前的「待命」。
		String stayNote = runtime.agents().resolveForOwner(sender.getUuid())
			.map(agent -> runtime.beginOrderedWork(agent, placement.origin))
			.orElse("");
		return SquireRuntime.ExecutionResult.ok("feedback.project_started",
			"[Squire] 好，「" + project.name + "」这个工程我接了。"
				+ "\n分成 " + project.stages().size() + " 步：备料 → 交料 → 掘进 → 施工"
				+ " → 点灯 → 验收。"
				+ "\n已从你和侍从的真实库存预留 " + reservation.total()
				+ " 件工程物资，施工不会再被其他任务挪用。"
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
		for (Blueprint blueprint : runtime.blueprints().registry().all()) {
			if (text.contains(blueprint.id())
					|| phrase != null && phrase.contains(blueprint.displayName())) {
				matched = blueprint.id();
				break;
			}
		}
		if (matched == null) {
			matched = byKeyword(phrase == null ? "" : phrase);
		}
		if (matched == null) {
			StringBuilder options = new StringBuilder(
				"[Squire] 我没听出要盖哪一种。能做的有：");
			for (Blueprint blueprint : runtime.blueprints().registry().all()) {
				options.append("\n· ").append(blueprint.displayName())
					.append("（").append(blueprint.id()).append("）");
			}
			options.append("\n可以说一句「帮我准备一个矿井前哨站」，"
				+ "也可以在工程页选择模板。");
			return SquireRuntime.ExecutionResult.fail("feedback.project_unknown",
				options.toString());
		}
		return start(sender, matched);
	}

	/** 玩家不会照着 id 说话。这张小表只覆盖内置蓝图的常见叫法。 */
	private static String byKeyword(String phrase) {
		if (phrase.contains("前哨站") || phrase.contains("矿井")) {
			return "mine_outpost";
		}
		if (phrase.contains("哨塔") || phrase.contains("瞭望塔")) {
			return "watchtower";
		}
		if (phrase.contains("仓库")) {
			return "storage_shed";
		}
		if (phrase.contains("石") && phrase.contains("屋")) {
			return "shelter_stone";
		}
		return null;
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
				text.append(" —— ").append(stage.blockedReason());
			}
		}
		text.append(project.state() == Project.State.RUNNING
			? "\n可在工程页暂停。"
			: "\n可在工程页继续。");
		text.append("不想做了也可在工程页取消。");
		return SquireRuntime.ExecutionResult.ok("feedback.project_status",
			text.toString());
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
		var placement = runtime.blueprints().placement(project.placementId).orElse(null);
		var resolved = placement == null ? null : runtime.blueprints().resolve(placement)
			.orElse(null);
		var avatar = runtime.agents().resolveForOwner(sender.getUuid()).orElse(null);
		if (resolved == null || avatar == null || !(avatar.getWorld() instanceof ServerWorld world)) {
			return SquireRuntime.ExecutionResult.fail("feedback.project_failed",
				"[Squire] 工程蓝图或侍从当前不可用，进度仍然保留。");
		}
		boolean needsSupplyCheck = !project.supplyPrepared()
			|| project.currentStage().map(stage -> stage.blockerCode()
				== dev.squire.server.project.Stage.BlockerCode.MATERIALS_MISSING)
				.orElse(false);
		boolean lightingCheckpoint = project.currentStage()
			.map(stage -> stage.kind == dev.squire.server.project.Stage.Kind.LIGHT
				|| stage.kind == dev.squire.server.project.Stage.Kind.VERIFY)
			.orElse(false);
		Map<Identifier, Integer> required = lightingCheckpoint
			? BlueprintManager.remainingProjectMaterials(world, resolved)
			: BlueprintManager.requiredProjectMaterials(world, resolved);
		Map<Identifier, Integer> missing = needsSupplyCheck
			? project.missingFrom(required) : Map.of();
		if (needsSupplyCheck && (!missing.isEmpty() || !project.supplyPrepared())) {
			Reservation reservation = planReservation(sender, avatar, missing);
			if (!reservation.missing().isEmpty()) {
				return missingMaterials(reservation.missing(),
					"补齐后点击“补料并重试”，工程会从当前断点继续。");
			}
			if (!commitReservation(sender, avatar, reservation)) {
				return SquireRuntime.ExecutionResult.fail("feedback.project_reserve_changed",
					"[Squire] 补料时库存发生了变化，工程仍保持原进度，请再试一次。");
			}
			project.reserve(reservation.fromOwner(), reservation.fromAgent());
		}
		project.setSupplyPrepared(true);
		projects().resume(project);
		String resumeNote = runtime.blueprints().placement(project.placementId)
			.flatMap(site -> runtime.agents().resolveForOwner(sender.getUuid())
				.map(agent -> runtime.beginOrderedWork(agent, site.origin)))
			.orElse("");
		return SquireRuntime.ExecutionResult.ok("feedback.project_resumed",
			"[Squire] 「" + project.name + "」从当前断点接着做。" + resumeNote);
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

	private static boolean commitReservation(ServerPlayerEntity owner,
			dev.squire.server.body.avatar.AvatarEntity avatar, Reservation reservation) {
		if (!reservation.missing().isEmpty()) return false;
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
		for (var entry : missing.entrySet()) {
			text.append("\n  · ").append(runtime.itemAliases().displayName(
				entry.getKey().toString())).append(" ×").append(entry.getValue());
		}
		text.append("\n").append(nextStep);
		return SquireRuntime.ExecutionResult.fail("feedback.project_materials_missing",
			text.toString());
	}

	public SquireRuntime.ExecutionResult cancel(ServerPlayerEntity sender) {
		Optional<Project> found = projects().activeOf(sender.getUuid());
		if (found.isEmpty()) {
			return noProject();
		}
		Project project = found.get();
		projects().cancel(project);
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
