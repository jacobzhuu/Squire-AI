package dev.squire.server.blueprint;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Compiles a validated {@link HouseSpec} into an ordinary blueprint. */
public final class HouseBlueprintFactory {

	private HouseBlueprintFactory() { }

	public static Blueprint compile(HouseSpec spec) {
		List<BlueprintStep> steps = new ArrayList<>();
		int order = 0;
		String wall = spec.material().blockId();
		int w = spec.width();
		int d = spec.depth();
		int h = spec.wallHeight();

		steps.add(BlueprintStep.place(order++, 0, 0, 0, w - 1, 0, d - 1,
			wall, "地板", false));
		steps.add(BlueprintStep.place(order++, 0, 1, 0, w - 1, h, 0,
			wall, "北墙", false));
		steps.add(BlueprintStep.place(order++, 0, 1, d - 1, w - 1, h, d - 1,
			wall, "南墙", false));
		steps.add(BlueprintStep.place(order++, 0, 1, 1, 0, h, d - 2,
			wall, "西墙", false));
		steps.add(BlueprintStep.place(order++, w - 1, 1, 1, w - 1, h, d - 2,
			wall, "东墙", false));

		// Authored facing north: the entrance is on the south wall, toward the player.
		steps.add(BlueprintStep.dig(order++, w / 2, 1, d - 1,
			w / 2, 2, d - 1, "门洞"));
		steps.add(BlueprintStep.place(order++, 0, 2, d / 2, 0, 2, d / 2,
			"minecraft:glass_pane", "西窗", true));
		steps.add(BlueprintStep.place(order++, w - 1, 2, d / 2,
			w - 1, 2, d / 2, "minecraft:glass_pane", "东窗", true));

		int totalHeight;
		if (spec.roof() == HouseSpec.Roof.FLAT) {
			steps.add(BlueprintStep.place(order++, 0, h + 1, 0,
				w - 1, h + 1, d - 1, wall, "平屋顶", false));
			totalHeight = h + 2;
		} else {
			int ridge = w / 2;
			for (int x = 0; x < w; x++) {
				int y = h + 1 + Math.min(x, w - 1 - x);
				steps.add(BlueprintStep.place(order++, x, y, 0, x, y, d - 1,
					wall, x == ridge ? "屋脊" : "斜屋顶", false));
			}
			totalHeight = h + 2 + ridge;
		}

		return new Blueprint(spec.blueprintId(), spec.displayName(), 1,
			Blueprint.Category.HOUSING, w, totalHeight, d, steps,
			Set.of(BlueprintRegistry.ABILITY_BUILD));
	}
}
