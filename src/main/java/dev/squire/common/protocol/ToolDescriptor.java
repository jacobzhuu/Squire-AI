package dev.squire.common.protocol;

import java.util.List;

/**
 * Provider-facing description of one tool, generated FROM the ToolDefinition
 * (spec sections 22/26) — never maintained separately from it.
 *
 * <p>{@code parametersJsonSchema} is a provider-independent JSON-Schema string;
 * provider adapters translate it into their native wire format.</p>
 */
public record ToolDescriptor(
	String name,
	String description,
	List<ParameterDescriptor> parameters,
	List<String> tags,
	List<String> aliases,
	boolean readOnly
) {
	public ToolDescriptor {
		parameters = List.copyOf(parameters);
		tags = List.copyOf(tags == null ? List.of() : tags);
		aliases = List.copyOf(aliases == null ? List.of() : aliases);
	}

	/** Backward-compatible constructor for providers compiled against the v1 DTO. */
	public ToolDescriptor(String name, String description,
			List<ParameterDescriptor> parameters) {
		this(name, description, parameters, List.of(), List.of(), false);
	}

	public record ParameterDescriptor(
		String name,
		String type,
		boolean required,
		String description,
		Double minimum,
		Double maximum
	) {
	}
}
