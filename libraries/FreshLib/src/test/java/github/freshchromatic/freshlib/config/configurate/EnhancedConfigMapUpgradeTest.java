package github.freshchromatic.freshlib.config.configurate;

import github.freshchromatic.freshlib.config.Config;
import github.freshchromatic.freshlib.util.Logging;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class EnhancedConfigMapUpgradeTest {

    @Test
    void addsMissingFieldsUnderMapKeysThatContainDots(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("config.yml");
        Files.writeString(path, "entries:\n  \"east.west\":\n    existing: custom\n");
        var manager = new EnhancedConfigManager<MapConfig, YamlConfigurationLoader>(path, MapConfig.class) {
        };

        try (var logging = mockStatic(Logging.class)) {
            logging.when(Logging::logger).thenReturn(mock(Logging.LoggerProxy.class));

            assertTrue(manager.tryLoad(true, false, null));
        }

        assertEquals("custom", manager.config().entries.get("east.west").existing);
        assertEquals("new default", manager.config().entries.get("east.west").newOption);
        var reloaded = YamlConfigurationLoader.builder().path(path).build().load();
        assertEquals("new default", reloaded.node("entries", "east.west", "new-option").getString());
    }

    @ConfigSerializable
    public static class MapConfig implements Config {

        public final Map<String, MapEntryConfig> entries = new LinkedHashMap<>();

        public MapConfig() {
            entries.put("east.west", new MapEntryConfig());
        }
    }

    @ConfigSerializable
    public static class MapEntryConfig {

        public String existing  = "default";
        public String newOption = "new default";
    }
}
