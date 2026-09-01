package dev.squire.server.provider;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import dev.squire.common.protocol.ToolDescriptor;

/**
 * Connects the configured {@link LlmConfig} to the {@link ProviderRegistry}.
 * Server-admin territory: the config file and its secret live in server-owner
 * data ({@code config/squire/llm.json}); players never touch any of it.
 *
 * <p>Failure philosophy: no file / invalid file = degraded mode (fastpath-only),
 * logged once — never a crash, never a half-wired client. {@link #rewire}
 * keeps an already-active provider when a reload fails closed.</p>
 */
public final class LlmWiring {

	private LlmWiring() {
	}

	/** Wires at startup; overwrites whatever is registered (registry is empty). */
	public static boolean wire(Path configFile,
			Function<UUID, List<ToolDescriptor>> modelVisibleTools) {
		Optional<LlmSettings> settings = LlmSettings.load(configFile);
		if (settings.isEmpty()) {
			dev.squire.SquireMod.LOGGER.info(
				"[Squire] no valid {} — degraded mode (control phrases still work)",
				configFile.getFileName());
			return false;
		}
		apply(settings.get(), modelVisibleTools);
		return true;
	}

	/** Admin reload: on failure keeps the current provider and reports false. */
	public static boolean rewire(Path configFile,
			Function<UUID, List<ToolDescriptor>> modelVisibleTools) {
		Optional<LlmSettings> settings = LlmSettings.load(configFile);
		if (settings.isEmpty()) {
			return false;
		}
		apply(settings.get(), modelVisibleTools);
		return true;
	}

	private static void apply(LlmSettings settings,
			Function<UUID, List<ToolDescriptor>> modelVisibleTools) {
		List<ProviderRegistry.Registered> chain = new java.util.ArrayList<>();
		for (LlmSettings.Entry entry : settings.ordered()) {
			OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(
				entry.name(), entry.config(), new JdkHttpTransport(), modelVisibleTools,
				LlmWiring::capabilitySummaryOf);
			chain.add(new ProviderRegistry.Registered(provider, entry.trustDomain(),
				entry.dataBoundary()));
			dev.squire.SquireMod.LOGGER.info(
				"[Squire] LLM provider configured: {} model={} endpoint={} boundary={} (key masked: {})",
				entry.name(), entry.config().model(), entry.config().baseUrl(),
				entry.dataBoundary(), entry.config());
		}
		ProviderRegistry.registerChain(chain, settings.allowLocalToCloudFailover());
	}

	/**
	 * 这只随从的能力边界摘要（§23）。运行时还没起来时给空串。
	 *
	 * <p>放在这里而不是让 provider 直接引用运行时：provider 是可替换的适配器，
	 * 不该知道存档长什么样。</p>
	 */
	private static String capabilitySummaryOf(UUID agentId) {
		if (agentId == null) {
			return "";
		}
		try {
			var runtime = dev.squire.server.runtime.SquireRuntime.get();
			return runtime == null ? ""
				: dev.squire.server.tool.ToolGate.capabilitySummary(
					runtime.professionOfAgent(agentId));
		} catch (RuntimeException notReady) {
			return ""; // 运行时还没起来（最小/测试环境）：少一段说明，不是错误
		}
	}

	/** Masked status lines for the admin inspector — never contains the key. */
	public static List<String> statusLines(Path configFile) {
		var current = ProviderRegistry.current();
		String active = current.map(p -> p.id()).orElse("(none)");
		Optional<LlmSettings> settings = LlmSettings.load(configFile);
		String fileState = settings.isEmpty() ? "absent or invalid"
			: "valid (" + settings.get().providers().size() + " provider(s), primary="
				+ settings.get().primary() + ")";
		return List.of("active provider: " + active,
			"config file: " + fileState,
			"failover chain: " + ProviderRegistry.configured().stream()
				.map(entry -> entry.provider().id() + "/" + entry.dataBoundary())
				.toList(),
			"capabilities: "
				+ current.map(p -> p.capabilities().toString()).orElse("(none)"));
	}
}
