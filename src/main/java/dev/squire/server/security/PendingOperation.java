package dev.squire.server.security;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 一次等待确认的高风险操作（方案 F3）。
 *
 * <p>关键点是 {@code canonicalArguments}：确认时重放的是这份 SERVER 侧保存的参数，
 * 而不是再问一次模型。模型因此无法在玩家看过 preview 之后偷偷改掉坐标或方块——
 * 任何参数变化都只能产生一条新的 PendingOperation 和新的 confirmId。</p>
 */
public record PendingOperation(UUID confirmId, UUID ownerId, UUID agentId,
		String operationType, Map<String, Object> canonicalArguments,
		String fingerprint, String preview, long issuedAtTick, long expiresAtTick,
		Status status, String lastError) {

	public enum Status {
		/** 已展示 preview，等待 owner 确认。 */
		PENDING,
		/** owner 已确认并成功提交了真实任务。 */
		EXECUTED,
		/** owner 明确拒绝。 */
		DENIED,
		/** 超时未确认。 */
		EXPIRED,
		/** 确认了但提交失败——绝不能算成"已执行"。 */
		FAILED
	}

	public PendingOperation {
		canonicalArguments = canonicalArguments == null
			? Map.of() : Map.copyOf(new LinkedHashMap<>(canonicalArguments));
	}

	public boolean isPending(long nowTick) {
		return status == Status.PENDING && expiresAtTick > nowTick;
	}

	public PendingOperation withStatus(Status next, String error) {
		return new PendingOperation(confirmId, ownerId, agentId, operationType,
			canonicalArguments, fingerprint, preview, issuedAtTick, expiresAtTick,
			next, error);
	}

	public String shortId() {
		return confirmId.toString().substring(0, 8);
	}
}
