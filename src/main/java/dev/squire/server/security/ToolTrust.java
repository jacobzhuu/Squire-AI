package dev.squire.server.security;

/**
 * Trust classification for externally-provided tools (spec section 55). The default
 * for anything not explicitly approved is {@link #UNTRUSTED}.
 *
 * <p>Provider annotations (readOnly/destructive) are HINTS only — they can make the
 * core MORE restrictive, never less: an UNTRUSTED tool is denied for any
 * write-shaped or high-risk use regardless of claiming readOnly.</p>
 */
public enum ToolTrust {
	INTERNAL(true),
	TRUSTED_MOD(true),
	ADMIN_APPROVED_LOCAL(true),
	ADMIN_APPROVED_REMOTE(true),
	UNTRUSTED(false),
	BLOCKED(false);

	private final boolean trusted;

	ToolTrust(boolean trusted) {
		this.trusted = trusted;
	}

	public boolean isTrusted() {
		return trusted;
	}
}
