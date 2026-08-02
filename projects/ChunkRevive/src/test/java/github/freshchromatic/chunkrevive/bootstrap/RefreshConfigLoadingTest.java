package github.freshchromatic.chunkrevive.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefreshConfigLoadingTest {

    @Test
    void emptyListLoadsAndRemainsAnEmptyBlacklist(@TempDir Path directory) throws Exception {
        Path configPath = directory.resolve("config.yml");
        Files.writeString(configPath, """
            structure:
              refresh:
                enabled: true
                list-mode: BLACKLIST
                default-interval-days: 7
                tracked-structures: []
            """);

        var manager = new ChunkReviveConfigManager(configPath, "test");
        manager.load();

        assertTrue(manager.config().structure.refresh.trackedStructures.isEmpty());
        assertTrue(manager.config().structure.refresh.isTracked("minecraft:village"));

        manager.load();
        assertTrue(manager.config().structure.refresh.trackedStructures.isEmpty());
    }

    @Test
    void blacklistExcludesOnlyConfiguredEntries(@TempDir Path directory) throws Exception {
        Path configPath = directory.resolve("config.yml");
        Files.writeString(configPath, """
            structure:
              refresh:
                list-mode: BLACKLIST
                tracked-structures:
                  minecraft:fortress: 14
            """);

        var manager = new ChunkReviveConfigManager(configPath, "test");
        manager.load();

        assertFalse(manager.config().structure.refresh.isTracked("minecraft:fortress"));
        assertTrue(manager.config().structure.refresh.isTracked("minecraft:village"));
    }
}
