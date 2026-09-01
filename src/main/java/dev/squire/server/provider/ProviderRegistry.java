package dev.squire.server.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dev.squire.api.provider.LlmProvider;

/**
 * Holds the active {@link LlmProvider}. Starts empty — the whole mod is functional
 * with none configured (M1 DoD "LLM 不可用时上述能力正常"). Registration happens via
 * config or a test hook; nothing here ever falls back to raw commands.
 */
public final class ProviderRegistry {
	public record Registered(LlmProvider provider, String trustDomain,
			LlmSettings.DataBoundary dataBoundary) { }

	private static volatile List<Registered> chain = List.of();
	private static volatile boolean allowLocalToCloudFailover;

	private ProviderRegistry() {
	}

	public static void register(LlmProvider provider) {
		if (provider == null) {
			throw new IllegalArgumentException("provider must not be null");
		}
		chain = List.of(new Registered(provider, provider.id(),
			LlmSettings.DataBoundary.LOCAL));
		allowLocalToCloudFailover = false;
	}

	public static void registerChain(List<Registered> providers,
			boolean allowLocalToCloud) {
		if (providers == null || providers.isEmpty()) {
			throw new IllegalArgumentException("provider chain must not be empty");
		}
		List<Registered> checked = new ArrayList<>();
		for (Registered entry : providers) {
			if (entry == null || entry.provider() == null) {
				throw new IllegalArgumentException("provider chain contains null");
			}
			checked.add(entry);
		}
		chain = List.copyOf(checked);
		allowLocalToCloudFailover = allowLocalToCloud;
	}

	public static void clear() {
		chain = List.of();
		allowLocalToCloudFailover = false;
	}

	public static Optional<LlmProvider> current() {
		return chain.isEmpty() ? Optional.empty() : Optional.of(chain.get(0).provider());
	}

	public static List<Registered> configured() { return chain; }

	/** Next provider allowed by the configured trust/data-boundary policy. */
	public static Optional<LlmProvider> failoverAfter(String currentId) {
		List<Registered> snapshot = chain;
		int currentIndex = -1;
		for (int i = 0; i < snapshot.size(); i++) {
			if (snapshot.get(i).provider().id().equals(currentId)) {
				currentIndex = i;
				break;
			}
		}
		if (currentIndex < 0) return Optional.empty();
		Registered current = snapshot.get(currentIndex);
		for (int i = currentIndex + 1; i < snapshot.size(); i++) {
			Registered candidate = snapshot.get(i);
			boolean localToCloud = current.dataBoundary() == LlmSettings.DataBoundary.LOCAL
				&& candidate.dataBoundary() == LlmSettings.DataBoundary.CLOUD;
			boolean sameTrust = current.trustDomain().equals(candidate.trustDomain());
			if ((!localToCloud && sameTrust)
					|| (localToCloud && allowLocalToCloudFailover)) {
				return Optional.of(candidate.provider());
			}
		}
		return Optional.empty();
	}

	/** True when conversational input can reach an LLM at all. */
	public static boolean available() {
		return !chain.isEmpty();
	}
}
