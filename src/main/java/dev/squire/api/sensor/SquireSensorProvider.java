package dev.squire.api.sensor;

/**
 * Third-party sensor entrypoint (spec sections 59 + M4): sensors are read-only,
 * bounded and budgeted by definition; the core polls them inside its perception
 * tick budget.
 */
public interface SquireSensorProvider {

	/**
	 * @return one sensor implementation per call id, or an empty list when the mod
	 *     contributes nothing on this platform
	 */
	java.util.List<SquireSensor> createSensors();
}
