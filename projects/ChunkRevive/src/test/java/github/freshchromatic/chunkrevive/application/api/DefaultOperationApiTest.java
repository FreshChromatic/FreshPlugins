package github.freshchromatic.chunkrevive.application.api;

import github.freshchromatic.chunkrevive.api.operation.OperationId;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import org.bukkit.Bukkit;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class DefaultOperationApiTest {

    @Test
    void schedulerRejectionCompletesCancelStageExceptionally() {
        Plugin plugin = mock(Plugin.class);
        var scheduler = mock(GlobalRegionScheduler.class);
        var rejection = new IllegalPluginAccessException("plugin disabled");
        doThrow(rejection).when(scheduler).execute(any(), any());
        var api = new DefaultOperationApi(plugin, mock(OperationCoordinator.class));

        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(scheduler);

            CompletionException failure = assertThrows(CompletionException.class, () ->
                api.cancel(new OperationId(
                UUID.randomUUID()))
                    .toCompletableFuture()
                    .join());

            assertSame(rejection, failure.getCause());
        }
    }
}
