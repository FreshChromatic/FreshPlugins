package github.freshchromatic.chunkrevive.config;

import github.freshchromatic.freshlib.config.configurate.ConfigurateConfigManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MemorySafetyConfigMigrationTest {

    @Test
    void legacyNumericLimitsLoadAsStrategyStrings(@TempDir Path directory) throws Exception {
        Path configPath = directory.resolve("config.yml");
        Files.writeString(configPath, """
            regen:
              memory-safety:
                max-active-batches: 1
                max-chunks-per-batch: 8
                max-generation-threads: 4
            """);

        var manager = new ConfigurateConfigManager<>(configPath, PluginConfig.class);
        manager.load();

        assertEquals("1", manager.config().regen.memorySafety.maxActiveBatches);
        assertEquals("8", manager.config().regen.memorySafety.maxChunksPerBatch);
        assertEquals("4", manager.config().regen.memorySafety.maxGenerationThreads);
    }
}
