package dev.squire.server.task;

/**
 * Task priorities (spec section 21). Lower ordinal preempts higher ordinal number...
 * expressed positively: P0 EMERGENCY outranks everything, P7 IDLE runs last.
 */
public enum TaskPriority {
	P0_EMERGENCY,
	P1_SURVIVAL,
	P2_OWNER_URGENT,
	P3_USER_TASK,
	P4_FOLLOW,
	P5_ROUTINE,
	P6_SOCIAL,
	P7_IDLE;

	/** True when this task may preempt {@code other} (strictly higher priority). */
	public boolean preempts(TaskPriority other) {
		return ordinal() < other.ordinal();
	}
}
