package dev.squire.server.provider;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.squire.common.protocol.ToolDescriptor;

/**
 * 蓝图参数类请求的话题命中（§23.1 第 5 条）。
 *
 * <h2>为什么单独验这一组句子</h2>
 * <p>{@link ToolRelevance} 一个话题都命不中时会<b>退回全量目录</b>——那条兜底本身是
 * 对的（宁可多发也不要让他突然不会做事），但「旋转一下」「改成两层」这类句子里
 * 一个建筑词都没有，于是每一句都在走兜底。结果是 §23 刚省下的上下文当场又还回去，
 * 而且模型收到一堆和这句话无关的说明。</p>
 *
 * <p>所以这里逐句断言两件事：<b>命中了</b>（不是退回全量），而且<b>命中的是对的那些</b>。</p>
 */
class BlueprintRelevanceTest {

	/** 一份足够大的目录：必须超过 MIN_CATALOG_TO_TRIM，否则裁剪根本不启动。 */
	private static List<ToolDescriptor> catalog() {
		List<ToolDescriptor> out = new ArrayList<>();
		for (String name : List.of(
				"query.status", "query.player", "query.inventory", "query.equipment",
				"navigation.move_to", "navigation.come_to_owner",
				"inventory.give", "inventory.equip", "inventory.unequip",
				"memory.set_home", "memory.recall_location", "memory.list_locations",
				"container.inspect", "crafting.recipe_of", "entity.scan_nearby",
				"world.locate_biome", "world.locate_structure", "world.set_time",
				"world.set_weather", "minecraft.command.effect",
				"minecraft.command.give", "minecraft.command.teleport",
				"guard.start", "guard.stop", "combat.attack_target",
				"combat.supplies", "combat.set_stance", "aid.owner", "heal.now",
				"project.start", "blueprint.place", "blueprint.build",
				"blueprint.design", "blueprint.set_size", "blueprint.rotate",
				"blueprint.set_material_region", "blueprint.set_variant",
				"blueprint.set_floors", "blueprint.mirror", "blueprint.add_module",
				"blueprint.save_preset", "blueprint.load_preset")) {
			out.add(new ToolDescriptor(name, "description of " + name, List.of()));
		}
		return out;
	}

	private static List<String> pick(String message) {
		return ToolRelevance.select(message, catalog(), false).stream()
			.map(ToolDescriptor::name).toList();
	}

	/** 这句话有没有把裁剪跑起来（而不是退回全量）。 */
	private static void assertTrimmed(String message, List<String> picked) {
		assertTrue(picked.size() < catalog().size(),
			"「" + message + "」没有命中任何话题，退回了全量目录（"
				+ picked.size() + " / " + catalog().size() + "）");
	}

	@Test
	@DisplayName("「旋转一下」命中蓝图参数，而不是退回全量")
	void rotate() {
		for (String message : List.of("旋转一下", "转一下朝向", "转个 90度",
				"rotate it 90 degrees", "change the facing")) {
			List<String> picked = pick(message);
			assertTrimmed(message, picked);
			assertTrue(picked.contains("blueprint.rotate"), message);
		}
	}

	@Test
	@DisplayName("「镜像一下」")
	void mirror() {
		for (String message : List.of("镜像一下", "把它翻转过来", "mirror it", "flip it")) {
			List<String> picked = pick(message);
			assertTrimmed(message, picked);
			assertTrue(picked.contains("blueprint.mirror"), message);
		}
	}

	@Test
	@DisplayName("「改成两层」")
	void floors() {
		for (String message : List.of("改成两层", "我想要三层的", "make it two floors",
				"add another storey")) {
			List<String> picked = pick(message);
			assertTrimmed(message, picked);
			assertTrue(picked.contains("blueprint.set_floors"), message);
		}
	}

	@Test
	@DisplayName("「加一个塔楼」")
	void modules() {
		for (String message : List.of("加一个塔楼", "来个门廊", "加个烟囱",
				"add a tower", "put a porch on it")) {
			List<String> picked = pick(message);
			assertTrimmed(message, picked);
			assertTrue(picked.contains("blueprint.add_module"), message);
		}
	}

	@Test
	@DisplayName("材料、尺寸、预设也各自命中")
	void materialsSizesPresets() {
		for (String message : List.of("屋顶换成云杉", "墙用石砖", "大一点",
				"缩小一点", "存成预设", "再建一个之前那个")) {
			List<String> picked = pick(message);
			assertTrimmed(message, picked);
			assertTrue(picked.contains("blueprint.set_variant")
					|| picked.contains("blueprint.set_size")
					|| picked.contains("blueprint.set_material_region")
					|| picked.contains("blueprint.save_preset")
					|| picked.contains("blueprint.load_preset"),
				message + " → " + picked);
		}
	}

	@Test
	@DisplayName("蓝图话题不会把战斗工具一起拖进来")
	void blueprintTalkDoesNotDragInCombat() {
		List<String> picked = pick("旋转一下");
		assertFalse(picked.contains("guard.start"),
			"一句「旋转一下」不该带上护卫工具");
		assertFalse(picked.contains("combat.attack_target"));
	}

	@Test
	@DisplayName("原有话题不受影响：跟随、攻击、盖房子照旧命中")
	void existingTopicsStillWork() {
		assertTrue(pick("跟着我").contains("navigation.come_to_owner"));
		assertTrue(pick("攻击那只僵尸").contains("combat.attack_target"));
		assertTrue(pick("建一个房子").contains("blueprint.place"));
	}

	/** 各典型句子实际会发出去多少个工具。断言只有一条，其余打印供人看。 */
	@Test
	@DisplayName("打印典型请求的工具数")
	void reportPerMessageCounts() {
		StringBuilder report = new StringBuilder("\n[§23.1] ToolRelevance 命中数\n");
		report.append(String.format("  %-16s %5s%n", "message", "tools"));
		for (String message : List.of("跟着我", "攻击那只僵尸", "建一个房子",
				"旋转一下", "改成两层", "加一个塔楼")) {
			report.append(String.format("  %-16s %5d%n", message,
				pick(message).size()));
		}
		report.append(String.format("  %-16s %5d%n", "(全量)", catalog().size()));
		System.out.println(report);
		assertTrimmed("旋转一下", pick("旋转一下"));
	}
}
