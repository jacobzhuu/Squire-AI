package dev.squire.server.command;

import net.minecraft.text.Text;

/**
 * i18n seam (spec §90). Player-visible {@code /squire} feedback goes through
 * {@link #msg} so clients render it from their own language
 * ({@code assets/squire/lang/<locale>.json}; every value already carries its
 * locale's {@code [Squire]} prefix). Runtime-internal engine messages (tool
 * results, access refusals) remain English literals in v1.0 — they are
 * diagnostic output, documented as such in docs/guides/server-guide.md.
 */
public final class SquireText {
	private SquireText() {
	}

	/** @return a translatable feedback line under the {@code squire.*} namespace. */
	public static Text msg(String key, Object... args) {
		return Text.translatable(key, args);
	}
}
