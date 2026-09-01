package dev.squire.server.threading;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import net.minecraft.server.MinecraftServer;

/**
 * The only sanctioned bridge between worker threads and the server thread (spec section 7).
 *
 * <p>Worker threads may run HTTP/LLM/MCP/JSON/pure analysis. They must never touch
 * {@code World}/{@code Entity} references; results are marshalled back with
 * {@link #runOnServer(MinecraftServer, Runnable)} (= {@code server.execute}).</p>
 *
 * <p>M0-06 spike: proves async → server.execute → world change without main-thread blocking.</p>
 */
public final class AsyncBridge {
	/** Daemon workers: they must never keep the JVM/server alive on shutdown. */
	private static final ExecutorService WORKERS = Executors.newFixedThreadPool(2, r -> {
		Thread t = new Thread(r, "squire-worker");
		t.setDaemon(true);
		return t;
	});

	private AsyncBridge() {
	}

	/** Run a pure computation off-thread. The supplier must not capture world objects. */
	public static <T> CompletableFuture<T> onWorker(Supplier<T> task) {
		return CompletableFuture.supplyAsync(task, WORKERS);
	}

	/** Marshal a result back onto the server thread. Never blocks the caller. */
	public static void runOnServer(MinecraftServer server, Runnable apply) {
		server.execute(apply);
	}
}
