package github.freshchromatic.chunkrevive.application.api;

import github.freshchromatic.chunkrevive.api.worldgen.GeneratorSupportLevel;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DefaultWorldGeneratorCompatibilityApiTest {

    @Test
    void queriesUseCachedSnapshotsWithoutCallingBukkitWorldFromCallerThread() {
        var world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(world.getGenerator()).thenReturn(null);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getSeed()).thenReturn(42L);
        when(world.getKey()).thenReturn(NamespacedKey.minecraft("world"));
        var api = new DefaultWorldGeneratorCompatibilityApi();
        api.recordWorld(world);
        clearInvocations(world);

        var result = CompletableFuture.supplyAsync(() -> api.inspect("world")).join();

        assertEquals(GeneratorSupportLevel.NATIVE, result.supportLevel());
        assertEquals(result, api.worlds().iterator().next());
        verifyNoInteractions(world);
    }

    @Test
    void inspectRejectsBlankWorldNames() {
        var api = new DefaultWorldGeneratorCompatibilityApi();

        assertThrows(IllegalArgumentException.class, () -> api.inspect(" "));
    }

    @Test
    void startupSnapshotsLoadedWorldsAndRegistersLifecycleListener() {
        var world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(world.getGenerator()).thenReturn(null);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getSeed()).thenReturn(42L);
        when(world.getKey()).thenReturn(NamespacedKey.minecraft("world"));
        var plugin = mock(Plugin.class);
        var pluginManager = mock(PluginManager.class);
        var api = new DefaultWorldGeneratorCompatibilityApi();

        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getWorlds).thenReturn(List.of(world));
            bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);

            api.start(plugin);

            assertEquals(GeneratorSupportLevel.NATIVE, api.inspect("world").supportLevel());
            verify(pluginManager).registerEvents(api, plugin);
            api.stop();
            assertEquals("API_UNAVAILABLE", api.inspect("world").reasonCode().orElseThrow());
        }
    }
}
