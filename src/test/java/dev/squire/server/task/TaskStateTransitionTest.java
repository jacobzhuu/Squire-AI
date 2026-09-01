package dev.squire.server.task;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 任务状态机的两条不变量。
 *
 * <p>这张表以前只被间接验过——调度器测试跑到哪条边就验到哪条边，而漏掉的那条边
 * 只会在运行时抛 {@code IllegalStateException}。下面两条把最要紧的两端钉死。</p>
 */
class TaskStateTransitionTest {

	/**
	 * <b>任何还活着的任务都取消得掉。</b>
	 *
	 * <p>这条是从一次真实的红灯来的：{@link TaskState#VERIFYING} 是唯一没有
	 * CANCELLED 出边的活动状态，而 {@code TaskScheduler.cancelAgent} 只判
	 * {@code isActive()} 就直接 {@code transitionTo(CANCELLED)}。验证窗口只有几拍，
	 * 但玩家随时可能按遣散、按巡逻、点「不做了」——落在那几拍里就抛异常，
	 * 取消半途而废，任务永远挂在 {@code runningByAgent} 里。</p>
	 */
	@Test
	void everyActiveStateCanBeCancelled() {
		for (TaskState state : TaskState.values()) {
			if (!state.isActive()) {
				continue;
			}
			assertTrue(state.canTransitionTo(TaskState.CANCELLED),
				state + " 取消不掉：玩家的「停下」必须在任何时刻都生效，"
					+ "否则调度器会抛异常并把任务卡在半路");
		}
	}

	/**
	 * <b>成功只能由验证器宣布</b>（ADR-013）。
	 *
	 * <p>上面那条放宽了 VERIFYING 的出边，这一条守住它<b>没有</b>顺手放宽这一侧：
	 * 取消不宣称任何结果，完成才宣称，两者不是一回事。</p>
	 */
	@Test
	void onlyVerificationLeadsToCompleted() {
		for (TaskState state : TaskState.values()) {
			if (state == TaskState.VERIFYING) {
				continue;
			}
			assertFalse(state.canTransitionTo(TaskState.COMPLETED),
				state + " 可以不经验证就直接宣称完成，这正是 ADR-013 要挡的");
		}
		assertTrue(TaskState.VERIFYING.canTransitionTo(TaskState.COMPLETED));
	}

	/** 终态就是终态：不许复活。 */
	@Test
	void terminalStatesGoNowhere() {
		for (TaskState state : TaskState.values()) {
			if (state.isActive()) {
				continue;
			}
			for (TaskState target : TaskState.values()) {
				assertFalse(state.canTransitionTo(target),
					state + " -> " + target + "：终态不许再动");
			}
		}
	}
}
