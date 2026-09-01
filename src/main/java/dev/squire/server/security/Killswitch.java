package dev.squire.server.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Global kill switch (spec section 63). While active: no new world writes, no new
 * commands, automation paused (M5), uncommitted MCP writes cancelled (M4), and
 * agents parked in safe idle. The flag is volatile and checked synchronously in
 * the gateway policy stage — activation takes effect on the same tick.
 */
public final class Killswitch {

	private static final Logger LOG = LoggerFactory.getLogger(Killswitch.class);

	private volatile boolean active = false;
	private volatile long activatedAtTick = -1;

	/** @return true when this call flipped the state. */
	public boolean activate(long tick) {
		if (active) {
			return false;
		}
		active = true;
		activatedAtTick = tick;
		LOG.warn("[killswitch] ACTIVATED at tick {} — world writes and commands denied", tick);
		return true;
	}

	public boolean deactivate() {
		if (!active) {
			return false;
		}
		active = false;
		LOG.warn("[killswitch] deactivated");
		return true;
	}

	public boolean isActive() {
		return active;
	}

	public long activatedAtTick() {
		return activatedAtTick;
	}
}
