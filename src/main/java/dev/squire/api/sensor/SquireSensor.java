package dev.squire.api.sensor;

/** One read-only observation source (spec section 59). */
public interface SquireSensor {

	SensorDefinition definition();

	SensorResult observe(SensorContext context);
}
