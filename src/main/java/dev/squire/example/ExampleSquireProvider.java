package dev.squire.example;

import java.util.List;
import java.util.Map;

import dev.squire.api.sensor.SensorContext;
import dev.squire.api.sensor.SensorDefinition;
import dev.squire.api.sensor.SensorResult;
import dev.squire.api.sensor.SquireSensor;
import dev.squire.api.sensor.SquireSensorProvider;
import dev.squire.api.tool.ArgSpec;
import dev.squire.api.tool.ExternalTool;
import dev.squire.api.tool.ExternalToolResult;
import dev.squire.api.tool.SquireToolProvider;
import dev.squire.api.tool.ToolInvocation;
import dev.squire.api.task.ExternalTaskExecutor;
import dev.squire.api.task.SquireTaskProvider;
import dev.squire.api.task.TaskTypeRegistrar;
import dev.squire.api.task.TaskTypeSpec;

/**
 * The spec §88 "Example Mod": written ONLY against {@code dev.squire.api.*}, loaded
 * through the {@code "squire"} entrypoint key, importing as UNTRUSTED. Proves third
 * parties can extend the platform without touching core — read-only tools work
 * immediately while privileged shapes stay denied until an admin approves them.
 */
public final class ExampleSquireProvider
		implements SquireToolProvider, SquireSensorProvider, SquireTaskProvider {

	/** Set by {@link #dangerousHandler()} when invoked — must stay 0 in tests. */
	public static final java.util.concurrent.atomic.AtomicInteger DANGEROUS_CALLS =
		new java.util.concurrent.atomic.AtomicInteger();

	@Override
	public void registerTools(dev.squire.api.tool.ToolRegistrar registrar) {
		registrar.register(ExternalTool.builder("example:echo", ExampleSquireProvider::echo)
			.description("Echoes the given message back (read-only example tool).")
			.arg(ArgSpec.requiredString("message", "text to echo"))
			.readOnly(true)
			.risk("LOW")
			.build());
		// deliberately privileged shape: untrusted + destructive + HIGH -> policy denies
		registrar.register(ExternalTool
			.builder("example:dangerous_write", ExampleSquireProvider::dangerous)
			.description("Would mutate the world if it were ever approved.")
			.readOnly(false)
			.destructive(true)
			.risk("HIGH")
			.build());
		// custom permission node: known after import, granted to NOBODY by default
		registrar.register(ExternalTool
			.builder("example:gated_echo", ExampleSquireProvider::echo)
			.description("Echo behind the example-owned permission node.")
			.arg(ArgSpec.requiredString("message", "text to echo"))
			.permissionNode("exampletools.gated.echo")
			.readOnly(true)
			.risk("LOW")
			.build());
	}

	private static ExternalToolResult echo(ToolInvocation invocation) {
		Object message = invocation.arguments().get("message");
		return ExternalToolResult.ok(invocation.callId(), Map.of("echo",
			String.valueOf(message)));
	}

	private static ExternalToolResult dangerous(ToolInvocation invocation) {
		DANGEROUS_CALLS.incrementAndGet();
		return ExternalToolResult.failed(invocation.callId(), "NOT_APPROVED",
			"this tool must never execute while its provider is untrusted");
	}

	@Override
	public List<SquireSensor> createSensors() {
		return List.of(new SquireSensor() {
			@Override
			public SensorDefinition definition() {
				return new SensorDefinition("example:heartbeat", 8, 16, 50, 256);
			}

			@Override
			public SensorResult observe(SensorContext context) {
				return SensorResult.of(Map.of(
					"tick", context.tick(),
					"agent", context.agentId().toString().substring(0, 8)));
			}
		});
	}

	@Override
	public void registerTaskTypes(TaskTypeRegistrar registrar) {
		registrar.register(
			new TaskTypeSpec("example:pause", List.of(), 100L,
				"Completes immediately; used to prove external task types run."),
			(ExternalTaskExecutor) invocation ->
				ExternalToolResult.ok(invocation.taskId(), Map.of("done", true)));
	}
}
