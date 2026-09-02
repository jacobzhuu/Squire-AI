package dev.squire.server.runtime;

import java.util.Optional;
import java.util.UUID;

import dev.squire.common.protocol.AgentRequest;
import dev.squire.server.agent.AgentRegistry;
import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.metrics.TickProfiler.Section;
import dev.squire.server.provider.ProviderRegistry;
import dev.squire.server.threading.AsyncBridge;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;

/**
 * M1 runtime: executes owner control intents against the agent registry.
 *
 * <p>Every intent passes the same pipeline regardless of origin (command or FastPath
 * chat): sender role check → registry resolve → body mutation (spec section 14.2 —
 * FastPath never touches the world directly).</p>
 *
 * <p>LLM availability is irrelevant here: all M1 capabilities work with no provider
 * configured (spec DoD "LLM 不可用时上述能力正常").</p>
 */
public final class SquireRuntime {
	public enum ControlIntent {
		FOLLOW, STAY, STOP, HOME_RETURN, STATUS, DISMISS
	}

	/** Sender role toward one avatar (spec section 13); write intents need OWNER or ADMIN. */
	public enum SenderRole {
		PUBLIC, TRUSTED, OWNER, ADMIN
	}

	public record ExecutionResult(boolean success, String feedbackKey, String errorCode,
			String message) {
		static ExecutionResult ok(String key, String message) {
			return new ExecutionResult(true, key, null, message);
		}

		static ExecutionResult fail(String key, String message) {
			return fail(key, null, message);
		}

		/**
		 * 一次「参数不对」的拒绝。给包外的工具层用——它们需要能造一个失败结果，
		 * 但不该看得见内部那套 feedback key。
		 */
		public static ExecutionResult refused(String message) {
			return new ExecutionResult(false, "feedback.refused", null, message);
		}

		static ExecutionResult fail(String key, String errorCode, String message) {
			return new ExecutionResult(false, key, errorCode, message);
		}
	}

	private static SquireRuntime instance;

	/**
	 * 即使 adminCommands 关掉也照常放行的 COMMAND 类工具：取物是伙伴的基础能力，
	 * 不是服务器管理员才用得上的结构化指令。
	 */
	private static final java.util.Set<String> ALWAYS_ALLOWED_COMMAND_TOOLS =
		java.util.Set.of("minecraft.command.give", "items.fulfill", "items.edit",
			"inventory.acquire_self", "minecraft.command.effect", "player.teleport");

	private final SquireBuildService buildService = new SquireBuildService(this);
	private final SquireItemService itemService = new SquireItemService(this);
	private final SquireRoster roster = new SquireRoster(this);
	private final SquireBlueprintService blueprintService =
		new SquireBlueprintService(this);
	private final SquireProfileService profileService =
		new SquireProfileService(this);
	private final SquireProjectService projectService =
		new SquireProjectService(this);
	/** Local, deterministic behaviour behind the first three autonomy levels. */
	private final AutonomyController autonomyController = new AutonomyController(this);
	/** 第 3 期：大目标分解。在构造函数里接线（需要 goals/blueprints 先就绪）。 */
	private final dev.squire.server.project.ProjectCoordinator projectCoordinator;
	/** 第 2 期：熟练度记账。调用点只有一个，在调度器的终态分支上。 */
	private final dev.squire.server.profile.ProgressionService progression =
		new dev.squire.server.profile.ProgressionService();
	/**
	 * 职业系统的平衡表。开服时从 {@code config/squire/profession.json} 读一次；
	 * 读不到就用内置默认表，绝不半配置化。
	 */
	private final dev.squire.server.profession.ProfessionConfig professionConfig =
		dev.squire.server.profession.ProfessionConfig.load(professionConfigFile());
	/** Server-authoritative recall health/cooldown/buff table. */
	private final dev.squire.server.item.BellReviveConfig bellReviveConfig =
		dev.squire.server.item.BellReviveConfig.load(recallBellConfigFile());
	/** 职业等级 / 经验 / 晋升的记账。和熟练度一样，只有一个入账口。 */
	private final dev.squire.server.profession.ProfessionService professions =
		new dev.squire.server.profession.ProfessionService(professionConfig);
	/** 「他到底参没参与这场战斗」的台账，以及同类怪的五分钟窗口。不落盘。 */
	private final dev.squire.server.profession.GuardCombatLedger combatLedger =
		new dev.squire.server.profession.GuardCombatLedger();
	private final SquireProfessionService professionService =
		new SquireProfessionService(this);
	private final SquirePersonalityService personalityService =
		new SquirePersonalityService(this);
	/** 工程师的参数层：模板、结构变体、多层、镜像、模块、预设。 */
	private final SquireEngineerService engineerService =
		new SquireEngineerService(this);

	final MinecraftServer server;
	private final AgentRegistry agents;
	private final dev.squire.server.tool.ToolRegistry tools =
		new dev.squire.server.tool.ToolRegistry();
	private final dev.squire.server.tool.ToolGateway gateway;
	private final dev.squire.server.task.TaskScheduler scheduler =
		new dev.squire.server.task.TaskScheduler();
	private final dev.squire.server.task.TaskCompiler compiler;
	private final ConversationOrchestrator conversations;
	/** 方案 A4：可恢复任务的声明式持久层（关服导出 / 开服恢复）。 */
	private final dev.squire.server.task.TaskStateStore taskStates;
	/** 方案 A2/B2：在线直发、离线补投的玩家通知通道（队列持久化）。 */
	private final PlayerNotifier notifier;
	/** 玩家自定义的快捷指令（名字 → 一句原话）。 */
	private final dev.squire.server.shortcut.ShortcutStore shortcuts;
	private final dev.squire.server.goal.GoalStateStore goalStates;
	private final dev.squire.server.goal.GoalCoordinator goals;
	private final TurnStateStore turnStates;

	// ------------------------------------------------------------------ security (M3)
	private final dev.squire.server.security.Killswitch killswitch =
		new dev.squire.server.security.Killswitch();
	private final dev.squire.server.security.PermissionManager permissions =
		new dev.squire.server.security.PermissionManager();
	private final dev.squire.server.security.CapabilityStore capabilityStore =
		new dev.squire.server.security.CapabilityStore();
	private final dev.squire.server.security.QuotaLedger quotas =
		new dev.squire.server.security.QuotaLedger();
	private final dev.squire.server.world.UndoJournal undoJournal =
		new dev.squire.server.world.UndoJournal();
	private final dev.squire.server.world.WorldEditor worldEditor =
		new dev.squire.server.world.WorldEditor(undoJournal, null);
	private final dev.squire.server.security.ConfirmationService confirmations =
		new dev.squire.server.security.ConfirmationService();
	/** 方案 F3：高风险操作的持久待确认记录（确认后由服务器重放，不再问模型）。 */
	private final dev.squire.server.security.PendingOperationStore pendingOperations;
	/** M4 extension platform: third-party tools/sensors/task types (spec §53-59). */
	private final dev.squire.server.ext.ExtensionManager extensions =
		new dev.squire.server.ext.ExtensionManager(tools, scheduler);
	/** M4 MCP stack: remote tool bridges; trust ONLY from the persisted store (§52). */
	private final dev.squire.server.mcp.McpTrustStore mcpTrusts =
		new dev.squire.server.mcp.McpTrustStore(
			net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
				.resolve("squire").resolve("mcp-trust.json"));
	private final dev.squire.server.mcp.McpClientBridge mcp =
		new dev.squire.server.mcp.McpClientBridge(extensions, mcpTrusts);
	/** Assigned in the constructor (needs gateway/scheduler/agents wired first). */
	private final dev.squire.server.automation.AutomationEngine automation;
	/** M5b/§50: explicit command-block materialization stack; ships OFF (§94). */
	private final dev.squire.server.cbp.CbpWorkspace cbpWorkspace;
	private final dev.squire.server.cbp.CbpRegistry cbpRegistry;
	private final dev.squire.server.cbp.CbpMaterializer cbp;
	/** One protection seam shared by WorldEditor and the CBP materializer (§60). */
	final dev.squire.server.world.ProtectionAdapter protectionAdapter;
	/** 方案 D1：长期护卫策略与本地战斗运行时（LLM 不参与任何一拍）。 */
	private final dev.squire.server.combat.GuardRuntime guards;
	/** 第 1 期：蓝图注册表、摆放、材料账与幽灵预览。 */
	private final dev.squire.server.blueprint.BlueprintManager blueprints;
	/**
	 * §74 中文词汇表：物品名 + 附魔名，每次解析都过注册表校验。
	 *
	 * <p>附魔那张表是后补的。之前只有物品表，于是「荆棘」这类词在系统里根本不存在，
	 * 任何点名附魔的请求都不是解析失败，而是压根没有词可查。</p>
	 */
	private final dev.squire.server.i18n.Vocabulary vocabulary = buildVocabulary();

	/**
	 * 词表 + 注册表译名。
	 *
	 * <p>接上译名这一步是「装了四十个模组、他只认原版物品」的解法：每个模组自带官方
	 * 中文名，单人游戏里服务端读到的就是玩家物品栏里印着的那几个字。于是
	 * 「给我 8 个安山合金」认得出来，「还差 12 个 create:andesite_alloy」也会说人话，
	 * 而我们一张模组物品表都不用手写、也不会过期。</p>
	 */
	private static dev.squire.server.i18n.Vocabulary buildVocabulary() {
		var built = dev.squire.server.i18n.Vocabulary.fromResources(
			id -> net.minecraft.registry.Registries.ITEM
				.containsId(new net.minecraft.util.Identifier(id)),
			id -> net.minecraft.registry.Registries.ENCHANTMENT
				.containsId(new net.minecraft.util.Identifier(id)),
			id -> net.minecraft.registry.Registries.STATUS_EFFECT
				.containsId(new net.minecraft.util.Identifier(id)),
			id -> net.minecraft.registry.Registries.ENTITY_TYPE
				.containsId(new net.minecraft.util.Identifier(id)));
		built.items().useRegistryNames(
			new dev.squire.server.i18n.ItemAliasResolver.RegistryNames() {
				@Override
				public String nameOf(String itemId) {
					var id = net.minecraft.util.Identifier.tryParse(itemId);
					if (id == null || !net.minecraft.registry.Registries.ITEM.containsId(id)) {
						return null;
					}
					return net.minecraft.registry.Registries.ITEM.get(id).getName().getString();
				}

				@Override
				public java.util.List<String> allItemIds() {
					return net.minecraft.registry.Registries.ITEM.getIds().stream()
						.map(net.minecraft.util.Identifier::toString).toList();
				}
			});
		built.entities().useRegistryNames(id -> {
			var parsed = net.minecraft.util.Identifier.tryParse(id);
			if (parsed == null) {
				return null;
			}
			return net.minecraft.registry.Registries.ENTITY_TYPE.getOrEmpty(parsed)
				.map(type -> type.getName().getString()).orElse(null);
		}, () -> net.minecraft.registry.Registries.ENTITY_TYPE.getIds().stream()
			.map(net.minecraft.util.Identifier::toString).toList());
		built.enchantments().useRegistryNames(id -> {
			var parsed = net.minecraft.util.Identifier.tryParse(id);
			if (parsed == null) {
				return null;
			}
			return net.minecraft.registry.Registries.ENCHANTMENT.getOrEmpty(parsed)
				// 附魔的显示名带等级，这里只要名字本身。
				.map(enchantment -> enchantment.getName(1).getString()
					.replaceAll("\\s+[IVXLC]+$", ""))
				.orElse(null);
		}, () -> net.minecraft.registry.Registries.ENCHANTMENT.getIds().stream()
			.map(net.minecraft.util.Identifier::toString).toList());
		built.effects().useRegistryNames(id -> {
			var parsed = net.minecraft.util.Identifier.tryParse(id);
			if (parsed == null) {
				return null;
			}
			return net.minecraft.registry.Registries.STATUS_EFFECT.getOrEmpty(parsed)
				.map(effect -> effect.getName().getString()).orElse(null);
		}, () -> net.minecraft.registry.Registries.STATUS_EFFECT.getIds().stream()
			.map(net.minecraft.util.Identifier::toString).toList());
		return built;
	}
	/** Spec §94: WorldEdit ships OFF; admins enable explicitly. */
	private volatile boolean worldEditEnabled = false;
	/** Spec §94: structured admin commands ship OFF. */
	private volatile boolean adminCommandsEnabled = false;
	/** Spec §94: materialized CBP ships OFF. */
	private volatile boolean cbpEnabled = false;

	/** Standard M2 capability grant for owner-requested turns (spec section 30). */
	private static final java.util.EnumSet<dev.squire.server.tool.AgentPermission>
		STANDARD_CAPABILITIES = java.util.EnumSet.of(
			dev.squire.server.tool.AgentPermission.MOVE,
			dev.squire.server.tool.AgentPermission.INVENTORY_READ,
			dev.squire.server.tool.AgentPermission.INVENTORY_WRITE,
			dev.squire.server.tool.AgentPermission.WORLD_BREAK,
			dev.squire.server.tool.AgentPermission.WORLD_PLACE,
			dev.squire.server.tool.AgentPermission.WORLD_EDIT,
			dev.squire.server.tool.AgentPermission.CRAFT,
			dev.squire.server.tool.AgentPermission.COMBAT,
			dev.squire.server.tool.AgentPermission.HEAL,
			dev.squire.server.tool.AgentPermission.TASK_CONTROL,
			dev.squire.server.tool.AgentPermission.COMMAND,
			dev.squire.server.tool.AgentPermission.QUERY);

	final dev.squire.server.task.executors.RuntimeServices runtimeServices =
		new dev.squire.server.task.executors.RuntimeServices() {
			@Override
			public MinecraftServer server() {
				return SquireRuntime.this.server;
			}

			@Override
			public AvatarEntity avatar(UUID agentId) {
				return agents.resolveByAgentId(agentId).orElse(null);
			}

			@Override
			public long currentTick() {
				return server.getOverworld().getTime();
			}

			@Override
			public String itemDisplayName(net.minecraft.util.Identifier itemId) {
				return vocabulary.items().displayName(itemId.toString());
			}

			@Override
			public ServerPlayerEntity requester(UUID playerId) {
				ServerPlayerEntity managed = server.getPlayerManager().getPlayer(playerId);
				if (managed != null) {
					return managed;
				}
				// fallback for player entities living in a world but not in the manager
				for (ServerWorld world : server.getWorlds()) {
					if (world.getEntity(playerId) instanceof ServerPlayerEntity player) {
						return player;
					}
				}
				return null;
			}

			@Override
			public dev.squire.server.profession.ProfessionConfig professionConfig() {
				return SquireRuntime.this.professionConfig;
			}

			@Override
			public dev.squire.server.world.WorldEditor worldEditor() {
				return SquireRuntime.this.worldEditor;
			}

			@Override
			public dev.squire.server.security.CapabilityStore capabilities() {
				return SquireRuntime.this.capabilityStore;
			}

			@Override
			public dev.squire.server.world.ProtectionAdapter protection() {
				return SquireRuntime.this.protectionAdapter;
			}

			@Override
			public dev.squire.server.blueprint.BlueprintManager blueprints() {
				return SquireRuntime.this.blueprints;
			}

			@Override
			public boolean consumeProjectMaterial(UUID projectId,
					net.minecraft.util.Identifier itemId, int count) {
				return SquireRuntime.this.projectCoordinator != null
					&& SquireRuntime.this.projectCoordinator.consumeMaterial(projectId,
						itemId, count);
			}

			@Override
			public int projectMaterialCount(UUID projectId,
					net.minecraft.util.Identifier itemId) {
				return SquireRuntime.this.projectCoordinator == null ? 0
					: SquireRuntime.this.projectCoordinator.reservedCount(projectId, itemId);
			}

			@Override
			public dev.squire.server.profile.SquireProfile profile(UUID agentId) {
				return SquireRuntime.this.profileOfAgent(agentId);
			}
		};

	private SquireRuntime(MinecraftServer server) {
		this.server = server;
		// 权限页的勾选跨重启存活（PermissionStore 只存显式 grant/revoke）。
		this.permissions.attachStore(new dev.squire.server.security.PermissionStore(
			() -> server.getSavePath(net.minecraft.util.WorldSavePath.ROOT)
				.resolve("squire").resolve("permissions.json")));
		this.shortcuts = new dev.squire.server.shortcut.ShortcutStore(
			() -> server.getSavePath(net.minecraft.util.WorldSavePath.ROOT)
				.resolve("squire").resolve("shortcuts.json"));
		this.shortcuts.load();
		this.taskStates = new dev.squire.server.task.TaskStateStore(() -> server
			.getSavePath(net.minecraft.util.WorldSavePath.ROOT)
			.resolve("squire").resolve("tasks.json"));
		this.notifier = new PlayerNotifier(server,
			() -> server.getSavePath(net.minecraft.util.WorldSavePath.ROOT)
				.resolve("squire").resolve("notifications.json"));
		this.goalStates = new dev.squire.server.goal.GoalStateStore(() -> server
			.getSavePath(net.minecraft.util.WorldSavePath.ROOT)
			.resolve("squire").resolve("goals.json"));
		this.turnStates = new TurnStateStore(() -> server
			.getSavePath(net.minecraft.util.WorldSavePath.ROOT)
			.resolve("squire").resolve("turns.json"));
		this.pendingOperations = new dev.squire.server.security.PendingOperationStore(
			() -> server.getSavePath(net.minecraft.util.WorldSavePath.ROOT)
				.resolve("squire").resolve("pending-operations.json"));
		undoJournal.setStorage(() -> server
			.getSavePath(net.minecraft.util.WorldSavePath.ROOT)
			.resolve("squire").resolve("undo.nbt"));
		this.protectionAdapter =
			new dev.squire.server.world.VanillaSpawnProtection(server);
		this.agents = new AgentRegistry(server);
		this.gateway = new dev.squire.server.tool.ToolGateway(tools);
		this.compiler = new dev.squire.server.task.TaskCompiler(runtimeServices, scheduler);
		this.goals = new dev.squire.server.goal.GoalCoordinator(compiler, scheduler,
			runtimeServices, notifier, goalStates);
		this.guards = new dev.squire.server.combat.GuardRuntime(runtimeServices,
			() -> dev.squire.server.agent.SquireAgentStateStore.get(server));
		this.conversations = new ConversationOrchestrator(server, this, gateway,
			turnStates);
		this.blueprints = new dev.squire.server.blueprint.BlueprintManager(
			new dev.squire.server.blueprint.BlueprintPlacementStore(
				() -> server.getSavePath(net.minecraft.util.WorldSavePath.ROOT)
					.resolve("squire").resolve("blueprints.json")),
			() -> this.server);
		// 幽灵预览的颜色要看伙伴真实背包里有多少料。
		this.blueprints.attachInventoryLookup(agentId -> {
			AvatarEntity avatar = agents.resolveByAgentId(agentId).orElse(null);
			return avatar == null ? null : avatar.items();
		});
		this.projectCoordinator = new dev.squire.server.project.ProjectCoordinator(
			runtimeServices, scheduler, goals, blueprints, notifier,
			new dev.squire.server.project.ProjectStore(
				() -> server.getSavePath(net.minecraft.util.WorldSavePath.ROOT)
					.resolve("squire").resolve("projects.json")),
			ownerId -> agents.resolveForOwner(ownerId).orElse(null),
			this::profileOfAgent, protectionAdapter);
		wireSecurity();
		registerExecutors();
		// 第 2 期：熟练度只在任务终态成功时记一次。这是唯一的记账入口。
		scheduler.setTerminalListener(this::onTaskTerminal);
		dev.squire.server.tool.builtin.BuiltinTools.register(tools,
			runtimeServices, scheduler, compiler);
		mcp.setCompletionExecutor(r ->
			dev.squire.server.threading.AsyncBridge.runOnServer(server, r));
		mcp.setClock(this::currentTick); // 方案 I2：期限与熔断冷却用服务器 tick
		this.automation = buildAutomationEngine();
		this.cbpWorkspace = new dev.squire.server.cbp.CbpWorkspace(() -> server
			.getSavePath(net.minecraft.util.WorldSavePath.ROOT)
			.resolve("squire").resolve("workspaces.json"));
		this.cbpRegistry = new dev.squire.server.cbp.CbpRegistry(() -> server
			.getSavePath(net.minecraft.util.WorldSavePath.ROOT)
			.resolve("squire").resolve("cbp-projects.json"));
		this.cbp = buildCbpMaterializer();
	}

	/**
	 * M5b/§50: the ONLY command-block placement path. Ships disabled (§94);
	 * requires an explicit owner workspace and per-project confirmation.
	 */
	private dev.squire.server.cbp.CbpMaterializer buildCbpMaterializer() {
		dev.squire.server.cbp.CbpMaterializer.WorldLookup worlds =
			dimensionKey -> {
				for (ServerWorld world : server.getWorlds()) {
					if (world.getRegistryKey().getValue().toString().equals(dimensionKey)) {
						return world;
					}
				}
				return null;
			};
		dev.squire.server.cbp.CbpMaterializer.Notifier notifier =
			(ownerId, message) -> {
				ServerPlayerEntity player = server.getPlayerManager().getPlayer(ownerId);
				if (player != null) {
					player.sendMessage(Text.literal(message), false);
				} else {
					dev.squire.SquireMod.LOGGER.info("[Squire] cbp-notify(offline): {}",
						message);
				}
			};
		dev.squire.server.cbp.CbpMaterializer materializer =
			new dev.squire.server.cbp.CbpMaterializer(cbpWorkspace, capabilityStore,
				confirmations, undoJournal, cbpRegistry, gateway.audit(), worlds,
				notifier);
		materializer.setProtection(protectionAdapter);
		return materializer;
	}

	/**
	 * M5/§49: long-term conditional logic runs HERE through the same gateway —
	 * never as hidden command blocks (ADR-009). Ships disabled (§94).
	 */
	private dev.squire.server.automation.AutomationEngine buildAutomationEngine() {
		dev.squire.server.automation.AutomationEngine.NodeDispatcher dispatcher =
			(toolName, args, ownerId, agentId) -> {
				AvatarEntity avatar = agents.resolveByAgentId(agentId).orElse(null);
				if (avatar == null) {
					return dev.squire.common.protocol.ToolResult.failed(
						UUID.randomUUID(), dev.squire.common.errors.ErrorPayload.of(
							dev.squire.common.errors.ErrorCode.INTERNAL_ERROR,
							"automation agent has no avatar"));
				}
				var ctx = new dev.squire.server.tool.ToolExecutionContext() {
					@Override
					public dev.squire.api.body.AgentBody body() {
						return avatar;
					}

					@Override
					public AvatarEntity avatar() {
						return avatar;
					}

					@Override
					public MinecraftServer server() {
						return SquireRuntime.this.server;
					}

					@Override
					public UUID requesterId() {
						return ownerId;
					}

					@Override
					public long tick() {
						return currentTick();
					}
				};
				// 自动化图的节点是玩家在服务器上事先编译好的，不是模型现场想出来的，
				// 所以身份是 PLANNER 而不是 MODEL。用 MODEL 会被 exposure 检查挡在
				// 门外：base.lights.set 这类 PLANNER_INTERNAL 工具必然 TOOL_NOT_VISIBLE，
				// 于是自动化一跑就失败。现有测试没覆盖到这条路径。
				return gateway().dispatch(
					new dev.squire.common.protocol.ToolCall(UUID.randomUUID(),
						toolName, args),
					dev.squire.server.tool.CallerIdentity.planner(agentId),
					ctx, capabilitiesOf(agentId));
			};
		dev.squire.server.automation.AutomationEngine.Notifier notifier =
			(ownerId, message) -> {
				ServerPlayerEntity player = server.getPlayerManager().getPlayer(ownerId);
				if (player != null) {
					player.sendMessage(net.minecraft.text.Text.literal(message), false);
				} else {
					dev.squire.SquireMod.LOGGER.info("[Squire] notify(offline owner): {}",
						message);
				}
			};
		dev.squire.server.automation.AutomationEngine.WorldProbe probe =
			new dev.squire.server.automation.AutomationEngine.WorldProbe() {
				@Override
				public boolean ownerOnline(UUID ownerId) {
					return server.getPlayerManager().getPlayer(ownerId) != null;
				}

				@Override
				public java.util.Set<UUID> playersInRegion(String dimension,
						dev.squire.server.world.BoundedRegion region) {
					java.util.Set<UUID> found = new java.util.HashSet<>();
					for (ServerWorld world : server.getWorlds()) {
						if (!world.getRegistryKey().getValue().toString()
							.equals(dimension)) {
							continue;
						}
						for (ServerPlayerEntity p : world.getPlayers()) {
							if (region.contains(p.getBlockPos())) {
								found.add(p.getUuid());
							}
						}
					}
					return found;
				}

				@Override
				public long dayTime() {
					return server.getOverworld().getTimeOfDay();
				}
			};
		return new dev.squire.server.automation.AutomationEngine(dispatcher, notifier,
			probe, scheduler, gateway.audit(),
			() -> server.getSavePath(net.minecraft.util.WorldSavePath.ROOT)
				.resolve("squire").resolve("automations.json"));
	}

	/**
	 * Gateway pipeline stages that need runtime state: policy (killswitch + feature
	 * flags), owner permission nodes (§61), quotas (§66) and capabilities (§30).
	 */
	private void wireSecurity() {
		dev.squire.server.metrics.SquireMetrics shared = conversations.metrics();
		gateway.setMetrics(shared); // §75 shared counters
		scheduler.setMetrics(shared);
		worldEditor.setMetrics(shared);
		undoJournal.setMetrics(shared);
		mcp.setMetrics(shared);
		gateway.setReplayRecorder(replay); // §76 debug replay (off by default)
		gateway.setPolicyGate((definition, caller, context) -> {
			// M4/§55 fail-closed trust: an untrusted or blocked provider tool that
			// LOOKS privileged (write-class, capability-gated, destructive hint or
			// HIGH risk) is denied outright. Hints can only make policy stricter.
			if (!definition.sourceTrust().isTrusted() && privilegedShape(definition)) {
				return dev.squire.common.errors.ErrorPayload.of(
					dev.squire.common.errors.ErrorCode.POLICY_DENIED,
					"tool '" + definition.name() + "' from " + definition.sourceTrust()
						+ " provider '" + definition.origin()
						+ "' is not approved for privileged use");
			}
			if (killswitch.isActive() && isWriteOrCommand(definition)) {
				return dev.squire.common.errors.ErrorPayload.of(
					dev.squire.common.errors.ErrorCode.POLICY_DENIED,
					"killswitch active — world writes and commands are denied");
			}
			if (definition.requiresCapability() && !worldEditEnabled) {
				return dev.squire.common.errors.ErrorPayload.of(
					dev.squire.common.errors.ErrorCode.POLICY_DENIED,
					"world editing is disabled on this server");
			}
			// 取物是伙伴的日常能力，不是"可选的结构化管理指令"：关掉 adminCommands
			// 的服务器上它也必须能用，否则「给我一套下界合金」在模型这条路上直接被
			// 策略拒掉，而同一句话走 FastPath 却是通的。
			if (!adminCommandsEnabled && definition.permission()
					== dev.squire.server.tool.AgentPermission.COMMAND
					&& !ALWAYS_ALLOWED_COMMAND_TOOLS.contains(definition.name())) {
				return dev.squire.common.errors.ErrorPayload.of(
					dev.squire.common.errors.ErrorCode.POLICY_DENIED,
					"optional structured commands are disabled on this server");
			}
			return null;
		});
		gateway.setNodeChecker((playerId, node) -> {
			ServerPlayerEntity player = runtimeServices.requester(playerId);
			return player != null && permissions.has(player, node);
		});
		gateway.setOwnerVerifier((senderId, agentId) -> {
			ServerPlayerEntity sender = runtimeServices.requester(senderId);
			if (sender != null && sender.hasPermissionLevel(2)) {
				return true; // admins may direct any agent
			}
			AvatarEntity avatar = agents.resolveByAgentId(agentId).orElse(null);
			return avatar != null && agents.isOwnerOf(senderId, avatar);
		});
		gateway.setQuotas(quotas);
		// §23 第二道闸：模型看不见的工具，伪造调用也执行不了。
		gateway.setProfessionLookup(this::professionOfAgent);
		gateway.setCapabilityStore(capabilityStore);
		gateway.setConfirmationAuthorizer((playerId, toolName, fingerprint) ->
			confirmations.matchesAndConsume(playerId, toolName, fingerprint,
				currentTick()));
		worldEditor.setProtection(protectionAdapter);
		undoJournal.setProtection(protectionAdapter); // 方案 F4: 撤销同样受保护约束
		// (the CBP materializer receives the same adapter when it is built later
		// in the constructor — see buildCbpMaterializer)
	}

	private static boolean isWriteOrCommand(dev.squire.server.tool.ToolDefinition definition) {
		return switch (definition.permission()) {
			case WORLD_BREAK, WORLD_PLACE -> true;
			default -> definition.name().startsWith("command.")
				|| definition.name().startsWith("minecraft.command.");
		};
	}

	/**
	 * True when a tool's shape demands elevated trust (M4): anything write-class,
	 * capability-bound, flagged destructive, or declared HIGH risk. Read-only LOW/MEDIUM
	 * queries from untrusted providers remain callable (spec §88: example mod tools work
	 * without core changes).
	 */
	private static boolean privilegedShape(dev.squire.server.tool.ToolDefinition definition) {
		return isWriteOrCommand(definition)
			|| definition.permission() == dev.squire.server.tool.AgentPermission.WORLD_EDIT
			|| definition.requiresCapability()
			|| definition.destructive()
			|| definition.risk() == dev.squire.server.tool.RiskLevel.HIGH;
	}

	private void registerExecutors() {
		scheduler.register(new dev.squire.server.task.executors.MoveToExecutor(runtimeServices));
		scheduler.register(new dev.squire.server.task.executors.GatherBlockExecutor(runtimeServices));
		scheduler.register(new dev.squire.server.task.executors.CraftRecipeExecutor(runtimeServices));
	// —— 已停用（方向性收缩：真身只保留跟随/护卫/救援，取物改走指令兑现）——
	// 保留代码而非删除，方便日后回退。当前无任何调用方。
//		scheduler.register(new dev.squire.server.task.executors.OneShotWorldExecutors.BreakOne(
//			runtimeServices));
	// —— 已停用（方向性收缩：真身只保留跟随/护卫/救援，取物改走指令兑现）——
	// 保留代码而非删除，方便日后回退。当前无任何调用方。
//		scheduler.register(new dev.squire.server.task.executors.OneShotWorldExecutors.PlaceOne(
//			runtimeServices));
		scheduler.register(new dev.squire.server.task.executors.GuardTaskExecutor(
			runtimeServices));
		scheduler.register(new dev.squire.server.task.executors.HealTaskExecutor(
			runtimeServices));
		scheduler.register(new dev.squire.server.task.executors.OwnerAidExecutor(
			runtimeServices));
		scheduler.register(new dev.squire.server.task.executors.SmeltTaskExecutor(
			runtimeServices));
		scheduler.register(new dev.squire.server.task.executors.DeliverToOwnerExecutor(
			runtimeServices));
	// —— 已停用（方向性收缩：真身只保留跟随/护卫/救援，取物改走指令兑现）——
	// 保留代码而非删除，方便日后回退。当前无任何调用方。
//		scheduler.register(new dev.squire.server.task.executors.WorldEditExecutor(
//			runtimeServices));
		// 方案 C3：容器物流
		scheduler.register(new dev.squire.server.task.executors.ContainerExecutors.Deposit(
			runtimeServices));
		scheduler.register(new dev.squire.server.task.executors.ContainerExecutors.Withdraw(
			runtimeServices));
		scheduler.register(new dev.squire.server.task.executors.ContainerExecutors.Transfer(
			runtimeServices));
		scheduler.register(new dev.squire.server.task.executors.ContainerExecutors.Sort(
			runtimeServices));
		scheduler.register(
			new dev.squire.server.task.executors.ContainerExecutors.PickupNearby(
				runtimeServices));
		// 方案 F1：用真实材料逐层建造
		scheduler.register(new dev.squire.server.task.executors.BuildStructureExecutor(
			runtimeServices));
		// 方案 G2：真实基地灯光（翻拉杆，不是伪造 LIT）
		scheduler.register(new dev.squire.server.task.executors.BaseLightsExecutor(
			runtimeServices));
		// 第 1 期：按蓝图施工与按蓝图掘进（材料真实流动）
		scheduler.register(new dev.squire.server.task.executors.BlueprintBuildExecutor(
			runtimeServices));
		scheduler.register(new dev.squire.server.task.executors.ExcavateExecutor(
			runtimeServices));
		// 第 2 期：回家收工。Goal 只提交任务，业务在执行器里。
		scheduler.register(new dev.squire.server.task.executors.HomeRoutineExecutor(
			runtimeServices));
		// 第 3 期：工程的点灯阶段（真火把，不是翻拉杆）
		scheduler.register(new dev.squire.server.task.executors.LightUpExecutor(
			runtimeServices));
		// 改动玩家已有的物品：先走到跟前，再动手，并留下可撤销的快照。
		scheduler.register(new dev.squire.server.task.executors.ItemEditExecutor(
			runtimeServices, itemService.editJournal(),
			(playerId, message) -> notifier.send(playerId, message)));
		// 「打那只苦力怕」：指哪打哪，区别于 GuardTaskExecutor 的被动护卫。
		scheduler.register(new dev.squire.server.task.executors.AttackTargetExecutor(
			runtimeServices));
	}

	/** 每多少 tick 检查一次「主人是不是换维度了」。一秒一次足够，也不费。 */
	private static final int FOLLOW_DIMENSION_CHECK_TICKS = 20;

	/**
	 * 处于跟随状态、而主人在<b>另一个维度</b>时，把伙伴带过去。
	 *
	 * <p>{@code FollowOwnerGoal} 明确要求同维度（寻路跨不了维度），所以这件事只能在
	 * 这里做。判据刻意保守：只有 FOLLOW 模式、没有任务占用身体、也没在打架时才动，
	 * 免得把一个正在施工或正在打怪的伙伴凭空拽走。</p>
	 */
	private void followAcrossDimensions() {
		for (java.util.UUID ownerId : agents.knownOwners()) {
			try {
				ServerPlayerEntity owner = server.getPlayerManager().getPlayer(ownerId);
				if (owner == null || !owner.isAlive()
						|| !(owner.getWorld() instanceof ServerWorld ownerWorld)) {
					continue;
				}
				AvatarEntity avatar = agents.resolveForOwner(ownerId).orElse(null);
				if (avatar == null || !avatar.isAlive()
						|| avatar.mode() != AvatarEntity.MovementMode.FOLLOW
						|| avatar.taskDriven() || avatar.inCombat()
						|| ownerWorld == avatar.getWorld()) {
					continue;
				}
				// 玩家创造飞行、鞘翅滑翔或正在坠落时，getBlockPos 本身没有任何支撑。
				// 跨维度传送不像同维度跟随那样会先验证 WALKABLE，因此必须在这里等他
				// 落地（乘船/游泳也有可接受的承载面），不能把侍从直接送到半空。
				if (!owner.isOnGround() && !owner.isTouchingWater()
						&& !owner.hasVehicle()) {
					continue;
				}
				AvatarEntity arrived = roster.controlledTeleport(avatar, ownerWorld,
					net.minecraft.util.math.GlobalPos.create(
						ownerWorld.getRegistryKey(), owner.getBlockPos()));
				if (arrived != null) {
					persistSnapshot(arrived);
					notifier.send(ownerId, "[Squire] 跟过来了（"
						+ ownerWorld.getRegistryKey().getValue().getPath() + "）。");
				}
			} catch (RuntimeException e) {
				dev.squire.SquireMod.LOGGER.warn(
					"[Squire] cross-dimension follow failed: {}", e.toString());
			}
		}
	}

	/**
	 * 每拍分段计时。常开：一拍十四次 {@code nanoTime}，相对 50ms 可以忽略，
	 * 而「卡的时候才打开」的开关在现场早就滑过去了。见 {@code /squire admin perf}。
	 */
	private final dev.squire.server.metrics.TickProfiler profiler =
		new dev.squire.server.metrics.TickProfiler();

	/** Advance the task runtime by one tick; called from the server tick event. */
	public void tickScheduler() {
		long tick = currentTick();
		long mark = profiler.now();
		if (!killswitch.isActive()) {
			automation.tick(tick); // §63: killswitch freezes long-term automation too
			mark = profiler.mark(Section.AUTOMATION, mark);
			cbp.tick(tick); // §50: same freeze applies to CBP placement/confirmation
			mark = profiler.mark(Section.CBP, mark);
			guards.tick(tick); // 方案 D1: 长期护卫在服务器线程内逐 tick 执行
			mark = profiler.mark(Section.GUARDS, mark);
			autonomyController.tick(tick);
			mark = profiler.mark(Section.AUTONOMY, mark);
		}
		// 跟随必须能跟过传送门。FollowOwnerGoal 只在同一维度里工作（它的寻路本来
		// 就跨不了维度），于是玩家一进下界，伙伴就永远留在主世界站着——「跟随」
		// 这条最基本的承诺在最需要它的时候失效。这一段专门补这个缺口。
		if (tick % FOLLOW_DIMENSION_CHECK_TICKS == 0) {
			followAcrossDimensions();
		}
		mark = profiler.mark(Section.FOLLOW_DIMENSION, mark);
		// 第 1 期：幽灵预览。只给摆放的主人发粒子，世界零改变，
		// 所以不在 killswitch 的冻结范围内——停掉只会让玩家看不见自己已经摆好的东西。
		blueprints.tick(tick);
		mark = profiler.mark(Section.BLUEPRINTS, mark);
		if (!killswitch.isActive()) {
			projectCoordinator.tick(tick); // 工程推进也归 killswitch 管
		}
		mark = profiler.mark(Section.PROJECT, mark);
		// 方案 I2：过了期限还没有任何结果的外部调用，主动变成结构化超时
		if (tick % 20 == 0) {
			mcp.sweepOverdue(tick);
			// 玩家补刀 / 目标掉进岩浆：随从参与过的战斗也要结算职业经验。
			sweepCombatLedger(tick);
		}
		mark = profiler.mark(Section.MCP_SWEEP, mark);
		if (tick % 100 == 0) { // 方案 F3/F4: 过期的待确认操作与 Undo 历史定期清理
			int expired = pendingOperations.expireAllBefore(tick);
			if (expired > 0) {
				dev.squire.SquireMod.LOGGER.info(
					"[Squire] {} pending confirmation(s) expired unexecuted", expired);
			}
			confirmations.expireAllBefore(tick);
			capabilityStore.expireAllBefore(tick);
			undoJournal.expireAllBefore(tick);
		}
		mark = profiler.mark(Section.EXPIRY, mark);
		scheduler.tick(tick, agentId -> new dev.squire.server.task.TaskEvaluationContext() {
			@Override
			public long tick() {
				return tick;
			}

			@Override
			public UUID agentId() {
				return agentId;
			}

			@Override
			public dev.squire.api.body.AgentBody body() {
				return agents.resolveByAgentId(agentId)
					.map(a -> (dev.squire.api.body.AgentBody) a).orElse(null);
			}
		});
		mark = profiler.mark(Section.SCHEDULER, mark);
		goals.tick(tick);
		mark = profiler.mark(Section.GOALS, mark);
		conversations.tick(tick);
		mark = profiler.mark(Section.CONVERSATIONS, mark);
		refreshActivityStates();
		profiler.mark(Section.ACTIVITY, mark);
		profiler.endTick();
	}

	/** 每拍分段耗时快照，供 {@code /squire admin perf} 打印。 */
	public dev.squire.server.metrics.TickProfiler profiler() {
		return profiler;
	}

	/**
	 * 每 tick 把每个伙伴的忙碌状态归位一次，优先级：思考 &gt; 干活 &gt; 空闲。
	 *
	 * <p>集中在这里推导，而不是在每个状态转换点上散落 setActivity —— 那样总会漏掉
	 * 某条退出路径，把伙伴永远卡在"思考中"。这里每 tick 重算，漏不掉。</p>
	 *
	 * <p>FAILED 是个例外：它是一次性的失败闪示，由实体自己计时退回 IDLE，
	 * 这里不去覆盖它，否则玩家根本来不及看见。</p>
	 */
	private void refreshActivityStates() {
		for (UUID owner : agents.knownOwners()) {
			AvatarEntity avatar = agents.resolveForOwner(owner).orElse(null);
			if (avatar == null) {
				continue;
			}
			warnIfBadlyHurt(owner, avatar);
			if (avatar.activity() == AvatarEntity.ActivityState.FAILED) {
				continue;
			}
			UUID agentId = avatar.agentId();
			avatar.setActivity(conversations.hasActiveTurn(agentId)
				? AvatarEntity.ActivityState.THINKING
				: scheduler.current(agentId).isPresent()
					? AvatarEntity.ActivityState.WORKING
					: AvatarEntity.ActivityState.IDLE);
		}
	}

	/** 上次对某个伙伴发过重伤警告的 tick，用来限频。 */
	private final java.util.Map<UUID, Long> lastHurtWarning = new java.util.HashMap<>();

	/** 低于这个血量比例就提醒主人；同一个伙伴至少隔这么久才再提醒一次。 */
	private static final float HURT_WARNING_FRACTION = 0.35f;
	private static final long HURT_WARNING_COOLDOWN_TICKS = 400L;

	/**
	 * 伙伴快死了要提前说一声。
	 *
	 * <p>在此之前玩家只有在他已经炸成烟的时候才知道出事了——中间完全没有可以介入的
	 * 窗口。提醒里直接给出可执行的两条路：把他收回来，或者让他自己吃药。</p>
	 */
	private void warnIfBadlyHurt(UUID ownerId, AvatarEntity avatar) {
		if (!avatar.isAlive()
				|| avatar.getHealth() > avatar.getMaxHealth() * HURT_WARNING_FRACTION) {
			lastHurtWarning.remove(avatar.agentId());
			return;
		}
		long tick = currentTick();
		Long last = lastHurtWarning.get(avatar.agentId());
		if (last != null && tick - last < HURT_WARNING_COOLDOWN_TICKS) {
			return;
		}
		lastHurtWarning.put(avatar.agentId(), tick);
		ServerPlayerEntity owner = server.getPlayerManager().getPlayer(ownerId);
		if (owner != null) {
			feedback(owner, "[Squire] 我快撑不住了（"
				+ (int) Math.ceil(avatar.getHealth()) + "/"
				+ (int) Math.ceil(avatar.getMaxHealth())
				+ "）。说「待在这里」让我脱离战斗，或者「治疗自己」。");
		}
	}

	long currentTick() {
		return server.getOverworld().getTime();
	}

	/** 当前服务器 tick（GameTest/工具用）。 */
	public long tickNow() {
		return currentTick();
	}

	/** 测试/诊断用：executor 共享服务。 */
	public dev.squire.server.task.executors.RuntimeServices runtimeServicesForTest() {
		return runtimeServices;
	}

	public dev.squire.server.tool.ToolGateway gateway() {
		return gateway;
	}

	public dev.squire.server.tool.ToolRegistry toolRegistry() {
		return tools;
	}

	public dev.squire.server.task.TaskScheduler scheduler() {
		return scheduler;
	}

	public dev.squire.server.tool.ToolGateway.CapabilityChecker capabilitiesOf(UUID agentId) {
		return permission -> STANDARD_CAPABILITIES.contains(permission);
	}

	// ------------------------------------------------------------------ security surface (M3)

	public dev.squire.server.security.Killswitch killswitch() {
		return killswitch;
	}

	public dev.squire.server.security.PermissionManager permissions() {
		return permissions;
	}

	/** Deterministic GameTest entry; production ticks use {@link #tickScheduler()}. */
	public void tickCooperativeHuntForTest(ServerPlayerEntity owner,
			AvatarEntity avatar) {
		autonomyController.tickCooperativeHuntForTest(owner, avatar);
	}

	public dev.squire.server.security.CapabilityStore capabilities() {
		return capabilityStore;
	}

	public dev.squire.server.security.QuotaLedger quotas() {
		return quotas;
	}

	public dev.squire.server.security.ConfirmationService confirmations() {
		return confirmations;
	}

	/** §75 observability counters (shared by gateway, turns, FastPath). */
	public dev.squire.server.metrics.SquireMetrics metrics() {
		return conversations.metrics();
	}

	public dev.squire.server.world.WorldEditor worldEditor() {
		return worldEditor;
	}

	public dev.squire.server.world.UndoJournal undoJournal() {
		return undoJournal;
	}

	public dev.squire.server.ext.ExtensionManager extensions() {
		return extensions;
	}

	public dev.squire.server.mcp.McpClientBridge mcp() {
		return mcp;
	}

	/**
	 * 任务运行时的现场快照。伙伴"接了活却不动"几乎总是这三种原因之一：
	 * 调度器被暂停、这个伙伴身上还压着一个没结束的任务（每个伙伴同一时刻只跑一个），
	 * 或者任务已经失败了但你没看到那条消息。这里一次全都摊开。
	 */
	public String diagnose(ServerPlayerEntity player) {
		StringBuilder text = new StringBuilder("[Squire] 运行时诊断").append("\n");
		text.append(" killswitch=").append(killswitch.isActive())
			.append("  调度器暂停=").append(scheduler.isPaused())
			.append("  当前 tick=").append(currentTick()).append("\n");

		// LLM 状态：以前"没放配置"和"配置写错了"都表现为静默降级，完全没法排查。
		var provider = dev.squire.server.provider.ProviderRegistry.current();
		text.append(" LLM=").append(provider.map(p -> "可用（" + p.id() + "）")
			.orElse("不可用"));
		dev.squire.server.provider.LlmConfig.lastLoadError()
			.ifPresent(reason -> text.append("  配置错误：").append(reason));
		text.append("\n");

		AvatarEntity avatar = agents.resolveForOwner(player.getUuid()).orElse(null);
		if (avatar == null) {
			text.append(" 侍从不在场（请使用召集铃）");
			return text.toString();
		}
		text.append(" 伙伴 ").append(avatar.agentId().toString(), 0, 8)
			.append("  模式=").append(avatar.mode())
			.append("  位置=").append(avatar.getBlockPos().toShortString())
			.append("\n");
		text.append(" 背包占用 ").append(avatar.items().occupiedSlots())
			.append("/").append(avatar.items().size());
		int worn = avatar.items().backpackSize();
		if (worn > 0) {
			// 背着背包时必须写出来：否则「他明明装得下」和「他说满了」对不上账。
			text.append("  ＋背包 ").append(worn).append(" 格");
		}
		text.append("\n");

		var running = scheduler.current(avatar.agentId()).orElse(null);
		text.append(" 正在执行：").append(running == null ? "（空闲）"
			: running.type() + " " + running.state() + " — " + running.goalDescription())
			.append("\n");
		if (running != null) {
			text.append("   进度：").append(describeProgress(running)).append("\n");
			running.lastErrorCode().ifPresent(code ->
				text.append("   上次错误：").append(code).append("\n"));
		}

		var pending = scheduler.pendingTasks().stream()
			.filter(t -> t.agentId().equals(avatar.agentId())).toList();
		text.append(" 排队中 ").append(pending.size()).append(" 个");
		for (var task : pending) {
			text.append("\n").append("   · ").append(task.type())
				.append(" ").append(task.state())
				.append(task.dependencies().isEmpty() ? "" : "（等依赖）");
		}
		text.append("\n");

		var goals = this.goals.all().stream()
			.filter(g -> g.ownerId().equals(player.getUuid())).toList();
		text.append(" 目标 ").append(goals.size()).append(" 个");
		for (var goal : goals) {
			text.append("\n").append("   · ").append(goal.subject())
				.append(" ").append(goal.state())
				.append(" 剩余任务 ").append(goal.remainingTaskIds().size());
			if (goal.lastError() != null) {
				text.append(" 错误=").append(goal.lastError());
			}
		}
		text.append("\n");

		var finished = scheduler.finishedTasks();
		int from = Math.max(0, finished.size() - 6);
		text.append(" 最近结束的任务：");
		if (from >= finished.size()) {
			text.append("（无）");
		}
		for (int i = from; i < finished.size(); i++) {
			var task = finished.get(i);
			text.append("\n").append("   · ").append(task.type())
				.append(" ").append(task.state())
				.append(" ").append(task.lastErrorCode().orElse("-"));
		}
		return text.toString();
	}

	/** 采集这类多阶段执行器的现场状态——不展开就只能看到一个 RUNNING。 */
	private static String describeProgress(dev.squire.server.task.Task task) {
		Object state = task.executionState();
		if (state instanceof dev.squire.server.task.executors
				.GatherBlockExecutor.Progress p) {
			return "阶段=" + p.phase()
				+ " 目标方块=" + (p.target() == null ? "（还没选定）"
					: p.target().toShortString())
				+ " 寻路=" + (p.handle() == null ? "-" : p.handle().state())
				+ " 已采=" + p.collectedThisTask() + "/" + p.wanted()
				+ " 跳过=" + p.avoided().size()
				+ " 空扫=" + p.emptyScans()
				+ " 换位=" + p.relocations();
		}
		return state == null ? "（无）" : String.valueOf(state);
	}

	// ------------------------------------------------------------------ 测试用直给

	/** Spec §94 style: ships OFF; an operator turns it on for testing only. */
	private volatile boolean instantAcquireEnabled = false;

	public boolean isInstantAcquireEnabled() {
		return instantAcquireEnabled;
	}

	public void setInstantAcquireEnabled(boolean enabled) {
		this.instantAcquireEnabled = enabled;
	}

	// —— 已停用（方向性收缩：真身只保留跟随/护卫/救援，取物改走指令兑现）——
	// 保留代码而非删除，方便日后回退。当前无任何调用方。
//	/**
//	 * 测试捷径：直接把物品放进伙伴背包，再走<b>真实的交付任务</b>把它交给玩家。
//	 * 跳过的是"去世界里找materials"这一段，交接本身仍然是真的物品搬运。
//	 *
//	 * <p>默认关闭。打开时每条回复都会写明"直给"，因为方案 §14 明确把
//	 * "直接 give" 列为 B02/B04 的<b>禁止证据</b>——它能让你快速测后半段，但不能拿来打勾。</p>
//	 */
//	private ExecutionResult instantAcquireAndGive(ServerPlayerEntity sender,
//			AvatarEntity avatar, String itemId, int count) {
//		net.minecraft.util.Identifier id;
//		try {
//			id = itemId.contains(":")
//				? new net.minecraft.util.Identifier(itemId)
//				: new net.minecraft.util.Identifier("minecraft", itemId);
//		} catch (RuntimeException e) {
//			return ExecutionResult.fail("feedback.bad_item", "[Squire] 认不出这个物品：" + itemId);
//		}
//		var item = net.minecraft.registry.Registries.ITEM.get(id);
//		if (item == net.minecraft.item.Items.AIR) {
//			return ExecutionResult.fail("feedback.bad_item",
//				"[Squire] 我不认识「" + itemId + "」这个东西。");
//		}
//		int remaining = count;
//		int granted = 0;
//		while (remaining > 0) {
//			net.minecraft.item.ItemStack stack = new net.minecraft.item.ItemStack(item,
//				Math.min(remaining, item.getMaxCount()));
//			int size = stack.getCount();
//			net.minecraft.item.ItemStack leftover = avatar.items().insert(stack);
//			granted += size - leftover.getCount();
//			if (!leftover.isEmpty()) {
//				break; // 伙伴背包满了：如实停在这里
//			}
//			remaining -= size;
//		}
//		if (granted <= 0) {
//			return ExecutionResult.fail("feedback.inventory_full",
//				"[Squire] 伙伴背包满了，一个都放不下。");
//		}
//		int playerTarget = dev.squire.server.task.executors.DeliverToOwnerExecutor
//			.playerCount(sender, id) + granted;
//		java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
//		params.put(dev.squire.server.task.executors.DeliverToOwnerExecutor.PARAM_ITEM_ID,
//			id.toString());
//		params.put(dev.squire.server.task.executors.DeliverToOwnerExecutor
//			.PARAM_TARGET_PLAYER_COUNT, playerTarget);
//		dev.squire.server.task.Task delivery = new dev.squire.server.task.Task(
//			avatar.agentId(), sender.getUuid(),
//			dev.squire.server.task.executors.DeliverToOwnerExecutor.TYPE,
//			dev.squire.server.task.TaskPriority.P3_USER_TASK,
//			"deliver " + granted + " " + id, null,
//			dev.squire.server.task.executors.DeliverToOwnerExecutor.playerHas(
//				runtimeServices, sender.getUuid(), id.toString(), playerTarget),
//			1200L, dev.squire.server.task.RetryPolicy.DEFAULT, true, "instant-acquire",
//			params);
//		scheduler.submit(delivery, currentTick());
//		return ExecutionResult.ok("feedback.instant_acquire",
//			"[Squire] 【直给·测试模式】已把 " + granted + " 个 " + id
//				+ " 放进伙伴背包，正在交给你。这不是真实采集，不能作为 B02/B04 的验收证据。");
//	}

	/** Where operators put MCP servers (方案 I2)：聊天里永远不接受服务器地址。 */
	public static java.nio.file.Path mcpConfigFile() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
			.resolve("squire").resolve("mcp.json");
	}

	/** Servers described by the current config file, for the admin inspector. */
	public dev.squire.server.mcp.McpConfig.LoadResult mcpConfig() {
		return dev.squire.server.mcp.McpConfig.load(mcpConfigFile());
	}

	/**
	 * 方案 I2：按 {@code config/squire/mcp.json} 连接（或重连）MCP 服务器。
	 * 配置错误只让这一台降级并写清原因，绝不阻止服务器启动。
	 *
	 * @return 面向服主的一段可读报告
	 */
	public String reloadMcpServers() {
		java.nio.file.Path file = mcpConfigFile();
		var config = dev.squire.server.mcp.McpConfig.load(file);
		StringBuilder report = new StringBuilder("[Squire] MCP 配置：")
			.append(file).append('\n');
		if (!java.nio.file.Files.exists(file)) {
			try { // 第一次运行时留一份可抄的样例，全部 enabled=false
				java.nio.file.Files.createDirectories(file.getParent());
				java.nio.file.Files.writeString(file,
					dev.squire.server.mcp.McpConfig.exampleJson());
				report.append(" - 文件不存在，已写入一份示例（全部 enabled=false）\n");
			} catch (java.io.IOException e) {
				report.append(" - 无法写入示例：").append(e).append('\n');
			}
			return report.toString();
		}
		for (String problem : config.problems()) {
			report.append(" - 问题：").append(problem).append('\n');
		}
		// 先断开当前连接：在飞的调用会立刻收到结构化的 MCP_DISCONNECTED
		for (String connected : mcp.connectedServers()) {
			mcp.disconnect(connected);
		}
		int started = 0;
		for (var entry : config.servers()) {
			if (!entry.enabled()) {
				report.append(" - ").append(entry.name()).append("：已禁用，跳过\n");
				continue;
			}
			try {
				mcp.setServerTimeout(entry.name(), entry.timeoutSeconds());
				mcp.circuitBreaker().reset(entry.name());
				mcp.registerServer(entry.name(),
					dev.squire.server.mcp.McpConfig.transportFor(entry));
				started++;
				report.append(" - ").append(entry.describe()).append("：连接中\n");
			} catch (RuntimeException e) {
				report.append(" - ").append(entry.name()).append("：无法启动（")
					.append(e).append("）\n");
			}
		}
		report.append("共 ").append(started).append(" 台服务器正在连接。"
			+ "信任等级只来自 mcp-trust.json，配置文件无法提权。");
		return report.toString();
	}

	/** One server's live status for {@code /squire admin mcp status <server>}. */
	public String mcpStatus(String server) {
		long now = currentTick();
		var entry = mcpConfig().servers().stream()
			.filter(s -> s.name().equals(server)).findFirst().orElse(null);
		StringBuilder text = new StringBuilder("[Squire] MCP ").append(server)
			.append('\n');
		text.append(" 配置：").append(entry == null ? "（不在 mcp.json 中）"
			: entry.describe()).append('\n');
		text.append(" 已连接：").append(mcp.isConnected(server)).append('\n');
		text.append(" 信任：").append(mcp.trusts().trustOf(server)).append('\n');
		text.append(" 熔断：").append(mcp.circuitBreaker().stateOf(server, now))
			.append("（连续失败 ")
			.append(mcp.circuitBreaker().consecutiveFailures(server)).append("）\n");
		text.append(" 在飞调用：").append(mcp.pendingCalls().forServer(server).size());
		return text.toString();
	}

	/** M5: long-term automation registry/engine (§49); ships disabled (§94). */
	public dev.squire.server.automation.AutomationEngine automation() {
		return automation;
	}

	/** M5b: explicit command-block materialization (§50); ships disabled (§94). */
	public dev.squire.server.cbp.CbpMaterializer cbp() {
		return cbp;
	}

	public dev.squire.server.cbp.CbpWorkspace cbpWorkspace() {
		return cbpWorkspace;
	}

	/** §74 alias resolver (deterministic layers only; registry-validated). */
	public dev.squire.server.i18n.ItemAliasResolver itemAliases() {
		return vocabulary.items();
	}

	/** 物品名 + 附魔名两张表。解析器只认这一个入口。 */
	public dev.squire.server.i18n.Vocabulary vocabulary() {
		return vocabulary;
	}

	public dev.squire.server.cbp.CbpRegistry cbpRegistry() {
		return cbpRegistry;
	}

	public boolean isCbpEnabled() {
		return cbpEnabled;
	}

	public void setCbpEnabled(boolean enabled) {
		this.cbpEnabled = enabled;
		this.cbp.setEnabled(enabled);
	}

	// ------------------------------------------------------------------ 高风险确认（方案 F3）

	public dev.squire.server.security.PendingOperationStore pendingOperations() {
		return pendingOperations;
	}

	/** 玩家可选的选区服务（方案 F2）。 */
	public dev.squire.server.world.SelectionService selections() {
		return dev.squire.server.world.SelectionService.get(server);
	}

	/**
	 * 记录一次待确认的高风险操作，并把 preview 交给玩家（方案 F3）。
	 * 保存的是 canonical 参数：确认时重放的就是玩家看过的这一份。
	 */
	public dev.squire.server.security.PendingOperation issuePendingOperation(UUID ownerId,
			UUID agentId, String toolName, java.util.Map<String, Object> canonicalArguments,
			String preview) {
		long now = currentTick();
		var operation = new dev.squire.server.security.PendingOperation(
			UUID.randomUUID(), ownerId, agentId, toolName, canonicalArguments,
			dev.squire.server.tool.ToolGateway.fingerprint(canonicalArguments), preview,
			now, now + dev.squire.server.security.ConfirmationService.DEFAULT_TTL_TICKS,
			dev.squire.server.security.PendingOperation.Status.PENDING, null);
		pendingOperations.put(operation);
		// 同时登记到 ConfirmationService，Gateway 的 HIGH 风险闸门认这一份指纹
		confirmations.issueWithId(operation.confirmId(), ownerId, agentId, toolName,
			operation.fingerprint(), now, operation.expiresAtTick());
		return operation;
	}

	/**
	 * Owner confirms one pending high-risk request (spec §45, 方案 F3). The stored
	 * CANONICAL operation is replayed exactly once on the server thread — the player
	 * never has to repeat the original sentence, and the model cannot alter what was
	 * previewed.
	 */
	public String confirmRequest(ServerPlayerEntity confirmer, UUID confirmId) {
		long now = currentTick();
		var stored = pendingOperations.get(confirmId).orElse(null);
		if (stored == null) {
			// 老式（无 canonical 参数）的请求仍然走原路径
			return confirmations.confirm(confirmer.getUuid(), confirmId, now);
		}
		if (!stored.ownerId().equals(confirmer.getUuid())) {
			dev.squire.SquireMod.LOGGER.warn(
				"[Squire] FORGED confirm: {} tried to confirm {} owned by {}",
				confirmer.getUuid(), confirmId, stored.ownerId());
			return "[Squire] 这条确认属于另一位玩家。";
		}
		switch (stored.status()) {
			case EXECUTED -> {
				return "[Squire] 这条操作已经执行过了（不会重复执行）。";
			}
			case DENIED -> {
				return "[Squire] 这条操作已被拒绝。";
			}
			case EXPIRED -> {
				return "[Squire] 这条确认已过期，请重新发起。";
			}
			default -> { /* PENDING / FAILED 可以继续 */ }
		}
		if (stored.expiresAtTick() <= now) {
			pendingOperations.update(stored.withStatus(
				dev.squire.server.security.PendingOperation.Status.EXPIRED,
				"not confirmed in time"));
			return "[Squire] 这条确认已过期，请重新发起。";
		}
		// 让 Gateway 的 HIGH 风险闸门放行这一次，且只放行这一次
		confirmations.confirm(confirmer.getUuid(), confirmId, now);
		var result = replayPendingOperation(confirmer, stored);
		if (result.success()) {
			pendingOperations.update(stored.withStatus(
				dev.squire.server.security.PendingOperation.Status.EXECUTED, null));
		} else {
			// 提交失败绝不能报告 "executing"：状态留在 FAILED 并说明原因
			pendingOperations.update(stored.withStatus(
				dev.squire.server.security.PendingOperation.Status.FAILED,
				result.message()));
		}
		return result.message();
	}

	/** 玩家明确拒绝一条待确认操作（方案 F3）。 */
	public String denyRequest(ServerPlayerEntity denier, UUID confirmId) {
		var stored = pendingOperations.get(confirmId).orElse(null);
		if (stored == null) {
			// CBP 等自带确认流程的操作没有 PendingOperation：取消那一条即可
			var request = confirmations.get(confirmId).orElse(null);
			if (request == null) {
				return "[Squire] 未知的确认 id。";
			}
			if (!request.ownerId().equals(denier.getUuid())) {
				return "[Squire] 这条确认属于另一位玩家。";
			}
			confirmations.cancel(confirmId);
			return "[Squire] 已拒绝操作 " + confirmId.toString().substring(0, 8)
				+ "，世界没有任何改变。";
		}
		if (!stored.ownerId().equals(denier.getUuid())) {
			return "[Squire] 这条确认属于另一位玩家。";
		}
		if (stored.status() != dev.squire.server.security.PendingOperation.Status.PENDING) {
			return "[Squire] 这条操作已经是 " + stored.status() + " 状态。";
		}
		pendingOperations.update(stored.withStatus(
			dev.squire.server.security.PendingOperation.Status.DENIED, "denied by owner"));
		confirmations.cancel(confirmId);
		return "[Squire] 已拒绝操作 " + stored.shortId() + "，世界没有任何改变。";
	}

	/**
	 * 在服务器线程上重放一条已确认的 canonical 操作。只有真正提交了任务（RUNNING）
	 * 或立即完成（SUCCESS）才算执行成功。
	 */
	private ExecutionResult replayPendingOperation(ServerPlayerEntity confirmer,
			dev.squire.server.security.PendingOperation stored) {
		AvatarEntity avatar = stored.agentId() == null ? null
			: agents.resolveByAgentId(stored.agentId()).orElse(null);
		if (avatar == null) {
			avatar = agents.resolveForOwner(confirmer.getUuid()).orElse(null);
		}
		if (avatar == null || !avatar.isAlive()) {
			return ExecutionResult.fail("feedback.no_agent",
				"[Squire] 确认失败：伙伴当前不在世界中，操作没有执行。");
		}
		final AvatarEntity body = avatar;
		dev.squire.server.tool.ToolExecutionContext ctx =
			new dev.squire.server.tool.ToolExecutionContext() {
				@Override
				public dev.squire.api.body.AgentBody body() {
					return body;
				}

				@Override
				public AvatarEntity avatar() {
					return body;
				}

				@Override
				public MinecraftServer server() {
					return SquireRuntime.this.server;
				}

				@Override
				public UUID requesterId() {
					return confirmer.getUuid();
				}

				@Override
				public long tick() {
					return currentTick();
				}
			};
		var call = new dev.squire.common.protocol.ToolCall(UUID.randomUUID(),
			stored.operationType(), stored.canonicalArguments());
		var result = gateway.dispatch(call,
			dev.squire.server.tool.CallerIdentity.model(confirmer.getUuid(),
				body.agentId()),
			ctx, capabilitiesOf(body.agentId()));
		if (result.status() == dev.squire.common.protocol.ToolResult.Status.RUNNING
				|| result.status() == dev.squire.common.protocol.ToolResult.Status.SUCCESS) {
			Object taskId = result.data().get("taskId");
			return ExecutionResult.ok("feedback.confirmed",
				"[Squire] 已确认，正在执行 " + stored.operationType()
					+ (taskId == null ? "" : "（task " + taskId + "）") + "。");
		}
		String error = result.errorOrNull()
			.map(e -> e.code().wire() + "：" + e.message())
			.orElse(String.valueOf(result.status()));
		return ExecutionResult.fail("feedback.confirm_failed",
			"[Squire] 确认后提交失败（" + error + "），世界没有任何改变。");
	}

	/**
	 * 方案 F2/F3/B07：“把选定区域铺成石头”。实现搬到了
	 * {@link SquireBuildService}，这里保留门面以免所有调用方跟着改。
	 */
	public ExecutionResult fillSelection(ServerPlayerEntity sender, String blockId) {
		return buildService.fillSelection(sender, blockId);
	}

	/**
	 * 「帮我盖一个房子」。
	 *
	 * <p>第 1 期起这句话不再一个 tick 把房子写出来，而是摆下一份蓝图摆放：
	 * 先出粒子轮廓和材料清单，玩家凑齐了才开工。方法签名不变是为了不动
	 * FastPath 和现有调用方；尺寸参数会被收敛到面板支持的安全档位。</p>
	 *
	 * <p>旧的凭空生成路径在 {@link SquireBuildService#buildHouse} 里原封不动地留着，
	 * 当前无调用方——保留它只是为了万一要回退时有一个完整的参照。</p>
	 */
	public ExecutionResult buildHouse(ServerPlayerEntity sender, String styleWord,
			int width, int depth, int height) {
		boolean stone = styleWord != null && (styleWord.contains("石")
			|| styleWord.toLowerCase(java.util.Locale.ROOT).contains("stone"));
		var spec = new dev.squire.server.blueprint.HouseSpec(
			nearestHouseChoice(width, dev.squire.server.blueprint.HouseSpec.SIZES, 7),
			nearestHouseChoice(depth, dev.squire.server.blueprint.HouseSpec.SIZES, 7),
			nearestHouseChoice(height, dev.squire.server.blueprint.HouseSpec.HEIGHTS, 4),
			stone ? dev.squire.server.blueprint.HouseSpec.Material.STONE_BRICKS
				: dev.squire.server.blueprint.HouseSpec.Material.OAK,
			dev.squire.server.blueprint.HouseSpec.Roof.GABLE);
		return projectService.start(sender, spec.blueprintId());
	}

	private static int nearestHouseChoice(int requested, int[] choices,
			int fallback) {
		if (requested <= 0) return fallback;
		int best = choices[0];
		for (int choice : choices) {
			if (Math.abs(choice - requested) < Math.abs(best - requested)) best = choice;
		}
		return best;
	}

	// ------------------------------------------------------------------ 职业与成长

	/**
	 * 任务走到终态时的熟练度记账。<b>只认 COMPLETED</b>——取消、失败、超时一分不给。
	 *
	 * <p>也只在主人在线时计入：一个挂机的服务器不该把随从养成满级。
	 * 这是防挂机四条里的第一条，其余三条分别在 {@code Track.applyDailyCap}、
	 * {@code undoOperation} 的回扣、以及执行器对「已经是目标方块」的跳过里。</p>
	 */
	private void onTaskTerminal(dev.squire.server.task.Task task) {
		if (task.state() != dev.squire.server.task.TaskState.COMPLETED) {
			return;
		}
		dev.squire.server.profile.SquireProfile profile = profileOfAgent(task.agentId());
		if (profile == null) {
			return;
		}
		UUID ownerId = task.requesterId();
		// 防挂机第一条：主人不在线就不计入。解析走 runtimeServices.requester，
		// 它先查 PlayerManager 再查已加载的世界——和其它执行器同一条路径。
		if (ownerId == null || runtimeServices.requester(ownerId) == null) {
			return;
		}
		// 职业经验和熟练度是<b>两条独立</b>的账：一个选了工程师但还没选 Role 的随从，
		// 照样该拿到工程经验。所以这一句在熟练度的门禁之外。
		settleEngineerProject(task, ownerId, profile);
		if (dev.squire.server.task.executors.BlueprintBuildExecutor.TYPE
				.equals(task.type())) {
			noteTraining(task.agentId(),
				dev.squire.server.profession.TrainingMilestone.BUILD);
		}

		var report = task.workReport();
		if (report == null || report.amount() <= 0) {
			return; // 这类任务不产生可计量的工作量
		}
		dev.squire.server.profile.Track track =
			dev.squire.server.profile.Track.byId(report.kind());
		if (track == null || profile.role() == null) {
			return; // 通用随从不积累熟练度，选 Role 才开始
		}
		awardProficiency(task.agentId(), ownerId, profile, track, report.amount(),
			report.operationId());
	}

	/**
	 * 工程师经验的结算点（设计文档 §16）：<b>施工验收通过之后一次性给</b>。
	 *
	 * <p>挂在任务终态的 COMPLETED 分支上，等于挂在 GoalVerifier 点头之后。
	 * 取消、失败、超时、中途断线的工程走不到这里，一分也拿不到——这正是设计文档
	 * 要求的那张「全部 0 XP」清单。</p>
	 */
	private void settleEngineerProject(dev.squire.server.task.Task task, UUID ownerId,
			dev.squire.server.profile.SquireProfile profile) {
		if (!dev.squire.server.task.executors.BlueprintBuildExecutor.TYPE
				.equals(task.type())) {
			return;
		}
		var data = profile.profession;
		if (data.profession()
				!= dev.squire.server.profession.SquireProfession.ENGINEER) {
			return;
		}
		Object rawId = task.parameters().get(
			dev.squire.server.task.executors.BlueprintBuildExecutor.PARAM_PLACEMENT_ID);
		if (rawId == null) {
			return;
		}
		dev.squire.server.blueprint.BlueprintPlacement placement;
		try {
			placement = blueprints.placement(UUID.fromString(rawId.toString()))
				.orElse(null);
		} catch (IllegalArgumentException badUuid) {
			return;
		}
		if (placement == null) {
			return;
		}
		var project = describeProject(placement);
		if (project == null) {
			return;
		}
		long now = currentTick();
		int repeats = data.recentProjectCount(project.signature(), now,
			professionConfig.engineerRepeatWindowTicks);
		var award = dev.squire.server.profession.EngineerXp.award(professionConfig,
			project, repeats);
		data.noteProject(project.signature(), now);
		awardProfessionXp(task.agentId(), ownerId, data, award.finalXp());
		if (ownerId != null && award.anything()) {
			notifier.send(ownerId, "[Squire] 「" + placement.blueprintId
				+ "」验收通过，工程经验 +" + award.finalXp()
				+ (award.penalised()
					? "（同款工程盖太多了，这次只有 "
						+ Math.round(award.repeatMultiplier() * 100) + "%）" : ""));
		}
	}

	/**
	 * 把一份摆放翻译成经验规则认识的工程描述。
	 *
	 * <p>参数化蓝图直接从 id 里解出全部参数；固定蓝图（内置的、数据包的）退回
	 * 「模板 + 足印」这两项——它们仍然是真的工程，不该因为没有参数就白干。</p>
	 */
	private dev.squire.server.profession.EngineerXp.Project describeProject(
			dev.squire.server.blueprint.BlueprintPlacement placement) {
		var spec = dev.squire.server.blueprint.ProjectSpec.parse(placement.blueprintId)
			.orElse(null);
		if (spec != null) {
			return new dev.squire.server.profession.EngineerXp.Project(
				spec.template().id(),
				dev.squire.server.profession.EngineerXp.sizeClassFor(spec.width(),
					spec.depth()),
				spec.floors(), spec.usesStructuralVariant(), spec.modules().size(),
				spec.compound());
		}
		var blueprint = blueprints.registry().byId(placement.blueprintId).orElse(null);
		if (blueprint == null) {
			return null;
		}
		return new dev.squire.server.profession.EngineerXp.Project(blueprint.id(),
			dev.squire.server.profession.EngineerXp.sizeClassFor(blueprint.width(),
				blueprint.depth()),
			1, false, 0, false);
	}

	/** 记一笔熟练度，并在真的发生了什么时告诉主人。 */
	void awardProficiency(UUID agentId, UUID ownerId,
			dev.squire.server.profile.SquireProfile profile,
			dev.squire.server.profile.Track track, long amount, UUID operationId) {
		if (profile == null || track == null || amount <= 0) {
			return;
		}
		var outcome = progression.award(profile, track, amount, currentDay());
		if (!outcome.anythingHappened()) {
			return;
		}
		if (operationId != null) {
			// 没有这一条，「建 → undo → 建」就是无限刷。
			progression.rememberForUndo(operationId, agentId, track,
				outcome.credited());
		}
		agents.resolveByAgentId(agentId).ifPresent(this::persistSnapshot);
		if (outcome.gainedSlot()) {
			notifier.send(ownerId, "[Squire] 我练到 Lv" + outcome.levelAfter()
				+ " 了，多了一个能力槽。可以在档案页看看能装什么。");
		} else if (outcome.leveledUp()) {
			notifier.send(ownerId, "[Squire] 我练到 Lv" + outcome.levelAfter() + " 了。");
		}
		for (var ability : outcome.unlocked()) {
			notifier.send(ownerId, "[Squire] 解锁了「" + ability.displayName()
				+ "」：" + ability.summary()
				+ "。可以在档案页装上它。");
		}
	}

	/** 当前 MC 天。防挂机的当日软上限按它清零。 */
	long currentDay() {
		return server == null ? 0L : server.getOverworld().getTimeOfDay() / 24000L;
	}


	/** 这只随从的档案；存档还没就绪时返回 null。 */
	public dev.squire.server.profile.SquireProfile profileOf(AvatarEntity avatar) {
		return avatar == null ? null : profileOfAgent(avatar.agentId());
	}

	dev.squire.server.profile.SquireProfile profileOfAgent(UUID agentId) {
		if (agentId == null || server == null) {
			return null;
		}
		return dev.squire.server.agent.SquireAgentStateStore.get(server)
			.recordOfAgent(agentId).map(record -> record.profile).orElse(null);
	}

	/**
	 * <b>服务端动手之前唯一该问的问题</b>：他会这个吗？
	 *
	 * <p>读不到档案时按「基础能力都会」放行——最小/测试运行时不该因为没有
	 * 档案就把伙伴变成什么都不会。</p>
	 */
	public boolean can(AvatarEntity avatar, dev.squire.server.profile.Ability ability) {
		if (ability == null) {
			return false;
		}
		dev.squire.server.profile.SquireProfile profile = profileOf(avatar);
		return profile == null ? ability.basic() && ability.available()
			: profile.can(ability);
	}

	public dev.squire.server.profile.ProgressionService progression() {
		return progression;
	}

	// ------------------------------------------------------------------ 职业系统

	/** 职业平衡表的位置。服主想改数字就改这个文件，不用碰 Java。 */
	public static java.nio.file.Path professionConfigFile() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
			.resolve("squire").resolve("profession.json");
	}

	public static java.nio.file.Path recallBellConfigFile() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
			.resolve("squire").resolve("recall_bell.json");
	}

	public dev.squire.server.item.BellReviveConfig bellReviveConfig() {
		return bellReviveConfig;
	}

	public dev.squire.server.profession.ProfessionConfig professionConfig() {
		return professionConfig;
	}

	/** 解析一个请求者 id 到在线玩家；不在线返回 null。工具层用它拿到发话的人。 */
	public ServerPlayerEntity requesterOf(UUID playerId) {
		return runtimeServices.requester(playerId);
	}

	/**
	 * 服务端加载的那份平衡表，实体层也要用得到它。
	 *
	 * <p>运行时还没起来（最小/测试环境）时回内置默认表——绝不因为拿不到配置就把
	 * 一条行为规则整个关掉。</p>
	 */
	public static dev.squire.server.profession.ProfessionConfig professionBalance() {
		return instance == null
			? dev.squire.server.profession.ProfessionConfig.defaults()
			: instance.professionConfig;
	}

	public dev.squire.server.profession.ProfessionService professions() {
		return professions;
	}

	public dev.squire.server.profession.GuardCombatLedger combatLedger() {
		return combatLedger;
	}

	/** 这只随从的职业进度；读不到档案时返回 null。 */
	public dev.squire.server.profession.ProfessionData professionOf(AvatarEntity avatar) {
		var profile = profileOf(avatar);
		return profile == null ? null : profile.profession;
	}

	public dev.squire.server.profession.ProfessionData professionOfAgent(UUID agentId) {
		var profile = profileOfAgent(agentId);
		return profile == null ? null : profile.profession;
	}

	/**
	 * <b>职业行为动手之前唯一该问的问题</b>：他到这一级了吗？
	 *
	 * <p>读不到档案时一律回 false——没有职业的随从走的是原来那条路，
	 * 而不是「默认全都会」。</p>
	 */
	public boolean can(AvatarEntity avatar,
			dev.squire.server.profession.ProfessionAbility ability) {
		var data = professionOf(avatar);
		return data != null && data.can(ability);
	}

	/** 同上，按 agentId。战斗层拿得到的往往只有 id。 */
	public boolean canAgent(UUID agentId,
			dev.squire.server.profession.ProfessionAbility ability) {
		var data = professionOfAgent(agentId);
		return data != null && data.can(ability);
	}

	/**
	 * 记一笔职业经验，并在真的发生了什么时告诉主人。
	 *
	 * <p><b>只在这里入账</b>：守卫的击杀结算和工程师的验收结算都收敛到这一个出口，
	 * 于是「什么时候会涨经验」永远只有一处答案。</p>
	 */
	void awardProfessionXp(UUID agentId, UUID ownerId,
			dev.squire.server.profession.ProfessionData data, int amount) {
		if (data == null || !data.hasProfession() || amount <= 0) {
			return;
		}
		var outcome = professions.award(data, amount);
		if (!outcome.anythingHappened() && !outcome.overflowed()) {
			return;
		}
		agents.resolveByAgentId(agentId).ifPresent(avatar -> {
			persistSnapshot(avatar);
			// 经验条刚满时名牌要挂上 ★。只在这一刻刷，不逐 tick 刷——
			// 名牌是廉价的展示位，但一个每杀一只怪就重拼一次的名牌只会让人眼花。
			if (outcome.justBecameReady()) {
				avatar.refreshNameplate();
			}
		});
		if (ownerId == null) {
			return;
		}
		if (outcome.justBecameReady()) {
			var check = professions.checkPromotion(data);
			notifier.send(ownerId, "[Squire] " + data.profession().displayName()
				+ " Lv" + data.level + " 的经验条满了。给我 "
				+ dev.squire.server.profession.ProfessionService.describeCost(check.cost())
				+ "，再到职业页确认就能晋升。");
		}
	}

	/**
	 * 记一项新手训练完成（Lv.0）。
	 *
	 * <p>幂等：每一项只算一次，重复调用什么都不做。调用点刻意分散在各个<b>已经存在</b>
	 * 的事实上（开面板、给装备、下命令、击杀、施工），而不是新做一套任务系统——
	 * 训练进度就该是玩家的实际足迹。</p>
	 */
	public void noteTraining(AvatarEntity avatar,
			dev.squire.server.profession.TrainingMilestone milestone) {
		if (avatar == null || milestone == null) {
			return;
		}
		var data = professionOf(avatar);
		if (data == null || data.hasProfession() || !data.completeTraining(milestone)) {
			return; // 已经转职的随从不再需要训练；重复完成也不发第二次提示
		}
		persistSnapshot(avatar);
		UUID ownerId = avatar.ownerId();
		if (ownerId == null) {
			return;
		}
		int have = data.trainingXp();
		int need = professionConfig.trainingXpRequired;
		notifier.send(ownerId, "[Squire] 训练进度 " + have + " / " + need + "：完成了「"
			+ milestone.displayName() + "」"
			+ (have >= need
				? "\n训练做完了——打开面板的「职业」页就能给我选一个职业。"
				: ""));
	}

	/** 同上，按 agentId（战斗与任务回调手上只有 id）。 */
	void noteTraining(UUID agentId,
			dev.squire.server.profession.TrainingMilestone milestone) {
		agents.resolveByAgentId(agentId)
			.ifPresent(avatar -> noteTraining(avatar, milestone));
	}

	/** 实现见 {@link SquireProfessionService}。 */
	public ExecutionResult professionStatus(ServerPlayerEntity sender) {
		return professionService.status(sender);
	}

	/** 实现见 {@link SquireProfessionService}。 */
	public ExecutionResult professionList(ServerPlayerEntity sender) {
		return professionService.list(sender);
	}

	/** 实现见 {@link SquireProfessionService}。 */
	public ExecutionResult setProfession(ServerPlayerEntity sender, String professionId) {
		return professionService.choose(sender, professionId);
	}

	/** 实现见 {@link SquireProfessionService}。 */
	public ExecutionResult forgetProfession(ServerPlayerEntity sender) {
		return professionService.forget(sender);
	}

	/** 实现见 {@link SquireProfessionService}。 */
	public ExecutionResult promoteProfession(ServerPlayerEntity sender) {
		return professionService.promote(sender);
	}

	/** OP command backend: bypasses progression costs to create a deterministic test state. */
	public ExecutionResult debugSetProfession(ServerPlayerEntity sender,
			String professionId, int level) {
		return professionService.debugSet(sender, professionId, level);
	}

	/** 召唤时把等级效果（守卫的生命上限）落到身上。不额外回血。 */
	void applyProfessionLevelEffects(AvatarEntity avatar) {
		professionService.applyLevelEffects(avatar, professionOf(avatar), false);
	}

	/** 实现见 {@link SquireProfessionService}。 */
	public ExecutionResult setCombatStance(ServerPlayerEntity sender, String stanceId) {
		return professionService.setStance(sender, stanceId);
	}

	/** 实现见 {@link SquireProfessionService}。 */
	public ExecutionResult supplyReport(ServerPlayerEntity sender) {
		return professionService.supplies(sender);
	}

	/** Rerolling is a local server operation and never passes through the model. */
	public ExecutionResult rerollPersonality(ServerPlayerEntity sender,
			AvatarEntity avatar) {
		return personalityService.reroll(sender, avatar);
	}

	// ------------------------------------------------------------------ 工程参数

	/** 工程师的参数层。蓝图服务用它问「这一级能不能这么干」。 */
	SquireEngineerService engineer() {
		return engineerService;
	}

	/** 换掉当前工地的蓝图，落点和已交材料不变。 */
	ExecutionResult reshapeBlueprint(ServerPlayerEntity sender, String blueprintId) {
		return blueprintService.reshape(sender, blueprintId,
			() -> blueprintService.place(sender, blueprintId));
	}

	/** 实现见 {@link SquireEngineerService}。 */
	public ExecutionResult designStatus(ServerPlayerEntity sender) {
		return engineerService.status(sender);
	}

	/** 实现见 {@link SquireEngineerService}。 */
	public ExecutionResult design(ServerPlayerEntity sender, String templateId) {
		return engineerService.design(sender, templateId);
	}

	/** 实现见 {@link SquireEngineerService}。 */
	public ExecutionResult designSize(ServerPlayerEntity sender, int width, int depth) {
		return engineerService.size(sender, width, depth);
	}

	/** 实现见 {@link SquireEngineerService}。 */
	public ExecutionResult designWallHeight(ServerPlayerEntity sender, int height) {
		return engineerService.wallHeight(sender, height);
	}

	/** 实现见 {@link SquireEngineerService}。 */
	public ExecutionResult designFloors(ServerPlayerEntity sender, int floors) {
		return engineerService.floors(sender, floors);
	}

	/** 实现见 {@link SquireEngineerService}。 */
	public ExecutionResult designRoof(ServerPlayerEntity sender, String id) {
		return engineerService.roof(sender, id);
	}

	/** 实现见 {@link SquireEngineerService}。 */
	public ExecutionResult designFoundation(ServerPlayerEntity sender, String id) {
		return engineerService.foundation(sender, id);
	}

	/** 实现见 {@link SquireEngineerService}。 */
	public ExecutionResult designWindow(ServerPlayerEntity sender, String id) {
		return engineerService.window(sender, id);
	}

	/** 实现见 {@link SquireEngineerService}。 */
	public ExecutionResult designEntrance(ServerPlayerEntity sender, String id) {
		return engineerService.entrance(sender, id);
	}

	/** 实现见 {@link SquireEngineerService}。 */
	public ExecutionResult designModule(ServerPlayerEntity sender, String id) {
		return engineerService.module(sender, id);
	}

	/** 实现见 {@link SquireEngineerService}。 */
	public ExecutionResult designMirror(ServerPlayerEntity sender, String axis) {
		return engineerService.mirror(sender, axis);
	}

	/** 实现见 {@link SquireEngineerService}。 */
	public ExecutionResult designPresetSave(ServerPlayerEntity sender, String name) {
		return engineerService.savePreset(sender, name);
	}

	/** 实现见 {@link SquireEngineerService}。 */
	public ExecutionResult designPresetLoad(ServerPlayerEntity sender, String name) {
		return engineerService.loadPreset(sender, name);
	}

	/** 实现见 {@link SquireEngineerService}。 */
	public ExecutionResult designPresetList(ServerPlayerEntity sender) {
		return engineerService.listPresets(sender);
	}

	/** 实现见 {@link SquireEngineerService}。 */
	public ExecutionResult designPresetDelete(ServerPlayerEntity sender, String name) {
		return engineerService.deletePreset(sender, name);
	}

	/** 面板上的参数按钮。全部落到和命令同一条带等级闸的路径上。 */
	public ExecutionResult designCycleTemplate(ServerPlayerEntity sender, int delta) {
		return engineerService.cycleTemplate(sender, delta);
	}

	public ExecutionResult designCycleSize(ServerPlayerEntity sender, int delta) {
		return engineerService.cycleSize(sender, delta);
	}

	public ExecutionResult designCycleFloors(ServerPlayerEntity sender, int delta) {
		return engineerService.cycleFloors(sender, delta);
	}

	public ExecutionResult designCycleRoof(ServerPlayerEntity sender) {
		return engineerService.cycleRoof(sender);
	}

	public ExecutionResult designCycleFoundation(ServerPlayerEntity sender) {
		return engineerService.cycleFoundation(sender);
	}

	public ExecutionResult designCycleWindow(ServerPlayerEntity sender) {
		return engineerService.cycleWindow(sender);
	}

	public ExecutionResult designCycleEntrance(ServerPlayerEntity sender) {
		return engineerService.cycleEntrance(sender);
	}

	public ExecutionResult designCycleMirror(ServerPlayerEntity sender) {
		return engineerService.cycleMirror(sender);
	}

	public ExecutionResult designCycleModule(ServerPlayerEntity sender, int index) {
		return engineerService.cycleModule(sender, index);
	}

	public ExecutionResult designPresetSaveAuto(ServerPlayerEntity sender) {
		return engineerService.savePresetAuto(sender);
	}

	public ExecutionResult designPresetLoadNext(ServerPlayerEntity sender) {
		return engineerService.loadPresetNext(sender);
	}

	/** 实现见 {@link SquireProfileService}。 */
	public ExecutionResult profileStatus(ServerPlayerEntity sender) {
		return profileService.status(sender);
	}

	/** 实现见 {@link SquireProfileService}。 */
	public ExecutionResult roleList(ServerPlayerEntity sender) {
		return profileService.listRoles(sender);
	}

	/** 实现见 {@link SquireProfileService}。 */
	public ExecutionResult setRole(ServerPlayerEntity sender, String roleId) {
		return profileService.setRole(sender, roleId);
	}

	/** 实现见 {@link SquireProfileService}。 */
	public ExecutionResult abilityList(ServerPlayerEntity sender) {
		return profileService.listAbilities(sender);
	}

	/** 实现见 {@link SquireProfileService}。 */
	public ExecutionResult equipAbility(ServerPlayerEntity sender, String abilityId) {
		return profileService.equip(sender, abilityId);
	}

	/** 实现见 {@link SquireProfileService}。 */
	public ExecutionResult unequipAbility(ServerPlayerEntity sender, String abilityId) {
		return profileService.unequip(sender, abilityId);
	}

	/** 实现见 {@link SquireProfileService}。 */
	public ExecutionResult setAutonomy(ServerPlayerEntity sender, String levelId) {
		return profileService.setAutonomy(sender, levelId);
	}

	// ------------------------------------------------------------------ 指挥权

	/**
	 * 玩家刚下了一条<b>站位命令</b>（跟随 / 待命 / 巡逻 / 回家 / 停下）。
	 *
	 * <p>最近一次命令优先：正在跑的任务当场取消，伙伴立刻听新的。可中断性是这套系统
	 * 唯一说得过去的默认——一个「等我把这栋楼盖完再理你」的伙伴，玩家只会觉得他坏了。</p>
	 *
	 * @return 被取消的任务数，供调用方决定要不要多说一句
	 */
	public int ownerOverride(AvatarEntity avatar) {
		if (avatar == null) {
			return 0;
		}
		int cancelled = scheduler.cancelAgent(avatar.agentId(), "OWNER_OVERRIDE");
		avatar.stopMoving();
		if (cancelled > 0) {
			// 工程也一起停住：它的下一步本来就该等玩家把注意力还回来。
			projectCoordinator.activeOf(avatar.ownerId())
				.ifPresent(projectCoordinator::pause);
		}
		return cancelled;
	}

	/**
	 * 玩家下了一条<b>要跑到 {@code target} 去干的活</b>。
	 *
	 * <p>如果他正在待命、而活在圈外，就放弃待命——同样是「最近一次命令优先」。
	 * 圈内的活不受影响，待命仍然拦得住他自己跑出去。</p>
	 *
	 * @return 需要转告玩家的一句话；没什么可说时是空串
	 */
	/** 被打断了多少件活。没打断任何东西时不多说一句。 */
	private static String interrupted(int cancelled) {
		return cancelled > 0 ? "（手上的 " + cancelled + " 件活先放下了）" : "";
	}

	public String beginOrderedWork(AvatarEntity avatar, net.minecraft.util.math.BlockPos target) {
		if (avatar == null || !avatar.releaseStayFor(target)) {
			return "";
		}
		persistSnapshot(avatar);
		return "\n（那儿在待命范围之外，我先不待命了。）";
	}

	// ------------------------------------------------------------------ 工程

	/** 工程协调器（第 3 期）。 */
	public dev.squire.server.project.ProjectCoordinator projects() {
		return projectCoordinator;
	}

	/** 实现见 {@link SquireProjectService}。 */
	public ExecutionResult projectStart(ServerPlayerEntity sender, String blueprintId) {
		return projectService.start(sender, blueprintId);
	}

	/** Confirm the currently adjusted blueprint ghost as a durable project. */
	public ExecutionResult projectConfirm(ServerPlayerEntity sender) {
		return projectService.confirm(sender);
	}

	/** 实现见 {@link SquireProjectService}。 */
	public ExecutionResult projectStartFromPhrase(ServerPlayerEntity sender,
			String phrase) {
		return projectService.startFromPhrase(sender, phrase);
	}

	/** 实现见 {@link SquireProjectService}。 */
	public ExecutionResult projectStatus(ServerPlayerEntity sender) {
		return projectService.status(sender);
	}

	/** 实现见 {@link SquireProjectService}。 */
	public ExecutionResult projectPause(ServerPlayerEntity sender) {
		return projectService.pause(sender);
	}

	/** 实现见 {@link SquireProjectService}。 */
	public ExecutionResult projectResume(ServerPlayerEntity sender) {
		return projectService.resume(sender);
	}

	/** 实现见 {@link SquireProjectService}。 */
	public ExecutionResult projectCancel(ServerPlayerEntity sender) {
		return projectService.cancel(sender);
	}

	/** 实现见 {@link SquireProfileService}。 */
	public ExecutionResult addPatrolPoint(ServerPlayerEntity sender) {
		return profileService.addPatrolPoint(sender);
	}

	/** 实现见 {@link SquireProfileService}。 */
	public ExecutionResult clearPatrolPoints(ServerPlayerEntity sender) {
		return profileService.clearPatrolPoints(sender);
	}

	/** 实现见 {@link SquireProfileService}。 */
	public ExecutionResult listPatrolPoints(ServerPlayerEntity sender) {
		return profileService.listPatrolPoints(sender);
	}

	// ------------------------------------------------------------------ 蓝图

	/** 蓝图子系统（注册表 + 摆放 + 材料账）。 */
	public dev.squire.server.blueprint.BlueprintManager blueprints() {
		return blueprints;
	}

	/** 实现见 {@link SquireBlueprintService}。 */
	public ExecutionResult blueprintList() {
		return blueprintService.list();
	}

	/** 实现见 {@link SquireBlueprintService}。 */
	public ExecutionResult blueprintPlace(ServerPlayerEntity sender, String blueprintId) {
		return blueprintService.place(sender, blueprintId);
	}

	public ExecutionResult blueprintPlaceHouse(ServerPlayerEntity sender,
			dev.squire.server.blueprint.HouseSpec spec) {
		return blueprintService.placeHouse(sender, spec);
	}

	public ExecutionResult blueprintRotate(ServerPlayerEntity sender) {
		return blueprintService.rotate(sender);
	}

	public ExecutionResult blueprintNudge(ServerPlayerEntity sender, int forward,
			int right) {
		return blueprintService.nudge(sender, forward, right);
	}

	public ExecutionResult blueprintConfigureHouse(ServerPlayerEntity sender,
			dev.squire.server.blueprint.HouseSpec spec) {
		return blueprintService.configureHouse(sender, spec);
	}

	public ExecutionResult blueprintCycleMaterial(ServerPlayerEntity sender,
			int slotIndex, int delta) {
		return blueprintService.cycleMaterial(sender, slotIndex, delta);
	}

	public ExecutionResult blueprintResetMaterials(ServerPlayerEntity sender) {
		return blueprintService.resetMaterials(sender);
	}

	public ExecutionResult blueprintTransferMissing(ServerPlayerEntity sender) {
		return blueprintService.transferMissing(sender);
	}

	/** 实现见 {@link SquireBlueprintService}。 */
	public ExecutionResult blueprintStatus(ServerPlayerEntity sender) {
		return blueprintService.status(sender);
	}

	/** 实现见 {@link SquireBlueprintService}。 */
	public ExecutionResult blueprintFulfil(ServerPlayerEntity sender) {
		return blueprintService.fulfil(sender);
	}

	/** 实现见 {@link SquireBlueprintService}。 */
	public ExecutionResult blueprintBuild(ServerPlayerEntity sender) {
		return blueprintService.build(sender);
	}

	/** 实现见 {@link SquireBlueprintService}。 */
	public ExecutionResult blueprintCancel(ServerPlayerEntity sender) {
		return blueprintService.cancel(sender);
	}

	/** 管理员：重新读数据包里的蓝图。 */
	public ExecutionResult blueprintReload() {
		return blueprintService.reload();
	}

	/**
	 * 模型发起的 HIGH 风险调用被 Gateway 拦下时，在这里生成 preview 并登记成
	 * PendingOperation（方案 F3）。玩家确认后由服务器重放，不需要再问模型一次。
	 */
	public dev.squire.server.security.PendingOperation previewBlockedCall(UUID ownerId,
			UUID agentId, String toolName, java.util.Map<String, Object> arguments) {
		String preview = describeBlockedCall(agentId, toolName, arguments);
		return issuePendingOperation(ownerId, agentId, toolName, arguments, preview);
	}

	private String describeBlockedCall(UUID agentId, String toolName,
			java.util.Map<String, Object> arguments) {
		AvatarEntity avatar = agents.resolveByAgentId(agentId).orElse(null);
		if (avatar != null && avatar.getWorld() instanceof ServerWorld world
				&& (toolName.equals("minecraft.command.fill")
					|| toolName.equals("minecraft.command.setblock"))) {
			try {
				var region = dev.squire.server.world.BoundedRegion.ofCorners(
					intArg(arguments, "x1", "x"), intArg(arguments, "y1", "y"),
					intArg(arguments, "z1", "z"), intArg(arguments, "x2", "x"),
					intArg(arguments, "y2", "y"), intArg(arguments, "z2", "z"));
				var plan = new dev.squire.server.world.WorldEditor.EditPlan(
					toolName.endsWith("setblock")
						? dev.squire.server.world.WorldEditor.EditPlan.Kind.SETBLOCK
						: dev.squire.server.world.WorldEditor.EditPlan.Kind.FILL,
					world.getRegistryKey().getValue(), region,
					String.valueOf(arguments.get("blockId")), agentId,
					avatar.ownerId(), UUID.randomUUID(), null);
				return commandEditPreview(worldEditor.richPreview(world, plan, toolName));
			} catch (RuntimeException e) {
				// fall through to the generic summary rather than hiding the request
				dev.squire.SquireMod.LOGGER.warn("[Squire] preview build failed: {}",
					e.toString());
			}
		}
		return "操作：" + toolName + "\n参数：" + arguments;
	}

	static String commandEditPreview(
			dev.squire.server.world.WorldEditor.RichPreview preview) {
		return preview.describe().replace(
			"可撤销条目：" + preview.estimatedUndoEntries(),
			"执行方式：原版 /fill 或 /setblock（不写入逐块 Undo 日志）");
	}

	private static int intArg(java.util.Map<String, Object> arguments, String key,
			String fallbackKey) {
		Object value = arguments.get(key);
		if (value == null) {
			value = arguments.get(fallbackKey);
		}
		if (value instanceof Number number) {
			return number.intValue();
		}
		throw new IllegalArgumentException("missing coordinate " + key);
	}

	// ------------------------------------------------------------------ 撤销（方案 F4）

	/** 列出该玩家还能撤销的操作，最近的在前。 */
	public java.util.List<dev.squire.server.world.UndoJournal.Journal> undoableFor(
			UUID ownerId) {
		return undoJournal.listFor(ownerId);
	}

	/**
	 * 撤销一次世界编辑。冲突（有人在这之后改过这些格子）时先给出预览并要求二次确认，
	 * 绝不静默覆盖别人的改动。
	 *
	 * @param operationId null 表示"最近一次"
	 */
	public ExecutionResult undoOperation(ServerPlayerEntity player, UUID operationId,
			boolean acceptConflicts) {
		var journal = operationId == null
			? undoJournal.mostRecentFor(player.getUuid()).orElse(null)
			: undoJournal.journal(operationId).orElse(null);
		if (journal == null) {
			return ExecutionResult.fail("feedback.undo_none",
				"[Squire] 没有可撤销的操作。");
		}
		if (journal.ownerId != null && !journal.ownerId.equals(player.getUuid())
				&& !player.hasPermissionLevel(2)) {
			return ExecutionResult.fail("feedback.undo_not_owner",
				"[Squire] 这次操作属于另一位玩家。");
		}
		ServerWorld world = worldByDimensionId(journal.dimension);
		if (world == null) {
			return ExecutionResult.fail("feedback.undo_dimension",
				"[Squire] 找不到维度 " + journal.dimension + "。");
		}
		var preview = undoJournal.preview(world, journal.operationId);
		if (!preview.executable()) {
			return ExecutionResult.fail("feedback.undo_refused",
				"[Squire] 无法撤销：" + preview.rejection());
		}
		if (preview.hasConflicts() && !acceptConflicts) {
			StringBuilder message = new StringBuilder("[Squire] 撤销 ")
				.append(journal.describe()).append("\n其中 ")
				.append(preview.conflictCells())
				.append(" 格在这之后被改动过，撤销会覆盖这些改动：");
			for (var pos : preview.conflictSamples()) {
				message.append("\n - (").append(pos.getX()).append(", ")
					.append(pos.getY()).append(", ").append(pos.getZ()).append(')');
			}
			message.append("\n确认请运行 /squire undo ")
				.append(journal.operationId).append(" confirm");
			return ExecutionResult.fail("feedback.undo_conflict", message.toString());
		}
		int restored = undoJournal.undo(world, journal.operationId, acceptConflicts);
		if (restored < 0) {
			return ExecutionResult.fail("feedback.undo_refused",
				"[Squire] 撤销被拒绝，世界没有任何改变。");
		}
		String rebate = rebateProficiency(journal.operationId);
		return ExecutionResult.ok("feedback.undo_done",
			"[Squire] 已撤销 " + restored + " 格（操作 "
				+ journal.operationId.toString().substring(0, 8) + "）。" + rebate);
	}

	/**
	 * 撤销回扣（防挂机第二条）：把这次操作对应的熟练度扣回去。
	 *
	 * <p>没有它，「建 → undo → 建」就是无限刷建筑熟练度。当日额度一并扣，
	 * 否则撤销反而成了清空软上限的手段。台账里没有就什么也不做——
	 * 撤销的可能是别的东西。</p>
	 */
	private String rebateProficiency(UUID operationId) {
		var ledger = progression.takeForUndo(operationId).orElse(null);
		if (ledger == null) {
			return "";
		}
		var profile = profileOfAgent(ledger.agentId());
		long taken = progression.revoke(profile, ledger);
		if (taken <= 0) {
			return "";
		}
		agents.resolveByAgentId(ledger.agentId()).ifPresent(this::persistSnapshot);
		return "\n（连带扣回了 " + taken + " 点"
			+ ledger.track().id() + " 熟练度）";
	}

	private ServerWorld worldByDimensionId(String dimensionId) {
		for (ServerWorld world : server.getWorlds()) {
			if (world.getRegistryKey().getValue().toString().equals(dimensionId)) {
				return world;
			}
		}
		return null;
	}

	public boolean isWorldEditEnabled() {
		return worldEditEnabled;
	}

	public void setWorldEditEnabled(boolean enabled) {
		this.worldEditEnabled = enabled;
	}

	public boolean isAdminCommandsEnabled() {
		return adminCommandsEnabled;
	}

	public void setAdminCommandsEnabled(boolean enabled) {
		this.adminCommandsEnabled = enabled;
	}

	/**
	 * Killswitch state change (spec section 63). Activation cancels every pending and
	 * running task, parks agents in safe idle and pauses the scheduler — all on the
	 * calling (server) tick.
	 */
	public String setKillswitch(boolean enable) {
		if (enable) {
			long tick = currentTick();
			if (!killswitch.activate(tick)) {
				return "[Squire] Killswitch already active.";
			}
			scheduler.cancelAll();
			scheduler.setPaused(true);
			for (UUID owner : agents.knownOwners()) {
				agents.resolveForOwner(owner).ifPresent(AvatarEntity::setIdleMode);
			}
			return "[Squire] KILLSWITCH ACTIVE — all agent writes/commands denied.";
		}
		if (!killswitch.deactivate()) {
			return "[Squire] Killswitch was not active.";
		}
		scheduler.setPaused(false);
		return "[Squire] Killswitch off — normal operation resumed.";
	}

	/** Flush buffered audit entries to JSONL; called periodically and at shutdown. */
	public void flushAudit() {
		try {
			gateway.audit().appendJsonl(auditFile());
		} catch (java.io.IOException e) {
			dev.squire.SquireMod.LOGGER.warn("[Squire] audit flush failed: {}", e.toString());
		}
		replay.flush(); // §76 debug replay (off by default)
	}

	private static java.nio.file.Path auditFile() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
			.resolve("squire").resolve("audit.jsonl");
	}

	/** §76 debug replay sink (privacy notice: off by default, redacted, local). */
	private final dev.squire.server.replay.ReplayRecorder replay =
		new dev.squire.server.replay.ReplayRecorder(() ->
			net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
				.resolve("squire").resolve("replay")
				.resolve("replay-session.jsonl"));

	/** §76 replay toggle (admin surface: /squire admin replay on|off). */
	public dev.squire.server.replay.ReplayRecorder replay() {
		return replay;
	}

	public Optional<AvatarEntity> resolveAvatarFor(UUID ownerUuid) {
		return agents.resolveForOwner(ownerUuid);
	}

	/** Called from SquireMod on SERVER_STARTED; rebuilds the registry from worlds. */
	public static void init(MinecraftServer server) {
		instance = new SquireRuntime(server);
		instance.agents.rebuildFromWorlds();
		try { // 方案 A2: loaded avatars get mode/home back from their persistent record
			for (ServerWorld world : server.getWorlds()) {
				for (net.minecraft.entity.Entity entity : world.iterateEntities()) {
					if (entity instanceof AvatarEntity avatar && avatar.ownerId() != null
							&& !avatar.isRemoved()) {
						onAvatarLoad(avatar);
					}
				}
			}
		} catch (RuntimeException e) {
			dev.squire.SquireMod.LOGGER.warn("[Squire] avatar restore failed: {}",
				e.toString());
		}
		try {
			instance.extensions.loadEntrypoints(); // never fatal for startup (§58)
		} catch (RuntimeException e) {
			dev.squire.SquireMod.LOGGER.warn("[Squire] extension loading failed: {}",
				e.toString());
		}
		try {
			int recovered = instance.automation.load(instance.currentTick()); // §49 restart recovery
			if (recovered > 0) {
				dev.squire.SquireMod.LOGGER.info(
					"[Squire] recovered {} automation(s) from disk", recovered);
			}
		} catch (RuntimeException e) {
			dev.squire.SquireMod.LOGGER.warn("[Squire] automation recovery failed: {}",
				e.toString());
		}
		try { // §50: workspaces and the placed-project registry survive restarts
			int areas = instance.cbpWorkspace.load();
			int projects = instance.cbpRegistry.load();
			if (areas > 0 || projects > 0) {
				dev.squire.SquireMod.LOGGER.info(
					"[Squire] recovered {} CBP workspace(s), {} project(s)", areas,
					projects);
			}
		} catch (RuntimeException e) {
			dev.squire.SquireMod.LOGGER.warn("[Squire] CBP recovery failed: {}",
				e.toString());
		}
		try {
			int queuedMessages = instance.notifier.load(); // 离线通知队列不丢
			if (queuedMessages > 0) {
				dev.squire.SquireMod.LOGGER.info(
					"[Squire] {} offline notification(s) queued for delivery",
					queuedMessages);
			}
		} catch (RuntimeException e) {
			dev.squire.SquireMod.LOGGER.warn("[Squire] notification recovery failed: {}",
				e.toString());
		}
		try { // 第 1 期：蓝图数据包 + 摆放必须先于任务恢复就位——
			// 正在施工的任务重启后只带着一个 placementId，找不到摆放就只能如实失败。
			String loaded = instance.blueprints.registry().reload(server.getResourceManager());
			int placements = instance.blueprints.load();
			instance.projectCoordinator.load();
			dev.squire.SquireMod.LOGGER.info(
				"[Squire] blueprints: {}; {} active placement(s)", loaded, placements);
		} catch (RuntimeException e) {
			dev.squire.SquireMod.LOGGER.warn("[Squire] blueprint recovery failed: {}",
				e.toString());
		}
		recoverTasks(); // notifier must load first so restart failures are not cleared
		try {
			int recoveredGoals = instance.goals.load();
			if (recoveredGoals > 0) {
				dev.squire.SquireMod.LOGGER.info(
					"[Squire] recovered {} goal record(s)", recoveredGoals);
			}
		} catch (RuntimeException e) {
			dev.squire.SquireMod.LOGGER.warn("[Squire] goal recovery failed: {}",
				e.toString());
		}
		try { // 权限页勾选跨重启存活
			int entries = instance.permissions.loadFromDisk();
			if (entries > 0) {
				dev.squire.SquireMod.LOGGER.info(
					"[Squire] restored explicit permissions for {} player(s)", entries);
			}
		} catch (RuntimeException e) {
			dev.squire.SquireMod.LOGGER.warn("[Squire] permission recovery failed: {}",
				e.toString());
		}
		try { // 方案 F3/F4: 待确认操作和 Undo 历史跨重启存活
			int pending = instance.pendingOperations.load();
			int undoable = instance.undoJournal.load(server);
			if (pending > 0 || undoable > 0) {
				dev.squire.SquireMod.LOGGER.info(
					"[Squire] recovered {} pending confirmation(s), {} undoable operation(s)",
					pending, undoable);
			}
		} catch (RuntimeException e) {
			dev.squire.SquireMod.LOGGER.warn("[Squire] worldedit recovery failed: {}",
				e.toString());
		}
		try { // 方案 D1: 长期护卫策略跨重启继续生效
			instance.guards.load();
		} catch (RuntimeException e) {
			dev.squire.SquireMod.LOGGER.warn("[Squire] guard policy recovery failed: {}",
				e.toString());
		}
		try {
			int recoveredTurns = instance.conversations.load();
			if (recoveredTurns > 0) {
				dev.squire.SquireMod.LOGGER.info(
					"[Squire] recovered {} conversation turn(s)", recoveredTurns);
			}
		} catch (RuntimeException e) {
			dev.squire.SquireMod.LOGGER.warn("[Squire] turn recovery failed: {}",
				e.toString());
		}
		wireLlmProvider(); // §23: server-admin config, never player-supplied
	}

	/**
	 * 方案 A4：关服时导出的任务在开服后恢复。有 executor 的任务 RUNNING→READY 幂等
	 * 续跑；没有的明确 FAILED(SERVER_RESTARTED) 并通知 owner（在线直发/离线补投）。
	 */
	private static void recoverTasks() {
		java.util.List<dev.squire.server.task.TaskStateStore.Snapshot> snapshots =
			instance.taskStates.loadAndClear();
		if (snapshots.isEmpty()) {
			return;
		}
		int rearmed = instance.scheduler.restoreFromSnapshots(snapshots,
			instance.currentTick(),
			snap -> {
				String message = "[Squire] 任务 " + snap.type()
					+ " 无法在服务器重启后恢复，已标记失败（SERVER_RESTARTED）。";
				instance.notifier.send(snap.ownerId(), message);
			});
		dev.squire.SquireMod.LOGGER.info(
			"[Squire] task recovery: {} re-armed, {} unrecoverable of {} snapshot(s)",
			rearmed, snapshots.size() - rearmed, snapshots.size());
	}

	// ------------------------------------------------------------------ entity lifecycle (方案 A1/A2)

	/**
	 * summonFor 物化新实体时置位：load 钩子只建索引，不把"尚未恢复家当的空白实体"
	 * 快照进档案（否则会清空背包/装备再被恢复流程读到）。
	 */
	static final ThreadLocal<Boolean> MATERIALIZING =
		ThreadLocal.withInitial(() -> Boolean.FALSE);

	/** Chunk load / server start brought an avatar into the world. */
	public static void onAvatarLoad(AvatarEntity avatar) {
		MinecraftServer server = ((ServerWorld) avatar.getWorld()).getServer();
		SquireRuntime runtime = instance != null && instance.server == server
			? instance : null;
		if (runtime == null) {
			return; // SERVER_STARTED init path handles the initial sweep itself
		}
		if (avatar.ownerId() == null) {
			return; // 无主实体（测试/NBT 未就绪）不进长期档案
		}
		if (MATERIALIZING.get()) {
			runtime.agents.onEntityLoad(avatar);
			return; // 召唤流程稍后自行恢复档案（方案 A2）
		}
		var store = runtime.agentStore();
		// 先按 agentId 找档案。多随从时代每个 owner 有多条记录，只按 owner 找会把
		// 第二只随从当成"陌生实体"直接 discard——那是数据丢失，不是防重复。
		var agentRecord = store.recordOfAgent(avatar.agentId());
		if (agentRecord.isPresent()) {
			var authoritative = agentRecord.get();
			// 同一 agentId 的重复实体实例：保留档案当前指向的那个。
			boolean staleBody = authoritative.activeBody
				&& authoritative.entityUuid != null
				&& !authoritative.entityUuid.equals(avatar.getUuid());
			if (staleBody) {
				dev.squire.SquireMod.LOGGER.warn(
					"[Squire][audit] discarded stale avatar owner={} "
						+ "agent={} entity={} expectedEntity={}",
					avatar.ownerId(), avatar.agentId(), avatar.getUuid(),
					authoritative.entityUuid);
				avatar.discard();
				return;
			}
		} else if (store.recordOfOwner(avatar.ownerId()).isPresent()) {
			// 档案里没有这个 agentId、owner 却已有别的档案：A1 之前的旧实体
			// （agentId 是加载时才随机生成的）按非权威身体处理，旧规则不变。
			dev.squire.SquireMod.LOGGER.warn(
				"[Squire][audit] discarded non-authoritative avatar owner={} "
					+ "agent={} entity={}: owner already has a different agent record",
				avatar.ownerId(), avatar.agentId(), avatar.getUuid());
			avatar.discard();
			return;
		}
		runtime.agents.onEntityLoad(avatar);
		if (avatar.isRemoved()) {
			return; // duplicate discarded by the runtime index guard
		}
		var record = agentRecord.orElseGet(() -> store.migrateFromLegacy(avatar));
		SquireRoster.restoreRecordOnto(avatar, record);
		// 档案跟着实体走：Goal 要读能力、待命半径和巡逻点。
		avatar.attachProfile(() -> record.profile);
		// 任务接管期间移动 Goal 让位，但玩家的长期命令不丢。
		avatar.attachBusyCheck(() -> runtime.scheduler()
			.current(avatar.agentId()).isPresent());
		avatar.attachExplicitSelfCareCheck(() -> runtime.scheduler()
			.current(avatar.agentId())
			.map(task -> dev.squire.server.task.executors.HealTaskExecutor.TYPE
				.equals(task.type()))
			.orElse(false));
		avatar.applyTraits(record.profile.traits);
		store.snapshotFromEntity(avatar); // refresh lastPos/health facts
	}

	/** Chunk unload or entity removal — drop the runtime index, keep the record. */
	public static void onAvatarUnload(AvatarEntity avatar) {
		if (instance == null) {
			return;
		}
		net.minecraft.entity.Entity.RemovalReason reason = avatar.getRemovalReason();
		boolean remainsMaterialized = reason != null && reason.shouldSave();
		instance.agentStore().snapshotFromEntity(avatar, remainsMaterialized);
		instance.agents.onEntityUnload(avatar);
	}

	/**
	 * 「回家没事干」时由 {@code HomeRoutineGoal} 提交一趟收工整理。
	 *
	 * <p>用最低优先级提交，而不是在 Goal 里直接动手：玩家随时下的任何一条指令都该
	 * 立刻把它顶掉。已经有任务在跑就什么都不做——这件事本来就是「闲着才做」。</p>
	 */
	public static void requestHomeRoutine(AvatarEntity avatar) {
		if (instance == null || avatar == null || avatar.ownerId() == null) {
			return;
		}
		if (instance.scheduler.current(avatar.agentId()).isPresent()) {
			return;
		}
		instance.scheduler.submit(new dev.squire.server.task.Task(avatar.agentId(),
			avatar.ownerId(),
			dev.squire.server.task.executors.HomeRoutineExecutor.TYPE,
			dev.squire.server.task.TaskPriority.P7_IDLE,
			"回家收工：换装与整理", null,
			dev.squire.server.task.executors.HomeRoutineExecutor.routineDone(),
			200L, dev.squire.server.task.RetryPolicy.DEFAULT, true, "c2",
			java.util.Map.of()), instance.currentTick());
	}


	/**
	 * 巡逻走到一个点、停下来看完一圈之后调用。
	 *
	 * <p>报告要有<b>价值</b>才发：没发现就不说话。一个每到一个点就说一句「一切正常」的
	 * 伙伴，第三句起玩家就不看了，真出事那句也就跟着被忽略——所以沉默是默认。
	 * 同一句话在冷却期内也不重复。</p>
	 */
	public static void onPatrolCheckpoint(AvatarEntity avatar) {
		if (instance == null || avatar == null || avatar.ownerId() == null
				|| !(avatar.getWorld() instanceof ServerWorld world)) {
			return;
		}
		if (!instance.can(avatar, dev.squire.server.profile.Ability.EXPLORE_WARN)) {
			return;
		}
		var report = dev.squire.server.perception.PatrolInspector.inspect(world,
			avatar.getBlockPos());
		if (!report.anythingToSay()) {
			return;
		}
		String message = "[Squire] 巡到 " + avatar.getBlockPos().toShortString()
			+ "：" + report.describe();
		String previous = instance.lastPatrolReport.get(avatar.agentId());
		long now = instance.currentTick();
		Long lastTick = instance.lastPatrolReportTick.get(avatar.agentId());
		boolean repeat = message.equals(previous) && lastTick != null
			&& now - lastTick < PATROL_REPORT_COOLDOWN_TICKS;
		if (repeat) {
			return;
		}
		instance.lastPatrolReport.put(avatar.agentId(), message);
		instance.lastPatrolReportTick.put(avatar.agentId(), now);
		instance.notifier.send(avatar.ownerId(), message);
	}

	/** 同一条巡检结论的重复冷却：一分钟。 */
	private static final long PATROL_REPORT_COOLDOWN_TICKS = 1200L;

	private final java.util.Map<UUID, String> lastPatrolReport =
		new java.util.concurrent.ConcurrentHashMap<>();
	private final java.util.Map<UUID, Long> lastPatrolReportTick =
		new java.util.concurrent.ConcurrentHashMap<>();


	/** Death snapshots belongings before vanilla removal, then marks the body inactive. */
	/**
	 * 伙伴干掉一个敌对生物。战斗熟练度的唯一信号源。
	 *
	 * <p>和任务终态那条路径一样：只在主人在线时计，而且过当日软上限。</p>
	 */
	public static void onAvatarKill(AvatarEntity avatar,
			net.minecraft.entity.LivingEntity victim) {
		if (instance == null || avatar == null || avatar.ownerId() == null) {
			return;
		}
		if (instance.runtimeServices.requester(avatar.ownerId()) == null) {
			return; // 主人不在线不计入
		}
		var profile = instance.profileOfAgent(avatar.agentId());
		if (profile == null) {
			return;
		}
		// 职业经验先算：它要用到台账里那条交战记录，而 take 会把记录取走。
		instance.settleGuardKill(avatar, victim, true);
		instance.noteTraining(avatar,
			dev.squire.server.profession.TrainingMilestone.KILL);
		if (profile.role() != null) {
			instance.awardProficiency(avatar.agentId(), avatar.ownerId(), profile,
				dev.squire.server.profile.Track.COMBAT, 1L, null);
		}
	}

	/**
	 * 结算一次击杀的<b>职业</b>经验（设计文档 §10）。
	 *
	 * @param lastHit 最后一击是随从打的。不是的话就要看他打掉了多少血。
	 */
	private void settleGuardKill(AvatarEntity avatar,
			net.minecraft.entity.LivingEntity victim, boolean lastHit) {
		var data = professionOfAgent(avatar.agentId());
		if (data == null
				|| data.profession() != dev.squire.server.profession.SquireProfession.GUARD) {
			// 交战记录仍然要清掉，否则不是守卫的随从会一直往台账里堆条目。
			combatLedger.take(avatar.agentId(), victim.getUuid());
			return;
		}
		String entityId = entityIdOf(victim);
		var engagement = combatLedger.take(avatar.agentId(), victim.getUuid());
		double dealt = engagement == null ? 0.0 : engagement.damageDealt();
		boolean threatenedOwner = engagement != null && engagement.threatenedOwner();
		if (!dev.squire.server.profession.GuardXp.participated(professionConfig,
				lastHit, dealt, victim.getMaxHealth())) {
			return; // 站在旁边看玩家杀怪，一分不给
		}
		int rank = combatLedger.noteKill(avatar.agentId(), entityId, currentTick(),
			professionConfig.guardRepeatKillWindowTicks);
		int priorBossKills = data.bossKillsOf(entityId);
		var award = dev.squire.server.profession.GuardXp.award(professionConfig,
			entityId, threatenedOwner, rank, priorBossKills);
		if (award.boss()) {
			data.noteBossKill(entityId);
		}
		awardProfessionXp(avatar.agentId(), avatar.ownerId(), data, award.finalXp());
	}

	/**
	 * 实体的注册 id，例如 {@code minecraft:zombie}。
	 *
	 * <p>职业系统的每一张表（威胁分档、Boss 经验、高威胁名单）都以它为键，
	 * 所以战斗层也要用<b>同一个</b>写法取 id——两处各写一遍，迟早有一处会漏掉
	 * 命名空间，然后表就永远查不中。</p>
	 */
	public static String entityIdOf(net.minecraft.entity.Entity entity) {
		return net.minecraft.registry.Registries.ENTITY_TYPE.getId(entity.getType())
			.toString();
	}

	/**
	 * 随从打中了一个目标。参与度台账的<b>唯一</b>写入口。
	 *
	 * <p>没有这一条，「玩家补刀」的每一只怪随从都白打——而那正是玩家和随从一起
	 * 战斗时最常发生的事。</p>
	 */
	public static void onAvatarDealtDamage(AvatarEntity avatar,
			net.minecraft.entity.LivingEntity target, double amount) {
		if (instance == null || avatar == null || target == null || amount <= 0) {
			return;
		}
		var data = instance.professionOfAgent(avatar.agentId());
		if (data == null
				|| data.profession() != dev.squire.server.profession.SquireProfession.GUARD) {
			return;
		}
		boolean threatensOwner = avatar.ownerId() != null
			&& target instanceof net.minecraft.entity.mob.MobEntity mob
			&& mob.getTarget() != null
			&& avatar.ownerId().equals(mob.getTarget().getUuid());
		instance.combatLedger.noteDamage(avatar.agentId(), target.getUuid(),
			entityIdOf(target), target.getMaxHealth(), amount, threatensOwner,
			instance.currentTick());
	}

	/**
	 * 这个目标正在盯着主人。战斗运行时每拍报一次，保护主人加成（×1.25）据此结算。
	 *
	 * <p>必须<b>不依赖伤害</b>：一只把主人逼到墙角、最后被随从一箭放倒的骷髅，
	 * 随从可能一下近战都没打到，但那正是「保护主人」最典型的样子。</p>
	 */
	public static void noteThreatensOwner(AvatarEntity avatar,
			net.minecraft.entity.LivingEntity target, long tick) {
		if (instance == null || avatar == null || target == null) {
			return;
		}
		var data = instance.professionOfAgent(avatar.agentId());
		if (data == null
				|| data.profession() != dev.squire.server.profession.SquireProfession.GUARD) {
			return;
		}
		instance.combatLedger.noteThreatensOwner(avatar.agentId(), target.getUuid(),
			entityIdOf(target), target.getMaxHealth(), tick);
	}

	/**
	 * 结算那些<b>不是随从收尾</b>的目标：玩家补刀、掉进岩浆、被别的怪打死。
	 *
	 * <p>只能靠轮询——一只怪的死亡不会回调到打过它的每一个人身上。台账本来就很小
	 * （每只随从最多 32 条），一秒扫一次的代价可以忽略。</p>
	 */
	void sweepCombatLedger(long tick) {
		for (UUID ownerId : agents.knownOwners()) {
			AvatarEntity avatar = agents.resolveForOwner(ownerId).orElse(null);
			if (avatar == null) {
				continue;
			}
			UUID agentId = avatar.agentId();
			if (agentId == null || !(avatar.getWorld() instanceof ServerWorld world)) {
				continue;
			}
			for (var engagement : combatLedger.open(agentId)) {
				var entity = world.getEntity(engagement.targetId());
				if (entity == null) {
					continue; // 只是走出了加载区块，别当成死了
				}
				if (entity instanceof net.minecraft.entity.LivingEntity living
						&& !living.isAlive()) {
					settleGuardKill(avatar, living, false);
				}
			}
			combatLedger.pruneEngagements(agentId, tick);
		}
	}

	public static void onAvatarDeath(AvatarEntity avatar) {
		if (instance == null || avatar == null || avatar.ownerId() == null) {
			return;
		}
		var store = instance.agentStore();
		store.snapshotFromEntity(avatar, false);
		var record = store.recordOfAgent(avatar.agentId()).orElse(null);
		dev.squire.server.item.BellTier tier = record == null
			? dev.squire.server.item.BellTier.COMMON
			: dev.squire.server.item.BellTier.byId(record.bellTier);
		long now = instance.currentTick();
		long cooldown = instance.bellReviveConfig.reviveCooldown(tier);
		store.markDeath(avatar.agentId(), now, now + cooldown);
		instance.agents.unregister(avatar.getUuid());
		// 死了要说清楚"东西没丢、怎么召回"。原版只会广播一句死亡消息，
		// 玩家看到伙伴消失，既不知道背包还在不在，也不知道还能不能要回来。
		ServerPlayerEntity owner = instance.server.getPlayerManager()
			.getPlayer(avatar.ownerId());
		if (owner != null) {
			instance.feedback(owner, "[Squire] 我阵亡了。背包、装备和耐久均已原样保存；"
				+ "死亡召回将在 " + formatBellTime(cooldown) + " 后可用。");
		}
	}


	/**
	 * §23 provider wiring from {@code config/squire/llm.json}. Runs AFTER tool
	 * registration + extension imports so the model-visible catalog is complete;
	 * the catalog itself is read lazily per request. Failure = degraded mode.
	 */
	private static void wireLlmProvider() {
		try {
			java.nio.file.Path config = net.fabricmc.loader.api.FabricLoader
				.getInstance().getConfigDir().resolve("squire").resolve("llm.json");
			boolean wired = dev.squire.server.provider.LlmWiring.wire(config,
				agentId -> instance.modelVisibleDescriptors(agentId));
			if (wired) {
				dev.squire.SquireMod.LOGGER.info(
					"[Squire] conversational turns enabled via llm.json");
			}
		} catch (RuntimeException e) {
			dev.squire.SquireMod.LOGGER.warn("[Squire] LLM wiring failed: {}",
				e.toString());
		}
	}

	/** Config path for the admin LLM inspector/reload (§23). */
	public static java.nio.file.Path llmConfigFile() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
			.resolve("squire").resolve("llm.json");
	}

	/**
	 * Current MODEL_PUBLIC tool catalog (§25.1) for the provider system prompt;
	 * read on the provider's async thread, never on the server thread.
	 */
	public java.util.List<dev.squire.common.protocol.ToolDescriptor>
			modelVisibleDescriptors() {
		return modelVisibleDescriptors(null);
	}

	/**
	 * 这只随从<b>此刻</b>该让模型看见的工具目录（设计文档 §23）。
	 *
	 * <p>裁剪发生在这里，也就是<b>序列化之前</b>：一个 Lv.0 的随从根本不会收到
	 * 「多层」「镜像」「复合蓝图」的 schema，模型也就不会去想那些事，更不会生成
	 * 一个注定被拒的调用。</p>
	 *
	 * <p>每次请求现算，不缓存：玩家在面板上点完晋升的<b>下一句话</b>就该用得上
	 * 新解锁的工具，而不是要重登。</p>
	 *
	 * @param agentId 这次对话是对谁说的；null 表示「不针对某只随从」（管理员巡检），
	 *                此时给全量目录
	 */
	public java.util.List<dev.squire.common.protocol.ToolDescriptor>
			modelVisibleDescriptors(UUID agentId) {
		java.util.List<dev.squire.common.protocol.ToolDescriptor> out =
			new java.util.ArrayList<>();
		for (dev.squire.server.tool.ToolRegistry.ToolDescriptorHolder holder
				: toolRegistry().modelVisible()) {
			out.add(holder.descriptor());
		}
		if (agentId != null) {
			out = new java.util.ArrayList<>(dev.squire.server.tool.ToolGate.filter(
				out, professionOfAgent(agentId)));
		}
		return java.util.List.copyOf(out);
	}

	/**
	 * Lazy-safe variant for GameTest / early-chat contexts where SERVER_STARTED may not
	 * have been observed yet. Re-initializes when the server instance changed.
	 */
	public static void ensureInitialized(MinecraftServer server) {
		if (instance == null || instance.server != server) {
			init(server);
		}
	}

	public static void shutdown() {
		if (instance != null) {
			try {
				instance.conversations.save();
			} catch (RuntimeException e) {
				dev.squire.SquireMod.LOGGER.warn("[Squire] turn save failed: {}",
					e.toString());
			}
			try {
				instance.goals.save();
			} catch (RuntimeException e) {
				dev.squire.SquireMod.LOGGER.warn("[Squire] goal save failed: {}",
					e.toString());
			}
			try {
				instance.automation.save(); // §49 persistence: state survives restarts
			} catch (RuntimeException e) {
				dev.squire.SquireMod.LOGGER.warn("[Squire] automation save failed: {}",
					e.toString());
			}
			try { // §50: flush workspaces + placed-project registry
				instance.cbpWorkspace.save();
				instance.cbpRegistry.save();
			} catch (RuntimeException e) {
				dev.squire.SquireMod.LOGGER.warn("[Squire] CBP save failed: {}",
					e.toString());
			}
			try { // 第 1/3 期：正在施工的蓝图与工程跨重启存活
				instance.blueprints.save();
				instance.projectCoordinator.save();
			} catch (RuntimeException e) {
				dev.squire.SquireMod.LOGGER.warn("[Squire] blueprint save failed: {}",
					e.toString());
			}
			try { // 方案 A4: export live tasks so the next start can re-arm them
				java.util.List<dev.squire.server.task.TaskStateStore.Snapshot> active =
					instance.scheduler.exportActive(instance.currentTick());
				instance.taskStates.save(active);
				if (!active.isEmpty()) {
					dev.squire.SquireMod.LOGGER.info(
						"[Squire] exported {} live task(s) for restart recovery",
						active.size());
				}
			} catch (RuntimeException e) {
				dev.squire.SquireMod.LOGGER.warn("[Squire] task export failed: {}",
					e.toString());
			}
			for (UUID owner : instance.agents.knownOwners()) { // 方案 A2: final snapshots
				instance.agents.resolveForOwner(owner)
					.ifPresent(instance::persistSnapshot);
			}
			instance.notifier.save();
		}
		instance = null;
	}

	public static boolean isAlive() {
		return instance != null;
	}

	public static SquireRuntime get() {
		if (instance == null) {
			throw new IllegalStateException("SquireRuntime not initialized");
		}
		return instance;
	}

	public AgentRegistry agents() {
		return agents;
	}

	/** 方案 A1：长期身份事实来源（世界级 PersistentState，随存档保存）。 */
	public dev.squire.server.agent.SquireAgentStateStore agentStore() {
		return dev.squire.server.agent.SquireAgentStateStore.get(server);
	}

	/** 在线直发、离线补投的通知通道（方案 B2/E/G 共用）。 */
	public PlayerNotifier notifier() {
		return notifier;
	}

	public dev.squire.server.goal.GoalCoordinator goals() {
		return goals;
	}

	/** 方案 D1：长期护卫策略运行时（命令、测试和诊断入口）。 */
	public dev.squire.server.combat.GuardRuntime guards() {
		return guards;
	}

	/** Durable complex-language turn state (diagnostics and black-box tests). */
	public ConversationOrchestrator conversations() {
		return conversations;
	}

	/**
	 * 方案 A1/A2：把实体的当前状态（背包/装备/血量/位置/模式/锚点）快照进它的
	 * 永久档案；实体已移除时安全跳过。
	 */
	public void persistSnapshot(AvatarEntity avatar) {
		try {
			agentStore().snapshotFromEntity(avatar);
		} catch (RuntimeException e) {
			dev.squire.SquireMod.LOGGER.warn("[Squire] agent snapshot failed: {}",
				e.toString());
		}
	}

	// ------------------------------------------------------------------ 忙碌状态

	/**
	 * 把伙伴的忙碌状态设成 {@code state}，玩家会立刻看到头顶粒子和名牌变化。
	 * agentId 为 null 或实体不在场时静默跳过——状态提示永远不该阻断真正的工作。
	 */
	public void setActivity(UUID agentId, AvatarEntity.ActivityState state) {
		if (agentId == null) {
			return;
		}
		agents.resolveByAgentId(agentId).ifPresent(avatar -> avatar.setActivity(state));
	}

	// ------------------------------------------------------------------ role model

	/** Resolve the sender's role toward the given avatar (spec section 13). */
	public SenderRole resolveRole(ServerPlayerEntity sender, AvatarEntity avatar) {
		if (sender.hasPermissionLevel(2)) {
			return SenderRole.ADMIN;
		}
		if (agents.isOwnerOf(sender.getUuid(), avatar)) {
			return SenderRole.OWNER;
		}
		return SenderRole.PUBLIC;
	}

	// ------------------------------------------------------------------ control intents

	// —— 已停用（方向性收缩：真身只保留跟随/护卫/救援，取物改走指令兑现）——
	// 保留代码而非删除，方便日后回退。当前无任何调用方。
//	public ExecutionResult startAcquireAndGive(ServerPlayerEntity sender, String itemId,
//			int count) {
//		return giveByCommand(sender, itemId, count);
//	}

	// —— 已停用（方向性收缩：真身只保留跟随/护卫/救援，取物改走指令兑现）——
	// 保留代码而非删除，方便日后回退。当前无任何调用方。
//	public ExecutionResult startCraftAndGive(ServerPlayerEntity sender, String itemId,
//			int count) {
//		return giveByCommand(sender, itemId, count);
//	}

	/**
	 * 执行一个已经解析好的物品操作——聊天、面板按钮和模型工具的<b>共同</b>出口。
	 *
	 * <p>实现见 {@link SquireItemService#execute}。规则层和模型层产出同一个
	 * {@link dev.squire.server.nlu.ItemOperation}（从哪拿 × 哪几件 × 做什么 × 归谁），
	 * 走同一段代码，能力才不会只在其中一条路上存在。</p>
	 */
	public ExecutionResult runItemOperation(ServerPlayerEntity sender,
			dev.squire.server.nlu.ItemOperation operation) {
		return itemService.execute(sender, operation);
	}

	/** 撤销最近一次物品改动；没有物品快照时再尝试最近的世界编辑。 */
	public ExecutionResult undoItemEdit(ServerPlayerEntity sender) {
		ExecutionResult item = itemService.undoLastEdit(sender);
		if (item.success() || !"feedback.item_undo_none".equals(item.feedbackKey())) {
			return item;
		}
		return undoOperation(sender, null, false);
	}

	// ------------------------------------------------------- 世界查询与世界状态

	/**
	 * 找最近的结构。坐标来自世界生成器本身，没找到就说没找到——绝不编一个出来。
	 */
	public ExecutionResult locateStructure(ServerPlayerEntity sender,
			String structureId, String spokenName) {
		Optional<AvatarEntity> found = agents.resolveForOwner(sender.getUuid());
		if (found.isEmpty()) {
			return ExecutionResult.fail("feedback.no_agent",
				"[Squire] 侍从不在场。请右键召集铃；首次召唤方法可按 K 查看。");
		}
		if (!permissions.has(sender, dev.squire.server.security.PermissionNodes
				.COMMAND_WORLD)) {
			return ExecutionResult.fail("feedback.permission_denied",
				"[Squire] 我没有查找结构的权限（面板 → 权限页 → 「时间与查找」）。");
		}
		String label = spokenName == null || spokenName.isBlank()
			? structureId : spokenName;
		var outcome = dev.squire.server.command.StructuredCommandCompiler
			.locateStructure((net.minecraft.server.world.ServerWorld) sender.getWorld(),
				sender.getBlockPos(), structureId);
		if (outcome.errorDetail() != null) {
			return ExecutionResult.fail("feedback.locate_failed",
				"[Squire] 找不到这种结构：" + label + "（" + outcome.errorDetail() + "）");
		}
		if (!outcome.found()) {
			return ExecutionResult.ok("feedback.locate_missing",
				"[Squire] 这附近（约 1600 格内）没有" + label
					+ "。换个维度或者走远一点再问我。");
		}
		var pos = outcome.pos();
		return ExecutionResult.ok("feedback.locate_found",
			"[Squire] 最近的" + label + "在 §e" + pos.getX() + ", " + pos.getY()
				+ ", " + pos.getZ() + "§r，直线距离约 " + outcome.horizontalDistance()
				+ " 格。");
	}

	/** 找最近的生物群系。和 {@link #locateStructure} 同形，走群系查询。 */
	public ExecutionResult locateBiome(ServerPlayerEntity sender, String biomeId,
			String spokenName) {
		Optional<AvatarEntity> found = agents.resolveForOwner(sender.getUuid());
		if (found.isEmpty()) {
			return ExecutionResult.fail("feedback.no_agent",
				"[Squire] 侍从不在场。请右键召集铃；首次召唤方法可按 K 查看。");
		}
		if (!permissions.has(sender, dev.squire.server.security.PermissionNodes
				.COMMAND_WORLD)) {
			return ExecutionResult.fail("feedback.permission_denied",
				"[Squire] 我没有查找的权限（面板 → 权限页 → 「时间与查找」）。");
		}
		String label = spokenName == null || spokenName.isBlank() ? biomeId : spokenName;
		var outcome = dev.squire.server.command.StructuredCommandCompiler.locateBiome(
			(net.minecraft.server.world.ServerWorld) sender.getWorld(),
			sender.getBlockPos(), biomeId);
		if (outcome.errorDetail() != null) {
			return ExecutionResult.fail("feedback.locate_failed",
				"[Squire] 找不到这种群系：" + label + "（" + outcome.errorDetail() + "）");
		}
		if (!outcome.found()) {
			return ExecutionResult.ok("feedback.locate_missing",
				"[Squire] 这附近（约 6400 格内）没有" + label + "。");
		}
		var pos = outcome.pos();
		return ExecutionResult.ok("feedback.locate_found",
			"[Squire] 最近的" + label + "在 §e" + pos.getX() + ", " + pos.getZ()
				+ "§r，直线距离约 " + outcome.horizontalDistance() + " 格。");
	}

	/** 设置天气。 */
	public ExecutionResult setWeather(ServerPlayerEntity sender, String preset) {
		Optional<AvatarEntity> found = agents.resolveForOwner(sender.getUuid());
		if (found.isEmpty()) {
			return ExecutionResult.fail("feedback.no_agent",
				"[Squire] 侍从不在场。请右键召集铃；首次召唤方法可按 K 查看。");
		}
		if (!permissions.has(sender, dev.squire.server.security.PermissionNodes
				.COMMAND_WORLD)) {
			return ExecutionResult.fail("feedback.permission_denied",
				"[Squire] 我没有调整天气的权限（面板 → 权限页 → 「时间与查找」）。");
		}
		var outcome = dev.squire.server.command.StructuredCommandCompiler
			.executeSetWeather(server, found.get(), sender.getUuid(), protectionAdapter,
				preset, null);
		if (!outcome.success()) {
			return ExecutionResult.fail("feedback.command_failed",
				"[Squire] 调天气失败：" + outcome.errorDetail());
		}
		String label = switch (preset) {
			case "clear" -> "晴天";
			case "rain" -> "下雨";
			case "thunder" -> "雷雨";
			default -> preset;
		};
		return ExecutionResult.ok("feedback.weather_set",
			"[Squire] 天气调成" + label + "了。");
	}

	/** 只读查询：背包 / 装备 / 玩家状态 / 附近有什么。 */
	public ExecutionResult inspect(ServerPlayerEntity sender,
			dev.squire.server.fastpath.FastPathIntent.Inspect.Kind kind) {
		Optional<AvatarEntity> found = agents.resolveForOwner(sender.getUuid());
		if (found.isEmpty()) {
			return ExecutionResult.fail("feedback.no_agent",
				"[Squire] 侍从不在场。请右键召集铃；首次召唤方法可按 K 查看。");
		}
		AvatarEntity avatar = found.get();
		return switch (kind) {
			case AGENT_INVENTORY -> {
				var ids = avatar.inventory().distinctItemIds();
				if (ids.isEmpty()) {
					yield ExecutionResult.ok("feedback.inventory", "[Squire] 我背包是空的。");
				}
				StringBuilder text = new StringBuilder("[Squire] 我背包里有：");
				for (String id : ids) {
					text.append("\n   ").append(itemAliases().displayName(id))
						.append(" ×").append(avatar.inventory().countOf(id));
				}
				yield ExecutionResult.ok("feedback.inventory", text.toString());
			}
			case AGENT_EQUIPMENT -> {
				StringBuilder text = new StringBuilder("[Squire] 我身上：");
				boolean any = false;
				for (var slot : net.minecraft.entity.EquipmentSlot.values()) {
					var stack = avatar.getEquippedStack(slot);
					if (stack.isEmpty()) {
						continue;
					}
					any = true;
					text.append("\n   ").append(slot.getName()).append("：")
						.append(stack.getName().getString());
				}
				yield ExecutionResult.ok("feedback.equipment",
					any ? text.toString() : "[Squire] 我什么都没穿，也没拿武器。");
			}
			case OWNER_STATE -> ExecutionResult.ok("feedback.owner_state",
				"[Squire] " + dev.squire.server.perception.PerceptionService
					.renderOwner(sender));
			case NEARBY -> ExecutionResult.ok("feedback.nearby",
				"[Squire] 附近：" + dev.squire.server.perception.PerceptionService
					.scanNearby(avatar, 16, false));
		};
	}

	/**
	 * 「用弓打」/「近战就行」/「你自己看着办」。
	 *
	 * <p>偏好存在实体上并落盘：玩家明确说过一次之后，下一场战斗他不该又自己换回剑。
	 * 顺手把武器现在就拿到手上，让玩家立刻看得见结果，而不是等下次遇怪才知道生没生效。
	 * </p>
	 */
	public ExecutionResult setCombatStyle(ServerPlayerEntity sender,
			dev.squire.server.combat.CombatStyle.Style style) {
		Optional<AvatarEntity> found = agents.resolveForOwner(sender.getUuid());
		if (found.isEmpty()) {
			return ExecutionResult.fail("feedback.no_agent",
				"[Squire] 侍从不在场。请右键召集铃；首次召唤方法可按 K 查看。");
		}
		AvatarEntity avatar = found.get();
		var gates = weaponGatesOf(avatar);
		avatar.setCombatStyle(style);
		// 明确下令时当场换一次手——这正是玩家说「用弓打」时期待的。换不成（没弓、
		// 没箭、等级还不会用弓）不是「静悄悄地按近战打」，而是当场说清楚。
		var outcome = dev.squire.server.combat.CombatStyle
			.equipForCommand(avatar, style, gates);
		persistSnapshot(avatar);
		return describeCombatMode(avatar, gates, outcome);
	}

	/** 这只随从的职业给选武器开了哪几道闸。 */
	public dev.squire.server.combat.CombatStyle.Gates weaponGatesOf(AvatarEntity avatar) {
		var profile = profileOf(avatar);
		return dev.squire.server.combat.CombatStyle.gatesFor(
			profile == null ? null : profile.profession);
	}

	/** 面板按钮：打法换到下一档（自动 → 用弓 → 近战 → 自动）。 */
	public ExecutionResult cycleCombatMode(ServerPlayerEntity sender) {
		Optional<AvatarEntity> found = agents.resolveForOwner(sender.getUuid());
		if (found.isEmpty()) {
			return ExecutionResult.fail("feedback.no_agent",
				"[Squire] 侍从不在场。请右键召集铃；首次召唤方法可按 K 查看。");
		}
		AvatarEntity avatar = found.get();
		var gates = weaponGatesOf(avatar);
		var outcome = dev.squire.server.combat.CombatStyle.cycleMode(avatar, gates);
		persistSnapshot(avatar);
		return describeCombatMode(avatar, gates, outcome);
	}

	/**
	 * 打法的当前状态，做成回执。
	 *
	 * <p>「记下了，但眼下办不到」必须说出口。原来这里只认「没弓或者没箭」一种说法，
	 * 而且档位照样上锁——玩家看到的是「好，只用弓」，看到的行为是近战。</p>
	 */
	private ExecutionResult describeCombatMode(AvatarEntity avatar,
			dev.squire.server.combat.CombatStyle.Gates gates,
			dev.squire.server.combat.CombatStyle.CommandOutcome outcome) {
		String excuse = switch (outcome) {
			case BOW_NOT_UNLOCKED -> "[Squire] 记下了：只用弓。不过我还没学会用弓"
				+ "（守卫 Lv.4「弓箭使用」解锁），在那之前我先用近战——升上去就自动照办。";
			case NO_BOW -> "[Squire] 记下了：只用弓。可我身上一把弓都没有，"
				+ "给我一把我就换；眼下先用近战。";
			case NO_ARROWS -> "[Squire] 记下了：只用弓。可我一支箭都没有，"
				+ "给我一些箭我就换；眼下先用近战。";
			case OK -> null;
		};
		if (excuse != null) {
			return ExecutionResult.ok("feedback.combat_style", excuse);
		}
		String label = switch (dev.squire.server.combat.CombatStyle.modeOf(avatar, gates)) {
			case AUTO -> "按怪的种类挑武器（骷髅苦力怕用弓，僵尸蜘蛛用剑）";
			case BOW, BOW_LOCKED -> "只用弓";
			case MELEE -> "只用近战";
			case MANUAL -> "用你给我的这把，不自己换";
		};
		return ExecutionResult.ok("feedback.combat_style", "[Squire] 好，" + label + "。");
	}

	/** 「打那只苦力怕」：指定目标的主动攻击，区别于被动护卫。 */
	public ExecutionResult attackTarget(ServerPlayerEntity sender, String entityId,
			String spokenName) {
		Optional<AvatarEntity> found = agents.resolveForOwner(sender.getUuid());
		if (found.isEmpty()) {
			return ExecutionResult.fail("feedback.no_agent",
				"[Squire] 侍从不在场。请右键召集铃；首次召唤方法可按 K 查看。");
		}
		if (!permissions.has(sender, dev.squire.server.security.PermissionNodes
				.TASK_GUARD)) {
			return ExecutionResult.fail("feedback.permission_denied",
				"[Squire] 我没有战斗权限（面板 → 权限页 → 「护卫」）。");
		}
		AvatarEntity avatar = found.get();
		java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
		if (entityId != null) {
			params.put(dev.squire.server.task.executors.AttackTargetExecutor
				.PARAM_ENTITY_ID, entityId);
		}
		String label = spokenName == null || spokenName.isBlank()
			? "附近的敌对生物" : spokenName;
		scheduler.submit(new dev.squire.server.task.Task(
			avatar.agentId(), sender.getUuid(),
			dev.squire.server.task.executors.AttackTargetExecutor.TYPE,
			dev.squire.server.task.TaskPriority.P2_OWNER_URGENT,
			"打" + label, null,
			dev.squire.server.task.executors.AttackTargetExecutor.targetDown(),
			1200L, dev.squire.server.task.RetryPolicy.DEFAULT, true, "attack", params),
			currentTick());
		return ExecutionResult.ok("feedback.attack_started", "[Squire] 好，我去打" + label + "。");
	}

	/** 设置时间。 */
	public ExecutionResult setTime(ServerPlayerEntity sender, String preset,
			Integer dayTime) {
		Optional<AvatarEntity> found = agents.resolveForOwner(sender.getUuid());
		if (found.isEmpty()) {
			return ExecutionResult.fail("feedback.no_agent",
				"[Squire] 侍从不在场。请右键召集铃；首次召唤方法可按 K 查看。");
		}
		if (!permissions.has(sender, dev.squire.server.security.PermissionNodes
				.COMMAND_WORLD)) {
			return ExecutionResult.fail("feedback.permission_denied",
				"[Squire] 我没有调整时间的权限（面板 → 权限页 → 「时间与查找」）。");
		}
		var outcome = dev.squire.server.command.StructuredCommandCompiler.executeSetTime(
			server, found.get(), sender.getUuid(), protectionAdapter, preset, dayTime);
		if (!outcome.success()) {
			return ExecutionResult.fail("feedback.command_failed",
				"[Squire] 调时间失败：" + outcome.errorDetail());
		}
		String label = switch (preset == null ? "" : preset) {
			case "day" -> "白天";
			case "noon" -> "正午";
			case "night" -> "夜晚";
			case "midnight" -> "午夜";
			default -> String.valueOf(dayTime);
		};
		return ExecutionResult.ok("feedback.time_set", "[Squire] 时间调成" + label + "了。");
	}

	/** 给玩家加一个白名单内的状态效果。 */
	public ExecutionResult giveEffect(ServerPlayerEntity sender, String effectId,
			String spokenName, int durationTicks, int amplifier) {
		Optional<AvatarEntity> found = agents.resolveForOwner(sender.getUuid());
		if (found.isEmpty()) {
			return ExecutionResult.fail("feedback.no_agent",
				"[Squire] 侍从不在场。请右键召集铃；首次召唤方法可按 K 查看。");
		}
		if (!permissions.has(sender, dev.squire.server.security.PermissionNodes
				.COMMAND_EFFECT)) {
			return ExecutionResult.fail("feedback.permission_denied",
				"[Squire] 我没有给你上状态效果的权限（面板 → 权限页 → 「状态效果」）。");
		}
		String label = spokenName == null || spokenName.isBlank() ? effectId : spokenName;
		var outcome = dev.squire.server.command.StructuredCommandCompiler.executeEffect(
			server, sender,
			new dev.squire.server.command.StructuredCommandCompiler.EffectIntent(
				effectId, durationTicks, amplifier),
			protectionAdapter);
		if (!outcome.success()) {
			// 白名单挡下来时说清楚是"不允许"，而不是含糊的失败。
			String why = String.valueOf(outcome.errorDetail()).contains("not_allowed")
				? "这个效果不在允许清单里" : String.valueOf(outcome.errorDetail());
			return ExecutionResult.fail("feedback.command_failed",
				"[Squire] 给不了「" + label + "」：" + why);
		}
		return ExecutionResult.ok("feedback.effect_given",
			"[Squire] 好，" + label + " 给你挂上了（" + durationTicks / 20 + " 秒）。");
	}

	// --------------------------------------------------------------- 快捷指令

	public dev.squire.server.shortcut.ShortcutStore shortcuts() {
		return shortcuts;
	}

	/**
	 * 命令保存一条：{@code /squire shortcut add <名字> <动作> [档位]}。
	 *
	 * <p>按<b>名字</b>定位（同名是改写），而面板按槽位定位。两条路存下来的东西
	 * 完全一样，都是 {@code entryId + arg}。</p>
	 */
	public ExecutionResult bindShortcut(ServerPlayerEntity sender, String name,
			String entryId, String arg) {
		var entry = dev.squire.server.gui.CommandCatalog.byId(entryId);
		if (entry == null || !entry.bindable()) {
			return ExecutionResult.fail("feedback.shortcut_format",
				"[Squire] 认不出动作「" + entryId + "」。"
					+ "按 Tab 补全，或者按 K 打开面板的「指令」页。");
		}
		var variant = entry.variant(arg);
		if (variant == null) {
			StringBuilder options = new StringBuilder();
			for (var choice : entry.variants()) {
				options.append(options.length() == 0 ? "" : " / ").append(choice.arg());
			}
			return ExecutionResult.fail("feedback.shortcut_format",
				"[Squire] 「" + entry.displayName() + "」要指定档位："
					+ options + "。");
		}
		var found = shortcuts.byName(sender.getUuid(), name);
		int slot = found.isEmpty() ? shortcuts.list(sender.getUuid()).size()
			: shortcuts.names(sender.getUuid()).indexOf(found.get().name());
		var result = shortcuts.bindAt(sender.getUuid(), slot, name, entry.id(),
			variant.arg());
		return result.success()
			? ExecutionResult.ok("feedback.shortcut_saved", "[Squire] " + result.message()
				+ " → " + dev.squire.server.gui.CommandCatalog.describe(entry.id(),
					variant.arg()))
			: ExecutionResult.fail("feedback.shortcut_failed",
				"[Squire] " + result.message());
	}

	/** 面板上第 {@code slot} 格的删除。 */
	public ExecutionResult deleteShortcutAt(ServerPlayerEntity sender, int slot) {
		var result = shortcuts.removeAt(sender.getUuid(), slot);
		return result.success()
			? ExecutionResult.ok("feedback.shortcut_removed",
				"[Squire] " + result.message())
			: ExecutionResult.fail("feedback.shortcut_missing",
				"[Squire] " + result.message());
	}

	public ExecutionResult deleteShortcut(ServerPlayerEntity sender, String name) {
		var result = shortcuts.remove(sender.getUuid(), name);
		return result.success()
			? ExecutionResult.ok("feedback.shortcut_removed", "[Squire] " + result.message())
			: ExecutionResult.fail("feedback.shortcut_missing",
				"[Squire] " + result.message());
	}

	/**
	 * 面板上第 {@code slot} 格的保存：绑定到 {@code CommandCatalog} 里的一个动作。
	 *
	 * <p>面板存下来的是 {@code entryId + arg}，不是一句自然语言——点一下走的是
	 * 服务端动作，不经过模型。校验「现在还点不点得动」放在
	 * {@link #runShortcut} 而不是这里：一条因为转职而失效的快捷是要
	 * <b>留着显示成锁着的</b>，不是要在保存那一刻被拒绝。</p>
	 */
	public ExecutionResult bindShortcutAt(ServerPlayerEntity sender, int slot,
			String name, String entryId, String arg) {
		var entry = dev.squire.server.gui.CommandCatalog.byId(entryId);
		if (entry == null || entry.variant(arg) == null) {
			return ExecutionResult.fail("feedback.shortcut_failed",
				"[Squire] 认不出这个动作，重新选一个。");
		}
		var result = shortcuts.bindAt(sender.getUuid(), slot, name, entry.id(),
			entry.variant(arg).arg());
		return result.success()
			? ExecutionResult.ok("feedback.shortcut_saved", "[Squire] " + result.message())
			: ExecutionResult.fail("feedback.shortcut_failed",
				"[Squire] " + result.message());
	}

	/**
	 * 面板上第 N 个快捷按钮。
	 *
	 * <p>绑定式的走<b>服务端动作</b>：先拿真实的职业档案过一遍
	 * {@code CommandCatalog} 的能力闸，再交给和面板按钮<b>同一个</b> handler 执行，
	 * 权限在那条运行时路径里照旧判一次。全程不碰模型，所以同一条快捷点两次
	 * 得到的是同一件事，离线也照样能用。</p>
	 *
	 * <p>失效的快捷在这里被<b>拒绝</b>而不是被删掉，也不会降格去走自然语言那条路——
	 * 那样就等于给了一条绕过等级闸的后门。</p>
	 */
	public ExecutionResult runShortcut(ServerPlayerEntity sender, int index) {
		var shortcut = shortcuts.byIndex(sender.getUuid(), index);
		if (shortcut.isEmpty()) {
			return ExecutionResult.fail("feedback.shortcut_missing",
				"[Squire] 这个位置还没存快捷指令。点一下这一格就能填。");
		}
		return runShortcut(sender, shortcut.get());
	}

	/**
	 * 执行一条已经取到手的快捷指令。
	 *
	 * <p>面板上点那一格、以及在聊天里直接喊它的名字，走的都是这一条——两条入口
	 * 判定不同的话，玩家会发现「点按钮不行、喊名字行」，那等于给了一条绕过
	 * 能力闸的后门。</p>
	 */
	public ExecutionResult runShortcut(ServerPlayerEntity sender,
			dev.squire.server.shortcut.ShortcutStore.Shortcut shortcut) {
		return shortcut.bound()
			? runBoundShortcut(sender, shortcut)
			: runShortcutPhrase(sender, shortcut);
	}

	/** 绑定式快捷的执行路径：能力闸 → 动作 → 权限（在动作自己那条路径里）。 */
	private ExecutionResult runBoundShortcut(ServerPlayerEntity sender,
			dev.squire.server.shortcut.ShortcutStore.Shortcut shortcut) {
		var entry = dev.squire.server.gui.CommandCatalog.byId(shortcut.entryId());
		if (entry == null) {
			return ExecutionResult.fail("feedback.shortcut_unknown",
				"[Squire] 「" + shortcut.name() + "」指向的动作已经不存在了，"
					+ "在指令页重新设置一下。");
		}
		Optional<AvatarEntity> found = agents.resolveForOwner(sender.getUuid());
		if (found.isEmpty()) {
			return ExecutionResult.fail("feedback.no_agent",
				"[Squire] 侍从不在场。请右键召集铃；首次召唤方法可按 K 查看。");
		}
		AvatarEntity avatar = found.get();
		var lock = dev.squire.server.gui.CommandCatalog.lockOf(entry, shortcutContext(
			sender, avatar));
		if (lock != null) {
			return ExecutionResult.fail("feedback.shortcut_locked",
				"[Squire] 「" + shortcut.name() + "」现在用不了：" + lock.describe()
					+ "。快捷我给你留着。");
		}
		var variant = entry.variant(shortcut.arg());
		var action = variant == null ? null
			: dev.squire.server.gui.SquireActions.byId(variant.actionId());
		if (action == null) {
			return ExecutionResult.fail("feedback.shortcut_unknown",
				"[Squire] 「" + shortcut.name() + "」的档位对不上了，重新设置一下。");
		}
		// 和面板上那个按钮<b>同一个</b> handler：权限、冷却、回执一字不差。
		action.handler().run(sender, avatar);
		return ExecutionResult.ok("feedback.shortcut_dispatched", "");
	}

	/** 判定快捷能不能用的那份服务端事实。客户端画锁用的是同一套判据。 */
	private dev.squire.server.gui.CommandCatalog.Context shortcutContext(
			ServerPlayerEntity sender, AvatarEntity avatar) {
		return dev.squire.server.gui.CommandCatalog.Context.of(professionOf(avatar),
			projects().activeOf(sender.getUuid()).isPresent(),
			blueprints.activeOf(sender.getUuid()).isPresent());
	}

	/**
	 * 执行一条<b>老存档里的</b>自然语言快捷：把原话喂回 {@code InputGateway}。
	 *
	 * <p>这条路只服务于旧数据。面板新建的一律是绑定式的，走
	 * {@link #runBoundShortcut}——那条路不碰模型，判定和面板按钮逐字相同。
	 * 留着这一条是因为玩家存了半年的快捷不该因为一次改版凭空失效；
	 * 面板上它会被标出来，改一次就升级成绑定式的。</p>
	 */
	public ExecutionResult runShortcutPhrase(ServerPlayerEntity sender,
			dev.squire.server.shortcut.ShortcutStore.Shortcut shortcut) {
		sender.sendMessage(net.minecraft.text.Text.literal(
			"[Squire] §7«" + shortcut.name() + "» → " + shortcut.phrase() + "§r"), false);
		var routed = dev.squire.server.input.InputGateway.acceptChat(sender,
			shortcut.phrase());
		// 落到 LLM 时 acceptChat 返回空——那是正常的异步路径，回执随后自己会来。
		return routed.orElse(ExecutionResult.ok("feedback.shortcut_dispatched", ""));
	}

	public ExecutionResult listShortcuts(ServerPlayerEntity sender) {
		var all = shortcuts.list(sender.getUuid());
		if (all.isEmpty()) {
			return ExecutionResult.ok("feedback.shortcut_empty",
				"[Squire] 你还没有快捷指令。按 K 打开面板的「指令」页，"
					+ "在「我的快捷」里点任意一格就能新建。");
		}
		StringBuilder text = new StringBuilder("[Squire] 你的快捷指令：");
		for (var shortcut : all) {
			text.append("\n   §e").append(shortcut.name()).append("§r — ")
				.append(shortcut.phrase());
		}
		return ExecutionResult.ok("feedback.shortcut_list", text.toString());
	}

	// ------------------------------------------------------------- 跟随传送距离

	/** 面板按钮循环的几档。覆盖「寸步不离」到「让他自己走路」。 */
	public static final int[] FOLLOW_TELEPORT_PRESETS = {8, 12, 16, 24, 32, 48};

	/** 面板按钮：换到下一档。 */
	public ExecutionResult cycleFollowTeleportDistance(ServerPlayerEntity sender) {
		Optional<AvatarEntity> found = agents.resolveForOwner(sender.getUuid());
		if (found.isEmpty()) {
			return ExecutionResult.fail("feedback.no_agent",
				"[Squire] 侍从不在场。请右键召集铃；首次召唤方法可按 K 查看。");
		}
		int current = found.get().followTeleportDistance();
		int next = FOLLOW_TELEPORT_PRESETS[0];
		for (int i = 0; i < FOLLOW_TELEPORT_PRESETS.length; i++) {
			if (FOLLOW_TELEPORT_PRESETS[i] == current) {
				next = FOLLOW_TELEPORT_PRESETS[
					(i + 1) % FOLLOW_TELEPORT_PRESETS.length];
				break;
			}
		}
		return setFollowTeleportDistance(sender, next);
	}

	/** 命令与面板共用：设定并持久化跟随传送距离。 */
	public ExecutionResult setFollowTeleportDistance(ServerPlayerEntity sender,
			int blocks) {
		Optional<AvatarEntity> found = agents.resolveForOwner(sender.getUuid());
		if (found.isEmpty()) {
			return ExecutionResult.fail("feedback.no_agent",
				"[Squire] 侍从不在场。请右键召集铃；首次召唤方法可按 K 查看。");
		}
		AvatarEntity avatar = found.get();
		int applied = avatar.setFollowTeleportDistance(blocks);
		persistSnapshot(avatar);
		String note = applied == blocks ? ""
			: "（只能在 " + AvatarEntity.FOLLOW_TELEPORT_MIN + "–"
				+ AvatarEntity.FOLLOW_TELEPORT_MAX + " 之间）";
		return ExecutionResult.ok("feedback.follow_distance",
			"[Squire] 跟随时落后超过 " + applied + " 格我就直接传送到你身边。" + note);
	}

	// ----------------------------------------------------------- 过来 / 传送

	/**
	 * 「到我身边来」——立刻到跟前，而不只是切换成跟随。
	 *
	 * <p>以前只有 {@code 跟着我} 这种精确短语能触发跟随，而跟随本身还得等他自己
	 * 走过来；隔着一堵墙或者一个维度就永远到不了。这条是玩家心里那个「过来」：
	 * 近就走过来，远或者跨维度就直接传送。</p>
	 */
	public ExecutionResult comeToOwner(ServerPlayerEntity sender) {
		Optional<AvatarEntity> found = agents.resolveForOwner(sender.getUuid());
		if (found.isEmpty()) {
			return ExecutionResult.fail("feedback.no_agent",
				"[Squire] 侍从不在场。请右键召集铃；首次召唤方法可按 K 查看。");
		}
		AvatarEntity avatar = found.get();
		ownerOverride(avatar);
		avatar.setFollowMode(sender.getUuid());
		if (!(sender.getWorld() instanceof ServerWorld ownerWorld)) {
			return ExecutionResult.fail("UNREACHABLE", "[Squire] 你现在的位置我够不着。");
		}
		if (ownerWorld != avatar.getWorld()) {
			AvatarEntity arrived = roster.controlledTeleport(avatar, ownerWorld,
				net.minecraft.util.math.GlobalPos.create(ownerWorld.getRegistryKey(),
					sender.getBlockPos()));
			if (arrived == null) {
				return ExecutionResult.fail("UNREACHABLE",
					"[Squire] 我过不去你那个维度。");
			}
			persistSnapshot(arrived);
			return ExecutionResult.ok("feedback.came",
				"[Squire] 来了（跨维度过来的）。");
		}
		// 同维度：近就自己走，远就直接传——这正是玩家说「过来」时期待的。
		double distSq = avatar.squaredDistanceTo(sender);
		if (distSq > 36.0) {
			avatar.teleportNextTo(sender);
		} else {
			avatar.moveTo(new dev.squire.api.body.TargetPosition(dimensionId(avatar),
				sender.getX(), sender.getY(), sender.getZ()),
				dev.squire.api.body.MoveOptions.WALK);
		}
		persistSnapshot(avatar);
		return ExecutionResult.ok("feedback.came", "[Squire] 来了。");
	}

	/** 按 K 开面板时把伙伴叫到身边的触发距离（格）。再近就不动他了。 */
	public static final double PANEL_SUMMON_DISTANCE = 6.0;

	/**
	 * 让面板打得开，并且<b>让他真的站到你面前</b>。按 K 打开面板之前调一次。
	 *
	 * <p>{@code SquireScreenHandler.canUse()} 只剩下「同一个维度、身体还在」这一条
	 * 前提（它原来还要求 8 格以内，于是伙伴自己走开几步界面就被关掉）。上一版由此
	 * 推出「那也别因为距离把他拽过来」——结果是按 K 面板开了、人还在两百格外，
	 * 玩家看到的就是「这个键不管用」。按 K 这个动作本身的意思就是<b>过来一下</b>，
	 * 所以超过 {@link #PANEL_SUMMON_DISTANCE} 格就把他挪到身边；已经在跟前的不动，
	 * 免得他在你面前原地闪一下。</p>
	 *
	 * <p>刻意<b>不</b>复用 {@link #comeToOwner}：那个会 {@code ownerOverride()} 取消他
	 * 手上的活并强制切成跟随。这里只搬身体，不动模式、不取消任务——工地上的他被叫来
	 * 之后会自己走回去接着干，「待命」的他也还认着原来那个锚点。</p>
	 *
	 * <p>跨维度必须搬，因为那时他根本不是同一个实体实例。</p>
	 */
	public AvatarEntity bringWithinPanelRange(ServerPlayerEntity sender,
			AvatarEntity avatar) {
		if (avatar == null || !avatar.isAlive()
				|| !(sender.getWorld() instanceof ServerWorld ownerWorld)) {
			return avatar;
		}
		if (ownerWorld != avatar.getWorld()) {
			// 跨维度会换出一个<b>新的实体实例</b>（香草走 NBT 复制），所以必须把它
			// 返回给调用方——拿旧引用去开面板会绑到一具已经不在世界里的身体上。
			AvatarEntity arrived = roster.controlledTeleport(avatar, ownerWorld,
				net.minecraft.util.math.GlobalPos.create(ownerWorld.getRegistryKey(),
					sender.getBlockPos()));
			if (arrived == null) {
				return avatar;
			}
			arrived.teleportNextTo(sender);
			persistSnapshot(arrived);
			return arrived;
		}
		// 同维度：太远就叫到身边，近处不动。
		if (avatar.squaredDistanceTo(sender)
				> PANEL_SUMMON_DISTANCE * PANEL_SUMMON_DISTANCE
				&& !avatar.teleportNextTo(sender)) {
			// 你周围一格安全落点都没有（在飞、在船上、脚下是熔岩）时老实说一句。
			// 否则玩家只能对着一个"没反应"的按键猜是不是坏了。
			feedback(sender, "[Squire] 你周围没有他能落脚的地方，他先留在原地。");
		}
		return avatar;
	}

	/** 三个主维度的中文说法。 */
	private static final java.util.Map<String, String> DIMENSION_ALIASES =
		java.util.Map.ofEntries(
			java.util.Map.entry("主世界", "minecraft:overworld"),
			java.util.Map.entry("地表", "minecraft:overworld"),
			java.util.Map.entry("上面", "minecraft:overworld"),
			java.util.Map.entry("overworld", "minecraft:overworld"),
			java.util.Map.entry("下界", "minecraft:the_nether"),
			java.util.Map.entry("地狱", "minecraft:the_nether"),
			java.util.Map.entry("nether", "minecraft:the_nether"),
			java.util.Map.entry("末地", "minecraft:the_end"),
			java.util.Map.entry("末界", "minecraft:the_end"),
			java.util.Map.entry("the end", "minecraft:the_end"),
			java.util.Map.entry("end", "minecraft:the_end"));

	/** 中文维度名 → 注册表 id；认不出返回 null。 */
	public static String dimensionIdOf(String spoken) {
		if (spoken == null) {
			return null;
		}
		String key = spoken.trim().toLowerCase(java.util.Locale.ROOT);
		if (DIMENSION_ALIASES.containsKey(key)) {
			return DIMENSION_ALIASES.get(key);
		}
		for (var entry : DIMENSION_ALIASES.entrySet()) {
			if (key.contains(entry.getKey())) {
				return entry.getValue();
			}
		}
		return null;
	}

	/**
	 * 把<b>玩家</b>传送走（可选带上伙伴）。
	 *
	 * <p>以前只有「把伙伴传送到某处」这一条（{@code minecraft.command.teleport}
	 * 的描述里就写着 THE AGENT），玩家自己想过去是完全办不到的——
	 * 「把我和他都 TP 过去」「帮我 TP 回主世界」都卡在这。</p>
	 *
	 * @param dimensionId 目标维度；null 表示原地维度
	 * @param pos         目标坐标；null 且换维度时用玩家的重生点
	 * @param bring       要不要把伙伴一起带过去
	 */
	public ExecutionResult teleportOwner(ServerPlayerEntity sender, String dimensionId,
			BlockPos pos, boolean bring, String label) {
		Optional<AvatarEntity> found = agents.resolveForOwner(sender.getUuid());
		if (found.isEmpty()) {
			return ExecutionResult.fail("feedback.no_agent",
				"[Squire] 侍从不在场。请右键召集铃；首次召唤方法可按 K 查看。");
		}
		if (!permissions.has(sender, dev.squire.server.security.PermissionNodes
				.COMMAND_TELEPORT)) {
			return ExecutionResult.fail("feedback.permission_denied",
				"[Squire] 我没有传送你的权限（面板 → 权限页 → 「传送」）。");
		}
		ServerWorld target = dimensionId == null
			? (ServerWorld) sender.getWorld()
			: worldByKey(net.minecraft.registry.RegistryKey.of(
				net.minecraft.registry.RegistryKeys.WORLD,
				new net.minecraft.util.Identifier(dimensionId)));
		if (target == null) {
			return ExecutionResult.fail("UNREACHABLE",
				"[Squire] 这个维度现在去不了：" + dimensionId);
		}
		BlockPos destination = pos;
		if (destination == null) {
			if (target == sender.getWorld()) {
				return ExecutionResult.fail("feedback.bad_target",
					"[Squire] 你没说要去哪儿。给我个坐标，或者说个我记过的地点。");
			}
			// 只说了维度没说坐标：用重生点。玩家说「回主世界」时想的就是这个。
			destination = spawnPointIn(target, sender);
		}
		int x = clampCoordinate(destination.getX());
		int z = clampCoordinate(destination.getZ());
		int y = Math.max(target.getBottomY() + 1,
			Math.min(target.getTopY() - 2, destination.getY()));
		sender.teleport(target, x + 0.5, y, z + 0.5, sender.getYaw(), sender.getPitch());

		String where = label == null || label.isBlank()
			? x + ", " + y + ", " + z : label;
		if (!bring) {
			return ExecutionResult.ok("feedback.teleported",
				"[Squire] 把你送到" + where + "了。");
		}
		AvatarEntity avatar = found.get();
		AvatarEntity arrived = target == avatar.getWorld() ? avatar
			: roster.controlledTeleport(avatar, target,
				net.minecraft.util.math.GlobalPos.create(target.getRegistryKey(),
					new BlockPos(x, y, z)));
		if (arrived == null) {
			return ExecutionResult.ok("feedback.teleported",
				"[Squire] 把你送到" + where + "了，但我自己没能跟过来。");
		}
		arrived.teleportNextTo(sender);
		persistSnapshot(arrived);
		return ExecutionResult.ok("feedback.teleported",
			"[Squire] 我们俩都到" + where + "了。");
	}

	/**
	 * 聊天那条路的入口：目的地可能是一个维度，也可能是一个<b>记过的地点名</b>。
	 *
	 * <p>地点名走已有的位置记忆——「传送到基地」和「基地在哪」查的是同一份数据，
	 * 不再另立一套目的地概念。</p>
	 */
	public ExecutionResult teleportOwnerToPlaceOrDimension(ServerPlayerEntity sender,
			String dimensionId, String place, boolean bring, String label) {
		if (place == null || place.isBlank()) {
			return teleportOwner(sender, dimensionId, null, bring, label);
		}
		var resolution = locations().resolve(sender.getUuid(), place);
		if (resolution.candidates().isEmpty()) {
			return ExecutionResult.fail("feedback.memory_missing",
				"[Squire] 我不记得有个地方叫「" + place + "」。"
					+ "站在那儿说「这里是" + place + "」我就记住了。");
		}
		if (resolution.candidates().size() > 1) {
			return ExecutionResult.fail("feedback.memory_ambiguous",
				"[Squire] 「" + place + "」我记了好几个，说得再具体点。");
		}
		var memory = resolution.candidates().get(0);
		return teleportOwner(sender, memory.pos().getDimension().getValue().toString(),
			memory.pos().getPos(), bring, place);
	}

	/** 目标维度里的落点：主世界用玩家重生点，其余维度用世界出生点。 */
	private static BlockPos spawnPointIn(ServerWorld target, ServerPlayerEntity player) {
		if (target.getRegistryKey().equals(net.minecraft.world.World.OVERWORLD)) {
			BlockPos bed = player.getSpawnPointPosition();
			if (bed != null && player.getSpawnPointDimension()
					.equals(net.minecraft.world.World.OVERWORLD)) {
				return bed;
			}
			return target.getSpawnPos();
		}
		return target.getSpawnPos();
	}

	private static int clampCoordinate(int value) {
		return Math.max(-30_000_000, Math.min(30_000_000, value));
	}

	/** 「你叫什么」。名字存在 AgentRecord 里，命名牌改过之后也是这一份。 */
	public ExecutionResult tellName(ServerPlayerEntity sender) {
		Optional<AvatarEntity> found = agents.resolveForOwner(sender.getUuid());
		if (found.isEmpty()) {
			return ExecutionResult.fail("feedback.no_agent",
				"[Squire] 侍从不在场。请右键召集铃；首次召唤方法可按 K 查看。");
		}
		return ExecutionResult.ok("feedback.name",
			"[Squire] 我叫「" + displayNameOf(found.get()) + "」。"
				+ "要改名，打开面板后直接点左上角的名字。");
	}

	/** 伙伴当前的名字：以持久化的 AgentRecord 为准，实体名牌只是它的展示。 */
	public String displayNameOf(AvatarEntity avatar) {
		var record = agentStore().recordOfAgent(avatar.agentId());
		if (record.isPresent() && record.get().displayName != null
				&& !record.get().displayName.isBlank()) {
			return record.get().displayName;
		}
		return avatar.getCustomName() == null ? "Squire"
			: avatar.getCustomName().getString();
	}

	/** Durable identity rename. The client can only request it; this path owns mutation. */
	public ExecutionResult renameAgent(ServerPlayerEntity sender, AvatarEntity avatar,
			String name) {
		var validation = dev.squire.server.profile.SquireName.validate(name);
		if (!validation.valid()) {
			String reason = switch (validation.error()) {
				case EMPTY -> "名字不能是空的。";
				case TOO_LONG -> "名字不能超过 "
					+ dev.squire.server.profile.SquireName.MAX_LENGTH + " 个字符。";
				case ILLEGAL_CHARACTER -> "名字只能使用文字、数字、空格，以及 _ - . ' ·。";
				case NONE -> "名字不合法。";
			};
			return ExecutionResult.fail("feedback.name_invalid", "[Squire] " + reason);
		}
		var record = agentStore().recordOfAgent(avatar.agentId());
		if (record.isEmpty()) {
			return ExecutionResult.fail("feedback.no_agent",
				"[Squire] 找不到这只侍从的档案。");
		}
		if (sender == null || (!record.get().ownerId.equals(sender.getUuid())
				&& !sender.hasPermissionLevel(2))) {
			return ExecutionResult.fail("feedback.not_owner",
				"[Squire] 你不能修改别人的侍从身份。");
		}
		String trimmed = validation.value();
		record.get().setDisplayName(trimmed);
		agentStore().put(record.get());
		avatar.setBaseName(net.minecraft.text.Text.literal("[Squire] " + trimmed));
		persistSnapshot(avatar);
		return ExecutionResult.ok("feedback.renamed",
			"[Squire] 好，从现在起我叫「" + trimmed + "」。"
				+ "在聊天里跟我说话时带上这个名字，我才知道你在叫我。");
	}

	/** Compatibility facade for callers that do not already hold the panel's avatar. */
	public ExecutionResult renameAgentFromPanel(ServerPlayerEntity sender, String name) {
		Optional<AvatarEntity> found = agents.resolveForOwner(sender.getUuid());
		if (found.isEmpty()) {
			return ExecutionResult.fail("feedback.no_agent",
				"[Squire] 侍从不在场。请右键召集铃；首次召唤方法可按 K 查看。");
		}
		return renameAgent(sender, found.get(), name);
	}

	/** The panel is bound to this exact identity, which matters once an owner has several. */
	public ExecutionResult renameAgentFromPanel(ServerPlayerEntity sender,
			AvatarEntity avatar, String name) {
		return renameAgent(sender, avatar, name);
	}

	/** 物品改动的撤销日志（仅本次运行期间有效）。 */
	public dev.squire.server.item.ItemEditJournal itemEdits() {
		return itemService.editJournal();
	}

	/**
	 * 取物请求：伙伴用指令给自己，再走到玩家跟前把东西扔在他脚边。
	 * 实现搬到了 {@link SquireItemService}，这里保留门面以免所有调用方跟着改。
	 */
	public ExecutionResult giveByCommand(ServerPlayerEntity sender,
			String itemId, int count) {
		return itemService.giveByCommand(sender, itemId, count, false);
	}

	public ExecutionResult giveByCommand(ServerPlayerEntity sender,
			String itemId, int count, boolean enchanted) {
		return itemService.giveByCommand(sender, itemId, count, enchanted);
	}

	/**
	 * 「治疗自己」：让伙伴吃掉自己背包里的食物/药水回血。
	 *
	 * <p>这个能力的执行器一直都在（{@code heal.self}），但只有模型能通过工具调到——
	 * 玩家没有任何一句话能触发它。伙伴重伤时最该说的一句话反而说不出口。</p>
	 */
	public ExecutionResult startHealSelf(ServerPlayerEntity sender) {
		Optional<AvatarEntity> found = agents.resolveForOwner(sender.getUuid());
		if (found.isEmpty()) {
			return ExecutionResult.fail("feedback.no_agent",
				"[Squire] 侍从不在场。请右键召集铃；首次召唤方法可按 K 查看。");
		}
		AvatarEntity avatar = found.get();
		if (avatar.getHealth() >= avatar.getMaxHealth()) {
			return ExecutionResult.ok("feedback.already_healthy",
				"[Squire] 我血是满的，不用治。");
		}
		double targetFraction = 1.0;
		var task = new dev.squire.server.task.Task(avatar.agentId(), sender.getUuid(),
			dev.squire.server.task.executors.HealTaskExecutor.TYPE,
			dev.squire.server.task.TaskPriority.P1_SURVIVAL,
			"restore health", null,
			dev.squire.server.task.executors.HealTaskExecutor.hasHealthFraction(
				targetFraction),
			1200L, dev.squire.server.task.RetryPolicy.DEFAULT, false, "heal-self",
			java.util.Map.of("targetHealthFraction", targetFraction));
		scheduler.submit(task, currentTick());
		return ExecutionResult.ok("feedback.heal_started",
			"[Squire] 我找点吃的回血。（背包里得有食物或治疗药水）");
	}

	/** 「你能做什么」：唯一一条不需要召唤伙伴就能用的指令，玩家卡住时的出口。 */
	public ExecutionResult describeCapabilities() {
		return ExecutionResult.ok("feedback.help",
			dev.squire.server.help.CapabilityGuide.helpText());
	}

	/** 同上，但把他现在的名字写进例句里——照抄就能用，不用玩家自己去替换。 */
	public ExecutionResult describeCapabilities(ServerPlayerEntity sender) {
		String name = agents.resolveForOwner(sender.getUuid())
			.map(this::displayNameOf).orElse(null);
		return ExecutionResult.ok("feedback.help",
			dev.squire.server.help.CapabilityGuide.helpText(name));
	}

	/** 「给自己装备下界合金套装」。实现见 {@link SquireItemService}。 */
	public ExecutionResult equipSelf(ServerPlayerEntity sender, String phrase) {
		return itemService.equipSelf(sender, phrase);
	}

	/**
	 * 面板套装按钮的直连入口：材质前缀 + 整套盔甲，不经过自然语言解析。
	 * 实现见 {@link SquireItemService#equipSelfSet}。
	 */
	public ExecutionResult equipSelfSet(ServerPlayerEntity sender, String materialPrefix,
			boolean enchanted) {
		return itemService.equipSelfSet(sender, materialPrefix, enchanted);
	}

	// —— 已停用（方向性收缩：真身只保留跟随/护卫/救援，取物改走指令兑现）——
	// 保留代码而非删除，方便日后回退。当前无任何调用方。
//	public ExecutionResult startGather(ServerPlayerEntity sender, String itemId, int count) {
//		return giveByCommand(sender, itemId, count);
//	}

	public ExecutionResult startGuard(ServerPlayerEntity sender, int radius,
			boolean persistent) {
		Optional<AvatarEntity> found = agents.resolveForOwner(sender.getUuid());
		if (found.isEmpty()) {
			return ExecutionResult.fail("feedback.no_agent",
				"[Squire] Your squire is absent. Use a recall bell, or press K for the first-summon guide.");
		}
		AvatarEntity avatar = found.get();
		if (persistent) {
			// 方案 D1：“保护我”创建长期 Policy，而不是一次性 24000 tick 任务
			var policy = guards.enable(sender.getUuid(), avatar.agentId(), radius);
			return ExecutionResult.ok("feedback.guard_started",
				"[Squire] 已进入长期护卫（半径 " + policy.radius()
					+ "），说“停止保护”即可解除。");
		}
		var started = goals.guard(sender.getUuid(), avatar.agentId(), radius, false);
		return started.success()
			? ExecutionResult.ok("feedback.guard_started", "[Squire] " + started.message())
			: ExecutionResult.fail("feedback.guard_failed", "[Squire] " + started.message());
	}

	/** 方案 D1：显式解除长期护卫；重启后不会再复活。 */
	public ExecutionResult stopGuard(ServerPlayerEntity sender) {
		Optional<AvatarEntity> found = agents.resolveForOwner(sender.getUuid());
		if (found.isEmpty()) {
			return ExecutionResult.fail("feedback.no_agent",
				"[Squire] Your squire is absent. Use a recall bell, or press K for the first-summon guide.");
		}
		AvatarEntity avatar = found.get();
		scheduler.cancelAgent(avatar.agentId(), "GUARD_STOPPED");
		boolean had = guards.disable(avatar.agentId());
		return had
			? ExecutionResult.ok("feedback.guard_stopped", "[Squire] 已解除护卫。")
			: ExecutionResult.ok("feedback.guard_not_active", "[Squire] 当前没有护卫任务。");
	}

	/** 方案 D2：用 Avatar 背包里的真实治疗物品改善 OWNER 的状态。 */
	public ExecutionResult startAidOwner(ServerPlayerEntity sender) {
		Optional<AvatarEntity> found = agents.resolveForOwner(sender.getUuid());
		if (found.isEmpty()) {
			return ExecutionResult.fail("feedback.no_agent",
				"[Squire] Your squire is absent. Use a recall bell, or press K for the first-summon guide.");
		}
		AvatarEntity avatar = found.get();
		var remedy = dev.squire.server.task.executors.OwnerAidExecutor.bestRemedy(
			avatar, Math.max(0f, sender.getMaxHealth() - sender.getHealth()));
		if (remedy == null) {
			return ExecutionResult.fail("feedback.aid_no_item",
				"INSUFFICIENT_HEALING_ITEM",
				"[Squire] 背包里没有能救你的东西——治疗药水或金苹果才行，"
					+ "普通食物只能他自己吃。");
		}
		double targetFraction = 0.8;
		java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
		params.put(dev.squire.server.task.executors.OwnerAidExecutor.PARAM_OWNER_ID,
			sender.getUuid().toString());
		params.put(dev.squire.server.task.executors.OwnerAidExecutor.PARAM_TARGET_FRACTION,
			targetFraction);
		dev.squire.server.task.Task task = new dev.squire.server.task.Task(
			avatar.agentId(), sender.getUuid(),
			dev.squire.server.task.executors.OwnerAidExecutor.TYPE,
			dev.squire.server.task.TaskPriority.P1_SURVIVAL,
			"aid the owner", null,
			dev.squire.server.task.executors.OwnerAidExecutor.ownerImproved(
				runtimeServices, sender.getUuid(), targetFraction),
			1200L, dev.squire.server.task.RetryPolicy.DEFAULT, false, "d2", params);
		scheduler.submit(task, currentTick());
		return ExecutionResult.ok("feedback.aid_started",
			"[Squire] 正在用 " + remedy.itemId() + " 救你。");
	}

	// ------------------------------------------------------------------ 位置记忆（方案 E）

	/** 世界存档里的长期位置记忆（home/warehouse/farm/mine/custom）。 */
	public dev.squire.server.memory.LocationMemoryStore locations() {
		return dev.squire.server.memory.LocationMemoryStore.get(server);
	}

	/**
	 * 方案 E2：“这里是基地/仓库/农场/矿洞”。以玩家当前 GlobalPos 写入显式记忆；
	 * 仓库还会尝试绑定一个附近的真实容器（E3 要求仓库必须关联已验证容器）。
	 */
	public ExecutionResult rememberLocation(ServerPlayerEntity sender, String typeOrName) {
		if (typeOrName == null || typeOrName.isBlank()) {
			return ExecutionResult.fail("feedback.memory_needs_name",
				"[Squire] 要记住这里，请说清楚这是什么地方。");
		}
		var type = dev.squire.server.memory.LocationMemory.Type.fromPhrase(typeOrName);
		UUID agentId = agents.resolveForOwner(sender.getUuid())
			.map(AvatarEntity::agentId).orElse(null);
		GlobalPos here = GlobalPos.create(
			((ServerWorld) sender.getWorld()).getRegistryKey(),
			sender.getBlockPos().toImmutable());
		var memory = dev.squire.server.memory.LocationMemory.explicit(sender.getUuid(),
			agentId, type, typeOrName.trim(), here, currentTick());
		if (type == dev.squire.server.memory.LocationMemory.Type.WAREHOUSE) {
			GlobalPos container = findNearbyContainer((ServerWorld) sender.getWorld(),
				sender.getBlockPos());
			if (container == null) {
				return ExecutionResult.fail("feedback.warehouse_needs_container",
					"[Squire] 附近没有找到箱子/木桶，仓库记忆必须绑定一个真实容器。");
			}
			memory = memory.withContainer(container, currentTick());
		}
		locations().remember(memory);
		if (type == dev.squire.server.memory.LocationMemory.Type.HOME) {
			// HOME 同时更新伙伴的 home 锚点，两处语义保持一致
			agents.resolveForOwner(sender.getUuid()).ifPresent(avatar -> {
				avatar.setHomeGlobalPos(here);
				persistSnapshot(avatar);
			});
		}
		return ExecutionResult.ok("feedback.memory_saved",
			"[Squire] 记住了：" + memory.describe());
	}

	/** 方案 E2/E1：按说法解析长期记忆；同名冲突时列出候选而不是静默选一个。 */
	public ExecutionResult recallLocation(ServerPlayerEntity sender, String typeOrName) {
		var resolution = locations().resolve(sender.getUuid(), typeOrName);
		if (resolution.empty()) {
			return ExecutionResult.fail("feedback.memory_missing",
				"[Squire] 还没有记住“" + typeOrName + "”的位置。");
		}
		if (resolution.ambiguous()) {
			StringBuilder message = new StringBuilder("[Squire] “" + typeOrName
				+ "”有多个候选，你指的是哪一个？");
			for (var candidate : resolution.candidates()) {
				message.append("\n - ").append(candidate.describe());
			}
			return ExecutionResult.fail("feedback.memory_ambiguous", message.toString());
		}
		var memory = resolution.best();
		locations().update(memory.visited(currentTick()));
		String lead = leadTheWay(sender, memory);
		return ExecutionResult.ok("feedback.memory_recalled",
			"[Squire] " + memory.describe() + lead);
	}

	/**
	 * 探险家的「引路」：回忆地点时他真的带你走过去，而不是念一串坐标。
	 *
	 * <p>走路走的是现有的 {@code navigation.move_to} 任务，所以它照样可以被
	 * 其它任务抢占、超时、取消——引路不是一条特权路径。</p>
	 *
	 * @return 追加到回复末尾的一句话；没开这个能力就是空串
	 */
	private String leadTheWay(ServerPlayerEntity sender,
			dev.squire.server.memory.LocationMemory memory) {
		var found = agents.resolveForOwner(sender.getUuid());
		if (found.isEmpty() || !can(found.get(),
				dev.squire.server.profile.Ability.EXPLORE_GUIDE)) {
			return "";
		}
		AvatarEntity avatar = found.get();
		if (!avatar.getWorld().getRegistryKey().getValue().toString()
				.equals(memory.dimensionId())) {
			return "";
		}
		var pos = memory.pos().getPos();
		java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
		params.put("x", pos.getX());
		params.put("y", pos.getY());
		params.put("z", pos.getZ());
		params.put("arriveWithinSq", dev.squire.api.body.MoveOptions.WALK.arriveWithin());
		scheduler.submit(new dev.squire.server.task.Task(avatar.agentId(),
			sender.getUuid(),
			dev.squire.server.task.executors.MoveToExecutor.TYPE,
			dev.squire.server.task.TaskPriority.P3_USER_TASK,
			"带你去" + memory.canonicalName(), null,
			dev.squire.server.task.executors.MoveToExecutor.nearTarget(pos.getX(),
				pos.getY(), pos.getZ(),
				dev.squire.api.body.MoveOptions.WALK.arriveWithin()),
			2400L, dev.squire.server.task.RetryPolicy.DEFAULT, true, "c2", params),
			currentTick());
		return "\n跟我来。";
	}

	// —— 已停用（方向性收缩：真身只保留跟随/护卫/救援，取物改走指令兑现）——
	// 保留代码而非删除，方便日后回退。当前无任何调用方。
//	/**
//	 * 方案 E3：“把矿放回仓库”。筛出背包里的矿物 → 导航到仓库容器 → deposit →
//	 * 由 Goal Verifier 检查最终容器状态。
//	 */
//	public ExecutionResult storeOresInWarehouse(ServerPlayerEntity sender) {
//		Optional<AvatarEntity> found = agents.resolveForOwner(sender.getUuid());
//		if (found.isEmpty()) {
//			return ExecutionResult.fail("feedback.no_agent",
//				"[Squire] No active squire. Use /squire summon.");
//		}
//		AvatarEntity avatar = found.get();
//		var resolution = locations().resolve(sender.getUuid(), "仓库");
//		if (resolution.empty()) {
//			return ExecutionResult.fail("feedback.memory_missing",
//				"[Squire] 还没有记住仓库的位置，先站在仓库旁说“这里是仓库”。");
//		}
//		if (resolution.ambiguous()) {
//			return ExecutionResult.fail("feedback.memory_ambiguous",
//				"[Squire] 记了多个仓库，请说明是哪一个。");
//		}
//		var warehouse = resolution.best();
//		GlobalPos container = warehouse.containerPos() == null
//			? warehouse.pos() : warehouse.containerPos();
//		String dimension = container.getDimension().getValue().toString();
//		if (!dimensionId(avatar).equals(dimension)) {
//			return ExecutionResult.fail("feedback.wrong_dimension",
//				"[Squire] 仓库在 " + dimension + "，伙伴当前不在那个维度。");
//		}
//		var filter = dev.squire.server.task.executors.ContainerExecutors
//			.itemFilter(null, "ORES");
//		int carried = avatar.items().countMatching(filter);
//		if (carried <= 0) {
//			return ExecutionResult.fail("feedback.no_ores",
//				"[Squire] 背包里现在没有矿物可以存。");
//		}
//		BlockPos pos = container.getPos();
//		java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
//		params.put("x", pos.getX());
//		params.put("y", pos.getY());
//		params.put("z", pos.getZ());
//		params.put(dev.squire.server.task.executors.ContainerExecutors.PARAM_FILTER, "ORES");
//		dev.squire.server.task.Task task = new dev.squire.server.task.Task(
//			avatar.agentId(), sender.getUuid(),
//			dev.squire.server.task.executors.ContainerExecutors.Deposit.TYPE,
//			dev.squire.server.task.TaskPriority.P3_USER_TASK,
//			"store " + carried + " ores in the warehouse", null,
//			dev.squire.server.task.executors.ContainerExecutors.avatarHoldsAtMost(
//				runtimeServices, avatar.agentId(), filter, "ores", 0),
//			1200L, dev.squire.server.task.RetryPolicy.DEFAULT, true, "e3", params);
//		scheduler.submit(task, currentTick());
//		locations().update(warehouse.visited(currentTick()));
//		return ExecutionResult.ok("feedback.store_ores_started",
//			"[Squire] 正在把 " + carried + " 个矿物送回 " + warehouse.describe() + "。");
//	}

	/** 玩家脚下附近的第一个真实容器（仓库记忆必须绑定已验证容器，方案 E3）。 */
	private static GlobalPos findNearbyContainer(ServerWorld world, BlockPos center) {
		BlockPos best = null;
		double bestDistSq = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.iterate(center.add(-4, -3, -4),
				center.add(4, 3, 4))) {
			if (world.getBlockEntity(pos) instanceof net.minecraft.inventory.Inventory) {
				double distSq = pos.getSquaredDistance(center);
				if (distSq < bestDistSq) {
					bestDistSq = distSq;
					best = pos.toImmutable();
				}
			}
		}
		return best == null ? null : GlobalPos.create(world.getRegistryKey(), best);
	}

	/**
	 * 方案 G1/G2/B09：“每天晚上在基地开灯”。区域来自可信的基地记忆，动作是翻动
	 * 真实的拉杆——世界里不会新增任何命令方块。写世界的自动化必须先预览再确认。
	 */
	public ExecutionResult createNightLightsAutomation(ServerPlayerEntity sender) {
		if (!automation.isEnabled()) {
			return ExecutionResult.fail("feedback.automation_disabled",
				"[Squire] 自动化当前关闭，服主可用 /squire admin automation enable 打开。");
		}
		Optional<AvatarEntity> found = agents.resolveForOwner(sender.getUuid());
		if (found.isEmpty()) {
			return ExecutionResult.fail("feedback.no_agent",
				"[Squire] Your squire is absent. Use a recall bell, or press K for the first-summon guide.");
		}
		AvatarEntity avatar = found.get();
		var resolved = locations().resolve(sender.getUuid(), "基地");
		if (resolved.empty()) {
			resolved = locations().resolve(sender.getUuid(), "家");
		}
		if (resolved.empty()) {
			return ExecutionResult.fail("feedback.no_base_memory",
				"[Squire] 还没有记住基地的位置，先站在基地说“这里是基地”。");
		}
		if (resolved.ambiguous()) {
			return ExecutionResult.fail("feedback.memory_ambiguous",
				"[Squire] 记了多个基地，请说明是哪一个。");
		}
		var base = resolved.best();
		ServerWorld world = worldByDimensionId(base.dimensionId());
		if (world == null) {
			return ExecutionResult.fail("feedback.wrong_dimension",
				"[Squire] 找不到维度 " + base.dimensionId() + "。");
		}
		// 没有真实开关就如实说，而不是建一个每天什么都不做的自动化
		var switches = dev.squire.server.task.executors.BaseLightsExecutor
			.findLightSwitches(world, base.pos().getPos(),
				dev.squire.server.task.executors.BaseLightsExecutor.DEFAULT_RADIUS);
		if (switches.isEmpty()) {
			return ExecutionResult.fail("feedback.no_light_anchor",
				"[Squire] 基地附近没有找到拉杆。开灯要控制真实的红石开关，"
					+ "请先在灯的电路上放一个拉杆。");
		}
		var compiled = dev.squire.server.automation.AutomationCompiler.compileNightLights(
			sender.getUuid(), avatar.agentId(), base.canonicalName(), base.dimensionId(),
			base.pos().getPos(),
			dev.squire.server.task.executors.BaseLightsExecutor.DEFAULT_RADIUS);
		if (!compiled.ok()) {
			return ExecutionResult.fail("feedback.automation_refused",
				"[Squire] 无法创建：" + compiled.rejection());
		}
		for (var graph : compiled.graphs()) {
			String problem = dev.squire.server.automation.AutomationCompiler.validate(
				graph, name -> tools.lookup(name).isPresent());
			if (problem != null) {
				return ExecutionResult.fail("feedback.automation_refused",
					"[Squire] 无法创建：" + problem);
			}
		}
		// 写世界的自动化必须确认（方案 G1），复用 F3 的持久待确认流程
		java.util.Map<String, Object> canonical = new java.util.LinkedHashMap<>();
		canonical.put("template", "NIGHT_LIGHTS");
		canonical.put("x", base.pos().getPos().getX());
		canonical.put("y", base.pos().getPos().getY());
		canonical.put("z", base.pos().getPos().getZ());
		canonical.put("radius",
			dev.squire.server.task.executors.BaseLightsExecutor.DEFAULT_RADIUS);
		var operation = issuePendingOperation(sender.getUuid(), avatar.agentId(),
			"automation.create", canonical,
			compiled.preview() + "\n附近已找到 " + switches.size() + " 个拉杆");
		return ExecutionResult.ok("feedback.preview_issued",
			"[Squire] 预览（还没有创建任何自动化）：\n" + compiled.preview()
				+ "\n附近已找到 " + switches.size() + " 个拉杆"
				+ "\n确认创建：/squire confirm " + operation.confirmId()
				+ "\n取消：/squire deny " + operation.confirmId());
	}

	/** Register the compiled graphs for an already-confirmed automation template. */
	public ExecutionResult registerNightLights(ServerPlayerEntity sender,
			BlockPos base, int radius) {
		Optional<AvatarEntity> found = agents.resolveForOwner(sender.getUuid());
		if (found.isEmpty()) {
			return ExecutionResult.fail("feedback.no_agent",
				"[Squire] Your squire is absent. Use a recall bell, or press K for the first-summon guide.");
		}
		AvatarEntity avatar = found.get();
		String dimension = dimensionId(avatar);
		var compiled = dev.squire.server.automation.AutomationCompiler.compileNightLights(
			sender.getUuid(), avatar.agentId(), "base", dimension, base, radius);
		if (!compiled.ok()) {
			return ExecutionResult.fail("feedback.automation_refused",
				"[Squire] 无法创建：" + compiled.rejection());
		}
		java.util.List<String> created = new java.util.ArrayList<>();
		for (var graph : compiled.graphs()) {
			String problem = dev.squire.server.automation.AutomationCompiler.validate(
				graph, name -> tools.lookup(name).isPresent());
			if (problem != null) {
				return ExecutionResult.fail("feedback.automation_refused",
					"[Squire] 无法创建：" + problem);
			}
			var registered = automation.create(graph, currentTick());
			if (registered == null) {
				return ExecutionResult.fail("feedback.automation_refused",
					"[Squire] 自动化数量已达上限，先移除一些再试。");
			}
			created.add(registered.name() + "（" + registered.id() + "）");
		}
		return ExecutionResult.ok("feedback.automation_created",
			"[Squire] 已创建夜间灯光自动化：\n - " + String.join("\n - ", created)
				+ "\n用 /squire automation list 查看，pause/resume/remove 管理。");
	}

	/** A real CBP never materializes implicitly; this entry only starts its safe workflow. */
	public ExecutionResult requestRealCbp(ServerPlayerEntity sender, String templateOrName) {
		// 方案 H1：只有明确说出"命令方块/可编辑/教学"这类意图才可能物化
		var intent = dev.squire.server.cbp.CbpIntent.classify(templateOrName);
		switch (intent.verdict()) {
			case PREFER_AUTOMATION -> {
				return ExecutionResult.fail("feedback.cbp_prefer_automation",
					"[Squire] " + intent.reason()
						+ "。要真的放命令方块，请明确说“用命令方块做一个…”。");
			}
			case UNCLEAR -> {
				return ExecutionResult.fail("feedback.cbp_unclear",
					"[Squire] " + intent.reason());
			}
			default -> { /* EXPLICIT：继续 */ }
		}
		if (!cbpEnabled) {
			return ExecutionResult.fail("feedback.cbp_disabled",
				"[Squire] 真实命令方块工程当前关闭，服主可用 "
					+ "/squire admin cbp enable 打开。");
		}
		Optional<AvatarEntity> found = agents.resolveForOwner(sender.getUuid());
		if (found.isEmpty()) {
			return ExecutionResult.fail("feedback.no_agent",
				"[Squire] Your squire is absent. Use a recall bell, or press K for the first-summon guide.");
		}
		AvatarEntity avatar = found.get();
		var template = dev.squire.server.cbp.CbpPlanner.Template
			.fromPhrase(templateOrName);
		if (template == null) {
			template = dev.squire.server.cbp.CbpPlanner.Template.parse(templateOrName);
		}
		if (template == null) {
			return ExecutionResult.fail("feedback.cbp_unknown_template",
				"[Squire] 没有这个模板。可用："
					+ dev.squire.server.cbp.CbpPlanner.supportedTemplates());
		}
		var area = cbpWorkspace.areaOf(sender.getUuid()).orElse(null);
		if (area == null) {
			return ExecutionResult.fail("feedback.cbp_needs_workspace",
				"[Squire] 请先用 /squire workspace set <from> <to> 划一块工作区；"
					+ "命令方块只会建在那里。");
		}
		return planCbp(sender, avatar, template, area.region().min());
	}

	/**
	 * 方案 H2/H3：把模板编译成 spec，逐块预览，并交给 Materializer 等待一次确认。
	 * 确认之前世界零改变。
	 */
	public ExecutionResult planCbp(ServerPlayerEntity sender, AvatarEntity avatar,
			dev.squire.server.cbp.CbpPlanner.Template template, BlockPos anchor) {
		var area = cbpWorkspace.areaOf(sender.getUuid()).orElse(null);
		if (area == null) {
			return ExecutionResult.fail("feedback.cbp_needs_workspace",
				"[Squire] 请先用 /squire workspace set <from> <to> 划一块工作区。");
		}
		dev.squire.server.cbp.CbpSpec spec;
		try {
			// agentId 必须是真实伙伴的 id，绝不是玩家 UUID（方案 H3）
			spec = dev.squire.server.cbp.CbpPlanner.plan(template, sender.getUuid(),
				avatar.agentId(), template.name().toLowerCase(java.util.Locale.ROOT),
				anchor);
		} catch (RuntimeException e) {
			return ExecutionResult.fail("feedback.cbp_refused",
				"[Squire] 无法规划：" + e.getMessage());
		}
		var problems = spec.validate();
		if (!problems.isEmpty()) {
			return ExecutionResult.fail("feedback.cbp_refused",
				"[Squire] 规划不合法：" + String.join("；", problems));
		}
		var result = cbp.plan(spec, currentTick());
		if (!result.ok()) {
			return ExecutionResult.fail("feedback.cbp_refused",
				"[Squire] " + result.message());
		}
		String preview = dev.squire.server.cbp.CbpPlanner.describe(spec,
			area.dimension());
		return ExecutionResult.ok("feedback.cbp_planned",
			"[Squire] 预览（世界还没有任何改变）：\n" + preview
				+ "\n确认建造：/squire confirm " + result.confirmId()
				+ "\n取消：/squire deny " + result.confirmId());
	}

	/** Execute a control intent; write intents require OWNER or ADMIN on the target avatar. */
	public ExecutionResult executeControl(ServerPlayerEntity sender, ControlIntent intent) {
		Optional<AvatarEntity> found = agents.resolveForOwner(sender.getUuid());
		if (found.isEmpty()) {
			return ExecutionResult.fail("feedback.no_agent", intent == ControlIntent.DISMISS
				? "[Squire] No squire exists."
				: "[Squire] Your squire is absent. Use a recall bell, or press K for the first-summon guide.");
		}
		AvatarEntity avatar = found.get();
		SenderRole role = resolveRole(sender, avatar);
		if (role == SenderRole.PUBLIC || role == SenderRole.TRUSTED) {
			return ExecutionResult.fail("feedback.not_owner", "[Squire] Only the owner can do that.");
		}
		// 新手训练「下一次站位命令」。DISMISS/STATUS 不算——那两个是<b>看</b>和
		// <b>收工</b>，不是给他派活。
		if (intent == ControlIntent.FOLLOW || intent == ControlIntent.STAY
				|| intent == ControlIntent.STOP
				|| intent == ControlIntent.HOME_RETURN) {
			noteTraining(avatar, dev.squire.server.profession.TrainingMilestone.ORDER);
		}

		switch (intent) {
			case FOLLOW -> {
				// 最近一次命令优先：手上的活当场停，他立刻听新的。
				int cancelled = ownerOverride(avatar);
				avatar.setFollowMode(sender.getUuid());
				persistSnapshot(avatar); // 方案 A2：模式变化立即落盘
				return ExecutionResult.ok("feedback.following",
					"[Squire] 跟着你走。" + interrupted(cancelled));
			}
			case STAY -> {
				int cancelled = ownerOverride(avatar);
				avatar.setStayMode();
				persistSnapshot(avatar);
				return ExecutionResult.ok("feedback.staying",
					"[Squire] 在这儿待命，不会乱走。" + interrupted(cancelled));
			}
			case STOP -> { // 方案 A3：stop 同时取消该伙伴的全部任务
				int cancelled = ownerOverride(avatar);
				avatar.setIdleMode();
				persistSnapshot(avatar);
				return ExecutionResult.ok("feedback.stopped",
					"[Squire] 停下了。" + interrupted(cancelled));
			}
			case HOME_RETURN -> {
				net.minecraft.util.math.GlobalPos home =
					avatar.homeGlobalPos().orElse(null);
				// 回家也是一条站位命令：它取代之前的跟随/待命，
				// 并且按「最近一次命令优先」把手上的活放下。
				if (home == null) {
					return ExecutionResult.fail("feedback.no_home",
						"[Squire] 还没有设置家。可在侍从面板的命令页把这里设为家。");
				}
				ServerWorld target = worldByKey(home.getDimension());
				ServerWorld current = (ServerWorld) avatar.getWorld();
				BlockPos pos = home.getPos();
				if (target == null) {
					return ExecutionResult.fail("UNREACHABLE",
						"[Squire] Home dimension is not available right now.");
				}
				if (!target.getRegistryKey().equals(current.getRegistryKey())) {
					AvatarEntity arrived = roster.controlledTeleport(avatar, target,
						home); // 方案 A2：跨维度回家
					if (arrived == null) {
						return ExecutionResult.fail("UNREACHABLE",
							"[Squire] Couldn't cross to the home dimension.");
					}
					persistSnapshot(arrived);
					feedback(sender, "[Squire] Returned home (cross-dimension).");
					return ExecutionResult.ok("feedback.going_home",
						"[Squire] Returned home (cross-dimension).");
				}
				int cancelled = ownerOverride(avatar);
				avatar.setIdleMode(); // 到家之后就待在家，不再跟着人走
				persistSnapshot(avatar);
				var handle = avatar.moveTo(new dev.squire.api.body.TargetPosition(
						dimensionId(avatar), pos.getX() + 0.5, pos.getY(),
						pos.getZ() + 0.5),
					dev.squire.api.body.MoveOptions.WALK);
				if (handle.state() == dev.squire.api.body.MoveHandle.State.FAILED) {
					String code = avatar.lastMoveErrorCode() == null
						? "PATH_NOT_FOUND" : avatar.lastMoveErrorCode();
					return ExecutionResult.fail(code,
						"[Squire] Can't reach home from here (" + code + ").");
				}
				return ExecutionResult.ok("feedback.going_home",
					"[Squire] 回家去了。" + interrupted(cancelled));
			}
			case STATUS -> {
				var state = avatar.snapshotState();
				String dim = avatar.getWorld().getRegistryKey().getValue().getPath();
				String text = String.format(
					"[Squire] mode=%s dim=%s pos=(%.0f, %.0f, %.0f) hp=%.0f/%.0f",
					avatar.mode(), dim, state.x(), state.y(), state.z(),
					state.health(), state.maxHealth());
				feedback(sender, text);
				return ExecutionResult.ok("feedback.status", text);
			}
			case DISMISS -> { // 方案 A1：实体消失，身份与家当留在 Store 里
				persistSnapshot(avatar);
				agentStore().setActiveBody(avatar.agentId(), false);
				scheduler.cancelAgent(avatar.agentId(), "dismiss");
				agents.unregister(avatar.getUuid());
				avatar.discard();
				return ExecutionResult.ok("feedback.dismissed", "[Squire] Dismissed. Summon again to bring them back with their belongings.");
			}
		}
		return ExecutionResult.fail("feedback.unknown_intent", "[Squire] Unknown intent.");
	}


	private ServerWorld worldByKey(net.minecraft.registry.RegistryKey<net.minecraft.world.World> key) {
		for (ServerWorld world : server.getWorlds()) {
			if (world.getRegistryKey().equals(key)) {
				return world;
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ look & emote（方案 A3）

	/** “看着我” / {@code /squire look me}：转头面向主人（owner/admin）。 */
	public ExecutionResult executeLookMe(ServerPlayerEntity sender) {
		Optional<AvatarEntity> found = agents.resolveForOwner(sender.getUuid());
		if (found.isEmpty()) {
			return ExecutionResult.fail("feedback.no_agent",
				"[Squire] Your squire is absent. Use a recall bell, or press K for the first-summon guide.");
		}
		AvatarEntity avatar = found.get();
		if (!roleAllowsWrite(sender, avatar)) {
			return ExecutionResult.fail("feedback.not_owner",
				"[Squire] Only the owner can do that.");
		}
		Vec3d eye = sender.getEyePos();
		avatar.lookAt(new dev.squire.api.body.TargetPosition(dimensionId(avatar),
			eye.x, eye.y, eye.z));
		return ExecutionResult.ok("feedback.look_me", "[Squire] Looking at you.");
	}

	/** {@code /squire look <x> <y> <z>}：看向指定坐标（owner/admin）。 */
	public ExecutionResult executeLookAt(ServerPlayerEntity sender,
			double x, double y, double z) {
		Optional<AvatarEntity> found = agents.resolveForOwner(sender.getUuid());
		if (found.isEmpty()) {
			return ExecutionResult.fail("feedback.no_agent",
				"[Squire] Your squire is absent. Use a recall bell, or press K for the first-summon guide.");
		}
		AvatarEntity avatar = found.get();
		if (!roleAllowsWrite(sender, avatar)) {
			return ExecutionResult.fail("feedback.not_owner",
				"[Squire] Only the owner can do that.");
		}
		avatar.lookAt(new dev.squire.api.body.TargetPosition(dimensionId(avatar),
			x, y, z));
		return ExecutionResult.ok("feedback.look_at",
			String.format("[Squire] Looking at (%.0f, %.0f, %.0f).", x, y, z));
	}

	/** 表情动作（挥手/跳/点头/摇头），owner/admin。 */
	public ExecutionResult executeEmote(ServerPlayerEntity sender,
			dev.squire.api.body.EmoteType type) {
		Optional<AvatarEntity> found = agents.resolveForOwner(sender.getUuid());
		if (found.isEmpty()) {
			return ExecutionResult.fail("feedback.no_agent",
				"[Squire] Your squire is absent. Use a recall bell, or press K for the first-summon guide.");
		}
		AvatarEntity avatar = found.get();
		if (!roleAllowsWrite(sender, avatar)) {
			return ExecutionResult.fail("feedback.not_owner",
				"[Squire] Only the owner can do that.");
		}
		avatar.emote(type);
		String message = switch (type) {
			case WAVE -> "*waves*";
			case JUMP -> "*jumps*";
			case NOD -> "*nods*";
			case SHAKE_HEAD -> "*shakes head*";
		};
		return ExecutionResult.ok("feedback.emote", message);
	}

	private boolean roleAllowsWrite(ServerPlayerEntity sender, AvatarEntity avatar) {
		SenderRole role = resolveRole(sender, avatar);
		return role == SenderRole.OWNER || role == SenderRole.ADMIN;
	}

	/**
	 * Set the agent's home to the PLAYER's current position and dimension
	 * (方案 A2：home 存玩家位置 GlobalPos，跨维度回家语义成立)。
	 */
	public ExecutionResult setHome(ServerPlayerEntity sender) {
		Optional<AvatarEntity> found = agents.resolveForOwner(sender.getUuid());
		if (found.isEmpty()) {
			return ExecutionResult.fail("feedback.no_agent",
				"[Squire] Your squire is absent. Use a recall bell, or press K for the first-summon guide.");
		}
		AvatarEntity avatar = found.get();
		if (resolveRole(sender, avatar) != SenderRole.OWNER
				&& resolveRole(sender, avatar) != SenderRole.ADMIN) {
			return ExecutionResult.fail("feedback.not_owner", "[Squire] Only the owner can do that.");
		}
		net.minecraft.util.math.GlobalPos home = net.minecraft.util.math.GlobalPos.create(
			((ServerWorld) sender.getWorld()).getRegistryKey(),
			sender.getBlockPos().toImmutable());
		avatar.setHomeGlobalPos(home);
		persistSnapshot(avatar); // home 随档案立即落盘
		// 方案 E1：home 同时进入长期位置记忆，"回家"和"家在哪"看到同一个事实
		locations().remember(dev.squire.server.memory.LocationMemory.explicit(
			sender.getUuid(), avatar.agentId(),
			dev.squire.server.memory.LocationMemory.Type.HOME, "家", home,
			currentTick()));
		return ExecutionResult.ok("feedback.home_set",
			"[Squire] Home set to where you're standing.");
	}

	// ------------------------------------------------------------------ conversation

	/**
	 * Plain conversation: the ONLY route into the LLM. Delegates to the cognitive
	 * turn orchestrator (perception → provider → parse → gateway → feedback).
	 */
	public void handleConversation(ServerPlayerEntity sender, String rawText) {
		conversations.beginTurn(sender, rawText);
	}

	// ------------------------------------------------------------------ summon

	/**
	 * Summon an avatar owned by {@code owner}（方案 A1/A2）。
	 * 实现搬到了 {@link SquireRoster}，这里保留门面以免所有调用方跟着改。
	 */
	public AvatarEntity summonFor(ServerPlayerEntity owner) {
		return roster.summonFor(owner);
	}

	/** Creates the first persistent identity at a completed training dummy. */
	public AvatarEntity summonFirstAt(ServerPlayerEntity owner, BlockPos feet) {
		if (owner == null || feet == null
				|| agentStore().recordOfOwner(owner.getUuid()).isPresent()) {
			return null;
		}
		return roster.summonAt(owner, feet);
	}

	/** Owner-bound, non-command recall path used by the recall bell. */
	public ExecutionResult recallWithBell(ServerPlayerEntity owner,
			net.minecraft.item.ItemStack bell) {
		var nbt = bell.getOrCreateNbt();
		if (nbt.containsUuid(dev.squire.server.registry.SquireItems.NBT_OWNER)
				&& !owner.getUuid().equals(nbt.getUuid(
					dev.squire.server.registry.SquireItems.NBT_OWNER))) {
			return ExecutionResult.fail("feedback.not_owner",
				"[Squire] 这枚召集铃不属于你。");
		}
		var record = agentStore().recordOfOwner(owner.getUuid()).orElse(null);
		if (record == null) {
			return ExecutionResult.fail("feedback.no_agent",
				"[Squire] 你还没有侍从。按 K 查看训练人偶的搭建方法。");
		}
		if (nbt.containsUuid(dev.squire.server.registry.SquireItems.NBT_AGENT)
				&& !record.agentId.equals(nbt.getUuid(
					dev.squire.server.registry.SquireItems.NBT_AGENT))) {
			return ExecutionResult.fail("feedback.wrong_agent",
				"[Squire] 这枚召集铃绑定的是另一名侍从。");
		}
		dev.squire.server.item.BellTier tier =
			dev.squire.server.item.BellTier.byId(record.bellTier);
		dev.squire.server.registry.SquireItems.bind(bell, owner.getUuid(),
			record.agentId, tier);
		syncRecallBellDisplay(bell, tier);
		boolean deathRecall = record.deathPending;
		long now = currentTick();
		if (deathRecall && now < record.reviveAvailableTick) {
			return ExecutionResult.fail("feedback.revive_cooldown",
				"[Squire] 死亡复苏尚未准备好，还需 "
					+ formatBellTime(record.reviveAvailableTick - now) + "。");
		}
		AvatarEntity existing = agents.resolveForOwnerNow(owner.getUuid()).orElse(null);
		if (existing != null && existing.isAlive()
				&& existing.getWorld() == owner.getWorld()
				&& existing.squaredDistanceTo(owner) <= 64.0) {
			((ServerWorld) owner.getWorld()).playSound(null, existing.getBlockPos(),
				net.minecraft.sound.SoundEvents.BLOCK_BELL_USE,
				net.minecraft.sound.SoundCategory.PLAYERS, 0.8f, 1.2f);
			return ExecutionResult.ok("feedback.already_nearby",
				"[Squire] 我就在这里。");
		}
		if (existing != null) {
			ownerOverride(existing);
		}
		AvatarEntity arrived = roster.summonFor(owner);
		if (arrived == null) {
			return ExecutionResult.fail("feedback.recall_failed",
				"[Squire] 这里没有足够的空间让我回来。");
		}
		if (deathRecall) {
			int level = Math.max(0, Math.min(10, record.profile.profession.level));
			double fraction = bellReviveConfig.reviveHealth(tier, level);
			arrived.setHealth(Math.max(1.0f,
				(float) (arrived.getMaxHealth() * fraction)));
			applyReviveBuff(arrived, bellReviveConfig.reviveBuff(tier, level));
			agentStore().completeRevival(record.agentId);
		}
		arrived.setFollowMode(owner.getUuid());
		persistSnapshot(arrived);
		((ServerWorld) owner.getWorld()).spawnParticles(
			net.minecraft.particle.ParticleTypes.HAPPY_VILLAGER,
			arrived.getX(), arrived.getBodyY(0.5), arrived.getZ(), 16,
			0.45, 0.75, 0.45, 0.06);
		((ServerWorld) owner.getWorld()).playSound(null, arrived.getBlockPos(),
			net.minecraft.sound.SoundEvents.BLOCK_BELL_USE,
			net.minecraft.sound.SoundCategory.PLAYERS, 1.0f, 1.0f);
		return ExecutionResult.ok(deathRecall ? "feedback.revived" : "feedback.recalled",
			deathRecall ? "[Squire] 我从铃声中复苏了，但仍需要休整。"
				: "[Squire] 我听见铃声了。");
	}

	/** Server-side commit for the NBT-preserving workbench recipe. */
	public boolean commitBellUpgrade(ServerPlayerEntity crafter,
			net.minecraft.item.ItemStack output) {
		if (crafter == null || output == null
				|| !output.isOf(dev.squire.server.registry.SquireItems.RECALL_BELL)
				|| !output.hasNbt()) return false;
		var nbt = output.getNbt();
		if (!nbt.containsUuid(dev.squire.server.registry.SquireItems.NBT_OWNER)
				|| !nbt.containsUuid(dev.squire.server.registry.SquireItems.NBT_AGENT)
				|| !nbt.contains(dev.squire.server.registry.SquireItems.NBT_UPGRADE_FROM)) {
			return false;
		}
		UUID ownerId = nbt.getUuid(dev.squire.server.registry.SquireItems.NBT_OWNER);
		UUID agentId = nbt.getUuid(dev.squire.server.registry.SquireItems.NBT_AGENT);
		var record = agentStore().recordOfAgent(agentId).orElse(null);
		dev.squire.server.item.BellTier from = dev.squire.server.item.BellTier.byId(
			nbt.getString(dev.squire.server.registry.SquireItems.NBT_UPGRADE_FROM));
		dev.squire.server.item.BellTier to = dev.squire.server.item.BellTier.byId(
			nbt.getString(dev.squire.server.registry.SquireItems.NBT_BELL_TIER));
		boolean valid = crafter.getUuid().equals(ownerId) && record != null
			&& record.ownerId.equals(ownerId) && from.next() == to
			&& agentStore().setBellTier(agentId, from, to);
		dev.squire.server.item.BellTier canonical = record == null
			? dev.squire.server.item.BellTier.COMMON
			: dev.squire.server.item.BellTier.byId(record.bellTier);
		dev.squire.server.registry.SquireItems.bind(output, ownerId, agentId, canonical);
		syncRecallBellDisplay(output, canonical);
		if (valid) {
			feedback(crafter, "[Squire] 召集铃已升级为「"
				+ net.minecraft.text.Text.translatable(to.translationKey()).getString()
				+ "」。品质已绑定到这名侍从。 ");
		} else {
			feedback(crafter, "[Squire] 铃铛升级校验失败，品质没有改变。");
		}
		return valid;
	}

	/** Copies real server balance values for client display; gameplay never reads them. */
	public void syncRecallBellDisplay(net.minecraft.item.ItemStack bell,
			dev.squire.server.item.BellTier tier) {
		dev.squire.server.registry.SquireItems.writeDisplayRule(bell,
			bellReviveConfig.rule(tier));
	}

	private static void applyReviveBuff(AvatarEntity avatar,
			dev.squire.server.item.BellReviveConfig.ReviveBuff buff) {
		if (avatar == null || buff == null || buff.empty()) return;
		for (var spec : buff.effects()) {
			net.minecraft.util.Identifier id = net.minecraft.util.Identifier.tryParse(
				spec.effectId());
			if (id == null || !net.minecraft.registry.Registries.STATUS_EFFECT.containsId(id)) {
				continue;
			}
			avatar.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
				net.minecraft.registry.Registries.STATUS_EFFECT.get(id),
				buff.durationTicks(), spec.amplifier(), false, true, true));
		}
	}

	private static String formatBellTime(long ticks) {
		long totalSeconds = Math.max(0L, (ticks + 19L) / 20L);
		long minutes = totalSeconds / 60L;
		long seconds = totalSeconds % 60L;
		return minutes > 0L ? minutes + "分" + (seconds == 0L ? "" : seconds + "秒")
			: seconds + "秒";
	}

	// ------------------------------------------------------------------ helpers

	private static String dimensionId(AvatarEntity avatar) {
		return avatar.getWorld().getRegistryKey().getValue().toString();
	}

	void feedback(PlayerEntity player, String message) {
		player.sendMessage(Text.literal(message), false);
	}
}
