package dev.squire.server.blueprint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import dev.squire.server.build.HouseTemplate;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * 所有可用蓝图。内置五份 + 数据包 {@code data/squire/blueprints/*.json}。
 *
 * <p>数据包里的同名 id <b>覆盖</b>内置——整合包作者要改一栋房子的形状，不该被迫
 * 换个名字，否则玩家的已有工程会指向一个不存在的 id。坏文件只跳过它自己并留下日志，
 * 绝不让一份手写 JSON 打掉整个注册表。</p>
 *
 * <p>{@code HouseTemplate} 没有被删掉，而是作为其中两份蓝图的<b>构造器</b>：
 * 在原点按正北编译一次，得到的就是局部坐标步骤。这样既没有把形状逻辑抄第二份，
 * 原有的 {@code HouseTemplateTest} 也一行不用改。</p>
 */
public final class BlueprintRegistry {

	private static final org.slf4j.Logger LOG =
		org.slf4j.LoggerFactory.getLogger(BlueprintRegistry.class);

	/** 数据包里蓝图 JSON 的位置。 */
	public static final String RESOURCE_DIR = "blueprints";

	private final Map<String, Blueprint> byId = new LinkedHashMap<>();
	private final MaterialFamilyRegistry materials = new MaterialFamilyRegistry();

	public BlueprintRegistry() {
		for (Blueprint blueprint : builtins()) {
			byId.put(blueprint.id(), blueprint);
		}
	}

	/**
	 * 登记（或覆盖）一份蓝图。数据包加载走的是同一扇门，
	 * GameTest 也用它塞一份小得能放进 9×4×9 测试场地的形状。
	 */
	public synchronized void register(Blueprint blueprint) {
		byId.put(blueprint.id(), blueprint);
	}

	/**
	 * 按 id 取一份蓝图。
	 *
	 * <p>先查登记表（内置 + 数据包），查不到再交给
	 * {@link ProjectBlueprintFactory}——工程师的参数化蓝图 id 里编着一整份规格，
	 * 数量是组合爆炸的，不可能预先全部登记。这一步让摆放只存一个字符串就能跨重启
	 * 还原出完全相同的形状。</p>
	 */
	public Optional<Blueprint> byId(String id) {
		String normalized = normalize(id);
		Blueprint registered = byId.get(normalized);
		if (registered != null) {
			return Optional.of(registered);
		}
		return ProjectBlueprintFactory.byId(normalized);
	}

	public MaterialFamilyRegistry materials() {
		return materials;
	}

	public List<Blueprint> all() {
		return List.copyOf(byId.values());
	}

	public int size() {
		return byId.size();
	}

	/** 命令补全和「有哪些蓝图」列表用。 */
	public List<String> ids() {
		return List.copyOf(byId.keySet());
	}

	private static String normalize(String id) {
		return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
	}

	// ------------------------------------------------------------------ 数据包

	/**
	 * 从服务端资源管理器重新加载数据包蓝图。内置的先复位，再让数据包覆盖，
	 * 所以删掉一个 JSON 之后再 reload 会干净地回到内置版本。
	 *
	 * @return 人话形式的加载结果，直接回显给管理员
	 */
	public synchronized String reload(ResourceManager resources) {
		String materialSummary = materials.reload(resources);
		byId.clear();
		for (Blueprint blueprint : builtins()) {
			byId.put(blueprint.id(), blueprint);
		}
		if (resources == null) {
			return "\u5185\u7f6e " + byId.size() + " \u4efd\uff08\u6ca1\u6709\u53ef\u8bfb\u7684\u6570\u636e\u5305\uff09";
		}
		int loaded = 0;
		int failed = 0;
		Map<Identifier, net.minecraft.resource.Resource> found =
			resources.findResources(RESOURCE_DIR, path -> path.getPath().endsWith(".json"));
		for (Map.Entry<Identifier, net.minecraft.resource.Resource> entry : found.entrySet()) {
			String path = entry.getKey().getPath();
			String id = normalize(path.substring(path.lastIndexOf('/') + 1,
				path.length() - ".json".length()));
			try (var reader = entry.getValue().getReader()) {
				StringBuilder text = new StringBuilder();
				char[] buffer = new char[4096];
				int read;
				while ((read = reader.read(buffer)) > 0) {
					text.append(buffer, 0, read);
				}
				byId.put(id, BlueprintCodec.parse(id, text.toString()));
				loaded++;
			} catch (Exception bad) {
				failed++;
				LOG.warn("[blueprint] skipped {}: {}", entry.getKey(), bad.toString());
			}
		}
		return "\u5171 " + byId.size() + " \u4efd\uff08\u6570\u636e\u5305 " + loaded
			+ " \u4efd\uff0c\u8df3\u8fc7 " + failed + " \u4efd\uff09\uff1b\u6750\u6599\u5bb6\u65cf "
			+ materialSummary;
	}

	// ------------------------------------------------------------------ 内置

	static List<Blueprint> builtins() {
		return MedievalBlueprints.all();
	}

	/**
	 * 把内置房屋模板转成蓝图：在原点按正北编译一次，绝对坐标即局部坐标。
	 * 模板里的 {@code minecraft:air} 步骤天然就是挖除步骤。
	 */
	private static Blueprint fromHouseTemplate(String id, String displayName,
			HouseTemplate.Style style, int width, int depth, int wallHeight) {
		HouseTemplate.Plan plan = HouseTemplate.compile(style, BlockPos.ORIGIN,
			width, depth, wallHeight, Direction.NORTH);
		List<BlueprintStep> steps = new ArrayList<>();
		int order = 0;
		for (HouseTemplate.Step step : plan.steps()) {
			var min = step.region().min();
			var max = step.region().max();
			boolean dig = BlueprintStep.AIR.equals(step.blockId());
			// 窗是唯一「缺料就先不装」的部分：少两块玻璃不该让整栋房子停在半截。
			boolean optional = !dig && step.what().contains("\u7a97");
			steps.add(dig
				? BlueprintStep.dig(order++, min.getX(), min.getY(), min.getZ(),
					max.getX(), max.getY(), max.getZ(), step.what())
				: BlueprintStep.place(order++, min.getX(), min.getY(), min.getZ(),
					max.getX(), max.getY(), max.getZ(), step.blockId(), step.what(),
					optional));
		}
		// 模板的屋顶在 y = wallHeight，所以总高比墙高多一层。
		return new Blueprint(id, displayName, 1, Blueprint.Category.SHELTER,
			plan.width(), wallHeight + 1, plan.depth(), steps,
			Set.of(ABILITY_BUILD));
	}

	/** 建造这份蓝图需要的能力 id（第 2 期的能力槽会真正校验）。 */
	public static final String ABILITY_BUILD = "build.blueprint";
	/** 挖除负空间需要的能力 id。 */
	public static final String ABILITY_EXCAVATE = "build.excavate";

	private static Blueprint storageShed() {
		List<BlueprintStep> steps = List.of(
			BlueprintStep.place(0, 0, 0, 0, 4, 3, 4, "minecraft:oak_planks",
				"\u5899\u4f53\u4e0e\u5730\u677f", false),
			BlueprintStep.place(1, 0, 4, 0, 4, 4, 4, "minecraft:cobblestone",
				"\u5c4b\u9876", false),
			BlueprintStep.dig(2, 1, 1, 1, 3, 3, 3, "\u638f\u7a7a\u5185\u90e8"),
			BlueprintStep.dig(3, 2, 1, 4, 2, 2, 4, "\u95e8\u6d1e"),
			BlueprintStep.place(4, 1, 1, 1, 1, 1, 1, "minecraft:chest",
				"\u50a8\u7269\u7bb1", false),
			BlueprintStep.place(5, 3, 1, 1, 3, 1, 1, "minecraft:chest",
				"\u7b2c\u4e8c\u4e2a\u7bb1\u5b50", true));
		return new Blueprint("storage_shed", "\u4ed3\u5e93\u5c0f\u5c4b", 1,
			Blueprint.Category.STORAGE, 5, 5, 5, steps, Set.of(ABILITY_BUILD));
	}

	private static Blueprint watchtower() {
		List<BlueprintStep> steps = List.of(
			BlueprintStep.place(0, 0, 0, 0, 4, 8, 4, "minecraft:cobblestone",
				"\u5854\u8eab", false),
			BlueprintStep.dig(1, 1, 1, 1, 3, 7, 3, "\u638f\u7a7a\u5854\u8eab"),
			BlueprintStep.dig(2, 2, 1, 4, 2, 2, 4, "\u95e8\u6d1e"),
			BlueprintStep.place(3, 0, 9, 0, 4, 9, 0, "minecraft:cobblestone_wall",
				"\u57db\u53e3\uff08\u5317\uff09", true),
			BlueprintStep.place(4, 0, 9, 4, 4, 9, 4, "minecraft:cobblestone_wall",
				"\u57db\u53e3\uff08\u5357\uff09", true),
			BlueprintStep.place(5, 0, 9, 0, 0, 9, 4, "minecraft:cobblestone_wall",
				"\u57db\u53e3\uff08\u897f\uff09", true),
			BlueprintStep.place(6, 4, 9, 0, 4, 9, 4, "minecraft:cobblestone_wall",
				"\u57db\u53e3\uff08\u4e1c\uff09", true));
		return new Blueprint("watchtower", "\u54e8\u5854", 2,
			Blueprint.Category.DEFENCE, 5, 10, 5, steps, Set.of(ABILITY_BUILD));
	}

	/**
	 * 矿井前哨站：地面一间石屋，屋里一条 3×3 竖井打穿地板往下七格。
	 * 竖井步骤的 y 是负的，所以这份蓝图的包围盒必须按步骤并集算——
	 * 见 {@link Blueprint#bounds}。
	 */
	private static Blueprint mineOutpost() {
		List<BlueprintStep> steps = List.of(
			BlueprintStep.place(0, 0, 0, 0, 6, 3, 6, "minecraft:cobblestone",
				"\u7ad9\u4f53\u4e0e\u5730\u677f", false),
			BlueprintStep.place(1, 0, 4, 0, 6, 4, 6, "minecraft:oak_planks",
				"\u5c4b\u9876", false),
			BlueprintStep.dig(2, 1, 1, 1, 5, 3, 5, "\u638f\u7a7a\u5185\u90e8"),
			BlueprintStep.dig(3, 3, 1, 6, 3, 2, 6, "\u95e8\u6d1e"),
			BlueprintStep.dig(4, 2, -6, 2, 4, 0, 4, "\u7ad6\u4e95"),
			BlueprintStep.place(5, 1, 1, 1, 1, 1, 1, "minecraft:chest",
				"\u50a8\u7269\u7bb1", false),
			BlueprintStep.place(6, 5, 1, 1, 5, 1, 1, "minecraft:crafting_table",
				"\u5de5\u4f5c\u53f0", true));
		return new Blueprint("mine_outpost", "\u77ff\u4e95\u524d\u54e8\u7ad9", 2,
			Blueprint.Category.MINE, 7, 5, 7, steps,
			Set.of(ABILITY_BUILD, ABILITY_EXCAVATE));
	}
}
