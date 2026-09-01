package dev.squire.api.tool;

import java.util.Objects;

/**
 * Pure argument schema for externally-provided tools (spec section 58). One spec is
 * the single source of truth (§26): the core derives BOTH the JSON-schema descriptor
 * AND the runtime validator from it — providers never maintain two rule sets.
 */
public record ArgSpec(String name, ArgType type, boolean required,
		Long minimum, Long maximum, String pattern, String description) {

	public enum ArgType { STRING, INT, DOUBLE, BOOLEAN, ITEM_ID, BLOCK_ID, ENTITY_ID }

	public ArgSpec {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(type, "type");
		if (name.isBlank()) {
			throw new IllegalArgumentException("arg name must not be blank");
		}
		if (!name.matches("[a-zA-Z][a-zA-Z0-9_]*")) {
			throw new IllegalArgumentException(
				"arg name must be a plain identifier: " + name);
		}
	}

	public static ArgSpec requiredString(String name, String description) {
		return new ArgSpec(name, ArgType.STRING, true, null, null, null, description);
	}

	public static ArgSpec optionalString(String name, String description) {
		return new ArgSpec(name, ArgType.STRING, false, null, null, null, description);
	}

	public static ArgSpec stringMatching(String name, String regex, String description) {
		return new ArgSpec(name, ArgType.STRING, true, null, null,
			Objects.requireNonNull(regex), description);
	}

	public static ArgSpec intRange(String name, long min, long max, String description) {
		if (min > max) {
			throw new IllegalArgumentException("min > max");
		}
		return new ArgSpec(name, ArgType.INT, true, min, max, null, description);
	}

	public static ArgSpec positiveInt(String name, long max, String description) {
		return intRange(name, 1, max, description);
	}

	public static ArgSpec doubleRange(String name, double min, double max,
			String description) {
		if (min > max) {
			throw new IllegalArgumentException("min > max");
		}
		return new ArgSpec(name, ArgType.DOUBLE, true, (long) Math.ceil(min),
			(long) Math.floor(max), null, description);
	}

	public static ArgSpec flag(String name, String description) {
		return new ArgSpec(name, ArgType.BOOLEAN, false, null, null, null, description);
	}

	public static ArgSpec itemId(String name, String description) {
		return new ArgSpec(name, ArgType.ITEM_ID, true, null, null, null, description);
	}

	public static ArgSpec blockId(String name, String description) {
		return new ArgSpec(name, ArgType.BLOCK_ID, true, null, null, null, description);
	}

	public static ArgSpec entityId(String name, String description) {
		return new ArgSpec(name, ArgType.ENTITY_ID, true, null, null, null, description);
	}
}
