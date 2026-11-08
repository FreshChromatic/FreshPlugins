package github.freshchromatic.chunkrevive.bootstrap;

import github.freshchromatic.chunkrevive.feature.regeneration.NmsTerrainGenerator;
import github.freshchromatic.freshlib.util.Logging;
import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class ChunkReviveBootstrapTest {

    @Test
    void invalidConfigurationStopsStartupBeforeTerrainInitialization(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("config.yml"), "broken: [\n");
        var plugin = mock(ChunkRevivePlugin.class, RETURNS_DEEP_STUBS);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getPluginMeta().getVersion()).thenReturn("test");

        try (var logging = mockStatic(Logging.class); var terrain = mockStatic(NmsTerrainGenerator.class)) {
            logging.when(Logging::logger).thenReturn(mock(Logging.LoggerProxy.class));
            assertNull(ChunkReviveBootstrap.start(plugin));
            terrain.verifyNoInteractions();
        }
    }

    @Test
    void invalidDatabaseTypeStopsStartupAndReleasesTerrainResources(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("config.yml"), "database:\n  type: mysqll\n");
        var plugin = mock(ChunkRevivePlugin.class, RETURNS_DEEP_STUBS);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getPluginMeta().getVersion()).thenReturn("test");
        var pluginManager = mock(PluginManager.class);

        try (var logging = mockStatic(Logging.class);
            var terrain = mockStatic(NmsTerrainGenerator.class);
            var bukkit = mockStatic(Bukkit.class);
            var runtime = mockStatic(PluginRuntime.class)) {
            logging.when(Logging::logger).thenReturn(mock(Logging.LoggerProxy.class));
            bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);

            assertNull(ChunkReviveBootstrap.start(plugin));
            terrain.verify(NmsTerrainGenerator::shutdownPool);
        }
    }
}
