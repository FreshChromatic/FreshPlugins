package github.freshchromatic.freshlib.config;

import github.freshchromatic.freshlib.util.Logging;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class ConfigManagerTest {

    @Test
    void failedReloadPreservesCurrentConfigAndMessages(@TempDir Path directory) throws Exception {
        var plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        try (var logging = mockStatic(Logging.class)) {
            logging.when(Logging::logger).thenReturn(mock(Logging.LoggerProxy.class));
            var manager = new ConfigManager(plugin);
            var config = manager.config();
            var messages = manager.messages();

            Files.writeString(directory.resolve("config.yml"), "broken: [\n");
            assertFalse(manager.tryReload());
            assertSame(config, manager.config());
            assertSame(messages, manager.messages());

            Files.writeString(directory.resolve("config.yml"), "{}\n");
            Files.writeString(directory.resolve("locale/lang-en.yml"), "broken: [\n");
            assertFalse(manager.tryReload());
            assertSame(config, manager.config());
            assertSame(messages, manager.messages());

            Files.writeString(directory.resolve("config.yml"), "settings:\n  language-file: ../outside.yml\n");
            assertFalse(manager.tryReload());
            assertSame(config, manager.config());
            assertSame(messages, manager.messages());

            Files.writeString(directory.resolve("config.yml"), "{}\n");
            Files.writeString(directory.resolve("locale/lang-en.yml"), "{}\n");
            assertTrue(manager.tryReload());
            assertNotSame(config, manager.config());
            assertNotSame(messages, manager.messages());
        }
    }

    @Test
    void invalidInitialFilesStillProvideDefaults(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("config.yml"), "broken: [\n");
        Files.createDirectories(directory.resolve("locale"));
        Files.writeString(directory.resolve("locale/lang-en.yml"), "broken: [\n");
        var plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        try (var logging = mockStatic(Logging.class)) {
            logging.when(Logging::logger).thenReturn(mock(Logging.LoggerProxy.class));
            var manager = new ConfigManager(plugin);
            assertNotNull(manager.config());
            assertNotNull(manager.messages());
        }
    }
}
