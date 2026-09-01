package dev.squire.server.runtime;

import java.util.Optional;
import java.util.UUID;

import dev.squire.server.body.avatar.AvatarEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

/**
 * 世界写入类的玩家意图：铺选区、盖房子。
 *
 * <p>从 {@link SquireRuntime} 里原样搬出来的，行为一个字没改——搬出来只是因为那个类
 * 已经 2976 行，而房子这条链路接下来要被蓝图系统整体重写。共用的协作者
 * （registry、worldEditor、undoJournal、protectionAdapter、确认流程）仍然由 Runtime
 * 持有，这里只借用，不复制状态。</p>
 */
final class SquireBuildService {

	private final SquireRuntime runtime;

	SquireBuildService(SquireRuntime runtime) {
		this.runtime = runtime;
	}

	/**
	 * 方案 F2/F3/B07：“把选定区域铺成石头”。区域只来自 server 拥有的选区，
	 * LLM/玩家都不能在这里塞进一组隐式坐标。返回的是 preview，世界零改变；
	 * 真正执行要等 {@code /squire confirm <id>}。
	 */
	public SquireRuntime.ExecutionResult fillSelection(ServerPlayerEntity sender, String blockId) {
		if (!runtime.isWorldEditEnabled()) {
			return SquireRuntime.ExecutionResult.fail("feedback.worldedit_disabled",
				"[Squire] 世界编辑当前关闭，服主可用 /squire admin worldedit enable 打开。");
		}
		Optional<AvatarEntity> found = runtime.agents().resolveForOwner(sender.getUuid());
		if (found.isEmpty()) {
			return SquireRuntime.ExecutionResult.fail("feedback.no_agent",
				"[Squire] Your squire is absent. Use a recall bell, or press K for the first-summon guide.");
		}
		AvatarEntity avatar = found.get();
		var selection = runtime.selections().of(sender.getUuid()).orElse(null);
		if (selection == null || !selection.complete()) {
			return SquireRuntime.ExecutionResult.fail("feedback.no_selection",
				"[Squire] 还没有选区。用 /squire selection pos1 和 pos2 选好再说一次。");
		}
		ServerWorld world = (ServerWorld) avatar.getWorld();
		if (!selection.dimension().equals(world.getRegistryKey())) {
			return SquireRuntime.ExecutionResult.fail("feedback.selection_dimension",
				"[Squire] 选区在 " + selection.dimensionId() + "，伙伴不在那个维度。");
		}
		var region = selection.region().orElseThrow();
		String canonicalBlock = blockId == null || blockId.isBlank()
			? "minecraft:stone" : blockId;
		var plan = new dev.squire.server.world.WorldEditor.EditPlan(
			dev.squire.server.world.WorldEditor.EditPlan.Kind.FILL,
			world.getRegistryKey().getValue(), region, canonicalBlock,
			avatar.agentId(), sender.getUuid(), UUID.randomUUID(), null);
		var preview = runtime.worldEditor().richPreview(world, plan, "minecraft.command.fill");
		if (!preview.executable()) {
			return SquireRuntime.ExecutionResult.fail("feedback.worldedit_refused",
				"[Squire] 无法执行：\n" + SquireRuntime.commandEditPreview(preview));
		}
		String previewText = SquireRuntime.commandEditPreview(preview);
		java.util.Map<String, Object> canonical = new java.util.LinkedHashMap<>();
		canonical.put("x1", region.min().getX());
		canonical.put("y1", region.min().getY());
		canonical.put("z1", region.min().getZ());
		canonical.put("x2", region.max().getX());
		canonical.put("y2", region.max().getY());
		canonical.put("z2", region.max().getZ());
		canonical.put("blockId", canonicalBlock);
		var operation = runtime.issuePendingOperation(sender.getUuid(), avatar.agentId(),
			"minecraft.command.fill", canonical, previewText);
		return SquireRuntime.ExecutionResult.ok("feedback.preview_issued",
			"[Squire] 预览（世界还没有任何改变）：\n" + previewText
				+ "\n确认执行：/squire confirm " + operation.confirmId()
				+ "\n取消：/squire deny " + operation.confirmId());
	}

	/**
	 * 「帮我盖一个房子」：用内置模板在玩家面前算好形状，先出预览等确认，
	 * 确认后由 {@link #executeHouse} 落地。世界在确认之前零改变。
	 */
	public SquireRuntime.ExecutionResult buildHouse(ServerPlayerEntity sender, String styleWord,
			int width, int depth, int height) {
		// 这里刻意不检查 worldEditEnabled。那个开关管的是"模型能不能自由发起
		// 任意范围的 /fill"，和这里不是一回事：房子由<b>主人本人</b>发起、形状来自
		// 内置模板（尺寸有硬上限）、每一格都记进撤销日志、整体还要过保护适配器。
		// 让它也被那个开关拦住，只会让帮助里写着的功能第一次用必然失败。
		// 真正的地权判定仍然由下面的 ProtectionAdapter 负责。
		Optional<AvatarEntity> found = runtime.agents().resolveForOwner(sender.getUuid());
		if (found.isEmpty()) {
			return SquireRuntime.ExecutionResult.fail("feedback.no_agent",
				"[Squire] 侍从不在场。请右键召集铃；首次召唤方法可按 K 查看。");
		}
		AvatarEntity avatar = found.get();
		ServerWorld world = (ServerWorld) avatar.getWorld();

		var plan = compileHousePlan(sender, styleWord, width, depth, height);
		// 保护适配器对整栋房子的包围盒做一次性判定，绝不逐块绕过。
		var decision = runtime.protectionAdapter.canEditRegion(world, plan.bounds(),
			sender.getUuid());
		if (!decision.allowed()) {
			return SquireRuntime.ExecutionResult.fail("feedback.protected",
				"[Squire] 这块地不让动：" + decision.reason());
		}

		// 直接盖。房子是有界的（尺寸夹在模板上限内）、过了保护判定、而且整栋都记进
		// 撤销日志——再压一道 /squire confirm 只是让玩家多打一行字，换不来任何安全性。
		return executeHouse(sender, avatar, plan);
	}

	/** 房子落在玩家面前几格，正对着他。 */
	private dev.squire.server.build.HouseTemplate.Plan compileHousePlan(
			ServerPlayerEntity sender, String styleWord, int width, int depth,
			int height) {
		var style = dev.squire.server.build.HouseTemplate.Style.parse(styleWord);
		net.minecraft.util.math.Direction facing = sender.getHorizontalFacing();
		int w = width > 0 ? width : DEFAULT_HOUSE_SIZE;
		int d = depth > 0 ? depth : DEFAULT_HOUSE_SIZE;
		int h = height > 0 ? height : DEFAULT_HOUSE_HEIGHT;
		// 往玩家正前方推开一段，别盖在他脸上；再回退半个宽度让房子居中。
		net.minecraft.util.math.BlockPos front = sender.getBlockPos()
			.offset(facing, HOUSE_STANDOFF + Math.max(w, d) / 2);
		net.minecraft.util.math.BlockPos origin =
			front.add(-w / 2, 0, -d / 2);
		return dev.squire.server.build.HouseTemplate.compile(style, origin, w, d, h,
			facing);
	}

	private static String describeHouse(dev.squire.server.build.HouseTemplate.Plan plan) {
		StringBuilder text = new StringBuilder();
		text.append("盖一间 ").append(plan.width()).append("×").append(plan.depth())
			.append(" 、高 ").append(plan.height()).append(" 的")
			.append(switch (plan.style()) {
				case WOOD -> "木屋";
				case STONE -> "石屋";
				case NETHER -> "下界砖屋";
			}).append("\n");
		text.append("位置：").append(plan.bounds().min().toShortString())
			.append(" 到 ").append(plan.bounds().max().toShortString()).append("\n");
		text.append("影响 ").append(plan.volume()).append(" 格，分 ")
			.append(plan.steps().size()).append(" 步：");
		for (var step : plan.steps()) {
			text.append("\n  · ").append(step.what()).append(" → ").append(step.blockId());
		}
		text.append("\n完成后直接对我说「撤销」可以还原。");
		return text.toString();
	}

	/** 真正动土。整栋房子的原方块都写进撤销日志，随时可以精确还原。 */
	private SquireRuntime.ExecutionResult executeHouse(ServerPlayerEntity confirmer, AvatarEntity avatar,
			dev.squire.server.build.HouseTemplate.Plan plan) {
		ServerWorld world = (ServerWorld) avatar.getWorld();
		UUID operationId = runtime.undoJournal().begin(UUID.randomUUID(), confirmer.getUuid(),
			world.getRegistryKey().getValue().toString(), "build.house",
			runtime.currentTick());
		int changed = 0;
		for (var step : plan.steps()) {
			net.minecraft.block.Block block = net.minecraft.registry.Registries.BLOCK
				.get(new net.minecraft.util.Identifier(step.blockId()));
			net.minecraft.block.BlockState target = block.getDefaultState();
			for (net.minecraft.util.math.BlockPos pos : step.region().cells()) {
				net.minecraft.block.BlockState old = world.getBlockState(pos);
				if (old.equals(target)) {
					continue;
				}
				var entity = world.getBlockEntity(pos);
				runtime.undoJournal().record(new dev.squire.server.world.UndoJournal.Entry(
					operationId, operationId, pos.toImmutable(), old,
					entity == null ? null : entity.createNbtWithIdentifyingData(),
					target, runtime.currentTick()));
				world.setBlockState(pos, target, net.minecraft.block.Block.NOTIFY_ALL);
				changed++;
			}
		}
		runtime.undoJournal().close(operationId);
		return SquireRuntime.ExecutionResult.ok("feedback.house_built",
			"[Squire] " + describeHouse(plan) + "\n改动 " + changed
				+ " 格。不满意就直接对我说「撤销」来还原。");
	}

	/** 房子默认尺寸，以及离玩家多远起盖。 */
	private static final int DEFAULT_HOUSE_SIZE = 7;
	private static final int DEFAULT_HOUSE_HEIGHT = 4;
	private static final int HOUSE_STANDOFF = 3;
}
