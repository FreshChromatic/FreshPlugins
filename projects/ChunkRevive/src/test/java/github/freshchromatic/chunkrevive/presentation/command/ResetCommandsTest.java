package github.freshchromatic.chunkrevive.presentation.command;

import github.freshchromatic.chunkrevive.bootstrap.ChunkRevivePlugin;
import github.freshchromatic.chunkrevive.config.Messages;
import github.freshchromatic.chunkrevive.feature.reset.DeletionService;
import github.freshchromatic.chunkrevive.feature.reset.ResetService;
import github.freshchromatic.chunkrevive.feature.scanning.ChunkScanService;
import github.freshchromatic.freshlib.command.Commander;
import github.freshchromatic.freshlib.scheduler.Scheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.incendo.cloud.context.CommandContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResetCommandsTest {

    @Test
    @SuppressWarnings("unchecked")
    void deleteCoordinatesLooksUpWorldOnlyAfterDispatchingToGlobalScheduler() {
        var plugin = mock(ChunkRevivePlugin.class);
        var deletionService = mock(DeletionService.class);
        var resetService = mock(ResetService.class);
        var sender = mock(Commander.class);
        CommandContext<Commander> context = mock(CommandContext.class);
        when(context.sender()).thenReturn(sender);
        when(context.get("world")).thenReturn("target-world");
        when(context.get("cx")).thenReturn(17);
        when(context.get("cz")).thenReturn(-9);
        var world = mock(World.class);
        var globalScheduler = mock(GlobalRegionScheduler.class);
        var commands = new ResetCommands(plugin, deletionService, new ConfirmationManager(), (ignoredSender,
            ignoredGroup) -> {
            }, resetService, mock(ChunkScanService.class), new Messages());
        ArgumentCaptor<Runnable> action = ArgumentCaptor.forClass(Runnable.class);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
            MockedStatic<Scheduler> scheduler = mockStatic(Scheduler.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(globalScheduler);
            bukkit.when(() -> Bukkit.getWorld("target-world")).thenReturn(world);
            scheduler.when(Scheduler::isGlobalTickThread).thenReturn(false);

            commands.deleteChunkCoordinates(context);

            bukkit.verify(() -> Bukkit.getWorld("target-world"), never());
            verify(globalScheduler).execute(eq(plugin), action.capture());
            assertNotNull(action.getValue());
            action.getValue().run();

            verify(resetService).queueChunkDelete(eq(sender), eq(world), eq(17), eq(-9));
        }
    }
}
