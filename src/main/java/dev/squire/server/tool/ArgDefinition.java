package dev.squire.server.tool;

import java.util.Optional;

/**
 * One tool argument: schema AND validator from a single declaration (spec section 26).
 * Values arrive decoded from JSON (String/Number/Boolean/Map/List).
 */
public final class ArgDefinition {
	public enum ArgType { STRING, INT, DOUBLE, BOOLEAN, ITEM_ID, BLOCK_ID, ENTITY_ID }

	private final String name;
	private final ArgType type;
	private final boolean required;
	private final Long min;
	private final Long max;
	private final String pattern;
	private final String description;

	private ArgDefinition(String name, ArgType type, boolean required,
			Long min, Long max, String description) {
		this(name, type, required, min, max, null, description);
	}

	private ArgDefinition(String name, ArgType type, boolean required,
			Long min, Long max, String pattern, String description) {
		this.name = name;
		this.type = type;
		this.required = required;
		this.min = min;
		this.max = max;
		this.pattern = pattern;
		this.description = description;
	}

	/** Full-shape factory used by the extension adapter (spec section 58 mapping). */
	public static ArgDefinition of(String name, ArgType type, boolean required,
			Long min, Long max, String pattern, String description) {
		return new ArgDefinition(name, type, required, min, max, pattern, description);
	}

	public static ArgDefinition required(String name, ArgType type, String description) {
		return new ArgDefinition(name, type, true, null, null, description);
	}

	public static ArgDefinition optional(String name, ArgType type, String description) {
		return new ArgDefinition(name, type, false, null, null, description);
	}

	public static ArgDefinition intRange(String name, long min, long max, String description) {
		return new ArgDefinition(name, ArgType.INT, true, min, max, description);
	}

	public String name() {
		return name;
	}

	public ArgType type() {
		return type;
	}

	public boolean required() {
		return required;
	}

	public String description() {
		return description;
	}

	public Long minimum() {
		return min;
	}

	public Long maximum() {
		return max;
	}

	/** Validate one decoded JSON value; empty = valid, present = error message. */
	public Optional<String> validate(Object value) {
		if (value == null) {
			return required
				? Optional.of("missing required argument '" + name + "'")
				: Optional.empty();
		}
		switch (type) {
			case STRING -> {
				if (!(value instanceof String s) || s.isBlank()) {
					return Optional.of("'" + name + "' must be a non-blank string");
				}
				if (pattern != null && !s.matches(pattern)) {
					return Optional.of("'" + name + "' does not match the required format");
				}
			}
			case ITEM_ID, BLOCK_ID, ENTITY_ID -> {
				if (!(value instanceof String id) || id.isBlank()) {
					return Optional.of("'" + name + "' must be an identifier string");
					// full registry existence check happens in the world-facing handler
				}
			}
			case INT -> {
				Long n = asLong(value);
				if (n == null) {
					return Optional.of("'" + name + "' must be an integer");
				}
				if ((min != null && n < min) || (max != null && n > max)) {
					return Optional.of("'" + name + "' must be within [" + min + ", " + max + "]");
				}
			}
			case DOUBLE -> {
				if (!(value instanceof Number)) {
					return Optional.of("'" + name + "' must be a number");
				}
			}
			case BOOLEAN -> {
				if (!(value instanceof Boolean)) {
					return Optional.of("'" + name + "' must be a boolean");
				}
			}
		}
		return Optional.empty();
	}

	private static Long asLong(Object value) {
		if (value instanceof Number n) {
			long l = n.longValue();
			if (l == n.doubleValue()) {
				return l;
			}
		}
		return null;
	}
}
