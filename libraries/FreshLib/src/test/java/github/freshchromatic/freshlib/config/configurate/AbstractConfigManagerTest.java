package github.freshchromatic.freshlib.config.configurate;

import github.freshchromatic.freshlib.config.Config;
import github.freshchromatic.freshlib.util.Logging;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class AbstractConfigManagerTest {

    @Test
    void preservesEmptyScalarValuesWhenMergingDefaults(@TempDir Path directory) throws IOException {
        Path configFile = directory.resolve("config.yml");
        Files.writeString(configFile, "permission: ''\n");

        ConfigurateConfigManager<TestConfig> manager = new ConfigurateConfigManager<>(configFile, TestConfig.class);
        manager.load();

        assertEquals("", manager.config().permission);
    }

    @Test
    void loadReportsFailureWhenSavingFails(@TempDir Path directory) throws IOException {
        Path path = directory.resolve("config.yml");
        Files.writeString(path, "permission: example\n");
        var manager = new ConfigurateConfigManager<TestConfig>(path, TestConfig.class) {

            @Override
            protected void save(ConfigurationNode node) throws ConfigurateException {
                throw new ConfigurateException("save failed");
            }
        };
        try (var logging = mockStatic(Logging.class)) {
            logging.when(Logging::logger).thenReturn(mock(Logging.LoggerProxy.class));
            assertFalse(manager.tryLoad());
            assertThrows(IllegalStateException.class, manager::save);
        }
    }

    @Test
    void failedReloadKeepsPreviouslyLoadedConfiguration(@TempDir Path directory) throws IOException {
        Path configPath = directory.resolve("config.yml");
        Files.writeString(configPath, "permission: original\n");
        class TestManager extends ConfigurateConfigManager<TestConfig> {

            boolean failSave;

            TestManager() {
                super(configPath, TestConfig.class);
            }

            @Override
            protected void save(ConfigurationNode node) throws ConfigurateException {
                if (failSave) {
                    throw new ConfigurateException("save failed");
                }
                super.save(node);
            }
        }
        var manager = new TestManager();
        try (var logging = mockStatic(Logging.class)) {
            logging.when(Logging::logger).thenReturn(mock(Logging.LoggerProxy.class));
            assertTrue(manager.tryLoad());
            TestConfig original = manager.config();
            Files.writeString(configPath, "permission: changed\n");
            manager.failSave = true;

            assertFalse(manager.tryLoad());
            assertSame(original, manager.config());
            assertEquals("original", manager.config().permission);
        }
    }

    @Test
    void preparedConfigurationDoesNotReplaceCurrentUntilApplied(@TempDir Path directory) throws IOException {
        Path path = directory.resolve("config.yml");
        Files.writeString(path, "permission: original\n");
        var manager = new ConfigurateConfigManager<>(path, TestConfig.class);
        try (var logging = mockStatic(Logging.class)) {
            logging.when(Logging::logger).thenReturn(mock(Logging.LoggerProxy.class));
            assertTrue(manager.tryLoad());
            TestConfig original = manager.config();
            Files.writeString(path, "permission: changed\n");
            TestConfig candidate = manager.loadCandidate().orElseThrow();

            assertSame(original, manager.config());
            manager.applyLoaded(candidate);
            assertSame(candidate, manager.config());
            assertEquals("changed", manager.config().permission);
        }
    }

    @ConfigSerializable
    public static final class TestConfig implements Config {

        public String permission = "default.permission";
    }
}
