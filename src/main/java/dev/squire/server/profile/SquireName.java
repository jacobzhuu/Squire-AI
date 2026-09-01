package dev.squire.server.profile;

/**
 * A Squire name is part of its durable identity, not client-side entity decoration.
 * This small value validator is shared by the panel and the authoritative server path;
 * the server always validates again before touching the record.
 */
public final class SquireName {

	public static final int MAX_LENGTH = 32;

	public enum Error {
		NONE, EMPTY, TOO_LONG, ILLEGAL_CHARACTER
	}

	public record Validation(String value, Error error) {
		public boolean valid() {
			return error == Error.NONE;
		}
	}

	private SquireName() {
	}

	public static Validation validate(String raw) {
		String value = raw == null ? "" : raw.trim();
		if (value.isEmpty()) {
			return new Validation(value, Error.EMPTY);
		}
		if (value.length() > MAX_LENGTH) {
			return new Validation(value, Error.TOO_LONG);
		}
		for (int offset = 0; offset < value.length();) {
			int codePoint = value.codePointAt(offset);
			if (!allowed(codePoint)) {
				return new Validation(value, Error.ILLEGAL_CHARACTER);
			}
			offset += Character.charCount(codePoint);
		}
		return new Validation(value, Error.NONE);
	}

	private static boolean allowed(int codePoint) {
		return Character.isLetterOrDigit(codePoint)
			|| codePoint == ' '
			|| codePoint == '_'
			|| codePoint == '-'
			|| codePoint == '.'
			|| codePoint == '\''
			|| codePoint == '\u2019'
			|| codePoint == '\u00b7';
	}
}
