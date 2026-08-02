package github.freshchromatic.chunkrevive.presentation.command;

import github.freshchromatic.chunkrevive.feature.reset.ResetService;
import github.freshchromatic.chunkrevive.bootstrap.ChunkRevivePlugin;
import github.freshchromatic.chunkrevive.presentation.command.ConfirmationManager;
import github.freshchromatic.chunkrevive.presentation.display.AdminTuiBuilder;
import github.freshchromatic.chunkrevive.config.Messages;
import github.freshchromatic.chunkrevive.feature.reset.AnvilRegionPos;
import github.freshchromatic.chunkrevive.feature.reset.DeletionService;
import github.freshchromatic.chunkrevive.feature.reset.ResetMethod;
import github.freshchromatic.chunkrevive.feature.marking.MarkRegistry;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import github.freshchromatic.chunkrevive.feature.structure.StructureRegistry;
import github.freshchromatic.freshlib.command.Commander;
import github.freshchromatic.freshlib.command.PlayerCommander;
import github.freshchromatic.freshlib.util.Components;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.incendo.cloud.context.CommandContext;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/** Handles regeneration, chunk deletion and region pruning commands. */
public final class ResetCommands {
    private final ChunkRevivePlugin plugin;
    private final MarkRegistry markRegistry;
    private final DeletionService deletionService;
    private final ConfirmationManager confirmationManager;
    private final BiConsumer<Commander, UUID> regenerateStructure;
    private final ResetService resetService;
    private Messages messages;

    public ResetCommands(
            ChunkRevivePlugin plugin,
            MarkRegistry markRegistry,
            DeletionService deletionService,
            ConfirmationManager confirmationManager,
            BiConsumer<Commander, UUID> regenerateStructure,
            ResetService resetService,
            Messages messages) {
        this.plugin = plugin;
        this.markRegistry = markRegistry;
        this.deletionService = deletionService;
        this.confirmationManager = confirmationManager;
        this.regenerateStructure = regenerateStructure;
        this.resetService = resetService;
        this.messages = messages;
    }

    public void setMessages(Messages messages) {
        this.messages = messages;
    }

    public void regenerateCurrent(CommandContext<PlayerCommander> ctx) {
        var sender = ctx.sender();
        var player = sender.player();
        var chunk = player.getLocation().getChunk();
        String world = player.getWorld().getName();
        if (!resetService.isWorldAllowed(world)) {
            player.sendMessage(messages.scan.worldNotAllowed.withPlaceholders(Components.placeholder("world", world)));
            return;
        }
        int[] structureBounds = resetService.structureContextBounds(world, chunk.getX(), chunk.getZ());
        var mc = new MarkedChunk(world, chunk.getX(), chunk.getZ(), player.getUniqueId(), System.currentTimeMillis());
        executeSingleRegeneration(sender, mc, structureBounds);
    }

    public void regenerateCurrentStructure(CommandContext<PlayerCommander> ctx, boolean confirmed) {
        var sender = ctx.sender();
        var player = sender.player();
        var chunk = player.getLocation().getChunk();
        String world = player.getWorld().getName();
        int cx = chunk.getX(), cz = chunk.getZ();

        UUID groupId = markRegistry.getMarkedChunks().stream()
            .filter(c -> c.world().equals(world) && c.cx() == cx && c.cz() == cz)
            .findFirst()
            .map(MarkedChunk::structureGroupId)
            .orElse(null);

        if (groupId == null) {
            sender.sendMessage(Component.text(messages.text("reset-not-structure")).color(AdminTuiBuilder.SEVERE));
            return;
        }

        String key = "regen-here-struct:" + groupId;
        if (!confirmed) {
            int timeout = markRegistry.getConfig().safety.confirmTimeoutSeconds;
            long chunkCount = markRegistry.getMarkedChunks().stream()
                .filter(c -> groupId.equals(c.structureGroupId()))
                .count();
            confirmationManager.request(sender.commanderId(), key, timeout);
            sender.sendMessage(Component.text(messages.text(
                "regen-structure-confirm", chunkCount, timeout)).color(TextColor.color(0xFFAA00)));
            return;
        }
        if (!confirmationManager.confirm(sender.commanderId(), key)) {
            sender.sendMessage(messages.scan.confirmExpired.asComponent());
            return;
        }

        regenerateStructure.accept(sender, groupId);
    }

    public void regenerateCoordinates(CommandContext<Commander> ctx) {
        var sender = ctx.sender();
        String world = ctx.get("world");
        int cx = ctx.get("cx");
        int cz = ctx.get("cz");
        if (!resetService.isWorldAllowed(world)) {
            sender.sendMessage(messages.scan.worldNotAllowed.withPlaceholders(Components.placeholder("world", world)));
            return;
        }
        int[] structureBounds = resetService.structureContextBounds(world, cx, cz);
        var mc = new MarkedChunk(world, cx, cz, UUID.randomUUID(), System.currentTimeMillis());
        executeSingleRegeneration(sender, mc, structureBounds);
    }

    public void resetCurrent(CommandContext<PlayerCommander> ctx, boolean confirmed) {
        var sender = ctx.sender();
        var player = sender.player();
        var chunk = player.getLocation().getChunk();
        String world = player.getWorld().getName();
        if (!resetService.isWorldAllowed(world)) {
            player.sendMessage(messages.scan.worldNotAllowed.withPlaceholders(Components.placeholder("world", world)));
            return;
        }
        int[] structureBounds = resetService.structureContextBounds(world, chunk.getX(), chunk.getZ());
        var target = new MarkedChunk(world, chunk.getX(), chunk.getZ(), player.getUniqueId(), System.currentTimeMillis());
        executeConfirmedSingleReset(sender, target, structureBounds, confirmed, "/cr reset here --confirm");
    }

    public void resetCurrentStructure(CommandContext<PlayerCommander> ctx, boolean confirmed) {
        var sender = ctx.sender();
        var player = sender.player();
        var chunk = player.getLocation().getChunk();
        String world = player.getWorld().getName();
        if (!resetService.isWorldAllowed(world)) {
            sender.sendMessage(messages.scan.worldNotAllowed.withPlaceholders(
                Components.placeholder("world", world)));
            return;
        }

        UUID groupId = resetService.structureGroupAt(world, chunk.getX(), chunk.getZ()).orElse(null);
        if (groupId == null) {
            sender.sendMessage(Component.text(messages.text("reset-not-structure")).color(AdminTuiBuilder.SEVERE));
            return;
        }
        List<MarkedChunk> targets = markRegistry.getMarkedChunks().stream()
            .filter(marked -> groupId.equals(marked.structureGroupId()))
            .toList();
        if (targets.isEmpty()) {
            sender.sendMessage(Component.text(messages.text("reset-structure-empty")).color(AdminTuiBuilder.SECONDARY));
            return;
        }
        String structureName = resetService.structureGroup(groupId)
            .map(group -> StructureRegistry.displayName(group.structureId()))
            .orElse(groupId.toString());

        executeConfirmedBulkReset(sender, targets, confirmed,
            "reset-here-struct:" + groupId, "/cr reset here struct --confirm", false, null,
            messages.text("reset-target-structure", structureName));
    }

    public void resetDetectedBiome(Commander sender, Collection<MarkedChunk> targets,
                                   boolean confirmed, String confirmationKey, String biomeId,
                                   Object confirmationPayload) {
        executeConfirmedBulkReset(sender, targets, confirmed,
            confirmationKey, "/cr reset here biome --confirm", true, confirmationPayload,
            messages.text("reset-target-biome", biomeId));
    }

    public void resetCoordinates(CommandContext<Commander> ctx, boolean confirmed) {
        var sender = ctx.sender();
        String world = ctx.get("world");
        int cx = ctx.get("cx");
        int cz = ctx.get("cz");
        if (!resetService.isWorldAllowed(world)) {
            sender.sendMessage(messages.scan.worldNotAllowed.withPlaceholders(Components.placeholder("world", world)));
            return;
        }
        int[] structureBounds = resetService.structureContextBounds(world, cx, cz);
        var target = new MarkedChunk(world, cx, cz, UUID.randomUUID(), System.currentTimeMillis());
        executeConfirmedSingleReset(sender, target, structureBounds, confirmed,
            "/cr reset chunk " + world + " " + cx + " " + cz + " --confirm");
    }

    public void deleteChunkHere(CommandContext<PlayerCommander> ctx) {
        var player = ctx.sender().player();
        var chunk = player.getLocation().getChunk();
        queueChunkDelete(ctx.sender(), player.getWorld(), chunk.getX(), chunk.getZ());
    }

    public void deleteChunkCoordinates(CommandContext<Commander> ctx) {
        String worldName = ctx.get("world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            ctx.sender().sendMessage(messages.regen.failed.withPlaceholders(
                Components.placeholder("cx_cz", ctx.<Integer>get("cx") + ", " + ctx.<Integer>get("cz")),
                Components.placeholder("reason", "World not found: " + worldName)));
            return;
        }
        queueChunkDelete(ctx.sender(), world, ctx.get("cx"), ctx.get("cz"));
    }

    public void deleteChunkAll(CommandContext<Commander> ctx, boolean confirmed) {
        deleteMarked(ctx, null, confirmed);
    }

    public void deleteMarked(CommandContext<Commander> ctx, String requestedWorld, boolean confirmed) {
        var sender = ctx.sender();
        if (requestedWorld != null && Bukkit.getWorld(requestedWorld) == null) {
            sender.sendMessage(Component.text(messages.text("world-not-found", requestedWorld)).color(AdminTuiBuilder.SEVERE));
            return;
        }
        if (requestedWorld != null
            && !resetService.isWorldAllowed(requestedWorld)) {
            sender.sendMessage(messages.scan.worldNotAllowed.withPlaceholders(
                Components.placeholder("world", requestedWorld)));
            return;
        }
        List<MarkedChunk> chunks = resetService.markedDeletionTargets(requestedWorld);
        if (chunks.isEmpty()) {
            sender.sendMessage(Component.text(requestedWorld == null
                ? messages.text("reset-no-deletable")
                : messages.text("reset-no-deletable-world", requestedWorld))
                .color(AdminTuiBuilder.SECONDARY));
            return;
        }
        String key = "delete-marked:" + (requestedWorld == null ? "all" : requestedWorld);
        String confirmCommand = "/cr delete marked"
            + (requestedWorld == null ? "" : " " + requestedWorld) + " --confirm";
        if (!confirmed) {
            confirmationManager.request(sender.commanderId(), key, markRegistry.getConfig().safety.confirmTimeoutSeconds);
            sender.sendMessage(Component.text(messages.text("reset-delete-marked-confirm", chunks.size(),
                markRegistry.getConfig().safety.confirmTimeoutSeconds, confirmCommand)).color(TextColor.color(0xFFAA00)));
            return;
        }
        if (!confirmationManager.confirm(sender.commanderId(), key)) {
            sender.sendMessage(messages.scan.confirmExpired.asComponent());
            return;
        }
        // Residence is checked again by each job immediately before its deletion fence is acquired.
        deletionService.queueChunks(sender, chunks);
    }

    public void deleteHereStructure(CommandContext<PlayerCommander> ctx, boolean confirmed) {
        var sender = ctx.sender();
        var player = sender.player();
        World world = player.getWorld();
        var chunk = player.getLocation().getChunk();
        if (!resetService.isWorldAllowed(world.getName())) {
            sender.sendMessage(messages.scan.worldNotAllowed.withPlaceholders(
                Components.placeholder("world", world.getName())));
            return;
        }
        var target = resetService.structureDeletionTarget(world, chunk.getX(), chunk.getZ());
        if (target.status() != ResetService.StructureTargetStatus.READY) {
            sender.sendMessage(switch (target.status()) {
                case NOT_FOUND -> Component.text(messages.text("reset-not-structure")).color(AdminTuiBuilder.SEVERE);
                case EMPTY -> Component.text(messages.text("reset-structure-empty")).color(AdminTuiBuilder.SECONDARY);
                case CLAIM_BLOCKED -> Component.text(messages.text("reset-structure-claim-blocked")).color(AdminTuiBuilder.SEVERE);
                case READY -> Component.empty();
            });
            return;
        }
        var group = target.group();
        var chunks = target.chunks();
        UUID groupId = group.groupId();
        String key = "delete-here-struct:" + groupId;
        if (!confirmed) {
            confirmationManager.request(sender.commanderId(), key, markRegistry.getConfig().safety.confirmTimeoutSeconds);
            sender.sendMessage(Component.text(messages.text("reset-structure-confirm", group.structureId(), chunks.size(),
                markRegistry.getConfig().safety.confirmTimeoutSeconds))
                .color(TextColor.color(0xFFAA00)));
            return;
        }
        if (!confirmationManager.confirm(sender.commanderId(), key)) {
            sender.sendMessage(messages.scan.confirmExpired.asComponent());
            return;
        }
        deletionService.queueChunks(sender, chunks);
    }

    private void queueChunkDelete(Commander sender, World world, int cx, int cz) {
        var status = resetService.queueChunkDelete(sender, world, cx, cz);
        if (status == ResetService.QueueStatus.WORLD_NOT_ALLOWED) {
            sender.sendMessage(messages.scan.worldNotAllowed.withPlaceholders(
                Components.placeholder("world", world.getName())));
        } else if (status == ResetService.QueueStatus.CLAIM_BLOCKED) {
            sender.sendMessage(messages.regen.residenceBlocked.withPlaceholders(
                Components.placeholder("cx_cz", cx + ", " + cz)));
        }
    }

    /** Applies the configured strategy to one explicit /cr reset target. */
    private void executeSingleReset(Commander sender, MarkedChunk chunk, int[] structureBounds) {
        var status = resetService.resetSingle(sender, chunk, structureBounds);
        if (status == ResetService.QueueStatus.WORLD_NOT_FOUND) {
            sender.sendMessage(Component.text(messages.text("world-not-found", chunk.world())).color(AdminTuiBuilder.SEVERE));
        } else if (status == ResetService.QueueStatus.WORLD_NOT_ALLOWED) {
            sender.sendMessage(messages.scan.worldNotAllowed.withPlaceholders(Components.placeholder("world", chunk.world())));
        } else if (status == ResetService.QueueStatus.CLAIM_BLOCKED) {
            sender.sendMessage(messages.regen.residenceBlocked.withPlaceholders(
                Components.placeholder("cx_cz", chunk.cx() + ", " + chunk.cz())));
        }
    }

    private void executeSingleRegeneration(Commander sender, MarkedChunk chunk, int[] structureBounds) {
        var status = resetService.regenerateSingle(sender, chunk, structureBounds);
        handleSingleStatus(sender, chunk, status);
    }

    private void executeConfirmedSingleReset(Commander sender, MarkedChunk chunk, int[] structureBounds,
                                             boolean confirmed, String confirmCommand) {
        ResetMethod method = resetService.resetMethodFor(chunk);
        if (method == ResetMethod.DELETE_CHUNK || method == ResetMethod.DELETE_REGION) {
            String key = "reset-single:" + chunk.world() + ":" + chunk.cx() + ":" + chunk.cz() + ":" + method;
            if (!confirmed) {
                confirmationManager.request(sender.commanderId(), key, markRegistry.getConfig().safety.confirmTimeoutSeconds);
                sender.sendMessage(Component.text(messages.text("reset-single-confirm", chunk.world(), chunk.cx(), chunk.cz(), method,
                    markRegistry.getConfig().safety.confirmTimeoutSeconds, confirmCommand))
                    .color(TextColor.color(0xFFAA00)));
                return;
            }
            if (!confirmationManager.confirm(sender.commanderId(), key)) {
                sender.sendMessage(messages.scan.confirmExpired.asComponent());
                return;
            }
        }
        executeSingleReset(sender, chunk, structureBounds);
    }

    private void executeConfirmedBulkReset(Commander sender, Collection<MarkedChunk> targets,
                                           boolean confirmed, String confirmationKey,
                                           String confirmCommand, boolean markBeforeExecute,
                                           Object confirmationPayload, String targetDescription) {
        var plan = resetService.previewResetBulk(targets);
        if (plan.isEmpty()) {
            sender.sendMessage(messages.regen.noneMarked.asComponent());
            return;
        }
        if (!plan.regenerateChunks().isEmpty() && markRegistry.getRegenerationQueue().isRunning()) {
            sender.sendMessage(Component.text(messages.text("reset-busy")).color(AdminTuiBuilder.SEVERE));
            return;
        }

        boolean destructive = !plan.deleteChunks().isEmpty() || !plan.deleteRegions().isEmpty();
        int threshold = plugin.getPluginConfig().safety.bulkRegenConfirmThresholdChunks;
        boolean requiresConfirmation = destructive || targets.size() > threshold;
        if (requiresConfirmation && !confirmed) {
            confirmationManager.request(sender.commanderId(), confirmationKey,
                markRegistry.getConfig().safety.confirmTimeoutSeconds, confirmationPayload);
            sender.sendMessage(Component.text(messages.text("reset-plan-confirm-target",
                targetDescription, plan.deleteRegions().size(), plan.deleteChunks().size(),
                plan.regenerateChunks().size(), markRegistry.getConfig().safety.confirmTimeoutSeconds,
                confirmCommand))
                .color(TextColor.color(0xFFAA00)));
            return;
        }
        if (requiresConfirmation && !confirmationManager.confirm(sender.commanderId(), confirmationKey)) {
            sender.sendMessage(messages.scan.confirmExpired.asComponent());
            return;
        }

        if (markBeforeExecute) {
            markRegistry.markChunksDirect(List.copyOf(targets));
        }
        bulkReset(sender, targets);
    }

    private void handleSingleStatus(Commander sender, MarkedChunk chunk, ResetService.QueueStatus status) {
        if (status == ResetService.QueueStatus.WORLD_NOT_FOUND) {
            sender.sendMessage(Component.text(messages.text("world-not-found", chunk.world())).color(AdminTuiBuilder.SEVERE));
        } else if (status == ResetService.QueueStatus.WORLD_NOT_ALLOWED) {
            sender.sendMessage(messages.scan.worldNotAllowed.withPlaceholders(Components.placeholder("world", chunk.world())));
        } else if (status == ResetService.QueueStatus.CLAIM_BLOCKED) {
            sender.sendMessage(messages.regen.residenceBlocked.withPlaceholders(
                Components.placeholder("cx_cz", chunk.cx() + ", " + chunk.cz())));
        }
    }

    public void bulkReset(Commander sender, Collection<MarkedChunk> targets) {
        var result = resetService.resetBulk(sender, targets);
        if (result.status() == ResetService.QueueStatus.EMPTY) return;
        if (result.status() == ResetService.QueueStatus.REGEN_BUSY) {
            sender.sendMessage(Component.text(messages.text("reset-busy"))
                .color(AdminTuiBuilder.SEVERE));
            return;
        }
        var plan = result.plan();
        sender.sendMessage(Component.text(messages.text("reset-plan", plan.deleteRegions().size(), plan.deleteChunks().size(), plan.regenerateChunks().size()))
            .color(AdminTuiBuilder.SECONDARY));
    }

    public void bulkRegenerate(Commander sender, Collection<MarkedChunk> targets) {
        var result = resetService.regenerateBulk(sender, targets);
        if (result.status() == ResetService.QueueStatus.EMPTY) return;
        if (result.status() == ResetService.QueueStatus.REGEN_BUSY) {
            sender.sendMessage(Component.text(messages.text("reset-regen-busy"))
                .color(AdminTuiBuilder.SEVERE));
        }
    }

    public void pruneRegionHere(CommandContext<PlayerCommander> ctx, boolean confirmed) {
        var player = ctx.sender().player();
        var chunk = player.getLocation().getChunk();
        AnvilRegionPos region = AnvilRegionPos.fromChunk(chunk.getX(), chunk.getZ());
        queueRegionPrune(ctx.sender(), player.getWorld(), region, confirmed,
            "/cr prune region here");
    }

    public void pruneRegionCoordinates(CommandContext<Commander> ctx, boolean confirmed) {
        String worldName = ctx.get("world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            ctx.sender().sendMessage(messages.regen.failed.withPlaceholders(
                Components.placeholder("cx_cz", "region"),
                Components.placeholder("reason", "World not found: " + worldName)));
            return;
        }
        int rx = ctx.get("rx");
        int rz = ctx.get("rz");
        queueRegionPrune(ctx.sender(), world, new AnvilRegionPos(rx, rz), confirmed,
            "/cr prune region " + worldName + " " + rx + " " + rz);
    }

    public void pruneRegionByChunk(CommandContext<Commander> ctx, boolean confirmed) {
        String worldName = ctx.get("world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            ctx.sender().sendMessage(messages.regen.failed.withPlaceholders(
                Components.placeholder("cx_cz", "region"),
                Components.placeholder("reason", "World not found: " + worldName)));
            return;
        }
        int cx = ctx.get("cx");
        int cz = ctx.get("cz");
        AnvilRegionPos region = AnvilRegionPos.fromChunk(cx, cz);
        queueRegionPrune(ctx.sender(), world, region, confirmed,
            "/cr prune region chunk " + worldName + " " + cx + " " + cz);
    }

    public void pruneRegionAll(CommandContext<Commander> ctx, boolean confirmed) {
        var sender = ctx.sender();
        Map<String, Set<AnvilRegionPos>> completeRegions = resetService.completeMarkedRegions();
        int count = completeRegions.values().stream().mapToInt(Set::size).sum();
        if (count == 0) {
            sender.sendMessage(messages.deletion.noCompleteRegions.asComponent());
            return;
        }
        String key = "prune-region-all";
        if (!confirmed) {
            confirmationManager.request(sender.commanderId(), key, markRegistry.getConfig().safety.confirmTimeoutSeconds);
            sender.sendMessage(messages.deletion.bulkRegionConfirmRequired.withPlaceholders(
                Components.placeholder("count", String.valueOf(count)),
                Components.placeholder("timeout", String.valueOf(markRegistry.getConfig().safety.confirmTimeoutSeconds))));
            return;
        }
        if (!confirmationManager.confirm(sender.commanderId(), key)) {
            sender.sendMessage(messages.scan.confirmExpired.asComponent());
            return;
        }
        deletionService.queueRegions(sender, completeRegions);
    }

    public void pruneEmpty(CommandContext<Commander> ctx, boolean confirmed) {
        Commander sender = ctx.sender();
        String worldName = ctx.get("world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            sender.sendMessage(messages.regen.failed.withPlaceholders(
                Components.placeholder("cx_cz", "region"),
                Components.placeholder("reason", "World not found: " + worldName)));
            return;
        }
        if (!resetService.isWorldAllowed(worldName)) {
            sender.sendMessage(messages.scan.worldNotAllowed.withPlaceholders(
                Components.placeholder("world", worldName)));
            return;
        }

        String key = "prune-empty:" + world.getUID();
        if (confirmed && !confirmationManager.confirm(sender.commanderId(), key)) {
            sender.sendMessage(messages.scan.confirmExpired.asComponent());
            return;
        }

        sender.sendMessage(messages.deletion.emptyRegionScanStarted.withPlaceholders(
            Components.placeholder("world", worldName)));
        deletionService.scanEmptyRegions(world).whenComplete((regions, failure) -> {
            if (failure != null) {
                Throwable cause = failure;
                while (cause.getCause() != null
                    && (cause instanceof java.util.concurrent.CompletionException
                        || cause instanceof java.util.concurrent.ExecutionException)) {
                    cause = cause.getCause();
                }
                sender.sendMessage(messages.deletion.emptyRegionScanFailed.withPlaceholders(
                    Components.placeholder("reason", cause.getMessage() == null
                        ? cause.getClass().getSimpleName() : cause.getMessage())));
                return;
            }
            if (regions.isEmpty()) {
                sender.sendMessage(messages.deletion.emptyRegionNone.withPlaceholders(
                    Components.placeholder("world", worldName)));
                return;
            }
            if (confirmed) {
                deletionService.queueEmptyRegions(sender, worldName, regions);
                return;
            }

            int timeout = markRegistry.getConfig().safety.confirmTimeoutSeconds;
            long bytes = regions.stream().mapToLong(region -> region.reclaimableBytes()).sum();
            confirmationManager.request(sender.commanderId(), key, timeout);
            sender.sendMessage(messages.deletion.emptyRegionConfirmRequired.withPlaceholders(
                Components.placeholder("count", String.valueOf(regions.size())),
                Components.placeholder("bytes", formatBytes(bytes)),
                Components.placeholder("timeout", String.valueOf(timeout)),
                Components.placeholder("world", worldName)));
        });
    }

    private void queueRegionPrune(Commander sender, World world, AnvilRegionPos region,
                                  boolean confirmed, String command) {
        if (!resetService.isWorldAllowed(world.getName())) {
            sender.sendMessage(messages.scan.worldNotAllowed.withPlaceholders(
                Components.placeholder("world", world.getName())));
            return;
        }
        String key = "prune-region:" + world.getUID() + ":" + region.x() + ":" + region.z();
        if (!confirmed) {
            confirmationManager.request(sender.commanderId(), key, markRegistry.getConfig().safety.confirmTimeoutSeconds);
            sender.sendMessage(messages.deletion.regionConfirmRequired.withPlaceholders(
                Components.placeholder("world", world.getName()),
                Components.placeholder("rx", String.valueOf(region.x())),
                Components.placeholder("rz", String.valueOf(region.z())),
                Components.placeholder("timeout", String.valueOf(markRegistry.getConfig().safety.confirmTimeoutSeconds)),
                Components.placeholder("command", command)));
            return;
        }
        if (!confirmationManager.confirm(sender.commanderId(), key)) {
            sender.sendMessage(messages.scan.confirmExpired.asComponent());
            return;
        }
        deletionService.queueRegion(sender, world.getName(), region.x(), region.z());
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
        }
        return String.format(Locale.ROOT, "%.2f GiB", bytes / (1024.0 * 1024.0 * 1024.0));
    }


    public void regenerateAllChunks(CommandContext<Commander> context, boolean confirmed) {
        handleRegenAll(context, RegenAllMode.CHUNKS_ONLY, confirmed);
    }

    public void regenerateAllStructures(CommandContext<Commander> context, boolean confirmed) {
        handleRegenAll(context, RegenAllMode.STRUCTURES_ONLY, confirmed);
    }

    public void regenerateAll(CommandContext<Commander> context, boolean confirmed) {
        handleRegenAll(context, RegenAllMode.ALL, confirmed);
    }

    private enum RegenAllMode { CHUNKS_ONLY, STRUCTURES_ONLY, ALL }

    private static String scopeLiteral(RegenAllMode mode) {
        return switch (mode) {
            case CHUNKS_ONLY -> "chunks";
            case STRUCTURES_ONLY -> "structures";
            case ALL -> "all";
        };
    }

    private void handleRegenAll(CommandContext<Commander> ctx, RegenAllMode mode, boolean confirmed) {
        var sender = ctx.sender();

        // blocked structure groups are excluded before anything else, so the confirmation threshold (§4.5)
        // reflects exactly what would actually be queued, not the theoretical total of all marks.
        var scope = switch (mode) {
            case CHUNKS_ONLY -> ResetService.RegenScope.CHUNKS;
            case STRUCTURES_ONLY -> ResetService.RegenScope.STRUCTURES;
            case ALL -> ResetService.RegenScope.ALL;
        };
        var chunks = resetService.regenerationTargets(scope);

        if (chunks.isEmpty()) {
            sender.sendMessage(messages.regen.noneMarked.asComponent());
            return;
        }
        if (markRegistry.getRegenerationQueue().isRunning()) {
            sender.sendMessage(Component.text(messages.text("regen-busy")).color(AdminTuiBuilder.SEVERE));
            return;
        }

        String key = "regenallmark:" + scopeLiteral(mode);
        int threshold = plugin.getPluginConfig().safety.bulkRegenConfirmThresholdChunks;
        if (chunks.size() > threshold) {
            if (!confirmed) {
                sender.sendMessage(messages.regen.confirmRequired.withPlaceholders(
                    Components.placeholder("count", String.valueOf(chunks.size())),
                    Components.placeholder("timeout", String.valueOf(plugin.getPluginConfig().safety.confirmTimeoutSeconds)),
                    Components.placeholder("scope", scopeLiteral(mode))));
                return;
            }
        }

        bulkRegenerate(sender, chunks);
    }

    public void resetAllChunks(CommandContext<Commander> context, boolean confirmed) {
        handleResetAll(context, RegenAllMode.CHUNKS_ONLY, confirmed);
    }

    public void resetAllStructures(CommandContext<Commander> context, boolean confirmed) {
        handleResetAll(context, RegenAllMode.STRUCTURES_ONLY, confirmed);
    }

    public void resetAll(CommandContext<Commander> context, boolean confirmed) {
        handleResetAll(context, RegenAllMode.ALL, confirmed);
    }

    private void handleResetAll(CommandContext<Commander> ctx, RegenAllMode mode, boolean confirmed) {
        var sender = ctx.sender();
        var scope = switch (mode) {
            case CHUNKS_ONLY -> ResetService.RegenScope.CHUNKS;
            case STRUCTURES_ONLY -> ResetService.RegenScope.STRUCTURES;
            case ALL -> ResetService.RegenScope.ALL;
        };
        var chunks = resetService.regenerationTargets(scope);
        var plan = resetService.previewResetBulk(chunks);
        if (plan.isEmpty()) {
            sender.sendMessage(messages.regen.noneMarked.asComponent());
            return;
        }
        if (!plan.regenerateChunks().isEmpty() && markRegistry.getRegenerationQueue().isRunning()) {
            sender.sendMessage(Component.text(messages.text("reset-busy"))
                .color(AdminTuiBuilder.SEVERE));
            return;
        }

        boolean destructive = !plan.deleteChunks().isEmpty() || !plan.deleteRegions().isEmpty();
        int threshold = plugin.getPluginConfig().safety.bulkRegenConfirmThresholdChunks;
        boolean requiresConfirmation = destructive || chunks.size() > threshold;
        String scopeName = scopeLiteral(mode);
        String key = "reset-all:" + scopeName;
        String confirmCommand = "/cr reset all " + scopeName + " --confirm";
        if (requiresConfirmation && !confirmed) {
            confirmationManager.request(sender.commanderId(), key, markRegistry.getConfig().safety.confirmTimeoutSeconds);
            sender.sendMessage(Component.text(messages.text("reset-plan-confirm", plan.deleteRegions().size(), plan.deleteChunks().size(),
                plan.regenerateChunks().size(), markRegistry.getConfig().safety.confirmTimeoutSeconds, confirmCommand))
                .color(TextColor.color(0xFFAA00)));
            return;
        }
        if (requiresConfirmation && !confirmationManager.confirm(sender.commanderId(), key)) {
            sender.sendMessage(messages.scan.confirmExpired.asComponent());
            return;
        }
        bulkReset(sender, chunks);
    }

    // ── fullmark / radiusmark / resetmark ──────────────────────────────────────


}
