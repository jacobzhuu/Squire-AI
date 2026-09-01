package dev.squire.server.command;

import java.util.UUID;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import dev.squire.server.runtime.SquireRuntime;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * The {@code /squire} command tree (spec section 70). Commands and FastPath chat are
 * two front doors into the SAME runtime pipeline — commands never bypass role checks.
 */
public final class SquireCommands {
	private SquireCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
			CommandManager.literal("squire")
				.then(CommandManager.literal("summon").executes(SquireCommands::executeSummon))
				.then(CommandManager.literal("dismiss").executes(ctx -> control(ctx, SquireRuntime.ControlIntent.DISMISS)))
				.then(CommandManager.literal("follow").executes(ctx -> control(ctx, SquireRuntime.ControlIntent.FOLLOW)))
				.then(CommandManager.literal("stay").executes(ctx -> control(ctx, SquireRuntime.ControlIntent.STAY)))
				.then(CommandManager.literal("stop").executes(ctx -> control(ctx, SquireRuntime.ControlIntent.STOP)))
				.then(CommandManager.literal("home")
					.then(CommandManager.literal("set").executes(SquireCommands::executeHomeSet))
					.then(CommandManager.literal("return").executes(ctx -> control(ctx, SquireRuntime.ControlIntent.HOME_RETURN))))
				.then(CommandManager.literal("status").executes(ctx -> control(ctx, SquireRuntime.ControlIntent.STATUS)))
				.then(conversationBranch())
				// 方案 A3：注视与表情的可玩入口（FastPath 同语义）
				.then(CommandManager.literal("look")
					.then(CommandManager.literal("me").executes(SquireCommands::executeLookMe))
					.then(CommandManager.argument("pos",
							net.minecraft.command.argument.Vec3ArgumentType.vec3())
						.executes(SquireCommands::executeLookAt)))
				.then(CommandManager.literal("action")
					.then(CommandManager.literal("wave")
						.executes(ctx -> action(ctx, dev.squire.api.body.EmoteType.WAVE)))
					.then(CommandManager.literal("jump")
						.executes(ctx -> action(ctx, dev.squire.api.body.EmoteType.JUMP)))
					.then(CommandManager.literal("nod")
						.executes(ctx -> action(ctx, dev.squire.api.body.EmoteType.NOD)))
					.then(CommandManager.literal("shake_head")
						.executes(ctx -> action(ctx,
							dev.squire.api.body.EmoteType.SHAKE_HEAD))))
				.then(CommandManager.literal("confirm")
					.then(CommandManager.argument("confirmId", StringArgumentType.string())
						.executes(SquireCommands::executeConfirm)))
				.then(CommandManager.literal("deny")
					.then(CommandManager.argument("confirmId", StringArgumentType.string())
						.executes(SquireCommands::executeDeny)))
				.then(selectionBranch())
				.then(shortcutBranch())
				.then(followBranch())
				.then(undoBranch())
				.then(blueprintBranch())
				.then(profileBranch())
				.then(roleBranch())
				.then(abilityBranch())
				.then(autonomyBranch())
				.then(professionBranch())
				.then(stanceBranch())
				.then(suppliesBranch())
				.then(designBranch())
				.then(patrolBranch())
				.then(projectBranch())
				.then(CommandManager.literal("say")
					.then(CommandManager.argument("text", StringArgumentType.greedyString())
						.executes(SquireCommands::executeSay)))
				.then(automationBranch())
				.then(workspaceBranch())
				.then(cbpBranch())
				.then(CommandManager.literal("admin")
					.requires(source -> source.hasPermissionLevel(2))
					.then(CommandManager.literal("killswitch")
						.then(CommandManager.literal("on").executes(ctx -> killswitch(ctx, true)))
						.then(CommandManager.literal("off").executes(ctx -> killswitch(ctx, false)))
						.executes(SquireCommands::killswitchStatus))
					.then(CommandManager.literal("worldedit")
						.then(CommandManager.literal("enable").executes(ctx -> worldEdit(ctx, true)))
						.then(CommandManager.literal("disable")
							.executes(ctx -> worldEdit(ctx, false))))
					.then(CommandManager.literal("commands")
						.then(CommandManager.literal("enable").executes(ctx -> adminCommands(ctx, true)))
						.then(CommandManager.literal("disable")
							.executes(ctx -> adminCommands(ctx, false))))
					.then(CommandManager.literal("tools")
						.executes(SquireCommands::toolInspector))
					.then(CommandManager.literal("metrics")
						.executes(SquireCommands::metricsInspector))
					// 每拍分段耗时：回答「到底是不是 Squire 在卡」，以及卡在哪一段
					.then(CommandManager.literal("perf")
						.executes(SquireCommands::perfInspector)
						.then(CommandManager.literal("reset")
							.executes(SquireCommands::perfReset)))
					.then(CommandManager.literal("llm")
						.executes(SquireCommands::llmStatus)
						.then(CommandManager.literal("reload")
							.executes(SquireCommands::llmReload)))
					.then(CommandManager.literal("alias")
						.then(CommandManager.argument("text", StringArgumentType.greedyString())
							.executes(SquireCommands::aliasProbe)))
					.then(CommandManager.literal("diagnose")
						.executes(SquireCommands::diagnose))
					.then(CommandManager.literal("instantacquire")
						.then(CommandManager.literal("enable")
							.executes(ctx -> instantAcquire(ctx, true)))
						.then(CommandManager.literal("disable")
							.executes(ctx -> instantAcquire(ctx, false))))
					.then(CommandManager.literal("mcp")
						.requires(source -> source.hasPermissionLevel(2))
						.executes(SquireCommands::mcpList)
						.then(CommandManager.literal("list")
							.executes(SquireCommands::mcpList))
						.then(CommandManager.literal("reload")
							.executes(SquireCommands::mcpReload))
						.then(CommandManager.literal("status")
							.then(CommandManager.argument("server",
									StringArgumentType.string())
								.executes(SquireCommands::mcpStatus))))
					.then(CommandManager.literal("replay")
						.then(CommandManager.literal("on").executes(ctx -> replayToggle(ctx, true)))
						.then(CommandManager.literal("off")
							.executes(ctx -> replayToggle(ctx, false))))
					.then(CommandManager.literal("automation")
						.then(CommandManager.literal("enable")
							.executes(ctx -> automationToggle(ctx, true)))
						.then(CommandManager.literal("disable")
							.executes(ctx -> automationToggle(ctx, false))))
					.then(CommandManager.literal("cbp")
						.then(CommandManager.literal("enable")
							.executes(ctx -> cbpToggle(ctx, true)))
						.then(CommandManager.literal("disable")
							.executes(ctx -> cbpToggle(ctx, false)))))));
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource>
			conversationBranch() {
		return CommandManager.literal("conversation")
			.executes(SquireCommands::conversationStatus)
			.then(CommandManager.literal("status")
				.executes(SquireCommands::conversationStatus))
			.then(CommandManager.literal("cancel")
				.executes(SquireCommands::conversationCancel))
			.then(CommandManager.literal("forget")
				.executes(SquireCommands::conversationForget));
	}

	private static int conversationStatus(CommandContext<ServerCommandSource> context) {
		return withPlayer(context, (source, player) -> {
			SquireRuntime runtime = SquireRuntime.get();
			UUID agentId = runtime.agents().resolveForOwner(player.getUuid())
				.map(dev.squire.server.body.avatar.AvatarEntity::agentId).orElse(null);
			String message = runtime.conversations().statusFor(player.getUuid(), agentId)
				.map(view -> {
					StringBuilder out = new StringBuilder("[Squire] 对话状态：")
						.append(view.state()).append("\n目标：").append(view.goal());
					if (!view.plan().isEmpty()) out.append("\n计划：")
						.append(String.join(" → ", view.plan()));
					if (view.pendingQuestion() != null && !view.pendingQuestion().isBlank())
						out.append("\n待回答：").append(view.pendingQuestion());
					if (view.lastError() != null && !view.lastError().isBlank())
						out.append("\n最近错误：").append(view.lastError());
					return out.toString();
				}).orElse("[Squire] 当前没有对话任务。");
			return new SquireRuntime.ExecutionResult(true,
				"feedback.conversation_status", null, message);
		});
	}

	private static int conversationCancel(CommandContext<ServerCommandSource> context) {
		return withPlayer(context, (source, player) -> {
			SquireRuntime runtime = SquireRuntime.get();
			UUID agentId = runtime.agents().resolveForOwner(player.getUuid())
				.map(dev.squire.server.body.avatar.AvatarEntity::agentId).orElse(null);
			int cancelled = runtime.conversations().cancelFor(player.getUuid(), agentId);
			return new SquireRuntime.ExecutionResult(true, "feedback.conversation_cancel",
				null, cancelled == 0 ? "[Squire] 没有正在进行的对话任务。"
					: "[Squire] 已取消 " + cancelled + " 个对话任务及其未完成步骤。");
		});
	}

	private static int conversationForget(CommandContext<ServerCommandSource> context) {
		return withPlayer(context, (source, player) -> {
			SquireRuntime runtime = SquireRuntime.get();
			UUID agentId = runtime.agents().resolveForOwner(player.getUuid())
				.map(dev.squire.server.body.avatar.AvatarEntity::agentId).orElse(null);
			int removed = runtime.conversations().forgetFor(player.getUuid(), agentId);
			return new SquireRuntime.ExecutionResult(true, "feedback.conversation_forget",
				null, "[Squire] 已删除 " + removed + " 条已结束的结构化对话摘要；"
					+ "正在执行的任务未受影响。");
		});
	}


	/** Player-facing automation management (spec §49/§70). */
	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> automationBranch() {
		return CommandManager.literal("automation")
			.executes(SquireCommands::automationList)
			.then(CommandManager.literal("list").executes(SquireCommands::automationList))
			.then(CommandManager.literal("inspect")
				.then(CommandManager.argument("id", StringArgumentType.string())
					.executes(SquireCommands::automationInspect)))
			.then(CommandManager.literal("pause")
				.then(CommandManager.argument("id", StringArgumentType.string())
					.executes(ctx -> automationControl(ctx, "pause"))))
			.then(CommandManager.literal("resume")
				.then(CommandManager.argument("id", StringArgumentType.string())
					.executes(ctx -> automationControl(ctx, "resume"))))
			.then(CommandManager.literal("remove")
				.then(CommandManager.argument("id", StringArgumentType.string())
					.executes(ctx -> automationControl(ctx, "remove"))))
			.then(CommandManager.literal("fire")
				.then(CommandManager.argument("id", StringArgumentType.string())
					.executes(ctx -> automationControl(ctx, "fire"))));
	}

	/**
	 * 方案 F2：选区归服务器所有。模型只能说"选定区域"，坐标永远由玩家在这里给定。
	 */
	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource>
			selectionBranch() {
		return CommandManager.literal("selection")
			.executes(SquireCommands::selectionShow)
			.then(CommandManager.literal("show").executes(SquireCommands::selectionShow))
			.then(CommandManager.literal("clear").executes(SquireCommands::selectionClear))
			.then(CommandManager.literal("pos1")
				.executes(ctx -> selectionCorner(ctx, true, null))
				.then(CommandManager.argument("pos",
						net.minecraft.command.argument.BlockPosArgumentType.blockPos())
					.executes(ctx -> selectionCorner(ctx, true,
						net.minecraft.command.argument.BlockPosArgumentType
							.getBlockPos(ctx, "pos")))))
			.then(CommandManager.literal("pos2")
				.executes(ctx -> selectionCorner(ctx, false, null))
				.then(CommandManager.argument("pos",
						net.minecraft.command.argument.BlockPosArgumentType.blockPos())
					.executes(ctx -> selectionCorner(ctx, false,
						net.minecraft.command.argument.BlockPosArgumentType
							.getBlockPos(ctx, "pos")))));
	}

	/**
	 * 第 1 期：蓝图的玩家入口。
	 *
	 * <p>每一步都有下一步可敲：list → place →（缺料就）fulfil → build，
	 * 任何时候都能 status 看账、cancel 撑掉。「任何输入都要有出路」在这里的
	 * 意思是：没有一条回复只说「不行」而不告诉你接下来敲什么。</p>
	 */
	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource>
			blueprintBranch() {
		return CommandManager.literal("blueprint")
			.executes(SquireCommands::blueprintStatus)
			.then(CommandManager.literal("list").executes(SquireCommands::blueprintList))
			.then(CommandManager.literal("status").executes(SquireCommands::blueprintStatus))
			.then(CommandManager.literal("place")
				.then(CommandManager.argument("id", StringArgumentType.word())
					.suggests((ctx, builder) -> {
						for (String id : SquireRuntime.get().blueprints().registry().ids()) {
							builder.suggest(id);
						}
						return builder.buildFuture();
					})
					.executes(SquireCommands::blueprintPlace)))
			.then(CommandManager.literal("fulfil").executes(SquireCommands::blueprintFulfil))
			.then(CommandManager.literal("fulfill").executes(SquireCommands::blueprintFulfil))
			.then(CommandManager.literal("build").executes(SquireCommands::blueprintBuild))
			.then(CommandManager.literal("cancel").executes(SquireCommands::blueprintCancel))
			.then(CommandManager.literal("reload")
				.requires(source -> source.hasPermissionLevel(2))
				.executes(SquireCommands::blueprintReload));
	}

	/**
	 * 第 2 期：职业 / 能力槽 / 自主档位。
	 *
	 * <p>每一条拒绝都带着下一步：没解锁就说还差多少，槽满就列出摘哪一个。</p>
	 */
	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource>
			profileBranch() {
		return CommandManager.literal("profile")
			.executes(SquireCommands::profileStatus);
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource>
			roleBranch() {
		return CommandManager.literal("role")
			.executes(SquireCommands::roleList)
			.then(CommandManager.argument("id", StringArgumentType.word())
				.suggests((ctx, builder) -> {
					for (var role : dev.squire.server.profile.Role.values()) {
						builder.suggest(role.id());
					}
					return builder.buildFuture();
				})
				.executes(SquireCommands::roleSet));
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource>
			abilityBranch() {
		return CommandManager.literal("ability")
			.executes(SquireCommands::abilityList)
			.then(CommandManager.literal("equip")
				.then(CommandManager.argument("id", StringArgumentType.word())
					.suggests(SquireCommands::suggestAbilities)
					.executes(SquireCommands::abilityEquip)))
			.then(CommandManager.literal("remove")
				.then(CommandManager.argument("id", StringArgumentType.word())
					.suggests(SquireCommands::suggestAbilities)
					.executes(SquireCommands::abilityRemove)));
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource>
			autonomyBranch() {
		return CommandManager.literal("autonomy")
			.executes(SquireCommands::profileStatus)
			.then(CommandManager.argument("level", StringArgumentType.word())
				.suggests((ctx, builder) -> {
					for (var level : dev.squire.server.profile.AutonomyLevel.values()) {
						builder.suggest(level.id());
					}
					return builder.buildFuture();
				})
				.executes(SquireCommands::autonomySet));
	}

	private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion
			.Suggestions> suggestAbilities(CommandContext<ServerCommandSource> context,
			com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
		for (var ability : dev.squire.server.profile.Ability.equippable()) {
			builder.suggest(ability.id());
		}
		return builder.buildFuture();
	}

	/**
	 * 职业（守卫 / 工程师，1–10 级）。
	 *
	 * <p>{@code promote} 是<b>唯一</b>会升级的入口——设计文档明令禁止自动晋升，
	 * 所以经验满了也只会收到一句提示，升不升是玩家自己按的。</p>
	 */
	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource>
			professionBranch() {
		return CommandManager.literal("profession")
			.executes(SquireCommands::professionStatus)
			.then(CommandManager.literal("list")
				.executes(SquireCommands::professionList))
			.then(CommandManager.literal("promote")
				.executes(SquireCommands::professionPromote))
			.then(CommandManager.literal("forget")
				.executes(SquireCommands::professionForget))
			.then(CommandManager.argument("id", StringArgumentType.word())
				.suggests((ctx, builder) -> {
					for (var profession
							: dev.squire.server.profession.SquireProfession.values()) {
						builder.suggest(profession.id());
					}
					return builder.buildFuture();
				})
				.executes(SquireCommands::professionSet));
	}

	/** 战斗姿态（守卫 Lv.8）。 */
	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource>
			stanceBranch() {
		return CommandManager.literal("stance")
			.executes(SquireCommands::professionStatus)
			.then(CommandManager.argument("id", StringArgumentType.word())
				.suggests((ctx, builder) -> {
					for (var stance
							: dev.squire.server.profession.CombatStance.values()) {
						builder.suggest(stance.id());
					}
					return builder.buildFuture();
				})
				.executes(SquireCommands::stanceSet));
	}

	/** 补给清点（守卫 Lv.5）。 */
	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource>
			suppliesBranch() {
		return CommandManager.literal("supplies")
			.executes(SquireCommands::supplyReport);
	}

	private static int professionStatus(CommandContext<ServerCommandSource> context) {
		return withPlayer(context, player ->
			SquireRuntime.get().professionStatus(player));
	}

	private static int professionList(CommandContext<ServerCommandSource> context) {
		return withPlayer(context, player -> SquireRuntime.get().professionList(player));
	}

	private static int professionSet(CommandContext<ServerCommandSource> context) {
		return withPlayer(context, player -> SquireRuntime.get()
			.setProfession(player, StringArgumentType.getString(context, "id")));
	}

	private static int professionPromote(CommandContext<ServerCommandSource> context) {
		return withPlayer(context, player ->
			SquireRuntime.get().promoteProfession(player));
	}

	private static int professionForget(CommandContext<ServerCommandSource> context) {
		return withPlayer(context, player ->
			SquireRuntime.get().forgetProfession(player));
	}

	private static int stanceSet(CommandContext<ServerCommandSource> context) {
		return withPlayer(context, player -> SquireRuntime.get()
			.setCombatStance(player, StringArgumentType.getString(context, "id")));
	}

	private static int supplyReport(CommandContext<ServerCommandSource> context) {
		return withPlayer(context, player -> SquireRuntime.get().supplyReport(player));
	}

	/**
	 * 工程师的参数层（设计文档 §13）。
	 *
	 * <p>每一个子命令都对应一项按等级解锁的能力；没解锁的会如实说还差几级、
	 * 以及现在能做什么，而不是静默无反应。</p>
	 */
	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource>
			designBranch() {
		return CommandManager.literal("design")
			.executes(SquireCommands::designStatus)
			.then(CommandManager.literal("size")
				.then(CommandManager.argument("width", IntegerArgumentType.integer(1, 64))
					.then(CommandManager.argument("depth",
							IntegerArgumentType.integer(1, 64))
						.executes(SquireCommands::designSize))))
			.then(CommandManager.literal("height")
				.then(CommandManager.argument("value", IntegerArgumentType.integer(1, 16))
					.executes(SquireCommands::designHeight)))
			.then(CommandManager.literal("floors")
				.then(CommandManager.argument("value", IntegerArgumentType.integer(1, 8))
					.executes(SquireCommands::designFloors)))
			.then(CommandManager.literal("roof")
				.then(CommandManager.argument("id", StringArgumentType.word())
					.suggests((ctx, builder) -> {
						for (var value : dev.squire.server.blueprint.ProjectSpec
								.Roof.values()) {
							builder.suggest(value.id());
						}
						return builder.buildFuture();
					})
					.executes(SquireCommands::designRoof)))
			.then(CommandManager.literal("foundation")
				.then(CommandManager.argument("id", StringArgumentType.word())
					.suggests((ctx, builder) -> {
						for (var value : dev.squire.server.blueprint.ProjectSpec
								.Foundation.values()) {
							builder.suggest(value.id());
						}
						return builder.buildFuture();
					})
					.executes(SquireCommands::designFoundation)))
			.then(CommandManager.literal("window")
				.then(CommandManager.argument("id", StringArgumentType.word())
					.suggests((ctx, builder) -> {
						for (var value : dev.squire.server.blueprint.ProjectSpec
								.WindowStyle.values()) {
							builder.suggest(value.id());
						}
						return builder.buildFuture();
					})
					.executes(SquireCommands::designWindow)))
			.then(CommandManager.literal("entrance")
				.then(CommandManager.argument("id", StringArgumentType.word())
					.suggests((ctx, builder) -> {
						for (var value : dev.squire.server.blueprint.ProjectSpec
								.Entrance.values()) {
							builder.suggest(value.id());
						}
						return builder.buildFuture();
					})
					.executes(SquireCommands::designEntrance)))
			.then(CommandManager.literal("module")
				.then(CommandManager.argument("id", StringArgumentType.word())
					.suggests((ctx, builder) -> {
						for (var value : dev.squire.server.blueprint.ProjectSpec
								.Module.values()) {
							builder.suggest(value.id());
						}
						return builder.buildFuture();
					})
					.executes(SquireCommands::designModule)))
			.then(CommandManager.literal("mirror")
				.then(CommandManager.argument("axis", StringArgumentType.word())
					.suggests((ctx, builder) -> {
						builder.suggest("x");
						builder.suggest("z");
						builder.suggest("xz");
						builder.suggest("none");
						return builder.buildFuture();
					})
					.executes(SquireCommands::designMirror)))
			.then(CommandManager.literal("preset")
				.executes(SquireCommands::designPresetList)
				.then(CommandManager.literal("list")
					.executes(SquireCommands::designPresetList))
				.then(CommandManager.literal("save")
					.then(CommandManager.argument("name",
							StringArgumentType.greedyString())
						.executes(SquireCommands::designPresetSave)))
				.then(CommandManager.literal("load")
					.then(CommandManager.argument("name",
							StringArgumentType.greedyString())
						.executes(SquireCommands::designPresetLoad)))
				.then(CommandManager.literal("delete")
					.then(CommandManager.argument("name",
							StringArgumentType.greedyString())
						.executes(SquireCommands::designPresetDelete))))
			// 模板放在最后：前面的字面量优先匹配，剩下的才当模板名解析。
			.then(CommandManager.argument("template", StringArgumentType.word())
				.suggests((ctx, builder) -> {
					for (var template : dev.squire.server.blueprint.ProjectSpec
							.Template.values()) {
						builder.suggest(template.id());
					}
					return builder.buildFuture();
				})
				.executes(SquireCommands::designTemplate));
	}

	private static int designStatus(CommandContext<ServerCommandSource> context) {
		return withPlayer(context, player -> SquireRuntime.get().designStatus(player));
	}

	private static int designTemplate(CommandContext<ServerCommandSource> context) {
		return withPlayer(context, player -> SquireRuntime.get()
			.design(player, StringArgumentType.getString(context, "template")));
	}

	private static int designSize(CommandContext<ServerCommandSource> context) {
		return withPlayer(context, player -> SquireRuntime.get().designSize(player,
			IntegerArgumentType.getInteger(context, "width"),
			IntegerArgumentType.getInteger(context, "depth")));
	}

	private static int designHeight(CommandContext<ServerCommandSource> context) {
		return withPlayer(context, player -> SquireRuntime.get().designWallHeight(player,
			IntegerArgumentType.getInteger(context, "value")));
	}

	private static int designFloors(CommandContext<ServerCommandSource> context) {
		return withPlayer(context, player -> SquireRuntime.get().designFloors(player,
			IntegerArgumentType.getInteger(context, "value")));
	}

	private static int designRoof(CommandContext<ServerCommandSource> context) {
		return withPlayer(context, player -> SquireRuntime.get()
			.designRoof(player, StringArgumentType.getString(context, "id")));
	}

	private static int designFoundation(CommandContext<ServerCommandSource> context) {
		return withPlayer(context, player -> SquireRuntime.get()
			.designFoundation(player, StringArgumentType.getString(context, "id")));
	}

	private static int designWindow(CommandContext<ServerCommandSource> context) {
		return withPlayer(context, player -> SquireRuntime.get()
			.designWindow(player, StringArgumentType.getString(context, "id")));
	}

	private static int designEntrance(CommandContext<ServerCommandSource> context) {
		return withPlayer(context, player -> SquireRuntime.get()
			.designEntrance(player, StringArgumentType.getString(context, "id")));
	}

	private static int designModule(CommandContext<ServerCommandSource> context) {
		return withPlayer(context, player -> SquireRuntime.get()
			.designModule(player, StringArgumentType.getString(context, "id")));
	}

	private static int designMirror(CommandContext<ServerCommandSource> context) {
		return withPlayer(context, player -> SquireRuntime.get()
			.designMirror(player, StringArgumentType.getString(context, "axis")));
	}

	private static int designPresetList(CommandContext<ServerCommandSource> context) {
		return withPlayer(context, player -> SquireRuntime.get().designPresetList(player));
	}

	private static int designPresetSave(CommandContext<ServerCommandSource> context) {
		return withPlayer(context, player -> SquireRuntime.get()
			.designPresetSave(player, StringArgumentType.getString(context, "name")));
	}

	private static int designPresetLoad(CommandContext<ServerCommandSource> context) {
		return withPlayer(context, player -> SquireRuntime.get()
			.designPresetLoad(player, StringArgumentType.getString(context, "name")));
	}

	private static int designPresetDelete(CommandContext<ServerCommandSource> context) {
		return withPlayer(context, player -> SquireRuntime.get()
			.designPresetDelete(player, StringArgumentType.getString(context, "name")));
	}

	/**
	 * 巡逻点。取的是<b>伙伴脚下</b>那一格：玩家要的是「你站到那儿去，把这里记下来」。
	 */
	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource>
			patrolBranch() {
		return CommandManager.literal("patrol")
			.executes(SquireCommands::patrolList)
			.then(CommandManager.literal("add").executes(SquireCommands::patrolAdd))
			.then(CommandManager.literal("list").executes(SquireCommands::patrolList))
			.then(CommandManager.literal("clear").executes(SquireCommands::patrolClear));
	}

	/**
	 * 第 3 期：一句大目标 → 六个阶段。命令入口和聊天入口（「帮我准备一个矿井前哨站」）
	 * 落到同一条服务端路径上。
	 */
	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource>
			projectBranch() {
		return CommandManager.literal("project")
			.executes(SquireCommands::projectStatus)
			.then(CommandManager.literal("start")
				.then(CommandManager.argument("id", StringArgumentType.word())
					.suggests((ctx, builder) -> {
						for (String id : SquireRuntime.get().blueprints().registry().ids()) {
							builder.suggest(id);
						}
						return builder.buildFuture();
					})
					.executes(SquireCommands::projectStart)))
			.then(CommandManager.literal("pause").executes(SquireCommands::projectPause))
			.then(CommandManager.literal("resume").executes(SquireCommands::projectResume))
			.then(CommandManager.literal("cancel").executes(SquireCommands::projectCancel));
	}

	private static int projectStart(CommandContext<ServerCommandSource> context) {
		return withPlayer(context, player -> SquireRuntime.get()
			.projectStart(player, StringArgumentType.getString(context, "id")));
	}

	private static int projectStatus(CommandContext<ServerCommandSource> context) {
		return withPlayer(context, player -> SquireRuntime.get().projectStatus(player));
	}

	private static int projectPause(CommandContext<ServerCommandSource> context) {
		return withPlayer(context, player -> SquireRuntime.get().projectPause(player));
	}

	private static int projectResume(CommandContext<ServerCommandSource> context) {
		return withPlayer(context, player -> SquireRuntime.get().projectResume(player));
	}

	private static int projectCancel(CommandContext<ServerCommandSource> context) {
		return withPlayer(context, player -> SquireRuntime.get().projectCancel(player));
	}


	private static int patrolAdd(CommandContext<ServerCommandSource> context) {
		return withPlayer(context, player -> SquireRuntime.get().addPatrolPoint(player));
	}

	private static int patrolList(CommandContext<ServerCommandSource> context) {
		return withPlayer(context, player -> SquireRuntime.get().listPatrolPoints(player));
	}

	private static int patrolClear(CommandContext<ServerCommandSource> context) {
		return withPlayer(context, player -> SquireRuntime.get().clearPatrolPoints(player));
	}


	private static int profileStatus(CommandContext<ServerCommandSource> context) {
		return withPlayer(context, player -> SquireRuntime.get().profileStatus(player));
	}

	private static int roleList(CommandContext<ServerCommandSource> context) {
		return withPlayer(context, player -> SquireRuntime.get().roleList(player));
	}

	private static int roleSet(CommandContext<ServerCommandSource> context) {
		return withPlayer(context, player -> SquireRuntime.get()
			.setRole(player, StringArgumentType.getString(context, "id")));
	}

	private static int abilityList(CommandContext<ServerCommandSource> context) {
		return withPlayer(context, player -> SquireRuntime.get().abilityList(player));
	}

	private static int abilityEquip(CommandContext<ServerCommandSource> context) {
		return withPlayer(context, player -> SquireRuntime.get()
			.equipAbility(player, StringArgumentType.getString(context, "id")));
	}

	private static int abilityRemove(CommandContext<ServerCommandSource> context) {
		return withPlayer(context, player -> SquireRuntime.get()
			.unequipAbility(player, StringArgumentType.getString(context, "id")));
	}

	private static int autonomySet(CommandContext<ServerCommandSource> context) {
		return withPlayer(context, player -> SquireRuntime.get()
			.setAutonomy(player, StringArgumentType.getString(context, "level")));
	}


	private static int blueprintList(CommandContext<ServerCommandSource> context) {
		surface(context.getSource(), SquireRuntime.get().blueprintList());
		return 1;
	}

	private static int blueprintReload(CommandContext<ServerCommandSource> context) {
		surface(context.getSource(), SquireRuntime.get().blueprintReload());
		return 1;
	}

	private static int blueprintPlace(CommandContext<ServerCommandSource> context) {
		return withPlayer(context, player -> SquireRuntime.get()
			.blueprintPlace(player, StringArgumentType.getString(context, "id")));
	}

	private static int blueprintStatus(CommandContext<ServerCommandSource> context) {
		return withPlayer(context, player -> SquireRuntime.get().blueprintStatus(player));
	}

	private static int blueprintFulfil(CommandContext<ServerCommandSource> context) {
		return withPlayer(context, player -> SquireRuntime.get().blueprintFulfil(player));
	}

	private static int blueprintBuild(CommandContext<ServerCommandSource> context) {
		return withPlayer(context, player -> SquireRuntime.get().blueprintBuild(player));
	}

	private static int blueprintCancel(CommandContext<ServerCommandSource> context) {
		return withPlayer(context, player -> SquireRuntime.get().blueprintCancel(player));
	}

	/** 玩家专属命令的共同外壳：控制台发这些命令时得到的是一句人话，而不是栈。 */
	private static int withPlayer(CommandContext<ServerCommandSource> context,
			java.util.function.Function<ServerPlayerEntity,
				SquireRuntime.ExecutionResult> action) {
		ServerCommandSource source = context.getSource();
		try {
			surface(source, action.apply(source.getPlayerOrThrow()));
			return 1;
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
			source.sendError(SquireText.msg("squire.cmd.players_only"));
			return 0;
		}
	}

	/**
	 * 自定义快捷指令：
	 * {@code /squire shortcut [add <名字> <动作> [档位] | remove <名字>]}。
	 *
	 * <p>面板「指令」页的「我的快捷」做同样的事。命令这一条留着是为了写进按键宏。</p>
	 *
	 * <p>参数原来是一整行「名字=要说的话」，存下来的是一句自然语言——点一下要重新
	 * 过输入网关，也就是<b>可能再走一次模型</b>。现在第二个参数是
	 * {@code CommandCatalog} 里的动作 id，能补全，也和面板存下来的东西完全一样。</p>
	 */
	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource>
			shortcutBranch() {
		return CommandManager.literal("shortcut")
			.executes(ctx -> withPlayer(ctx,
				(source, player) -> SquireRuntime.get().listShortcuts(player)))
			.then(CommandManager.literal("add")
				.then(CommandManager.argument("name", StringArgumentType.string())
					.then(CommandManager.argument("action", StringArgumentType.string())
						.suggests(SquireCommands::suggestShortcutActions)
						.executes(ctx -> withPlayer(ctx, (source, player) ->
							SquireRuntime.get().bindShortcut(player,
								StringArgumentType.getString(ctx, "name"),
								StringArgumentType.getString(ctx, "action"), "")))
						.then(CommandManager.argument("variant",
								StringArgumentType.string())
							.executes(ctx -> withPlayer(ctx, (source, player) ->
								SquireRuntime.get().bindShortcut(player,
									StringArgumentType.getString(ctx, "name"),
									StringArgumentType.getString(ctx, "action"),
									StringArgumentType.getString(ctx, "variant"))))))))
			.then(CommandManager.literal("remove")
				.then(CommandManager.argument("name", StringArgumentType.greedyString())
					.executes(ctx -> withPlayer(ctx, (source, player) ->
						SquireRuntime.get().deleteShortcut(player,
							StringArgumentType.getString(ctx, "name"))))));
	}

	/** 动作 id 从目录里补全——让玩家去别处抄一个 id 出来是没道理的。 */
	private static java.util.concurrent.CompletableFuture<
			com.mojang.brigadier.suggestion.Suggestions> suggestShortcutActions(
			CommandContext<ServerCommandSource> context,
			com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
		for (var entry : dev.squire.server.gui.CommandCatalog.ENTRIES) {
			if (entry.bindable()) {
				builder.suggest(entry.id(), Text.literal(entry.displayName()));
			}
		}
		return builder.buildFuture();
	}

	/** {@code /squire follow distance <格>}：多远就直接传送到主人身边。 */
	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource>
			followBranch() {
		return CommandManager.literal("follow")
			.then(CommandManager.literal("distance")
				.then(CommandManager.argument("blocks",
						com.mojang.brigadier.arguments.IntegerArgumentType.integer(
							dev.squire.server.body.avatar.AvatarEntity
								.FOLLOW_TELEPORT_MIN,
							dev.squire.server.body.avatar.AvatarEntity
								.FOLLOW_TELEPORT_MAX))
					.executes(ctx -> withPlayer(ctx, (source, player) ->
						SquireRuntime.get().setFollowTeleportDistance(player,
							com.mojang.brigadier.arguments.IntegerArgumentType
								.getInteger(ctx, "blocks"))))));
	}

	/** 需要玩家身份的子命令共用的外壳：统一取玩家、统一回执、统一错误。 */
	private static int withPlayer(CommandContext<ServerCommandSource> context,
			java.util.function.BiFunction<ServerCommandSource, ServerPlayerEntity,
				SquireRuntime.ExecutionResult> body) {
		ServerCommandSource source = context.getSource();
		try {
			ServerPlayerEntity player = source.getPlayerOrThrow();
			var result = body.apply(source, player);
			source.sendFeedback(() -> Text.literal(result.message()), false);
			return result.success() ? 1 : 0;
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
			source.sendError(Text.translatable("squire.cmd.players_only"));
			return 0;
		}
	}

	/** 方案 F4：持久 Undo 的玩家入口。 */
	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource>
			undoBranch() {
		return CommandManager.literal("undo")
			.executes(ctx -> undo(ctx, null, false))
			.then(CommandManager.literal("list").executes(SquireCommands::undoList))
			.then(CommandManager.argument("operationId", StringArgumentType.string())
				.executes(ctx -> undo(ctx,
					StringArgumentType.getString(ctx, "operationId"), false))
				.then(CommandManager.literal("confirm")
					.executes(ctx -> undo(ctx,
						StringArgumentType.getString(ctx, "operationId"), true))));
	}

	private static int selectionCorner(CommandContext<ServerCommandSource> context,
			boolean first, net.minecraft.util.math.BlockPos explicit) {
		ServerCommandSource source = context.getSource();
		try {
			ServerPlayerEntity player = source.getPlayerOrThrow();
			var selections = SquireRuntime.get().selections();
			// 不带坐标时用注视的方块，但解析结果必须原样回显给玩家
			var pos = explicit != null
				? explicit
				: dev.squire.server.world.SelectionService.lookedAtBlock(player);
			var updated = selections.setCorner(player, first, pos);
			String message = "[Squire] " + (first ? "pos1" : "pos2") + " = ("
				+ pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")\n"
				+ updated.describe();
			source.sendFeedback(() -> Text.literal(message), false);
			return 1;
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
			source.sendError(SquireText.msg("squire.cmd.players_only"));
			return 0;
		}
	}

	private static int selectionShow(CommandContext<ServerCommandSource> context) {
		ServerCommandSource source = context.getSource();
		try {
			ServerPlayerEntity player = source.getPlayerOrThrow();
			var selection = SquireRuntime.get().selections().of(player.getUuid());
			String message = selection.map(s -> "[Squire] 当前选区：" + s.describe())
				.orElse("[Squire] 还没有选区。用 /squire selection pos1 / pos2 设定。");
			source.sendFeedback(() -> Text.literal(message), false);
			return 1;
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
			source.sendError(SquireText.msg("squire.cmd.players_only"));
			return 0;
		}
	}

	private static int selectionClear(CommandContext<ServerCommandSource> context) {
		ServerCommandSource source = context.getSource();
		try {
			ServerPlayerEntity player = source.getPlayerOrThrow();
			SquireRuntime.get().selections().clear(player.getUuid());
			source.sendFeedback(() -> Text.literal("[Squire] 选区已清除。"), false);
			return 1;
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
			source.sendError(SquireText.msg("squire.cmd.players_only"));
			return 0;
		}
	}

	private static int undoList(CommandContext<ServerCommandSource> context) {
		ServerCommandSource source = context.getSource();
		try {
			ServerPlayerEntity player = source.getPlayerOrThrow();
			var entries = SquireRuntime.get().undoableFor(player.getUuid());
			StringBuilder text = new StringBuilder("[Squire] 可撤销的操作（最近在前）：");
			if (entries.isEmpty()) {
				text.append("\n（无）");
			}
			for (var journal : entries) {
				text.append("\n - ").append(journal.describe());
			}
			String message = text.toString();
			source.sendFeedback(() -> Text.literal(message), false);
			return 1;
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
			source.sendError(SquireText.msg("squire.cmd.players_only"));
			return 0;
		}
	}

	private static int undo(CommandContext<ServerCommandSource> context, String rawId,
			boolean acceptConflicts) {
		ServerCommandSource source = context.getSource();
		try {
			ServerPlayerEntity player = source.getPlayerOrThrow();
			UUID operationId = null;
			if (rawId != null) {
				try {
					operationId = UUID.fromString(rawId);
				} catch (IllegalArgumentException e) {
					source.sendError(Text.literal("[Squire] 无效的 operationId。"));
					return 0;
				}
			}
			var result = SquireRuntime.get().undoOperation(player, operationId,
				acceptConflicts);
			source.sendFeedback(() -> Text.literal(result.message()), false);
			return result.success() ? 1 : 0;
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
			source.sendError(SquireText.msg("squire.cmd.players_only"));
			return 0;
		}
	}

	/** 方案 F3：玩家明确拒绝一条待确认操作。 */
	private static int executeDeny(CommandContext<ServerCommandSource> context) {
		ServerCommandSource source = context.getSource();
		String raw = StringArgumentType.getString(context, "confirmId");
		try {
			ServerPlayerEntity player = source.getPlayerOrThrow();
			UUID confirmId;
			try {
				confirmId = UUID.fromString(raw);
			} catch (IllegalArgumentException e) {
				source.sendError(SquireText.msg("squire.cmd.bad_confirm_id"));
				return 0;
			}
			String message = SquireRuntime.get().denyRequest(player, confirmId);
			source.sendFeedback(() -> Text.literal(message), false);
			return 1;
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
			source.sendError(SquireText.msg("squire.cmd.players_only"));
			return 0;
		}
	}

	/** Owner confirms one pending high-risk operation (spec §45). */
	private static int executeConfirm(CommandContext<ServerCommandSource> context) {
		ServerCommandSource source = context.getSource();
		String raw = StringArgumentType.getString(context, "confirmId");
		try {
			ServerPlayerEntity player = source.getPlayerOrThrow();
			UUID confirmId;
			try {
				confirmId = UUID.fromString(raw);
			} catch (IllegalArgumentException e) {
				source.sendError(SquireText.msg("squire.cmd.bad_confirm_id"));
				return 0;
			}
			String message = SquireRuntime.get().confirmRequest(player, confirmId);
			source.sendFeedback(() -> Text.literal(message), false);
			return 1;
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
			source.sendError(SquireText.msg("squire.cmd.players_only"));
			return 0;
		}
	}

	/** 一条命令看清"伙伴为什么不动"。 */
	private static int diagnose(CommandContext<ServerCommandSource> context) {
		ServerCommandSource source = context.getSource();
		try {
			ServerPlayerEntity player = source.getPlayerOrThrow();
			String message = SquireRuntime.get().diagnose(player);
			source.sendFeedback(() -> Text.literal(message), false);
			return 1;
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
			source.sendError(SquireText.msg("squire.cmd.players_only"));
			return 0;
		}
	}

	/** 测试捷径开关：让"给我 xxx"直接发物品，跳过采集。 */
	private static int instantAcquire(CommandContext<ServerCommandSource> context,
			boolean enable) {
		SquireRuntime.get().setInstantAcquireEnabled(enable);
		String message = enable
			? "[Squire] 直给模式已开启：\u300c给我 xxx\u300d会直接把物品放进伙伴背包再交给你。"
				+ "这是测试用捷径，不能作为 B02/B04 的验收证据。"
			: "[Squire] 直给模式已关闭，恢复为真实采集/合成。";
		context.getSource().sendFeedback(() -> Text.literal(message), true);
		return 1;
	}

	/** 方案 I2：服主可见的 MCP 配置与连接状态。 */
	private static int mcpList(CommandContext<ServerCommandSource> context) {
		var rt = SquireRuntime.get();
		var config = rt.mcpConfig();
		StringBuilder text = new StringBuilder("[Squire] MCP 服务器（")
			.append(SquireRuntime.mcpConfigFile()).append("）：");
		if (config.servers().isEmpty()) {
			text.append("\n（无）");
		}
		for (var entry : config.servers()) {
			text.append("\n - ").append(entry.describe())
				.append(rt.mcp().isConnected(entry.name()) ? " [已连接]" : " [未连接]");
		}
		for (String problem : config.problems()) {
			text.append("\n ! ").append(problem);
		}
		String message = text.toString();
		context.getSource().sendFeedback(() -> Text.literal(message), false);
		return 1;
	}

	private static int mcpReload(CommandContext<ServerCommandSource> context) {
		String message = SquireRuntime.get().reloadMcpServers();
		context.getSource().sendFeedback(() -> Text.literal(message), true);
		return 1;
	}

	private static int mcpStatus(CommandContext<ServerCommandSource> context) {
		String server = StringArgumentType.getString(context, "server");
		String message = SquireRuntime.get().mcpStatus(server);
		context.getSource().sendFeedback(() -> Text.literal(message), false);
		return 1;
	}

	private static int killswitch(CommandContext<ServerCommandSource> context, boolean enable) {
		String message = SquireRuntime.get().setKillswitch(enable);
		context.getSource().sendFeedback(() -> Text.literal(message), true);
		return 1;
	}

	private static int killswitchStatus(CommandContext<ServerCommandSource> context) {
		boolean active = SquireRuntime.get().killswitch().isActive();
		context.getSource().sendFeedback(() -> SquireText.msg(active
			? "squire.cmd.killswitch_on" : "squire.cmd.killswitch_off"), false);
		return 1;
	}

	private static int worldEdit(CommandContext<ServerCommandSource> context, boolean enable) {
		SquireRuntime rt = SquireRuntime.get();
		rt.setWorldEditEnabled(enable);
		context.getSource().sendFeedback(() -> SquireText.msg(enable
			? "squire.cmd.worldedit_enabled" : "squire.cmd.worldedit_disabled"), true);
		return 1;
	}

	private static int adminCommands(CommandContext<ServerCommandSource> context,
			boolean enable) {
		SquireRuntime.get().setAdminCommandsEnabled(enable);
		context.getSource().sendFeedback(() -> SquireText.msg(enable
			? "squire.cmd.admincommands_enabled"
			: "squire.cmd.admincommands_disabled"), true);
		return 1;
	}

	/** M4 tool inspector: every registered tool plus the import report trail. */
	private static int toolInspector(CommandContext<ServerCommandSource> context) {
		var rt = SquireRuntime.get();
		var lines = dev.squire.server.ext.ExtensionManager.inspectorLines(
			rt.toolRegistry(), rt.extensions().reports());
		for (String line : lines) {
			context.getSource().sendFeedback(() -> Text.literal("[Squire] " + line), false);
		}
		return lines.size();
	}

	/** M6/§75 metrics inspector: bounded counters + duration gauges. */
	private static int metricsInspector(CommandContext<ServerCommandSource> context) {
		java.util.List<String> lines = SquireRuntime.get().metrics().snapshotLines();
		context.getSource().sendFeedback(() ->
			SquireText.msg("squire.cmd.metrics_header"), false);
		for (String line : lines) {
			context.getSource().sendFeedback(() -> Text.literal("[Squire]   " + line),
				false);
		}
		return lines.size();
	}

	// ------------------------------------------------------------------ perf

	/**
	 * 打印 {@code tickScheduler()} 十三个分段最近 200 拍的耗时。
	 *
	 * <p>用等宽字体对齐，因为这份输出的用法是「改动前后各拍一张，逐行对比哪一行变了」，
	 * 对不齐就没法扫。</p>
	 */
	private static int perfInspector(CommandContext<ServerCommandSource> context) {
		java.util.List<String> lines = SquireRuntime.get().profiler().snapshotLines();
		context.getSource().sendFeedback(() ->
			Text.literal("[Squire] 每拍分段耗时（最近 "
				+ dev.squire.server.metrics.TickProfiler.WINDOW + " 拍）")
				.formatted(Formatting.AQUA), false);
		for (String line : lines) {
			context.getSource().sendFeedback(() -> Text.literal("  " + line), false);
		}
		return lines.size();
	}

	/** 量一段新场景之前先归零，免得旧数据把均值和峰值都带偏。 */
	private static int perfReset(CommandContext<ServerCommandSource> context) {
		SquireRuntime.get().profiler().reset();
		context.getSource().sendFeedback(() ->
			Text.literal("[Squire] 分段计时已归零。"), true);
		return 1;
	}

	// ------------------------------------------------------------------ llm (§23)

	/** Masked provider/config inspector — never prints the API key. */
	private static int llmStatus(CommandContext<ServerCommandSource> context) {
		for (String line : dev.squire.server.provider.LlmWiring
				.statusLines(SquireRuntime.llmConfigFile())) {
			context.getSource().sendFeedback(() -> Text.literal("[Squire]   " + line),
				false);
		}
		return 1;
	}

	/** Re-reads config/squire/llm.json; keeps the old provider if it fails. */
	private static int llmReload(CommandContext<ServerCommandSource> context) {
		boolean ok = dev.squire.server.provider.LlmWiring.rewire(
			SquireRuntime.llmConfigFile(),
			agentId -> SquireRuntime.get().modelVisibleDescriptors(agentId));
		context.getSource().sendFeedback(() -> Text.literal(ok
			? "[Squire] LLM config reloaded and active."
			: "[Squire] reload failed (config absent/invalid) — previous provider kept."),
			true);
		return ok ? 1 : 0;
	}

	// ------------------------------------------------------------------ automation (M5)

	private static int automationToggle(CommandContext<ServerCommandSource> context,
			boolean enable) {
		SquireRuntime.get().automation().setEnabled(enable);
		context.getSource().sendFeedback(() -> SquireText.msg(enable
			? "squire.cmd.automation_enabled" : "squire.cmd.automation_disabled"), true);
		return 1;
	}

	private static int automationList(CommandContext<ServerCommandSource> context) {
		var engine = SquireRuntime.get().automation();
		java.util.List<dev.squire.server.automation.AutomationGraph> graphs;
		try {
			ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
			graphs = engine.ownedBy(player.getUuid());
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
			graphs = engine.all(); // console sees everything
		}
		if (graphs.isEmpty()) {
			context.getSource().sendFeedback(() ->
				SquireText.msg("squire.cmd.no_automations"), false);
			return 0;
		}
		for (var g : graphs) {
			String line = g.name() + " [" + g.state() + "] id=" + g.id()
				+ " trigger=" + g.trigger();
			context.getSource().sendFeedback(() -> Text.literal("[Squire] " + line), false);
		}
		return graphs.size();
	}

	private static int automationInspect(CommandContext<ServerCommandSource> context) {
		UUID id = automationId(context);
		if (id == null) {
			return 0;
		}
		var found = SquireRuntime.get().automation().get(id);
		if (found.isEmpty()) {
			context.getSource().sendError(SquireText.msg(
				"squire.cmd.no_such_automation", id.toString()));
			return 0;
		}
		var g = found.get();
		StringBuilder sb = new StringBuilder("[Squire] ").append(g.name())
			.append(" [").append(g.state()).append(']')
			.append(" owner=").append(g.ownerId())
			.append(" ttl=").append(g.ttlTicks()).append("t")
			.append(" trigger=").append(g.trigger());
		for (var c : g.conditions()) {
			sb.append("\n[Squire]   condition: ").append(c);
		}
		for (var n : g.nodeList()) {
			sb.append("\n[Squire]   node ").append(n.kind()).append(": ")
				.append(n.kind() == dev.squire.server.automation.AutomationNode.Kind.NOTIFY
					|| n.kind() == dev.squire.server.automation.AutomationNode.Kind.TOOL_CALL
					|| n.kind() == dev.squire.server.automation.AutomationNode.Kind.CREATE_TASK
					? n.text() : "");
		}
		String text = sb.toString();
		context.getSource().sendFeedback(() -> Text.literal(text), false);
		return 1;
	}

	private static int automationControl(CommandContext<ServerCommandSource> context,
			String action) {
		ServerCommandSource source = context.getSource();
		UUID id = automationId(context);
		if (id == null) {
			return 0;
		}
		try {
			ServerPlayerEntity player = source.getPlayerOrThrow();
			boolean admin = source.hasPermissionLevel(2);
			dev.squire.server.automation.AutomationEngine.Access access =
				switch (action) {
					case "pause" -> SquireRuntime.get().automation().pause(id,
						player.getUuid(), admin);
					case "resume" -> SquireRuntime.get().automation().resume(id,
						player.getUuid(), admin);
					case "remove" -> SquireRuntime.get().automation().remove(id,
						player.getUuid(), admin);
					case "fire" -> SquireRuntime.get().automation().fire(id,
						player.getUuid(), source.getWorld().getTime());
					default -> new dev.squire.server.automation.AutomationEngine.Access(
						false, "unknown action");
				};
			if (access.ok()) {
				source.sendFeedback(() -> SquireText.msg(
					"squire.cmd.automation_done", action, access.message()), false);
				return 1;
			}
			source.sendError(Text.literal("[Squire] " + access.message()));
			return 0;
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
			source.sendError(SquireText.msg("squire.cmd.players_only"));
			return 0;
		}
	}

	private static UUID automationId(CommandContext<ServerCommandSource> context) {
		try {
			return UUID.fromString(StringArgumentType.getString(context, "id"));
		} catch (IllegalArgumentException e) {
			context.getSource().sendError(SquireText.msg("squire.cmd.bad_automation_id"));
			return null;
		}
	}

	// ------------------------------------------------------------------ CBP (M5b, spec §50/§89)

	/** Owner's build workspace — the ONLY area a CBP project may ever touch. */
	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> workspaceBranch() {
		return CommandManager.literal("workspace")
			.then(CommandManager.literal("set")
				.then(CommandManager.argument("from",
						net.minecraft.command.argument.BlockPosArgumentType.blockPos())
					.then(CommandManager.argument("to",
							net.minecraft.command.argument.BlockPosArgumentType.blockPos())
						.executes(SquireCommands::workspaceSet))))
			.then(CommandManager.literal("show").executes(SquireCommands::workspaceShow))
			.then(CommandManager.literal("clear").executes(SquireCommands::workspaceClear));
	}

	/**
	 * Explicit CBP surface (spec §89: 玩家明确要求时可物化). The plan subcommand
	 * creates the SPEC + confirmation request; placement only ever follows
	 * {@code /squire confirm <id>}.
	 */
	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> cbpBranch() {
		return CommandManager.literal("cbp")
			.executes(SquireCommands::cbpList)
			.then(CommandManager.literal("list").executes(SquireCommands::cbpList))
			.then(CommandManager.literal("plan")
				.then(CommandManager.argument("name", StringArgumentType.string())
					.then(CommandManager.argument("pos",
							net.minecraft.command.argument.BlockPosArgumentType.blockPos())
						.then(CommandManager.argument("type", StringArgumentType.string())
							.then(CommandManager.argument("auto", StringArgumentType.word())
								.then(CommandManager.argument("command",
										StringArgumentType.greedyString())
									.executes(SquireCommands::cbpPlan)))))))
			.then(CommandManager.literal("inspect")
				.then(CommandManager.argument("id", StringArgumentType.string())
					.executes(SquireCommands::cbpInspect)))
			.then(CommandManager.literal("disable")
				.then(CommandManager.argument("id", StringArgumentType.string())
					.executes(ctx -> cbpControl(ctx, "disable"))))
			.then(CommandManager.literal("enable")
				.then(CommandManager.argument("id", StringArgumentType.string())
					.executes(ctx -> cbpControl(ctx, "enable"))))
			.then(CommandManager.literal("remove")
				.then(CommandManager.argument("id", StringArgumentType.string())
					.executes(ctx -> cbpControl(ctx, "remove"))));
	}

	private static int workspaceSet(CommandContext<ServerCommandSource> context) {
		ServerCommandSource source = context.getSource();
		try {
			ServerPlayerEntity player = source.getPlayerOrThrow();
			var from = net.minecraft.command.argument.BlockPosArgumentType.getBlockPos(
				context, "from");
			var to = net.minecraft.command.argument.BlockPosArgumentType.getBlockPos(
				context, "to");
			String dimension = source.getWorld().getRegistryKey().getValue().toString();
			var refusal = SquireRuntime.get().cbpWorkspace().set(player.getUuid(),
				dimension, dev.squire.server.world.BoundedRegion.ofCorners(
					from.getX(), from.getY(), from.getZ(),
					to.getX(), to.getY(), to.getZ()));
			if (refusal.isPresent()) {
				source.sendError(Text.literal("[Squire] " + refusal.get()));
				return 0;
			}
			source.sendFeedback(() -> SquireText.msg(
				"squire.cmd.workspace_set", dimension), false);
			return 1;
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
			source.sendError(SquireText.msg("squire.cmd.players_only"));
			return 0;
		}
	}

	private static int workspaceShow(CommandContext<ServerCommandSource> context) {
		ServerCommandSource source = context.getSource();
		try {
			ServerPlayerEntity player = source.getPlayerOrThrow();
			var area = SquireRuntime.get().cbpWorkspace().areaOf(player.getUuid());
			if (area.isEmpty()) {
				source.sendFeedback(() ->
					SquireText.msg("squire.cmd.no_workspace"), false);
				return 0;
			}
			source.sendFeedback(() -> SquireText.msg("squire.cmd.workspace_show",
				area.get().dimension(), area.get().region().toString()), false);
			return 1;
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
			source.sendError(SquireText.msg("squire.cmd.players_only"));
			return 0;
		}
	}

	private static int workspaceClear(CommandContext<ServerCommandSource> context) {
		ServerCommandSource source = context.getSource();
		try {
			ServerPlayerEntity player = source.getPlayerOrThrow();
			var refusal = SquireRuntime.get().cbpWorkspace().clear(player.getUuid());
			if (refusal.isPresent()) {
				source.sendError(Text.literal("[Squire] " + refusal.get()));
				return 0;
			}
			source.sendFeedback(() ->
				SquireText.msg("squire.cmd.workspace_cleared"), false);
			return 1;
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
			source.sendError(SquireText.msg("squire.cmd.players_only"));
			return 0;
		}
	}

	private static int cbpToggle(CommandContext<ServerCommandSource> context,
			boolean enable) {
		SquireRuntime.get().setCbpEnabled(enable);
		context.getSource().sendFeedback(() -> SquireText.msg(enable
			? "squire.cmd.cbp_enabled" : "squire.cmd.cbp_disabled"), true);
		return 1;
	}

	private static int cbpPlan(CommandContext<ServerCommandSource> context) {
		ServerCommandSource source = context.getSource();
		try {
			ServerPlayerEntity player = source.getPlayerOrThrow();
			SquireRuntime rt = SquireRuntime.get();
			if (!rt.isCbpEnabled()) {
				source.sendError(SquireText.msg("squire.cmd.cbp_disabled_server"));
				return 0;
			}
			if (rt.killswitch().isActive()) {
				source.sendError(SquireText.msg("squire.cmd.killswitch_active"));
				return 0;
			}
			var pos = net.minecraft.command.argument.BlockPosArgumentType.getBlockPos(
				context, "pos").toImmutable();
			String blockType = normalizeBlockType(
				StringArgumentType.getString(context, "type"));
			boolean auto = Boolean.parseBoolean(StringArgumentType.getString(context,
				"auto"));
			String command = StringArgumentType.getString(context, "command");
			dev.squire.server.cbp.CbpSpec spec = dev.squire.server.cbp.CbpSpec
				.builder(player.getUuid(), player.getUuid(), StringArgumentType
					.getString(context, "name"))
				.entry(pos, blockType, command, auto)
				.build();
			var result = rt.cbp().plan(spec, source.getWorld().getTime());
			if (result.ok()) {
				source.sendFeedback(() -> Text.literal(result.message()), false);
				return 1;
			}
			source.sendError(Text.literal("[Squire] " + result.message()));
			return 0;
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
			source.sendError(SquireText.msg("squire.cmd.players_only"));
			return 0;
		}
	}

	private static String normalizeBlockType(String raw) {
		return switch (raw.toLowerCase(java.util.Locale.ROOT)) {
			case "chain", "chain_command_block" -> "minecraft:chain_command_block";
			case "repeating", "repeating_command_block"
					-> "minecraft:repeating_command_block";
			default -> "minecraft:command_block";
		};
	}

	private static int cbpList(CommandContext<ServerCommandSource> context) {
		var registry = SquireRuntime.get().cbpRegistry();
		java.util.List<dev.squire.server.cbp.CbpRegistry.Project> projects;
		try {
			ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
			projects = registry.ownedBy(player.getUuid());
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
			projects = registry.all(); // console sees everything
		}
		if (projects.isEmpty()) {
			context.getSource().sendFeedback(() ->
				SquireText.msg("squire.cmd.no_cbp_projects"), false);
			return 0;
		}
		for (var p : projects) {
			String line = p.name() + (p.disabled() ? " [DISABLED]" : "") + " id="
				+ p.id() + " blocks=" + p.positions().size() + " in " + p.footprint();
			context.getSource().sendFeedback(() ->
				Text.literal("[Squire] " + line), false);
		}
		return projects.size();
	}

	private static int cbpInspect(CommandContext<ServerCommandSource> context) {
		UUID id = cbpId(context);
		if (id == null) {
			return 0;
		}
		var found = SquireRuntime.get().cbpRegistry().get(id);
		if (found.isEmpty()) {
			context.getSource().sendError(SquireText.msg(
				"squire.cmd.no_such_cbp", id.toString()));
			return 0;
		}
		var p = found.get();
		StringBuilder sb = new StringBuilder("[Squire] ").append(p.name())
			.append(p.disabled() ? " [DISABLED]" : "")
			.append(" owner=").append(p.ownerId())
			.append(" dimension=").append(p.dimensionKey())
			.append(" footprint=").append(p.footprint())
			.append(" placedAt=").append(p.placedAtTick()).append("t");
		for (var pos : p.positions()) {
			sb.append("\n[Squire]   block at ").append(pos.toShortString());
		}
		String text = sb.toString();
		context.getSource().sendFeedback(() -> Text.literal(text), false);
		return 1;
	}

	private static int cbpControl(CommandContext<ServerCommandSource> context,
			String action) {
		ServerCommandSource source = context.getSource();
		UUID id = cbpId(context);
		if (id == null) {
			return 0;
		}
		try {
			ServerPlayerEntity player = source.getPlayerOrThrow();
			boolean admin = source.hasPermissionLevel(2);
			dev.squire.server.cbp.CbpMaterializer.Access access =
				switch (action) {
					case "disable" -> SquireRuntime.get().cbp().disable(id,
						player.getUuid(), admin);
					case "enable" -> SquireRuntime.get().cbp().enable(id,
						player.getUuid(), admin);
					case "remove" -> SquireRuntime.get().cbp().remove(id,
						player.getUuid(), admin);
					default -> new dev.squire.server.cbp.CbpMaterializer.Access(
						false, "unknown action");
				};
			if (access.ok()) {
				source.sendFeedback(() -> SquireText.msg(
					"squire.cmd.cbp_done", action, access.message()), false);
				return 1;
			}
			source.sendError(Text.literal("[Squire] " + access.message()));
			return 0;
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
			source.sendError(SquireText.msg("squire.cmd.players_only"));
			return 0;
		}
	}

	private static UUID cbpId(CommandContext<ServerCommandSource> context) {
		try {
			return UUID.fromString(StringArgumentType.getString(context, "id"));
		} catch (IllegalArgumentException e) {
			context.getSource().sendError(SquireText.msg("squire.cmd.bad_cbp_id"));
			return null;
		}
	}

	private static int control(CommandContext<ServerCommandSource> context,
			SquireRuntime.ControlIntent intent) {
		ServerCommandSource source = context.getSource();
		try {
			ServerPlayerEntity player = source.getPlayerOrThrow();
			SquireRuntime.ExecutionResult result =
				SquireRuntime.get().executeControl(player, intent);
			surface(source, result);
			return result.success() ? 1 : 0;
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
			source.sendError(SquireText.msg("squire.cmd.players_only"));
			return 0;
		}
	}

	private static int executeSummon(CommandContext<ServerCommandSource> context) {
		ServerCommandSource source = context.getSource();
		try {
			ServerPlayerEntity player = source.getPlayerOrThrow();
			SquireRuntime.get().summonFor(player);
			return 1;
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
			source.sendError(SquireText.msg("squire.cmd.players_only"));
			return 0;
		}
	}

	private static int executeHomeSet(CommandContext<ServerCommandSource> context) {
		ServerCommandSource source = context.getSource();
		try {
			ServerPlayerEntity player = source.getPlayerOrThrow();
			SquireRuntime.ExecutionResult result = SquireRuntime.get().setHome(player);
			surface(source, result);
			return result.success() ? 1 : 0;
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
			source.sendError(SquireText.msg("squire.cmd.players_only"));
			return 0;
		}
	}

	/** 方案 A3：{@code /squire look me}。 */
	private static int executeLookMe(CommandContext<ServerCommandSource> context) {
		ServerCommandSource source = context.getSource();
		try {
			ServerPlayerEntity player = source.getPlayerOrThrow();
			SquireRuntime.ExecutionResult result =
				SquireRuntime.get().executeLookMe(player);
			surface(source, result);
			return result.success() ? 1 : 0;
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
			source.sendError(SquireText.msg("squire.cmd.players_only"));
			return 0;
		}
	}

	/** 方案 A3：{@code /squire look <x> <y> <z>}（支持相对坐标）。 */
	private static int executeLookAt(CommandContext<ServerCommandSource> context) {
		ServerCommandSource source = context.getSource();
		try {
			ServerPlayerEntity player = source.getPlayerOrThrow();
			net.minecraft.util.math.Vec3d pos =
				net.minecraft.command.argument.Vec3ArgumentType.getVec3(context, "pos");
			SquireRuntime.ExecutionResult result = SquireRuntime.get()
				.executeLookAt(player, pos.x, pos.y, pos.z);
			surface(source, result);
			return result.success() ? 1 : 0;
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
			source.sendError(SquireText.msg("squire.cmd.players_only"));
			return 0;
		}
	}

	/** 方案 A3：{@code /squire action wave|jump|nod|shake_head}。 */
	private static int action(CommandContext<ServerCommandSource> context,
			dev.squire.api.body.EmoteType type) {
		ServerCommandSource source = context.getSource();
		try {
			ServerPlayerEntity player = source.getPlayerOrThrow();
			SquireRuntime.ExecutionResult result =
				SquireRuntime.get().executeEmote(player, type);
			surface(source, result);
			return result.success() ? 1 : 0;
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
			source.sendError(SquireText.msg("squire.cmd.players_only"));
			return 0;
		}
	}

	/**
	 * Conversational path: the ONLY place plain text reaches the LLM. With no provider
	 * configured this degrades to a clear notice — never an error, never a raw command.
	 */
	private static int executeSay(CommandContext<ServerCommandSource> context) {
		ServerCommandSource source = context.getSource();
		String text = StringArgumentType.getString(context, "text");
		try {
			ServerPlayerEntity player = source.getPlayerOrThrow();
			SquireRuntime.get().handleConversation(player, text);
			return 1;
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
			source.sendError(SquireText.msg("squire.cmd.players_only"));
			return 0;
		}
	}

	static void surface(ServerCommandSource source, SquireRuntime.ExecutionResult result) {
		if (result.success()) {
			source.sendFeedback(() -> Text.literal(result.message()), false);
		} else {
			source.sendError(Text.literal(result.message()).formatted(Formatting.RED));
		}
	}

	/**
	 * §74 alias probe: resolves free text (Chinese alias, quantity phrase or registry
	 * id) through the deterministic pipeline + registry validation.
	 */
	private static int aliasProbe(CommandContext<ServerCommandSource> context) {
		String raw = StringArgumentType.getString(context, "text");
		var resolution = SquireRuntime.get().itemAliases().resolve(raw);
		if (resolution.isEmpty()) {
			context.getSource().sendError(SquireText.msg(
				"squire.cmd.alias_missing", raw));
			return 0;
		}
		var r = resolution.get();
		context.getSource().sendFeedback(() -> SquireText.msg(
			"squire.cmd.alias_ok", raw, r.itemId(), r.count(), r.matchedBy()), false);
		return 1;
	}

	/** §76 debug replay toggle — ships OFF; writes stay local and redacted. */
	private static int replayToggle(CommandContext<ServerCommandSource> context,
			boolean enable) {
		SquireRuntime rt = SquireRuntime.get();
		rt.replay().setEnabled(enable);
		context.getSource().sendFeedback(() -> Text.literal(enable
			? "[Squire] Debug replay ON (redacted, local files under config/squire/replay)."
			: "[Squire] Debug replay OFF."), true);
		return 1;
	}
}
