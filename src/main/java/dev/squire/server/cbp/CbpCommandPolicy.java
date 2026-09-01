package dev.squire.server.cbp;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Shape whitelist for commands written INTO a materialized command block (spec
 * §50: "使用结构化 Command Intent"). Only the three families the structured
 * compiler already whitelists may be baked into a block — anything else is
 * refused at spec-validation time, before any confirmation exists.
 *
 * <p>Registry-dependent existence checks (item/effect ids) happen later on the
 * server thread inside {@link CbpMaterializer}; everything here is pure so unit
 * tests can run without a Minecraft world.</p>
 */
public final class CbpCommandPolicy {

	private CbpCommandPolicy() {
	}

	/** Command families allowed inside a materialized block. */
	public enum Family { GIVE, EFFECT_GIVE, SUMMON }

	/** Vanilla-style resource id (namespace optional). */
	private static final Pattern RESOURCE_ID =
		Pattern.compile("[a-z0-9_]+(:[a-z0-9_.\\-]+)?");

	/** `~`, `~12`, `~-3` — block-relative offsets only, no absolutes. */
	private static final Pattern REL_COORD = Pattern.compile("~[+-]?\\d*");

	private static final int MAX_TOKENS = 6;
	private static final int MAX_COMMAND_CHARS = 256;

	/**
	 * Pure shape check.
	 *
	 * @return empty when acceptable, otherwise a user-facing refusal reason
	 */
	public static Optional<String> validate(String command) {
		if (command == null || command.isBlank()) {
			return Optional.of("command must not be empty");
		}
		if (command.length() > MAX_COMMAND_CHARS) {
			return Optional.of("command exceeds " + MAX_COMMAND_CHARS + " chars");
		}
		String trimmed = command.trim();
		if (trimmed.contains("\n") || trimmed.contains("\r") || trimmed.contains(";")) {
			return Optional.of("multi-statement commands are not allowed");
		}
		String[] tokens = trimmed.split(" ");
		if (tokens.length > MAX_TOKENS) {
			return Optional.of("too many arguments");
		}
		return switch (tokens[0]) {
			case "give" -> validateGive(tokens);
			case "effect" -> validateEffect(tokens);
			case "summon" -> validateSummon(tokens);
			default -> Optional.of("command family '" + tokens[0]
				+ "' is not whitelisted for command blocks");
		};
	}

	/** {@code give @p <item> <count>} — nearest player, 1..64 items. */
	private static Optional<String> validateGive(String[] t) {
		if (t.length != 4) {
			return Optional.of("give wants: give @p <item> <count>");
		}
		if (!"@p".equals(t[1])) {
			return Optional.of("give target must be @p (nearest player)");
		}
		if (!RESOURCE_ID.matcher(t[2]).matches()) {
			return Optional.of("item id '" + t[2] + "' is not a valid resource id");
		}
		Integer count = positiveInt(t[3]);
		if (count == null || count < 1 || count > 64) {
			return Optional.of("count must be 1..64");
		}
		return Optional.empty();
	}

	/** {@code effect give @p <effect> <seconds> [amplifier]} */
	private static Optional<String> validateEffect(String[] t) {
		if (t.length != 5 && t.length != 6) {
			return Optional.of("effect wants: effect give @p <effect> <seconds> [amplifier]");
		}
		if (!"give".equals(t[1]) || !"@p".equals(t[2])) {
			return Optional.of("effect target must be @p via 'effect give'");
		}
		if (!dev.squire.server.command.StructuredCommandCompiler.ALLOWED_EFFECTS
				.contains(normalize(t[3]))) {
			return Optional.of("effect '" + t[3] + "' is not on the beneficial whitelist");
		}
		Integer seconds = positiveInt(t[4]);
		if (seconds == null || seconds < 1 || seconds > 7200) {
			return Optional.of("duration must be 1..7200 seconds");
		}
		if (t.length == 6) {
			Integer amp = positiveInt(t[5]);
			if (amp == null || amp > 255) {
				return Optional.of("amplifier must be 0..255");
			}
		}
		return Optional.empty();
	}

	/** {@code summon <entity> [~ ~ ~]} — peaceful entities only. */
	private static Optional<String> validateSummon(String[] t) {
		if (t.length != 2 && t.length != 5) {
			return Optional.of("summon wants: summon <entity> [~ ~ ~]");
		}
		if (!dev.squire.server.command.StructuredCommandCompiler
				.isSafeSummon(normalize(t[1]))) {
			return Optional.of("entity '" + t[1] + "' is not on the summon-safe whitelist");
		}
		for (int i = 2; i < t.length; i++) {
			if (!REL_COORD.matcher(t[i]).matches()
					|| Math.abs(coordinateValue(t[i])) > 256) {
				return Optional.of("offsets must be relative (~ style) within ±256");
			}
		}
		return Optional.empty();
	}

	private static String normalize(String id) {
		return id.contains(":") ? id.toLowerCase(Locale.ROOT)
			: "minecraft:" + id.toLowerCase(Locale.ROOT);
	}

	private static Integer positiveInt(String raw) {
		try {
			int v = Integer.parseInt(raw);
			return v >= 0 ? v : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static int coordinateValue(String raw) {
		if (raw.startsWith("~")) {
			String rest = raw.substring(1);
			return rest.isEmpty() ? 0 : Integer.parseInt(rest);
		}
		return Integer.parseInt(raw);
	}
}
