package dev.squire.api.body;

/**
 * Options for a {@link AgentBody#moveTo(TargetPosition, MoveOptions)} request.
 *
 * @param speed        movement speed multiplier (1.0 = normal walk)
 * @param arriveWithin squared distance considered "arrived"
 */
public record MoveOptions(double speed, double arriveWithin) {
	public static final MoveOptions WALK = new MoveOptions(1.0, 2.25);

	public MoveOptions {
		if (!Double.isFinite(speed) || speed <= 0 || speed > 4.0) {
			throw new IllegalArgumentException("speed must be in (0, 4]");
		}
		if (!Double.isFinite(arriveWithin) || arriveWithin <= 0) {
			throw new IllegalArgumentException("arriveWithin must be positive");
		}
	}
}
