package dev.squire.server.blueprint;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.squire.server.blueprint.MaterialFamily.SlotType;

/** The five fixed, survival-scale medieval buildings shipped with Squire. */
final class MedievalBlueprints {
	private MedievalBlueprints() { }

	static List<Blueprint> all() {
		return List.of(timberCottage(), stoneLodge(), storageShed(), watchtower(),
			mineOutpost());
	}

	private static Blueprint timberCottage() {
		B b = new B();
		b.m(1,0,1, 7,0,5,"foundation","block","石质地基");
		b.m(2,0,2, 6,0,4,"floor","planks","室内地板");
		b.m(1,1,1, 7,3,1,"wall","planks","北墙");
		b.m(1,1,5, 7,3,5,"wall","planks","南墙");
		b.m(1,1,2, 1,3,4,"wall","planks","西墙");
		b.m(7,1,2, 7,3,4,"wall","planks","东墙");
		b.dig(2,1,2, 6,3,4,"清理室内");
		b.dig(4,1,5, 4,2,5,"门洞");
		posts(b, 1, 7, 1, 5, 1, 3, "frame");
		b.ms(1,3,1,7,3,1,"frame","log","北横梁",false, Map.of("axis","x"));
		b.ms(1,3,5,7,3,5,"frame","log","南横梁",false, Map.of("axis","x"));
		b.ms(1,3,1,1,3,5,"frame","log","西横梁",false, Map.of("axis","z"));
		b.ms(7,3,1,7,3,5,"frame","log","东横梁",false, Map.of("axis","z"));
		b.m(1,2,3,1,2,3,"window","pane","西窗",true);
		b.m(7,2,3,7,2,3,"window","pane","东窗",true);
		b.m(3,2,1,3,2,1,"window","pane","北窗",true);
		b.m(5,2,1,5,2,1,"window","pane","北窗",true);
		gable(b, 9, 7, 3, "wall", "planks", "roof");
		b.ms(3,3,6,5,3,6,"roof","stairs","入口雨棚",true,
			Map.of("facing","south"));
		b.m(3,1,6,3,2,6,"frame","fence","门廊支柱",true);
		b.m(5,1,6,5,2,6,"frame","fence","门廊支柱",true);
		return b.build("shelter_wood", "木构乡野住宅", Blueprint.Category.SHELTER,
			9, 9, 7, List.of(
				slot("foundation","地基",SlotType.FOUNDATION,"squire:cobblestone","block"),
				slot("frame","梁柱",SlotType.FRAME,"squire:spruce","log","fence"),
				slot("wall","墙面",SlotType.WALL,"squire:oak","planks"),
				slot("floor","地板",SlotType.FLOOR,"squire:oak","planks"),
				slot("roof","屋顶",SlotType.ROOF,"squire:spruce","stairs","slab","block"),
				slot("window","窗户",SlotType.WINDOW,"squire:glass","pane")));
	}

	private static Blueprint stoneLodge() {
		B b = new B();
		b.m(1,0,1,7,0,7,"foundation","block","加宽地基");
		b.m(2,0,2,6,0,6,"floor","planks","木地板");
		b.m(1,1,1,7,4,1,"wall","block","北墙");
		b.m(1,1,7,7,4,7,"wall","block","南墙");
		b.m(1,1,2,1,4,6,"wall","block","西墙");
		b.m(7,1,2,7,4,6,"wall","block","东墙");
		b.dig(2,1,2,6,4,6,"清理室内");
		b.dig(4,1,7,4,2,7,"门洞");
		posts(b,1,7,1,7,1,4,"frame");
		b.ms(1,4,1,7,4,1,"frame","log","北檐梁",false,Map.of("axis","x"));
		b.ms(1,4,7,7,4,7,"frame","log","南檐梁",false,Map.of("axis","x"));
		b.m(1,2,3,1,3,3,"window","pane","西窗",true);
		b.m(7,2,5,7,3,5,"window","pane","东窗",true);
		b.m(3,2,1,5,2,1,"window","pane","北窗",true);
		gable(b,9,9,4,"wall","block","roof");
		b.m(6,4,2,6,9,2,"wall","block","烟囱",true);
		return b.build("shelter_stone", "石木山墙住宅", Blueprint.Category.SHELTER,
			9, 10, 9, List.of(
				slot("foundation","地基",SlotType.FOUNDATION,"squire:cobblestone","block"),
				slot("frame","梁柱",SlotType.FRAME,"squire:dark_oak","log"),
				slot("wall","墙体",SlotType.WALL,"squire:stone_bricks","block"),
				slot("floor","地板",SlotType.FLOOR,"squire:spruce","planks"),
				slot("roof","屋顶",SlotType.ROOF,"squire:deepslate_tiles","stairs","slab","block"),
				slot("window","窗户",SlotType.WINDOW,"squire:gray_glass","pane")));
	}

	private static Blueprint storageShed() {
		B b = new B();
		b.m(1,0,1,7,0,5,"foundation","block","仓库地基");
		b.m(2,0,2,6,0,4,"floor","planks","仓库地板");
		b.m(1,1,1,7,4,1,"wall","planks","后墙");
		b.m(1,1,5,7,4,5,"wall","planks","前墙");
		b.m(1,1,2,1,4,4,"wall","planks","侧墙");
		b.m(7,1,2,7,4,4,"wall","planks","侧墙");
		b.dig(2,1,2,6,4,4,"清理仓储空间");
		b.dig(3,1,5,5,3,5,"装卸门洞");
		posts(b,1,7,1,5,1,4,"frame");
		b.ms(1,4,1,7,4,1,"frame","log","后檐梁",false,Map.of("axis","x"));
		b.ms(1,4,5,7,4,5,"frame","log","前檐梁",false,Map.of("axis","x"));
		b.m(1,2,3,1,2,3,"window","pane","侧窗",true);
		b.m(7,2,3,7,2,3,"window","pane","侧窗",true);
		gable(b,9,7,4,"wall","planks","roof");
		b.fixed(2,1,2,2,1,2,"minecraft:barrel","货桶",true);
		b.fixed(6,1,2,6,1,2,"minecraft:chest","储物箱",true);
		return b.build("storage_shed", "梁架仓储屋", Blueprint.Category.STORAGE,
			9, 10, 7, List.of(
				slot("foundation","地基",SlotType.FOUNDATION,"squire:cobblestone","block"),
				slot("frame","梁柱",SlotType.FRAME,"squire:oak","log"),
				slot("wall","墙板",SlotType.WALL,"squire:spruce","planks"),
				slot("floor","地板",SlotType.FLOOR,"squire:oak","planks"),
				slot("roof","屋顶",SlotType.ROOF,"squire:spruce","stairs","slab","block"),
				slot("window","窗户",SlotType.WINDOW,"squire:glass","pane")));
	}

	private static Blueprint watchtower() {
		B b = new B();
		b.m(1,0,1,5,0,5,"foundation","block","塔基");
		b.m(1,1,1,5,7,1,"wall","block","北塔墙");
		b.m(1,1,5,5,7,5,"wall","block","南塔墙");
		b.m(1,1,2,1,7,4,"wall","block","西塔墙");
		b.m(5,1,2,5,7,4,"wall","block","东塔墙");
		b.dig(2,1,2,4,7,4,"清理塔内");
		b.dig(3,1,5,3,2,5,"塔门");
		b.m(3,2,1,3,2,1,"window","pane","观察窗",true);
		b.m(1,5,3,1,5,3,"window","pane","观察窗",true);
		b.m(5,5,3,5,5,3,"window","pane","观察窗",true);
		b.fixedState(3,1,2,3,7,2,"minecraft:ladder","塔内梯",false,
			Map.of("facing","south"));
		b.m(0,8,0,6,8,6,"floor","planks","瞭望平台");
		posts(b,0,6,0,6,9,11,"frame");
		b.m(0,9,0,6,9,0,"frame","fence","北护栏");
		b.m(0,9,6,6,9,6,"frame","fence","南护栏");
		b.m(0,9,1,0,9,5,"frame","fence","西护栏");
		b.m(6,9,1,6,9,5,"frame","fence","东护栏");
		b.ms(0,12,0,6,12,0,"roof","stairs","北屋檐",false,Map.of("facing","north"));
		b.ms(0,12,6,6,12,6,"roof","stairs","南屋檐",false,Map.of("facing","south"));
		b.ms(0,12,1,0,12,5,"roof","stairs","西屋檐",false,Map.of("facing","west"));
		b.ms(6,12,1,6,12,5,"roof","stairs","东屋檐",false,Map.of("facing","east"));
		b.m(1,13,1,5,13,5,"roof","slab","上层塔盖");
		b.m(2,14,2,4,14,4,"roof","slab","塔顶");
		b.m(3,15,3,3,15,3,"frame","fence","旗杆",true);
		return b.build("watchtower", "石基瞭望塔", Blueprint.Category.DEFENCE,
			7, 16, 7, List.of(
				slot("foundation","塔基",SlotType.FOUNDATION,"squire:cobblestone","block"),
				slot("frame","木构件",SlotType.FRAME,"squire:dark_oak","log","fence"),
				slot("wall","塔墙",SlotType.WALL,"squire:stone_bricks","block"),
				slot("floor","平台",SlotType.FLOOR,"squire:spruce","planks"),
				slot("roof","塔顶",SlotType.ROOF,"squire:deepslate_tiles","stairs","slab"),
				slot("window","观察窗",SlotType.WINDOW,"squire:glass","pane")));
	}

	private static Blueprint mineOutpost() {
		B b = new B();
		b.m(1,0,1,9,0,7,"foundation","block","前哨地基");
		b.m(2,0,2,8,0,6,"floor","planks","工作区地板");
		b.m(1,1,1,9,3,1,"wall","block","后墙");
		b.m(1,1,7,9,3,7,"wall","block","前墙");
		b.m(1,1,2,1,3,6,"wall","block","西墙");
		b.m(9,1,2,9,3,6,"wall","block","东墙");
		b.dig(2,1,2,8,3,6,"清理工作区");
		b.dig(4,1,7,6,2,7,"矿站大门");
		posts(b,1,9,1,7,1,3,"frame");
		b.ms(1,3,1,9,3,1,"frame","log","后檐梁",false,Map.of("axis","x"));
		b.ms(1,3,7,9,3,7,"frame","log","前檐梁",false,Map.of("axis","x"));
		b.m(1,2,4,1,2,4,"window","pane","西窗",true);
		b.m(9,2,4,9,2,4,"window","pane","东窗",true);
		gable(b,11,9,3,"wall","block","roof");
		b.dig(3,-8,2,5,0,4,"加固竖井");
		b.fixedState(4,-7,1,4,-1,1,"minecraft:ladder","竖井梯",false,
			Map.of("facing","south"));
		b.fixed(8,1,2,8,1,2,"minecraft:crafting_table","工作台",true);
		b.fixed(8,1,3,8,1,3,"minecraft:barrel","矿料桶",true);
		b.fixed(2,1,6,2,1,6,"minecraft:chest","补给箱",true);
		return b.build("mine_outpost", "加固矿井前哨站", Blueprint.Category.MINE,
			11, 10, 9, List.of(
				slot("foundation","地基",SlotType.FOUNDATION,"squire:cobblestone","block"),
				slot("frame","支撑梁",SlotType.FRAME,"squire:spruce","log"),
				slot("wall","墙体",SlotType.WALL,"squire:stone_bricks","block"),
				slot("floor","地板",SlotType.FLOOR,"squire:spruce","planks"),
				slot("roof","屋顶",SlotType.ROOF,"squire:deepslate_tiles","stairs","slab","block"),
				slot("window","窗户",SlotType.WINDOW,"squire:gray_glass","pane")),
			Set.of(BlueprintRegistry.ABILITY_BUILD,
				BlueprintRegistry.ABILITY_EXCAVATE));
	}

	private static Blueprint.MaterialSlot slot(String id, String name, SlotType type,
			String family, String... variants) {
		return new Blueprint.MaterialSlot(id, name, type, family, Set.of(variants));
	}

	private static void posts(B b, int x1, int x2, int z1, int z2, int y1, int y2,
			String slot) {
		b.ms(x1,y1,z1,x1,y2,z1,slot,"log","角柱",false,Map.of("axis","y"));
		b.ms(x2,y1,z1,x2,y2,z1,slot,"log","角柱",false,Map.of("axis","y"));
		b.ms(x1,y1,z2,x1,y2,z2,slot,"log","角柱",false,Map.of("axis","y"));
		b.ms(x2,y1,z2,x2,y2,z2,slot,"log","角柱",false,Map.of("axis","y"));
	}

	/** Symmetric north-authored gable with one stair row per slope level. */
	private static void gable(B b, int width, int depth, int wallTop, String wallSlot,
			String wallVariant, String roofSlot) {
		int c = width / 2;
		int north = 1;
		int south = depth - 2;
		for (int level = 0; level < c; level++) {
			int from = 1 + level;
			int to = width - 2 - level;
			int y = wallTop + 1 + level;
			if (from <= to) {
				b.m(from,y,north,to,y,north,wallSlot,wallVariant,"北山墙");
				b.m(from,y,south,to,y,south,wallSlot,wallVariant,"南山墙");
			}
			b.ms(level,y,0,level,y,depth-1,roofSlot,"stairs","西坡屋顶",false,
				Map.of("facing","west"));
			b.ms(width-1-level,y,0,width-1-level,y,depth-1,roofSlot,"stairs",
				"东坡屋顶",false,Map.of("facing","east"));
		}
		b.m(c,wallTop+1+c,0,c,wallTop+1+c,depth-1,roofSlot,"slab","屋脊");
	}

	private static final class B {
		private final List<BlueprintStep> steps = new ArrayList<>();
		private int order;
		void m(int x1,int y1,int z1,int x2,int y2,int z2,String slot,String variant,String what) {
			steps.add(BlueprintStep.material(order++,x1,y1,z1,x2,y2,z2,slot,variant,what,false));
		}
		void m(int x1,int y1,int z1,int x2,int y2,int z2,String slot,String variant,String what,boolean optional) {
			steps.add(BlueprintStep.material(order++,x1,y1,z1,x2,y2,z2,slot,variant,what,optional));
		}
		void ms(int x1,int y1,int z1,int x2,int y2,int z2,String slot,String variant,String what,boolean optional,Map<String,String> state) {
			steps.add(BlueprintStep.materialState(order++,x1,y1,z1,x2,y2,z2,slot,variant,what,optional,state));
		}
		void fixed(int x1,int y1,int z1,int x2,int y2,int z2,String block,String what,boolean optional) {
			steps.add(BlueprintStep.place(order++,x1,y1,z1,x2,y2,z2,block,what,optional));
		}
		void fixedState(int x1,int y1,int z1,int x2,int y2,int z2,String block,String what,boolean optional,Map<String,String> state) {
			steps.add(BlueprintStep.placeState(order++,x1,y1,z1,x2,y2,z2,block,what,optional,state));
		}
		void dig(int x1,int y1,int z1,int x2,int y2,int z2,String what) {
			steps.add(BlueprintStep.dig(order++,x1,y1,z1,x2,y2,z2,what));
		}
		Blueprint build(String id,String name,Blueprint.Category category,int width,int height,int depth,List<Blueprint.MaterialSlot> slots) {
			return build(id,name,category,width,height,depth,slots,
				Set.of(BlueprintRegistry.ABILITY_BUILD));
		}
		Blueprint build(String id,String name,Blueprint.Category category,int width,int height,int depth,List<Blueprint.MaterialSlot> slots,Set<String> abilities) {
			int tier = category == Blueprint.Category.DEFENCE
				|| category == Blueprint.Category.MINE ? 2 : 1;
			return new Blueprint(id,name,tier,category,width,height,depth,steps,
				abilities,slots);
		}
	}
}
