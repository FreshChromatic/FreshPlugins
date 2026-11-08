package github.freshchromatic.chunkrevive.presentation.command;

import github.freshchromatic.chunkrevive.bootstrap.ChunkRevivePlugin;
import github.freshchromatic.chunkrevive.config.Messages;
import github.freshchromatic.chunkrevive.feature.marking.MarkRegistry;
import github.freshchromatic.chunkrevive.feature.reset.DeletionService;
import github.freshchromatic.chunkrevive.feature.scanning.DiskChunkScanner;
import github.freshchromatic.freshlib.command.Commander;
import github.freshchromatic.freshlib.scheduler.Scheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.incendo.cloud.context.CommandContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminCommandsTest {

    @Test
    @SuppressWarnings("unchecked")
    void regionsCountLooksUpWorldAfterGlobalDispatchAndCountsAsync() {
        var plugin = mock(ChunkRevivePlugin.class);
        var markRegistry = mock(MarkRegistry.class);
        var scanner = mock(DiskChunkScanner.class);
        var deletionService = mock(DeletionService.class);
        var sender = mock(Commander.class);
        CommandContext<Commander> context = mock(CommandContext.class);
        when(context.sender()).thenReturn(sender);
        when(context.optional("world")).thenReturn(Optional.of("target-world"));
        var world = mock(World.class);
        var globalScheduler = mock(GlobalRegionScheduler.class);
        when(scanner.countRegionFilesAsync(world, null)).thenReturn(CompletableFuture.completedFuture(12));
        var commands = new AdminCommands(plugin, markRegistry, scanner, deletionService, new Messages());
        ArgumentCaptor<Runnable> action = ArgumentCaptor.forClass(Runnable.class);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
            MockedStatic<Scheduler> scheduler = mockStatic(Scheduler.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(globalScheduler);
            bukkit.when(() -> Bukkit.getWorld("target-world")).thenReturn(world);
            scheduler.when(Scheduler::isGlobalTickThread).thenReturn(false);

            commands.regionsCount(context);

            bukkit.verify(() -> Bukkit.getWorld("target-world"), never());
            verify(globalScheduler).execute(eq(plugin), action.capture());
            assertNotNull(action.getValue());
            action.getValue().run();

            verify(scanner).countRegionFilesAsync(eq(world), eq(null));
        }
    }
}
