package github.freshchromatic.chunkrevive.presentation.command;

import github.freshchromatic.chunkrevive.bootstrap.ChunkRevivePlugin;
import github.freshchromatic.chunkrevive.config.Messages;
import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.feature.marking.MarkRegistry;
import github.freshchromatic.chunkrevive.feature.reset.AnvilRegionPos;
import github.freshchromatic.chunkrevive.feature.reset.DeletionService;
import github.freshchromatic.chunkrevive.feature.reset.ResetService;
import github.freshchromatic.freshlib.command.Commander;
import github.freshchromatic.freshlib.scheduler.Scheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.incendo.cloud.context.CommandContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegionPruneCommandsTest {

    @Test
    @SuppressWarnings("unchecked")
    void pruneRegionAllRequiresConfirmationForThePreviewedRegions() {
        var plugin = mock(ChunkRevivePlugin.class);
        var markRegistry = mock(MarkRegistry.class);
        when(markRegistry.getConfig()).thenReturn(new PluginConfig());
        var deletionService = mock(DeletionService.class);
        var confirmationManager = new ConfirmationManager();
        var resetService = mock(ResetService.class);
        Map<String, Set<AnvilRegionPos>> regions = Map.of("world", Set.of(new AnvilRegionPos(1, -1)));
        when(resetService.completeMarkedRegions()).thenReturn(regions);
        var sender = mock(Commander.class);
        when(sender.commanderId()).thenReturn("sender");
        CommandContext<Commander> context = mock(CommandContext.class);
        when(context.sender()).thenReturn(sender);
        var commands = new RegionPruneCommands(plugin, markRegistry, deletionService, confirmationManager, resetService,
            new Messages());

        try (MockedStatic<Scheduler> scheduler = mockStatic(Scheduler.class)) {
            scheduler.when(Scheduler::isGlobalTickThread).thenReturn(true);
            commands.pruneRegionAll(context, false);
            commands.pruneRegionAll(context, true);
        }

        verify(deletionService).queueRegions(eq(sender), eq(regions));
    }

    @Test
    @SuppressWarnings("unchecked")
    void coordinatePruneLooksUpWorldOnlyAfterDispatchingToGlobalScheduler() {
        var plugin = mock(ChunkRevivePlugin.class);
        var markRegistry = mock(MarkRegistry.class);
        var deletionService = mock(DeletionService.class);
        var confirmationManager = new ConfirmationManager();
        var resetService = mock(ResetService.class);
        var sender = mock(Commander.class);
        CommandContext<Commander> context = mock(CommandContext.class);
        when(context.sender()).thenReturn(sender);
        when(context.get("world")).thenReturn("target-world");
        when(context.get("rx")).thenReturn(3);
        when(context.get("rz")).thenReturn(-4);
        var world = mock(World.class);
        when(world.getName()).thenReturn("target-world");
        var globalScheduler = mock(GlobalRegionScheduler.class);
        var commands = new RegionPruneCommands(plugin, markRegistry, deletionService, confirmationManager, resetService,
            new Messages());
        ArgumentCaptor<Runnable> action = ArgumentCaptor.forClass(Runnable.class);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
            MockedStatic<Scheduler> scheduler = mockStatic(Scheduler.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(globalScheduler);
            bukkit.when(() -> Bukkit.getWorld("target-world")).thenReturn(world);
            scheduler.when(Scheduler::isGlobalTickThread).thenReturn(false);

            commands.pruneRegionCoordinates(context, false);

            bukkit.verify(() -> Bukkit.getWorld("target-world"), never());
            verify(globalScheduler).execute(eq(plugin), action.capture());
            action.getValue().run();

            verify(resetService).isWorldAllowed("target-world");
        }
    }
}
