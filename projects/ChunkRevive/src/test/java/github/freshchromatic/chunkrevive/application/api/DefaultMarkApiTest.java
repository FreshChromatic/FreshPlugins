package github.freshchromatic.chunkrevive.application.api;

import github.freshchromatic.chunkrevive.api.model.ChunkKey;
import github.freshchromatic.chunkrevive.api.model.RequestContext;
import github.freshchromatic.chunkrevive.api.mark.MarkChangeStatus;
import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.config.WorldAccessPolicy;
import github.freshchromatic.chunkrevive.feature.marking.MarkRegistry;
import github.freshchromatic.chunkrevive.feature.marking.ProtectedMarkExecutor;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import org.bukkit.Bukkit;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class DefaultMarkApiTest {

    @Test
    void markChecksWorldOnGlobalScheduler() {
        Plugin plugin = mock(Plugin.class);
        var scheduler = mock(GlobalRegionScheduler.class);
        var scheduledTask = new AtomicReference<Runnable>();
        doAnswer(invocation -> {
            scheduledTask.set(invocation.getArgument(1));
            return null;
        })
            .when(scheduler)
            .execute(any(), any());
        var api = new DefaultMarkApi(plugin, plugin, mock(MarkRegistry.class), new WorldAccessPolicy(
            new PluginConfig()), mock(ProtectedMarkExecutor.class));
        var worldLookedUp = new AtomicBoolean();

        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(scheduler);
            bukkit.when(() -> Bukkit.getWorld("world")).thenAnswer(invocation -> {
                worldLookedUp.set(true);
                return null;
            });

            var result = api.mark(new ChunkKey("world", 1, 2), RequestContext.system());

            assertFalse(worldLookedUp.get());
            scheduledTask.get().run();

            assertEquals(MarkChangeStatus.WORLD_NOT_FOUND, result.toCompletableFuture().join().status());
            assertTrue(worldLookedUp.get());
        }
    }

    @Test
    void schedulerRejectionCompletesUnmarkStageExceptionally() {
        Plugin plugin = mock(Plugin.class);
        var scheduler = mock(GlobalRegionScheduler.class);
        var rejection = new IllegalPluginAccessException("plugin disabled");
        doThrow(rejection).when(scheduler).execute(any(), any());
        var api = new DefaultMarkApi(plugin, plugin, mock(MarkRegistry.class), new WorldAccessPolicy(
            new PluginConfig()), mock(ProtectedMarkExecutor.class));

        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(scheduler);

            CompletionException failure = assertThrows(CompletionException.class, () ->
                api.unmark(new ChunkKey("world",
                1, 2), RequestContext.system())
                    .toCompletableFuture()
                    .join());

            assertSame(rejection, failure.getCause());
        }
    }
}
