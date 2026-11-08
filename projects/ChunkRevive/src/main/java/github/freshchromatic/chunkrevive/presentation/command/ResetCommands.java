package github.freshchromatic.chunkrevive.presentation.command;

import github.freshchromatic.chunkrevive.bootstrap.ChunkRevivePlugin;
import github.freshchromatic.chunkrevive.config.Messages;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import github.freshchromatic.chunkrevive.feature.reset.DeletionService;
import github.freshchromatic.chunkrevive.feature.reset.ResetMethod;
import github.freshchromatic.chunkrevive.feature.reset.ResetService;
import github.freshchromatic.chunkrevive.feature.reset.ResetStrategyPlanner;
import github.freshchromatic.chunkrevive.feature.scanning.ChunkScanService;
import github.freshchromatic.chunkrevive.feature.structure.StructureRegistry;
import github.freshchromatic.chunkrevive.presentation.display.AdminTuiBuilder;
import github.freshchromatic.freshlib.command.Commander;
import github.freshchromatic.freshlib.command.PlayerCommander;
import github.freshchromatic.freshlib.scheduler.Scheduler;
import github.freshchromatic.freshlib.util.Components;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.incendo.cloud.context.CommandContext;

import java.util.Collection;
import java.util.function.BiConsumer;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Handles regeneration, chunk deletion and region pruning commands. */
public final class ResetCommands {

    private record MarkedChunkSelection(List<MarkedChunk> chunks) {

        private MarkedChunkSelection {
            chunks = List.copyOf(chunks);
        }
    }

    static record BulkResetSelection(List<MarkedChunk> targets, ResetStrategyPlanner.Plan plan,
        BiomeSelection biomeSelection) {

        BulkResetSelection {
            targets = List.copyOf(targets);
        }
    }

    private record BulkResetRequest(List<MarkedChunk> targets, String confirmationKey, String confirmationCommand,
        BiomeSelection biomeSelection, String targetDescription) {}

    private final ChunkRevivePlugin           plugin;
    private final DeletionService             deletionService;
    private final ConfirmationManager         confirmationManager;
    private final BiConsumer<Commander, UUID> regenerateStructure;
    private final ResetService                resetService;
    private final ChunkScanService            chunkScanService;
    private Messages                          messages;

    public ResetCommands(ChunkRevivePlugin plugin, DeletionService deletionService,
        ConfirmationManager confirmationManager, BiConsumer<Commander, UUID> regenerateStructure,
        ResetService resetService, ChunkScanService chunkScanService, Messages messages) {
        this.plugin = plugin;
        this.deletionService = deletionService;
        this.confirmationManager = confirmationManager;
        this.regenerateStructure = regenerateStructure;
        this.resetService = resetService;
        this.chunkScanService = chunkScanService;
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
        var mc = new MarkedChunk(world, chunk.getX(), chunk.getZ(), player.getUniqueId(), System.currentTimeMillis());
        executeSingleRegeneration(sender, mc);
    }

    public void regenerateCurrentStructure(CommandContext<PlayerCommander> ctx, boolean confirmed) {
        var sender = ctx.sender();
        var player = sender.player();
        var chunk = player.getLocation().getChunk();
        String world = player.getWorld().getName();
        int cx = chunk.getX(), cz = chunk.getZ();

        UUID groupId = resetService.structureGroupAt(world, cx, cz).orElse(null);

        if (groupId == null) {
            CommandMessageRouter.send(plugin, sender, Component.text(messages.text("reset-not-structure")).color(
                AdminTuiBuilder.SEVERE));
            return;
        }

        String key = "regen-here-struct:" + groupId;
        if (!confirmed) {
            int timeout = resetService.confirmationTimeoutSeconds();
            int chunkCount = resetService.markedChunksInStructure(groupId).size();
            confirmationManager.request(sender.commanderId(), key, timeout);
            CommandMessageRouter.send(plugin, sender, Component.text(messages.text("regen-structure-confirm",
                chunkCount, timeout)).color(TextColor.color(0xFFAA00)));
            return;
        }
        if (!confirmationManager.confirm(sender.commanderId(), key)) {
            CommandMessageRouter.send(plugin, sender, messages.scan.confirmExpired.asComponent());
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
            CommandMessageRouter.send(plugin, sender, messages.scan.worldNotAllowed.withPlaceholders(
                Components.placeholder("world", world)));
            return;
        }
        var mc = new MarkedChunk(world, cx, cz, UUID.randomUUID(), System.currentTimeMillis());
        executeSingleRegeneration(sender, mc);
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
        var target = new MarkedChunk(world, chunk.getX(), chunk.getZ(), player.getUniqueId(),
            System.currentTimeMillis());
        executeConfirmedSingleReset(sender, target, confirmed, "/cr reset here --confirm");
    }

    public void resetCurrentStructure(CommandContext<PlayerCommander> ctx, boolean confirmed) {
        var sender = ctx.sender();
        var player = sender.player();
        var chunk = player.getLocation().getChunk();
        String world = player.getWorld().getName();
        if (!resetService.isWorldAllowed(world)) {
            CommandMessageRouter.send(plugin, sender, messages.scan.worldNotAllowed.withPlaceholders(
                Components.placeholder("world", world)));
            return;
        }

        UUID groupId = resetService.structureGroupAt(world, chunk.getX(), chunk.getZ()).orElse(null);
        if (groupId == null) {
            CommandMessageRouter.send(plugin, sender, Component.text(messages.text("reset-not-structure")).color(
                AdminTuiBuilder.SEVERE));
            return;
        }
        List<MarkedChunk> targets = resetService.markedChunksInStructure(groupId);
        if (targets.isEmpty()) {
            CommandMessageRouter.send(plugin, sender, Component.text(messages.text("reset-structure-empty")).color(
                AdminTuiBuilder.SECONDARY));
            return;
        }
        String structureName = resetService.structureGroup(groupId)
            .map(group -> StructureRegistry.displayName(group.structureId()))
            .orElse(groupId.toString());

        var request = new BulkResetRequest(targets, "reset-here-struct:" + groupId, "/cr reset here struct --confirm",
            null, messages.text("reset-target-structure", structureName));
        executeConfirmedBulkReset(sender, request, confirmed);
    }

    void resetDetectedBiome(Commander sender, BiomeSelection selection, boolean confirmed, String confirmationKey) {
        var request = new BulkResetRequest(selection.chunks(), confirmationKey, "/cr reset here biome --confirm",
            selection, messages.text("reset-target-biome", selection.biomeId()));
        executeConfirmedBulkReset(sender, request, confirmed);
    }

    public void resetCoordinates(CommandContext<Commander> ctx, boolean confirmed) {
        var sender = ctx.sender();
        String world = ctx.get("world");
        int cx = ctx.get("cx");
        int cz = ctx.get("cz");
        if (CommandMessageRouter.dispatchGlobal(plugin, () -> resetCoordinates(sender, world, cx, cz, confirmed))) {
            return;
        }
        resetCoordinates(sender, world, cx, cz, confirmed);
    }

    private void resetCoordinates(Commander sender, String world, int cx, int cz, boolean confirmed) {
        if (!resetService.isWorldAllowed(world)) {
            CommandMessageRouter.send(plugin, sender, messages.scan.worldNotAllowed.withPlaceholders(
                Components.placeholder("world", world)));
            return;
        }
        var target = new MarkedChunk(world, cx, cz, UUID.randomUUID(), System.currentTimeMillis());
        executeConfirmedSingleReset(sender, target, confirmed, "/cr reset chunk " + world + " " + cx + " " + cz
            + " --confirm");
    }

    public void deleteChunkHere(CommandContext<PlayerCommander> ctx) {
        var player = ctx.sender().player();
        var chunk = player.getLocation().getChunk();
        var sender = ctx.sender();
        String worldName = player.getWorld().getName();
        int cx = chunk.getX();
        int cz = chunk.getZ();
        if (CommandMessageRouter.dispatchGlobal(plugin, () -> deleteChunkHere(sender, worldName, cx, cz))) {
            return;
        }
        deleteChunkHere(sender, worldName, cx, cz);
    }

    private void deleteChunkHere(Commander sender, String worldName, int cx, int cz) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            CommandMessageRouter.send(plugin, sender, Component.text(messages.text("world-not-found", worldName)).color(
                AdminTuiBuilder.SEVERE));
            return;
        }
        queueChunkDelete(sender, world, cx, cz);
    }

    public void deleteChunkCoordinates(CommandContext<Commander> ctx) {
        var sender = ctx.sender();
        String worldName = ctx.get("world");
        int cx = ctx.get("cx");
        int cz = ctx.get("cz");
        if (CommandMessageRouter.dispatchGlobal(plugin, () -> deleteChunkCoordinates(sender, worldName, cx, cz))) {
            return;
        }
        deleteChunkCoordinates(sender, worldName, cx, cz);
    }

    private void deleteChunkCoordinates(Commander sender, String worldName, int cx, int cz) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            CommandMessageRouter.send(plugin, sender, messages.regen.failed.withPlaceholders(Components.placeholder(
                "cx_cz", cx + ", " + cz), Components.placeholder("reason", "World not found: " + worldName)));
            return;
        }
        queueChunkDelete(sender, world, cx, cz);
    }

    public void deleteChunkAll(CommandContext<Commander> ctx, boolean confirmed) {
        deleteMarked(ctx, null, confirmed);
    }

    public void deleteMarked(CommandContext<Commander> ctx, String requestedWorld, boolean confirmed) {
        var sender = ctx.sender();
        if (CommandMessageRouter.dispatchGlobal(plugin, () -> deleteMarked(sender, requestedWorld, confirmed))) {
            return;
        }
        deleteMarked(sender, requestedWorld, confirmed);
    }

    private void deleteMarked(Commander sender, String requestedWorld, boolean confirmed) {
        if (requestedWorld != null && Bukkit.getWorld(requestedWorld) == null) {
            CommandMessageRouter.send(plugin, sender, Component.text(messages.text("world-not-found",
                requestedWorld)).color(AdminTuiBuilder.SEVERE));
            return;
        }
        if (requestedWorld != null && !resetService.isWorldAllowed(requestedWorld)) {
            CommandMessageRouter.send(plugin, sender, messages.scan.worldNotAllowed.withPlaceholders(
                Components.placeholder("world", requestedWorld)));
            return;
        }
        String key = "delete-marked:" + (requestedWorld == null ? "all" : requestedWorld);
        if (confirmed) {
            var selection = confirmationManager.consumePayload(sender.commanderId(), key, MarkedChunkSelection.class);
            if (selection.isEmpty()) {
                CommandMessageRouter.send(plugin, sender, messages.scan.confirmExpired.asComponent());
                return;
            }
            deletionService.queueChunks(sender, selection.get().chunks());
            return;
        }

        List<MarkedChunk> chunks = resetService.markedDeletionTargets(requestedWorld);
        if (chunks.isEmpty()) {
            CommandMessageRouter.send(plugin, sender, Component.text(requestedWorld == null ? messages.text(
                "reset-no-deletable") : messages.text("reset-no-deletable-world", requestedWorld)).color(
                    AdminTuiBuilder.SECONDARY));
            return;
        }
        String confirmCommand = "/cr delete marked" + (requestedWorld == null ? "" : " " + requestedWorld)
            + " --confirm";
        confirmationManager.request(sender.commanderId(), key, resetService.confirmationTimeoutSeconds(),
            new MarkedChunkSelection(chunks));
        CommandMessageRouter.send(plugin, sender, Component.text(messages.text("reset-delete-marked-confirm",
            chunks.size(), resetService.confirmationTimeoutSeconds(), confirmCommand)).color(TextColor.color(
                0xFFAA00)));
    }

    public void deleteHereStructure(CommandContext<PlayerCommander> ctx, boolean confirmed) {
        var sender = ctx.sender();
        var player = sender.player();
        String worldName = player.getWorld().getName();
        var chunk = player.getLocation().getChunk();
        int cx = chunk.getX();
        int cz = chunk.getZ();
        if (CommandMessageRouter.dispatchGlobal(plugin, () -> deleteHereStructure(sender, worldName, cx, cz,
            confirmed))) {
            return;
        }
        deleteHereStructure(sender, worldName, cx, cz, confirmed);
    }

    private void deleteHereStructure(Commander sender, String worldName, int cx, int cz, boolean confirmed) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            CommandMessageRouter.send(plugin, sender, Component.text(messages.text("world-not-found", worldName)).color(
                AdminTuiBuilder.SEVERE));
            return;
        }
        if (!resetService.isWorldAllowed(world.getName())) {
            CommandMessageRouter.send(plugin, sender, messages.scan.worldNotAllowed.withPlaceholders(
                Components.placeholder("world", world.getName())));
            return;
        }
        String key = "delete-here-struct";
        if (confirmed) {
            var selection = confirmationManager.consumePayload(sender.commanderId(), key, MarkedChunkSelection.class);
            if (selection.isEmpty()) {
                CommandMessageRouter.send(plugin, sender, messages.scan.confirmExpired.asComponent());
                return;
            }
            deletionService.queueChunks(sender, selection.get().chunks());
            return;
        }

        var target = resetService.structureDeletionTarget(world, cx, cz);
        if (target.status() != ResetService.StructureTargetStatus.READY) {
            CommandMessageRouter.send(plugin, sender, switch (target.status()) {
                case NOT_FOUND -> Component.text(messages.text("reset-not-structure")).color(AdminTuiBuilder.SEVERE);
                case EMPTY -> Component.text(messages.text("reset-structure-empty")).color(AdminTuiBuilder.SECONDARY);
                case CLAIM_BLOCKED -> Component.text(messages.text("reset-structure-claim-blocked")).color(
                    AdminTuiBuilder.SEVERE);
                case READY -> Component.empty();
            });
            return;
        }
        var group = target.group();
        var chunks = target.chunks();
        confirmationManager.request(sender.commanderId(), key, resetService.confirmationTimeoutSeconds(),
            new MarkedChunkSelection(chunks));
        CommandMessageRouter.send(plugin, sender, Component.text(messages.text("reset-structure-confirm",
            group.structureId(), chunks.size(), resetService.confirmationTimeoutSeconds())).color(TextColor.color(
                0xFFAA00)));
    }

    private void queueChunkDelete(Commander sender, World world, int cx, int cz) {
        var status = resetService.queueChunkDelete(sender, world, cx, cz);
        if (status == ResetService.QueueStatus.WORLD_NOT_ALLOWED) {
            CommandMessageRouter.send(plugin, sender, messages.scan.worldNotAllowed.withPlaceholders(
                Components.placeholder("world", world.getName())));
        } else if (status == ResetService.QueueStatus.CLAIM_BLOCKED) {
            CommandMessageRouter.send(plugin, sender, messages.regen.residenceBlocked.withPlaceholders(
                Components.placeholder("cx_cz", cx + ", " + cz)));
        }
    }

    /** Applies the configured strategy to one explicit /cr reset target. */
    private void executeSingleReset(Commander sender, MarkedChunk chunk) {
        var status = resetService.resetSingle(sender, chunk);
        if (status == ResetService.QueueStatus.WORLD_NOT_FOUND) {
            CommandMessageRouter.send(plugin, sender, Component.text(messages.text("world-not-found",
                chunk.world())).color(AdminTuiBuilder.SEVERE));
        } else if (status == ResetService.QueueStatus.WORLD_NOT_ALLOWED) {
            CommandMessageRouter.send(plugin, sender, messages.scan.worldNotAllowed.withPlaceholders(
                Components.placeholder("world", chunk.world())));
        } else if (status == ResetService.QueueStatus.CLAIM_BLOCKED) {
            CommandMessageRouter.send(plugin, sender, messages.regen.residenceBlocked.withPlaceholders(
                Components.placeholder("cx_cz", chunk.cx() + ", " + chunk.cz())));
        }
    }

    private void executeSingleRegeneration(Commander sender, MarkedChunk chunk) {
        var status = resetService.regenerateSingle(sender, chunk);
        handleSingleStatus(sender, chunk, status);
    }

    private void executeConfirmedSingleReset(Commander sender, MarkedChunk chunk, boolean confirmed,
        String confirmCommand) {
        if (CommandMessageRouter.dispatchGlobal(plugin, () -> executeConfirmedSingleReset(sender, chunk, confirmed,
            confirmCommand))) {
            return;
        }
        ResetMethod method = resetService.resetMethodFor(chunk);
        if (method == ResetMethod.DELETE_CHUNK || method == ResetMethod.DELETE_REGION) {
            String key = "reset-single:" + chunk.world() + ":" + chunk.cx() + ":" + chunk.cz() + ":" + method;
            if (!confirmed) {
                confirmationManager.request(sender.commanderId(), key, resetService.confirmationTimeoutSeconds());
                CommandMessageRouter.send(plugin, sender, Component.text(messages.text("reset-single-confirm",
                    chunk.world(), chunk.cx(), chunk.cz(), method, resetService.confirmationTimeoutSeconds(),
                    confirmCommand)).color(TextColor.color(0xFFAA00)));
                return;
            }
            if (!confirmationManager.confirm(sender.commanderId(), key)) {
                CommandMessageRouter.send(plugin, sender, messages.scan.confirmExpired.asComponent());
                return;
            }
        }
        executeSingleReset(sender, chunk);
    }

    private void executeConfirmedBulkReset(Commander sender, BulkResetRequest request, boolean confirmed) {
        if (CommandMessageRouter.dispatchGlobal(plugin, () -> executeConfirmedBulkReset(sender, request, confirmed))) {
            return;
        }
        List<MarkedChunk> targets = request.targets();
        BiomeSelection biomeSelection = request.biomeSelection();
        ResetStrategyPlanner.Plan plan = null;
        boolean hasConfirmedSelection = false;
        if (confirmed) {
            var selection = confirmationManager.consumePayload(sender.commanderId(), request.confirmationKey(),
                BulkResetSelection.class);
            if (selection.isPresent()) {
                targets = selection.get().targets();
                plan = selection.get().plan();
                biomeSelection = selection.get().biomeSelection();
                hasConfirmedSelection = true;
            }
        }

        if (plan == null) {
            plan = resetService.previewResetBulk(targets);
        }
        if (plan.isEmpty()) {
            CommandMessageRouter.send(plugin, sender, messages.regen.noneMarked.asComponent());
            return;
        }
        if (!plan.regenerateChunks().isEmpty() && resetService.regenerationQueueRunning()) {
            CommandMessageRouter.send(plugin, sender, Component.text(messages.text("reset-busy")).color(
                AdminTuiBuilder.SEVERE));
            return;
        }
        boolean requiresConfirmation = resetService.bulkResetRequiresConfirmation(plan, targets.size());
        if (requiresConfirmation && !confirmed) {
            confirmationManager.request(sender.commanderId(), request.confirmationKey(),
                resetService.confirmationTimeoutSeconds(), new BulkResetSelection(targets, plan, biomeSelection));
            CommandMessageRouter.send(plugin, sender, Component.text(messages.text("reset-plan-confirm-target",
                request.targetDescription(), plan.deleteRegions().size(), plan.deleteChunks().size(),
                plan.regenerateChunks().size(), resetService.confirmationTimeoutSeconds(),
                request.confirmationCommand())).color(TextColor.color(0xFFAA00)));
            return;
        }
        if (requiresConfirmation && !hasConfirmedSelection) {
            CommandMessageRouter.send(plugin, sender, messages.scan.confirmExpired.asComponent());
            return;
        }

        if (biomeSelection != null) {
            chunkScanService.markDirect(List.copyOf(targets)).whenComplete((outcome, failure) -> Scheduler.runTask(
                plugin, () -> {
                    if (failure != null) {
                        plugin.getLogger().warning("Biome protection check failed: " + failure.getMessage());
                        CommandMessageRouter.send(plugin, sender, messages.regen.noneMarked.asComponent());
                    } else if (outcome.eligible().isEmpty()) {
                        CommandMessageRouter.send(plugin, sender, messages.regen.noneMarked.asComponent());
                    } else {
                        bulkReset(sender, outcome.eligible());
                    }
                }));
            return;
        }
        bulkReset(sender, plan);
    }

    private void handleSingleStatus(Commander sender, MarkedChunk chunk, ResetService.QueueStatus status) {
        if (status == ResetService.QueueStatus.WORLD_NOT_FOUND) {
            CommandMessageRouter.send(plugin, sender, Component.text(messages.text("world-not-found",
                chunk.world())).color(AdminTuiBuilder.SEVERE));
        } else if (status == ResetService.QueueStatus.WORLD_NOT_ALLOWED) {
            CommandMessageRouter.send(plugin, sender, messages.scan.worldNotAllowed.withPlaceholders(
                Components.placeholder("world", chunk.world())));
        } else if (status == ResetService.QueueStatus.CLAIM_BLOCKED) {
            CommandMessageRouter.send(plugin, sender, messages.regen.residenceBlocked.withPlaceholders(
                Components.placeholder("cx_cz", chunk.cx() + ", " + chunk.cz())));
        }
    }

    public void bulkReset(Commander sender, Collection<MarkedChunk> targets) {
        if (CommandMessageRouter.dispatchGlobal(plugin, () -> bulkReset(sender, targets))) {
            return;
        }
        var result = resetService.resetBulk(sender, targets);
        reportBulkResetResult(sender, result);
    }

    private void bulkReset(Commander sender, ResetStrategyPlanner.Plan plan) {
        if (CommandMessageRouter.dispatchGlobal(plugin, () -> bulkReset(sender, plan))) {
            return;
        }
        reportBulkResetResult(sender, resetService.resetBulk(sender, plan));
    }

    private void reportBulkResetResult(Commander sender, ResetService.BulkResetResult result) {
        if (result.status() == ResetService.QueueStatus.EMPTY) {
            return;
        }
        if (result.status() == ResetService.QueueStatus.REGEN_BUSY) {
            CommandMessageRouter.send(plugin, sender, Component.text(messages.text("reset-busy")).color(
                AdminTuiBuilder.SEVERE));
            return;
        }
        var plan = result.plan();
        CommandMessageRouter.send(plugin, sender, Component.text(messages.text("reset-plan",
            plan.deleteRegions().size(), plan.deleteChunks().size(), plan.regenerateChunks().size())).color(
                AdminTuiBuilder.SECONDARY));
    }

    public void bulkRegenerate(Commander sender, Collection<MarkedChunk> targets) {
        var result = resetService.regenerateBulk(sender, targets);
        if (result.status() == ResetService.QueueStatus.EMPTY) {
            return;
        }
        if (result.status() == ResetService.QueueStatus.REGEN_BUSY) {
            CommandMessageRouter.send(plugin, sender, Component.text(messages.text("reset-regen-busy")).color(
                AdminTuiBuilder.SEVERE));
        }
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

    private enum RegenAllMode {
        CHUNKS_ONLY, STRUCTURES_ONLY, ALL
    }

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
        String scopeName = scopeLiteral(mode);
        String key = "regenallmark:" + scopeName;
        var confirmedSelection = confirmed ? confirmationManager.consumePayload(sender.commanderId(), key,
            MarkedChunkSelection.class) : Optional.<MarkedChunkSelection>empty();
        var chunks = confirmedSelection.map(MarkedChunkSelection::chunks).orElseGet(
            () -> resetService.regenerationTargets(scope));

        if (chunks.isEmpty()) {
            CommandMessageRouter.send(plugin, sender, messages.regen.noneMarked.asComponent());
            return;
        }
        if (resetService.regenerationQueueRunning()) {
            CommandMessageRouter.send(plugin, sender, Component.text(messages.text("regen-busy")).color(
                AdminTuiBuilder.SEVERE));
            return;
        }

        if (resetService.bulkRegenerationRequiresConfirmation(chunks.size())) {
            if (!confirmed) {
                int timeout = resetService.confirmationTimeoutSeconds();
                confirmationManager.request(sender.commanderId(), key, timeout, new MarkedChunkSelection(chunks));
                CommandMessageRouter.send(plugin, sender, messages.regen.confirmRequired.withPlaceholders(
                    Components.placeholder("count", String.valueOf(chunks.size())), Components.placeholder("timeout",
                        String.valueOf(timeout)), Components.placeholder("scope", scopeName)));
                return;
            }
            if (confirmedSelection.isEmpty()) {
                CommandMessageRouter.send(plugin, sender, messages.scan.confirmExpired.asComponent());
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
        if (CommandMessageRouter.dispatchGlobal(plugin, () -> handleResetAll(sender, mode, confirmed))) {
            return;
        }
        handleResetAll(sender, mode, confirmed);
    }

    private void handleResetAll(Commander sender, RegenAllMode mode, boolean confirmed) {
        var scope = switch (mode) {
            case CHUNKS_ONLY -> ResetService.RegenScope.CHUNKS;
            case STRUCTURES_ONLY -> ResetService.RegenScope.STRUCTURES;
            case ALL -> ResetService.RegenScope.ALL;
        };
        String scopeName = scopeLiteral(mode);
        String key = "reset-all:" + scopeName;
        var confirmedSelection = confirmed ? confirmationManager.consumePayload(sender.commanderId(), key,
            BulkResetSelection.class) : Optional.<BulkResetSelection>empty();
        var chunks = confirmedSelection.map(BulkResetSelection::targets).orElseGet(
            () -> resetService.regenerationTargets(scope));
        var plan = confirmedSelection.map(BulkResetSelection::plan).orElseGet(() -> resetService.previewResetBulk(
            chunks));
        if (plan.isEmpty()) {
            CommandMessageRouter.send(plugin, sender, messages.regen.noneMarked.asComponent());
            return;
        }
        if (!plan.regenerateChunks().isEmpty() && resetService.regenerationQueueRunning()) {
            CommandMessageRouter.send(plugin, sender, Component.text(messages.text("reset-busy")).color(
                AdminTuiBuilder.SEVERE));
            return;
        }

        boolean requiresConfirmation = resetService.bulkResetRequiresConfirmation(plan, chunks.size());
        String confirmCommand = "/cr reset all " + scopeName + " --confirm";
        if (requiresConfirmation && !confirmed) {
            confirmationManager.request(sender.commanderId(), key, resetService.confirmationTimeoutSeconds(),
                new BulkResetSelection(chunks, plan, null));
            CommandMessageRouter.send(plugin, sender, Component.text(messages.text("reset-plan-confirm",
                plan.deleteRegions().size(), plan.deleteChunks().size(), plan.regenerateChunks().size(),
                resetService.confirmationTimeoutSeconds(), confirmCommand)).color(TextColor.color(0xFFAA00)));
            return;
        }
        if (requiresConfirmation && confirmedSelection.isEmpty()) {
            CommandMessageRouter.send(plugin, sender, messages.scan.confirmExpired.asComponent());
            return;
        }
        bulkReset(sender, plan);
    }

    // ── fullmark / radiusmark / resetmark ──────────────────────────────────────

}
