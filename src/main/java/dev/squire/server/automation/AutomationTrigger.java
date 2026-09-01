package dev.squire.server.automation;

import java.util.Objects;

import dev.squire.server.world.BoundedRegion;

/**
 * What wakes an {@link AutomationGraph} (spec section 49). One trigger per graph;
 * MANUAL graphs fire only through an explicit owner command/API call.
 */
public final class AutomationTrigger {
	public enum Kind { MANUAL, TIME, INTERVAL, OWNER_ONLINE, PLAYER_ENTER_REGION, TASK_EVENT }

	private final Kind kind;
	/** TIME: in-game time-of-day tick (0..23999) that starts the firing window. */
	private final long timeOfDayTicks;
	/** TIME: how many ticks the window stays open (fires once per day inside it). */
	private final long windowTicks;
	/** INTERVAL: minimum ticks between firings. */
	private final long intervalTicks;
	/** PLAYER_ENTER_REGION: dimension id + region to watch. */
	private final String dimension;
	private final BoundedRegion region;
	/** TASK_EVENT: task type filter (empty = any terminal task event). */
	private final String taskEventType;

	private AutomationTrigger(Kind kind, long timeOfDayTicks, long windowTicks,
			long intervalTicks, String dimension, BoundedRegion region,
			String taskEventType) {
		this.kind = kind;
		this.timeOfDayTicks = timeOfDayTicks;
		this.windowTicks = windowTicks;
		this.intervalTicks = intervalTicks;
		this.dimension = dimension;
		this.region = region;
		this.taskEventType = taskEventType;
	}

	public static AutomationTrigger manual() {
		return new AutomationTrigger(Kind.MANUAL, 0, 0, 0, null, null, "");
	}

	/** Fires once per game day when the day-time enters [timeOfDay, timeOfDay+window). */
	public static AutomationTrigger atTimeOfDay(long timeOfDayTicks, long windowTicks) {
		if (timeOfDayTicks < 0 || timeOfDayTicks >= 24000 || windowTicks <= 0
				|| windowTicks > 24000) {
			throw new IllegalArgumentException("invalid time-of-day trigger");
		}
		return new AutomationTrigger(Kind.TIME,
			timeOfDayTicks % 24000, Math.min(windowTicks, 24000), 0, null, null, "");
	}

	/** Fires every {@code intervalTicks} after creation/submission. */
	public static AutomationTrigger every(long intervalTicks) {
		if (intervalTicks < 20) {
			throw new IllegalArgumentException("interval below 20 ticks");
		}
		return new AutomationTrigger(Kind.INTERVAL, 0, 0, intervalTicks, null, null, "");
	}

	public static AutomationTrigger ownerOnline() {
		return new AutomationTrigger(Kind.OWNER_ONLINE, 0, 0, 0, null, null, "");
	}

	public static AutomationTrigger playerEntersRegion(String dimension,
			BoundedRegion region) {
		Objects.requireNonNull(region);
		if (dimension == null || dimension.isBlank()) {
			throw new IllegalArgumentException("region trigger needs a dimension");
		}
		return new AutomationTrigger(Kind.PLAYER_ENTER_REGION, 0, 0, 0,
			dimension, region, "");
	}

	public static AutomationTrigger taskEvent(String taskType) {
		return new AutomationTrigger(Kind.TASK_EVENT, 0, 0, 0, null, null,
			taskType == null ? "" : taskType);
	}

	public Kind kind() {
		return kind;
	}

	public long timeOfDayTicks() {
		return timeOfDayTicks;
	}

	public long windowTicks() {
		return windowTicks;
	}

	public long intervalTicks() {
		return intervalTicks;
	}

	public String dimension() {
		return dimension;
	}

	public BoundedRegion region() {
		return region;
	}

	public String taskEventType() {
		return taskEventType;
	}

	@Override
	public String toString() {
		return switch (kind) {
			case MANUAL -> "MANUAL";
			case TIME -> "TIME@" + timeOfDayTicks + "+" + windowTicks + "t";
			case INTERVAL -> "INTERVAL/" + intervalTicks + "t";
			case OWNER_ONLINE -> "OWNER_ONLINE";
			case PLAYER_ENTER_REGION -> "ENTER[" + dimension + " " + region + "]";
			case TASK_EVENT -> "TASK_EVENT" + (taskEventType.isEmpty()
				? "" : ":" + taskEventType);
		};
	}
}
