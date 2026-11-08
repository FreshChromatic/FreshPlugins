package github.freshchromatic.freshlib.config.configurate;

import github.freshchromatic.freshlib.config.Config;
import github.freshchromatic.freshlib.util.Logging;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class ConfigHeaderTest {

    @Test
    void usesManagerHeaderWhenConfigHasNone() {
        var manager = new EnhancedConfigManager<Config, YamlConfigurationLoader>(Path.of("config.yml"), Config.class) {

            @Override
            protected Config createInstance() {
                return new Config() {
                };
            }

            @Override
            protected String header() {
                return "fallback";
            }
        };

        assertEquals("fallback", manager.resolveHeader());
    }

    @Test
    void reportsConfigConstructionFailure() {
        var failure = new IllegalStateException("cannot create configuration");
        var manager = new EnhancedConfigManager<Config, YamlConfigurationLoader>(Path.of("config.yml"), Config.class) {

            @Override
            protected Config createInstance() {
                throw failure;
            }
        };

        assertSame(failure, assertThrows(IllegalStateException.class, manager::resolveHeader));
    }

    @Test
    void reportsSaveFailure(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("config.yml");
        var manager = new EnhancedConfigManager<TestConfig, YamlConfigurationLoader>(path, TestConfig.class) {
        };
        try (var logging = mockStatic(Logging.class)) {
            logging.when(Logging::logger).thenReturn(mock(Logging.LoggerProxy.class));
            manager.load(false, false, null);
            Files.delete(path);
            Files.createDirectory(path);

            assertThrows(IllegalStateException.class, manager::save);
        }
    }

    @Test
    void loadReportsFailureWhenRequiredSaveFails(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("config.yml");
        Files.writeString(path, "value: test\n");
        var manager = new EnhancedConfigManager<TestConfig, YamlConfigurationLoader>(path, TestConfig.class) {

            @Override
            protected void saveWithComments(ConfigurationNode node) throws ConfigurateException {
                throw new ConfigurateException("save failed");
            }
        };
        try (var logging = mockStatic(Logging.class)) {
            logging.when(Logging::logger).thenReturn(mock(Logging.LoggerProxy.class));
            assertFalse(manager.tryLoad(true, false, null));
        }
    }

    @Test
    void failedReloadKeepsPreviouslyLoadedConfiguration(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("config.yml");
        Files.writeString(path, "value: original\n");
        class TestManager extends EnhancedConfigManager<TestConfig, YamlConfigurationLoader> {

            boolean failSave;

            TestManager() {
                super(path, TestConfig.class);
            }

            @Override
            protected void saveWithComments(ConfigurationNode node) throws ConfigurateException {
                if (failSave) {
                    throw new ConfigurateException("save failed");
                }
                super.saveWithComments(node);
            }
        }
        var manager = new TestManager();
        try (var logging = mockStatic(Logging.class)) {
            logging.when(Logging::logger).thenReturn(mock(Logging.LoggerProxy.class));
            assertTrue(manager.tryLoad(false, false, null));
            TestConfig original = manager.config();
            Files.writeString(path, "value: changed\n");
            manager.failSave = true;

            assertFalse(manager.tryLoad(true, false, null));
            assertSame(original, manager.config());
            assertEquals("original", manager.config().value);
        }
    }

    @Test
    void savingCachePersistsItsCurrentValue(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("config.yml");
        var manager = new EnhancedConfigManager<TestConfig, YamlConfigurationLoader>(path, TestConfig.class) {
        };
        try (var logging = mockStatic(Logging.class)) {
            logging.when(Logging::logger).thenReturn(mock(Logging.LoggerProxy.class));
            assertTrue(manager.tryLoad(false, false, null));
            var updated = new TestConfig();
            updated.value = "updated";
            var cache = manager.getOrCreateCache();
            var notifications = new AtomicInteger();
            cache.addChangeListener("test", ignored -> notifications.incrementAndGet());
            cache.set(updated);

            cache.save();

            assertSame(updated, manager.config());
            assertSame(updated, cache.get());
            assertEquals(1, notifications.get());
            assertTrue(Files.readString(path).contains("value: updated"));
        }
    }

    @ConfigSerializable
    public static class TestConfig implements Config {

        public String value = "default";
    }
}
