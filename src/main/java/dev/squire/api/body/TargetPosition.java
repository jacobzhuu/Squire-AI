package dev.squire.api.body;

/**
 * A validated world target for movement (module-pure: no Minecraft imports).
 * Coordinates are bounded to ±30_000_000 (world border scale); dimension ids are
 * registry-checked at resolve time.
 */
public record TargetPosition(String dimension, double x, double y, double z) {
	private static final double LIMIT = 30_000_000.0;

	public TargetPosition {
		if (dimension == null || dimension.isBlank()) {
			throw new IllegalArgumentException("dimension required");
		}
		if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
			throw new IllegalArgumentException("coordinates must be finite");
		}
		if (Math.abs(x) > LIMIT || Math.abs(z) > LIMIT || y < -2048.0 || y > 2048.0) {
			throw new IllegalArgumentException("coordinate overflow");
		}
	}
}
