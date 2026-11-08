package github.freshchromatic.chunkrevive.application.api;

import github.freshchromatic.chunkrevive.api.model.RequestContext;
import github.freshchromatic.chunkrevive.feature.marking.MarkRegistry;
import github.freshchromatic.chunkrevive.feature.structure.StructureGroup;
import github.freshchromatic.chunkrevive.feature.structure.StructureService;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import org.bukkit.Bukkit;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class DefaultStructureApiTest {

    @Test
    void fatalPersistenceErrorEscapesStructureChangeApi() {
        Plugin plugin = mock(Plugin.class);
        var scheduler = mock(GlobalRegionScheduler.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        })
            .when(scheduler)
            .execute(any(), any());
        var structures = mock(StructureService.class);
        UUID groupId = UUID.randomUUID();
        when(structures.get(groupId)).thenReturn(Optional.of(new StructureGroup(groupId, "world", "village", 1, 1, 0, 0,
            0, 0, 0, false)));
        AssertionError fatal = new AssertionError("fatal persistence failure");
        when(structures.block(groupId)).thenReturn(CompletableFuture.failedFuture(fatal));
        var api = new DefaultStructureApi(plugin, plugin, structures, mock(MarkRegistry.class), mock(
            OperationCoordinator.class));

        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(scheduler);

            CompletionException failure = assertThrows(CompletionException.class, () ->
                api.block(groupId, RequestContext.system())
                    .toCompletableFuture()
                    .join());

            assertSame(fatal, failure.getCause());
        }
    }

    @Test
    void schedulerRejectionCompletesStructureChangeStageExceptionally() {
        Plugin plugin = mock(Plugin.class);
        var scheduler = mock(GlobalRegionScheduler.class);
        var rejection = new IllegalPluginAccessException("plugin disabled");
        org.mockito.Mockito.doThrow(rejection).when(scheduler).execute(any(), any());
        var api = new DefaultStructureApi(plugin, plugin, mock(StructureService.class), mock(MarkRegistry.class), mock(
            OperationCoordinator.class));

        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(scheduler);

            CompletionException failure = assertThrows(CompletionException.class, () ->
                api.block(UUID.randomUUID(), RequestContext.system())
                    .toCompletableFuture()
                    .join());

            assertSame(rejection, failure.getCause());
        }
    }
}
