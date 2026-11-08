package github.freshchromatic.freshlib.config.configurate.cache;

import github.freshchromatic.freshlib.util.Logging;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class ConfigCacheReloadTest {

    @Test
    void rejectsNonpositiveDebounceIntervals(@TempDir Path directory) {
        Path configPath = directory.resolve("config.yml");

        assertThrows(IllegalArgumentException.class, () -> new ConfigCache<String>(configPath, ignored -> {
        }, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new ConfigCache<String>(configPath, ignored -> {
        }, null, -1));
    }

    @Test
    void doesNotRetryAnUnchangedFileAfterReloadAttempt(@TempDir Path directory) throws Exception {
        Path configPath = directory.resolve("config.yml");
        Files.writeString(configPath, "value: initial\n");
        AtomicInteger loadAttempts = new AtomicInteger();
        var cache = new ConfigCache<String>(configPath, ignored -> loadAttempts.incrementAndGet(), null);
        long changedTimestamp = Files.getLastModifiedTime(configPath).toMillis() + 5_000;
        Files.setLastModifiedTime(configPath, FileTime.fromMillis(changedTimestamp));

        try (var logging = mockStatic(Logging.class)) {
            logging.when(Logging::logger).thenReturn(mock(Logging.LoggerProxy.class));
            cache.checkForChanges();
            cache.checkForChanges();

            assertEquals(1, loadAttempts.get());

            Files.setLastModifiedTime(configPath, FileTime.fromMillis(changedTimestamp + 5_000));
            cache.checkForChanges();

            assertEquals(2, loadAttempts.get());
        }
    }

    @Test
    void detectsFileChangesWhenTimestampMovesBackwards(@TempDir Path directory) throws Exception {
        Path configPath = directory.resolve("config.yml");
        Files.writeString(configPath, "value: initial\n");
        AtomicInteger loadAttempts = new AtomicInteger();
        var cache = new ConfigCache<String>(configPath, ignored -> loadAttempts.incrementAndGet(), null);
        FileTime earlierTimestamp = FileTime.fromMillis(Files.getLastModifiedTime(configPath).toMillis() - 10_000);
        Files.setLastModifiedTime(configPath, earlierTimestamp);

        try (var logging = mockStatic(Logging.class)) {
            logging.when(Logging::logger).thenReturn(mock(Logging.LoggerProxy.class));
            cache.checkForChanges();

            assertEquals(1, loadAttempts.get());
        }
    }

    @Test
    void detectsFileCreatedWithAnOlderTimestamp(@TempDir Path directory) throws Exception {
        Path configPath = directory.resolve("config.yml");
        AtomicInteger loadAttempts = new AtomicInteger();
        var cache = new ConfigCache<String>(configPath, ignored -> loadAttempts.incrementAndGet(), null);
        Files.writeString(configPath, "value: initial\n");
        Files.setLastModifiedTime(configPath, FileTime.fromMillis(1_000));

        try (var logging = mockStatic(Logging.class)) {
            logging.when(Logging::logger).thenReturn(mock(Logging.LoggerProxy.class));
            cache.checkForChanges();

            assertEquals(1, loadAttempts.get());
        }
    }
}
