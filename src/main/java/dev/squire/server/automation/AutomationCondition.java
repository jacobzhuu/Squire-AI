package dev.squire.server.automation;

import java.util.Objects;

import dev.squire.server.world.BoundedRegion;

/**
 * A guard evaluated server-side before/while a graph runs (spec section 49).
 * Conditions are FACTS about the world — never model-supplied expressions.
 */
public final class AutomationCondition {
	public enum Op {
		/** Day-time is at or after the given tick of the day. */
		TIME_OF_DAY_AFTER,
		/** Day-time is before the given tick of the day. */
		TIME_OF_DAY_BEFORE,
		/** The owner player is online. */
		OWNER_ONLINE,
		/** Any player stands inside the region (dimension-bound). */
		PLAYER_IN_REGION
	}

	private final Op op;
	private final long dayTick;
	private final String dimension;
	private final BoundedRegion region;

	private AutomationCondition(Op op, long dayTick, String dimension,
			BoundedRegion region) {
		this.op = op;
		this.dayTick = dayTick;
		this.dimension = dimension;
		this.region = region;
	}

	public static AutomationCondition timeOfDayAfter(long tickOfDay) {
		return new AutomationCondition(Op.TIME_OF_DAY_AFTER, tickOfDay % 24000, null, null);
	}

	public static AutomationCondition timeOfDayBefore(long tickOfDay) {
		return new AutomationCondition(Op.TIME_OF_DAY_BEFORE, tickOfDay % 24000, null, null);
	}

	public static AutomationCondition ownerOnline() {
		return new AutomationCondition(Op.OWNER_ONLINE, 0, null, null);
	}

	public static AutomationCondition playerInRegion(String dimension,
			BoundedRegion region) {
		Objects.requireNonNull(region);
		if (dimension == null || dimension.isBlank()) {
			throw new IllegalArgumentException("region condition needs a dimension");
		}
		return new AutomationCondition(Op.PLAYER_IN_REGION, 0, dimension, region);
	}

	public Op op() {
		return op;
	}

	public long dayTick() {
		return dayTick;
	}

	public String dimension() {
		return dimension;
	}

	public BoundedRegion region() {
		return region;
	}

	@Override
	public String toString() {
		return switch (op) {
			case TIME_OF_DAY_AFTER -> "time>=" + dayTick;
			case TIME_OF_DAY_BEFORE -> "time<" + dayTick;
			case OWNER_ONLINE -> "owner-online";
			case PLAYER_IN_REGION -> "player-in[" + dimension + " " + region + "]";
		};
	}
}
