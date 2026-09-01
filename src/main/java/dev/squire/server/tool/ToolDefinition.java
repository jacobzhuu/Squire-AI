package dev.squire.server.tool;

import java.util.ArrayList;
import java.util.List;

import dev.squire.common.protocol.ToolDescriptor;

/**
 * Single source of truth for one tool (spec section 26): JSON schema for providers,
 * runtime validation, docs and inspector metadata are all DERIVED from this definition.
 */
public final class ToolDefinition {
	private final String name;
	private final String description;
	private final List<ArgDefinition> args;
	private final AgentPermission permission;
	private final RiskLevel risk;
	private final ToolExposure exposure;
	private final String permissionNode; // player node required of the requesting owner (§61)
	private final boolean requiresCapability; // scoped Capability mandatory (§30)
	private final dev.squire.server.security.ToolTrust sourceTrust; // who provided this tool (M4/§55)
	private final String origin; // human-readable provider origin for the inspector
	private final boolean destructive; // provider hint — fail-closed input to policy (§55)
	private final List<String> tags;
	private final List<String> aliases;
	private final boolean readOnly;

	private ToolDefinition(Builder b) {
		this.name = b.name;
		this.description = b.description;
		this.args = List.copyOf(b.args);
		this.permission = b.permission;
		this.risk = b.risk;
		this.exposure = b.exposure;
		this.permissionNode = b.permissionNode;
		this.requiresCapability = b.requiresCapability;
		this.sourceTrust = b.sourceTrust;
		this.origin = b.origin;
		this.destructive = b.destructive;
		this.tags = List.copyOf(b.tags);
		this.aliases = List.copyOf(b.aliases);
		this.readOnly = b.readOnly || b.permission == AgentPermission.QUERY;
	}

	public static Builder builder(String name) {
		return new Builder(name);
	}

	public String name() {
		return name;
	}

	public String description() {
		return description;
	}

	public List<ArgDefinition> args() {
		return args;
	}

	public AgentPermission permission() {
		return permission;
	}

	public RiskLevel risk() {
		return risk;
	}

	public ToolExposure exposure() {
		return exposure;
	}

	/** Player permission node the REQUESTING OWNER must hold, or null for none (§61). */
	public String permissionNode() {
		return permissionNode;
	}

	/** True when dispatch needs a live scoped Capability (§30). */
	public boolean requiresCapability() {
		return requiresCapability;
	}

	/** Trust classification of whoever provided this tool (default INTERNAL, M4). */
	public dev.squire.server.security.ToolTrust sourceTrust() {
		return sourceTrust;
	}

	/** Human-readable provider origin (mod id / MCP server name) for the inspector. */
	public String origin() {
		return origin;
	}

	/** Provider's destructive hint — can only make policy STRICTER, never looser. */
	public boolean destructive() {
		return destructive;
	}

	public List<String> tags() { return tags; }
	public List<String> aliases() { return aliases; }
	public boolean readOnly() { return readOnly; }

	/** Provider-independent schema; adapters translate to their native wire format. */
	public ToolDescriptor descriptor() {
		List<ToolDescriptor.ParameterDescriptor> params = new ArrayList<>();
		for (ArgDefinition arg : args) {
			String type = switch (arg.type()) {
				case STRING, ITEM_ID, BLOCK_ID, ENTITY_ID -> "string";
				case INT -> "integer";
				case DOUBLE -> "number";
				case BOOLEAN -> "boolean";
			};
			params.add(new ToolDescriptor.ParameterDescriptor(arg.name(), type,
				arg.required(), arg.description(), arg.minimum() == null ? null : arg.minimum().doubleValue(),
				arg.maximum() == null ? null : arg.maximum().doubleValue()));
		}
		return new ToolDescriptor(name, description, params, tags, aliases, readOnly);
	}

	public static final class Builder {
		private final String name;
		private String description = "";
		private final List<ArgDefinition> args = new ArrayList<>();
		private AgentPermission permission = AgentPermission.QUERY;
		private RiskLevel risk = RiskLevel.LOW;
		private ToolExposure exposure = ToolExposure.MODEL_PUBLIC;
		private String permissionNode;
		private boolean requiresCapability;
		private dev.squire.server.security.ToolTrust sourceTrust =
			dev.squire.server.security.ToolTrust.INTERNAL;
		private String origin = "core";
		private boolean destructive;
		private final List<String> tags = new ArrayList<>();
		private final List<String> aliases = new ArrayList<>();
		private boolean readOnly;

		private Builder(String name) {
			if (name == null || name.isBlank()) {
				throw new IllegalArgumentException("tool name must not be blank");
			}
			this.name = name;
		}

		public Builder description(String description) {
			this.description = description;
			return this;
		}

		public Builder arg(ArgDefinition arg) {
			args.add(arg);
			return this;
		}

		public Builder permission(AgentPermission permission) {
			this.permission = permission;
			return this;
		}

		public Builder risk(RiskLevel risk) {
			this.risk = risk;
			return this;
		}

		public Builder exposure(ToolExposure exposure) {
			this.exposure = exposure;
			return this;
		}

		public Builder node(String permissionNode) {
			this.permissionNode = permissionNode;
			return this;
		}

		public Builder requiresCapability() {
			this.requiresCapability = true;
			return this;
		}

		public Builder sourceTrust(dev.squire.server.security.ToolTrust trust) {
			this.sourceTrust = java.util.Objects.requireNonNull(trust, "trust");
			return this;
		}

		public Builder origin(String origin) {
			this.origin = origin == null || origin.isBlank() ? "unknown" : origin;
			return this;
		}

		public Builder destructive() {
			this.destructive = true;
			return this;
		}

		public Builder tag(String tag) {
			if (tag != null && !tag.isBlank()) tags.add(tag.trim());
			return this;
		}

		public Builder alias(String alias) {
			if (alias != null && !alias.isBlank()) aliases.add(alias.trim());
			return this;
		}

		public Builder readOnly() {
			this.readOnly = true;
			return this;
		}

		public ToolDefinition build() {
			return new ToolDefinition(this);
		}
	}
}
