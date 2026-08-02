package github.freshchromatic.chunkrevive.application.api;

import github.freshchromatic.chunkrevive.api.model.ApiError;
import github.freshchromatic.chunkrevive.api.event.OperationCompletedEvent;
import github.freshchromatic.chunkrevive.api.event.OperationStateChangedEvent;
import github.freshchromatic.chunkrevive.api.model.ChunkKey;
import github.freshchromatic.chunkrevive.api.model.RequestContext;
import github.freshchromatic.chunkrevive.api.operation.*;
import github.freshchromatic.chunkrevive.config.WorldAccessPolicy;
import github.freshchromatic.chunkrevive.feature.marking.MarkRegistry;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import github.freshchromatic.chunkrevive.feature.reset.DeletionService;
import github.freshchromatic.chunkrevive.feature.reset.ResetService;
import github.freshchromatic.chunkrevive.feature.reset.ResetStrategyPlanner;
import github.freshchromatic.chunkrevive.feature.scanning.ChunkScanService;
import github.freshchromatic.chunkrevive.feature.scanning.DiskChunkScanner;
import github.freshchromatic.chunkrevive.feature.operation.OperationStore;
import github.freshchromatic.chunkrevive.feature.operation.OperationRecord;
import github.freshchromatic.chunkrevive.api.worldgen.GeneratorCapability;
import github.freshchromatic.chunkrevive.api.worldgen.WorldGeneratorCompatibilityApi;
import github.freshchromatic.chunkrevive.api.integration.MaintenanceAction;
import github.freshchromatic.chunkrevive.api.integration.ProtectionBatchResult;
import github.freshchromatic.chunkrevive.api.integration.ProtectionDecision;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/** Coordinates the existing reset/deletion engines behind a preview-token safety boundary. */
final class OperationCoordinator {
    private static final Duration PREVIEW_TTL = Duration.ofMinutes(5);
    private final Plugin plugin;
    private final MarkRegistry marks;
    private final ResetService resets;
    private final DeletionService deletions;
    private final ChunkScanService scans;
    private final WorldGeneratorCompatibilityApi generators;
    private final DefaultIntegrationApi integrations;
    private final OperationStore operationStore;
    private final WorldAccessPolicy worlds;
    private final Map<String, Preview> previews = new ConcurrentHashMap<>();
    private final Map<UUID, MutableOperation> operations = new ConcurrentHashMap<>();
    private final Map<String, Idempotency> idempotency = new ConcurrentHashMap<>();
    private volatile boolean active = true;

    OperationCoordinator(Plugin plugin, MarkRegistry marks, ResetService resets,
                         DeletionService deletions, ChunkScanService scans,
                         WorldAccessPolicy worlds, WorldGeneratorCompatibilityApi generators,
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
    }

    CompletionStage<OperationPreview> preview(Plugin owner, MaintenanceRequest request, RequestContext context) {
        List<ChunkKey> chunks = requestedChunks(request.targets());
        return integrations.check(actionFor(request.type()), chunks)
            .thenCompose(protection -> onGlobal(() -> previewResolved(owner, request, context, protection)));
    }

    private OperationPreview previewResolved(Plugin owner, MaintenanceRequest request, RequestContext context,
                                             ProtectionBatchResult externalProtection) {
        previews.entrySet().removeIf(entry -> Instant.now().isAfter(entry.getValue().expires));
        requireSupported(request.type());
        if (request.type() == OperationType.SCAN_EXISTING_CHUNKS) return previewScan(owner, request, context);
        List<MarkedChunk> candidates = resolve(request.targets());
        List<PreviewRejection> rejected = new ArrayList<>();
        List<MarkedChunk> accepted = new ArrayList<>();
        for (MarkedChunk chunk : candidates) {
            World world = Bukkit.getWorld(chunk.world());
            if (world == null) { rejected.add(reject(chunk, "WORLD_NOT_FOUND")); continue; }
            if (!worlds.isAllowed(chunk.world(), WorldAccessPolicy.Scope.REGEN)) { rejected.add(reject(chunk, "WORLD_NOT_ALLOWED")); continue; }
            if (marks.getLandProtection().hasClaim(world, chunk.cx(), chunk.cz())) { rejected.add(reject(chunk, "PROTECTION_BLOCKED")); continue; }
            ChunkKey key = new ChunkKey(chunk.world(), chunk.cx(), chunk.cz());
            if (externalProtection.decisions().getOrDefault(key, ProtectionDecision.ABSTAIN) == ProtectionDecision.DENY) {
                rejected.add(new PreviewRejection(Optional.of(key), externalProtection.reasonCodes().getOrDefault(key, "PROTECTION_BLOCKED"), Optional.empty()));
                continue;
            }
            if (!generators.inspect(chunk.world()).supports(GeneratorCapability.REGENERATION)) {
                rejected.add(reject(chunk, "UNSUPPORTED_CHUNK_GENERATOR"));
                continue;
            }
            accepted.add(chunk);
        }
        if (candidates.isEmpty()) rejected.add(new PreviewRejection(Optional.empty(), "NO_ELIGIBLE_TARGETS", Optional.empty()));
        ResetStrategyPlanner.Plan plan = request.type() == OperationType.RESET
            ? resets.previewResetBulk(accepted)
            : new ResetStrategyPlanner.Plan(List.of(), List.of(), List.copyOf(accepted));
        String fingerprint = fingerprint(request, accepted, plan);
        PreviewToken token = new PreviewToken(UUID.randomUUID().toString());
        Instant expires = Instant.now().plus(PREVIEW_TTL);
        boolean executable = !accepted.isEmpty() && rejected.isEmpty() && !plan.isEmpty();
        Preview stored = new Preview(token, owner.getName(), request, context, List.copyOf(accepted), plan, fingerprint, expires, executable, null);
        previews.put(token.value(), stored);
        return new OperationPreview(token, expires, executable, candidates.size(), accepted.size(), rejected.size(),
            plan.regenerateChunks().size(), plan.deleteChunks().size(), plan.deleteRegions().size(), rejected);
    }

    CompletionStage<OperationHandle> submit(Plugin owner, PreviewToken token, String key) {
        Preview preview = previews.get(token.value());
        if (preview == null) return CompletableFuture.failedFuture(new IllegalStateException("PREVIEW_EXPIRED"));
        List<ChunkKey> chunks = preview.targets.stream().map(chunk -> new ChunkKey(chunk.world(), chunk.cx(), chunk.cz())).toList();
        return integrations.check(actionFor(preview.request.type()), chunks)
            .thenCompose(protection -> onGlobal(() -> submitValidated(owner, token, key, protection)));
    }

    private OperationHandle submitValidated(Plugin owner, PreviewToken token, String key,
                                            ProtectionBatchResult externalProtection) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("idempotencyKey must not be blank");
        Preview preview = previews.remove(token.value());
        if (preview == null || Instant.now().isAfter(preview.expires)) throw new IllegalStateException("PREVIEW_EXPIRED");
        if (!preview.owner.equals(owner.getName())) throw new IllegalStateException("PREVIEW_OWNER_MISMATCH");
        if (!preview.executable) throw new IllegalStateException("PREVIEW_REJECTED");
        String idKey = owner.getName() + '\u0000' + key;
        Idempotency previous = idempotency.get(idKey);
        if (previous != null) {
            if (!previous.fingerprint.equals(preview.fingerprint)) throw new IllegalStateException("IDEMPOTENCY_CONFLICT");
            return new OperationHandle(new OperationId(previous.id));
        }
        revalidate(preview, externalProtection);
        UUID id = UUID.randomUUID();
        MutableOperation operation = new MutableOperation(id, preview);
        operations.put(id, operation);
        persist(operation, true);
        idempotency.put(idKey, new Idempotency(id, preview.fingerprint));
        execute(operation);
        return new OperationHandle(new OperationId(id));
    }

    Optional<OperationSnapshot> find(OperationId id) { return Optional.ofNullable(operations.get(id.value())).map(MutableOperation::snapshot); }
    OperationPage list(OperationQuery query) {
        List<OperationSnapshot> all = operations.values().stream().map(MutableOperation::snapshot)
            .filter(snapshot -> query.type().map(type -> type == snapshot.type()).orElse(true))
            .filter(snapshot -> query.state().map(state -> state == snapshot.state()).orElse(true))
            .sorted(Comparator.comparing(OperationSnapshot::createdAt).reversed()).toList();
        int from = Math.min(query.offset(), all.size()); int to = Math.min(from + query.limit(), all.size());
        return new OperationPage(all.size(), all.subList(from, to));
    }
    CancelResult cancel(OperationId id) {
        MutableOperation operation = operations.get(id.value());
        if (operation == null) return new CancelResult(id, false, "OPERATION_NOT_FOUND");
        if (operation.terminal()) return new CancelResult(id, false, "OPERATION_TERMINAL");
        if (operation.usesRegeneration) marks.getRegenerationQueue().cancel();
        if (operation.scan != null) scans.cancel(operation.scan.world);
        for (UUID deletionId : operation.deletionJobs) deletions.cancel(deletionId);
        operation.cancelled(); persist(operation, false); publish(operation, true);
        return new CancelResult(id, true, "CANCELLED");
    }
    void deactivate() { active = false; previews.clear(); }
    void invalidatePreviews() { previews.clear(); }

    private void execute(MutableOperation op) {
        if (!active) {
            op.failed("PLUGIN_DISABLED");
            publish(op, true);
            return;
        }
        op.running(); persist(op, false); publish(op, false);
        try {
            if (op.scan != null) {
                World world = Bukkit.getWorld(op.scan.world);
                if (world == null) {
                    op.failed("WORLD_NOT_FOUND");
                    publish(op, true);
                    return;
                }
                op.scanFuture = scans.scan(world, new DiskChunkScanner.ScanArea(op.scan.centerX, op.scan.centerZ, op.scan.radius), null)
                    .whenComplete((result, failure) -> {
                        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                            if (op.terminal()) return;
                            if (failure != null) op.failed("SCAN_FAILED"); else { op.completed = result.regionsScanned(); op.succeeded(); }
                            publish(op, true);
                        });
                    });
                return;
            }
            for (ResetStrategyPlanner.RegionTarget region : op.plan.deleteRegions()) {
                op.deletionJobs.add(deletions.queueRegion(Audience.empty(), region.world(), region.region().x(), region.region().z()));
            }
            for (MarkedChunk chunk : op.plan.deleteChunks()) {
                op.deletionJobs.add(deletions.queueChunk(Audience.empty(), chunk.world(), chunk.cx(), chunk.cz()));
            }
            if (!op.plan.regenerateChunks().isEmpty()) {
                if (marks.getRegenerationQueue().isRunning()) {
                    op.failed("OPERATION_CONFLICT");
                    publish(op, true);
                    return;
                }
                op.usesRegeneration = true;
                marks.getRegenerationQueue().start(op.plan.regenerateChunks(), Audience.empty(), completed -> {
                    marks.onChunksRegenComplete(completed); monitor(op);
                });
            }
            monitor(op);
        } catch (Throwable failure) { op.failed("INTERNAL_ERROR"); publish(op, true); }
    }
    private void monitor(MutableOperation op) {
        if (op.terminal()) return;
        int completed = op.plan.regenerateChunks().isEmpty() ? 0 : marks.getRegenerationQueue().getCompletedCount();
        op.progress(completed);
        boolean regenDone = !op.usesRegeneration || !marks.getRegenerationQueue().isRunning();
        if (op.usesRegeneration && marks.getRegenerationQueue().isCancelled()) {
            op.failed("REGENERATION_FAILED");
            publish(op, true);
            return;
        }
        boolean deletionsDone = true;
        for (DeletionService.JobSnapshot job : deletions.snapshots()) {
            if (!op.deletionJobs.contains(job.id())) continue;
            deletionsDone = false;
            if (job.state() == DeletionService.State.FAILED) {
                op.failed("DELETION_FAILED");
                publish(op, true);
                return;
            }
        }
        if (regenDone && deletionsDone) {
            op.succeeded();
            persist(op, false);
            publish(op, true);
            return;
        }
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> monitor(op), 20L);
    }
    private void revalidate(Preview preview, ProtectionBatchResult externalProtection) {
        if (!active) throw new IllegalStateException("PLUGIN_DISABLED");
        if (preview.scan != null) {
            World world = Bukkit.getWorld(preview.scan.world);
            if (world == null || !worlds.isAllowed(preview.scan.world, WorldAccessPolicy.Scope.BULK_MARK)) throw new IllegalStateException("PREVIEW_STALE");
            return;
        }
        List<MarkedChunk> current = resolve(preview.request.targets());
        ResetStrategyPlanner.Plan plan = preview.request.type() == OperationType.RESET ? resets.previewResetBulk(current)
            : new ResetStrategyPlanner.Plan(List.of(), List.of(), current);
        String now = fingerprint(preview.request, current, plan);
        if (!now.equals(preview.fingerprint)) throw new IllegalStateException("PREVIEW_STALE");
        for (MarkedChunk chunk : current) {
            World world = Bukkit.getWorld(chunk.world());
            if (world == null || !worlds.isAllowed(chunk.world(), WorldAccessPolicy.Scope.REGEN) || marks.getLandProtection().hasClaim(world, chunk.cx(), chunk.cz()))
                throw new IllegalStateException("PREVIEW_STALE");
            if (!generators.inspect(chunk.world()).supports(GeneratorCapability.REGENERATION)) throw new IllegalStateException("GENERATOR_CAPABILITY_CHANGED");
            ChunkKey key = new ChunkKey(chunk.world(), chunk.cx(), chunk.cz());
            if (externalProtection.decisions().getOrDefault(key, ProtectionDecision.ABSTAIN) == ProtectionDecision.DENY) {
                throw new IllegalStateException("PREVIEW_STALE");
            }
        }
    }
    private OperationPreview previewScan(Plugin owner, MaintenanceRequest request, RequestContext context) {
        if (!(request.targets() instanceof ChunkRadius radius)) throw new IllegalArgumentException("SCAN_REQUIRES_CHUNK_RADIUS");
        World world = Bukkit.getWorld(radius.world());
        List<PreviewRejection> rejections = new ArrayList<>();
        if (world == null) rejections.add(new PreviewRejection(Optional.empty(), "WORLD_NOT_FOUND", Optional.empty()));
        else if (!worlds.isAllowed(radius.world(), WorldAccessPolicy.Scope.BULK_MARK)) rejections.add(new PreviewRejection(Optional.empty(), "WORLD_NOT_ALLOWED", Optional.empty()));
        int total = world == null ? 0 : scans.countRegions(world, new DiskChunkScanner.ScanArea(radius.centerX(), radius.centerZ(), radius.radius()));
        PreviewToken token = new PreviewToken(UUID.randomUUID().toString()); Instant expires = Instant.now().plus(PREVIEW_TTL);
        Scan scan = new Scan(radius.world(), radius.centerX(), radius.centerZ(), radius.radius(), total);
        boolean executable = rejections.isEmpty();
        Preview preview = new Preview(token, owner.getName(), request, context, List.of(), new ResetStrategyPlanner.Plan(List.of(), List.of(), List.of()),
            fingerprint(request, List.of(), new ResetStrategyPlanner.Plan(List.of(), List.of(), List.of())), expires, executable, scan);
        previews.put(token.value(), preview);
        return new OperationPreview(token, expires, executable, 1, executable ? 1 : 0, rejections.size(), 0, 0, 0, rejections);
    }
    private List<MarkedChunk> resolve(TargetSelection selection) {
        Collection<MarkedChunk> all = marks.getMarkedChunks();
        if (selection instanceof ExplicitChunks explicit) {
            Set<ChunkKey> wanted = Set.copyOf(explicit.chunks());
            return all.stream().filter(c -> wanted.contains(new ChunkKey(c.world(), c.cx(), c.cz()))).toList();
        }
        if (selection instanceof AllMarked marked) return all.stream()
            .filter(c -> marked.world().map(c.world()::equals).orElse(true))
            .filter(chunk -> switch (marked.scope()) {
                case ALL -> true;
                case INDEPENDENT -> chunk.structureGroupId() == null;
                case STRUCTURES -> chunk.structureGroupId() != null;
            })
            .toList();
        if (selection instanceof ChunkRadius radius) return all.stream().filter(c -> c.world().equals(radius.world())
            && Math.abs(c.cx() - radius.centerX()) <= radius.radius() && Math.abs(c.cz() - radius.centerZ()) <= radius.radius()).toList();
        return List.of();
    }
    private List<ChunkKey> requestedChunks(TargetSelection selection) {
        if (selection instanceof ExplicitChunks chunks) return List.copyOf(chunks.chunks());
        return resolve(selection).stream().map(chunk -> new ChunkKey(chunk.world(), chunk.cx(), chunk.cz())).toList();
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
    private <T> CompletableFuture<T> onGlobal(java.util.function.Supplier<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            try {
                result.complete(action.get());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }
    private static PreviewRejection reject(MarkedChunk chunk, String code) {
        return new PreviewRejection(
            Optional.of(new ChunkKey(chunk.world(), chunk.cx(), chunk.cz())),
            code,
            Optional.empty());
    }
    private static void publish(MutableOperation operation, boolean terminal) {
        OperationSnapshot snapshot = operation.snapshot();
        Bukkit.getPluginManager().callEvent(new OperationStateChangedEvent(snapshot, operation.preview.owner));
        if (terminal) Bukkit.getPluginManager().callEvent(new OperationCompletedEvent(snapshot, operation.preview.owner));
    }
    private void persist(MutableOperation operation, boolean create) {
        OperationSnapshot snapshot = operation.snapshot();
        OperationRecord record = new OperationRecord(
            snapshot.id().value(), snapshot.type().name(), snapshot.state().name(), snapshot.completed(), snapshot.total(),
            snapshot.createdAt().toEpochMilli(), snapshot.updatedAt().toEpochMilli(), operation.preview.owner,
            snapshot.correlationId().orElse(null), snapshot.failure().map(ApiError::code).orElse(null));
        CompletableFuture.runAsync(() -> { if (create) operationStore.save(record); else operationStore.update(record); });
    }
    private static void requireSupported(OperationType type) {
        if (type != OperationType.REGENERATE
            && type != OperationType.RESET
            && type != OperationType.SCAN_EXISTING_CHUNKS) {
            throw new IllegalArgumentException("UNSUPPORTED_OPERATION");
        }
    }
    private static String fingerprint(MaintenanceRequest request, Collection<MarkedChunk> chunks, ResetStrategyPlanner.Plan plan) {
        try { MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((request.type() + request.options().toString()).getBytes(StandardCharsets.UTF_8));
            chunks.stream().sorted(Comparator.comparing(MarkedChunk::world).thenComparingInt(MarkedChunk::cx).thenComparingInt(MarkedChunk::cz))
                .forEach(c -> digest.update((c.world()+":"+c.cx()+":"+c.cz()+":"+c.markedAt()+":"+c.structureGroupId()).getBytes(StandardCharsets.UTF_8)));
            digest.update(plan.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private record Preview(
            PreviewToken token,
            String owner,
            MaintenanceRequest request,
            RequestContext context,
            List<MarkedChunk> targets,
            ResetStrategyPlanner.Plan plan,
            String fingerprint,
            Instant expires,
            boolean executable,
            Scan scan) { }
    private record Scan(String world, int centerX, int centerZ, int radius, int regions) { }
    private record Idempotency(UUID id, String fingerprint) { }
    private static final class MutableOperation {
        final UUID id;
        final Preview preview;
        final ResetStrategyPlanner.Plan plan;
        final Scan scan;
        final Instant created = Instant.now();
        final Set<UUID> deletionJobs = ConcurrentHashMap.newKeySet();
        volatile OperationState state = OperationState.QUEUED;
        volatile int completed;
        volatile Instant updated = created;
        volatile boolean usesRegeneration;
        volatile ApiError failure;
        volatile java.util.concurrent.CompletableFuture<?> scanFuture;

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
        synchronized void failed(String code){ state=OperationState.FAILED; failure=new ApiError(code, code, Map.of()); updated=Instant.now(); }
        synchronized boolean terminal(){ return state==OperationState.SUCCEEDED||state==OperationState.FAILED||state==OperationState.CANCELLED; }
        int total(){ return scan == null ? plan.regenerateChunks().size()+plan.deleteChunks().size()+plan.deleteRegions().size() : scan.regions; }
        synchronized OperationSnapshot snapshot() {
            return new OperationSnapshot(
                new OperationId(id),
                preview.request.type(),
                state,
                completed,
                total(),
                created,
                updated,
                Optional.empty(),
                Optional.ofNullable(failure),
                preview.context.correlationId());
        }
    }
}
