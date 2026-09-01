package dev.squire.server.tool.builtin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.squire.api.body.MoveOptions;
import dev.squire.common.errors.ErrorCode;
import dev.squire.common.errors.ErrorPayload;
import dev.squire.common.protocol.ToolResult;
import dev.squire.server.body.avatar.AvatarEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import dev.squire.server.task.Task;
import dev.squire.server.task.TaskCompiler;
import dev.squire.server.task.TaskPriority;
import dev.squire.server.task.RetryPolicy;
import dev.squire.server.task.TaskScheduler;
import dev.squire.server.task.executors.CraftRecipeExecutor;
import dev.squire.server.task.executors.GatherBlockExecutor;
import dev.squire.server.task.executors.OneShotWorldExecutors;
import dev.squire.server.task.executors.RuntimeServices;
import dev.squire.server.tool.AgentPermission;
import dev.squire.server.tool.ArgDefinition;
import dev.squire.server.tool.RiskLevel;
import dev.squire.server.tool.ToolDefinition;
import dev.squire.server.tool.ToolExecutionContext;
import dev.squire.server.tool.ToolExposure;
import dev.squire.server.tool.ToolRegistry;

/**
 * The model-facing action vocabulary (spec section 25.1). Every effect-producing
 * call creates TASKS — nothing mutates the world inline from a model turn.
 * Query tools are read-only and instant.
 */
public final class BuiltinTools {

	private BuiltinTools() {
	}

	public static void register(ToolRegistry registry, RuntimeServices services,
			TaskScheduler scheduler, TaskCompiler compiler) {
		registerQueries(registry, services);
		registerNavigation(registry, services, scheduler);
		registerWorld(registry, services, scheduler);
		registerInventoryAndCrafting(registry, services, scheduler, compiler);
		registerEquipment(registry, services);
		registerContainers(registry, services, scheduler);
		registerLocationMemory(registry, services);
		registerAutomation(registry, services, scheduler);
		registerCbp(registry, services);
		registerCombatAndHealing(registry, services, scheduler);
		registerWorldEditAndCommands(registry, services, scheduler);
		// 职业成长线解锁的那些工具单独一处（§23）：这里的每一条都是「所有随从都会」，
		// 那边的每一条都要走到某一级才看得见。
		ProfessionTools.register(registry);
	}

	// ------------------------------------------------------------------ C2 装备

	private static void registerEquipment(ToolRegistry registry, RuntimeServices services) {
		registry.register(ToolDefinition.builder("inventory.equip")
			.description("Equip an item the agent already owns into its matching slot "
				+ "(weapon/tool to the main hand, armour to its own slot). The item is "
				+ "MOVED, never copied; the previous item goes back to the backpack.")
			.arg(ArgDefinition.required("itemId", ArgDefinition.ArgType.ITEM_ID,
				"item to equip; must already be in the agent inventory"))
			.arg(ArgDefinition.optional("slot", ArgDefinition.ArgType.STRING,
				"mainhand/offhand/head/chest/legs/feet — default: the item's natural slot"))
			.permission(AgentPermission.INVENTORY_WRITE)
			.risk(RiskLevel.LOW)
			// 模型必须能直接调用：玩家说"把下界合金套装穿上"时，除了这个工具没有
			// 别的路径。之前是 PLANNER_INTERNAL，而唯一的 PLANNER 调用方
			// （AutomationEngine）的工具白名单里没有它，于是这个能力对模型永远
			// 不可见，玩家看到的就是"说了没反应"。
			.exposure(ToolExposure.MODEL_PUBLIC)
			.build(), (call, ctx) -> {
				var items = ctx.avatar().items();
				String itemId = str(call.arguments().get("itemId"));
				if (itemId == null) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.INVALID_ARGUMENT, "itemId required"));
				}
				int slot = items.findSlotOf(OneShotWorldExecutors.itemId(itemId));
				if (slot < 0) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.INSUFFICIENT_ITEM, "agent does not hold " + itemId));
				}
				var target = equipmentSlotOf(str(call.arguments().get("slot")));
				var result = items.equipFromMain(slot, target);
				if (!result.success()) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						errorCodeOf(result.errorCode()), "equip failed: "
							+ result.errorCode()));
				}
				return ToolResult.success(call.callId(),
					Map.of("equipped", itemId, "slot", result.slot().getName()));
			});

		registry.register(ToolDefinition.builder("inventory.unequip")
			.description("Take an equipped item off and put it back in the backpack.")
			.arg(ArgDefinition.required("slot", ArgDefinition.ArgType.STRING,
				"mainhand/offhand/head/chest/legs/feet"))
			.permission(AgentPermission.INVENTORY_WRITE)
			.risk(RiskLevel.LOW)
			.exposure(ToolExposure.MODEL_PUBLIC)
			.build(), (call, ctx) -> {
				var target = equipmentSlotOf(str(call.arguments().get("slot")));
				if (target == null) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.INVALID_ARGUMENT, "unknown equipment slot"));
				}
				var result = ctx.avatar().items().unequip(target);
				if (!result.success()) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						errorCodeOf(result.errorCode()), "unequip failed: "
							+ result.errorCode()));
				}
				return ToolResult.success(call.callId(),
					Map.of("slot", target.getName()));
			});

		registry.register(ToolDefinition.builder("inventory.acquire_self")
			.description("Obtain an item FOR THE AGENT ITSELF by command, into its own "
				+ "backpack. Use this before inventory.equip when the agent does not "
				+ "already own the gear. Does NOT give anything to the player.")
			.arg(ArgDefinition.required("itemId", ArgDefinition.ArgType.ITEM_ID, "item"))
			.arg(ArgDefinition.intRange("count", 1, 64, "how many"))
			.permission(AgentPermission.COMMAND)
			.node(dev.squire.server.security.PermissionNodes.COMMAND_GIVE)
			.risk(RiskLevel.MEDIUM)
			.exposure(ToolExposure.MODEL_PUBLIC)
			.build(), (call, ctx) -> {
				// 这是"他先用指令给自己"的那一步。原版 /give 只能给玩家，给不了实体，
				// 所以在它脚下 summon 一份掉落物再让它自己捡起来。没有这个工具，
				// 伙伴就永远无法给自己弄到装备——"装备下界合金套装"于是毫无反应。
				try {
					var intent = new dev.squire.server.command.StructuredCommandCompiler
						.AcquireIntent(identifierOf(str(call.arguments().get("itemId"))),
							intOf(call.arguments().get("count"), 1));
					var outcome = dev.squire.server.command.StructuredCommandCompiler
						.executeAcquireForAgent(ctx.server(), ctx.avatar(), intent,
							services.protection());
					if (!outcome.success()) {
						return commandResult(call, outcome);
					}
					// 立刻收进背包，别让调用方还要等自动捡拾那一拍。
					var swept = dev.squire.server.task.executors.ContainerExecutors
						.PickupNearby.sweep(ctx.avatar(), 3.0);
					return ToolResult.success(call.callId(),
						Map.of("acquired", swept.picked()));
				} catch (IllegalArgumentException e) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.INVALID_ARGUMENT, String.valueOf(e.getMessage())));
				}
			});

		registry.register(ToolDefinition.builder("query.equipment")
			.description("Report what the agent currently wears and holds.")
			.permission(AgentPermission.INVENTORY_READ)
			.risk(RiskLevel.LOW)
			.exposure(ToolExposure.MODEL_PUBLIC)
			.build(), (call, ctx) -> {
				Map<String, Object> data = new LinkedHashMap<>();
				for (var slot : dev.squire.server.body.avatar.AvatarInventory
						.EQUIPMENT_SLOTS) {
					ItemStack stack = ctx.avatar().items().equipped(slot);
					data.put(slot.getName(), stack.isEmpty() ? null
						: net.minecraft.registry.Registries.ITEM.getId(stack.getItem())
							.toString());
				}
				return ToolResult.success(call.callId(), data);
			});
	}

	// ------------------------------------------------------------------ H 真实命令方块工程

	private static void registerCbp(ToolRegistry registry, RuntimeServices services) {
		registry.register(ToolDefinition.builder("cbp.preview_project")
			.description("Describe, block by block, what a command-block template WOULD "
				+ "build. Purely read-only: it issues no confirmation and touches "
				+ "nothing. Templates: DAY_NIGHT_CONTROLLER, PULSE_TEACHING_DEVICE.")
			.arg(ArgDefinition.required("template", ArgDefinition.ArgType.STRING,
				"template name"))
			.arg(ArgDefinition.optional("x", ArgDefinition.ArgType.INT,
				"anchor x (defaults to the workspace corner)"))
			.arg(ArgDefinition.optional("y", ArgDefinition.ArgType.INT, "anchor y"))
			.arg(ArgDefinition.optional("z", ArgDefinition.ArgType.INT, "anchor z"))
			.permission(AgentPermission.QUERY)
			.risk(RiskLevel.LOW)
			.exposure(ToolExposure.PLANNER_INTERNAL)
			.build(), (call, ctx) -> {
				var runtime = dev.squire.server.runtime.SquireRuntime.get();
				if (runtime == null) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.INTERNAL_ERROR, "runtime unavailable"));
				}
				var template = dev.squire.server.cbp.CbpPlanner.Template
					.parse(str(call.arguments().get("template")));
				if (template == null) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.INVALID_ARGUMENT, "unknown template; supported: "
							+ dev.squire.server.cbp.CbpPlanner.supportedTemplates()));
				}
				var area = runtime.cbpWorkspace().areaOf(ctx.requesterId()).orElse(null);
				var anchor = blockPosOf(call.arguments(), "x", "y", "z");
				if (anchor == null && area == null) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.PRECONDITION_FAILED,
						"no workspace set and no anchor given"));
				}
				var spec = dev.squire.server.cbp.CbpPlanner.plan(template,
					ctx.requesterId(), ctx.body().agentId(),
					template.name().toLowerCase(java.util.Locale.ROOT),
					anchor != null ? anchor : area.region().min());
				return ToolResult.success(call.callId(), Map.of(
					"template", template.name(),
					"blocks", spec.entries().size(),
					"commandBlocks", spec.commandBlockCount(),
					"preview", dev.squire.server.cbp.CbpPlanner.describe(spec,
						area == null
							? ctx.avatar().getWorld().getRegistryKey().getValue().toString()
							: area.dimension())));
			});

		registry.register(ToolDefinition.builder("cbp.plan_project")
			.description("Plan a command-block project from a whitelisted template and "
				+ "ask the player to confirm it. Writes NOTHING: it returns the "
				+ "block-by-block preview and a confirmId. Only use this when the "
				+ "player explicitly asked for real, editable command blocks.")
			.arg(ArgDefinition.required("template", ArgDefinition.ArgType.STRING,
				"template name"))
			.arg(ArgDefinition.optional("x", ArgDefinition.ArgType.INT,
				"anchor x (defaults to the workspace corner)"))
			.arg(ArgDefinition.optional("y", ArgDefinition.ArgType.INT, "anchor y"))
			.arg(ArgDefinition.optional("z", ArgDefinition.ArgType.INT, "anchor z"))
			.permission(AgentPermission.WORLD_EDIT)
			.node(dev.squire.server.security.PermissionNodes.WORLD_EDIT)
			.risk(RiskLevel.MEDIUM)
			.exposure(ToolExposure.PLANNER_INTERNAL)
			.build(), (call, ctx) -> {
				var runtime = dev.squire.server.runtime.SquireRuntime.get();
				ServerPlayerEntity owner = services.requester(ctx.requesterId());
				if (runtime == null || owner == null) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.ENTITY_NOT_FOUND, "requester offline"));
				}
				var template = dev.squire.server.cbp.CbpPlanner.Template
					.parse(str(call.arguments().get("template")));
				if (template == null) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.INVALID_ARGUMENT, "unknown template; supported: "
							+ dev.squire.server.cbp.CbpPlanner.supportedTemplates()));
				}
				var area = runtime.cbpWorkspace().areaOf(ctx.requesterId()).orElse(null);
				var anchor = blockPosOf(call.arguments(), "x", "y", "z");
				if (anchor == null && area == null) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.PRECONDITION_FAILED,
						"set a workspace first: /squire workspace set <from> <to>"));
				}
				var result = runtime.planCbp(owner, ctx.avatar(), template,
					anchor != null ? anchor : area.region().min());
				if (!result.success()) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.PRECONDITION_FAILED, result.message()));
				}
				return ToolResult.success(call.callId(), Map.of(
					"awaitingConfirmation", true, "preview", result.message()));
			});

		registry.register(ToolDefinition.builder("cbp.materialize_project")
			.description("Place a PLANNED command-block project that the player has "
				+ "already confirmed. Fails unless that exact plan was confirmed by its "
				+ "owner. This is the only tool that ever puts a command block in the "
				+ "world.")
			.arg(ArgDefinition.required("confirmId", ArgDefinition.ArgType.STRING,
				"id returned by cbp.plan_project"))
			.permission(AgentPermission.WORLD_EDIT)
			.node(dev.squire.server.security.PermissionNodes.WORLD_EDIT)
			.requiresCapability()
			.risk(RiskLevel.HIGH)
			.exposure(ToolExposure.PLANNER_INTERNAL)
			.build(), (call, ctx) -> {
				var runtime = dev.squire.server.runtime.SquireRuntime.get();
				if (runtime == null) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.INTERNAL_ERROR, "runtime unavailable"));
				}
				UUID confirmId;
				try {
					confirmId = UUID.fromString(str(call.arguments().get("confirmId")));
				} catch (RuntimeException e) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.INVALID_ARGUMENT, "confirmId must be a uuid"));
				}
				var access = runtime.cbp().materializeConfirmed(confirmId,
					ctx.requesterId(), services.currentTick());
				if (!access.ok()) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.CONFIRMATION_REQUIRED, access.message()));
				}
				return ToolResult.running(call.callId(),
					Map.of("message", access.message()));
			});
	}

	// ------------------------------------------------------------------ G 自动化

	private static void registerAutomation(ToolRegistry registry, RuntimeServices services,
			TaskScheduler scheduler) {
		// --- G2: 真实灯光开关；自动化节点唯一被允许调用的写世界工具 ---------------
		registry.register(ToolDefinition.builder("base.lights.set")
			.description("Turn the base lights on or off by flipping the REAL levers "
				+ "within a bounded radius of the base. Changes actual redstone state — "
				+ "no command blocks, and never a fake 'lit' flag.")
			.arg(ArgDefinition.required("on", ArgDefinition.ArgType.BOOLEAN,
				"true to switch the lights on, false to switch them off"))
			.arg(ArgDefinition.optional("x", ArgDefinition.ArgType.INT,
				"base x (defaults to the remembered base)"))
			.arg(ArgDefinition.optional("y", ArgDefinition.ArgType.INT, "base y"))
			.arg(ArgDefinition.optional("z", ArgDefinition.ArgType.INT, "base z"))
			.arg(ArgDefinition.intRange("radius", 1,
				dev.squire.server.task.executors.BaseLightsExecutor.MAX_RADIUS,
				"bounded scan radius for light switches"))
			.permission(AgentPermission.WORLD_PLACE)
			.risk(RiskLevel.MEDIUM)
			.exposure(ToolExposure.PLANNER_INTERNAL)
			.build(), (call, ctx) -> {
				var executor = dev.squire.server.task.executors.BaseLightsExecutor.class;
				boolean on = Boolean.TRUE.equals(call.arguments().get("on"));
				var pos = blockPosOf(call.arguments(), "x", "y", "z");
				int radius = intOf(call.arguments().get("radius"),
					dev.squire.server.task.executors.BaseLightsExecutor.DEFAULT_RADIUS);
				if (pos == null) {
					var runtime = dev.squire.server.runtime.SquireRuntime.get();
					var resolved = runtime == null ? null
						: runtime.locations().resolve(ctx.requesterId(), "基地");
					if (resolved == null || !resolved.unique()) {
						return ToolResult.failed(call.callId(), ErrorPayload.of(
							ErrorCode.ENTITY_NOT_FOUND,
							"NO_BASE_MEMORY: no unique remembered base to light"));
					}
					pos = resolved.best().pos().getPos();
				}
				String dimension = ctx.avatar().getWorld().getRegistryKey().getValue()
					.toString();
				Map<String, Object> params = new LinkedHashMap<>();
				params.put(dev.squire.server.task.executors.BaseLightsExecutor.PARAM_ON, on);
				params.put(dev.squire.server.task.executors.BaseLightsExecutor.PARAM_X,
					pos.getX());
				params.put(dev.squire.server.task.executors.BaseLightsExecutor.PARAM_Y,
					pos.getY());
				params.put(dev.squire.server.task.executors.BaseLightsExecutor.PARAM_Z,
					pos.getZ());
				params.put(dev.squire.server.task.executors.BaseLightsExecutor.PARAM_RADIUS,
					radius);
				Task task = new Task(ctx.body().agentId(), ctx.requesterId(),
					dev.squire.server.task.executors.BaseLightsExecutor.TYPE,
					TaskPriority.P3_USER_TASK,
					(on ? "lights on" : "lights off") + " at " + pos.toShortString(), null,
					dev.squire.server.task.executors.BaseLightsExecutor.lightsAre(
						services, on, dimension, pos, radius),
					200L, RetryPolicy.DEFAULT, true, "g2", params);
				scheduler.submit(task, services.currentTick());
				return ToolResult.running(call.callId(), Map.of(
					"taskId", task.taskId().toString(), "on", on,
					"executor", executor.getSimpleName()));
			});

		// --- G1: 结构化模板创建；HIGH 风险，必须由玩家确认一次 --------------------
		registry.register(ToolDefinition.builder("automation.create")
			.description("Create a long-term automation from a WHITELISTED template. "
				+ "Only 'NIGHT_LIGHTS' exists today: it lights the remembered base at "
				+ "dusk and darkens it at dawn using real levers. High risk: the player "
				+ "sees a preview and confirms once before anything is created.")
			.arg(ArgDefinition.required("template", ArgDefinition.ArgType.STRING,
				"template name; NIGHT_LIGHTS"))
			.arg(ArgDefinition.optional("x", ArgDefinition.ArgType.INT,
				"base x (defaults to the remembered base)"))
			.arg(ArgDefinition.optional("y", ArgDefinition.ArgType.INT, "base y"))
			.arg(ArgDefinition.optional("z", ArgDefinition.ArgType.INT, "base z"))
			.arg(ArgDefinition.intRange("radius", 1,
				dev.squire.server.task.executors.BaseLightsExecutor.MAX_RADIUS,
				"bounded scan radius for light switches"))
			.permission(AgentPermission.TASK_CONTROL)
			.node(dev.squire.server.security.PermissionNodes.AUTOMATION)
			.risk(RiskLevel.HIGH)
			.exposure(ToolExposure.PLANNER_INTERNAL)
			.build(), (call, ctx) -> {
				var runtime = dev.squire.server.runtime.SquireRuntime.get();
				ServerPlayerEntity owner = services.requester(ctx.requesterId());
				if (runtime == null || owner == null) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.ENTITY_NOT_FOUND, "requester offline"));
				}
				var template = dev.squire.server.automation.AutomationCompiler.Template
					.parse(str(call.arguments().get("template")));
				if (template == null) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.INVALID_ARGUMENT,
						"unknown template; supported: NIGHT_LIGHTS"));
				}
				var pos = blockPosOf(call.arguments(), "x", "y", "z");
				if (pos == null) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.INVALID_ARGUMENT,
						"the confirmed template must carry explicit base coordinates"));
				}
				// 到这里说明玩家已经确认过：真正注册图
				var result = runtime.registerNightLights(owner, pos,
					intOf(call.arguments().get("radius"),
						dev.squire.server.task.executors.BaseLightsExecutor
							.DEFAULT_RADIUS));
				if (!result.success()) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.PRECONDITION_FAILED, result.message()));
				}
				return ToolResult.success(call.callId(), Map.of(
					"created", true, "template", template.name(),
					"message", result.message()));
			});
	}

	// ------------------------------------------------------------------ E2 位置记忆

	private static void registerLocationMemory(ToolRegistry registry,
			RuntimeServices services) {
		registry.register(ToolDefinition.builder("memory.remember_location")
			.description("Remember a place by name and kind (home/warehouse/farm/mine/"
				+ "custom). Defaults to the requesting player's current position; the "
				+ "dimension is always recorded.")
			.arg(ArgDefinition.required("name", ArgDefinition.ArgType.STRING,
				"what to call this place"))
			.arg(ArgDefinition.optional("type", ArgDefinition.ArgType.STRING,
				"HOME/WAREHOUSE/FARM/MINE/CUSTOM — inferred from the name when omitted"))
			.arg(ArgDefinition.optional("x", ArgDefinition.ArgType.INT, "override x"))
			.arg(ArgDefinition.optional("y", ArgDefinition.ArgType.INT, "override y"))
			.arg(ArgDefinition.optional("z", ArgDefinition.ArgType.INT, "override z"))
			.permission(AgentPermission.TASK_CONTROL)
			.risk(RiskLevel.LOW)
			.exposure(ToolExposure.MODEL_PUBLIC)
			.build(), (call, ctx) -> {
				var runtime = dev.squire.server.runtime.SquireRuntime.get();
				ServerPlayerEntity owner = services.requester(ctx.requesterId());
				if (runtime == null || owner == null) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.ENTITY_NOT_FOUND, "requester offline"));
				}
				String name = str(call.arguments().get("name"));
				if (name == null || name.isBlank()) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.INVALID_ARGUMENT, "name required"));
				}
				String typeRaw = str(call.arguments().get("type"));
				var type = typeRaw == null
					? dev.squire.server.memory.LocationMemory.Type.fromPhrase(name)
					: parseMemoryType(typeRaw, name);
				var override = blockPosOf(call.arguments(), "x", "y", "z");
				var pos = net.minecraft.util.math.GlobalPos.create(
					((net.minecraft.server.world.ServerWorld) owner.getWorld())
						.getRegistryKey(),
					override != null ? override : owner.getBlockPos().toImmutable());
				var memory = dev.squire.server.memory.LocationMemory.explicit(
					ctx.requesterId(), ctx.body().agentId(), type, name.trim(), pos,
					services.currentTick());
				runtime.locations().remember(memory);
				return ToolResult.success(call.callId(), Map.of(
					"memoryId", memory.memoryId().toString(),
					"type", type.name(),
					"dimension", memory.dimensionId(),
					"x", pos.getPos().getX(), "y", pos.getPos().getY(),
					"z", pos.getPos().getZ()));
			});

		registry.register(ToolDefinition.builder("memory.recall_location")
			.description("Look up a remembered place by name, alias or kind — including "
				+ "phrasings like 'the last mine'. Returns candidates when ambiguous.")
			.arg(ArgDefinition.required("query", ArgDefinition.ArgType.STRING,
				"name, alias or kind of the place"))
			.permission(AgentPermission.QUERY)
			.risk(RiskLevel.LOW)
			.exposure(ToolExposure.MODEL_PUBLIC)
			.build(), (call, ctx) -> {
				var runtime = dev.squire.server.runtime.SquireRuntime.get();
				if (runtime == null) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.INTERNAL_ERROR, "runtime unavailable"));
				}
				var resolution = runtime.locations().resolve(ctx.requesterId(),
					str(call.arguments().get("query")));
				if (resolution.empty()) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.ENTITY_NOT_FOUND, "no such remembered place"));
				}
				if (resolution.ambiguous()) {
					return ToolResult.success(call.callId(), Map.of(
						"ambiguous", true,
						"candidates", resolution.candidates().stream()
							.map(BuiltinTools::memoryData).toList()));
				}
				var memory = resolution.best();
				runtime.locations().update(memory.visited(services.currentTick()));
				return ToolResult.success(call.callId(), memoryData(memory));
			});

		registry.register(ToolDefinition.builder("memory.list_locations")
			.description("List every place remembered for the requesting player.")
			.permission(AgentPermission.QUERY)
			.risk(RiskLevel.LOW)
			.exposure(ToolExposure.MODEL_PUBLIC)
			.build(), (call, ctx) -> {
				var runtime = dev.squire.server.runtime.SquireRuntime.get();
				if (runtime == null) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.INTERNAL_ERROR, "runtime unavailable"));
				}
				return ToolResult.success(call.callId(), Map.of("locations",
					runtime.locations().ofOwner(ctx.requesterId()).stream()
						.map(BuiltinTools::memoryData).toList()));
			});

		registry.register(ToolDefinition.builder("memory.forget_location")
			.description("Delete one remembered place. Only its owner (or an admin) may.")
			.arg(ArgDefinition.required("memoryId", ArgDefinition.ArgType.STRING,
				"id from memory.list_locations"))
			.permission(AgentPermission.TASK_CONTROL)
			.risk(RiskLevel.MEDIUM)
			.exposure(ToolExposure.MODEL_PUBLIC)
			.build(), (call, ctx) -> {
				var runtime = dev.squire.server.runtime.SquireRuntime.get();
				ServerPlayerEntity owner = services.requester(ctx.requesterId());
				if (runtime == null || owner == null) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.ENTITY_NOT_FOUND, "requester offline"));
				}
				UUID memoryId;
				try {
					memoryId = UUID.fromString(str(call.arguments().get("memoryId")));
				} catch (RuntimeException e) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.INVALID_ARGUMENT, "memoryId must be a uuid"));
				}
				boolean removed = runtime.locations().forget(ctx.requesterId(),
					owner.hasPermissionLevel(2), memoryId);
				return removed
					? ToolResult.success(call.callId(), Map.of("forgotten", true))
					: ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.PERMISSION_DENIED,
						"unknown memory, or it belongs to someone else"));
			});

		registry.register(ToolDefinition.builder("memory.set_home")
			.description("Set the companion's home to the requesting player's current "
				+ "position, dimension included.")
			.permission(AgentPermission.TASK_CONTROL)
			.risk(RiskLevel.LOW)
			.exposure(ToolExposure.MODEL_PUBLIC)
			.build(), (call, ctx) -> {
				var runtime = dev.squire.server.runtime.SquireRuntime.get();
				ServerPlayerEntity owner = services.requester(ctx.requesterId());
				if (runtime == null || owner == null) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.ENTITY_NOT_FOUND, "requester offline"));
				}
				var result = runtime.setHome(owner);
				return result.success()
					? ToolResult.success(call.callId(), Map.of("message", result.message()))
					: ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.PRECONDITION_FAILED, result.message()));
			});

		registry.register(ToolDefinition.builder("memory.register_container")
			.description("Bind a real, verified container to a remembered place so "
				+ "storage tasks know exactly where to put things.")
			.arg(ArgDefinition.required("memoryId", ArgDefinition.ArgType.STRING,
				"id from memory.list_locations"))
			.arg(ArgDefinition.required("x", ArgDefinition.ArgType.INT, "container x"))
			.arg(ArgDefinition.required("y", ArgDefinition.ArgType.INT, "container y"))
			.arg(ArgDefinition.required("z", ArgDefinition.ArgType.INT, "container z"))
			.permission(AgentPermission.TASK_CONTROL)
			.risk(RiskLevel.MEDIUM)
			.exposure(ToolExposure.MODEL_PUBLIC)
			.build(), (call, ctx) -> {
				var runtime = dev.squire.server.runtime.SquireRuntime.get();
				if (runtime == null) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.INTERNAL_ERROR, "runtime unavailable"));
				}
				var pos = blockPosOf(call.arguments(), "x", "y", "z");
				if (pos == null) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.INVALID_ARGUMENT, "x/y/z required"));
				}
				UUID memoryId;
				try {
					memoryId = UUID.fromString(str(call.arguments().get("memoryId")));
				} catch (RuntimeException e) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.INVALID_ARGUMENT, "memoryId must be a uuid"));
				}
				var memory = runtime.locations().byId(memoryId).orElse(null);
				if (memory == null || !memory.ownerId().equals(ctx.requesterId())) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.PERMISSION_DENIED,
						"unknown memory, or it belongs to someone else"));
				}
				// 绑定前必须真的验证过：不存在的容器不能写进长期记忆
				var world = (net.minecraft.server.world.ServerWorld) ctx.avatar().getWorld();
				var resolved = dev.squire.server.world.ContainerAccess.resolve(world, pos,
					ctx.avatar().getPos(), ctx.avatar().ownerId(), services.protection(),
					false);
				if (!resolved.ok()) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						errorCodeOf(resolved.errorCode()), String.valueOf(
							resolved.detail())));
				}
				runtime.locations().update(memory.withContainer(
					net.minecraft.util.math.GlobalPos.create(world.getRegistryKey(),
						pos.toImmutable()), services.currentTick()));
				return ToolResult.success(call.callId(), Map.of(
					"memoryId", memoryId.toString(), "container", resolved.typeId()));
			});
	}

	private static Map<String, Object> memoryData(
			dev.squire.server.memory.LocationMemory memory) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("memoryId", memory.memoryId().toString());
		data.put("name", memory.canonicalName());
		data.put("type", memory.type().name());
		data.put("dimension", memory.dimensionId());
		data.put("x", memory.pos().getPos().getX());
		data.put("y", memory.pos().getPos().getY());
		data.put("z", memory.pos().getPos().getZ());
		data.put("source", memory.source().name());
		data.put("lastVisitedAt", memory.lastVisitedAt());
		if (memory.containerPos() != null) {
			data.put("containerX", memory.containerPos().getPos().getX());
			data.put("containerY", memory.containerPos().getPos().getY());
			data.put("containerZ", memory.containerPos().getPos().getZ());
		}
		return data;
	}

	private static dev.squire.server.memory.LocationMemory.Type parseMemoryType(String raw,
			String fallbackPhrase) {
		try {
			return dev.squire.server.memory.LocationMemory.Type.valueOf(
				raw.trim().toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return dev.squire.server.memory.LocationMemory.Type.fromPhrase(fallbackPhrase);
		}
	}

	// ------------------------------------------------------------------ C3 容器物流

	private static void registerContainers(ToolRegistry registry, RuntimeServices services,
			TaskScheduler scheduler) {
		registry.register(ToolDefinition.builder("container.inspect")
			.description("Read the contents of a chest/barrel/furnace within reach. "
				+ "Read-only and instant.")
			.arg(ArgDefinition.required("x", ArgDefinition.ArgType.INT, "container x"))
			.arg(ArgDefinition.required("y", ArgDefinition.ArgType.INT, "container y"))
			.arg(ArgDefinition.required("z", ArgDefinition.ArgType.INT, "container z"))
			.permission(AgentPermission.INVENTORY_READ)
			.risk(RiskLevel.LOW)
			.exposure(ToolExposure.MODEL_PUBLIC)
			.build(), (call, ctx) -> {
				var pos = blockPosOf(call.arguments(), "x", "y", "z");
				if (pos == null) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.INVALID_ARGUMENT, "x/y/z required"));
				}
				var world = (net.minecraft.server.world.ServerWorld) ctx.avatar().getWorld();
				var resolved = dev.squire.server.world.ContainerAccess.resolve(world, pos,
					ctx.avatar().getPos(), ctx.avatar().ownerId(), services.protection(),
					false);
				if (!resolved.ok()) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						errorCodeOf(resolved.errorCode()), String.valueOf(
							resolved.detail())));
				}
				Map<String, Object> data = new LinkedHashMap<>();
				data.put("type", resolved.typeId());
				data.put("items", dev.squire.server.world.ContainerAccess
					.contents(resolved.inventory()));
				data.put("freeSlots", dev.squire.server.world.ContainerAccess
					.freeSlots(resolved.inventory()));
				return ToolResult.success(call.callId(), data);
			});

		registerContainerTask(registry, services, scheduler, "container.deposit",
			dev.squire.server.task.executors.ContainerExecutors.Deposit.TYPE,
			"Put items from the agent backpack into a container. Omit itemId to deposit "
				+ "everything, or pass filter=ORES for ores/ingots only.", false);
		registerContainerTask(registry, services, scheduler, "container.withdraw",
			dev.squire.server.task.executors.ContainerExecutors.Withdraw.TYPE,
			"Take items out of a container into the agent backpack.", false);
		registerContainerTask(registry, services, scheduler, "container.transfer",
			dev.squire.server.task.executors.ContainerExecutors.Transfer.TYPE,
			"Move items straight from one container into another; both must be reachable.",
			true);

		registry.register(ToolDefinition.builder("container.sort")
			.description("Tidy a chest/barrel: merge equal stacks and order them by "
				+ "category. Item NBT is preserved and nothing is ever discarded.")
			.arg(ArgDefinition.required("x", ArgDefinition.ArgType.INT, "container x"))
			.arg(ArgDefinition.required("y", ArgDefinition.ArgType.INT, "container y"))
			.arg(ArgDefinition.required("z", ArgDefinition.ArgType.INT, "container z"))
			.permission(AgentPermission.INVENTORY_WRITE)
			.risk(RiskLevel.MEDIUM)
			.exposure(ToolExposure.PLANNER_INTERNAL)
			.build(), (call, ctx) -> submitContainerTask(call, ctx, scheduler, services,
				dev.squire.server.task.executors.ContainerExecutors.Sort.TYPE,
				"sort container", false));

		registry.register(ToolDefinition.builder("inventory.pickup_nearby")
			.description("Pick up loose item drops around the agent into its backpack.")
			.arg(ArgDefinition.intRange("radius", 1, 16, "pickup radius in blocks"))
			.permission(AgentPermission.INVENTORY_WRITE)
			.risk(RiskLevel.LOW)
			.exposure(ToolExposure.PLANNER_INTERNAL)
			.build(), (call, ctx) -> {
				Map<String, Object> params = new LinkedHashMap<>();
				Object radius = call.arguments().get("radius");
				double effectiveRadius = radius instanceof Number n ? n.intValue() : 6.0;
				if (radius instanceof Number n) {
					params.put(dev.squire.server.task.executors.ContainerExecutors
						.PickupNearby.PARAM_RADIUS, n.intValue());
				}
				Task task = new Task(ctx.body().agentId(), ctx.requesterId(),
					dev.squire.server.task.executors.ContainerExecutors.PickupNearby.TYPE,
					TaskPriority.P3_USER_TASK, "pick up nearby drops", null,
					dev.squire.server.task.executors.ContainerExecutors.noDropsNearby(
						services, ctx.body().agentId(), effectiveRadius),
					200L, RetryPolicy.DEFAULT, true, "c3", params);
				scheduler.submit(task, services.currentTick());
				return ToolResult.running(call.callId(),
					Map.of("taskId", task.taskId().toString()));
			});
	}

	private static void registerContainerTask(ToolRegistry registry,
			RuntimeServices services, TaskScheduler scheduler, String toolName,
			String taskType, String description, boolean twoContainers) {
		var builder = ToolDefinition.builder(toolName)
			.description(description)
			.arg(ArgDefinition.required("x", ArgDefinition.ArgType.INT, "container x"))
			.arg(ArgDefinition.required("y", ArgDefinition.ArgType.INT, "container y"))
			.arg(ArgDefinition.required("z", ArgDefinition.ArgType.INT, "container z"))
			.arg(ArgDefinition.optional("itemId", ArgDefinition.ArgType.ITEM_ID,
				"item to move; omit for every matching item"))
			.arg(ArgDefinition.intRange("count", 1, 4096, "how many to move"))
			.arg(ArgDefinition.optional("filter", ArgDefinition.ArgType.STRING,
				"ALL or ORES when itemId is omitted"));
		if (twoContainers) {
			builder.arg(ArgDefinition.required("toX", ArgDefinition.ArgType.INT,
					"destination container x"))
				.arg(ArgDefinition.required("toY", ArgDefinition.ArgType.INT,
					"destination container y"))
				.arg(ArgDefinition.required("toZ", ArgDefinition.ArgType.INT,
					"destination container z"));
		}
		registry.register(builder
			.permission(AgentPermission.INVENTORY_WRITE)
			.risk(RiskLevel.MEDIUM)
			.exposure(ToolExposure.PLANNER_INTERNAL)
			.build(), (call, ctx) -> submitContainerTask(call, ctx, scheduler, services,
				taskType, toolName, twoContainers));
	}

	private static ToolResult submitContainerTask(dev.squire.common.protocol.ToolCall call,
			ToolExecutionContext ctx, TaskScheduler scheduler, RuntimeServices services,
			String taskType, String goal, boolean twoContainers) {
		var pos = blockPosOf(call.arguments(), "x", "y", "z");
		if (pos == null) {
			return ToolResult.failed(call.callId(), ErrorPayload.of(
				ErrorCode.INVALID_ARGUMENT, "x/y/z required"));
		}
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("x", pos.getX());
		params.put("y", pos.getY());
		params.put("z", pos.getZ());
		copyIfPresent(call.arguments(), params, "itemId", "count", "filter");
		net.minecraft.util.math.BlockPos destination = pos;
		if (twoContainers) {
			var to = blockPosOf(call.arguments(), "toX", "toY", "toZ");
			if (to == null) {
				return ToolResult.failed(call.callId(), ErrorPayload.of(
					ErrorCode.INVALID_ARGUMENT, "toX/toY/toZ required"));
			}
			params.put("toX", to.getX());
			params.put("toY", to.getY());
			params.put("toZ", to.getZ());
			destination = to;
		}
		// 没有可校验的最终世界状态就不该提交任务——否则它永远停在 VERIFYING
		var success = containerSuccessCondition(ctx, services, taskType, pos, destination,
			str(call.arguments().get("itemId")), str(call.arguments().get("filter")),
			intOrNull(call.arguments().get("count")));
		if (success == null) {
			return ToolResult.failed(call.callId(), ErrorPayload.of(
				ErrorCode.PRECONDITION_FAILED,
				"cannot verify this container operation; refusing to start it"));
		}
		Task task = new Task(ctx.body().agentId(), ctx.requesterId(), taskType,
			TaskPriority.P3_USER_TASK, goal + " at " + pos.toShortString(), null, success,
			1200L, RetryPolicy.DEFAULT, true, "c3", params);
		scheduler.submit(task, services.currentTick());
		return ToolResult.running(call.callId(), Map.of("taskId", task.taskId().toString()));
	}

	/**
	 * Baseline-anchored goal conditions for the container tasks (方案 C3). The baseline
	 * is read HERE, at dispatch time, so the verifier compares real before/after world
	 * state instead of trusting the executor's own bookkeeping.
	 */
	private static dev.squire.server.task.TaskCondition containerSuccessCondition(
			ToolExecutionContext ctx, RuntimeServices services, String taskType,
			net.minecraft.util.math.BlockPos source,
			net.minecraft.util.math.BlockPos destination, String itemId, String filterMode,
			Integer count) {
		var executors = dev.squire.server.task.executors.ContainerExecutors.class;
		String dimension = ctx.avatar().getWorld().getRegistryKey().getValue().toString();
		var filter = dev.squire.server.task.executors.ContainerExecutors
			.itemFilter(itemId, filterMode);
		String describe = itemId != null ? itemId
			: ("ORES".equalsIgnoreCase(filterMode) ? "ores" : "items");
		var world = (net.minecraft.server.world.ServerWorld) ctx.avatar().getWorld();

		if (taskType.equals(dev.squire.server.task.executors.ContainerExecutors
				.Deposit.TYPE)) {
			int carried = ctx.avatar().items().countMatching(filter);
			if (carried <= 0) {
				return null; // nothing to deposit: refuse rather than hang in VERIFYING
			}
			int moving = count == null ? carried : Math.min(count, carried);
			int baseline = containerCount(services, world, source, filter);
			return dev.squire.server.task.executors.ContainerExecutors
				.containerHoldsAtLeast(services, dimension, source, filter, describe,
					baseline + moving);
		}
		if (taskType.equals(dev.squire.server.task.executors.ContainerExecutors
				.Withdraw.TYPE)) {
			int available = containerCount(services, world, source, filter);
			if (available <= 0) {
				return null;
			}
			int moving = count == null ? available : Math.min(count, available);
			int baseline = ctx.avatar().items().countMatching(filter);
			return dev.squire.server.task.executors.ContainerExecutors
				.avatarHoldsAtLeast(services, ctx.body().agentId(), filter, describe,
					baseline + moving);
		}
		if (taskType.equals(dev.squire.server.task.executors.ContainerExecutors
				.Transfer.TYPE)) {
			int available = containerCount(services, world, source, filter);
			if (available <= 0) {
				return null;
			}
			int moving = count == null ? available : Math.min(count, available);
			int baseline = containerCount(services, world, destination, filter);
			return dev.squire.server.task.executors.ContainerExecutors
				.containerHoldsAtLeast(services, dimension, destination, filter, describe,
					baseline + moving);
		}
		if (taskType.equals(dev.squire.server.task.executors.ContainerExecutors
				.Sort.TYPE)) {
			var resolved = dev.squire.server.world.ContainerAccess.resolve(world, source,
				ctx.avatar().getPos(), ctx.avatar().ownerId(), services.protection(), false);
			if (!resolved.ok()) {
				return null;
			}
			var expected = dev.squire.server.world.ContainerAccess
				.contents(resolved.inventory());
			int occupied = resolved.inventory().size()
				- dev.squire.server.world.ContainerAccess.freeSlots(resolved.inventory());
			return dev.squire.server.task.executors.ContainerExecutors.sortedWithoutLoss(
				services, dimension, source, expected, occupied);
		}
		throw new IllegalStateException("no verifier wired for " + taskType
			+ " (" + executors.getSimpleName() + ")");
	}

	private static int containerCount(RuntimeServices services,
			net.minecraft.server.world.ServerWorld world,
			net.minecraft.util.math.BlockPos pos,
			java.util.function.Predicate<net.minecraft.item.ItemStack> filter) {
		var resolved = dev.squire.server.world.ContainerAccess.resolve(world, pos, null,
			null, services.protection(), false);
		return resolved.ok()
			? dev.squire.server.world.ContainerAccess.count(resolved.inventory(), filter)
			: 0;
	}

	private static void copyIfPresent(Map<String, Object> from, Map<String, Object> to,
			String... keys) {
		for (String key : keys) {
			Object value = from.get(key);
			if (value != null) {
				to.put(key, value);
			}
		}
	}

	private static net.minecraft.util.math.BlockPos blockPosOf(Map<String, Object> args,
			String kx, String ky, String kz) {
		Integer x = intOrNull(args.get(kx));
		Integer y = intOrNull(args.get(ky));
		Integer z = intOrNull(args.get(kz));
		return x == null || y == null || z == null ? null
			: new net.minecraft.util.math.BlockPos(x, y, z);
	}

	private static net.minecraft.entity.EquipmentSlot equipmentSlotOf(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		for (var slot : dev.squire.server.body.avatar.AvatarInventory.EQUIPMENT_SLOTS) {
			if (slot.getName().equalsIgnoreCase(raw)) {
				return slot;
			}
		}
		return null;
	}

	/** Executor-level string codes → the unified enum surfaced to model and audit. */
	private static ErrorCode errorCodeOf(String raw) {
		if (raw == null) {
			return ErrorCode.INTERNAL_ERROR;
		}
		try {
			return ErrorCode.valueOf(raw);
		} catch (IllegalArgumentException e) {
			return ErrorCode.INTERNAL_ERROR;
		}
	}

	// ------------------------------------------------------------------ M3 world edit & structured commands

	private static void registerWorldEditAndCommands(ToolRegistry registry,
			RuntimeServices services, TaskScheduler scheduler) {
		// --- L3: bulk fill through WorldEditor (spec §45) -----------------------
		registry.register(ToolDefinition.builder("minecraft.command.fill")
			.description("Compile and execute vanilla /fill for one bounded region and "
				+ "one registry-validated block type. High risk: owner confirmation and "
				+ "region-protection checks are mandatory.")
			.arg(ArgDefinition.intRange("x1", -30_000_000, 30_000_000, "corner 1 x"))
			.arg(ArgDefinition.intRange("y1", -2048, 2048, "corner 1 y"))
			.arg(ArgDefinition.intRange("z1", -30_000_000, 30_000_000, "corner 1 z"))
			.arg(ArgDefinition.intRange("x2", -30_000_000, 30_000_000, "corner 2 x"))
			.arg(ArgDefinition.intRange("y2", -2048, 2048, "corner 2 y"))
			.arg(ArgDefinition.intRange("z2", -30_000_000, 30_000_000, "corner 2 z"))
			.arg(ArgDefinition.required("blockId", ArgDefinition.ArgType.BLOCK_ID,
				"block to fill with (default state)"))
			.permission(AgentPermission.WORLD_EDIT)
			.node(dev.squire.server.security.PermissionNodes.WORLD_EDIT)
			.requiresCapability()
			.risk(RiskLevel.HIGH)
			.exposure(ToolExposure.MODEL_PUBLIC)
			.build(), (call, ctx) -> {
				try {
					var region = dev.squire.server.world.BoundedRegion.ofCorners(
						intOf(call.arguments().get("x1"), 0),
						intOf(call.arguments().get("y1"), 0),
						intOf(call.arguments().get("z1"), 0),
						intOf(call.arguments().get("x2"), 0),
						intOf(call.arguments().get("y2"), 0),
						intOf(call.arguments().get("z2"), 0));
					var outcome = dev.squire.server.command.StructuredCommandCompiler
						.executeFill(ctx.server(), ctx.avatar(), ctx.requesterId(),
							services.protection(),
							new dev.squire.server.command.StructuredCommandCompiler.FillIntent(
								region, identifierOf(str(call.arguments().get("blockId")))));
					return commandResult(call, outcome, true);
				} catch (IllegalArgumentException e) {
					return invalidCommandArgument(call, e);
				}
			});

		// --- F2: 区域来自 server 拥有的选区，模型永远不写坐标 --------------------
		registry.register(ToolDefinition.builder("worldedit.propose_fill_selection")
			.description("PROPOSE filling the requesting player's current selection with "
				+ "one block. The region comes from the server-owned selection "
				+ "(/squire selection), never from coordinates you choose. This writes "
				+ "NOTHING: it shows the player a preview and returns a confirmId they "
				+ "confirm with /squire confirm. Do not call it twice for one request.")
			.arg(ArgDefinition.required("blockId", ArgDefinition.ArgType.BLOCK_ID,
				"block to fill the selection with"))
			.permission(AgentPermission.WORLD_EDIT)
			.node(dev.squire.server.security.PermissionNodes.WORLD_EDIT)
			.risk(RiskLevel.MEDIUM)
			.exposure(ToolExposure.MODEL_PUBLIC)
			.build(), (call, ctx) -> {
				var runtime = dev.squire.server.runtime.SquireRuntime.get();
				ServerPlayerEntity owner = services.requester(ctx.requesterId());
				if (runtime == null || owner == null) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.ENTITY_NOT_FOUND, "requester offline"));
				}
				var result = runtime.fillSelection(owner, str(call.arguments().get("blockId")));
				if (!result.success()) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.PRECONDITION_FAILED, result.message()));
				}
				// 提案本身不写世界。真正的写入是 minecraft.command.fill，它是 HIGH
				// 风险并且只会在玩家确认这条 PendingOperation 之后由服务器重放一次。
				var pending = runtime.pendingOperations()
					.pendingFor(ctx.requesterId(), services.currentTick());
				String confirmId = pending.isEmpty() ? null
					: pending.get(pending.size() - 1).confirmId().toString();
				Map<String, Object> data = new LinkedHashMap<>();
				data.put("awaitingConfirmation", true);
				data.put("preview", result.message());
				if (confirmId != null) {
					data.put("confirmId", confirmId);
				}
				return ToolResult.success(call.callId(), data);
			});

		registry.register(ToolDefinition.builder("minecraft.command.setblock")
			.description("Compile and execute vanilla /setblock for one cell and one "
				+ "registry-validated block type. High risk: owner confirmation and "
				+ "region-protection checks are mandatory.")
			.arg(ArgDefinition.intRange("x", -30_000_000, 30_000_000, "cell x"))
			.arg(ArgDefinition.intRange("y", -2048, 2048, "cell y"))
			.arg(ArgDefinition.intRange("z", -30_000_000, 30_000_000, "cell z"))
			.arg(ArgDefinition.required("blockId", ArgDefinition.ArgType.BLOCK_ID,
				"block to set (default state)"))
			.permission(AgentPermission.WORLD_EDIT)
			.node(dev.squire.server.security.PermissionNodes.WORLD_EDIT)
			.requiresCapability()
			.risk(RiskLevel.HIGH)
			.exposure(ToolExposure.MODEL_PUBLIC)
			.build(), (call, ctx) -> {
				try {
					var pos = new net.minecraft.util.math.BlockPos(
						intOf(call.arguments().get("x"), 0),
						intOf(call.arguments().get("y"), 0),
						intOf(call.arguments().get("z"), 0));
					var outcome = dev.squire.server.command.StructuredCommandCompiler
						.executeSetBlock(ctx.server(), ctx.avatar(), ctx.requesterId(),
							services.protection(),
							new dev.squire.server.command.StructuredCommandCompiler.SetBlockIntent(
								pos, identifierOf(str(call.arguments().get("blockId")))));
					return commandResult(call, outcome, true);
				} catch (IllegalArgumentException e) {
					return invalidCommandArgument(call, e);
				}
			});

		// --- L2: structured admin commands (spec §46/47) ------------------------
		registry.register(ToolDefinition.builder("minecraft.command.give")
			.description("Give the requesting player an item in bounded quantity. "
				+ "Target is resolved server-side; selectors are impossible.")
			.arg(ArgDefinition.required("itemId", ArgDefinition.ArgType.ITEM_ID, "item"))
			.arg(ArgDefinition.intRange("count", 1, 4096, "how many"))
			.permission(AgentPermission.COMMAND)
			.node(dev.squire.server.security.PermissionNodes.COMMAND_GIVE)
			.risk(RiskLevel.MEDIUM)
			.exposure(ToolExposure.MODEL_PUBLIC)
			.build(), (call, ctx) -> {
				ServerPlayerEntity target = services.requester(ctx.requesterId());
				if (target == null) {
					return ToolResult.failed(call.callId(), dev.squire.common.errors
						.ErrorPayload.of(dev.squire.common.errors.ErrorCode.ENTITY_NOT_FOUND,
							"requester offline"));
				}
				try {
					var outcome = dev.squire.server.command.StructuredCommandCompiler
						.executeGive(ctx.server(), target,
							new dev.squire.server.command.StructuredCommandCompiler.GiveIntent(
								identifierOf(str(call.arguments().get("itemId"))),
								intOf(call.arguments().get("count"), 1)),
							services.protection());
					return commandResult(call, outcome);
				} catch (IllegalArgumentException e) {
					return invalidCommandArgument(call, e);
				}
			});

		// 模型这边的"要东西"入口。和 FastPath 的槽位解析产出同一个 ItemRequest、
		// 走同一个执行器：以前模型只有 minecraft.command.give，连 enchanted 参数都
		// 没有，所以「满配附魔的下界合金套装」它就算完全理解了也表达不出来——
		// 只能拆成四次白板 give。能力清单必须与实现对齐。
		registry.register(ToolDefinition.builder("items.fulfill")
			.description("Fulfil a gear/item request in ONE call. Use this - not "
				+ "minecraft.command.give - whenever the player asks for armour, tools, "
				+ "weapons, a WHOLE ARMOUR SET, or anything ENCHANTED. Set shape="
				+ "armor_set with a material to deliver all four pieces at once; set "
				+ "enchant=max for '满配附魔 / fully enchanted' (the server derives the "
				+ "actual enchantments - never pass NBT). target=agent makes the "
				+ "companion WEAR it instead of handing it to the player.")
			.arg(ArgDefinition.optional("target", ArgDefinition.ArgType.STRING,
				"'player' (default, the companion carries it over and drops it at your "
					+ "feet) or 'agent' (the companion equips it itself)"))
			.arg(ArgDefinition.optional("shape", ArgDefinition.ArgType.STRING,
				"'armor_set' for a full four-piece set, 'item' (default) for one item"))
			.arg(ArgDefinition.optional("material", ArgDefinition.ArgType.STRING,
				"armour material for shape=armor_set: netherite / diamond / iron / "
					+ "golden / chainmail / leather"))
			.arg(ArgDefinition.optional("itemId", ArgDefinition.ArgType.ITEM_ID,
				"item for shape=item, e.g. minecraft:netherite_helmet"))
			.arg(ArgDefinition.optional("count", ArgDefinition.ArgType.INT,
				"how many items (or how many sets); default 1"))
			.arg(ArgDefinition.optional("enchant", ArgDefinition.ArgType.STRING,
				"'max' for fully enchanted, 'none' (default) for plain"))
			.permission(AgentPermission.COMMAND)
			.node(dev.squire.server.security.PermissionNodes.COMMAND_GIVE)
			.risk(RiskLevel.MEDIUM)
			.exposure(ToolExposure.MODEL_PUBLIC)
			.build(), (call, ctx) -> {
				if (!dev.squire.server.runtime.SquireRuntime.isAlive()) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.INTERNAL_ERROR, "runtime unavailable"));
				}
				var runtime = dev.squire.server.runtime.SquireRuntime.get();
				ServerPlayerEntity owner = services.requester(ctx.requesterId());
				if (owner == null) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.ENTITY_NOT_FOUND, "requester offline"));
				}
				var request = buildConjureOperation(call.arguments(), runtime.vocabulary());
				if (request.isEmpty()) {
					// 参数凑不出一个能执行的请求时说清楚缺什么，别让模型盲目重试。
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.INVALID_ARGUMENT,
						"need either shape=armor_set with a known material, or "
							+ "shape=item with a registry itemId"));
				}
				var result = runtime.runItemOperation(owner, request.get());
				return result.success()
					? ToolResult.running(call.callId(), Map.of("report", result.message()))
					: ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.PRECONDITION_FAILED, result.message()));
			});

		// 改动玩家<b>已经有</b>的物品。这一整类能力以前根本不存在：全仓库唯一碰玩家
		// 物品的地方是交付验收的 count()，模型手上只有"凭空造一件新的"。于是
		// 「取消我套装的荆棘附魔」不是解析失败，是没有任何工具能表达它。
		registry.register(ToolDefinition.builder("items.edit")
			.description("Modify items the player (or the companion) ALREADY OWNS: "
				+ "remove/add/clear enchantments, or repair durability. Use this - never "
				+ "items.fulfill - when the player says 取消/去掉/清除/加上 an enchantment "
				+ "on gear they are wearing or holding. The companion walks over and edits "
				+ "in place; the change is undoable. Removing ONE named enchantment is "
				+ "possible here and only here (a vanilla grindstone would strip all).")
			.arg(ArgDefinition.optional("scope", ArgDefinition.ArgType.STRING,
				"where the items are: 'player_equipped' (default, the armour they wear), "
					+ "'player_held' (main hand), 'player_inventory', 'agent_equipped'"))
			.arg(ArgDefinition.optional("selector", ArgDefinition.ArgType.STRING,
				"which items in that scope: 'armor' (default), 'held', 'all', or 'item' "
					+ "together with itemId"))
			.arg(ArgDefinition.optional("itemId", ArgDefinition.ArgType.ITEM_ID,
				"required only when selector=item"))
			.arg(ArgDefinition.required("transform", ArgDefinition.ArgType.STRING,
				"'remove_enchant' / 'add_enchant' (both need enchantments), "
					+ "'clear_enchants', 'set_max_enchants', 'repair'"))
			.arg(ArgDefinition.optional("enchantments", ArgDefinition.ArgType.STRING,
				"comma-separated enchantment ids or Chinese names, e.g. "
					+ "'minecraft:thorns' or '荆棘,保护'"))
			.permission(AgentPermission.COMMAND)
			.node(dev.squire.server.security.PermissionNodes.COMMAND_GIVE)
			.risk(RiskLevel.MEDIUM)
			.exposure(ToolExposure.MODEL_PUBLIC)
			.build(), (call, ctx) -> {
				if (!dev.squire.server.runtime.SquireRuntime.isAlive()) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.INTERNAL_ERROR, "runtime unavailable"));
				}
				var runtime = dev.squire.server.runtime.SquireRuntime.get();
				ServerPlayerEntity owner = services.requester(ctx.requesterId());
				if (owner == null) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.ENTITY_NOT_FOUND, "requester offline"));
				}
				var operation = buildEditOperation(call.arguments(), runtime.vocabulary());
				if (operation.isEmpty()) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.INVALID_ARGUMENT,
						"unknown transform, or remove_enchant/add_enchant without a "
							+ "resolvable enchantment name"));
				}
				var result = runtime.runItemOperation(owner, operation.get());
				return result.success()
					? ToolResult.running(call.callId(), Map.of("report", result.message()))
					: ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.PRECONDITION_FAILED, result.message()));
			});

		registry.register(ToolDefinition.builder("minecraft.command.teleport")
			.description("Teleport THE AGENT to a bounded position.")
			.arg(ArgDefinition.required("x", ArgDefinition.ArgType.INT, "target x"))
			.arg(ArgDefinition.required("y", ArgDefinition.ArgType.INT, "target y"))
			.arg(ArgDefinition.required("z", ArgDefinition.ArgType.INT, "target z"))
			.permission(AgentPermission.COMMAND)
			.node(dev.squire.server.security.PermissionNodes.COMMAND_TELEPORT)
			.risk(RiskLevel.MEDIUM)
			.exposure(ToolExposure.MODEL_PUBLIC)
			.build(), (call, ctx) -> {
				double x = num(call.arguments().get("x"));
				double y = num(call.arguments().get("y"));
				double z = num(call.arguments().get("z"));
				var outcome = dev.squire.server.command.StructuredCommandCompiler
					.executeAgentTeleport(ctx.avatar(), x, y, z, ctx.requesterId(),
						services.protection());
				return commandResult(call, outcome);
			});

		registry.register(ToolDefinition.builder("minecraft.command.effect")
			.description("Apply a whitelisted beneficial effect to the requesting player.")
			.arg(ArgDefinition.required("effectId", ArgDefinition.ArgType.STRING,
				"e.g. minecraft:speed — whitelist enforced"))
			.arg(ArgDefinition.intRange("durationTicks", 1, 72000, "effect duration"))
			.arg(ArgDefinition.intRange("amplifier", 0, 4, "amplifier level"))
			.permission(AgentPermission.COMMAND)
			.node(dev.squire.server.security.PermissionNodes.COMMAND_EFFECT)
			.risk(RiskLevel.MEDIUM)
			.exposure(ToolExposure.MODEL_PUBLIC)
			.build(), (call, ctx) -> {
				ServerPlayerEntity target = services.requester(ctx.requesterId());
				if (target == null) {
					return ToolResult.failed(call.callId(), dev.squire.common.errors
						.ErrorPayload.of(dev.squire.common.errors.ErrorCode.ENTITY_NOT_FOUND,
							"requester offline"));
				}
				try {
					var outcome = dev.squire.server.command.StructuredCommandCompiler.executeEffect(
						ctx.server(), target,
						new dev.squire.server.command.StructuredCommandCompiler.EffectIntent(
							str(call.arguments().get("effectId")),
							intOf(call.arguments().get("durationTicks"), 200),
							intOf(call.arguments().get("amplifier"), 0)),
						services.protection());
					return commandResult(call, outcome);
				} catch (IllegalArgumentException e) {
					return ToolResult.failed(call.callId(),
						dev.squire.common.errors.ErrorPayload.of(
							dev.squire.common.errors.ErrorCode.INVALID_ARGUMENT,
							String.valueOf(e.getMessage())));
				}
			});

		// 「把时间调成白天」。这条能力以前完全不存在——不是没听懂，是没有工具。
		registry.register(ToolDefinition.builder("world.set_time")
			.description("Set the world time. Use preset day/noon/night/midnight, or "
				+ "dayTime for an exact tick 0-23999. Affects the whole server.")
			.arg(ArgDefinition.optional("preset", ArgDefinition.ArgType.STRING,
				"day / noon / night / midnight"))
			.arg(ArgDefinition.optional("dayTime", ArgDefinition.ArgType.INT,
				"exact time of day, 0-23999 (0 = sunrise, 6000 = noon)"))
			.permission(AgentPermission.COMMAND)
			.node(dev.squire.server.security.PermissionNodes.COMMAND_WORLD)
			.risk(RiskLevel.MEDIUM)
			.exposure(ToolExposure.MODEL_PUBLIC)
			.build(), (call, ctx) -> {
				var outcome = dev.squire.server.command.StructuredCommandCompiler
					.executeSetTime(ctx.server(), ctx.avatar(), ctx.requesterId(),
						services.protection(), str(call.arguments().get("preset")),
						intOrNull(call.arguments().get("dayTime")));
				return commandResult(call, outcome);
			});

		// 「最近的远古城市在哪」。同样是一整类以前无法回答的问题。
		registry.register(ToolDefinition.builder("world.locate_structure")
			.description("Find the nearest generated structure and report its "
				+ "coordinates and distance. Accepts a structure id "
				+ "(minecraft:ancient_city) or a tag (#minecraft:village). This is a "
				+ "READ-ONLY query - answer the player with the coordinates it returns, "
				+ "and never invent coordinates when it reports nothing was found.")
			.arg(ArgDefinition.required("structure", ArgDefinition.ArgType.STRING,
				"structure id or #tag, e.g. minecraft:ancient_city"))
			.permission(AgentPermission.QUERY)
			.node(dev.squire.server.security.PermissionNodes.COMMAND_WORLD)
			.risk(RiskLevel.LOW)
			.exposure(ToolExposure.MODEL_PUBLIC)
			.build(), (call, ctx) -> {
				var world = ctx.world();
				var outcome = dev.squire.server.command.StructuredCommandCompiler
					.locateStructure(world, ctx.avatar().getBlockPos(),
						str(call.arguments().get("structure")));
				if (outcome.errorDetail() != null) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.INVALID_ARGUMENT, outcome.errorDetail()));
				}
				if (!outcome.found()) {
					// 「这附近没有」是一个<b>成功的查询结果</b>，不是失败。
					// 判成失败会让模型去重试或者干脆编一个坐标出来。
					return ToolResult.success(call.callId(), Map.of(
						"found", false,
						"structure", String.valueOf(outcome.matchedId()),
						"searchedChunks", 100));
				}
				return ToolResult.success(call.callId(), Map.of(
					"found", true,
					"structure", outcome.matchedId(),
					"x", outcome.pos().getX(),
					"y", outcome.pos().getY(),
					"z", outcome.pos().getZ(),
					"distance", outcome.horizontalDistance(),
					"dimension", world.getRegistryKey().getValue().toString()));
			});

		// 「到我身边来」。navigation.move_to 需要坐标，而玩家说「过来」时并不知道
		// 自己站在哪；他也可能在另一个维度，那时寻路根本无从谈起。
		registry.register(ToolDefinition.builder("navigation.come_to_owner")
			.description("Come to the requesting player right now: walk if close, "
				+ "teleport if far away or in another dimension. Use this for "
				+ "'come here' - never make the player give coordinates for it.")
			.permission(AgentPermission.MOVE)
			.node(dev.squire.server.security.PermissionNodes.TASK_FOLLOW)
			.risk(RiskLevel.LOW)
			.exposure(ToolExposure.MODEL_PUBLIC)
			.build(), (call, ctx) -> {
				if (!dev.squire.server.runtime.SquireRuntime.isAlive()) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.INTERNAL_ERROR, "runtime unavailable"));
				}
				ServerPlayerEntity owner = services.requester(ctx.requesterId());
				if (owner == null) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.ENTITY_NOT_FOUND, "requester offline"));
				}
				var result = dev.squire.server.runtime.SquireRuntime.get()
					.comeToOwner(owner);
				return result.success()
					? ToolResult.success(call.callId(), Map.of("report", result.message()))
					: ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.PRECONDITION_FAILED, result.message()));
			});

		// 「把我和他都传送过去」「帮我回主世界」。以前只有"传送伙伴"这一条，
		// 玩家自己想过去完全办不到——minecraft.command.teleport 的描述里就写着
		// THE AGENT。
		registry.register(ToolDefinition.builder("player.teleport")
			.description("Teleport the REQUESTING PLAYER (optionally bringing the "
				+ "companion along). Give a dimension, explicit coordinates, or a "
				+ "remembered place name. Dimension without coordinates lands on the "
				+ "player's spawn point. This moves a person - only do it when they "
				+ "clearly asked to be moved.")
			.arg(ArgDefinition.optional("dimension", ArgDefinition.ArgType.STRING,
				"minecraft:overworld / minecraft:the_nether / minecraft:the_end"))
			.arg(ArgDefinition.optional("place", ArgDefinition.ArgType.STRING,
				"a remembered place name, e.g. 基地"))
			.arg(ArgDefinition.optional("x", ArgDefinition.ArgType.INT, "target x"))
			.arg(ArgDefinition.optional("y", ArgDefinition.ArgType.INT, "target y"))
			.arg(ArgDefinition.optional("z", ArgDefinition.ArgType.INT, "target z"))
			.arg(ArgDefinition.optional("bringCompanion", ArgDefinition.ArgType.BOOLEAN,
				"true (default) to bring the companion too"))
			.permission(AgentPermission.COMMAND)
			.node(dev.squire.server.security.PermissionNodes.COMMAND_TELEPORT)
			.risk(RiskLevel.HIGH)
			.exposure(ToolExposure.MODEL_PUBLIC)
			.build(), (call, ctx) -> {
				if (!dev.squire.server.runtime.SquireRuntime.isAlive()) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.INTERNAL_ERROR, "runtime unavailable"));
				}
				ServerPlayerEntity owner = services.requester(ctx.requesterId());
				if (owner == null) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.ENTITY_NOT_FOUND, "requester offline"));
				}
				var runtime = dev.squire.server.runtime.SquireRuntime.get();
				boolean bring = !Boolean.FALSE.equals(
					call.arguments().get("bringCompanion"));
				String place = str(call.arguments().get("place"));
				String dimension = str(call.arguments().get("dimension"));
				Integer x = intOrNull(call.arguments().get("x"));
				Integer y = intOrNull(call.arguments().get("y"));
				Integer z = intOrNull(call.arguments().get("z"));
				var result = x != null && y != null && z != null
					? runtime.teleportOwner(owner, dimension,
						new net.minecraft.util.math.BlockPos(x, y, z), bring, null)
					: runtime.teleportOwnerToPlaceOrDimension(owner, dimension, place,
						bring, place);
				return result.success()
					? ToolResult.success(call.callId(), Map.of("report", result.message()))
					: ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.PRECONDITION_FAILED, result.message()));
			});

		// 「把雨停了」。和 set_time 是一对：玩家问完时间，下一句多半就是天气。
		registry.register(ToolDefinition.builder("world.set_weather")
			.description("Set the weather: clear, rain, or thunder.")
			.arg(ArgDefinition.required("preset", ArgDefinition.ArgType.STRING,
				"clear / rain / thunder"))
			.arg(ArgDefinition.optional("durationSeconds", ArgDefinition.ArgType.INT,
				"how long it lasts; omit for the vanilla default"))
			.permission(AgentPermission.COMMAND)
			.node(dev.squire.server.security.PermissionNodes.COMMAND_WORLD)
			.risk(RiskLevel.MEDIUM)
			.exposure(ToolExposure.MODEL_PUBLIC)
			.build(), (call, ctx) -> {
				var outcome = dev.squire.server.command.StructuredCommandCompiler
					.executeSetWeather(ctx.server(), ctx.avatar(), ctx.requesterId(),
						services.protection(), str(call.arguments().get("preset")),
						intOrNull(call.arguments().get("durationSeconds")));
				return commandResult(call, outcome);
			});

		// 「最近的樱花林在哪」。和 locate_structure 同形，但走群系查询。
		registry.register(ToolDefinition.builder("world.locate_biome")
			.description("Find the nearest biome and report its coordinates and "
				+ "distance. Accepts a biome id (minecraft:cherry_grove) or a tag. "
				+ "READ-ONLY: answer with what it returns, and never invent "
				+ "coordinates when it reports nothing was found.")
			.arg(ArgDefinition.required("biome", ArgDefinition.ArgType.STRING,
				"biome id or #tag, e.g. minecraft:cherry_grove"))
			.permission(AgentPermission.QUERY)
			.node(dev.squire.server.security.PermissionNodes.COMMAND_WORLD)
			.risk(RiskLevel.LOW)
			.exposure(ToolExposure.MODEL_PUBLIC)
			.build(), (call, ctx) -> {
				var world = ctx.world();
				var outcome = dev.squire.server.command.StructuredCommandCompiler
					.locateBiome(world, ctx.avatar().getBlockPos(),
						str(call.arguments().get("biome")));
				if (outcome.errorDetail() != null) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.INVALID_ARGUMENT, outcome.errorDetail()));
				}
				if (!outcome.found()) {
					return ToolResult.success(call.callId(), Map.of(
						"found", false, "biome", String.valueOf(outcome.matchedId())));
				}
				return ToolResult.success(call.callId(), Map.of(
					"found", true, "biome", outcome.matchedId(),
					"x", outcome.pos().getX(), "y", outcome.pos().getY(),
					"z", outcome.pos().getZ(),
					"distance", outcome.horizontalDistance(),
					"dimension", world.getRegistryKey().getValue().toString()));
			});

		// 玩家自己的状态。以前感知块里关于玩家一个字都没有，「我血量多少」
		// 「我手上这是什么」他只能瞎猜。
		registry.register(ToolDefinition.builder("query.player")
			.description("Report the REQUESTING PLAYER's health, hunger, position, "
				+ "held item, and the local time/weather/biome. Read-only. Use this "
				+ "instead of guessing anything about the player.")
			.permission(AgentPermission.INVENTORY_READ)
			.risk(RiskLevel.LOW)
			.exposure(ToolExposure.MODEL_PUBLIC)
			.build(), (call, ctx) -> {
				ServerPlayerEntity owner = services.requester(ctx.requesterId());
				if (owner == null) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.ENTITY_NOT_FOUND, "requester offline"));
				}
				return ToolResult.success(call.callId(), Map.of("report",
					dev.squire.server.perception.PerceptionService.renderOwner(owner)));
			});

		// 「附近有怪吗」。这段扫描逻辑一直在 PerceptionService 的 DETAILED 分支里，
		// 但对话用的是 NORMAL，所以它在聊天路径上从来没执行过。
		registry.register(ToolDefinition.builder("entity.scan_nearby")
			.description("List living entities around the companion, grouped by type "
				+ "with counts and the nearest distance. Read-only.")
			.arg(ArgDefinition.optional("radius", ArgDefinition.ArgType.INT,
				"scan radius in blocks, 4-48 (default 16)"))
			.arg(ArgDefinition.optional("hostileOnly", ArgDefinition.ArgType.BOOLEAN,
				"true to list only hostiles"))
			.permission(AgentPermission.INVENTORY_READ)
			.risk(RiskLevel.LOW)
			.exposure(ToolExposure.MODEL_PUBLIC)
			.build(), (call, ctx) -> {
				int radius = Math.max(4, Math.min(48,
					intOf(call.arguments().get("radius"), 16)));
				boolean hostileOnly =
					Boolean.TRUE.equals(call.arguments().get("hostileOnly"));
				return ToolResult.success(call.callId(), Map.of("nearby",
					dev.squire.server.perception.PerceptionService.scanNearby(
						ctx.avatar(), radius, hostileOnly)));
			});

		registry.register(ToolDefinition.builder("minecraft.command.summon_safe")
			.description("Summon one whitelisted peaceful entity near the agent.")
			.arg(ArgDefinition.required("entityId", ArgDefinition.ArgType.ENTITY_ID,
				"whitelisted entity id"))
			.arg(ArgDefinition.optional("x", ArgDefinition.ArgType.DOUBLE, "spawn x (default agent)"))
			.arg(ArgDefinition.optional("y", ArgDefinition.ArgType.DOUBLE, "spawn y"))
			.arg(ArgDefinition.optional("z", ArgDefinition.ArgType.DOUBLE, "spawn z"))
			.permission(AgentPermission.COMMAND)
			.node(dev.squire.server.security.PermissionNodes.COMMAND_GIVE)
			.risk(RiskLevel.MEDIUM)
			.exposure(ToolExposure.MODEL_PUBLIC)
			.build(), (call, ctx) -> {
				double x = call.arguments().get("x") instanceof Number n ? n.doubleValue()
					: ctx.avatar().getX();
				double y = call.arguments().get("y") instanceof Number n ? n.doubleValue()
					: ctx.avatar().getY();
				double z = call.arguments().get("z") instanceof Number n ? n.doubleValue()
					: ctx.avatar().getZ();
				var outcome = dev.squire.server.command.StructuredCommandCompiler
					.executeSummonSafe(ctx.server(), ctx.avatar(), ctx.requesterId(),
						services.protection(), str(call.arguments().get("entityId")),
						x, y + 0.5, z);
				return commandResult(call, outcome);
			});
	}

	// —— 已停用（方向性收缩：真身只保留跟随/护卫/救援，取物改走指令兑现）——
	// 保留代码而非删除，方便日后回退。当前无任何调用方。
//	/** Shared path for fill/setblock: capability issue → world.edit task. */
//	private static ToolResult submitEdit(dev.squire.common.protocol.ToolCall call,
//			ToolExecutionContext ctx, TaskScheduler scheduler, RuntimeServices services,
//			String kind) {
//		dev.squire.server.world.BoundedRegion region =
//			dev.squire.server.world.BoundedRegion.ofCorners(
//				intOf(call.arguments().get("x1"), 0), intOf(call.arguments().get("y1"), 0),
//				intOf(call.arguments().get("z1"), 0), intOf(call.arguments().get("x2"), 0),
//				intOf(call.arguments().get("y2"), 0), intOf(call.arguments().get("z2"), 0));
//		if (region.volume() > dev.squire.server.world.BoundedRegion.MAX_VOLUME) {
//			return ToolResult.failed(call.callId(), dev.squire.common.errors.ErrorPayload.of(
//				dev.squire.common.errors.ErrorCode.CAPABILITY_SCOPE_VIOLATION,
//				"region too large: " + region.volume()));
//		}
//		String blockId = str(call.arguments().get("blockId"));
//
//		// scoped capability bound to THIS operation (§30); consumed by WorldEditor
//		var store = services.capabilities();
//		if (store == null) {
//			return ToolResult.failed(call.callId(), dev.squire.common.errors.ErrorPayload.of(
//				dev.squire.common.errors.ErrorCode.CAPABILITY_REQUIRED,
//				"no capability store"));
//		}
//		long now = services.currentTick();
//		var dimension = ctx.avatar().getWorld().getRegistryKey().getValue();
//		var editKind = "setblock".equals(kind)
//			? dev.squire.server.world.WorldEditor.EditPlan.Kind.SETBLOCK
//			: dev.squire.server.world.WorldEditor.EditPlan.Kind.FILL;
//		String capabilityTool =
//			dev.squire.server.world.WorldEditor.capabilityToolNameForKind(editKind);
//		var capability = store.issue(ctx.requesterId(), ctx.body().agentId(),
//			capabilityTool, java.util.Set.of(capabilityTool), dimension, region,
//			(int) region.volume(), now);
//
//		long timeout = 400L + (region.volume()
//			/ dev.squire.server.world.WorldEditor.BLOCKS_PER_TICK) * 25L;
//		Map<String, Object> params = new java.util.LinkedHashMap<>();
//		params.put(dev.squire.server.task.executors.WorldEditExecutor.PARAM_KIND, kind);
//		params.put("x1", region.min().getX());
//		params.put("y1", region.min().getY());
//		params.put("z1", region.min().getZ());
//		params.put("x2", region.max().getX());
//		params.put("y2", region.max().getY());
//		params.put("z2", region.max().getZ());
//		params.put(dev.squire.server.task.executors.WorldEditExecutor.PARAM_BLOCK_ID, blockId);
//		params.put(dev.squire.server.task.executors.WorldEditExecutor.PARAM_CAPABILITY_ID,
//			capability.capabilityId().toString());
//
//		Task task = new Task(ctx.body().agentId(), ctx.requesterId(),
//			dev.squire.server.task.executors.WorldEditExecutor.TYPE,
//			TaskPriority.P3_USER_TASK,
//			kind + " " + blockId + " in " + region, null,
//			dev.squire.server.task.executors.WorldEditExecutor.regionMatches(
//				dimension.toString(), region, blockId),
//			timeout, RetryPolicy.DEFAULT, true, "m3", params);
//		scheduler.submit(task, now);
//		return ToolResult.running(call.callId(),
//			Map.of("taskId", task.taskId().toString()));
//	}

	private static net.minecraft.util.Identifier identifierOf(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new IllegalArgumentException("identifier required");
		}
		return raw.contains(":")
			? net.minecraft.util.Identifier.of(raw.substring(0, raw.indexOf(':')),
				raw.substring(raw.indexOf(':') + 1))
			: net.minecraft.util.Identifier.of("minecraft", raw);
	}

	/** Typed CommandOutcome → unified ToolResult mapping (§46 verify step). */
	private static ToolResult commandResult(dev.squire.common.protocol.ToolCall call,
			dev.squire.server.command.StructuredCommandCompiler.CommandOutcome outcome) {
		return commandResult(call, outcome, false);
	}

	private static ToolResult commandResult(dev.squire.common.protocol.ToolCall call,
			dev.squire.server.command.StructuredCommandCompiler.CommandOutcome outcome,
			boolean worldEdit) {
		if (outcome.success()) {
			Map<String, Object> data = new LinkedHashMap<>();
			data.put("executed", true);
			data.put("affected", outcome.affected());
			data.put("viaCommandBlock", outcome.viaCommandBlock());
			if (worldEdit && outcome.affected() > 0) {
				data.put("blocksChanged", outcome.affected());
			}
			return ToolResult.success(call.callId(), data);
		}
		boolean parse = outcome.errorDetail() != null
			&& outcome.errorDetail().startsWith("COMMAND_PARSE_FAILED");
		return ToolResult.failed(call.callId(), dev.squire.common.errors.ErrorPayload.of(
			parse ? dev.squire.common.errors.ErrorCode.COMMAND_PARSE_FAILED
				: dev.squire.common.errors.ErrorCode.COMMAND_FAILED,
			outcome.errorDetail() == null ? "command failed" : outcome.errorDetail()));
	}

	private static ToolResult invalidCommandArgument(
			dev.squire.common.protocol.ToolCall call, IllegalArgumentException error) {
		return ToolResult.failed(call.callId(), ErrorPayload.of(
			ErrorCode.INVALID_ARGUMENT, String.valueOf(error.getMessage())));
	}

	// ------------------------------------------------------------------ combat & healing

	private static void registerCombatAndHealing(ToolRegistry registry,
			RuntimeServices services, TaskScheduler scheduler) {
		registry.register(ToolDefinition.builder("guard.start")
			.description("Protect the requesting player from nearby hostiles for a while; "
				+ "the agent picks targets and fights on its own.")
			.arg(ArgDefinition.intRange("radius", 4, 32, "threat scan radius around the player"))
			.arg(ArgDefinition.intRange("durationTicks", 100, 24000, "how long to guard"))
			.arg(ArgDefinition.optional("persistent", ArgDefinition.ArgType.BOOLEAN,
				"true for open-ended guard duty that survives restarts until the "
					+ "player says stop; false (default) for a bounded watch"))
			.permission(AgentPermission.COMBAT)
			.risk(RiskLevel.MEDIUM)
			.exposure(ToolExposure.MODEL_PUBLIC)
			.build(), (call, ctx) -> {
				if (Boolean.TRUE.equals(call.arguments().get("persistent"))) {
					// 方案 D1：长期护卫是一条 Policy，不是一次性任务
					var runtime = dev.squire.server.runtime.SquireRuntime.get();
					if (runtime == null) {
						return ToolResult.failed(call.callId(), ErrorPayload.of(
							ErrorCode.INTERNAL_ERROR, "runtime unavailable"));
					}
					var policy = runtime.guards().enable(ctx.requesterId(),
						ctx.body().agentId(),
						intOf(call.arguments().get("radius"), 16));
					return ToolResult.success(call.callId(), Map.of(
						"persistent", true, "radius", policy.radius()));
				}
				Map<String, Object> params = new LinkedHashMap<>();
				params.put(dev.squire.server.task.executors.GuardTaskExecutor.PARAM_OWNER_ID,
					ctx.requesterId().toString());
				Object radius = call.arguments().get("radius");
				if (radius != null) {
					params.put(dev.squire.server.task.executors.GuardTaskExecutor.PARAM_RADIUS,
						String.valueOf(radius));
				}
				Object duration = call.arguments().get("durationTicks");
				if (duration != null) {
					params.put(dev.squire.server.task.executors.GuardTaskExecutor.PARAM_DURATION,
						String.valueOf(duration));
				}
				Task task = new Task(ctx.body().agentId(), ctx.requesterId(),
					dev.squire.server.task.executors.GuardTaskExecutor.TYPE,
					TaskPriority.P2_OWNER_URGENT,
					"protect the owner", null,
					dev.squire.server.task.executors.GuardTaskExecutor.guardSurvived(),
					24000L + 200L, RetryPolicy.DEFAULT, true, "m2", params);
				scheduler.submit(task, services.currentTick());
				return ToolResult.running(call.callId(),
					Map.of("taskId", task.taskId().toString()));
			});

		registry.register(ToolDefinition.builder("aid.owner")
			.description("Help the REQUESTING PLAYER when they are hurt, using real "
				+ "healing items from the agent's own backpack. Never heals the agent "
				+ "itself and never conjures items.")
			.arg(ArgDefinition.optional("targetHealthFraction", ArgDefinition.ArgType.DOUBLE,
				"stop once the player is back to this fraction of max health (default 0.8)"))
			.permission(AgentPermission.HEAL)
			.risk(RiskLevel.MEDIUM)
			.exposure(ToolExposure.MODEL_PUBLIC)
			.build(), (call, ctx) -> {
				ServerPlayerEntity owner = services.requester(ctx.requesterId());
				if (owner == null || !owner.isAlive()) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.ENTITY_NOT_FOUND, "requester offline or dead"));
				}
				var remedy = dev.squire.server.task.executors.OwnerAidExecutor
					.bestRemedy(ctx.avatar(),
						Math.max(0f, owner.getMaxHealth() - owner.getHealth()));
				if (remedy == null) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.INSUFFICIENT_ITEM,
						"INSUFFICIENT_HEALING_ITEM: the agent carries no healing item"));
				}
				double fraction = call.arguments().get("targetHealthFraction")
					instanceof Number n ? Math.max(0.1, Math.min(1.0, n.doubleValue()))
					: 0.8;
				Map<String, Object> params = new LinkedHashMap<>();
				params.put(dev.squire.server.task.executors.OwnerAidExecutor.PARAM_OWNER_ID,
					ctx.requesterId().toString());
				params.put(dev.squire.server.task.executors.OwnerAidExecutor
					.PARAM_TARGET_FRACTION, fraction);
				Task task = new Task(ctx.body().agentId(), ctx.requesterId(),
					dev.squire.server.task.executors.OwnerAidExecutor.TYPE,
					TaskPriority.P1_SURVIVAL, "aid the owner", null,
					dev.squire.server.task.executors.OwnerAidExecutor.ownerImproved(
						services, ctx.requesterId(), fraction),
					1200L, RetryPolicy.DEFAULT, false, "d2", params);
				scheduler.submit(task, services.currentTick());
				return ToolResult.running(call.callId(), Map.of(
					"taskId", task.taskId().toString(),
					"usingItem", remedy.itemId().toString()));
			});

		// 「打那只苦力怕」。guard.start 是被动护卫，这条是指哪打哪。
		registry.register(ToolDefinition.builder("combat.attack_target")
			.description("Attack the nearest matching creature. Give entityId to name "
				+ "a kind (minecraft:creeper); omit it to clear nearby HOSTILES only. "
				+ "The server never targets players, and only touches peaceful animals "
				+ "when their kind was named explicitly.")
			.arg(ArgDefinition.optional("entityId", ArgDefinition.ArgType.ENTITY_ID,
				"what to attack, e.g. minecraft:creeper; omit for nearby hostiles"))
			.arg(ArgDefinition.optional("radius", ArgDefinition.ArgType.INT,
				"search radius 4-32 (default 16)"))
			.arg(ArgDefinition.optional("maxKills", ArgDefinition.ArgType.INT,
				"how many to take down, 1-8 (default 1)"))
			.permission(AgentPermission.COMBAT)
			.node(dev.squire.server.security.PermissionNodes.TASK_GUARD)
			.risk(RiskLevel.MEDIUM)
			.exposure(ToolExposure.MODEL_PUBLIC)
			.build(), (call, ctx) -> {
				Map<String, Object> params = new LinkedHashMap<>();
				String entityId = str(call.arguments().get("entityId"));
				if (entityId != null) {
					params.put(dev.squire.server.task.executors.AttackTargetExecutor
						.PARAM_ENTITY_ID, entityId);
				}
				Integer radius = intOrNull(call.arguments().get("radius"));
				if (radius != null) {
					params.put(dev.squire.server.task.executors.AttackTargetExecutor
						.PARAM_RADIUS, radius);
				}
				Integer maxKills = intOrNull(call.arguments().get("maxKills"));
				if (maxKills != null) {
					params.put(dev.squire.server.task.executors.AttackTargetExecutor
						.PARAM_MAX_KILLS, maxKills);
				}
				Task task = new Task(ctx.body().agentId(), ctx.requesterId(),
					dev.squire.server.task.executors.AttackTargetExecutor.TYPE,
					TaskPriority.P2_OWNER_URGENT,
					"attack " + (entityId == null ? "nearby hostiles" : entityId), null,
					dev.squire.server.task.executors.AttackTargetExecutor.targetDown(),
					1200L, RetryPolicy.DEFAULT, true, "attack", params);
				scheduler.submit(task, services.currentTick());
				return ToolResult.running(call.callId(),
					Map.of("taskId", task.taskId().toString()));
			});

		// 「用弓打」。选武器原来只看 GENERIC_ATTACK_DAMAGE，而弓身上没有这个属性
		// （伤害来自箭的初速），所以满配弓的得分是 0，永远输给一把木剑。
		registry.register(ToolDefinition.builder("combat.set_style")
			.description("Set how the companion fights: 'ranged' (prefer the bow), "
				+ "'melee' (never shoot), or 'auto' (shoot at range, swing up close). "
				+ "The preference is remembered. Shooting consumes REAL arrows from "
				+ "the companion's backpack, so give it a bow and arrows first.")
			.arg(ArgDefinition.required("style", ArgDefinition.ArgType.STRING,
				"ranged / melee / auto"))
			.permission(AgentPermission.COMBAT)
			.node(dev.squire.server.security.PermissionNodes.TASK_GUARD)
			.risk(RiskLevel.LOW)
			.exposure(ToolExposure.MODEL_PUBLIC)
			.build(), (call, ctx) -> {
				if (!dev.squire.server.runtime.SquireRuntime.isAlive()) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.INTERNAL_ERROR, "runtime unavailable"));
				}
				ServerPlayerEntity owner = services.requester(ctx.requesterId());
				if (owner == null) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.ENTITY_NOT_FOUND, "requester offline"));
				}
				dev.squire.server.combat.CombatStyle.Style style;
				try {
					style = dev.squire.server.combat.CombatStyle.Style.valueOf(
						String.valueOf(str(call.arguments().get("style")))
							.trim().toUpperCase(java.util.Locale.ROOT));
				} catch (IllegalArgumentException e) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.INVALID_ARGUMENT, "style must be ranged/melee/auto"));
				}
				var result = dev.squire.server.runtime.SquireRuntime.get()
					.setCombatStyle(owner, style);
				return result.success()
					? ToolResult.success(call.callId(), Map.of("report", result.message()))
					: ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.PRECONDITION_FAILED, result.message()));
			});

		// 「信标怎么做」。区别于 crafting.craft：这条什么都不消耗，只回答配方。
		registry.register(ToolDefinition.builder("crafting.recipe_of")
			.description("Look up how an item is made, from the server's REAL loaded "
				+ "recipe table (so datapack and mod recipes are correct too). "
				+ "Read-only, consumes nothing. Use it instead of reciting a recipe "
				+ "from memory.")
			.arg(ArgDefinition.required("itemId", ArgDefinition.ArgType.ITEM_ID,
				"the item to look up, e.g. minecraft:beacon"))
			.permission(AgentPermission.INVENTORY_READ)
			.risk(RiskLevel.LOW)
			.exposure(ToolExposure.MODEL_PUBLIC)
			.build(), (call, ctx) -> {
				var result = dev.squire.server.craft.RecipeLookup.of(ctx.world(),
					str(call.arguments().get("itemId")));
				if (!result.itemExists()) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.INVALID_ARGUMENT,
						"no such item: " + result.itemId()));
				}
				if (result.recipes().isEmpty()) {
					// 「做不出来」是一个有效答案（矿石、刷怪蛋、下界之星）。
					return ToolResult.success(call.callId(), Map.of(
						"item", result.itemId(), "craftable", false));
				}
				List<Object> recipes = new java.util.ArrayList<>();
				for (var recipe : result.recipes()) {
					recipes.add(Map.of("kind", recipe.kind(),
						"makes", recipe.outputCount(),
						"ingredients", recipe.ingredients()));
				}
				return ToolResult.success(call.callId(), Map.of(
					"item", result.itemId(), "craftable", true, "recipes", recipes));
			});

		registry.register(ToolDefinition.builder("guard.stop")
			.description("End the long-running guard duty for the requesting player.")
			.permission(AgentPermission.COMBAT)
			.risk(RiskLevel.LOW)
			.exposure(ToolExposure.MODEL_PUBLIC)
			.build(), (call, ctx) -> {
				var runtime = dev.squire.server.runtime.SquireRuntime.get();
				boolean stopped = runtime != null
					&& runtime.guards().disable(ctx.body().agentId());
				return ToolResult.success(call.callId(), Map.of("stopped", stopped));
			});

		registry.register(ToolDefinition.builder("heal.now")
			.description("Eat healing food from the agent inventory until healthy again.")
			.permission(AgentPermission.HEAL)
			.risk(RiskLevel.LOW)
			.exposure(ToolExposure.MODEL_PUBLIC)
			.build(), (call, ctx) -> {
				double baseline = Math.max(ctx.avatar().snapshotState().health(),
					ctx.avatar().snapshotState().maxHealth() * 0.95);
				double targetFraction = Math.min(
					baseline / ctx.avatar().snapshotState().maxHealth(), 1.0);
				Task task = new Task(ctx.body().agentId(), ctx.requesterId(),
					dev.squire.server.task.executors.HealTaskExecutor.TYPE,
					TaskPriority.P1_SURVIVAL,
					"restore health", null,
					dev.squire.server.task.executors.HealTaskExecutor.hasHealthFraction(
						targetFraction),
					1200L, RetryPolicy.DEFAULT, false, "m2",
					Map.of("targetHealthFraction", targetFraction));
				scheduler.submit(task, services.currentTick());
				return ToolResult.running(call.callId(),
					Map.of("taskId", task.taskId().toString()));
			});
	}

	// ------------------------------------------------------------------ queries

	private static void registerQueries(ToolRegistry registry, RuntimeServices services) {
		registry.register(ToolDefinition.builder("query.status")
			.description("Report the agent's position, dimension, health and mode.")
			.permission(AgentPermission.QUERY)
			.risk(RiskLevel.LOW)
			.exposure(ToolExposure.MODEL_PUBLIC)
			.build(), (call, ctx) -> {
				AvatarEntity avatar = ctx.avatar();
				Map<String, Object> data = new LinkedHashMap<>();
				data.put("dimension", avatar.getWorld().getRegistryKey().getValue().toString());
				data.put("x", Math.round(avatar.getX() * 10) / 10.0);
				data.put("y", Math.round(avatar.getY() * 10) / 10.0);
				data.put("z", Math.round(avatar.getZ() * 10) / 10.0);
				data.put("health", (double) avatar.getHealth());
				data.put("mode", avatar.mode().name());
				return ToolResult.success(call.callId(), data);
			});

		registry.register(ToolDefinition.builder("query.inventory")
			.description("List item id -> count currently held by the agent.")
			.permission(AgentPermission.INVENTORY_READ)
			.risk(RiskLevel.LOW)
			.exposure(ToolExposure.MODEL_PUBLIC)
			.build(), (call, ctx) -> {
				Map<String, Object> counts = new LinkedHashMap<>();
				for (String id : ctx.avatar().inventory().distinctItemIds()) {
					counts.put(id, ctx.avatar().inventory().countOf(id));
				}
				return ToolResult.success(call.callId(),
					Map.of("items", counts, "occupiedSlots", ctx.avatar().inventory().occupiedSlots()));
			});
	}

	// ------------------------------------------------------------------ navigation

	private static void registerNavigation(ToolRegistry registry, RuntimeServices services,
			TaskScheduler scheduler) {
		registry.register(ToolDefinition.builder("navigation.move_to")
			.description("Walk to the given position in the current dimension.")
			.arg(ArgDefinition.intRange("x", -30_000_000, 30_000_000, "target x"))
			.arg(ArgDefinition.intRange("y", -2048, 2048, "target y"))
			.arg(ArgDefinition.intRange("z", -30_000_000, 30_000_000, "target z"))
			.permission(AgentPermission.MOVE)
			.risk(RiskLevel.LOW)
			.exposure(ToolExposure.MODEL_PUBLIC)
			.build(), (call, ctx) -> {
				Task task = baseTask(ctx, "navigation.move_to",
					TaskPriority.P3_USER_TASK, "walk to "
						+ call.arguments().get("x") + "," + call.arguments().get("y")
						+ "," + call.arguments().get("z"),
					dev.squire.server.task.executors.MoveToExecutor.nearTarget(
						num(call.arguments().get("x")), num(call.arguments().get("y")),
						num(call.arguments().get("z")), MoveOptions.WALK.arriveWithin()),
					1200L,
					Map.of("x", num(call.arguments().get("x")),
						"y", num(call.arguments().get("y")),
						"z", num(call.arguments().get("z")),
						"arriveWithinSq", MoveOptions.WALK.arriveWithin()));
				scheduler.submit(task, services.currentTick());
				return ToolResult.running(call.callId(), Map.of("taskId", task.taskId().toString()));
			});
	}

	// ------------------------------------------------------------------ world edits

	private static void registerWorld(ToolRegistry registry, RuntimeServices services,
			TaskScheduler scheduler) {
		// —— 已停用：对应的 BreakOne/PlaceOne 执行器已注释掉了。
		//
		// 注意别照抄一句已经不成立的话：PLANNER_INTERNAL <b>不</b>等于没人调得到。
		// AutomationEngine 的 TOOL_CALL 节点就是以 PLANNER 身份走网关的
		// （SquireRuntime.buildAutomationEngine）。真正拦住这两条的是它们没被注册，
		// 以及 AutomationCompiler.ALLOWED_TOOLS 那张白名单。哪天要恢复它们，
		// 记得先去 ToolGate 定门槛——放方块是工程师那条线的能力。——
//		registry.register(ToolDefinition.builder("world.break_block")
//			.description("Break ONE block within reach of the agent; drops go to its inventory.")
//			.arg(ArgDefinition.required("x", ArgDefinition.ArgType.INT, "block x"))
//			.arg(ArgDefinition.required("y", ArgDefinition.ArgType.INT, "block y"))
//			.arg(ArgDefinition.required("z", ArgDefinition.ArgType.INT, "block z"))
//			.permission(AgentPermission.WORLD_BREAK)
//			.risk(RiskLevel.MEDIUM)
//			.exposure(ToolExposure.PLANNER_INTERNAL)
//			.build(), (call, ctx) -> submitSimple(call, ctx, scheduler, services,
//				"break", "world.break", call.arguments()));

		// —— 已停用：对应的 BreakOne/PlaceOne 执行器已注释掉了。
		//
		// 注意别照抄一句已经不成立的话：PLANNER_INTERNAL <b>不</b>等于没人调得到。
		// AutomationEngine 的 TOOL_CALL 节点就是以 PLANNER 身份走网关的
		// （SquireRuntime.buildAutomationEngine）。真正拦住这两条的是它们没被注册，
		// 以及 AutomationCompiler.ALLOWED_TOOLS 那张白名单。哪天要恢复它们，
		// 记得先去 ToolGate 定门槛——放方块是工程师那条线的能力。——
//		registry.register(ToolDefinition.builder("world.place_block")
//			.description("Place ONE block from the agent inventory onto the target cell.")
//			.arg(ArgDefinition.required("x", ArgDefinition.ArgType.INT, "block x"))
//			.arg(ArgDefinition.required("y", ArgDefinition.ArgType.INT, "block y"))
//			.arg(ArgDefinition.required("z", ArgDefinition.ArgType.INT, "block z"))
//			.arg(ArgDefinition.required("itemId", ArgDefinition.ArgType.ITEM_ID, "block item to place"))
//			.permission(AgentPermission.WORLD_PLACE)
//			.risk(RiskLevel.MEDIUM)
//			.exposure(ToolExposure.PLANNER_INTERNAL)
//			.build(), (call, ctx) -> submitSimple(call, ctx, scheduler, services,
//				"place", "world.place", call.arguments()));

		// --- F1: 用真实材料逐层建造（普通建造，不是批量地形编辑） ---------------
		registry.register(ToolDefinition.builder("build.structure")
			.description("Build a box-shaped structure out of blocks the agent is really "
				+ "carrying: solid, hollow, walls-only or a floor. Materials come out of "
				+ "the backpack — this never conjures blocks. Undoable.")
			.arg(ArgDefinition.intRange("x1", -30_000_000, 30_000_000, "corner 1 x"))
			.arg(ArgDefinition.intRange("y1", -2048, 2048, "corner 1 y"))
			.arg(ArgDefinition.intRange("z1", -30_000_000, 30_000_000, "corner 1 z"))
			.arg(ArgDefinition.intRange("x2", -30_000_000, 30_000_000, "corner 2 x"))
			.arg(ArgDefinition.intRange("y2", -2048, 2048, "corner 2 y"))
			.arg(ArgDefinition.intRange("z2", -30_000_000, 30_000_000, "corner 2 z"))
			.arg(ArgDefinition.required("blockId", ArgDefinition.ArgType.ITEM_ID,
				"block item to build with; must be in the agent inventory"))
			.arg(ArgDefinition.optional("pattern", ArgDefinition.ArgType.STRING,
				"SOLID / HOLLOW / WALLS / FLOOR (default SOLID)"))
			.arg(ArgDefinition.optional("onProtected", ArgDefinition.ArgType.STRING,
				"STOP (default) or SKIP when a cell is protected"))
			.permission(AgentPermission.WORLD_PLACE)
			.risk(RiskLevel.MEDIUM)
			.exposure(ToolExposure.PLANNER_INTERNAL)
			.build(), (call, ctx) -> {
				var corner1 = blockPosOf(call.arguments(), "x1", "y1", "z1");
				var corner2 = blockPosOf(call.arguments(), "x2", "y2", "z2");
				if (corner1 == null || corner2 == null) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.INVALID_ARGUMENT, "x1/y1/z1 and x2/y2/z2 required"));
				}
				String blockId = str(call.arguments().get("blockId"));
				if (blockId == null) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.INVALID_ARGUMENT, "blockId required"));
				}
				var region = dev.squire.server.world.BoundedRegion.ofCorners(
					corner1.getX(), corner1.getY(), corner1.getZ(),
					corner2.getX(), corner2.getY(), corner2.getZ());
				var pattern = dev.squire.server.task.executors.BuildStructureExecutor
					.Pattern.parse(str(call.arguments().get("pattern")));
				var cells = dev.squire.server.task.executors.BuildStructureExecutor
					.cellsOf(region, pattern);
				if (cells.size() > dev.squire.server.task.executors
						.BuildStructureExecutor.MAX_CELLS) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.CAPABILITY_SCOPE_VIOLATION,
						"structure of " + cells.size() + " cells is too large"));
				}
				int carried = ctx.avatar().items().countOf(
					OneShotWorldExecutors.itemId(blockId));
				if (carried <= 0) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.INSUFFICIENT_ITEM,
						"the agent carries no " + blockId + " to build with"));
				}
				String dimension = ctx.avatar().getWorld().getRegistryKey().getValue()
					.toString();
				Map<String, Object> params = new LinkedHashMap<>();
				params.put("x1", region.min().getX());
				params.put("y1", region.min().getY());
				params.put("z1", region.min().getZ());
				params.put("x2", region.max().getX());
				params.put("y2", region.max().getY());
				params.put("z2", region.max().getZ());
				params.put(dev.squire.server.task.executors.BuildStructureExecutor
					.PARAM_BLOCK_ID, blockId);
				params.put(dev.squire.server.task.executors.BuildStructureExecutor
					.PARAM_PATTERN, pattern.name());
				copyIfPresent(call.arguments(), params, "onProtected");
				Task task = new Task(ctx.body().agentId(), ctx.requesterId(),
					dev.squire.server.task.executors.BuildStructureExecutor.TYPE,
					TaskPriority.P3_USER_TASK,
					"build " + pattern + " " + blockId + " at " + region, null,
					dev.squire.server.task.executors.BuildStructureExecutor.structureBuilt(
						services, dimension, region, pattern, blockId),
					600L + 20L * cells.size(), RetryPolicy.DEFAULT, true, "f1", params);
				scheduler.submit(task, services.currentTick());
				return ToolResult.running(call.callId(), Map.of(
					"taskId", task.taskId().toString(),
					"plannedCells", cells.size(), "carried", carried));
			});

		// --- 第 1 期：蓝图。模型只能挑蓝图名和“开工”，形状、路线、
		// 材料校验、放置顺序全部在服务端。这和 cbp.plan_project 是同一套路数。 ---
		registry.register(ToolDefinition.builder("blueprint.place")
			.description("Place a blueprint ghost in front of the owner and report the "
				+ "bill of materials. Changes nothing in the world.")
			.arg(ArgDefinition.required("blueprintId", ArgDefinition.ArgType.STRING,
				"blueprint id, e.g. shelter_wood / watchtower / mine_outpost. Fixed "
					+ "templates work for every companion; parameterised `project/...` "
					+ "ids need the Engineer profession and enough level."))
			.permission(AgentPermission.QUERY)
			.risk(RiskLevel.LOW)
			.exposure(ToolExposure.MODEL_PUBLIC)
			.build(), (call, ctx) -> {
				var runtime = dev.squire.server.runtime.SquireRuntime.get();
				var owner = services.requester(ctx.requesterId());
				if (runtime == null || owner == null) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.PRECONDITION_FAILED, "owner is not online"));
				}
				var result = runtime.blueprintPlace(owner,
					str(call.arguments().get("blueprintId")));
				return result.success()
					? ToolResult.success(call.callId(), Map.of("report", result.message()))
					: ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.PRECONDITION_FAILED, result.message()));
			});

		registry.register(ToolDefinition.builder("project.start")
			.description("Start a multi-stage project for the owner: fulfil materials, "
				+ "hand them over, excavate, build, light, verify. The model picks a "
				+ "template name only; the server decomposes and schedules it.")
			.arg(ArgDefinition.required("blueprintId", ArgDefinition.ArgType.STRING,
				"blueprint id, e.g. mine_outpost / watchtower. Fixed templates work for "
					+ "every companion; parameterised `project/...` ids need the "
					+ "Engineer profession and enough level."))
			.permission(AgentPermission.WORLD_PLACE)
			.risk(RiskLevel.MEDIUM)
			.exposure(ToolExposure.MODEL_PUBLIC)
			.build(), (call, ctx) -> {
				var runtime = dev.squire.server.runtime.SquireRuntime.get();
				var owner = services.requester(ctx.requesterId());
				if (runtime == null || owner == null) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.PRECONDITION_FAILED, "owner is not online"));
				}
				var result = runtime.projectStart(owner,
					str(call.arguments().get("blueprintId")));
				return result.success()
					? ToolResult.running(call.callId(), Map.of("report", result.message()))
					: ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.PRECONDITION_FAILED, result.message()));
			});


		registry.register(ToolDefinition.builder("blueprint.build")
			.description("Start building the owner's placed blueprint. Materials are taken "
				+ "out of the companion's real backpack; it stops honestly when short.")
			.permission(AgentPermission.WORLD_PLACE)
			.risk(RiskLevel.MEDIUM)
			.exposure(ToolExposure.MODEL_PUBLIC)
			.build(), (call, ctx) -> {
				var runtime = dev.squire.server.runtime.SquireRuntime.get();
				var owner = services.requester(ctx.requesterId());
				if (runtime == null || owner == null) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.PRECONDITION_FAILED, "owner is not online"));
				}
				var result = runtime.blueprintBuild(owner);
				return result.success()
					? ToolResult.running(call.callId(), Map.of("report", result.message()))
					: ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.INSUFFICIENT_ITEM, result.message()));
			});

		registry.register(ToolDefinition.builder("gather.block")
			.description("Mine nearby source blocks until the agent holds `count` more of itemId.")
			.arg(ArgDefinition.required("blockId", ArgDefinition.ArgType.BLOCK_ID, "source block"))
			.arg(ArgDefinition.required("itemId", ArgDefinition.ArgType.ITEM_ID, "expected drop"))
			.arg(ArgDefinition.intRange("count", 1, 64, "how many to collect"))
			.permission(AgentPermission.WORLD_BREAK)
			.risk(RiskLevel.MEDIUM)
			.exposure(ToolExposure.PLANNER_INTERNAL)
			.build(), (call, ctx) -> {
				int count = intOf(call.arguments().get("count"), 1);
				String itemId = str(call.arguments().get("itemId"));
				int targetCount = ctx.avatar().inventory().countOf(itemId) + count;
				Task task = new Task(ctx.body().agentId(), ctx.requesterId(),
					GatherBlockExecutor.TYPE, TaskPriority.P3_USER_TASK,
					"gather " + count + " " + itemId,
					null, GatherBlockExecutor.hasItems(itemId, targetCount),
					600L + 400L * count, RetryPolicy.DEFAULT, true, "m2",
					Map.of("blockId", str(call.arguments().get("blockId")),
						"itemId", itemId, "count", count,
						"targetCount", targetCount));
				scheduler.submit(task, services.currentTick());
				return ToolResult.running(call.callId(), Map.of("taskId", task.taskId().toString()));
			});
	}

	// ------------------------------------------------------------------ crafting & delivery

	private static void registerInventoryAndCrafting(ToolRegistry registry,
			RuntimeServices services, TaskScheduler scheduler, TaskCompiler compiler) {
		registry.register(ToolDefinition.builder("crafting.craft")
			.description("Craft `count` of itemId using the agent's current materials and known recipes.")
			.arg(ArgDefinition.required("itemId", ArgDefinition.ArgType.ITEM_ID, "thing to craft"))
			.arg(ArgDefinition.intRange("count", 1, 64, "target total count"))
			.permission(AgentPermission.CRAFT)
			.risk(RiskLevel.MEDIUM)
			.exposure(ToolExposure.PLANNER_INTERNAL)
			.build(), (call, ctx) -> {
				String itemId = str(call.arguments().get("itemId"));
				int count = intOf(call.arguments().get("count"), 1);
				int targetCount = ctx.avatar().inventory().countOf(itemId) + count;
				try {
					Task task = singleTask(ctx, CraftRecipeExecutor.TYPE,
						"craft " + count + " " + itemId,
						CraftRecipeExecutor.hasProduced(itemId, targetCount),
						200L + 100L * count,
						Map.of("itemId", itemId, "count", count,
							"targetCount", targetCount));
					scheduler.submit(task, services.currentTick());
					return ToolResult.running(call.callId(),
						Map.of("taskId", task.taskId().toString()));
				} catch (RuntimeException e) {
					return ToolResult.failed(call.callId(),
						ErrorPayload.of(ErrorCode.NO_RECIPE, "no acquisition route for "
							+ e.getMessage() + "; obtainable items: "
							+ dev.squire.server.task.CraftPlanner.supportedItems()));
				}
			});

		registry.register(ToolDefinition.builder("task.acquire")
			.description("Full acquisition plan for `count` of itemId: mine raw materials, craft intermediates, then the item.")
			.arg(ArgDefinition.required("itemId", ArgDefinition.ArgType.ITEM_ID, "goal item"))
			.arg(ArgDefinition.intRange("count", 1, 64, "goal total count"))
			.permission(AgentPermission.TASK_CONTROL)
			.risk(RiskLevel.MEDIUM)
			.exposure(ToolExposure.PLANNER_INTERNAL)
			.build(), (call, ctx) -> {
				String itemId = str(call.arguments().get("itemId"));
				int count = intOf(call.arguments().get("count"), 1);
				try {
					List<Task> tasks = compiler.compileAcquire(ctx.body().agentId(),
						ctx.requesterId(), itemId, count);
					List<String> ids = tasks.stream().map(t -> t.taskId().toString()).toList();
					if (ids.isEmpty()) {
						return ToolResult.success(call.callId(),
							Map.of("note", "already have enough " + itemId));
					}
					return ToolResult.running(call.callId(), Map.of(
						"taskIds", ids, "steps", ids.size()));
				} catch (dev.squire.server.task.CraftPlanner.UnsupportedItemException e) {
					return ToolResult.failed(call.callId(),
						ErrorPayload.of(ErrorCode.NO_RECIPE, "no acquisition route for "
							+ e.getMessage() + "; obtainable items: "
							+ dev.squire.server.task.CraftPlanner.supportedItems()));
				}
			});

		registry.register(ToolDefinition.builder("inventory.give")
			.description("Hand `count` of itemId from the agent inventory to the requesting player.")
			.arg(ArgDefinition.required("itemId", ArgDefinition.ArgType.ITEM_ID, "item to give"))
			.arg(ArgDefinition.intRange("count", 1, 64, "how many"))
			.permission(AgentPermission.INVENTORY_WRITE)
			.risk(RiskLevel.MEDIUM)
			.exposure(ToolExposure.MODEL_PUBLIC)
			.build(), (call, ctx) -> {
				String itemId = str(call.arguments().get("itemId"));
				int count = intOf(call.arguments().get("count"), 1);
				ServerPlayerEntity requester = services.requester(ctx.requesterId());
				if (requester == null || !requester.isAlive()) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.ENTITY_NOT_FOUND, "requester offline or dead"));
				}
				var id = OneShotWorldExecutors.itemId(itemId);
				int delivered = 0;
				while (delivered < count && ctx.avatar().hasAtLeast(id, 1)) {
					ItemStack taken = ctx.avatar().extractItems(id, 1).stream()
						.findFirst().orElse(null);
					if (taken == null) {
						break;
					}
					requester.getInventory().insertStack(taken); // shrinks as it fits
					if (!taken.isEmpty()) {
						ctx.avatar().insertStack(taken); // overflow back to the avatar
						return ToolResult.failed(call.callId(), ErrorPayload.of(
							ErrorCode.INVENTORY_FULL, "player inventory full at "
								+ delivered + "/" + count));
					}
					delivered++;
				}
				if (delivered < count) {
					return ToolResult.failed(call.callId(), ErrorPayload.of(
						ErrorCode.INSUFFICIENT_ITEM,
						"agent holds only " + delivered + " of " + count + " " + itemId));
				}
				return ToolResult.success(call.callId(),
					Map.of("delivered", delivered, "itemId", itemId));
			});
	}

	// ------------------------------------------------------------------ helpers

	private static ToolResult submitSimple(dev.squire.common.protocol.ToolCall call,
			ToolExecutionContext ctx, TaskScheduler scheduler,
			RuntimeServices services, String goalVerb, String type,
			Map<String, Object> args) {
		Integer x = intOrNull(args.get("x"));
		Integer y = intOrNull(args.get("y"));
		Integer z = intOrNull(args.get("z"));
		if (x == null || y == null || z == null) {
			return ToolResult.failed(call.callId(), ErrorPayload.of(
				ErrorCode.INVALID_ARGUMENT, "x/y/z required"));
		}
		Map<String, Object> params = new LinkedHashMap<>(args);
		Task task = new Task(ctx.body().agentId(), ctx.requesterId(), type,
			TaskPriority.P3_USER_TASK, goalVerb + " block at " + x + "," + y + "," + z,
			null, OneShotWorldExecutors.nearBlock(x, y, z),
			200L, RetryPolicy.DEFAULT, true, "m2", params);
		scheduler.submit(task, services.currentTick());
		return ToolResult.running(call.callId(), Map.of("taskId", task.taskId().toString()));
	}

	private static Task baseTask(ToolExecutionContext ctx, String type, TaskPriority priority,
			String goal, dev.squire.server.task.TaskCondition success, long timeoutTicks,
			Map<String, Object> params) {
		return new Task(ctx.body().agentId(), ctx.requesterId(), type, priority, goal,
			null, success, timeoutTicks, RetryPolicy.DEFAULT, true, "m2", params);
	}

	private static Task singleTask(ToolExecutionContext ctx, String type, String goal,
			dev.squire.server.task.TaskCondition success, long timeoutTicks,
			Map<String, Object> params) {
		return baseTask(ctx, type, TaskPriority.P3_USER_TASK, goal, success, timeoutTicks, params);
	}

	private static double num(Object o) {
		return ((Number) o).doubleValue();
	}

	private static int intOf(Object o, int fallback) {
		return o instanceof Number n ? n.intValue() : fallback;
	}

	private static Integer intOrNull(Object o) {
		return o instanceof Number n ? Integer.valueOf(n.intValue()) : null;
	}

	private static String str(Object o) {
		return o instanceof String s ? s : null;
	}

	/**
	 * {@code items.fulfill} 的参数 → 结构化操作（凭空取物那一半）。
	 *
	 * <p>只做形状判断，物品 id 一律仍由 {@code ItemOperationParser} 过注册表校验：
	 * 模型给的名字和玩家打的字享受同一套把关。</p>
	 */
	private static java.util.Optional<dev.squire.server.nlu.ItemOperation>
			buildConjureOperation(Map<String, Object> args,
				dev.squire.server.i18n.Vocabulary vocabulary) {
		var delivery = "agent".equalsIgnoreCase(str(args.get("target")))
			? dev.squire.server.nlu.ItemOperation.Delivery.TO_AGENT_EQUIP
			: dev.squire.server.nlu.ItemOperation.Delivery.TO_PLAYER;
		var transform = "max".equalsIgnoreCase(str(args.get("enchant")))
			? dev.squire.server.nlu.ItemOperation.Transform.SET_MAX_ENCHANTS
			: dev.squire.server.nlu.ItemOperation.Transform.NONE;
		int count = Math.max(1, intOf(args.get("count"), 1));
		String shape = str(args.get("shape"));
		String material = str(args.get("material"));
		// 说了材质就按整套走，哪怕 shape 没填——模型漏一个字段不该让整次调用作废。
		if ("armor_set".equalsIgnoreCase(shape) || "set".equalsIgnoreCase(shape)
				|| (shape == null && material != null && str(args.get("itemId")) == null)) {
			return dev.squire.server.nlu.ItemOperationParser.armorSet(delivery, material,
				count, transform, vocabulary);
		}
		return dev.squire.server.nlu.ItemOperationParser.piece(delivery,
			str(args.get("itemId")), count, transform, vocabulary);
	}

	/**
	 * {@code items.edit} 的参数 → 结构化操作（改动已有物品那一半）。
	 *
	 * <p>附魔名和物品名一样要过注册表校验：模型写「荆棘」或
	 * {@code minecraft:thorns} 都行，写一个不存在的就整次拒绝，绝不猜。</p>
	 */
	private static java.util.Optional<dev.squire.server.nlu.ItemOperation>
			buildEditOperation(Map<String, Object> args,
				dev.squire.server.i18n.Vocabulary vocabulary) {
		if (vocabulary == null || !vocabulary.isUsable()) {
			return java.util.Optional.empty();
		}
		var transform = enumOrNull(dev.squire.server.nlu.ItemOperation.Transform.class,
			str(args.get("transform")));
		if (transform == null
				|| transform == dev.squire.server.nlu.ItemOperation.Transform.NONE) {
			return java.util.Optional.empty();
		}
		var scope = enumOrNull(dev.squire.server.nlu.ItemOperation.Scope.class,
			str(args.get("scope")));
		if (scope == null) {
			scope = dev.squire.server.nlu.ItemOperation.Scope.PLAYER_EQUIPPED;
		}
		if (scope == dev.squire.server.nlu.ItemOperation.Scope.CONJURE) {
			return java.util.Optional.empty(); // 凭空造走 items.fulfill
		}
		var kind = enumOrNull(
			dev.squire.server.nlu.ItemOperation.Selector.Filter.Kind.class,
			str(args.get("selector")));
		if (kind == null) {
			kind = dev.squire.server.nlu.ItemOperation.Selector.Filter.Kind.ARMOR;
		}
		String itemId = str(args.get("itemId"));
		if (kind == dev.squire.server.nlu.ItemOperation.Selector.Filter.Kind.ITEM) {
			var resolved = itemId == null ? java.util.Optional
				.<dev.squire.server.i18n.ItemAliasResolver.Resolution>empty()
				: vocabulary.items().resolve(itemId);
			if (resolved.isEmpty()) {
				return java.util.Optional.empty();
			}
			itemId = resolved.get().itemId();
		} else {
			itemId = null;
		}
		java.util.List<String> enchantments = new java.util.ArrayList<>();
		String raw = str(args.get("enchantments"));
		if (raw != null) {
			for (String part : raw.split("[,，、]")) {
				vocabulary.enchantments().resolve(part.trim())
					.ifPresent(id -> {
						if (!enchantments.contains(id)) {
							enchantments.add(id);
						}
					});
			}
		}
		if (transform.needsEnchantmentList() && enchantments.isEmpty()) {
			return java.util.Optional.empty();
		}
		var selector = itemId == null
			? dev.squire.server.nlu.ItemOperation.Selector.Filter.of(kind)
			: dev.squire.server.nlu.ItemOperation.Selector.Filter.item(itemId);
		return java.util.Optional.of(dev.squire.server.nlu.ItemOperation.edit(
			scope, selector, transform, enchantments, describeEdit(transform)));
	}

	private static String describeEdit(
			dev.squire.server.nlu.ItemOperation.Transform transform) {
		return switch (transform) {
			case REMOVE_ENCHANT -> "去掉指定附魔";
			case ADD_ENCHANT -> "加上指定附魔";
			case CLEAR_ENCHANTS -> "清空全部附魔";
			case SET_MAX_ENCHANTS -> "附满所有可用附魔";
			case REPAIR -> "修满耐久";
			case NONE -> "不做改动";
		};
	}

	/** 模型给的枚举名大小写不定，统一按大写查；查不到就是 null，由调用方拒绝。 */
	private static <E extends Enum<E>> E enumOrNull(Class<E> type, String name) {
		if (name == null || name.isBlank()) {
			return null;
		}
		try {
			return Enum.valueOf(type, name.trim().toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
