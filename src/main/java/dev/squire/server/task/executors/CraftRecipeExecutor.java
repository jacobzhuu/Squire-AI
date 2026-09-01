package dev.squire.server.task.executors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.task.Task;
import net.minecraft.block.Block;
import net.minecraft.inventory.CraftingInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.ShapedRecipe;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;

/**
 * RecipeManager-driven crafting (spec section 36 MUST): recipes are RESOLVED from
 * the server's recipe book, materials CHECKED against the real inventory, and each
 * successful {@code craft()} is followed by consuming the grid inputs from the
 * avatar inventory. The goal condition re-checks the produced item count.
 */
public final class CraftRecipeExecutor implements dev.squire.server.task.TaskExecutor {

	public static final String TYPE = "craft.recipe";
	public static final String PARAM_ITEM_ID = "itemId";
	public static final String PARAM_COUNT = "count";

	private final RuntimeServices services;

	public CraftRecipeExecutor(RuntimeServices services) {
		this.services = services;
	}

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public boolean requiresBody() {
		return true;
	}

	static Identifier parseId(String raw) {
		int colon = raw.indexOf(':');
		return colon < 0 ? new Identifier(raw) : new Identifier(raw.substring(0, colon),
			raw.substring(colon + 1));
	}

	@Override
	public void start(Task task) {
		if (task.stringParam(PARAM_ITEM_ID) == null) {
			task.setLastErrorCode("INVALID_ARGUMENT");
			throw new IllegalStateException("craft needs itemId");
		}
	}

	@Override
	public StepOutcome tick(Task task, long tick) {
		AvatarEntity avatar = services.avatar(task.agentId());
		if (avatar == null || !avatar.isAlive()) {
			task.setLastErrorCode("ENTITY_NOT_FOUND");
			return StepOutcome.FAILED;
		}
		ServerWorld world = (ServerWorld) avatar.getWorld();
		Item target = Registries.ITEM.get(parseId(task.stringParam(PARAM_ITEM_ID)));
		if (target == Items.AIR) {
			task.setLastErrorCode("ITEM_NOT_FOUND");
			return StepOutcome.FAILED;
		}
		Integer absoluteTarget = task.intParam("targetCount");
		int wanted = absoluteTarget != null ? absoluteTarget
			: task.intParam(PARAM_COUNT) == null ? 1 : task.intParam(PARAM_COUNT);

		List<CraftingRecipe> candidates = findRecipes(world, target);
		if (candidates.isEmpty()) {
			task.setLastErrorCode("NO_RECIPE");
			return StepOutcome.FAILED;
		}
		// 方案 C4：2x2 配方随身可做；需要 3x3 的必须站在真实工作台旁边
		boolean tableNearby = craftingTableNearby(world, avatar);
		if (!tableNearby) {
			List<CraftingRecipe> handheld = candidates.stream()
				.filter(recipe -> recipe.fits(2, 2)).toList();
			if (handheld.isEmpty()) {
				task.setLastErrorCode("NO_WORKSTATION");
				return StepOutcome.FAILED;
			}
			candidates = handheld;
		}

		while (avatar.inventory().countOf(task.stringParam(PARAM_ITEM_ID)) < wanted) {
			Crafted craft = craftOnce(avatar, world, candidates);
			if (craft == Crafted.NONE) {
				task.setLastErrorCode("INSUFFICIENT_ITEM");
				return StepOutcome.FAILED;
			}
			if (craft == Crafted.ERR) {
				task.setLastErrorCode("INTERNAL_ERROR");
				return StepOutcome.FAILED;
			}
		}
		return StepOutcome.WORK_DONE;
	}

	private enum Crafted { DONE, NONE, ERR }

	/**
	 * Headless grid holder: {@code CraftingInventory.setStack} notifies its handler,
	 * so a bare {@code null} handler NPEs (root-caused via gametest stack trace).
	 * A shared no-op handler keeps recipe matching/crafting server-side without a UI.
	 */
	private static final ScreenHandler NOOP_HANDLER = new ScreenHandler(null, 0) {
		@Override
		public ItemStack quickMove(net.minecraft.entity.player.PlayerEntity player, int slot) {
			return ItemStack.EMPTY;
		}

		@Override
		public boolean canUse(net.minecraft.entity.player.PlayerEntity player) {
			return true;
		}
	};

	/** Player-equivalent reach to a crafting table (vanilla ~5 blocks, squared). */
	private static final double TABLE_REACH_SQ = 25.0;
	private static final int TABLE_SEARCH_RADIUS = 5;

	/**
	 * A real crafting table within reach (方案 C4). Never places one — 3x3 recipes
	 * simply stay unavailable until an existing table is nearby.
	 */
	static boolean craftingTableNearby(ServerWorld world,
			dev.squire.server.body.avatar.AvatarEntity avatar) {
		net.minecraft.util.math.BlockPos center = avatar.getBlockPos();
		for (net.minecraft.util.math.BlockPos pos : net.minecraft.util.math.BlockPos.iterate(
				center.add(-TABLE_SEARCH_RADIUS, -TABLE_SEARCH_RADIUS, -TABLE_SEARCH_RADIUS),
				center.add(TABLE_SEARCH_RADIUS, TABLE_SEARCH_RADIUS, TABLE_SEARCH_RADIUS))) {
			if (world.getBlockState(pos).isOf(net.minecraft.block.Blocks.CRAFTING_TABLE)
					&& avatar.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5,
						pos.getZ() + 0.5) <= TABLE_REACH_SQ) {
				return true;
			}
		}
		return false;
	}

	private static List<CraftingRecipe> findRecipes(ServerWorld world, Item target) {
		List<CraftingRecipe> out = new ArrayList<>();
		for (Recipe<?> recipe : world.getServer().getRecipeManager().values()) {
			if (recipe.getType() == RecipeType.CRAFTING
					&& recipe.getOutput(world.getRegistryManager()).getItem() == target) {
				out.add((CraftingRecipe) recipe);
			}
		}
		return out;
	}

	/**
	 * One full craft cycle: arrange a grid from owned items → verify with
	 * {@code matches} → produce via {@code craft} → consume inputs → collect output.
	 */
	private Crafted craftOnce(AvatarEntity avatar, ServerWorld world,
			List<CraftingRecipe> candidates) {
		List<String> availableIds = avatar.inventory().distinctItemIds();
		for (CraftingRecipe recipe : candidates) {
			for (int[] offset : arrangements(recipe)) {
				int[] cellToIngredient = cellToIngredient(recipe, offset);
				// choose a concrete owned item per used cell
				Item[] cellItems = new Item[9];
				boolean satisfiable = true;
				var ingredients = recipe.getIngredients();
				for (int cell = 0; cell < 9 && satisfiable; cell++) {
					if (cellToIngredient[cell] < 0) {
						continue;
					}
					Ingredient ingredient = ingredients.size() > cellToIngredient[cell]
						? ingredients.get(cellToIngredient[cell])
						: Ingredient.EMPTY;
					Item owned = pickOwned(ingredient, availableIds);
					if (owned == null) {
						satisfiable = false;
					} else {
						cellItems[cell] = owned;
					}
				}
				if (!satisfiable) {
					continue;
				}
				CraftingInventory grid = new CraftingInventory(NOOP_HANDLER, 3, 3);
				for (int cell = 0; cell < 9; cell++) {
					grid.setStack(cell, cellItems[cell] == null ? ItemStack.EMPTY
						: new ItemStack(cellItems[cell]));
				}
				if (!recipe.matches(grid, world)) {
					continue;
				}
				ItemStack output = recipe.craft(grid, world.getRegistryManager());
				if (output.isEmpty()) {
					return Crafted.ERR;
				}
				// consume exactly what the verified grid used, then collect outputs
				for (int cell = 0; cell < 9; cell++) {
					if (cellItems[cell] != null) {
						avatar.extractItems(Registries.ITEM.getId(cellItems[cell]), 1);
					}
				}
				ItemStack leftover = avatar.insertStack(output.copy());
				for (ItemStack remainder : recipe.getRemainder(grid)) {
					if (!remainder.isEmpty()) {
						leftover = avatar.insertStack(remainder.copy());
					}
				}
				if (!leftover.isEmpty()) {
					Block.dropStack(world, avatar.getBlockPos(), leftover); // never lose items
				}
				return Crafted.DONE;
			}
		}
		return Crafted.NONE;
	}

	/** Grid placements worth trying: shapeless = flat, shaped = every offset. */
	static List<int[]> arrangements(CraftingRecipe recipe) {
		List<int[]> out = new ArrayList<>();
		if (recipe instanceof ShapedRecipe shaped) {
			int w = shaped.getWidth();
			int h = shaped.getHeight();
			for (int dy = 0; dy <= 3 - h; dy++) {
				for (int dx = 0; dx <= 3 - w; dx++) {
					out.add(new int[] {dx, dy});
				}
			}
		} else {
			out.add(new int[] {0, 0});
		}
		return out;
	}

	/** Maps each 3x3 grid cell to its recipe-ingredient index, or -1 when unused. */
	static int[] cellToIngredient(CraftingRecipe recipe, int[] offset) {
		int[] map = new int[9];
		Arrays.fill(map, -1);
		var ingredients = recipe.getIngredients();
		if (recipe instanceof ShapedRecipe shaped) {
			int w = shaped.getWidth();
			int h = shaped.getHeight();
			for (int py = 0; py < h; py++) {
				for (int px = 0; px < w; px++) {
					int ingIdx = py * w + px;
					if (ingIdx < ingredients.size() && !ingredients.get(ingIdx).isEmpty()) {
						map[(offset[1] + py) * 3 + (offset[0] + px)] = ingIdx;
					}
				}
			}
		} else {
			int cell = 0;
			for (int i = 0; i < ingredients.size() && cell < 9; i++) {
				if (!ingredients.get(i).isEmpty()) {
					map[cell++] = i;
				}
			}
		}
		return map;
	}

	/** An owned item accepted by the ingredient, or null. */
	static Item pickOwned(Ingredient ingredient, List<String> ownedIds) {
		for (String id : ownedIds) {
			ItemStack stack = new ItemStack(Registries.ITEM.get(parseId(id)));
			if (ingredient.test(stack)) {
				return stack.getItem();
			}
		}
		return null;
	}

	@Override
	public void cancel(Task task) {
	}

	@Override
	public dev.squire.server.task.TaskCondition recoverySuccessCondition(
			dev.squire.server.task.TaskStateStore.Snapshot snapshot) {
		Object item = snapshot.parameters().get(PARAM_ITEM_ID);
		Object target = snapshot.parameters().get("targetCount");
		if (!(item instanceof String itemId) || !(target instanceof Number count)) {
			return null;
		}
		return hasProduced(itemId, count.intValue());
	}

	/** Goal condition factory over the REAL inventory. */
	public static dev.squire.server.task.TaskCondition hasProduced(String itemId, int count) {
		return GatherBlockExecutor.hasItems(itemId, count);
	}
}
