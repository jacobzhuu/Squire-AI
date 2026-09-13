package dev.squire.common.animation;

/** Per-body, tick-driven ground gait. No Minecraft/client classes or render-time integration. */
public final class LocomotionGait {
    private static final double TAU = Math.PI * 2;
    private double x, y, z, phase, previousPhase, amplitude, previousAmplitude;
    private double directionX, directionZ = 1;
    private double previousDirectionX, previousDirectionZ = 1;
    private boolean initialized;
    private int revision, settlingTicks;

    /** A revision change denotes a deliberate reposition, including sub-block corrections. */
    public void tick(double x, double y, double z, boolean grounded, int revision) {
        double dx = x - this.x, dy = y - this.y, dz = z - this.z;
        this.x = x; this.y = y; this.z = z;
        double distance = Math.hypot(dx, dz);
        boolean reset = !initialized || this.revision != revision || distance > 1.25 || Math.abs(dy) > 1.25;
        initialized = true;
        this.revision = revision;
        if (reset) {
            // Entity position packets interpolate over three client ticks. Exclude
            // that whole correction, not just the tick carrying the metadata.
            settlingTicks = 3;
            phase = previousPhase = amplitude = previousAmplitude = 0;
            return;
        }
        previousPhase = phase;
        previousAmplitude = amplitude;
        previousDirectionX = directionX;
        previousDirectionZ = directionZ;
        if (settlingTicks > 0 || !grounded) {
            if (settlingTicks > 0) settlingTicks--;
            amplitude = previousAmplitude = 0;
            return;
        }
        if (distance < .002) {
            amplitude *= .55;
            if (amplitude < .001) amplitude = 0;
            return; // No accumulated phase while blocked or standing still.
        }
        directionX = dx / distance;
        directionZ = dz / distance;
        // Short, restrained steps when shuffling; longer strides when travelling.
        // A full cycle covers this many blocks, irrespective of render FPS.
        double stride = Math.max(.75, Math.min(2.7, .75 + distance * 8));
        double angle = Math.asin(stride / 3.0); // two legs, each 0.75 blocks long
        amplitude += (angle - amplitude) * .45;
        phase += TAU * distance / stride;
        if (phase >= TAU) { phase -= TAU; previousPhase -= TAU; }
    }

    public Frame frame(float tickDelta, float bodyYawDegrees) {
        double t = Math.max(0, Math.min(1, tickDelta));
        double yaw = Math.toRadians(bodyYawDegrees);
        double dx = previousDirectionX + (directionX - previousDirectionX) * t;
        double dz = previousDirectionZ + (directionZ - previousDirectionZ) * t;
        double forward = -dx * Math.sin(yaw) + dz * Math.cos(yaw);
        double right = -dx * Math.cos(yaw) - dz * Math.sin(yaw);
        return new Frame(previousPhase + (phase - previousPhase) * t,
            previousAmplitude + (amplitude - previousAmplitude) * t, forward, right);
    }

    public record Frame(double phase, double amplitude, double forward, double right) {
        public float pitch() { return (float) (Math.cos(phase) * amplitude * forward); }
        public float roll() { return (float) (-Math.cos(phase) * amplitude * right); }
    }
}
