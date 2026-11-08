package github.freshchromatic.freshlib.config;

import github.freshchromatic.freshlib.util.Logging;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class EnhancedConfigManagerTest {

    @Test
    void firstLoadFailureFallsBackToDefaultsWithoutOverwritingFiles(@TempDir Path directory) throws Exception {
        Path configPath = directory.resolve("config.yml");
        Path messagesPath = directory.resolve("locale/lang-en.yml");
        Files.createDirectories(messagesPath.getParent());
        Files.writeString(configPath, "broken: [\n");
        Files.writeString(messagesPath, "broken: [\n");
        var plugin = mock(JavaPlugin.class);
        var description = mock(PluginDescriptionFile.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getDescription()).thenReturn(description);
        when(description.getVersion()).thenReturn("test");

        try (MockedStatic<Logging> logging = mockStatic(Logging.class)) {
            logging.when(Logging::logger).thenReturn(mock(Logging.LoggerProxy.class));
            var manager = new EnhancedConfigManager(plugin);
            try {
                assertTrue(manager.isInitialized());
                assertNotNull(manager.config());
                assertNotNull(manager.messages());
                assertEquals("broken: [\n", Files.readString(configPath));
                assertEquals("broken: [\n", Files.readString(messagesPath));
            } finally {
                manager.shutdown();
            }
        }
    }

    @Test
    void reloadReplacesAndClosesWatcherAndPreservesDisabledState(@TempDir Path directory) throws Exception {
        var plugin = mock(JavaPlugin.class);
        var description = mock(PluginDescriptionFile.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getDescription()).thenReturn(description);
        when(description.getVersion()).thenReturn("test");

        try (MockedStatic<Logging> logging = mockStatic(Logging.class)) {
            logging.when(Logging::logger).thenReturn(mock(Logging.LoggerProxy.class));
            var manager = new EnhancedConfigManager(plugin);
            try {
                var original = currentConfigManager(manager);
                assertTrue(original.getOrCreateCache().isWatching());
                var initiallyLoaded = manager.config();
                assertSame(initiallyLoaded, original.getOrCreateCache().get());
                assertSame(initiallyLoaded, manager.config());
                manager.disableAutoReload();
                Files.writeString(original.getConfigPath(), "automatic-configuration-upgrade: false\n");
                original.load(false, false, null);
                assertNotSame(initiallyLoaded, original.config());
                assertSame(original.config(), original.getOrCreateCache().get());
                var loadedFromDisk = original.config();
                Files.delete(original.getConfigPath());
                original.load(false, false, null);
                assertNotSame(loadedFromDisk, original.config());
                assertSame(original.config(), original.getOrCreateCache().get());
                manager.enableAutoReload();
                manager.reload();
                var replacement = currentConfigManager(manager);
                assertNotSame(original, replacement);
                assertFalse(original.getOrCreateCache().isWatching());
                assertTrue(replacement.getOrCreateCache().isWatching());
                var currentConfig = manager.config();
                Files.writeString(replacement.getConfigPath(), "broken: [\n");
                assertFalse(replacement.tryLoad(false, false, null));
                assertTrue(replacement.isInitialized());
                assertSame(currentConfig, replacement.config());
                manager.reload();
                assertSame(replacement, currentConfigManager(manager));
                assertSame(currentConfig, manager.config());
                assertTrue(replacement.getOrCreateCache().isWatching());
                Files.writeString(replacement.getConfigPath(), "{}\n");
                manager.disableAutoReload();
                manager.reload();
                assertFalse(replacement.getOrCreateCache().isWatching());
                var disabled = currentConfigManager(manager);
                assertFalse(disabled.getOrCreateCache().isWatching());
                manager.enableAutoReload();
                assertTrue(disabled.getOrCreateCache().isWatching());
                manager.shutdown();
                assertFalse(disabled.getOrCreateCache().isWatching());
            } finally {
                manager.shutdown();
            }
        }
    }

    @Test
    void failedTranslationReloadKeepsCurrentConfigurationAndMessages(@TempDir Path directory) throws Exception {
        var plugin = mock(JavaPlugin.class);
        var description = mock(PluginDescriptionFile.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getDescription()).thenReturn(description);
        when(description.getVersion()).thenReturn("test");

        try (MockedStatic<Logging> logging = mockStatic(Logging.class)) {
            logging.when(Logging::logger).thenReturn(mock(Logging.LoggerProxy.class));
            var manager = new EnhancedConfigManager(plugin);
            try {
                var config = manager.config();
                var messages = manager.messages();
                var current = currentConfigManager(manager);
                Files.writeString(directory.resolve("locale/lang-en.yml"), "broken: [\n");

                manager.reload();

                assertSame(config, manager.config());
                assertSame(messages, manager.messages());
                assertSame(current, currentConfigManager(manager));
                assertTrue(current.getOrCreateCache().isWatching());
            } finally {
                manager.shutdown();
            }
        }
    }

    @Test
    void invalidLanguageFileKeepsCurrentConfigurationAndWatcher(@TempDir Path directory) throws Exception {
        var plugin = mock(JavaPlugin.class);
        var description = mock(PluginDescriptionFile.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getDescription()).thenReturn(description);
        when(description.getVersion()).thenReturn("test");

        try (MockedStatic<Logging> logging = mockStatic(Logging.class)) {
            logging.when(Logging::logger).thenReturn(mock(Logging.LoggerProxy.class));
            var manager = new EnhancedConfigManager(plugin);
            try {
                var config = manager.config();
                var messages = manager.messages();
                var current = currentConfigManager(manager);
                Files.writeString(current.getConfigPath(), "settings:\n  language-file: ../outside.yml\n");

                manager.reload();

                assertSame(config, manager.config());
                assertSame(messages, manager.messages());
                assertSame(current, currentConfigManager(manager));
                assertTrue(current.getOrCreateCache().isWatching());
            } finally {
                manager.shutdown();
            }
        }
    }

    @Test
    void cacheReportsSuccessOnlyAfterSuccessfulLoad(@TempDir Path directory) throws Exception {
        var plugin = mock(JavaPlugin.class);
        var description = mock(PluginDescriptionFile.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getDescription()).thenReturn(description);
        when(description.getVersion()).thenReturn("test");

        try (MockedStatic<Logging> logging = mockStatic(Logging.class)) {
            var logger = mock(Logging.LoggerProxy.class);
            logging.when(Logging::logger).thenReturn(logger);
            var manager = new EnhancedConfigManager(plugin);
            try {
                manager.disableAutoReload();
                var current = currentConfigManager(manager);
                var original = manager.config();
                Files.writeString(current.getConfigPath(), "broken: [\n");

                current.getOrCreateCache().load();
                current.reload();

                assertSame(original, manager.config());
                assertSame(original, current.getOrCreateCache().get());
                verify(logger, never()).info("Configuration reloaded: config.yml");
                Files.writeString(current.getConfigPath(), "{}\n");
                current.getOrCreateCache().load();
                verify(logger).info("Configuration reloaded: config.yml");
            } finally {
                manager.shutdown();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private github.freshchromatic.freshlib.config.configurate.EnhancedConfigManager<FreshLibConfig, YamlConfigurationLoader> currentConfigManager(
        EnhancedConfigManager manager) throws ReflectiveOperationException {
        Field field = EnhancedConfigManager.class.getDeclaredField("mainConfigManager");
        field.setAccessible(true);
        return (github.freshchromatic.freshlib.config.configurate.EnhancedConfigManager<FreshLibConfig, YamlConfigurationLoader>) field.get(
            manager);
    }
}
