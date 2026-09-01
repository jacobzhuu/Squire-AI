package dev.squire.server.item;

import java.util.Locale;

/** Stable ids stored on a squire record and mirrored onto its recall bell. */
public enum BellTier {
	COMMON("common"),
	ENHANCED("enhanced"),
	RESONANT("resonant"),
	ROYAL("royal");

	private final String id;

	BellTier(String id) {
		this.id = id;
	}

	public String id() {
		return id;
	}

	public String translationKey() {
		return "item.squire.recall_bell.tier." + id;
	}

	public BellTier next() {
		int next = ordinal() + 1;
		return next < values().length ? values()[next] : null;
	}

	public boolean atLeast(BellTier other) {
		return ordinal() >= other.ordinal();
	}

	public static BellTier byId(String raw) {
		if (raw == null) return COMMON;
		String id = raw.trim().toLowerCase(Locale.ROOT);
		for (BellTier tier : values()) {
			if (tier.id.equals(id) || tier.name().equalsIgnoreCase(id)) return tier;
		}
		return COMMON;
	}
}
