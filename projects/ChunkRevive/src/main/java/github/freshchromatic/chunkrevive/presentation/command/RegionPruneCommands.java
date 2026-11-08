package github.freshchromatic.chunkrevive.presentation.command;

import github.freshchromatic.chunkrevive.bootstrap.ChunkRevivePlugin;
import github.freshchromatic.chunkrevive.config.Messages;
import github.freshchromatic.chunkrevive.feature.marking.MarkRegistry;
import github.freshchromatic.chunkrevive.feature.reset.AnvilRegionPos;
import github.freshchromatic.chunkrevive.feature.reset.DeletionService;
import github.freshchromatic.chunkrevive.feature.reset.ResetService;
import github.freshchromatic.chunkrevive.nms.EmptyRegionInfo;
import github.freshchromatic.freshlib.command.Commander;
import github.freshchromatic.freshlib.command.PlayerCommander;
import github.freshchromatic.freshlib.scheduler.Scheduler;
import github.freshchromatic.freshlib.util.Components;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.incendo.cloud.context.CommandContext;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

final class RegionPruneCommands {

    private record CompleteRegionSelection(Map<String, Set<AnvilRegionPos>> regions) {

        private CompleteRegionSelection {
            regions = regions.entrySet()
                .stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
        }
    }

    private record EmptyRegionSelection(String world, List<EmptyRegionInfo> regions) {

        private EmptyRegionSelection {
            regions = List.copyOf(regions);
        }
    }

    private final ChunkRevivePlugin   plugin;
    private final MarkRegistry        markRegistry;
    private final DeletionService     deletionService;
    private final ConfirmationManager confirmationManager;
    private final ResetService        resetService;
    private Messages                  messages;

    RegionPruneCommands(ChunkRevivePlugin plugin, MarkRegistry markRegistry, DeletionService deletionService,
        ConfirmationManager confirmationManager, ResetService resetService, Messages messages) {
        this.plugin = plugin;
        this.markRegistry = markRegistry;
        this.deletionService = deletionService;
        this.confirmationManager = confirmationManager;
        this.resetService = resetService;
        this.messages = messages;
    }

    void setMessages(Messages messages) {
        this.messages = messages;
    }

    void pruneRegionHere(CommandContext<PlayerCommander> ctx, boolean confirmed) {
        var player = ctx.sender().player();
        var chunk = player.getLocation().getChunk();
        var region = AnvilRegionPos.fromChunk(chunk.getX(), chunk.getZ());
        queueRegionPrune(ctx.sender(), player.getWorld(), region, confirmed, "/cr prune region here");
    }

    void pruneRegionCoordinates(CommandContext<Commander> ctx, boolean confirmed) {
        String worldName = ctx.get("world");
        int rx = ctx.get("rx");
        int rz = ctx.get("rz");
        var sender = ctx.sender();
        if (CommandMessageRouter.dispatchGlobal(plugin, () -> pruneRegionCoordinates(sender, worldName, rx, rz,
            confirmed))) {
            return;
        }
        pruneRegionCoordinates(sender, worldName, rx, rz, confirmed);
    }

    private void pruneRegionCoordinates(Commander sender, String worldName, int rx, int rz, boolean confirmed) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            CommandMessageRouter.send(plugin, sender, messages.regen.failed.withPlaceholders(Components.placeholder(
                "cx_cz", "region"), Components.placeholder("reason", "World not found: " + worldName)));
            return;
        }
        queueRegionPrune(sender, world, new AnvilRegionPos(rx, rz), confirmed, "/cr prune region " + worldName + " "
            + rx + " " + rz);
    }

    void pruneRegionByChunk(CommandContext<Commander> ctx, boolean confirmed) {
        String worldName = ctx.get("world");
        int cx = ctx.get("cx");
        int cz = ctx.get("cz");
        var sender = ctx.sender();
        if (CommandMessageRouter.dispatchGlobal(plugin, () -> pruneRegionByChunk(sender, worldName, cx, cz,
            confirmed))) {
            return;
        }
        pruneRegionByChunk(sender, worldName, cx, cz, confirmed);
    }

    private void pruneRegionByChunk(Commander sender, String worldName, int cx, int cz, boolean confirmed) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            CommandMessageRouter.send(plugin, sender, messages.regen.failed.withPlaceholders(Components.placeholder(
                "cx_cz", "region"), Components.placeholder("reason", "World not found: " + worldName)));
            return;
        }
        var region = AnvilRegionPos.fromChunk(cx, cz);
        queueRegionPrune(sender, world, region, confirmed, "/cr prune region chunk " + worldName + " " + cx + " " + cz);
    }

    void pruneRegionAll(CommandContext<Commander> ctx, boolean confirmed) {
        var sender = ctx.sender();
        String key = "prune-region-all";
        if (confirmed) {
            var selection = confirmationManager.consumePayload(sender.commanderId(), key,
                CompleteRegionSelection.class);
            if (selection.isEmpty()) {
                CommandMessageRouter.send(plugin, sender, messages.scan.confirmExpired.asComponent());
                return;
            }
            deletionService.queueRegions(sender, selection.get().regions());
            return;
        }

        Map<String, Set<AnvilRegionPos>> completeRegions = resetService.completeMarkedRegions();
        int count = completeRegions.values().stream().mapToInt(Set::size).sum();
        if (count == 0) {
            CommandMessageRouter.send(plugin, sender, messages.deletion.noCompleteRegions.asComponent());
            return;
        }
        int timeout = markRegistry.getConfig().safety.confirmTimeoutSeconds;
        confirmationManager.request(sender.commanderId(), key, timeout, new CompleteRegionSelection(completeRegions));
        CommandMessageRouter.send(plugin, sender, messages.deletion.bulkRegionConfirmRequired.withPlaceholders(
            Components.placeholder("count", String.valueOf(count)), Components.placeholder("timeout", String.valueOf(
                timeout))));
    }

    void pruneEmpty(CommandContext<Commander> ctx, boolean confirmed) {
        Commander sender = ctx.sender();
        String worldName = ctx.get("world");
        if (CommandMessageRouter.dispatchGlobal(plugin, () -> pruneEmpty(sender, worldName, confirmed))) {
            return;
        }
        pruneEmpty(sender, worldName, confirmed);
    }

    private void pruneEmpty(Commander sender, String worldName, boolean confirmed) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            CommandMessageRouter.send(plugin, sender, messages.regen.failed.withPlaceholders(Components.placeholder(
                "cx_cz", "region"), Components.placeholder("reason", "World not found: " + worldName)));
            return;
        }
        if (!resetService.isWorldAllowed(worldName)) {
            CommandMessageRouter.send(plugin, sender, messages.scan.worldNotAllowed.withPlaceholders(
                Components.placeholder("world", worldName)));
            return;
        }

        String key = "prune-empty:" + world.getUID();
        if (confirmed) {
            var selection = confirmationManager.consumePayload(sender.commanderId(), key, EmptyRegionSelection.class);
            if (selection.isEmpty()) {
                CommandMessageRouter.send(plugin, sender, messages.scan.confirmExpired.asComponent());
                return;
            }
            deletionService.queueEmptyRegions(sender, selection.get().world(), selection.get().regions());
            return;
        }

        CommandMessageRouter.send(plugin, sender, messages.deletion.emptyRegionScanStarted.withPlaceholders(
            Components.placeholder("world", worldName)));
        deletionService.scanEmptyRegions(world).whenComplete((regions, failure) -> Scheduler.runTask(plugin, () -> {
            if (failure != null) {
                Throwable cause = failure;
                while (cause.getCause() != null && (cause instanceof CompletionException
                    || cause instanceof ExecutionException)) {
                    cause = cause.getCause();
                }
                CommandMessageRouter.send(plugin, sender, messages.deletion.emptyRegionScanFailed.withPlaceholders(
                    Components.placeholder("reason", cause.getMessage() == null ? cause.getClass().getSimpleName()
                        : cause.getMessage())));
                return;
            }
            if (regions.isEmpty()) {
                CommandMessageRouter.send(plugin, sender, messages.deletion.emptyRegionNone.withPlaceholders(
                    Components.placeholder("world", worldName)));
                return;
            }
            int timeout = markRegistry.getConfig().safety.confirmTimeoutSeconds;
            long bytes = regions.stream().mapToLong(EmptyRegionInfo::reclaimableBytes).sum();
            confirmationManager.request(sender.commanderId(), key, timeout, new EmptyRegionSelection(worldName,
                regions));
            CommandMessageRouter.send(plugin, sender, messages.deletion.emptyRegionConfirmRequired.withPlaceholders(
                Components.placeholder("count", String.valueOf(regions.size())), Components.placeholder("bytes",
                    formatBytes(bytes)), Components.placeholder("timeout", String.valueOf(timeout)),
                Components.placeholder("world", worldName)));
        }));
    }

    private void queueRegionPrune(Commander sender, World world, AnvilRegionPos region, boolean confirmed,
        String command) {
        if (!resetService.isWorldAllowed(world.getName())) {
            CommandMessageRouter.send(plugin, sender, messages.scan.worldNotAllowed.withPlaceholders(
                Components.placeholder("world", world.getName())));
            return;
        }
        String key = "prune-region:" + world.getUID() + ":" + region.x() + ":" + region.z();
        if (!confirmed) {
            int timeout = markRegistry.getConfig().safety.confirmTimeoutSeconds;
            confirmationManager.request(sender.commanderId(), key, timeout);
            CommandMessageRouter.send(plugin, sender, messages.deletion.regionConfirmRequired.withPlaceholders(
                Components.placeholder("world", world.getName()), Components.placeholder("rx", String.valueOf(
                    region.x())), Components.placeholder("rz", String.valueOf(region.z())), Components.placeholder(
                        "timeout", String.valueOf(timeout)), Components.placeholder("command", command)));
            return;
        }
        if (!confirmationManager.confirm(sender.commanderId(), key)) {
            CommandMessageRouter.send(plugin, sender, messages.scan.confirmExpired.asComponent());
            return;
        }
        deletionService.queueRegion(sender, world.getName(), region.x(), region.z());
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
        }
        return String.format(Locale.ROOT, "%.2f GiB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}
