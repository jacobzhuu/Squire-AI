package dev.squire.common.protocol;

import java.util.List;
import java.util.Objects;

/**
 * Provider reply for one {@link AgentRequest}.
 *
 * <p>The original API exposed only {@link #text()}. That made an HTTP-200 reply
 * with empty content indistinguishable from a successful conversational turn and
 * discarded the provider's truncation and usage signals. The richer fields are
 * deliberately provider-neutral; the existing one-argument constructor and
 * accessors remain source compatible for third-party providers.</p>
 */
public final class AgentResponse {
	public enum Outcome {
		COMPLETED,
		EMPTY_OUTPUT,
		TRUNCATED,
		CONTENT_FILTERED,
		TRANSIENT_ERROR,
		PERMANENT_ERROR
	}

	public record Usage(long inputTokens, long outputTokens, long totalTokens) {
		public Usage {
			inputTokens = Math.max(0L, inputTokens);
			outputTokens = Math.max(0L, outputTokens);
			totalTokens = Math.max(Math.max(0L, totalTokens), inputTokens + outputTokens);
		}

		public static Usage unknown() {
			return new Usage(0L, 0L, 0L);
		}
	}

	private final String text;
	private final List<ToolCall> toolCalls;
	private final String finishReason;
	private final Outcome outcome;
	private final Usage usage;
	private final String requestId;

	/** Compatibility constructor for simple/mock providers. */
	public AgentResponse(String text) {
		this(text, List.of(), null,
			text == null || text.isBlank() ? Outcome.EMPTY_OUTPUT : Outcome.COMPLETED,
			Usage.unknown(), null);
	}

	public AgentResponse(String text, List<ToolCall> toolCalls, String finishReason,
			Outcome outcome, Usage usage, String requestId) {
		this.text = Objects.requireNonNullElse(text, "");
		this.toolCalls = List.copyOf(toolCalls == null ? List.of() : toolCalls);
		this.finishReason = finishReason;
		this.outcome = Objects.requireNonNullElseGet(outcome, () ->
			this.text.isBlank() && this.toolCalls.isEmpty()
				? Outcome.EMPTY_OUTPUT : Outcome.COMPLETED);
		this.usage = usage == null ? Usage.unknown() : usage;
		this.requestId = requestId;
	}

	public String text() { return text; }
	public List<ToolCall> toolCalls() { return toolCalls; }
	public String finishReason() { return finishReason; }
	public Outcome outcome() { return outcome; }
	public Usage usage() { return usage; }
	public String requestId() { return requestId; }

	public boolean hasOutput() {
		return !text.isBlank() || !toolCalls.isEmpty();
	}

	public static AgentResponse of(String text) {
		return new AgentResponse(text);
	}

	@Override
	public String toString() {
		return "AgentResponse[outcome=" + outcome + ", finishReason=" + finishReason
			+ ", textLength=" + text.length() + ", toolCalls=" + toolCalls.size()
			+ ", usage=" + usage + "]";
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) return true;
		if (!(other instanceof AgentResponse that)) return false;
		return text.equals(that.text) && toolCalls.equals(that.toolCalls)
			&& Objects.equals(finishReason, that.finishReason) && outcome == that.outcome
			&& usage.equals(that.usage) && Objects.equals(requestId, that.requestId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(text, toolCalls, finishReason, outcome, usage, requestId);
	}
}
