package github.freshchromatic.chunkrevive.feature.reset;

import github.freshchromatic.chunkrevive.bootstrap.ChunkRevivePlugin;
import github.freshchromatic.chunkrevive.config.Messages;
import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.feature.marking.MarkRegistry;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import github.freshchromatic.chunkrevive.feature.coordination.ChunkMutationCoordinator;
import github.freshchromatic.chunkrevive.api.integration.MaintenanceAction;
import github.freshchromatic.chunkrevive.api.integration.ProtectionBatchResult;
import github.freshchromatic.chunkrevive.api.integration.ProtectionDecision;
import github.freshchromatic.chunkrevive.api.model.ChunkKey;
import github.freshchromatic.chunkrevive.integration.protection.ProtectionChecks;
import github.freshchromatic.chunkrevive.nms.ChunkArea;
import github.freshchromatic.chunkrevive.nms.ChunkCoordinate;
import github.freshchromatic.chunkrevive.nms.ChunkStorageGateway;
import github.freshchromatic.chunkrevive.nms.EmptyRegionInfo;
import github.freshchromatic.chunkrevive.nms.NmsPlatformLoader;
import github.freshchromatic.freshlib.command.PlayerCommander;
import github.freshchromatic.freshlib.config.Messages.ComponentMessage;
import github.freshchromatic.freshlib.scheduler.ScheduledTask;
import github.freshchromatic.freshlib.scheduler.Scheduler;
import github.freshchromatic.freshlib.util.Components;
import github.freshchromatic.freshlib.util.Logging;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * Waits for destructive targets to become completely cold, fences Moonrise chunk scheduling, then
 * performs deletion through Moonrise's per-Anvil-region I/O queues. Region pruning leaves a valid
 * 8 KiB Anvil header in the cache and truncates the now-unused sectors instead of unlinking an open
 * file behind RegionFileStorage's cache.
 */
public final class DeletionService {

    public enum Type {
        CHUNK_DELETE, REGION_PRUNE, EMPTY_REGION_PRUNE
    }

    public enum State {
        WAITING_FOR_COLD, RUNNING, FAILED
    }

    public record JobSnapshot(UUID id, Type type, State state, String world, int x, int z, long createdAt,
        String failure, String waitingReason, boolean restored) {

        public String shortId() {
            return id.toString().substring(0, 8);
        }

        public String targetDisplay() {
            return type == Type.CHUNK_DELETE ? "chunk " + x + "," + z : "r." + x + "." + z;
        }
    }

    public record CancelledDeletionJob(UUID id, Set<UUID> operationIds) {

        public CancelledDeletionJob {
            operationIds = Set.copyOf(operationIds);
        }
    }

    public record OperationDeletionRequest(Type type, String world, int x, int z) {

        public OperationDeletionRequest {
            Objects.requireNonNull(type);
            Objects.requireNonNull(world);
            if (world.isBlank() || (type != Type.CHUNK_DELETE && type != Type.REGION_PRUNE)) {
                throw new IllegalArgumentException("Operation deletion requests require a world and a supported type");
            }
        }
    }

    private record OperationDeletionKey(Type type, String world, int x, int z) {}

    private final ChunkRevivePlugin        plugin;
    private final MarkRegistry             markRegistry;
    private final DeletionJobStore         repository;
    private final ChunkStorageGateway      chunkStorage;
    private final ProtectionChecks         protectionChecks;
    private final DeletionWorldAccess      worldAccess;
    private final ChunkMutationCoordinator chunkMutationCoordinator;
    private final Consumer<Runnable>       workerStarter;

    private final Map<UUID, Job>   jobs                = new ConcurrentHashMap<>();
    private final AtomicBoolean    workerActive        = new AtomicBoolean();
    private final DeletionProgress chunkProgress       = new DeletionProgress();
    private final DeletionProgress emptyRegionProgress = new DeletionProgress();

    private volatile PluginConfig config;
    private volatile Messages     messages;
    private volatile boolean      stopping;
    private ScheduledTask         ticker;
    private boolean               persistentJobsLoaded;

    public DeletionService(ChunkRevivePlugin plugin, MarkRegistry markRegistry, DeletionJobStore repository,
        PluginConfig config, Messages messages, ProtectionChecks protectionChecks) {
        this(plugin, markRegistry, repository, config, messages, protectionChecks,
            NmsPlatformLoader.load().chunkStorage(), new ChunkMutationCoordinator());
    }

    public DeletionService(ChunkRevivePlugin plugin, MarkRegistry markRegistry, DeletionJobStore repository,
        PluginConfig config, Messages messages, ProtectionChecks protectionChecks,
        ChunkMutationCoordinator chunkMutationCoordinator) {
        this(plugin, markRegistry, repository, config, messages, protectionChecks,
            NmsPlatformLoader.load().chunkStorage(), chunkMutationCoordinator);
    }

    DeletionService(ChunkRevivePlugin plugin, MarkRegistry markRegistry, DeletionJobStore repository,
        PluginConfig config, Messages messages, ProtectionChecks protectionChecks, ChunkStorageGateway chunkStorage) {
        this(plugin, markRegistry, repository, config, messages, protectionChecks, chunkStorage,
            new ChunkMutationCoordinator());
    }

    DeletionService(ChunkRevivePlugin plugin, MarkRegistry markRegistry, DeletionJobStore repository,
        PluginConfig config, Messages messages, ProtectionChecks protectionChecks, ChunkStorageGateway chunkStorage,
        ChunkMutationCoordinator chunkMutationCoordinator) {
        this(plugin, markRegistry, repository, config, messages, protectionChecks, chunkStorage,
            chunkMutationCoordinator, task -> Thread.ofVirtual().name("cr-online-delete").start(task));
    }

    DeletionService(ChunkRevivePlugin plugin, MarkRegistry markRegistry, DeletionJobStore repository,
        PluginConfig config, Messages messages, ProtectionChecks protectionChecks, ChunkStorageGateway chunkStorage,
        Consumer<Runnable> workerStarter) {
        this(plugin, markRegistry, repository, config, messages, protectionChecks, chunkStorage,
            new ChunkMutationCoordinator(), workerStarter);
    }

    DeletionService(ChunkRevivePlugin plugin, MarkRegistry markRegistry, DeletionJobStore repository,
        PluginConfig config, Messages messages, ProtectionChecks protectionChecks, ChunkStorageGateway chunkStorage,
        ChunkMutationCoordinator chunkMutationCoordinator, Consumer<Runnable> workerStarter) {
        this.plugin = plugin;
        this.markRegistry = markRegistry;
        this.repository = repository;
        this.chunkStorage = chunkStorage;
        this.chunkMutationCoordinator = chunkMutationCoordinator;
        this.workerStarter = workerStarter;
        this.worldAccess = new DeletionWorldAccess(plugin, markRegistry);
        this.config = config;
        this.messages = messages;
        this.protectionChecks = protectionChecks;
    }

    public void start() {
        stopping = false;
        cancelTicker();
        loadPersistentJobs();
        long period = Math.max(1L, config.deletion.checkIntervalTicks);
        ticker = Scheduler.runTaskTimer(plugin, this::tick, period, period);
    }

    public void stop() {
        stop(Duration.ofSeconds(10));
    }

    void stop(Duration workerWait) {
        stopping = true;
        cancelTicker();
        long deadline = System.nanoTime() + Math.max(0L, workerWait.toNanos());
        while (workerActive.get() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10L));
        }
        if (workerActive.get()) {
            Logging.logger().warning(
                "Timed out waiting for an online deletion job during plugin shutdown; server region I/O owns the remaining work.");
        }
        jobs.clear();
    }

    private void cancelTicker() {
        if (ticker == null) {
            return;
        }
        ticker.cancel();
        ticker = null;
    }

    public void setConfig(PluginConfig config) {
        this.config = config;
        start();
    }

    public void setMessages(Messages messages) {
        this.messages = messages;
    }

    public UUID queueChunk(Audience requester, String world, int chunkX, int chunkZ) {
        return queueSingle(requester, Type.CHUNK_DELETE, new Target(world, chunkX, chunkZ), null);
    }

    public UUID queueChunk(UUID operationId, Audience requester, String world, int chunkX, int chunkZ) {
        return queueSingle(requester, Type.CHUNK_DELETE, new Target(world, chunkX, chunkZ), operationId);
    }

    public int queueChunks(Audience requester, Collection<MarkedChunk> chunks) {
        List<Target> targets = chunks.stream().map(chunk -> new Target(chunk.world(), chunk.cx(), chunk.cz())).toList();
        return queueBulk(requester, Type.CHUNK_DELETE, targets, messages.deletion.bulkQueued);
    }

    public UUID queueRegion(Audience requester, String world, int regionX, int regionZ) {
        return queueSingle(requester, Type.REGION_PRUNE, new Target(world, regionX, regionZ), null);
    }

    public UUID queueRegion(UUID operationId, Audience requester, String world, int regionX, int regionZ) {
        return queueSingle(requester, Type.REGION_PRUNE, new Target(world, regionX, regionZ), operationId);
    }

    public synchronized List<UUID> queueOperation(UUID operationId, Collection<OperationDeletionRequest> requests) {
        Objects.requireNonNull(operationId);
        if (requests.isEmpty()) {
            return List.of();
        }

        Map<OperationDeletionKey, Job> selected = new LinkedHashMap<>();
        Map<OperationDeletionKey, Job> existingJobs = new HashMap<>();
        jobs.values()
            .stream()
            .filter(job -> job.state != State.FAILED)
            .sorted(Comparator.comparingLong(job -> job.createdAt))
            .forEach(job -> existingJobs.putIfAbsent(new OperationDeletionKey(job.type, job.world,
                job.x, job.z), job));

        List<Job> added = new ArrayList<>();
        for (OperationDeletionRequest request : requests) {
            OperationDeletionKey key = new OperationDeletionKey(request.type(), request.world(), request.x(),
                request.z());
            Job job = selected.get(key);
            if (job == null) {
                Job candidate = existingJobs.get(key);
                if (candidate != null && jobs.get(candidate.id) == candidate && candidate.state != State.FAILED) {
                    job = candidate;
                } else {
                    job = new Job(UUID.randomUUID(), request.type(), request.world(), request.x(), request.z(),
                        Audience.empty());
                    added.add(job);
                }
                selected.put(key, job);
            }
        }

        List<Job> operationJobs = List.copyOf(selected.values());
        List<UUID> operationJobIds = operationJobs.stream().map(job -> job.id).toList();
        Set<Job> newJobs = new HashSet<>(added);
        List<UUID> existingJobIds = operationJobs.stream()
            .filter(job -> !newJobs.contains(job))
            .map(job -> job.id)
            .toList();
        if (config.deletion.resumeOnStartup) {
            repository.saveDeletionJobs(added.stream().map(Job::record).toList(), operationId, existingJobIds);
        } else {
            repository.associateOperation(operationId, operationJobIds);
        }

        operationJobs.forEach(job -> job.operationIds.add(operationId));
        added.forEach(job -> jobs.put(job.id, job));
        added.forEach(this::notifyQueued);
        if (!added.isEmpty()) {
            tick();
        }
        return requests.stream()
            .map(request -> selected.get(new OperationDeletionKey(request.type(), request.world(),
            request.x(), request.z())).id)
            .distinct()
            .toList();
    }

    private synchronized UUID queueSingle(Audience requester, Type type, Target target, UUID operationId) {
        Job existing = jobs.values()
            .stream()
            .filter(job -> job.state != State.FAILED && job.type == type
            && job.world.equals(target.world()) && job.x == target.x() && job.z == target.z())
            .findFirst()
            .orElse(null);
        if (existing != null) {
            synchronized (existing) {
                if (jobs.get(existing.id) == existing && existing.state != State.FAILED) {
                    if (operationId != null) {
                        repository.associateOperation(operationId, existing.id);
                        existing.operationIds.add(operationId);
                    }
                    return existing.id;
                }
            }
        }
        removeFailedTarget(type, target.world(), target.x(), target.z());
        Job job = new Job(UUID.randomUUID(), type, target.world(), target.x(), target.z(), requester);
        if (operationId != null) {
            job.operationIds.add(operationId);
        }
        persist(List.of(job), operationId);
        jobs.put(job.id, job);
        notifyQueued(job);
        tick();
        return job.id;
    }

    public int queueRegions(Audience requester, Map<String, ? extends Collection<AnvilRegionPos>> regions) {
        List<Target> targets = regions.entrySet()
            .stream()
            .flatMap(entry ->
                entry.getValue()
                    .stream()
                    .map(region -> new Target(entry.getKey(), region.x(), region.z())))
            .toList();
        return queueBulk(requester, Type.REGION_PRUNE, targets, messages.deletion.bulkRegionQueued);
    }

    public CompletableFuture<List<EmptyRegionInfo>> scanEmptyRegions(World world) {
        return chunkStorage.scanEmptyRegions(world);
    }

    public int queueEmptyRegions(Audience requester, String world, Collection<EmptyRegionInfo> regions) {
        List<Target> targets = regions.stream()
            .map(region -> new Target(world, region.regionX(),
            region.regionZ()))
            .toList();
        return queueBulk(requester, Type.EMPTY_REGION_PRUNE, targets, messages.deletion.bulkEmptyRegionQueued);
    }

    private synchronized int queueBulk(Audience requester, Type type, Collection<Target> targets,
        ComponentMessage message) {
        List<Job> addedJobs = new ArrayList<>();
        BulkQueueIndex index = new BulkQueueIndex(type);
        for (Target target : targets) {
            if (!index.reserve(target)) {
                continue;
            }
            removeFailedJobs(index.failedJobs(target));
            Job job = new Job(UUID.randomUUID(), type, target.world(), target.x(), target.z(), Audience.empty());
            addedJobs.add(job);
        }
        persist(addedJobs, null);
        addedJobs.forEach(job -> jobs.put(job.id, job));
        if (!addedJobs.isEmpty()) {
            requester.sendMessage(message.withPlaceholders(Components.placeholder("count", String.valueOf(
                addedJobs.size()))));
            tick();
        }
        return addedJobs.size();
    }

    public boolean regionHasResidence(World world, AnvilRegionPos region) {
        return worldAccess.regionHasResidence(world, region);
    }

    public List<JobSnapshot> snapshots() {
        return jobs.values()
            .stream()
            .map(Job::snapshot)
            .sorted(Comparator.comparingLong(JobSnapshot::createdAt))
            .toList();
    }

    public int cancelWaiting() {
        return cancelWaitingWithIds().size();
    }

    public synchronized List<CancelledDeletionJob> cancelWaitingWithIds() {
        return cancelWaitingWithIds(ignored -> {
        });
    }

    public synchronized List<CancelledDeletionJob> cancelWaitingWithIds(
        Consumer<List<CancelledDeletionJob>> onCancelled) {
        List<Job> candidates = jobs.values().stream().sorted(Comparator.comparing(job -> job.id)).toList();
        List<CancelledDeletionJob> cancelled = new ArrayList<>(candidates.size());
        for (Job job : candidates) {
            synchronized (job) {
                if (job.state == State.RUNNING || jobs.get(job.id) != job) {
                    continue;
                }

                CancelledDeletionJob cancelledJob = new CancelledDeletionJob(job.id, job.operationIds);
                repository.deleteDeletionJobs(List.of(job.id));
                onCancelled.accept(List.of(cancelledJob));
                if (jobs.remove(job.id, job)) {
                    cancelled.add(cancelledJob);
                }
            }
        }
        boolean activeChunkDelete = jobs.values()
            .stream()
            .anyMatch(job -> job.type == Type.CHUNK_DELETE
            && job.state != State.FAILED);
        if (!activeChunkDelete) {
            synchronized (this) {
                chunkProgress.reset();
            }
        }
        boolean activeEmptyRegionPrune = jobs.values()
            .stream()
            .anyMatch(job -> job.type == Type.EMPTY_REGION_PRUNE
            && job.state != State.FAILED);
        if (!activeEmptyRegionPrune) {
            synchronized (this) {
                emptyRegionProgress.reset();
            }
        }
        return List.copyOf(cancelled);
    }

    /** Cancels exactly one API-owned waiting job; running and failed jobs remain available for monitoring. */
    public boolean cancel(UUID id) {
        return cancelAll(List.of(id));
    }

    public synchronized boolean cancelAll(Collection<UUID> ids) {
        Set<UUID> uniqueIds = new HashSet<>(ids);
        if (uniqueIds.isEmpty()) {
            return true;
        }
        List<Job> candidates = uniqueIds.stream().map(jobs::get).toList();
        if (candidates.size() != uniqueIds.size()) {
            return false;
        }
        List<Job> ordered = candidates.stream().sorted(Comparator.comparing(job -> job.id)).toList();
        List<UUID> orderedIds = ordered.stream().map(job -> job.id).toList();
        return cancelWaitingJobs(ordered, orderedIds, 0);
    }

    private boolean cancelWaitingJobs(List<Job> candidates, List<UUID> ids, int index) {
        if (index < candidates.size()) {
            synchronized (candidates.get(index)) {
                return cancelWaitingJobs(candidates, ids, index + 1);
            }
        }
        if (candidates.stream().anyMatch(job -> job.state != State.WAITING_FOR_COLD || jobs.get(job.id) != job)) {
            return false;
        }
        candidates.forEach(job -> jobs.remove(job.id, job));
        try {
            if (ids.size() == 1) {
                repository.deleteDeletionJob(ids.get(0));
            } else {
                repository.deleteDeletionJobs(ids);
            }
        } catch (RuntimeException failure) {
            candidates.forEach(job -> jobs.put(job.id, job));
            throw failure;
        }
        return true;
    }

    private void notifyQueued(Job job) {
        job.requester.sendMessage(messages.deletion.queued.withPlaceholders(Components.placeholder("job",
            job.shortId())));
    }

    private void tick() {
        if (stopping || !plugin.isEnabled() || !workerActive.compareAndSet(false, true)) {
            return;
        }
        try {
            workerStarter.accept(() -> {
                try {
                    int batchLimit = Math.clamp(config.deletion.regionBatchesPerCycle, 1, 64);
                    Comparator<Job> priority = Comparator.comparingInt((Job job) -> job.coldChecks > 0 ? 0
                        : 1)
                        .thenComparingLong(job -> job.lastCheckedAt)
                        .thenComparingLong(job -> job.createdAt);
                    Map<DeletionBatchKey, Job> nextByBatch = new HashMap<>();
                    for (Job job : jobs.values()) {
                        if (job.state != State.WAITING_FOR_COLD) {
                            continue;
                        }
                        Job current = nextByBatch.get(job.batchKey);
                        if (current == null || priority.compare(job, current) < 0) {
                            nextByBatch.put(job.batchKey, job);
                        }
                    }
                    List<Job> nextJobs = nextByBatch.values().stream().sorted(priority).limit(batchLimit).toList();
                    for (Job next : nextJobs) {
                        if (next.type == Type.CHUNK_DELETE) {
                            int regionX = Math.floorDiv(next.x, 32);
                            int regionZ = Math.floorDiv(next.z, 32);
                            List<Job> batch = jobs.values()
                                .stream()
                                .filter(job -> job.state == State.WAITING_FOR_COLD
                                && job.type == Type.CHUNK_DELETE && job.world.equals(next.world) && Math.floorDiv(job.x, 32) == regionX && Math.floorDiv(job.z, 32) == regionZ)
                                .sorted(Comparator.comparingLong(job -> job.createdAt))
                                .toList();
                            long checkedAt = System.currentTimeMillis();
                            batch.forEach(job -> job.lastCheckedAt = checkedAt);
                            tryRunChunkBatch(batch);
                        } else {
                            next.lastCheckedAt = System.currentTimeMillis();
                            tryRun(next);
                        }
                    }
                } finally {
                    workerActive.set(false);
                }
            });
        } catch (RuntimeException failure) {
            workerActive.set(false);
            Logging.logger().severe("Could not start the deletion worker", failure);
        } catch (Error failure) {
            workerActive.set(false);
            throw failure;
        }
    }

    private record DeletionBatchKey(Type type, String world, int regionX, int regionZ) {}

    private void tryRunChunkBatch(List<Job> candidates) {
        if (candidates.isEmpty()) {
            return;
        }
        int chunkLimit = Math.clamp(config.deletion.chunksPerRegionBatch, 1, 1024);
        List<Job> limitedCandidates = candidates.size() <= chunkLimit ? candidates : candidates.subList(0, chunkLimit);
        World world = worldAccess.world(limitedCandidates.getFirst().world);
        if (world == null) {
            limitedCandidates.forEach(job -> fail(job, "World not found"));
            return;
        }

        List<Job> batch = new ArrayList<>();
        for (Job job : limitedCandidates) {
            if (hasClaim(world, job.x, job.z)) {
                fail(job, "Chunk is protected by a land plugin");
            } else {
                job.protectionChecked = true;
                batch.add(job);
            }
        }
        if (batch.isEmpty()) {
            return;
        }

        int generationPadding = chunkStorage.generationPadding();
        int lockPadding = generationPadding + Math.max(0, config.deletion.playerSafetyPaddingChunks);
        int minX = batch.stream().mapToInt(job -> job.x).min().orElseThrow() - lockPadding;
        int maxX = batch.stream().mapToInt(job -> job.x).max().orElseThrow() + lockPadding;
        int minZ = batch.stream().mapToInt(job -> job.z).min().orElseThrow() - lockPadding;
        int maxZ = batch.stream().mapToInt(job -> job.z).max().orElseThrow() + lockPadding;
        var fencedArea = new ChunkArea(minX, maxX, minZ, maxZ);

        if (worldAccess.hasNearbyPlayer(world, fencedArea)) {
            batch.forEach(job -> {
                job.coldChecks = 0;
                job.waitingReason = messages.text("deletion-nearby-player");
            });
            return;
        }
        var blockingHolder = chunkStorage.firstHolder(world, fencedArea);
        if (blockingHolder.isPresent()) {
            String reason = messages.text("deletion-chunk-holder", blockingHolder.get().x(), blockingHolder.get().z());
            batch.forEach(job -> {
                job.coldChecks = 0;
                job.waitingReason = reason;
            });
            return;
        }
        boolean stable = true;
        for (Job job : batch) {
            if (++job.coldChecks < 2) {
                job.waitingReason = messages.text("deletion-cold-check");
                stable = false;
            }
        }
        if (!stable) {
            return;
        }

        blockingHolder = chunkStorage.firstHolder(world, fencedArea);
        if (blockingHolder.isPresent()) {
            String reason = messages.text("deletion-chunk-holder", blockingHolder.get().x(), blockingHolder.get().z());
            batch.forEach(job -> {
                job.coldChecks = 0;
                job.waitingReason = reason;
            });
            return;
        }

        List<ChunkKey> keys = batch.stream().map(job -> new ChunkKey(job.world, job.x, job.z)).toList();
        ProtectionSnapshot protection;
        try {
            protection = checkProtection(MaintenanceAction.DELETE_CHUNK, keys);
        } catch (RuntimeException failure) {
            batch.forEach(job -> fail(job, "Protection check unavailable: " + failure.getMessage()));
            return;
        }
        if (protection.revision() != protectionChecks.revision() || stopping) {
            batch.forEach(job -> fail(job, "Protection changed before deletion"));
            return;
        }
        if (worldAccess.hasNearbyPlayer(world, fencedArea) || chunkStorage.firstHolder(world, fencedArea).isPresent()) {
            batch.forEach(job -> {
                job.coldChecks = 0;
                job.waitingReason = messages.text("deletion-cold-check");
            });
            return;
        }

        var mutationLease = chunkMutationCoordinator.tryAcquire(keys);
        if (mutationLease == null) {
            batch.forEach(job -> {
                job.coldChecks = 0;
                job.waitingReason = messages.text("deletion-cold-check");
            });
            return;
        }

        try (mutationLease) {
            if (protection.revision() != protectionChecks.revision() || stopping) {
                batch.forEach(job -> fail(job, "Protection changed before deletion"));
                return;
            }

            if (worldAccess.hasNearbyPlayer(world, fencedArea) || chunkStorage.firstHolder(world,
                fencedArea).isPresent()) {
                batch.forEach(job -> {
                    job.coldChecks = 0;
                    job.waitingReason = messages.text("deletion-cold-check");
                });
                return;
            }

            List<Job> ready = new ArrayList<>();
            for (Job job : batch) {
                if (protection.denied(new ChunkKey(job.world, job.x, job.z)) || hasClaim(world, job.x, job.z)) {
                    fail(job, "Chunk is protected by a land plugin");
                    continue;
                }
                synchronized (job) {
                    if (job.state != State.WAITING_FOR_COLD || !jobs.containsKey(job.id)) {
                        continue;
                    }
                    job.state = State.RUNNING;
                    job.waitingReason = null;
                    ready.add(job);
                }
            }
            if (ready.isEmpty()) {
                return;
            }

            if (protection.revision() != protectionChecks.revision() || stopping) {
                ready.forEach(job -> fail(job, "Protection changed before deletion"));
                return;
            }

            List<UUID> readyIds = ready.stream().map(job -> job.id).toList();
            if (!persistRunning(ready)) {
                return;
            }
            if (protection.revision() != protectionChecks.revision() || stopping) {
                ready.forEach(job -> fail(job, "Protection changed before deletion"));
                return;
            }

            try {
                long released = chunkStorage.deleteChunks(world, ready.stream()
                    .map(job -> new ChunkCoordinate(job.x,
                    job.z))
                    .toList()).join();
                if (stopping) {
                    return;
                }
                if (config.deletion.autoUnmarkAfterDelete) {
                    List<MarkedChunk> marked = ready.stream()
                        .map(job -> new MarkedChunk(job.world, job.x, job.z,
                        job.id, job.createdAt))
                        .toList();
                    markRegistry.unmarkAfterDeletion(marked);
                }
                repository.deleteDeletionJobs(readyIds);
                for (Job job : ready) {
                    jobs.remove(job.id);
                }
                recordChunkDeletionProgress(ready.size(), released);
            } catch (RuntimeException failure) {
                DeletionFailurePolicy.rethrowFatalFailure(failure);
                String reason = unwrap(failure).getMessage();
                if (!stopping) {
                    ready.forEach(job -> fail(job, reason));
                }
            }
        }
    }

    private void tryRun(Job job) {
        World world = worldAccess.world(job.world);
        if (world == null) {
            fail(job, "World not found");
            return;
        }
        int minX;
        int maxX;
        int minZ;
        int maxZ;
        if (job.type == Type.CHUNK_DELETE) {
            if (!job.protectionChecked) {
                if (hasClaim(world, job.x, job.z)) {
                    fail(job, "Chunk is protected by a land plugin");
                    return;
                }
                job.protectionChecked = true;
            }
            minX = maxX = job.x;
            minZ = maxZ = job.z;
        } else {
            AnvilRegionPos region = new AnvilRegionPos(job.x, job.z);
            if (job.type == Type.REGION_PRUNE && !job.protectionChecked) {
                if (regionHasResidence(world, region)) {
                    job.state = State.FAILED;
                    job.failure = "Region contains a protected claim";
                    repository.updateDeletionJobState(job.id, State.FAILED.name());
                    sendRequester(job, messages.deletion.protectedRegion.withPlaceholders(Components.placeholder("rx",
                        String.valueOf(region.x())), Components.placeholder("rz", String.valueOf(region.z()))));
                    return;
                }
                job.protectionChecked = true;
            }
            minX = region.minChunkX();
            maxX = region.maxChunkX();
            minZ = region.minChunkZ();
            maxZ = region.maxChunkZ();
        }

        int generationPadding = chunkStorage.generationPadding();
        int lockPadding = generationPadding + Math.max(0, config.deletion.playerSafetyPaddingChunks);
        var fencedArea = new ChunkArea(minX - lockPadding, maxX + lockPadding, minZ - lockPadding, maxZ + lockPadding);
        var mutationLease = chunkMutationCoordinator.tryAcquire(mutationTargets(job));
        if (mutationLease == null) {
            job.coldChecks = 0;
            job.waitingReason = messages.text("deletion-cold-check");
            return;
        }

        try (mutationLease) {
            // Player state belongs to entity scheduler threads on Folia. Take conservative snapshots
            // there instead of inferring player presence by manipulating Moonrise's internal
            // scheduling lock from a plugin worker.
            if (worldAccess.hasNearbyPlayer(world, fencedArea)) {
                job.coldChecks = 0;
                job.waitingReason = messages.text("deletion-nearby-player");
                return;
            }

            var blockingHolder = chunkStorage.firstHolder(world, fencedArea);
            if (blockingHolder.isPresent()) {
                job.coldChecks = 0;
                job.waitingReason = messages.text("deletion-chunk-holder", blockingHolder.get().x(),
                    blockingHolder.get().z());
                return;
            }
            // Require a stable cold observation across two ticker passes. This gives unload
            // serialization and task-queue tickets a full interval to disappear without creating a
            // ticket merely to inspect the target.
            if (++job.coldChecks < 2) {
                job.waitingReason = messages.text("deletion-cold-check");
                return;
            }

            CompletableFuture<Long> deletion;
            try {
                // Moonrise's region I/O queue is the cross-thread serialization boundary. Do not use
                // Folia RegionScheduler here: both run() and execute() add a temporary ticket, which
                // creates the exact holder that a cold check is trying to prove absent.
                deletion = prepareDeletion(job, world, fencedArea);
                if (deletion == null) {
                    return;
                }
            } catch (RuntimeException failure) {
                DeletionFailurePolicy.rethrowFatalFailure(failure);
                if (!stopping) {
                    fail(job, unwrap(failure).getMessage());
                }
                return;
            }

            boolean aggregateEmptyRegion = job.type == Type.EMPTY_REGION_PRUNE;
            if (config.enableDebugLogs && !aggregateEmptyRegion) {
                sendRequester(job, messages.deletion.started.withPlaceholders(Components.placeholder("job",
                    job.shortId())));
            }
            if (!aggregateEmptyRegion) {
                debugDeletion(job, messages.text("deletion-started"), null);
            }
            try {
                long released = deletion.join();
                if (stopping) {
                    return;
                }
                if (config.deletion.autoUnmarkAfterDelete && job.type != Type.EMPTY_REGION_PRUNE) {
                    if (job.type == Type.CHUNK_DELETE) {
                        markRegistry.unmarkAfterDeletion(List.of(new MarkedChunk(job.world, job.x, job.z, job.id,
                            job.createdAt)));
                    } else {
                        AnvilRegionPos region = new AnvilRegionPos(job.x, job.z);
                        List<MarkedChunk> marked = markRegistry.getMarkedChunks()
                            .stream()
                            .filter(c -> c.world().equals(job.world) && c.cx() >= region.minChunkX() && c.cx() <= region.maxChunkX()
                            && c.cz() >= region.minChunkZ() && c.cz() <= region.maxChunkZ())
                            .toList();
                        markRegistry.unmarkAfterDeletion(marked);
                    }
                }
                repository.deleteDeletionJob(job.id);
                jobs.remove(job.id);
                if (config.enableDebugLogs && !aggregateEmptyRegion) {
                    sendRequester(job, messages.deletion.done.withPlaceholders(Components.placeholder("job",
                        job.shortId()), Components.placeholder("bytes", formatBytes(released))));
                }
                if (aggregateEmptyRegion) {
                    recordEmptyRegionProgress(1, released);
                } else {
                    debugDeletion(job, messages.text("deletion-completed"), released);
                }
            } catch (RuntimeException failure) {
                DeletionFailurePolicy.rethrowFatalFailure(failure);
                if (!stopping) {
                    fail(job, unwrap(failure).getMessage());
                }
            }
        }
    }

    private List<ChunkKey> mutationTargets(Job job) {
        if (job.type == Type.CHUNK_DELETE) {
            return List.of(new ChunkKey(job.world, job.x, job.z));
        }
        AnvilRegionPos region = new AnvilRegionPos(job.x, job.z);
        List<ChunkKey> keys = new ArrayList<>(AnvilRegionPos.WIDTH * AnvilRegionPos.WIDTH);
        for (int x = region.minChunkX(); x <= region.maxChunkX(); x++) {
            for (int z = region.minChunkZ(); z <= region.maxChunkZ(); z++) {
                keys.add(new ChunkKey(job.world, x, z));
            }
        }
        return keys;
    }

    private CompletableFuture<Long> prepareDeletion(Job job, World world, ChunkArea fencedArea) {
        if (job.state != State.WAITING_FOR_COLD || !jobs.containsKey(job.id)) {
            return null;
        }

        var blockingHolder = chunkStorage.firstHolder(world, fencedArea);
        if (blockingHolder.isPresent()) {
            job.coldChecks = 0;
            job.waitingReason = messages.text("deletion-chunk-holder", blockingHolder.get().x(),
                blockingHolder.get().z());
            return null;
        }

        ProtectionSnapshot protection = null;
        if (job.type != Type.EMPTY_REGION_PRUNE) {
            List<ChunkKey> keys = new ArrayList<>();
            if (job.type == Type.CHUNK_DELETE) {
                keys.add(new ChunkKey(job.world, job.x, job.z));
            } else {
                AnvilRegionPos region = new AnvilRegionPos(job.x, job.z);
                for (int x = region.minChunkX(); x <= region.maxChunkX(); x++) {
                    for (int z = region.minChunkZ(); z <= region.maxChunkZ(); z++) {
                        keys.add(new ChunkKey(job.world, x, z));
                    }
                }
            }
            MaintenanceAction action = job.type == Type.CHUNK_DELETE ? MaintenanceAction.DELETE_CHUNK
                : MaintenanceAction.PRUNE_REGION;
            protection = checkProtection(action, keys);
            if (stopping) {
                return null;
            }
            if (protection.revision() != protectionChecks.revision() || keys.stream().anyMatch(protection::denied)) {
                fail(job, "Chunk or region is protected by a land plugin");
                return null;
            }
            if (worldAccess.hasNearbyPlayer(world, fencedArea) || chunkStorage.firstHolder(world,
                fencedArea).isPresent()) {
                job.coldChecks = 0;
                job.waitingReason = messages.text("deletion-cold-check");
                return null;
            }
            if (stopping) {
                return null;
            }
            if (protection.revision() != protectionChecks.revision()) {
                fail(job, "Protection changed before deletion");
                return null;
            }
        }

        // Claims can be created while a job waits for chunks to unload; validate immediately
        // before queueing the destructive I/O.
        if (job.type == Type.CHUNK_DELETE && hasClaim(world, job.x, job.z)) {
            fail(job, "Chunk is protected by a land plugin");
            return null;
        }
        if (job.type == Type.REGION_PRUNE && regionHasResidence(world, new AnvilRegionPos(job.x, job.z))) {
            fail(job, "Region contains a protected claim");
            return null;
        }
        if (protection != null && (stopping || protection.revision() != protectionChecks.revision())) {
            fail(job, "Protection changed before deletion");
            return null;
        }
        synchronized (job) {
            if (stopping || job.state != State.WAITING_FOR_COLD || !jobs.containsKey(job.id)) {
                return null;
            }
            job.state = State.RUNNING;
            job.waitingReason = null;
            if (!persistRunning(List.of(job))) {
                return null;
            }
        }
        if (protection != null && (stopping || protection.revision() != protectionChecks.revision())) {
            fail(job, "Protection changed before deletion");
            return null;
        }
        return switch (job.type) {
            case CHUNK_DELETE -> chunkStorage.deleteChunk(world, job.x, job.z);
            case REGION_PRUNE -> chunkStorage.pruneRegion(world, job.x, job.z, config.deletion.forceRegionFileToDisk);
            case EMPTY_REGION_PRUNE -> chunkStorage.pruneEmptyRegion(world, job.x, job.z,
                config.deletion.forceRegionFileToDisk);
        };
    }

    private boolean persistRunning(List<Job> runningJobs) {
        if (stopping) {
            for (Job job : runningJobs) {
                job.state = State.WAITING_FOR_COLD;
                job.waitingReason = messages.text("deletion-persistence-unavailable");
            }
            return false;
        }
        if (!config.deletion.resumeOnStartup) {
            return true;
        }
        try {
            repository.updateDeletionJobStates(runningJobs.stream().map(job -> job.id).toList(), State.RUNNING.name());
            return true;
        } catch (RuntimeException failure) {
            Logging.logger().severe("Could not persist deletion job state; deletion will wait", failure);
            for (Job job : runningJobs) {
                job.state = State.WAITING_FOR_COLD;
                job.waitingReason = messages.text("deletion-persistence-unavailable");
            }
            return false;
        }
    }

    private record ProtectionSnapshot(long revision, ProtectionBatchResult result) {

        boolean denied(ChunkKey chunk) {
            return result.decisions().getOrDefault(chunk, ProtectionDecision.ABSTAIN) == ProtectionDecision.DENY;
        }
    }

    private ProtectionSnapshot checkProtection(MaintenanceAction action, List<ChunkKey> chunks) {
        var result = new CompletableFuture<ProtectionSnapshot>();
        try {
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                try {
                    long revision = protectionChecks.revision();
                    protectionChecks.check(action, chunks).whenComplete((value, failure) -> {
                        if (failure != null) {
                            result.completeExceptionally(failure);
                        } else if (value == null) {
                            result.completeExceptionally(new IllegalStateException("Empty protection result"));
                        } else {
                            result.complete(new ProtectionSnapshot(revision, value));
                        }
                    });
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
            return result.get(5, TimeUnit.SECONDS);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            DeletionFailurePolicy.rethrowFatalFailure(failure);
            throw new IllegalStateException("Protection provider did not respond", failure);
        }
    }

    private boolean hasClaim(World world, int chunkX, int chunkZ) {
        return worldAccess.hasClaim(world, chunkX, chunkZ);
    }

    private void fail(Job job, String reason) {
        String safeReason = reason == null || reason.isBlank() ? "Unknown failure" : reason;
        job.state = State.FAILED;
        job.failure = safeReason;
        if (!stopping) {
            try {
                repository.updateDeletionJobState(job.id, State.FAILED.name());
            } catch (RuntimeException failure) {
                Logging.logger().severe("Could not persist failed deletion job " + job.shortId(), failure);
            }
        }
        Logging.logger().severe("Online deletion job " + job.shortId() + " failed: " + safeReason);
        sendRequester(job, messages.deletion.failed.withPlaceholders(Components.placeholder("job", job.shortId()),
            Components.placeholder("reason", safeReason)));
    }

    private void sendRequester(Job job, Component message) {
        if (!plugin.isEnabled()) {
            return;
        }
        if (job.requester instanceof PlayerCommander commander) {
            scheduleNotification(() -> job.requester.sendMessage(message), commander.player());
            return;
        }
        if (job.requester instanceof Player player) {
            scheduleNotification(() -> job.requester.sendMessage(message), player);
            return;
        }
        scheduleNotification(() -> job.requester.sendMessage(message), null);
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
            && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
    }

    private void debugDeletion(Job job, String action, Long releasedBytes) {
        if (!config.enableDebugLogs) {
            return;
        }
        String mode = switch (job.type) {
            case CHUNK_DELETE -> "DELETE_CHUNK";
            case REGION_PRUNE -> "DELETE_REGION";
            case EMPTY_REGION_PRUNE -> "PRUNE_EMPTY_REGION";
        };
        String target = job.type == Type.CHUNK_DELETE ? "chunk " + job.x + "," + job.z
            : "region r." + job.x + "." + job.z;
        String released = releasedBytes == null ? "" : messages.text("deletion-released", formatBytes(releasedBytes));
        Logging.logger().info(messages.text("deletion-log", mode, action, job.shortId(), job.world, target, released));
    }

    private synchronized void recordChunkDeletionProgress(int completed, long releasedBytes) {
        for (var report : chunkProgress.record(completed, releasedBytes, config.deletion.progressReportChunks)) {
            sendChunkDeletionProgress(report);
        }
        boolean finished = jobs.values()
            .stream()
            .noneMatch(job -> job.type == Type.CHUNK_DELETE
            && job.state != State.FAILED);
        if (finished) {
            var update = chunkProgress.finish();
            update.reports().forEach(this::sendChunkDeletionProgress);
            sendConsole(messages.deletion.bulkDone.withPlaceholders(Components.placeholder("count", String.valueOf(
                update.total().count())), Components.placeholder("bytes", formatBytes(update.total().bytes()))));
        }
    }

    private void sendChunkDeletionProgress(DeletionProgress.Amount report) {
        sendConsole(messages.deletion.bulkProgress.withPlaceholders(Components.placeholder("count", String.valueOf(
            report.count())), Components.placeholder("bytes", formatBytes(report.bytes()))));
    }

    private synchronized void recordEmptyRegionProgress(int completed, long releasedBytes) {
        for (var report : emptyRegionProgress.record(completed, releasedBytes, config.deletion.progressReportRegions)) {
            sendEmptyRegionProgress(report);
        }
        boolean finished = jobs.values()
            .stream()
            .noneMatch(job -> job.type == Type.EMPTY_REGION_PRUNE
            && job.state != State.FAILED);
        if (finished) {
            var update = emptyRegionProgress.finish();
            update.reports().forEach(this::sendEmptyRegionProgress);
            sendConsole(messages.deletion.bulkEmptyRegionDone.withPlaceholders(Components.placeholder("count",
                String.valueOf(update.total().count())), Components.placeholder("bytes", formatBytes(
                    update.total().bytes()))));
        }
    }

    private void sendEmptyRegionProgress(DeletionProgress.Amount report) {
        sendConsole(messages.deletion.bulkEmptyRegionProgress.withPlaceholders(Components.placeholder("count",
            String.valueOf(report.count())), Components.placeholder("bytes", formatBytes(report.bytes()))));
    }

    private void sendConsole(Component message) {
        if (!plugin.isEnabled()) {
            return;
        }
        scheduleNotification(() -> Bukkit.getConsoleSender().sendMessage(message), null);
    }

    private void scheduleNotification(Runnable notification, Player player) {
        try {
            if (player == null) {
                Scheduler.runTask(plugin, notification);
            } else {
                Scheduler.runTask(plugin, notification, () -> {
                }, player);
            }
        } catch (RuntimeException failure) {
            Logging.logger().warning("Could not schedule deletion notification: " + failure.getMessage());
        }
    }

    private void loadPersistentJobs() {
        if (persistentJobsLoaded) {
            return;
        }
        if (!config.deletion.resumeOnStartup) {
            repository.deleteAllDeletionJobs();
            persistentJobsLoaded = true;
            return;
        }

        List<DeletionJob> storedJobs = repository.loadDeletionJobs();
        int restoredCount = 0;
        for (DeletionJob record : storedJobs) {
            try {
                Type type = Type.valueOf(record.type());
                State storedState = State.valueOf(record.state());
                State restoredState = storedState == State.RUNNING ? State.WAITING_FOR_COLD : storedState;
                Job job = new Job(record.id(), type, record.world(), record.x(), record.z(), Audience.empty(),
                    record.createdAt(), restoredState, true);
                if (restoredState == State.WAITING_FOR_COLD) {
                    job.waitingReason = storedState == State.RUNNING ? messages.text("deletion-restart-cold-check")
                        : messages.text("deletion-restored-waiting");
                }
                jobs.put(job.id, job);
                if (storedState != restoredState) {
                    repository.updateDeletionJobState(job.id, restoredState.name());
                }
                restoredCount++;
            } catch (IllegalArgumentException failure) {
                Logging.logger().severe("Ignoring invalid persistent deletion job " + record.id() + ": "
                    + failure.getMessage());
            }
        }
        persistentJobsLoaded = true;
        if (restoredCount > 0) {
            Logging.logger().info("Restored " + restoredCount + " persistent deletion job(s).");
        }
    }

    private void persist(Collection<Job> toPersist, UUID operationId) {
        if (toPersist.isEmpty()) {
            return;
        }
        List<DeletionJob> records = toPersist.stream().map(Job::record).toList();
        if (config.deletion.resumeOnStartup) {
            if (operationId == null) {
                repository.saveDeletionJobs(records);
            } else {
                repository.saveDeletionJobs(records, operationId);
            }
        } else if (operationId != null) {
            records.forEach(job -> repository.associateOperation(operationId, job.id()));
        }
    }

    private void removeFailedTarget(Type type, String world, int x, int z) {
        jobs.values()
            .stream()
            .filter(job -> job.state == State.FAILED && job.type == type && job.world.equals(world)
            && job.x == x && job.z == z)
            .toList()
            .forEach(this::removeFailedJob);
    }

    private void removeFailedJobs(Collection<Job> failedJobs) {
        failedJobs.forEach(this::removeFailedJob);
    }

    private void removeFailedJob(Job job) {
        if (!jobs.remove(job.id, job)) {
            return;
        }
        try {
            repository.deleteDeletionJob(job.id);
        } catch (RuntimeException failure) {
            jobs.put(job.id, job);
            throw failure;
        }
    }

    private record Target(String world, int x, int z) {}

    /** Snapshot index that keeps bulk queueing linear in the number of jobs plus requested targets. */
    private final class BulkQueueIndex {

        private final Set<Target>            reserved = new HashSet<>();
        private final Map<Target, List<Job>> failed   = new HashMap<>();

        private BulkQueueIndex(Type type) {
            for (Job job : jobs.values()) {
                if (job.type != type) {
                    continue;
                }
                Target target = new Target(job.world, job.x, job.z);
                if (job.state == State.FAILED) {
                    failed.computeIfAbsent(target, ignored -> new ArrayList<>()).add(job);
                } else {
                    reserved.add(target);
                }
            }
        }

        private boolean reserve(Target target) {
            return reserved.add(target);
        }

        private List<Job> failedJobs(Target target) {
            return failed.getOrDefault(target, List.of());
        }
    }

    private final class Job {

        private final UUID             id;
        private final Type             type;
        private final String           world;
        private final int              x;
        private final int              z;
        private final DeletionBatchKey batchKey;
        private final Audience         requester;
        private final Set<UUID>        operationIds  = ConcurrentHashMap.newKeySet();
        private final long             createdAt;
        private volatile State         state;
        private volatile String        failure;
        private volatile String        waitingReason = messages.text("deletion-not-checked");
        private volatile boolean       protectionChecked;
        private volatile int           coldChecks;
        private volatile long          lastCheckedAt;
        private final boolean          restored;

        private Job(UUID id, Type type, String world, int x, int z, Audience requester) {
            this(id, type, world, x, z, requester, System.currentTimeMillis(), State.WAITING_FOR_COLD, false);
        }

        private Job(UUID id, Type type, String world, int x, int z, Audience requester, long createdAt, State state,
            boolean restored) {
            this.id = id;
            this.type = type;
            this.world = world;
            this.x = x;
            this.z = z;
            this.batchKey = type == Type.CHUNK_DELETE ? new DeletionBatchKey(type, world, Math.floorDiv(x, 32),
                Math.floorDiv(z, 32)) : new DeletionBatchKey(type, world, x, z);
            this.requester = requester;
            this.createdAt = createdAt;
            this.state = state;
            this.restored = restored;
        }

        private String shortId() {
            return id.toString().substring(0, 8);
        }

        private JobSnapshot snapshot() {
            return new JobSnapshot(id, type, state, world, x, z, createdAt, failure, waitingReason, restored);
        }

        private DeletionJob record() {
            return new DeletionJob(id, type.name(), state.name(), world, x, z, createdAt);
        }
    }
}
