package dev.squire.server.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * All registered tools. Registration happens once at server init; lookups are read-only.
 */
public final class ToolRegistry {
	private final Map<String, ToolDefinition> definitions = new LinkedHashMap<>();
	private final Map<String, ToolHandler> handlers = new LinkedHashMap<>();

	public synchronized void register(ToolDefinition definition, ToolHandler handler) {
		if (definitions.containsKey(definition.name())) {
			throw new IllegalStateException("duplicate tool: " + definition.name());
		}
		definitions.put(definition.name(), definition);
		handlers.put(definition.name(), handler);
	}

	public synchronized Optional<ToolDefinition> lookup(String name) {
		return Optional.ofNullable(definitions.get(name));
	}

	public synchronized Optional<ToolHandler> handlerFor(String name) {
		return Optional.ofNullable(handlers.get(name));
	}

	/** Every registered definition regardless of exposure — inspector use only. */
	public synchronized List<ToolDefinition> allDefinitions() {
		return List.copyOf(definitions.values());
	}

	/** Descriptors visible to the model (spec section 25.1 only). */
	public synchronized List<ToolDescriptorHolder> modelVisible() {
		List<ToolDescriptorHolder> out = new ArrayList<>();
		for (ToolDefinition def : definitions.values()) {
			if (def.exposure().visibleToModel()) {
				out.add(new ToolDescriptorHolder(def.descriptor(), def.permission(), def.risk()));
			}
		}
		return Collections.unmodifiableList(out);
	}

	public record ToolDescriptorHolder(dev.squire.common.protocol.ToolDescriptor descriptor,
			AgentPermission permission, RiskLevel risk) {
	}
}
