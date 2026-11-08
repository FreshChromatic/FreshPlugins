package github.freshchromatic.chunkrevive.presentation.command;

import github.freshchromatic.chunkrevive.bootstrap.ChunkRevivePlugin;
import github.freshchromatic.chunkrevive.config.Messages;
import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.feature.marking.MarkRegistry;
import github.freshchromatic.chunkrevive.feature.marking.MarkService;
import github.freshchromatic.freshlib.command.Commander;
import github.freshchromatic.freshlib.command.PlayerCommander;
import github.freshchromatic.freshlib.scheduler.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.incendo.cloud.context.CommandContext;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MarkCommandsTest {

    @Test
    @SuppressWarnings("unchecked")
    void resetMarksRequiresAValidConfirmationBeforeResettingWorld() {
        var plugin = mock(ChunkRevivePlugin.class);
        when(plugin.getPluginConfig()).thenReturn(new PluginConfig());
        var markService = mock(MarkService.class);
        var sender = mock(Commander.class);
        when(sender.commanderId()).thenReturn("sender");
        CommandContext<Commander> context = mock(CommandContext.class);
        when(context.sender()).thenReturn(sender);
        when(context.get("world")).thenReturn("world");
        var messages = new Messages();
        var commands = new MarkCommands(plugin, markService, new ConfirmationManager(), messages);
        var world = mock(World.class);

        try (var bukkit = mockStatic(Bukkit.class); var scheduler = mockStatic(Scheduler.class)) {
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            scheduler.when(Scheduler::isGlobalTickThread).thenReturn(true);

            commands.resetMarks(context, true);

            verify(sender).sendMessage(messages.scan.confirmExpired.asComponent());
            verifyNoInteractions(markService);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void resetMarksSendsCompletionToThePlayerEntityScheduler() {
        var plugin = mock(ChunkRevivePlugin.class);
        when(plugin.getPluginConfig()).thenReturn(new PluginConfig());
        var markService = mock(MarkService.class);
        when(markService.resetWorld(eq("world"))).thenReturn(CompletableFuture.completedFuture(
            new MarkRegistry.ResetResult(1, 0, 0)));
        var sender = mock(PlayerCommander.class);
        when(sender.commanderId()).thenReturn("sender");
        var player = mock(Player.class);
        when(sender.player()).thenReturn(player);
        CommandContext<Commander> context = mock(CommandContext.class);
        when(context.sender()).thenReturn(sender);
        when(context.get("world")).thenReturn("world");
        var confirmations = new ConfirmationManager();
        var commands = new MarkCommands(plugin, markService, confirmations, new Messages());
        var world = mock(World.class);
        var globalScheduler = mock(GlobalRegionScheduler.class);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        })
            .when(globalScheduler)
            .execute(eq(plugin), any(Runnable.class));

        try (var bukkit = mockStatic(Bukkit.class); var scheduler = mockStatic(Scheduler.class)) {
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(globalScheduler);

            commands.resetMarks(context, false);
            clearInvocations(sender);
            scheduler.clearInvocations();
            commands.resetMarks(context, true);

            scheduler.verify(() -> Scheduler.runTask(eq(plugin), any(Runnable.class), eq(player)));
            verify(markService).resetWorld(eq("world"));
        }
    }
}
