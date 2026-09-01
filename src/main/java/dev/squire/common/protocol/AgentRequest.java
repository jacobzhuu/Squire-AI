package dev.squire.common.protocol;

import java.util.UUID;

/**
 * One turn of conversation handed to an {@code LlmProvider} (spec section 23).
 *
 * <p>Pure DTO — no Minecraft imports. The provider sees only sanitized text and ids;
 * it never receives live world references or permission state.</p>
 *
 * <p>{@code agentName} 是玩家给这只侍从起的名字。它必须走到<b>提示词</b>里，而不是
 * 只出现在感知块里：系统提示词是模型对"我是谁"的唯一定义，那里写死一句
 * "You are Squire" 的话，玩家用命名牌改成「豆包」之后，他仍然会自称 Squire——
 * 这正是玩家反馈的"他意识不到自己叫这个名字"。空字符串表示还没起名。</p>
 */
public record AgentRequest(
	UUID requestId,
	UUID senderId,
	UUID agentId,
	String message,
	String agentName
) {
	public AgentRequest {
		if (requestId == null) {
			throw new IllegalArgumentException("requestId must not be null");
		}
		if (senderId == null) {
			throw new IllegalArgumentException("senderId must not be null");
		}
		if (message == null || message.isBlank()) {
			throw new IllegalArgumentException("message must not be blank");
		}
		agentName = agentName == null ? "" : agentName.trim();
	}

	/** 没有名字（或者调用方拿不到）时的便利构造。 */
	public AgentRequest(UUID requestId, UUID senderId, UUID agentId, String message) {
		this(requestId, senderId, agentId, message, "");
	}

	public boolean hasAgentName() {
		return !agentName.isEmpty();
	}
}
