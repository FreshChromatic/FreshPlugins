package github.freshchromatic.chunkrevive.application.api;

import github.freshchromatic.chunkrevive.api.event.OperationCompletedEvent;
import github.freshchromatic.chunkrevive.api.event.OperationStateChangedEvent;
import github.freshchromatic.chunkrevive.api.integration.MaintenanceAction;
import github.freshchromatic.chunkrevive.api.integration.ProtectionBatchResult;
import github.freshchromatic.chunkrevive.api.integration.ProtectionDecision;
import github.freshchromatic.chunkrevive.api.model.ApiError;
import github.freshchromatic.chunkrevive.api.model.ChunkKey;
import github.freshchromatic.chunkrevive.api.model.RequestContext;
import github.freshchromatic.chunkrevive.api.operation.CancelResult;
import github.freshchromatic.chunkrevive.api.operation.ChunkRadius;
import github.freshchromatic.chunkrevive.api.operation.ExplicitChunks;
import github.freshchromatic.chunkrevive.api.operation.MaintenanceRequest;
import github.freshchromatic.chunkrevive.api.operation.OperationHandle;
import github.freshchromatic.chunkrevive.api.operation.OperationId;
import github.freshchromatic.chunkrevive.api.operation.OperationPage;
import github.freshchromatic.chunkrevive.api.operation.OperationPersistenceStatus;
import github.freshchromatic.chunkrevive.api.operation.OperationPreview;
import github.freshchromatic.chunkrevive.api.operation.OperationQuery;
import github.freshchromatic.chunkrevive.api.operation.OperationSnapshot;
import github.freshchromatic.chunkrevive.api.operation.OperationState;
import github.freshchromatic.chunkrevive.api.operation.OperationType;
import github.freshchromatic.chunkrevive.api.operation.PreviewRejection;
import github.freshchromatic.chunkrevive.api.operation.PreviewToken;
import github.freshchromatic.chunkrevive.api.operation.TargetSelection;
import github.freshchromatic.chunkrevive.api.worldgen.GeneratorCapability;
import github.freshchromatic.chunkrevive.api.worldgen.WorldGeneratorCompatibilityApi;
import github.freshchromatic.chunkrevive.config.WorldAccessPolicy;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import github.freshchromatic.chunkrevive.feature.marking.MarkRegistry;
import github.freshchromatic.chunkrevive.feature.operation.OperationRecord;
import github.freshchromatic.chunkrevive.feature.operation.OperationStore;
import github.freshchromatic.chunkrevive.feature.reset.DeletionService;
import github.freshchromatic.chunkrevive.feature.reset.ResetService;
import github.freshchromatic.chunkrevive.feature.reset.ResetStrategyPlanner;
import github.freshchromatic.chunkrevive.feature.scanning.ChunkScanService;
import github.freshchromatic.chunkrevive.feature.scanning.DiskChunkScanner;
import github.freshchromatic.freshlib.util.Logging;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.World;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.logging.Level;

/** Coordinates the existing reset/deletion engines behind a preview-token safety boundary. */
final class OperationCoordinator {

    private static final Duration                PREVIEW_TTL          = Duration.ofMinutes(5);
    private final Plugin                         plugin;
    private final MarkRegistry                   marks;
    private final ResetService                   resets;
    private final DeletionService                deletions;
    private final ChunkScanService               scans;
    private final WorldGeneratorCompatibilityApi generators;
    private final DefaultIntegrationApi          integrations;
    private final OperationStore                 operationStore;
    private final OperationHistory               history;
    private final WorldAccessPolicy              worlds;
    private final Object                         persistenceLifecycle = new Object();
    private final Map<String, Preview>           previews             = new ConcurrentHashMap<>();
    private final Map<String, SubmittedPreview>  submittedPreviews    = new ConcurrentHashMap<>();
    private final Map<UUID, MutableOperation>    operations           = new ConcurrentHashMap<>();
    private final Map<String, Idempotency>       idempotency          = new ConcurrentHashMap<>();
    private volatile boolean                     active               = true;
    private boolean                              persistenceStopped;

    OperationCoordinator(Plugin plugin, MarkRegistry marks, ResetService resets, DeletionService deletions,
        ChunkScanService scans, WorldAccessPolicy worlds, WorldGeneratorCompatibilityApi generators,
        DefaultIntegrationApi integrations, OperationStore operationStore) {
        this.plugin = plugin;
        this.marks = marks;
        this.resets = resets;
        this.deletions = deletions;
        this.scans = scans;
        this.worlds = worlds;
        this.generators = generators;
        this.integrations = integrations;
        this.operationStore = operationStore;
        this.history = new OperationHistory(operationStore);
    }

    CompletionStage<OperationPreview> preview(Plugin owner, MaintenanceRequest request, RequestContext context) {
        List<ChunkKey> chunks = requestedChunks(request.targets());
        return integrations.check(actionFor(request.type()), chunks).thenCompose(protection -> onGlobal(
            () -> previewResolved(owner, request, context, protection)));
    }

    private OperationPreview previewResolved(Plugin owner, MaintenanceRequest request, RequestContext context,
        ProtectionBatchResult externalProtection) {
        Instant now = Instant.now();
        previews.entrySet().removeIf(entry -> now.isAfter(entry.getValue().expires));
        submittedPreviews.entrySet().removeIf(entry -> now.isAfter(entry.getValue().expires));
        requireSupported(request.type());
        if (request.type() == OperationType.SCAN_EXISTING_CHUNKS) {
            return previewScan(owner, request, context);
        }
        List<MarkedChunk> candidates = OperationTargets.resolve(request.targets(), marks.getMarkedChunks());
        List<PreviewRejection> rejected = new ArrayList<>();
        List<MarkedChunk> accepted = new ArrayList<>();
        for (MarkedChunk chunk : candidates) {
            World world = Bukkit.getWorld(chunk.world());
            if (world == null) {
                rejected.add(reject(chunk, "WORLD_NOT_FOUND"));
                continue;
            }
            if (!worlds.isAllowed(chunk.world(), WorldAccessPolicy.Scope.REGEN)) {
                rejected.add(reject(chunk, "WORLD_NOT_ALLOWED"));
                continue;
            }
            if (marks.getLandProtection().hasClaim(world, chunk.cx(), chunk.cz())) {
                rejected.add(reject(chunk, "PROTECTION_BLOCKED"));
                continue;
            }
            ChunkKey key = new ChunkKey(chunk.world(), chunk.cx(), chunk.cz());
            if (externalProtection.decisions().getOrDefault(key,
                ProtectionDecision.ABSTAIN) == ProtectionDecision.DENY) {
                rejected.add(new PreviewRejection(Optional.of(key), externalProtection.reasonCodes().getOrDefault(key,
                    "PROTECTION_BLOCKED"), Optional.empty()));
                continue;
            }
            if (!generators.inspect(chunk.world()).supports(GeneratorCapability.REGENERATION)) {
                rejected.add(reject(chunk, "UNSUPPORTED_CHUNK_GENERATOR"));
                continue;
            }
            accepted.add(chunk);
        }
        if (candidates.isEmpty()) {
            rejected.add(new PreviewRejection(Optional.empty(), "NO_ELIGIBLE_TARGETS", Optional.empty()));
        }
        ResetStrategyPlanner.Plan plan = request.type() == OperationType.RESET ? resets.previewResetBulk(accepted)
            : new ResetStrategyPlanner.Plan(List.of(), List.of(), accepted);
        String fingerprint = fingerprint(request, accepted, plan);
        PreviewToken token = new PreviewToken(UUID.randomUUID().toString());
        Instant expires = Instant.now().plus(PREVIEW_TTL);
        boolean executable = !accepted.isEmpty() && rejected.isEmpty() && !plan.isEmpty();
        Preview stored = new Preview(token, owner.getName(), request, context, List.copyOf(accepted), plan, fingerprint,
            expires, executable, null);
        previews.put(token.value(), stored);
        return new OperationPreview(token, expires, executable, candidates.size(), accepted.size(), candidates.size()
            - accepted.size(), plan.regenerateChunks().size(), plan.deleteChunks().size(), plan.deleteRegions().size(),
            rejected);
    }

    CompletionStage<OperationHandle> submit(Plugin owner, PreviewToken token, String key) {
        if (key == null || key.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("idempotencyKey must not be blank"));
        }
        Preview preview = previews.get(token.value());
        if (preview == null) {
            try {
                return CompletableFuture.completedFuture(replaySubmission(owner, token, key));
            } catch (IllegalStateException failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }
        if (Instant.now().isAfter(preview.expires)) {
            previews.remove(token.value(), preview);
            return CompletableFuture.failedFuture(new IllegalStateException("PREVIEW_EXPIRED"));
        }
        if (!preview.owner.equals(owner.getName())) {
            return CompletableFuture.failedFuture(new IllegalStateException("PREVIEW_OWNER_MISMATCH"));
        }
        List<ChunkKey> chunks = preview.targets.stream()
            .map(chunk -> new ChunkKey(chunk.world(), chunk.cx(),
            chunk.cz()))
            .toList();
        return integrations.check(actionFor(preview.request.type()), chunks).thenCompose(protection -> onGlobal(
            () -> submitValidated(owner, token, key, protection)));
    }

    private OperationHandle submitValidated(Plugin owner, PreviewToken token, String key,
        ProtectionBatchResult externalProtection) {
        Preview preview = previews.get(token.value());
        if (preview == null) {
            return replaySubmission(owner, token, key);
        }
        if (Instant.now().isAfter(preview.expires)) {
            previews.remove(token.value(), preview);
            throw new IllegalStateException("PREVIEW_EXPIRED");
        }
        if (!preview.owner.equals(owner.getName())) {
            throw new IllegalStateException("PREVIEW_OWNER_MISMATCH");
        }
        if (!previews.remove(token.value(), preview)) {
            throw new IllegalStateException("PREVIEW_EXPIRED");
        }
        if (!preview.executable) {
            throw new IllegalStateException("PREVIEW_REJECTED");
        }
        String idKey = owner.getName() + '\u0000' + key;
        Idempotency previous = idempotency.get(idKey);
        if (previous != null) {
            if (!previous.fingerprint.equals(preview.fingerprint)) {
                throw new IllegalStateException("IDEMPOTENCY_CONFLICT");
            }
            submittedPreviews.put(token.value(), new SubmittedPreview(owner.getName(), key, previous.id,
                preview.expires));
            return new OperationHandle(new OperationId(previous.id));
        }
        revalidate(preview, externalProtection);
        UUID id = UUID.randomUUID();
        MutableOperation operation = new MutableOperation(id, preview);
        operations.put(id, operation);
        idempotency.put(idKey, new Idempotency(id, preview.fingerprint));
        submittedPreviews.put(token.value(), new SubmittedPreview(owner.getName(), key, id, preview.expires));
        GlobalRegionScheduler globalScheduler = Bukkit.getGlobalRegionScheduler();
        persist(operation, true).whenComplete((ignored, failure) -> {
            RuntimeException schedulingFailure = dispatchGlobal(globalScheduler, () -> {
                if (operation.terminal()) {
                    return;
                }
                if (failure != null) {
                    operation.failed(new ApiError("OPERATION_PERSISTENCE_FAILED",
                        "Operation could not be recorded; no changes were made.", Map.of()));
                    finish(operation);
                    return;
                }
                execute(operation);
            });
            if (schedulingFailure != null && !operation.terminal()) {
                failDispatch(operation, schedulingFailure, globalScheduler);
            }
        });
        return new OperationHandle(new OperationId(id));
    }

    private OperationHandle replaySubmission(Plugin owner, PreviewToken token, String key) {
        SubmittedPreview submitted = submittedPreviews.get(token.value());
        if (submitted == null || Instant.now().isAfter(submitted.expires)) {
            if (submitted != null) {
                submittedPreviews.remove(token.value(), submitted);
            }
            throw new IllegalStateException("PREVIEW_EXPIRED");
        }
        if (!submitted.owner.equals(owner.getName())) {
            throw new IllegalStateException("PREVIEW_OWNER_MISMATCH");
        }
        if (!submitted.idempotencyKey.equals(key)) {
            throw new IllegalStateException("PREVIEW_ALREADY_SUBMITTED");
        }
        return new OperationHandle(new OperationId(submitted.operationId));
    }

    Optional<OperationSnapshot> find(OperationId id) {
        MutableOperation operation = operations.get(id.value());
        return operation != null ? Optional.of(operation.snapshot()) : history.find(id.value());
    }

    OperationPage list(OperationQuery query) {
        List<OperationSnapshot> all = Stream.concat(history.snapshots().stream(), operations.values()
            .stream()
            .map(MutableOperation::snapshot))
            .filter(snapshot ->
                query.type()
                    .map(type -> type == snapshot.type())
                    .orElse(true))
            .filter(snapshot -> query.state().map(state -> state == snapshot.state()).orElse(true))
            .sorted(Comparator.comparing(OperationSnapshot::createdAt).reversed())
            .toList();
        int from = Math.min(query.offset(), all.size());
        int to = Math.min(from + query.limit(), all.size());
        return new OperationPage(all.size(), all.subList(from, to));
    }

    CancelResult cancel(OperationId id) {
        MutableOperation operation = operations.get(id.value());
        if (operation == null) {
            return new CancelResult(id, false, history.contains(id.value()) ? "OPERATION_TERMINAL"
                : "OPERATION_NOT_FOUND");
        }
        if (operation.terminal()) {
            return new CancelResult(id, false, "OPERATION_TERMINAL");
        }
        if (operation.cancellationRequested()) {
            return new CancelResult(id, true, "CANCELLATION_PENDING");
        }
        if (!operation.deletionJobs.isEmpty() && !deletions.cancelAll(operation.deletionJobs)) {
            return new CancelResult(id, false, "OPERATION_WORK_NOT_CANCELLABLE");
        }
        if (operation.usesRegeneration) {
            var queue = marks.getRegenerationQueue();
            synchronized (operation) {
                operation.cancelling();
            }
            persist(operation, false);
            publish(operation, false);
            queue.cancel();
            monitor(operation);
            return new CancelResult(id, true, operation.terminal() ? "CANCELLED" : "CANCELLATION_PENDING");
        }
        if (operation.scan != null) {
            scans.cancel(operation.scan.world);
        }
        operation.cancelled();
        finish(operation);
        return new CancelResult(id, true, "CANCELLED");
    }

    void deletionJobsCancelled(Collection<DeletionService.CancelledDeletionJob> cancelledJobs) {
        cancelledJobs.forEach(cancelledJob -> cancelledJob.operationIds().forEach(operationId -> {
            MutableOperation operation = operations.get(operationId);
            if (operation != null && !operation.terminal()) {
                operation.cancelledDeletionJobs.add(cancelledJob.id());
            }
        }));
    }

    void deactivate() {
        active = false;
        previews.clear();
    }

    boolean stopPersistence(Duration timeout) {
        synchronized (persistenceLifecycle) {
            persistenceStopped = true;
        }
        List<CompletableFuture<Void>> pendingWrites = operations.values()
            .stream()
            .map(operation -> {
                synchronized (operation) {
                    return operation.persistence;
                }
            })
            .toList();
        try {
            CompletableFuture.allOf(pendingWrites.toArray(CompletableFuture[]::new)).get(timeout.toMillis(),
                TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (TimeoutException timeoutFailure) {
            return false;
        } catch (java.util.concurrent.ExecutionException ignored) {
            return false;
        }
    }

    void invalidatePreviews() {
        previews.clear();
    }

    private void execute(MutableOperation op) {
        if (!active) {
            op.failed("PLUGIN_DISABLED");
            finish(op);
            return;
        }
        try {
            if (op.scan != null) {
                op.running();
                persist(op, false);
                publish(op, false);
                World world = Bukkit.getWorld(op.scan.world);
                if (world == null) {
                    op.failed(new ApiError("WORLD_NOT_FOUND", "The target world is not available.", Map.of()));
                    finish(op);
                    return;
                }
                GlobalRegionScheduler globalScheduler = Bukkit.getGlobalRegionScheduler();
                op.scanFuture = scans.scan(world, new DiskChunkScanner.ScanArea(op.scan.centerX, op.scan.centerZ,
                    op.scan.radius), null).whenComplete((result, failure) -> {
                        RuntimeException schedulingFailure = dispatchGlobal(globalScheduler, () -> {
                            if (op.terminal()) {
                                return;
                            }
                            if (failure != null) {
                                op.failed(new ApiError("SCAN_FAILED", "Chunk scan failed; check server logs.",
                                    Map.of()));
                            } else if (result.cancelled()) {
                                op.cancelled();
                            } else {
                                op.completed = result.regionsScanned();
                                op.succeeded();
                            }
                            finish(op, globalScheduler);
                        });
                        if (schedulingFailure != null && !op.terminal()) {
                            failDispatch(op, schedulingFailure, globalScheduler);
                        }
                    });
                return;
            }

            Runnable prepare = () -> {
                op.running();
                persist(op, false);
                List<DeletionService.OperationDeletionRequest> deletionRequests = new ArrayList<>();
                for (ResetStrategyPlanner.RegionTarget region : op.plan.deleteRegions()) {
                    deletionRequests.add(new DeletionService.OperationDeletionRequest(DeletionService.Type.REGION_PRUNE,
                        region.world(), region.region().x(), region.region().z()));
                }
                for (MarkedChunk chunk : op.plan.deleteChunks()) {
                    deletionRequests.add(new DeletionService.OperationDeletionRequest(DeletionService.Type.CHUNK_DELETE,
                        chunk.world(), chunk.cx(), chunk.cz()));
                }
                op.deletionJobs.addAll(deletions.queueOperation(op.id, deletionRequests));
                if (!op.deletionJobs.isEmpty()) {
                    persist(op, false);
                }
                publish(op, false);
            };

            if (!op.plan.regenerateChunks().isEmpty()) {
                op.usesRegeneration = true;
                boolean started = marks.getRegenerationQueue().start(op.plan.regenerateChunks(), Audience.empty(),
                    completed -> marks.onChunksRegenComplete(completed,
                        task -> Bukkit.getGlobalRegionScheduler().execute(plugin, task)).whenComplete((ignored,
                            failure) -> {
                                if (failure != null) {
                                    Logging.logger().severe("Failed to persist mark removals after regeneration", failure);
                                }
                            }), prepare, () -> monitor(op));
                if (!started) {
                    op.failed(new ApiError("OPERATION_CONFLICT", "Another regeneration queue is already running.",
                        Map.of()));
                    finish(op);
                    return;
                }
            } else {
                prepare.run();
            }
            monitor(op);
        } catch (RuntimeException failure) {
            op.failed(new ApiError("INTERNAL_ERROR", "An internal error occurred; check server logs.", Map.of()));
            finish(op);
        }
    }

    private void monitor(MutableOperation op) {
        synchronized (op) {
            monitorLocked(op);
        }
    }

    private void monitorLocked(MutableOperation op) {
        if (op.terminal()) {
            return;
        }
        int completed = op.plan.regenerateChunks().isEmpty() ? 0 : marks.getRegenerationQueue().getCompletedCount();
        op.progress(completed);
        boolean regenDone = !op.usesRegeneration || !marks.getRegenerationQueue().isRunning();
        if (op.cancellationRequested()) {
            if (!regenDone) {
                scheduleMonitor(op);
                return;
            }
            if (marks.getRegenerationQueue().hasFailed()) {
                op.failed(new ApiError("REGENERATION_FAILED", "Chunk regeneration failed; check server logs.",
                    Map.of()));
            } else {
                op.cancelled();
            }
            finish(op);
            return;
        }
        if (op.usesRegeneration && marks.getRegenerationQueue().isCancelled()) {
            op.failed(new ApiError("REGENERATION_FAILED", "Chunk regeneration failed; check server logs.", Map.of()));
            finish(op);
            return;
        }
        boolean deletionsDone = true;
        Set<UUID> activeDeletionJobs = new HashSet<>();
        for (DeletionService.JobSnapshot job : deletions.snapshots()) {
            if (!op.deletionJobs.contains(job.id())) {
                continue;
            }
            deletionsDone = false;
            activeDeletionJobs.add(job.id());
            if (job.state() == DeletionService.State.FAILED) {
                String message = job.failure();
                if (message == null || message.isBlank()) {
                    message = "Deletion job failed.";
                }
                op.failed(new ApiError("DELETION_FAILED", message, Map.of("deletionJobId", job.id().toString())));
                finish(op);
                return;
            }
        }
        if (regenDone && deletionsDone) {
            Set<UUID> cancelledJobs = new HashSet<>(op.cancelledDeletionJobs);
            cancelledJobs.retainAll(op.deletionJobs);
            int completedDeletions = op.deletionJobs.size() - activeDeletionJobs.size() - cancelledJobs.size();
            int completedWork = (op.usesRegeneration ? marks.getRegenerationQueue().getCompletedCount() : 0)
                + completedDeletions;
            if (cancelledJobs.isEmpty()) {
                op.succeeded();
            } else if (completedWork == 0 && cancelledJobs.size() == op.deletionJobs.size() && !op.usesRegeneration) {
                op.cancelled();
            } else {
                op.partiallySucceeded(completedWork);
            }
            finish(op);
            return;
        }
        scheduleMonitor(op);
    }

    private void scheduleMonitor(MutableOperation op) {
        GlobalRegionScheduler globalScheduler = Bukkit.getGlobalRegionScheduler();
        try {
            globalScheduler.runDelayed(plugin, task -> monitor(op), 20L);
        } catch (RuntimeException failure) {
            if (op.cancellationRequested()) {
                plugin.getLogger().log(Level.WARNING, "Could not reschedule the pending cancellation monitor for "
                    + "operation " + op.id + "; the regeneration completion callback will finalize it.", failure);
                return;
            }
            failDispatch(op, failure, globalScheduler);
        }
    }

    private void revalidate(Preview preview, ProtectionBatchResult externalProtection) {
        if (!active) {
            throw new IllegalStateException("PLUGIN_DISABLED");
        }
        if (preview.scan != null) {
            World world = Bukkit.getWorld(preview.scan.world);
            if (world == null || !worlds.isAllowed(preview.scan.world, WorldAccessPolicy.Scope.BULK_MARK)) {
                throw new IllegalStateException("PREVIEW_STALE");
            }
            return;
        }
        List<MarkedChunk> current = OperationTargets.resolve(preview.request.targets(), marks.getMarkedChunks());
        ResetStrategyPlanner.Plan plan = preview.request.type() == OperationType.RESET ? resets.previewResetBulk(
            current) : new ResetStrategyPlanner.Plan(List.of(), List.of(), current);
        String now = fingerprint(preview.request, current, plan);
        if (!now.equals(preview.fingerprint)) {
            throw new IllegalStateException("PREVIEW_STALE");
        }
        for (MarkedChunk chunk : current) {
            World world = Bukkit.getWorld(chunk.world());
            if (world == null || !worlds.isAllowed(chunk.world(), WorldAccessPolicy.Scope.REGEN)
                || marks.getLandProtection().hasClaim(world, chunk.cx(), chunk.cz())) {
                throw new IllegalStateException("PREVIEW_STALE");
            }
            if (!generators.inspect(chunk.world()).supports(GeneratorCapability.REGENERATION)) {
                throw new IllegalStateException("GENERATOR_CAPABILITY_CHANGED");
            }
            ChunkKey key = new ChunkKey(chunk.world(), chunk.cx(), chunk.cz());
            if (externalProtection.decisions().getOrDefault(key,
                ProtectionDecision.ABSTAIN) == ProtectionDecision.DENY) {
                throw new IllegalStateException("PREVIEW_STALE");
            }
        }
    }

    private OperationPreview previewScan(Plugin owner, MaintenanceRequest request, RequestContext context) {
        if (!(request.targets() instanceof ChunkRadius radius)) {
            throw new IllegalArgumentException("SCAN_REQUIRES_CHUNK_RADIUS");
        }
        World world = Bukkit.getWorld(radius.world());
        List<PreviewRejection> rejections = new ArrayList<>();
        if (world == null) {
            rejections.add(new PreviewRejection(Optional.empty(), "WORLD_NOT_FOUND", Optional.empty()));
        } else if (!worlds.isAllowed(radius.world(), WorldAccessPolicy.Scope.BULK_MARK)) {
            rejections.add(new PreviewRejection(Optional.empty(), "WORLD_NOT_ALLOWED", Optional.empty()));
        }
        int total = world == null ? 0
            : scans.countRegions(world, new DiskChunkScanner.ScanArea(radius.centerX(), radius.centerZ(),
                radius.radius()));
        PreviewToken token = new PreviewToken(UUID.randomUUID().toString());
        Instant expires = Instant.now().plus(PREVIEW_TTL);
        Scan scan = new Scan(radius.world(), radius.centerX(), radius.centerZ(), radius.radius(), total);
        boolean executable = rejections.isEmpty();
        Preview preview = new Preview(token, owner.getName(), request, context, List.of(),
            new ResetStrategyPlanner.Plan(List.of(), List.of(), List.of()), fingerprint(request, List.of(),
                new ResetStrategyPlanner.Plan(List.of(), List.of(), List.of())), expires, executable, scan);
        previews.put(token.value(), preview);
        return new OperationPreview(token, expires, executable, 1, executable ? 1 : 0, rejections.size(), 0, 0, 0,
            rejections);
    }

    private List<ChunkKey> requestedChunks(TargetSelection selection) {
        if (selection instanceof ExplicitChunks chunks) {
            return List.copyOf(chunks.chunks());
        }
        return OperationTargets.resolve(selection, marks.getMarkedChunks())
            .stream()
            .map(chunk -> new ChunkKey(
            chunk.world(), chunk.cx(), chunk.cz()))
            .toList();
    }

    private static MaintenanceAction actionFor(OperationType type) {
        return switch (type) {
            case REGENERATE -> MaintenanceAction.REGENERATE;
            case RESET -> MaintenanceAction.DELETE_CHUNK;
            case SCAN_EXISTING_CHUNKS, SCAN_BIOMES, MARK_SCAN_RESULTS -> MaintenanceAction.MARK;
            case DELETE_CHUNKS -> MaintenanceAction.DELETE_CHUNK;
            case PRUNE_REGIONS, PRUNE_EMPTY_REGIONS -> MaintenanceAction.PRUNE_REGION;
        };
    }

    private <T> CompletableFuture<T> onGlobal(Supplier<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        GlobalRegionScheduler globalScheduler = Bukkit.getGlobalRegionScheduler();
        RuntimeException schedulingFailure = dispatchGlobal(globalScheduler, () -> {
            try {
                result.complete(action.get());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        if (schedulingFailure != null) {
            result.completeExceptionally(schedulingFailure);
        }
        return result;
    }

    private static PreviewRejection reject(MarkedChunk chunk, String code) {
        return new PreviewRejection(Optional.of(new ChunkKey(chunk.world(), chunk.cx(), chunk.cz())), code,
            Optional.empty());
    }

    private static void publish(MutableOperation operation, boolean terminal) {
        OperationSnapshot snapshot = operation.snapshot();
        Bukkit.getPluginManager().callEvent(new OperationStateChangedEvent(snapshot, operation.preview.owner));
        if (terminal) {
            Bukkit.getPluginManager().callEvent(new OperationCompletedEvent(snapshot, operation.preview.owner));
        }
    }

    private void finish(MutableOperation operation) {
        finish(operation, Bukkit.getGlobalRegionScheduler());
    }

    private void finish(MutableOperation operation, GlobalRegionScheduler globalScheduler) {
        persist(operation, false).whenComplete((ignored, failure) -> {
            RuntimeException schedulingFailure = dispatchGlobal(globalScheduler, () -> publish(operation, true));
            if (schedulingFailure != null) {
                Logging.logger().severe("Failed to publish completed operation " + operation.id, schedulingFailure);
            }
        });
    }

    private void failDispatch(MutableOperation operation, RuntimeException failure,
        GlobalRegionScheduler globalScheduler) {
        operation.failed(new ApiError("OPERATION_SCHEDULER_UNAVAILABLE",
            "Operation could not continue because the server scheduler rejected its task.", Map.of()));
        finish(operation, globalScheduler);
        Logging.logger().severe("Failed to schedule operation " + operation.id, failure);
    }

    private RuntimeException dispatchGlobal(GlobalRegionScheduler globalScheduler, Runnable task) {
        try {
            globalScheduler.execute(plugin, task);
            return null;
        } catch (RuntimeException failure) {
            return failure;
        }
    }

    private CompletableFuture<Void> persist(MutableOperation operation, boolean create) {
        synchronized (operation) {
            synchronized (persistenceLifecycle) {
                if (persistenceStopped) {
                    operation.persistenceStatus = OperationPersistenceStatus.FAILED;
                    return CompletableFuture.failedFuture(new IllegalStateException(
                        "Operation persistence has stopped"));
                }
                return persistWhileActive(operation, create);
            }
        }
    }

    private CompletableFuture<Void> persistWhileActive(MutableOperation operation, boolean create) {
        operation.persistenceStatus = OperationPersistenceStatus.PENDING;
        OperationSnapshot snapshot = operation.snapshot();
        OperationRecord record = new OperationRecord(snapshot.id().value(), snapshot.type().name(),
            snapshot.state().name(), snapshot.completed(), snapshot.total(), snapshot.createdAt().toEpochMilli(),
            snapshot.updatedAt().toEpochMilli(), operation.preview.owner, snapshot.correlationId().orElse(null),
            snapshot.failure().map(ApiError::code).orElse(null), snapshot.failure().map(ApiError::message).orElse(null),
            snapshot.failure().map(ApiError::details).orElse(Map.of()), snapshot.deletionJobIds());
        long persistenceVersion = ++operation.persistenceVersion;
        CompletableFuture<Void> write = operation.persistence.thenRunAsync(() -> {
            if (create) {
                operationStore.save(record);
            } else {
                operationStore.update(record);
            }
        });
        operation.persistence = write.handle((ignored, failure) -> null);
        return write.whenComplete((ignored, failure) -> {
            synchronized (operation) {
                if (persistenceVersion == operation.persistenceVersion) {
                    operation.persistenceStatus = failure == null ? OperationPersistenceStatus.PERSISTED
                        : OperationPersistenceStatus.FAILED;
                }
            }
            if (failure != null) {
                Logging.logger().severe("Failed to persist operation " + operation.id, failure);
            }
        });
    }

    private static void requireSupported(OperationType type) {
        if (type != OperationType.REGENERATE && type != OperationType.RESET
            && type != OperationType.SCAN_EXISTING_CHUNKS) {
            throw new IllegalArgumentException("UNSUPPORTED_OPERATION");
        }
    }

    private static String fingerprint(MaintenanceRequest request, Collection<MarkedChunk> chunks,
        ResetStrategyPlanner.Plan plan) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((request.type() + request.options().toString()).getBytes(StandardCharsets.UTF_8));
            chunks.stream()
                .sorted(Comparator.comparing(MarkedChunk::world)
                .thenComparingInt(MarkedChunk::cx)
                .thenComparingInt(MarkedChunk::cz))
                .forEach(c -> digest.update((c.world() + ":" + c.cx()
                    + ":" + c.cz() + ":" + c.markedAt() + ":" + c.structureGroupId()).getBytes(StandardCharsets.UTF_8)));
            digest.update(plan.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Preview(PreviewToken token, String owner, MaintenanceRequest request, RequestContext context,
        List<MarkedChunk> targets, ResetStrategyPlanner.Plan plan, String fingerprint, Instant expires,
        boolean executable, Scan scan) {}

    private record Scan(String world, int centerX, int centerZ, int radius, int regions) {}

    private record Idempotency(UUID id, String fingerprint) {}

    private record SubmittedPreview(String owner, String idempotencyKey, UUID operationId, Instant expires) {}

    private static final class MutableOperation {

        final UUID                          id;
        final Preview                       preview;
        final ResetStrategyPlanner.Plan     plan;
        final Scan                          scan;
        final Instant                       created               = Instant.now();
        final Set<UUID>                     deletionJobs          = ConcurrentHashMap.newKeySet();
        final Set<UUID>                     cancelledDeletionJobs = ConcurrentHashMap.newKeySet();
        volatile OperationState             state                 = OperationState.QUEUED;
        volatile OperationPersistenceStatus persistenceStatus     = OperationPersistenceStatus.PENDING;
        volatile int                        completed;
        volatile Instant                    updated               = created;
        volatile boolean                    usesRegeneration;
        volatile boolean                    cancellationRequested;
        volatile ApiError                   failure;
        volatile CompletableFuture<?>       scanFuture;
        CompletableFuture<Void>             persistence           = CompletableFuture.completedFuture(null);
        long                                persistenceVersion;

        MutableOperation(UUID id, Preview preview) {
            this.id = id;
            this.preview = preview;
            this.plan = preview.plan;
            this.scan = preview.scan;
        }

        synchronized void running() {
            state = OperationState.RUNNING;
            updated = Instant.now();
        }

        synchronized void progress(int value) {
            completed = value;
            updated = Instant.now();
        }

        synchronized void succeeded() {
            completed = total();
            state = OperationState.SUCCEEDED;
            updated = Instant.now();
        }

        synchronized void cancelled() {
            state = OperationState.CANCELLED;
            updated = Instant.now();
        }

        synchronized void cancelling() {
            cancellationRequested = true;
            state = OperationState.CANCELLING;
            updated = Instant.now();
        }

        synchronized boolean cancellationRequested() {
            return cancellationRequested;
        }

        synchronized void partiallySucceeded(int completed) {
            this.completed = completed;
            state = OperationState.PARTIALLY_SUCCEEDED;
            updated = Instant.now();
        }

        synchronized void failed(String code) {
            failed(new ApiError(code, code, Map.of()));
        }

        synchronized void failed(ApiError error) {
            state = OperationState.FAILED;
            failure = error;
            updated = Instant.now();
        }

        synchronized boolean terminal() {
            return state == OperationState.SUCCEEDED || state == OperationState.PARTIALLY_SUCCEEDED
                || state == OperationState.FAILED || state == OperationState.CANCELLED;
        }

        int total() {
            return scan == null ? plan.regenerateChunks().size() + plan.deleteChunks().size()
                + plan.deleteRegions().size() : scan.regions;
        }

        synchronized OperationSnapshot snapshot() {
            return new OperationSnapshot(new OperationId(id), preview.request.type(), state, completed, total(),
                created, updated, Optional.empty(), Optional.ofNullable(failure), preview.context.correlationId(),
                deletionJobs.stream().sorted().toList(), persistenceStatus);
        }
    }
}
