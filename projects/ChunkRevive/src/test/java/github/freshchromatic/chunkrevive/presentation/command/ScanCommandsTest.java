package github.freshchromatic.chunkrevive.presentation.command;

import github.freshchromatic.chunkrevive.bootstrap.ChunkRevivePlugin;
import github.freshchromatic.chunkrevive.config.Messages;
import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.config.WorldAccessPolicy;
import github.freshchromatic.chunkrevive.feature.reset.DeletionService;
import github.freshchromatic.chunkrevive.feature.scanning.ChunkScanService;
import github.freshchromatic.chunkrevive.feature.scanning.DiskChunkScanner;
import github.freshchromatic.freshlib.command.Commander;
import github.freshchromatic.freshlib.scheduler.Scheduler;
import github.freshchromatic.freshlib.util.Components;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.incendo.cloud.context.CommandContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ScanCommandsTest {

    @Test
    @SuppressWarnings("unchecked")
    void fullMarkReportsRegionCountFailure() {
        var plugin = mock(ChunkRevivePlugin.class);
        var config = new PluginConfig();
        when(plugin.getPluginConfig()).thenReturn(config);
        var world = mock(World.class);
        when(world.getName()).thenReturn("world");
        var scans = mock(ChunkScanService.class);
        var failure = new UncheckedIOException("Failed to count region files for world", new IOException(
            "unavailable"));
        when(scans.countRegionsAsync(world, null)).thenReturn(CompletableFuture.failedFuture(failure));
        var messages = new Messages();
        var sender = mock(Commander.class);
        when(sender.commanderId()).thenReturn("sender");
        CommandContext<Commander> context = mock(CommandContext.class);
        when(context.sender()).thenReturn(sender);
        when(context.get("world")).thenReturn("world");
        var commands = new ScanCommands(plugin, new WorldAccessPolicy(config), scans, (ignored, chunks) -> {
        }, new ConfirmationManager(), mock(DeletionService.class), messages);
        try (var bukkit = mockStatic(Bukkit.class); var scheduler = mockStatic(Scheduler.class)) {
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            scheduler.when(Scheduler::isGlobalTickThread).thenReturn(true);

            commands.fullMark(context, false);

            verify(sender).sendMessage(messages.scan.scanFailed.withPlaceholders(Components.placeholder("reason",
                failure.getMessage())));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void fullMarkReportsCancellationInsteadOfCompletion() {
        var plugin = mock(ChunkRevivePlugin.class);
        var config = new PluginConfig();
        when(plugin.getPluginConfig()).thenReturn(config);
        var world = mock(World.class);
        when(world.getName()).thenReturn("world");
        UUID worldId = UUID.randomUUID();
        when(world.getUID()).thenReturn(worldId);
        var scans = mock(ChunkScanService.class);
        when(scans.countRegionsAsync(world, null)).thenReturn(CompletableFuture.completedFuture(1));
        when(scans.scan(world, null, null)).thenReturn(CompletableFuture.completedFuture(
            new DiskChunkScanner.ScanResult(1, 2, 1, 1, 0, 0, 0, 0, true)));
        var messages = new Messages();
        var sender = mock(Commander.class);
        when(sender.commanderId()).thenReturn("sender");
        CommandContext<Commander> context = mock(CommandContext.class);
        when(context.sender()).thenReturn(sender);
        when(context.get("world")).thenReturn("world");
        var commands = new ScanCommands(plugin, new WorldAccessPolicy(config), scans, (ignored, chunks) -> {
        }, new ConfirmationManager(), mock(DeletionService.class), messages);
        try (var bukkit = mockStatic(Bukkit.class); var scheduler = mockStatic(Scheduler.class)) {
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            bukkit.when(() -> Bukkit.getWorld(worldId)).thenReturn(world);
            scheduler.when(Scheduler::isGlobalTickThread).thenReturn(true);
            scheduler.when(() -> Scheduler.runTask(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(
                Runnable.class))).thenAnswer(invocation -> {
                    invocation.<Runnable>getArgument(1).run();
                    return null;
                });

            commands.fullMark(context, false);
            commands.fullMark(context, true);

            verify(sender).sendMessage(messages.scan.scanCancelled.asComponent());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void fullMarkRequiresAValidPreviewConfirmation() {
        var plugin = mock(ChunkRevivePlugin.class);
        var scans = mock(ChunkScanService.class);
        var sender = mock(Commander.class);
        when(sender.commanderId()).thenReturn("sender");
        CommandContext<Commander> context = mock(CommandContext.class);
        when(context.sender()).thenReturn(sender);
        when(context.get("world")).thenReturn("world");
        var messages = new Messages();
        var commands = new ScanCommands(plugin, new WorldAccessPolicy(new PluginConfig()), scans, (ignored, chunks) -> {
        }, new ConfirmationManager(), mock(DeletionService.class), messages);

        try (var scheduler = mockStatic(Scheduler.class)) {
            scheduler.when(Scheduler::isGlobalTickThread).thenReturn(true);
            commands.fullMark(context, true);
        }

        verify(sender).sendMessage(messages.scan.confirmExpired.asComponent());
        verifyNoInteractions(scans);
    }
}
