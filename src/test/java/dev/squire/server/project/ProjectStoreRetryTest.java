package dev.squire.server.project;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProjectStoreRetryTest {
    @Test void briefWindowsFileLockDoesNotAbortCheckpoint() throws IOException {
        var attempts = new AtomicInteger();
        ProjectStore.retryAccessDenied(() -> {
            if (attempts.incrementAndGet() < 3) throw new AccessDeniedException("projects.json");
        });
        assertEquals(3, attempts.get());
    }
    @Test void persistentDeniedWriteStillFailsAfterBoundedRetries() {
        var attempts = new AtomicInteger();
        assertThrows(AccessDeniedException.class, () -> ProjectStore.retryAccessDenied(() -> {
            attempts.incrementAndGet(); throw new AccessDeniedException("projects.json");
        }));
        assertEquals(5, attempts.get());
    }
    @Test void otherIoErrorsAreNotHidden() {
        var attempts = new AtomicInteger();
        assertThrows(IOException.class, () -> ProjectStore.retryAccessDenied(() -> {
            attempts.incrementAndGet(); throw new IOException("disk full");
        }));
        assertEquals(1, attempts.get());
    }
}
