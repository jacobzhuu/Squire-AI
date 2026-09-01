package dev.squire.api.tool;

import java.util.List;
import java.util.Objects;

/**
 * A complete third-party tool description (spec section 58). Everything the core
 * needs to expose, validate, gate and audit the tool — stable id, schema, risk,
 * exposure, trust hints and the handler.
 *
 * <p>Annotations ({@code readOnlyHint}/{@code destructiveHint}) are HINTS ONLY
 * (spec section 55): the core classifies impact from the declared risk and its own
 * policy, never from provider claims.</p>
 */
public final class ExternalTool {
	private final String id;
	private final String description;
	private final List<ArgSpec> args;
	private final String permissionNode; // custom node, auto-registered as known-but-ungranted
	private final String risk; // LOW | MEDIUM | HIGH
	private final String exposure; // MODEL_PUBLIC | PLANNER_INTERNAL | ADMIN_ONLY
	private final boolean readOnlyHint;
	private final boolean destructiveHint;
	private final String precondition; // documented precondition (docs/inspector)
	private final String postcondition; // documented postcondition
	private final List<String> tags;
	private final List<String> aliases;
	private final ToolHandler handler;

	private ExternalTool(Builder b) {
		this.id = b.id;
		this.description = b.description;
		this.args = List.copyOf(b.args);
		this.permissionNode = b.permissionNode;
		this.risk = b.risk;
		this.exposure = b.exposure;
		this.readOnlyHint = b.readOnlyHint;
		this.destructiveHint = b.destructiveHint;
		this.precondition = b.precondition;
		this.postcondition = b.postcondition;
		this.tags = List.copyOf(b.tags);
		this.aliases = List.copyOf(b.aliases);
		this.handler = Objects.requireNonNull(b.handler, "handler");
	}

	public static Builder builder(String id, ToolHandler handler) {
		return new Builder(id, handler);
	}

	public String id() {
		return id;
	}

	public String description() {
		return description;
	}

	public List<ArgSpec> args() {
		return args;
	}

	public String permissionNode() {
		return permissionNode;
	}

	public String risk() {
		return risk;
	}

	public String exposure() {
		return exposure;
	}

	public boolean readOnlyHint() {
		return readOnlyHint;
	}

	public boolean destructiveHint() {
		return destructiveHint;
	}

	public String precondition() {
		return precondition;
	}

	public String postcondition() {
		return postcondition;
	}

	public List<String> tags() { return tags; }
	public List<String> aliases() { return aliases; }

	public ToolHandler handler() {
		return handler;
	}

	public static final class Builder {
		private final String id;
		private final ToolHandler handler;
		private String description = "";
		private final List<ArgSpec> args = new java.util.ArrayList<>();
		private String permissionNode;
		private String risk = "MEDIUM";
		private String exposure = "MODEL_PUBLIC";
		private boolean readOnlyHint;
		private boolean destructiveHint;
		private String precondition = "none";
		private String postcondition = "none";
		private final List<String> tags = new java.util.ArrayList<>();
		private final List<String> aliases = new java.util.ArrayList<>();

		private Builder(String id, ToolHandler handler) {
			if (id == null || !id.matches("[a-z0-9_][a-z0-9_.-]*:[a-z0-9_][a-z0-9_-]*")) {
				throw new IllegalArgumentException(
					"tool id must be namespaced 'modid:name': " + id);
			}
			String namespace = id.substring(0, id.indexOf(':'));
			if (namespace.equals("minecraft") || namespace.equals("squire")) {
				throw new IllegalArgumentException("namespace is reserved: " + namespace);
			}
			this.id = id;
			this.handler = handler;
		}

		public Builder description(String description) {
			this.description = description == null ? "" : description;
			return this;
		}

		public Builder arg(ArgSpec arg) {
			args.add(Objects.requireNonNull(arg));
			return this;
		}

		public Builder permissionNode(String node) {
			this.permissionNode = node == null || node.isBlank() ? null : node;
			return this;
		}

		public Builder risk(String risk) {
			this.risk = switch (risk == null ? "" : risk.toUpperCase(java.util.Locale.ROOT)) {
				case "LOW", "MEDIUM", "HIGH" -> risk.toUpperCase(java.util.Locale.ROOT);
				default -> throw new IllegalArgumentException("risk must be LOW/MEDIUM/HIGH");
			};
			return this;
		}

		public Builder exposure(String exposure) {
			this.exposure = switch (exposure == null ? ""
					: exposure.toUpperCase(java.util.Locale.ROOT)) {
				case "MODEL_PUBLIC" -> "MODEL_PUBLIC";
				case "PLANNER_INTERNAL" -> "PLANNER_INTERNAL";
				case "ADMIN_ONLY" -> "ADMIN_ONLY";
				default -> throw new IllegalArgumentException(
					"exposure must be MODEL_PUBLIC/PLANNER_INTERNAL/ADMIN_ONLY");
			};
			return this;
		}

		public Builder readOnly(boolean hint) {
			this.readOnlyHint = hint;
			return this;
		}

		public Builder destructive(boolean hint) {
			this.destructiveHint = hint;
			return this;
		}

		public Builder precondition(String docs) {
			this.precondition = docs == null ? "none" : docs;
			return this;
		}

		public Builder postcondition(String docs) {
			this.postcondition = docs == null ? "none" : docs;
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

		public ExternalTool build() {
			return new ExternalTool(this);
		}
	}
}
