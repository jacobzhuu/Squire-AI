package dev.squire.server.runtime;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import dev.squire.server.blueprint.Blueprint;
import dev.squire.server.blueprint.BlueprintPlacement;
import dev.squire.server.blueprint.BuildTier;
import dev.squire.server.blueprint.ProjectBlueprintFactory;
import dev.squire.server.blueprint.ProjectSpec;
import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.profession.ProfessionAbility;
import dev.squire.server.profession.ProfessionConfig;
import dev.squire.server.profession.ProfessionData;
import dev.squire.server.profession.SquireProfession;
import dev.squire.server.profile.SquireProfile;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * 工程师的参数层：挑模板、调尺寸、换结构变体、加层、镜像、装模块、存取预设。
 *
 * <h2>等级在这里咬人</h2>
 * <p>设计文档 §13 给工程师的每一级都是<b>多一个可调参数</b>，而不是「LLM 变聪明」。
 * 所以这个类里几乎每一个方法开头都是同一件事：问一句「他到这一级了吗」，
 * 没到就<b>如实说还差什么、以及在那之前能做什么</b>——绝不留一句「不行」。</p>
 *
 * <h2>形状永远由代码生成</h2>
 * <p>玩家（以及替玩家说话的 LLM）在这里只能改参数。从参数到方块的那一步在
 * {@link ProjectBlueprintFactory} 里，是确定性的普通 Java。</p>
 */
public final class SquireEngineerService {

	private final SquireRuntime runtime;

	SquireEngineerService(SquireRuntime runtime) {
		this.runtime = runtime;
	}

	private ProfessionConfig config() {
		return runtime.professionConfig();
	}

	// ------------------------------------------------------------------ 查看

	public SquireRuntime.ExecutionResult status(ServerPlayerEntity sender) {
		Bound bound = bind(sender);
		if (bound.failure() != null) {
			return bound.failure();
		}
		ProfessionData data = bound.data();
		StringBuilder text = new StringBuilder("[Squire] 工程参数：");
		ProjectSpec spec = currentSpec(sender).orElse(null);
		if (spec == null) {
			text.append("现在没有参数化工地。");
			text.append("\n可以在设计页选择一个模板。");
		} else {
			text.append("\n模板 ").append(spec.template().displayName())
				.append("（").append(spec.template().id()).append("）")
				.append("\n尺寸 ").append(spec.width()).append("×").append(spec.depth())
				.append("，层高 ").append(spec.wallHeight())
				.append("，层数 ").append(spec.floors())
				.append("\n屋顶 ").append(spec.roof().displayName())
				.append(" · 地基 ").append(spec.foundation().displayName())
				.append(" · 窗 ").append(spec.window().displayName())
				.append(" · 入口 ").append(spec.entrance().displayName());
			text.append("\n模块 ");
			if (spec.orderedModules().isEmpty()) {
				text.append("（无）");
			} else {
				for (var module : spec.orderedModules()) {
					text.append(module.displayName()).append(" ");
				}
			}
			if (spec.mirrored()) {
				text.append("\n镜像 ").append(spec.mirrorX() ? "X " : "")
					.append(spec.mirrorZ() ? "Z" : "");
			}
		}
		text.append("\n\n").append(abilitySummary(data));
		return SquireRuntime.ExecutionResult.ok("feedback.design_status", text.toString());
	}

	/** 「你现在能调什么、还差什么」——每一条拒绝背后都要有这张表兜底。 */
	private String abilitySummary(ProfessionData data) {
		int level = data != null && data.profession() == SquireProfession.ENGINEER
			? data.level : 0;
		StringBuilder text = new StringBuilder();
		if (level == 0) {
			text.append("我还不是工程师。可以在职业页选择工程师。");
			return text.toString();
		}
		if (dev.squire.server.blueprint.BuildingContentPolicy.current().retired(ProjectSpec.ID_PREFIX)) {
			var growth = dev.squire.server.profession.EngineerProgression.current();
			long count = runtime.blueprints().registry().catalog().variants().stream().filter(v -> v.allowed(level)).count();
			return "工程师 Lv" + level + "：已开放 " + count + " 个建筑变体；放置间隔 " + growth.placementInterval(level)
				+ " tick，新工程" + growth.materialAdjustmentLabel(level) + "；Lv10 开放完整已验证目录与材料分区。旧参数化模板仅保留解析，不再创建。";
		}
		text.append("工程师 Lv").append(level).append(" 能调的：")
			.append("\n  尺寸上限 ").append(config().maxFootprint(level)).append("×")
			.append(config().maxFootprint(level));
		appendGate(text, data, ProfessionAbility.ENGINEER_TEMPLATE_LIBRARY_1, "更多模板");
		appendGate(text, data, ProfessionAbility.ENGINEER_BLUEPRINT_ROTATION, "旋转");
		appendGate(text, data, ProfessionAbility.ENGINEER_MATERIAL_REGIONS, "材料分区");
		appendGate(text, data, ProfessionAbility.ENGINEER_STRUCTURAL_VARIANTS,
			"结构变体（屋顶/地基/窗/入口）");
		appendGate(text, data, ProfessionAbility.ENGINEER_MULTI_FLOOR, "多层");
		appendGate(text, data, ProfessionAbility.ENGINEER_BLUEPRINT_MIRROR, "镜像");
		appendGate(text, data, ProfessionAbility.ENGINEER_OPTIONAL_MODULES,
			"附属模块（一次一个）");
		appendGate(text, data, ProfessionAbility.ENGINEER_MODULAR_BLUEPRINT,
			"模块自由组合");
		appendGate(text, data, ProfessionAbility.ENGINEER_COMPOUND_BLUEPRINT, "复合营地");
		appendGate(text, data, ProfessionAbility.ENGINEER_BLUEPRINT_PRESET_LIBRARY,
			"保存预设");
		return text.toString();
	}

	private void appendGate(StringBuilder text, ProfessionData data,
			ProfessionAbility ability, String label) {
		text.append("\n  ").append(data != null && data.can(ability) ? "✓ " : "✗ ")
			.append(label);
		if (data == null || !data.can(ability)) {
			text.append("（Lv").append(ability.unlockLevel()).append(" 解锁）");
		}
	}

	// ------------------------------------------------------------------ 开一份设计

	public SquireRuntime.ExecutionResult design(ServerPlayerEntity sender,
			String templateId) {
		Bound bound = bind(sender);
		if (bound.failure() != null) {
			return bound.failure();
		}
		ProjectSpec.Template template = ProjectSpec.Template.byId(templateId);
		if (template == null) {
			StringBuilder text = new StringBuilder("[Squire] 没有叫「" + templateId
				+ "」的模板。现在有：");
			for (ProjectSpec.Template candidate : ProjectSpec.Template.values()) {
				text.append("\n  · ").append(candidate.id()).append(" —— ")
					.append(candidate.displayName()).append("（Lv")
					.append(config().templateMinLevel(candidate.id())).append(" 起）");
			}
			return SquireRuntime.ExecutionResult.fail("feedback.design_template_unknown",
				text.toString());
		}
		SquireRuntime.ExecutionResult blocked = checkTemplate(bound.data(), template);
		if (blocked != null) {
			return blocked;
		}
		ProjectSpec spec = clampToLevel(bound.data(), template.defaults());
		// 已经有工地就<b>就地换形状</b>，而不是叫玩家先取消再来一次：
		// 「换个模板看看」是设计阶段最常做的事，不该每次都要走两条命令。
		return runtime.reshapeBlueprint(sender, spec.blueprintId());
	}

	// ------------------------------------------------------------------ 调参数

	public SquireRuntime.ExecutionResult size(ServerPlayerEntity sender, int width,
			int depth) {
		return adjust(sender, null, spec -> spec.withSize(width, depth),
			"尺寸改成 " + width + "×" + depth);
	}

	public SquireRuntime.ExecutionResult wallHeight(ServerPlayerEntity sender,
			int height) {
		return adjust(sender, null, spec -> spec.withWallHeight(height),
			"层高改成 " + height);
	}

	public SquireRuntime.ExecutionResult floors(ServerPlayerEntity sender, int floors) {
		return adjust(sender, ProfessionAbility.ENGINEER_MULTI_FLOOR,
			spec -> spec.withFloors(floors), "改成 " + floors + " 层");
	}

	public SquireRuntime.ExecutionResult roof(ServerPlayerEntity sender, String id) {
		ProjectSpec.Roof roof = ProjectSpec.Roof.byId(id);
		if (roof == null) {
			return unknownVariant("屋顶", id, ProjectSpec.Roof.values().length,
				List.of("flat", "gable", "hip"));
		}
		return adjust(sender, ProfessionAbility.ENGINEER_STRUCTURAL_VARIANTS,
			spec -> spec.withRoof(roof), "屋顶改成" + roof.displayName());
	}

	public SquireRuntime.ExecutionResult foundation(ServerPlayerEntity sender,
			String id) {
		ProjectSpec.Foundation foundation = ProjectSpec.Foundation.byId(id);
		if (foundation == null) {
			return unknownVariant("地基", id, 3, List.of("none", "stone", "raised"));
		}
		return adjust(sender, ProfessionAbility.ENGINEER_STRUCTURAL_VARIANTS,
			spec -> spec.withFoundation(foundation),
			"地基改成" + foundation.displayName());
	}

	public SquireRuntime.ExecutionResult window(ServerPlayerEntity sender, String id) {
		ProjectSpec.WindowStyle window = ProjectSpec.WindowStyle.byId(id);
		if (window == null) {
			return unknownVariant("窗户", id, 3, List.of("small", "wide", "tall"));
		}
		return adjust(sender, ProfessionAbility.ENGINEER_STRUCTURAL_VARIANTS,
			spec -> spec.withWindow(window), "窗户改成" + window.displayName());
	}

	public SquireRuntime.ExecutionResult entrance(ServerPlayerEntity sender, String id) {
		ProjectSpec.Entrance entrance = ProjectSpec.Entrance.byId(id);
		if (entrance == null) {
			return unknownVariant("入口", id, 2, List.of("center", "side"));
		}
		return adjust(sender, ProfessionAbility.ENGINEER_STRUCTURAL_VARIANTS,
			spec -> spec.withEntrance(entrance), "入口改成" + entrance.displayName());
	}

	public SquireRuntime.ExecutionResult module(ServerPlayerEntity sender, String id) {
		ProjectSpec.Module module = ProjectSpec.Module.byId(id);
		if (module == null) {
			StringBuilder text = new StringBuilder("[Squire] 没有叫「" + id
				+ "」的模块。现在有：");
			for (ProjectSpec.Module candidate : ProjectSpec.Module.values()) {
				text.append("\n  · ").append(candidate.id()).append(" —— ")
					.append(candidate.displayName());
			}
			return SquireRuntime.ExecutionResult.fail("feedback.design_module_unknown",
				text.toString());
		}
		Bound bound = bind(sender);
		if (bound.failure() != null) {
			return bound.failure();
		}
		// Lv.7 只能挂<b>一个</b>模块；把它们自由组合成一栋大建筑是 Lv.8「模块化蓝图」。
		// 这是两条能力真正的区别，不写在这里它就只是清单上的一行字。
		Optional<ProjectSpec> current = currentSpec(sender);
		boolean adding = current.isPresent() && !current.get().modules().contains(module);
		if (adding && current.get().modules().size() >= 1
				&& !bound.data().can(ProfessionAbility.ENGINEER_MODULAR_BLUEPRINT)) {
			return SquireRuntime.ExecutionResult.fail("feedback.design_locked",
				"[Squire] 我现在一次只能挂一个模块（已经挂着「"
					+ current.get().orderedModules().get(0).displayName() + "」）。"
					+ "\n把它们自由组合成一栋大建筑是 Lv"
					+ ProfessionAbility.ENGINEER_MODULAR_BLUEPRINT.unlockLevel()
					+ "「" + ProfessionAbility.ENGINEER_MODULAR_BLUEPRINT.displayName()
					+ "」。"
					+ "\n现在可以在设计页先摘掉已有模块，再挂新的。");
		}
		return adjust(sender, ProfessionAbility.ENGINEER_OPTIONAL_MODULES,
			spec -> spec.toggleModule(module), "切换模块：" + module.displayName());
	}

	public SquireRuntime.ExecutionResult mirror(ServerPlayerEntity sender, String axis) {
		String want = axis == null ? "" : axis.trim().toLowerCase(Locale.ROOT);
		boolean mirrorX = want.contains("x");
		boolean mirrorZ = want.contains("z");
		if (!mirrorX && !mirrorZ && !"none".equals(want) && !"-".equals(want)) {
			return SquireRuntime.ExecutionResult.fail("feedback.design_mirror_unknown",
				"[Squire] 镜像轴只能是 x、z、xz 或 none。");
		}
		return adjust(sender, ProfessionAbility.ENGINEER_BLUEPRINT_MIRROR,
			spec -> spec.withMirror(mirrorX, mirrorZ),
			mirrorX || mirrorZ ? "沿 " + want.toUpperCase(Locale.ROOT) + " 轴镜像"
				: "取消镜像");
	}

	// ------------------------------------------------------------------ 面板：循环

	/**
	 * 面板上的参数按钮走「点一下换下一档」。
	 *
	 * <p>按钮包只带得动一个 id，带不了值——这是原版 {@code ButtonClickC2SPacket} 的
	 * 形状。所以每一个参数在面板上都是一个循环按钮，而它们全部落到上面那几个
	 * 带等级闸的方法上：<b>面板和命令共用同一条校验路径</b>。</p>
	 */
	public SquireRuntime.ExecutionResult cycleTemplate(ServerPlayerEntity sender,
			int delta) {
		ProjectSpec current = currentSpec(sender).orElse(null);
		var values = ProjectSpec.Template.values();
		int start = current == null ? -1 : current.template().ordinal();
		ProfessionData data = dataOf(sender);
		// 只在<b>这一级能用的模板</b>之间循环。跳过锁着的，玩家点一圈都是可用的，
		// 而不是点四下撞四次「Lv.6 才解锁」。
		for (int step = 1; step <= values.length; step++) {
			var candidate = values[Math.floorMod(start + step * Integer.signum(
				delta == 0 ? 1 : delta), values.length)];
			if (checkTemplate(data, candidate) == null) {
				return design(sender, candidate.id());
			}
		}
		return SquireRuntime.ExecutionResult.fail("feedback.design_locked",
			"[Squire] 我这一级还没有别的模板可以换。");
	}

	public SquireRuntime.ExecutionResult cycleSize(ServerPlayerEntity sender, int delta) {
		ProjectSpec current = currentSpec(sender).orElse(null);
		if (current == null) {
			return noSite();
		}
		// 足印一律是奇数，所以一步走两格。
		int step = 2 * Integer.signum(delta);
		return size(sender, current.width() + step, current.depth() + step);
	}

	public SquireRuntime.ExecutionResult cycleFloors(ServerPlayerEntity sender,
			int delta) {
		ProjectSpec current = currentSpec(sender).orElse(null);
		if (current == null) {
			return noSite();
		}
		int next = Math.floorMod(current.floors() - 1 + Integer.signum(delta),
			ProjectSpec.MAX_FLOORS) + 1;
		return floors(sender, next);
	}

	public SquireRuntime.ExecutionResult cycleRoof(ServerPlayerEntity sender) {
		ProjectSpec current = currentSpec(sender).orElse(null);
		if (current == null) {
			return noSite();
		}
		var values = ProjectSpec.Roof.values();
		return roof(sender,
			values[(current.roof().ordinal() + 1) % values.length].id());
	}

	public SquireRuntime.ExecutionResult cycleFoundation(ServerPlayerEntity sender) {
		ProjectSpec current = currentSpec(sender).orElse(null);
		if (current == null) {
			return noSite();
		}
		var values = ProjectSpec.Foundation.values();
		return foundation(sender,
			values[(current.foundation().ordinal() + 1) % values.length].id());
	}

	public SquireRuntime.ExecutionResult cycleWindow(ServerPlayerEntity sender) {
		ProjectSpec current = currentSpec(sender).orElse(null);
		if (current == null) {
			return noSite();
		}
		var values = ProjectSpec.WindowStyle.values();
		return window(sender,
			values[(current.window().ordinal() + 1) % values.length].id());
	}

	public SquireRuntime.ExecutionResult cycleEntrance(ServerPlayerEntity sender) {
		ProjectSpec current = currentSpec(sender).orElse(null);
		if (current == null) {
			return noSite();
		}
		var values = ProjectSpec.Entrance.values();
		return entrance(sender,
			values[(current.entrance().ordinal() + 1) % values.length].id());
	}

	/** 镜像循环：无 → X → Z → XZ → 无。 */
	public SquireRuntime.ExecutionResult cycleMirror(ServerPlayerEntity sender) {
		ProjectSpec current = currentSpec(sender).orElse(null);
		if (current == null) {
			return noSite();
		}
		int state = (current.mirrorX() ? 1 : 0) | (current.mirrorZ() ? 2 : 0);
		return mirror(sender, switch ((state + 1) % 4) {
			case 1 -> "x";
			case 2 -> "z";
			case 3 -> "xz";
			default -> "none";
		});
	}

	/** 模块循环：依次把每一个模块装上/摘掉。 */
	public SquireRuntime.ExecutionResult cycleModule(ServerPlayerEntity sender,
			int index) {
		var values = ProjectSpec.Module.values();
		return module(sender, values[Math.floorMod(index, values.length)].id());
	}

	private SquireRuntime.ExecutionResult noSite() {
		return SquireRuntime.ExecutionResult.fail("feedback.design_none",
			"[Squire] 现在没有参数化工地。先在这一页挑一个模板。");
	}

	/**
	 * 面板上的「存预设」：自动取名，因为按钮包带不了字符串。
	 *
	 * <p>名字是「预设 1 / 2 / 3」，确保玩家不打字也存得下来。</p>
	 */
	public SquireRuntime.ExecutionResult savePresetAuto(ServerPlayerEntity sender) {
		Bound bound = bind(sender);
		if (bound.failure() != null) {
			return bound.failure();
		}
		int index = 1;
		while (bound.profile().blueprintPresets.containsKey("预设 " + index)
				&& index <= SquireProfile.MAX_BLUEPRINT_PRESETS) {
			index++;
		}
		return savePreset(sender, "预设 " + index);
	}

	/** 面板上的「读预设」：在存过的那几个之间循环。 */
	public SquireRuntime.ExecutionResult loadPresetNext(ServerPlayerEntity sender) {
		Bound bound = bind(sender);
		if (bound.failure() != null) {
			return bound.failure();
		}
		var names = new java.util.ArrayList<>(bound.profile().blueprintPresets.keySet());
		if (names.isEmpty()) {
			return SquireRuntime.ExecutionResult.fail("feedback.design_preset_none",
				"[Squire] 还没存过预设。调好一份工地之后点「存预设」。");
		}
		String currentId = runtime.blueprints().activeOf(sender.getUuid())
			.map(placement -> placement.blueprintId).orElse("");
		int at = -1;
		for (int i = 0; i < names.size(); i++) {
			if (currentId.equals(bound.profile().blueprintPresets.get(names.get(i)))) {
				at = i;
			}
		}
		return loadPreset(sender, names.get(Math.floorMod(at + 1, names.size())));
	}

	// ------------------------------------------------------------------ 预设

	public SquireRuntime.ExecutionResult savePreset(ServerPlayerEntity sender,
			String name) {
		Bound bound = bind(sender);
		if (bound.failure() != null) {
			return bound.failure();
		}
		SquireRuntime.ExecutionResult blocked = checkAbility(bound.data(),
			ProfessionAbility.ENGINEER_BLUEPRINT_PRESET_LIBRARY);
		if (blocked != null) {
			return blocked;
		}
		Optional<BlueprintPlacement> active = runtime.blueprints()
			.activeOf(sender.getUuid());
		if (active.isEmpty()) {
			return SquireRuntime.ExecutionResult.fail("feedback.design_none",
				"[Squire] 现在没有工地可以存。请先在设计页摆一份模板。");
		}
		SquireProfile profile = bound.profile();
		String key = name == null ? "" : name.trim();
		if (key.isEmpty()) {
			return SquireRuntime.ExecutionResult.fail("feedback.design_preset_name",
				"[Squire] 预设要有个名字，比如「生存屋」。");
		}
		if (profile.blueprintPresets.size() >= SquireProfile.MAX_BLUEPRINT_PRESETS
				&& !profile.blueprintPresets.containsKey(key)) {
			return SquireRuntime.ExecutionResult.fail("feedback.design_preset_full",
				"[Squire] 预设最多 " + SquireProfile.MAX_BLUEPRINT_PRESETS
					+ " 个了。请先在设计页删掉一个。");
		}
		profile.blueprintPresets.put(key, active.get().blueprintId);
		runtime.persistSnapshot(bound.avatar());
		return SquireRuntime.ExecutionResult.ok("feedback.design_preset_saved",
			"[Squire] 存好了：「" + key + "」。以后可从设计页再开一份相同预览。");
	}

	public SquireRuntime.ExecutionResult loadPreset(ServerPlayerEntity sender,
			String name) {
		Bound bound = bind(sender);
		if (bound.failure() != null) {
			return bound.failure();
		}
		SquireRuntime.ExecutionResult blocked = checkAbility(bound.data(),
			ProfessionAbility.ENGINEER_BLUEPRINT_PRESET_LIBRARY);
		if (blocked != null) {
			return blocked;
		}
		String key = name == null ? "" : name.trim();
		String blueprintId = bound.profile().blueprintPresets.get(key);
		if (blueprintId == null) {
			return SquireRuntime.ExecutionResult.fail("feedback.design_preset_unknown",
				"[Squire] 没有叫「" + key + "」的预设。可在设计页查看已存预设。");
		}
		// 存的时候够格，不代表现在还够格（可能改过行）。重新过一遍等级闸。
		ProjectSpec spec = ProjectSpec.parse(blueprintId).orElse(null);
		if (spec != null) {
			SquireRuntime.ExecutionResult templateBlocked =
				checkTemplate(bound.data(), spec.template());
			if (templateBlocked != null) {
				return templateBlocked;
			}
			blueprintId = clampToLevel(bound.data(), spec).blueprintId();
		}
		// 已经有工地就<b>就地换形状</b>，而不是叫玩家先取消再来一次：
		// 「换个模板看看」是设计阶段最常做的事，不该每次都要走两条命令。
		return runtime.reshapeBlueprint(sender, blueprintId);
	}

	public SquireRuntime.ExecutionResult listPresets(ServerPlayerEntity sender) {
		Bound bound = bind(sender);
		if (bound.failure() != null) {
			return bound.failure();
		}
		SquireProfile profile = bound.profile();
		if (profile.blueprintPresets.isEmpty()) {
			return SquireRuntime.ExecutionResult.ok("feedback.design_preset_none",
				"[Squire] 还没存过预设。调好一份工地后，可在设计页保存。");
		}
		StringBuilder text = new StringBuilder("[Squire] 存过的蓝图预设：");
		profile.blueprintPresets.forEach((key, id) -> {
			text.append("\n  · ").append(key).append(" —— ");
			ProjectSpec spec = ProjectSpec.parse(id).orElse(null);
			text.append(spec == null ? id : spec.displayName());
		});
		text.append("\n可在设计页选择并载入。");
		return SquireRuntime.ExecutionResult.ok("feedback.design_preset_list",
			text.toString());
	}

	public SquireRuntime.ExecutionResult deletePreset(ServerPlayerEntity sender,
			String name) {
		Bound bound = bind(sender);
		if (bound.failure() != null) {
			return bound.failure();
		}
		String key = name == null ? "" : name.trim();
		if (bound.profile().blueprintPresets.remove(key) == null) {
			return SquireRuntime.ExecutionResult.fail("feedback.design_preset_unknown",
				"[Squire] 没有叫「" + key + "」的预设。");
		}
		runtime.persistSnapshot(bound.avatar());
		return SquireRuntime.ExecutionResult.ok("feedback.design_preset_deleted",
			"[Squire] 删掉预设「" + key + "」了。");
	}

	// ------------------------------------------------------------------ 等级闸

	/**
	 * 资源蓝图等级与参数化设计权限——这条线只在这里判一次。
	 *
	 * <p>{@link dev.squire.server.runtime.SquireBlueprintService#place} 是所有摆放路径
	 * 的唯一漏斗：面板的蓝图库按钮、聊天里的「盖个仓库」、{@code /squire} 命令、
	 * 模型的 {@code blueprint.place} / {@code project.start} 全部经过它。判断放在这里，
	 * 四条路就<b>不可能</b>再给出不同的答案——在此之前面板不判、ToolGate 整条判给
	 * 工程师，于是同一个守卫「按钮能盖、说话被拒」。</p>
	 *
	 * <p>每份资源蓝图先读取自己的 {@code minEngineerLevel}；0 只作为旧数据包的兼容
	 * 值。参数化蓝图仍要求工程师，而且规格不能超出等级：夹过之后不等于原样，
	 * 就是他还调不动这一份。这样 UI 锁、聊天、命令和实际开工读的是同一条规则。</p>
	 *
	 * @return {@code null} 表示放行
	 */
	SquireRuntime.ExecutionResult checkBuildTier(ServerPlayerEntity sender, Blueprint blueprint) {
		if (blueprint == null) return null;
		var decision = EngineerBuildPolicy.evaluate(blueprint, dataOf(sender), config());
		return decision.allowed() ? null : SquireRuntime.ExecutionResult.fail(
			"feedback.design_locked", "[Squire] " + blueprint.displayName() + "：" + decision.reason());
	}
	/** 旋转（Lv.3）。蓝图服务在真的转之前问这一句。 */
	SquireRuntime.ExecutionResult checkRotation(ServerPlayerEntity sender) {
		return checkAbility(dataOf(sender), ProfessionAbility.ENGINEER_BLUEPRINT_ROTATION);
	}

	/** 材料分区（Lv.4）。 */
	SquireRuntime.ExecutionResult checkMaterialRegions(ServerPlayerEntity sender) {
		return checkAbility(dataOf(sender), ProfessionAbility.ENGINEER_MATERIAL_REGIONS);
	}

	/**
	 * 摆放这份蓝图的足印上限（Lv.1 起逐级放宽）。
	 *
	 * <p>没有工程师职业的随从<b>不受限制</b>——尺寸上限是工程师职业的成长曲线，
	 * 不是给所有人的新限制。</p>
	 */
	SquireRuntime.ExecutionResult checkFootprint(ServerPlayerEntity sender,
			Blueprint blueprint) {
		if (blueprint != null && (blueprint.metadata().tags().contains("catalog")
				|| blueprint.metadata().source().startsWith("https://github.com/gowenrw/keepitlevel_mc_style/")))
			return checkBuildTier(sender, blueprint);
		ProfessionData data = dataOf(sender);
		if (data == null || data.profession() != SquireProfession.ENGINEER
				|| blueprint == null) {
			return null;
		}
		int span = Math.max(blueprint.width(), blueprint.depth());
		int max = config().maxFootprint(data.level);
		if (span <= max) {
			return null;
		}
		int neededLevel = SquireProfession.MAX_LEVEL;
		for (int level = data.level; level <= SquireProfession.MAX_LEVEL; level++) {
			if (config().maxFootprint(level) >= span) {
				neededLevel = level;
				break;
			}
		}
		return SquireRuntime.ExecutionResult.fail("feedback.design_too_large",
			"[Squire] 这份蓝图 " + span + " 格宽，我现在最多能控制 " + max
				+ " 格（工程师 Lv" + data.level + "）。"
				+ "\n要盖到 " + span + " 格，得练到 Lv" + neededLevel + "。"
				+ "\n现在可以在设计页把尺寸调到 " + max + "×" + max + " 以内。");
	}

	private SquireRuntime.ExecutionResult checkTemplate(ProfessionData data,
			ProjectSpec.Template template) {
		int min = config().templateMinLevel(template.id());
		int level = data != null && data.profession() == SquireProfession.ENGINEER
			? data.level : 0;
		if (level == 0) {
			return SquireRuntime.ExecutionResult.fail("feedback.design_not_engineer",
				"[Squire] 我还不是工程师，只能使用工程页里的现成蓝图。"
					+ "\n想调整参数，可以在职业页让我成为工程师。");
		}
		if (template.compound()
				&& !data.can(ProfessionAbility.ENGINEER_COMPOUND_BLUEPRINT)) {
			return SquireRuntime.ExecutionResult.fail("feedback.design_locked",
				"[Squire] 复合营地要工程师 Lv"
					+ ProfessionAbility.ENGINEER_COMPOUND_BLUEPRINT.unlockLevel()
					+ "，我现在 Lv" + level + "。"
					+ "\n现在可以在设计页一栋一栋地盖。");
		}
		if (level < min) {
			return SquireRuntime.ExecutionResult.fail("feedback.design_locked",
				"[Squire] 「" + template.displayName() + "」要工程师 Lv" + min
					+ "，我现在 Lv" + level + "。"
					+ "\n现在能用的模板：" + availableTemplates(level));
		}
		return null;
	}

	private String availableTemplates(int level) {
		StringBuilder text = new StringBuilder();
		for (ProjectSpec.Template template : ProjectSpec.Template.values()) {
			if (config().templateMinLevel(template.id()) <= level) {
				if (text.length() > 0) {
					text.append("、");
				}
				text.append(template.id());
			}
		}
		return text.length() == 0 ? "（暂无）" : text.toString();
	}

	private SquireRuntime.ExecutionResult checkAbility(ProfessionData data,
			ProfessionAbility ability) {
		if (data != null && data.can(ability)) {
			return null;
		}
		int level = data != null && data.profession() == SquireProfession.ENGINEER
			? data.level : 0;
		return SquireRuntime.ExecutionResult.fail("feedback.design_locked",
			"[Squire] 「" + ability.displayName() + "」要工程师 Lv"
				+ ability.unlockLevel() + "，"
				+ (level == 0 ? "我还不是工程师。\n可以在职业页选择工程师。"
					: "我现在 Lv" + level + "。\n设计页会显示目前能调的项目。"));
	}

	private ProjectSpec clampToLevel(ProfessionData data, ProjectSpec spec) {
		return clampToLevel(config(), data, spec);
	}

	/**
	 * 把规格夹进当前等级允许的范围内。玩家调过头时<b>夹住</b>，而不是整条拒绝——
	 * 一次多点的点击不该把整个工地作废。
	 *
	 * <p>静态且只依赖配置和档案，所以「Lv.N 的工程师到底能盖出什么」这个问题
	 * 有一段可以被普通单测逐级验的答案。</p>
	 */
	static ProjectSpec clampToLevel(ProfessionConfig config, ProfessionData data,
			ProjectSpec spec) {
		if (data == null || data.profession() != SquireProfession.ENGINEER) {
			return spec;
		}
		int max = config.maxFootprint(data.level);
		ProjectSpec clamped = spec.withSize(Math.min(spec.width(), max),
			Math.min(spec.depth(), max));
		if (!data.can(ProfessionAbility.ENGINEER_MULTI_FLOOR)) {
			clamped = clamped.withFloors(1);
		}
		if (!data.can(ProfessionAbility.ENGINEER_STRUCTURAL_VARIANTS)) {
			clamped = clamped.withRoof(ProjectSpec.Roof.GABLE)
				.withFoundation(ProjectSpec.Foundation.NONE)
				.withWindow(ProjectSpec.WindowStyle.SMALL)
				.withEntrance(ProjectSpec.Entrance.CENTER);
		}
		if (!data.can(ProfessionAbility.ENGINEER_BLUEPRINT_MIRROR)) {
			clamped = clamped.withMirror(false, false);
		}
		if (!data.can(ProfessionAbility.ENGINEER_OPTIONAL_MODULES)) {
			for (var module : clamped.orderedModules()) {
				clamped = clamped.toggleModule(module);
			}
		} else if (!data.can(ProfessionAbility.ENGINEER_MODULAR_BLUEPRINT)) {
			// Lv.7 只挂得住一个；多出来的摘掉，而不是整个规格作废。
			var kept = clamped.orderedModules();
			for (int i = 1; i < kept.size(); i++) {
				clamped = clamped.toggleModule(kept.get(i));
			}
		}
		return clamped;
	}

	// ------------------------------------------------------------------ helpers

	/** 一次参数调整：过等级闸 → 改规格 → 换掉幽灵的蓝图 id。 */
	private SquireRuntime.ExecutionResult adjust(ServerPlayerEntity sender,
			ProfessionAbility ability,
			java.util.function.UnaryOperator<ProjectSpec> change, String what) {
		Bound bound = bind(sender);
		if (bound.failure() != null) {
			return bound.failure();
		}
		if (ability != null) {
			SquireRuntime.ExecutionResult blocked = checkAbility(bound.data(), ability);
			if (blocked != null) {
				return blocked;
			}
		}
		Optional<BlueprintPlacement> active = runtime.blueprints()
			.activeOf(sender.getUuid());
		if (active.isEmpty()) {
			return SquireRuntime.ExecutionResult.fail("feedback.design_none",
				"[Squire] 现在没有工地可以调。请先在设计页摆一份模板。");
		}
		ProjectSpec current = ProjectSpec.parse(active.get().blueprintId).orElse(null);
		if (current == null) {
			return SquireRuntime.ExecutionResult.fail("feedback.design_not_parametric",
				"[Squire] 当前工地是一份固定蓝图（" + active.get().blueprintId
					+ "），参数改不动。想调参数，可在设计页换成参数化模板。");
		}
		ProjectSpec next = clampToLevel(bound.data(), change.apply(current));
		if (next.equals(current)) {
			return SquireRuntime.ExecutionResult.ok("feedback.design_unchanged",
				"[Squire] " + what + "——不过和现在一样，没有变化。");
		}
		SquireRuntime.ExecutionResult sizeBlocked = checkFootprint(sender,
			ProjectBlueprintFactory.compile(next));
		if (sizeBlocked != null) {
			return sizeBlocked;
		}
		SquireRuntime.ExecutionResult result =
			runtime.reshapeBlueprint(sender, next.blueprintId());
		return result.success()
			? SquireRuntime.ExecutionResult.ok("feedback.design_changed",
				"[Squire] " + what + "了。现在是：" + next.displayName()
					+ "\n" + result.message())
			: result;
	}

	private SquireRuntime.ExecutionResult unknownVariant(String label, String raw,
			int count, List<String> ids) {
		return SquireRuntime.ExecutionResult.fail("feedback.design_variant_unknown",
			"[Squire] 没有叫「" + raw + "」的" + label + "样式。有 " + count + " 种："
				+ String.join("、", ids));
	}

	private Optional<ProjectSpec> currentSpec(ServerPlayerEntity sender) {
		return runtime.blueprints().activeOf(sender.getUuid())
			.flatMap(placement -> ProjectSpec.parse(placement.blueprintId));
	}

	private ProfessionData dataOf(ServerPlayerEntity sender) {
		return runtime.agents().resolveForOwner(sender.getUuid())
			.map(runtime::professionOf).orElse(null);
	}

	private record Bound(AvatarEntity avatar, SquireProfile profile, ProfessionData data,
			SquireRuntime.ExecutionResult failure) { }

	private Bound bind(ServerPlayerEntity sender) {
		Optional<AvatarEntity> found = runtime.agents().resolveForOwner(sender.getUuid());
		if (found.isEmpty()) {
			return new Bound(null, null, null, SquireRuntime.ExecutionResult.fail(
				"feedback.no_agent", "[Squire] 侍从不在场。请右键召集铃；首次召唤方法可按 K 查看。"));
		}
		AvatarEntity avatar = found.get();
		SquireProfile profile = runtime.profileOf(avatar);
		if (profile == null) {
			return new Bound(null, null, null, SquireRuntime.ExecutionResult.fail(
				"feedback.no_profile",
				"[Squire] 读不到他的档案（存档还没就绪？）。稍后再试。"));
		}
		return new Bound(avatar, profile, profile.profession, null);
	}
}
