package dev.squire.server.blueprint;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A complete, named family of blocks that may fill one or more blueprint slots.
 * Families are explicit rather than guessed from registry names so modded blocks can
 * participate without silently producing a roof with missing stairs or slabs.
 */
public record MaterialFamily(String id, String displayName, Set<SlotType> types,
		Map<String, String> variants) {

	public enum SlotType {
		FOUNDATION, FRAME, WALL, FLOOR, ROOF, WINDOW;

		public static SlotType parse(String raw) {
			return valueOf(raw.trim().toUpperCase(Locale.ROOT));
		}
	}

	public MaterialFamily {
		if (id == null || id.isBlank()) throw new IllegalArgumentException("material family needs an id");
		displayName = displayName == null || displayName.isBlank() ? id : displayName;
		types = types == null ? Set.of() : Set.copyOf(types);
		variants = variants == null ? Map.of() : Map.copyOf(variants);
		if (types.isEmpty()) throw new IllegalArgumentException(id + ": material family needs at least one type");
		if (variants.isEmpty()) throw new IllegalArgumentException(id + ": material family needs variants");
	}

	public boolean supports(Blueprint.MaterialSlot slot) {
		return slot != null && types.contains(slot.type())
			&& variants.keySet().containsAll(slot.requiredVariants());
	}

	public String block(String variant) {
		String block = variants.get(variant);
		if (block == null || block.isBlank()) {
			throw new IllegalArgumentException(id + ": missing material variant " + variant);
		}
		return block;
	}

	public String representative(MaterialFamily.SlotType type) {
		String preferred = switch (type) {
			case FRAME -> "log";
			case ROOF -> "stairs";
			case WINDOW -> "pane";
			case FLOOR -> "planks";
			default -> "block";
		};
		return variants.getOrDefault(preferred, variants.values().iterator().next());
	}
}
