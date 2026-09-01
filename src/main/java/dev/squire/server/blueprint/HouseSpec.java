package dev.squire.server.blueprint;

import java.util.Locale;
import java.util.Optional;

/**
 * A deliberately small, serialisable vocabulary for generated houses.
 *
 * <p>The model and panel may choose these values, but neither may provide block
 * coordinates. The deterministic id is all a placement needs to persist; after a
 * restart the exact same blueprint is compiled again.</p>
 */
public record HouseSpec(int width, int depth, int wallHeight,
		Material material, Roof roof) {

	public static final String ID_PREFIX = "generated_house/";
	public static final int[] SIZES = {5, 7, 9};
	public static final int[] HEIGHTS = {3, 4, 5};

	public enum Material {
		OAK("oak", "橡木", "minecraft:oak_planks"),
		SPRUCE("spruce", "云杉木", "minecraft:spruce_planks"),
		COBBLESTONE("cobblestone", "圆石", "minecraft:cobblestone"),
		STONE_BRICKS("stone_bricks", "石砖", "minecraft:stone_bricks");

		private final String id;
		private final String displayName;
		private final String blockId;

		Material(String id, String displayName, String blockId) {
			this.id = id;
			this.displayName = displayName;
			this.blockId = blockId;
		}

		public String id() { return id; }
		public String displayName() { return displayName; }
		public String blockId() { return blockId; }

		static Material byId(String raw) {
			for (Material value : values()) {
				if (value.id.equals(raw)) return value;
			}
			return null;
		}
	}

	public enum Roof {
		FLAT("flat", "平顶"),
		GABLE("gable", "人字顶");

		private final String id;
		private final String displayName;

		Roof(String id, String displayName) {
			this.id = id;
			this.displayName = displayName;
		}

		public String id() { return id; }
		public String displayName() { return displayName; }

		static Roof byId(String raw) {
			for (Roof value : values()) {
				if (value.id.equals(raw)) return value;
			}
			return null;
		}
	}

	public HouseSpec {
		if (!allowed(width, SIZES) || !allowed(depth, SIZES)
				|| !allowed(wallHeight, HEIGHTS)) {
			throw new IllegalArgumentException("unsupported house dimensions");
		}
		if (material == null || roof == null) {
			throw new IllegalArgumentException("house material and roof are required");
		}
	}

	public static HouseSpec defaults() {
		return new HouseSpec(7, 7, 4, Material.OAK, Roof.GABLE);
	}

	public static HouseSpec stoneDefaults() {
		return new HouseSpec(7, 7, 4, Material.STONE_BRICKS, Roof.GABLE);
	}

	public String blueprintId() {
		return material == Material.COBBLESTONE || material == Material.STONE_BRICKS
			? "shelter_stone" : "shelter_wood";
	}

	public String displayName() {
		return width + "×" + depth + " " + material.displayName() + roof.displayName()
			+ "房";
	}

	public HouseSpec withWidth(int next) {
		return new HouseSpec(next, depth, wallHeight, material, roof);
	}

	public HouseSpec withDepth(int next) {
		return new HouseSpec(width, next, wallHeight, material, roof);
	}

	public HouseSpec withWallHeight(int next) {
		return new HouseSpec(width, depth, next, material, roof);
	}

	public HouseSpec withMaterial(Material next) {
		return new HouseSpec(width, depth, wallHeight, next, roof);
	}

	public HouseSpec withRoof(Roof next) {
		return new HouseSpec(width, depth, wallHeight, material, next);
	}

	public static Optional<HouseSpec> parseBlueprintId(String raw) {
		String id = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
		if ("shelter_wood".equals(id)) return Optional.of(defaults());
		if ("shelter_stone".equals(id)) return Optional.of(stoneDefaults());
		if (!id.startsWith(ID_PREFIX)) return Optional.empty();
		String[] parts = id.substring(ID_PREFIX.length()).split("/");
		if (parts.length != 3) return Optional.empty();
		Material material = Material.byId(parts[0]);
		Roof roof = Roof.byId(parts[2]);
		String[] dimensions = parts[1].split("x");
		if (material == null || roof == null || dimensions.length != 3) {
			return Optional.empty();
		}
		try {
			return Optional.of(new HouseSpec(Integer.parseInt(dimensions[0]),
				Integer.parseInt(dimensions[1]), Integer.parseInt(dimensions[2]),
				material, roof));
		} catch (IllegalArgumentException invalid) {
			return Optional.empty();
		}
	}

	private static boolean allowed(int value, int[] choices) {
		for (int choice : choices) if (choice == value) return true;
		return false;
	}
}
