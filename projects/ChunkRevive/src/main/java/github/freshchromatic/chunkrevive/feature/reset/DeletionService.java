package github.freshchromatic.chunkrevive.feature.reset;

import github.freshchromatic.chunkrevive.bootstrap.ChunkRevivePlugin;
import github.freshchromatic.chunkrevive.config.Messages;
import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.feature.marking.MarkRegistry;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import github.freshchromatic.chunkrevive.nms.ChunkArea;
import github.freshchromatic.chunkrevive.nms.ChunkCoordinate;
import github.freshchromatic.chunkrevive.nms.ChunkStorageGateway;
import github.freshchromatic.chunkrevive.nms.EmptyRegionInfo;
import github.freshchromatic.chunkrevive.nms.NmsPlatformLoader;
import github.freshchromatic.freshlib.scheduler.ScheduledTask;
import github.freshchromatic.freshlib.scheduler.Scheduler;
import github.freshchromatic.freshlib.util.Components;
import github.freshchromatic.freshlib.util.Logging;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Waits for destructive targets to become completely cold, fences Moonrise chunk scheduling, then
 * performs deletion through Moonrise's per-Anvil-region I/O queues. Region pruning leaves a valid
 * 8 KiB Anvil header in the cache and truncates the now-unused sectors instead of unlinking an open
 * file behind RegionFileStorage's cache.
 */
public final class DeletionService {

    public enum Type { CHUNK_DELETE, REGION_PRUNE, EMPTY_REGION_PRUNE }
    public enum State { WAITING_FOR_COLD, RUNNING, FAILED }

    public record JobSnapshot(UUID id, Type type, State state, String world, int x, int z,
                              long createdAt, String failure, String waitingReason, boolean restored) {
        public String shortId() { return id.toString().substring(0, 8); }
        public String targetDisplay() {
            return type == Type.CHUNK_DELETE ? "chunk " + x + "," + z : "r." + x + "." + z;
        }
    }

    private final ChunkRevivePlugin plugin;
    private final MarkRegistry markRegistry;
    private final DeletionJobStore repository;
    private final ChunkStorageGateway chunkStorage;
    private final Map<UUID, Job> jobs = new ConcurrentHashMap<>();
    private final AtomicBoolean workerActive = new AtomicBoolean();
    private volatile PluginConfig config;
    private volatile Messages messages;
    private ScheduledTask ticker;
    private volatile boolean stopping;
    private boolean persistentJobsLoaded;
    private int completedChunkDeletesSinceReport;
    private long releasedChunkBytesSinceReport;
    private long completedChunkDeletesTotal;
    private long releasedChunkBytesTotal;
    private boolean chunkProgressReported;
    private int completedEmptyRegionsSinceReport;
    private long releasedEmptyRegionBytesSinceReport;
    private long completedEmptyRegionsTotal;
    private long releasedEmptyRegionBytesTotal;
    private boolean emptyRegionProgressReported;

    public DeletionService(ChunkRevivePlugin plugin, MarkRegistry markRegistry,
                                 DeletionJobStore repository,
                                 PluginConfig config, Messages messages) {
        this.plugin = plugin;
        this.markRegistry = markRegistry;
        this.repository = repository;
        this.chunkStorage = NmsPlatformLoader.load().chunkStorage();
        this.config = config;
        this.messages = messages;
    }

    public void start() {
        stopping = false;
        cancelTicker();
        loadPersistentJobs();
        long period = Math.max(1L, config.deletion.checkIntervalTicks);
        ticker = Scheduler.runTaskTimer(plugin, this::tick, period, period);
    }

    public void stop() {
        stopping = true;
        cancelTicker();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10L);
        while (workerActive.get() && System.nanoTime() < deadline) {
            java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(10L));
        }
        if (workerActive.get()) {
            Logging.logger().warning("Timed out waiting for an online deletion job during plugin shutdown; server region I/O owns the remaining work.");
        }
        jobs.clear();
    }

    private void cancelTicker() {
        if (ticker == null) return;
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
        Job existing = jobs.values().stream()
            .filter(j -> j.state != State.FAILED && j.type == Type.CHUNK_DELETE
                && j.world.equals(world) && j.x == chunkX && j.z == chunkZ)
            .findFirst().orElse(null);
        if (existing != null) return existing.id;
        removeFailedTarget(Type.CHUNK_DELETE, world, chunkX, chunkZ);
        Job job = new Job(UUID.randomUUID(), Type.CHUNK_DELETE, world, chunkX, chunkZ, requester);
        jobs.put(job.id, job);
        persist(List.of(job));
        notifyQueued(job);
        tick();
        return job.id;
    }

    public int queueChunks(Audience requester, Collection<MarkedChunk> chunks) {
        int added = 0;
        List<Job> addedJobs = new ArrayList<>();
        BulkQueueIndex index = new BulkQueueIndex(Type.CHUNK_DELETE);
        for (MarkedChunk chunk : chunks) {
            Target target = new Target(chunk.world(), chunk.cx(), chunk.cz());
            if (!index.reserve(target)) continue;
            removeFailedJobs(index.failedJobs(target));
            Job job = new Job(UUID.randomUUID(), Type.CHUNK_DELETE, chunk.world(), chunk.cx(), chunk.cz(), Audience.empty());
            jobs.put(job.id, job);
            addedJobs.add(job);
            added++;
        }
        persist(addedJobs);
        if (added > 0) {
            requester.sendMessage(messages.deletion.bulkQueued.withPlaceholders(
                Components.placeholder("count", String.valueOf(added))));
            tick();
        }
        return added;
    }

    public UUID queueRegion(Audience requester, String world, int regionX, int regionZ) {
        Job existing = jobs.values().stream()
            .filter(j -> j.state != State.FAILED && j.type == Type.REGION_PRUNE
                && j.world.equals(world) && j.x == regionX && j.z == regionZ)
            .findFirst().orElse(null);
        if (existing != null) return existing.id;
        removeFailedTarget(Type.REGION_PRUNE, world, regionX, regionZ);
        Job job = new Job(UUID.randomUUID(), Type.REGION_PRUNE, world, regionX, regionZ, requester);
        jobs.put(job.id, job);
        persist(List.of(job));
        notifyQueued(job);
        tick();
        return job.id;
    }

    public int queueRegions(Audience requester, Map<String, ? extends Collection<AnvilRegionPos>> regions) {
        int added = 0;
        List<Job> addedJobs = new ArrayList<>();
        BulkQueueIndex index = new BulkQueueIndex(Type.REGION_PRUNE);
        for (var entry : regions.entrySet()) {
            for (AnvilRegionPos region : entry.getValue()) {
                Target target = new Target(entry.getKey(), region.x(), region.z());
                if (!index.reserve(target)) continue;
                removeFailedJobs(index.failedJobs(target));
                Job job = new Job(UUID.randomUUID(), Type.REGION_PRUNE, entry.getKey(), region.x(), region.z(), Audience.empty());
                jobs.put(job.id, job);
                addedJobs.add(job);
                added++;
            }
        }
        persist(addedJobs);
        if (added > 0) {
            requester.sendMessage(messages.deletion.bulkRegionQueued.withPlaceholders(
                Components.placeholder("count", String.valueOf(added))));
            tick();
        }
        return added;
    }

    public CompletableFuture<List<EmptyRegionInfo>> scanEmptyRegions(World world) {
        return chunkStorage.scanEmptyRegions(world);
    }

    public int queueEmptyRegions(Audience requester, String world,
                                 Collection<EmptyRegionInfo> regions) {
        int added = 0;
        List<Job> addedJobs = new ArrayList<>();
        BulkQueueIndex index = new BulkQueueIndex(Type.EMPTY_REGION_PRUNE);
        for (EmptyRegionInfo region : regions) {
            Target target = new Target(world, region.regionX(), region.regionZ());
            if (!index.reserve(target)) continue;
            removeFailedJobs(index.failedJobs(target));
            Job job = new Job(UUID.randomUUID(), Type.EMPTY_REGION_PRUNE, world,
                region.regionX(), region.regionZ(), Audience.empty());
            jobs.put(job.id, job);
            addedJobs.add(job);
            added++;
        }
        persist(addedJobs);
        if (added > 0) {
            requester.sendMessage(messages.deletion.bulkEmptyRegionQueued.withPlaceholders(
                Components.placeholder("count", String.valueOf(added))));
            tick();
        }
        return added;
    }

    public boolean regionHasResidence(World world, AnvilRegionPos region) {
        if (!markRegistry.getLandProtection().isEnabled()) return false;
        for (int cx = region.minChunkX(); cx <= region.maxChunkX(); cx++) {
            for (int cz = region.minChunkZ(); cz <= region.maxChunkZ(); cz++) {
                if (markRegistry.getLandProtection().hasClaim(world, cx, cz)) return true;
            }
        }
        return false;
    }

    public List<JobSnapshot> snapshots() {
        return jobs.values().stream().map(Job::snapshot)
            .sorted(Comparator.comparingLong(JobSnapshot::createdAt)).toList();
    }

    public int cancelWaiting() {
        List<Job> removed = jobs.values().stream().filter(job -> job.state != State.RUNNING).toList();
        List<UUID> removedIds = new ArrayList<>(removed.size());
        for (Job job : removed) {
            if (jobs.remove(job.id, job)) removedIds.add(job.id);
        }
        repository.deleteDeletionJobs(removedIds);
        boolean activeChunkDelete = jobs.values().stream()
            .anyMatch(job -> job.type == Type.CHUNK_DELETE && job.state != State.FAILED);
        if (!activeChunkDelete) resetChunkDeletionProgress();
        boolean activeEmptyRegionPrune = jobs.values().stream()
            .anyMatch(job -> job.type == Type.EMPTY_REGION_PRUNE && job.state != State.FAILED);
        if (!activeEmptyRegionPrune) resetEmptyRegionProgress();
        return removedIds.size();
    }

    /** Cancels exactly one API-owned waiting job; running disk I/O is never interrupted. */
    public boolean cancel(UUID id) {
        Job job = jobs.get(id);
        if (job == null || job.state == State.RUNNING) return false;
        if (!jobs.remove(id, job)) return false;
        repository.deleteDeletionJob(id);
        return true;
    }

    private void notifyQueued(Job job) {
        job.requester.sendMessage(messages.deletion.queued.withPlaceholders(
            Components.placeholder("job", job.shortId())));
    }

    private void tick() {
        if (stopping || !plugin.isEnabled() || !workerActive.compareAndSet(false, true)) return;
        Thread.ofVirtual().name("cr-online-delete").start(() -> {
            try {
                int batchLimit = Math.clamp(config.deletion.regionBatchesPerCycle, 1, 64);
                Comparator<Job> priority = Comparator
                    .comparingInt((Job job) -> job.coldChecks > 0 ? 0 : 1)
                    .thenComparingLong(job -> job.lastCheckedAt)
                    .thenComparingLong(job -> job.createdAt);
                Map<DeletionBatchKey, Job> nextByBatch = new HashMap<>();
                for (Job job : jobs.values()) {
                    if (job.state != State.WAITING_FOR_COLD) continue;
                    Job current = nextByBatch.get(job.batchKey);
                    if (current == null || priority.compare(job, current) < 0) {
                        nextByBatch.put(job.batchKey, job);
                    }
                }
                List<Job> nextJobs = nextByBatch.values().stream()
                    .sorted(priority).limit(batchLimit).toList();
                for (Job next : nextJobs) {
                    if (next.type == Type.CHUNK_DELETE) {
                        int regionX = Math.floorDiv(next.x, 32);
                        int regionZ = Math.floorDiv(next.z, 32);
                        List<Job> batch = jobs.values().stream()
                            .filter(job -> job.state == State.WAITING_FOR_COLD
                                && job.type == Type.CHUNK_DELETE && job.world.equals(next.world)
                                && Math.floorDiv(job.x, 32) == regionX
                                && Math.floorDiv(job.z, 32) == regionZ)
                            .sorted(Comparator.comparingLong(job -> job.createdAt)).toList();
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
    }

    private record DeletionBatchKey(Type type, String world, int regionX, int regionZ) {}

    private void tryRunChunkBatch(List<Job> candidates) {
        if (candidates.isEmpty()) return;
        int chunkLimit = Math.clamp(config.deletion.chunksPerRegionBatch, 1, 1024);
        List<Job> limitedCandidates = candidates.size() <= chunkLimit
            ? candidates : candidates.subList(0, chunkLimit);
        World world = Bukkit.getWorld(limitedCandidates.getFirst().world);
        if (world == null) {
            limitedCandidates.forEach(job -> fail(job, "World not found"));
            return;
        }

        List<Job> batch = new ArrayList<>();
        for (Job job : limitedCandidates) {
            if (markRegistry.getLandProtection().hasClaim(world, job.x, job.z)) {
                fail(job, "Chunk is protected by Residence");
            } else {
                job.protectionChecked = true;
                batch.add(job);
            }
        }
        if (batch.isEmpty()) return;

        int generationPadding = chunkStorage.generationPadding();
        int lockPadding = generationPadding + Math.max(0, config.deletion.playerSafetyPaddingChunks);
        int minX = batch.stream().mapToInt(job -> job.x).min().orElseThrow() - lockPadding;
        int maxX = batch.stream().mapToInt(job -> job.x).max().orElseThrow() + lockPadding;
        int minZ = batch.stream().mapToInt(job -> job.z).min().orElseThrow() - lockPadding;
        int maxZ = batch.stream().mapToInt(job -> job.z).max().orElseThrow() + lockPadding;

        if (hasNearbyPlayer(world, minX, maxX, minZ, maxZ)) {
            batch.forEach(job -> {
                job.coldChecks = 0;
                job.waitingReason = messages.text("deletion-nearby-player");
            });
            return;
        }
        var blockingHolder = chunkStorage.firstHolder(world, new ChunkArea(minX, maxX, minZ, maxZ));
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
        if (!stable) return;

        blockingHolder = chunkStorage.firstHolder(world, new ChunkArea(minX, maxX, minZ, maxZ));
        if (blockingHolder.isPresent()) {
            String reason = messages.text("deletion-chunk-holder", blockingHolder.get().x(), blockingHolder.get().z());
            batch.forEach(job -> {
                job.coldChecks = 0;
                job.waitingReason = reason;
            });
            return;
        }

        List<Job> ready = new ArrayList<>();
        for (Job job : batch) {
            if (markRegistry.getLandProtection().hasClaim(world, job.x, job.z)) {
                fail(job, "Chunk is protected by Residence");
                continue;
            }
            synchronized (job) {
                if (job.state != State.WAITING_FOR_COLD || !jobs.containsKey(job.id)) continue;
                job.state = State.RUNNING;
                job.waitingReason = null;
                ready.add(job);
            }
        }
        if (ready.isEmpty()) return;

        List<UUID> readyIds = ready.stream().map(job -> job.id).toList();
        repository.updateDeletionJobStates(readyIds, State.RUNNING.name());

        try {
            long released = chunkStorage.deleteChunks(world, ready.stream()
                .map(job -> new ChunkCoordinate(job.x, job.z)).toList()).join();
            if (config.deletion.autoUnmarkAfterDelete) {
                List<MarkedChunk> marked = ready.stream()
                    .map(job -> new MarkedChunk(job.world, job.x, job.z, job.id, job.createdAt)).toList();
                markRegistry.unmarkChunksBatch(marked);
            }
            for (Job job : ready) jobs.remove(job.id);
            repository.deleteDeletionJobs(readyIds);
            recordChunkDeletionProgress(ready.size(), released);
        } catch (Throwable failure) {
            String reason = unwrap(failure).getMessage();
            ready.forEach(job -> fail(job, reason));
        }
    }

    private void tryRun(Job job) {
        World world = Bukkit.getWorld(job.world);
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
                if (markRegistry.getLandProtection().hasClaim(world, job.x, job.z)) {
                    fail(job, "Chunk is protected by Residence");
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
                    job.failure = "Region contains a Residence claim";
                    repository.updateDeletionJobState(job.id, State.FAILED.name());
                    job.requester.sendMessage(messages.deletion.protectedRegion.withPlaceholders(
                        Components.placeholder("rx", String.valueOf(region.x())),
                        Components.placeholder("rz", String.valueOf(region.z()))));
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
        int fencedMinX = minX - lockPadding;
        int fencedMaxX = maxX + lockPadding;
        int fencedMinZ = minZ - lockPadding;
        int fencedMaxZ = maxZ + lockPadding;

        // Player state belongs to entity scheduler threads on Folia. Take conservative snapshots
        // there instead of inferring player presence by manipulating Moonrise's internal
        // scheduling lock from a plugin worker.
        if (hasNearbyPlayer(world, fencedMinX, fencedMaxX, fencedMinZ, fencedMaxZ)) {
            job.coldChecks = 0;
            job.waitingReason = messages.text("deletion-nearby-player");
            return;
        }

        var blockingHolder = chunkStorage.firstHolder(
            world, new ChunkArea(fencedMinX, fencedMaxX, fencedMinZ, fencedMaxZ));
        if (blockingHolder.isPresent()) {
            job.coldChecks = 0;
            job.waitingReason = messages.text("deletion-chunk-holder", blockingHolder.get().x(), blockingHolder.get().z());
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
            deletion = prepareDeletion(
                job, world, fencedMinX, fencedMaxX, fencedMinZ, fencedMaxZ);
            if (deletion == null) return;
        } catch (Throwable failure) {
            fail(job, unwrap(failure).getMessage());
            return;
        }

        boolean aggregateEmptyRegion = job.type == Type.EMPTY_REGION_PRUNE;
        if (config.enableDebugLogs && !aggregateEmptyRegion) {
            job.requester.sendMessage(messages.deletion.started.withPlaceholders(
                Components.placeholder("job", job.shortId())));
        }
        if (!aggregateEmptyRegion) debugDeletion(job, messages.text("deletion-started"), null);
        try {
            long released = deletion.join();
            if (config.deletion.autoUnmarkAfterDelete && job.type != Type.EMPTY_REGION_PRUNE) {
                if (job.type == Type.CHUNK_DELETE) {
                    markRegistry.unmark(job.world, job.x, job.z, null);
                } else {
                    AnvilRegionPos region = new AnvilRegionPos(job.x, job.z);
                    List<github.freshchromatic.chunkrevive.feature.marking.MarkedChunk> marked = markRegistry.getMarkedChunks().stream()
                        .filter(c -> c.world().equals(job.world)
                            && c.cx() >= region.minChunkX() && c.cx() <= region.maxChunkX()
                            && c.cz() >= region.minChunkZ() && c.cz() <= region.maxChunkZ())
                        .toList();
                    markRegistry.unmarkChunksBatch(marked);
                }
            }
            jobs.remove(job.id);
            repository.deleteDeletionJob(job.id);
            if (config.enableDebugLogs && !aggregateEmptyRegion) {
                job.requester.sendMessage(messages.deletion.done.withPlaceholders(
                    Components.placeholder("job", job.shortId()),
                    Components.placeholder("bytes", formatBytes(released))));
            }
            if (aggregateEmptyRegion) {
                recordEmptyRegionProgress(1, released);
            } else {
                debugDeletion(job, messages.text("deletion-completed"), released);
            }
        } catch (Throwable failure) {
            fail(job, unwrap(failure).getMessage());
        }
    }

    private CompletableFuture<Long> prepareDeletion(Job job, World world,
                                                     int fencedMinX, int fencedMaxX,
                                                     int fencedMinZ, int fencedMaxZ) {
        if (job.state != State.WAITING_FOR_COLD || !jobs.containsKey(job.id)) return null;

        var blockingHolder = chunkStorage.firstHolder(
            world, new ChunkArea(fencedMinX, fencedMaxX, fencedMinZ, fencedMaxZ));
        if (blockingHolder.isPresent()) {
            job.coldChecks = 0;
            job.waitingReason = messages.text("deletion-chunk-holder", blockingHolder.get().x(), blockingHolder.get().z());
            return null;
        }

        // Claims can be created while a job waits for chunks to unload; validate immediately
        // before queueing the destructive I/O.
        if (job.type == Type.CHUNK_DELETE
            && markRegistry.getLandProtection().hasClaim(world, job.x, job.z)) {
            fail(job, "Chunk is protected by Residence");
            return null;
        }
        if (job.type == Type.REGION_PRUNE
            && regionHasResidence(world, new AnvilRegionPos(job.x, job.z))) {
            fail(job, "Region contains a Residence claim");
            return null;
        }
        synchronized (job) {
            if (job.state != State.WAITING_FOR_COLD || !jobs.containsKey(job.id)) return null;
            job.state = State.RUNNING;
            job.waitingReason = null;
            repository.updateDeletionJobState(job.id, State.RUNNING.name());
        }
        return switch (job.type) {
            case CHUNK_DELETE -> chunkStorage.deleteChunk(world, job.x, job.z);
            case REGION_PRUNE -> chunkStorage.pruneRegion(
                world, job.x, job.z, config.deletion.forceRegionFileToDisk);
            case EMPTY_REGION_PRUNE -> chunkStorage.pruneEmptyRegion(
                world, job.x, job.z, config.deletion.forceRegionFileToDisk);
        };
    }

    private boolean hasNearbyPlayer(World world, int minX, int maxX, int minZ, int maxZ) {
        List<Future<PlayerChunkSnapshot>> snapshots = new ArrayList<>();
        for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
            snapshots.add(Scheduler.callSyncMethod(plugin, () -> {
                if (!player.isValid()) return null;
                org.bukkit.Location location = player.getLocation();
                return new PlayerChunkSnapshot(location.getWorld().getUID(),
                    location.getBlockX() >> 4, location.getBlockZ() >> 4);
            }, () -> null, player));
        }

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        for (Future<PlayerChunkSnapshot> future : snapshots) {
            try {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) return true;
                PlayerChunkSnapshot snapshot = future.get(remaining, TimeUnit.NANOSECONDS);
                if (snapshot != null && snapshot.world().equals(world.getUID())
                    && snapshot.chunkX() >= minX && snapshot.chunkX() <= maxX
                    && snapshot.chunkZ() >= minZ && snapshot.chunkZ() <= maxZ) {
                    return true;
                }
            } catch (Throwable ignored) {
                // Failure to prove a player is outside the fence must keep deletion waiting.
                return true;
            }
        }
        return false;
    }

    private record PlayerChunkSnapshot(UUID world, int chunkX, int chunkZ) {}

    private void fail(Job job, String reason) {
        String safeReason = reason == null || reason.isBlank() ? "Unknown failure" : reason;
        job.state = State.FAILED;
        job.failure = safeReason;
        repository.updateDeletionJobState(job.id, State.FAILED.name());
        Logging.logger().severe("Online deletion job " + job.shortId() + " failed: " + safeReason);
        job.requester.sendMessage(messages.deletion.failed.withPlaceholders(
            Components.placeholder("job", job.shortId()), Components.placeholder("reason", safeReason)));
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof java.util.concurrent.CompletionException
            || current instanceof java.util.concurrent.ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(java.util.Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        return String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
    }

    private void debugDeletion(Job job, String action, Long releasedBytes) {
        if (!config.enableDebugLogs) return;
        String mode = switch (job.type) {
            case CHUNK_DELETE -> "DELETE_CHUNK";
            case REGION_PRUNE -> "DELETE_REGION";
            case EMPTY_REGION_PRUNE -> "PRUNE_EMPTY_REGION";
        };
        String target = job.type == Type.CHUNK_DELETE
            ? "chunk " + job.x + "," + job.z
            : "region r." + job.x + "." + job.z;
        String released = releasedBytes == null ? "" : messages.text("deletion-released", formatBytes(releasedBytes));
        Logging.logger().info(messages.text("deletion-log", mode, action, job.shortId(), job.world, target, released));
    }

    private synchronized void recordChunkDeletionProgress(int completed, long releasedBytes) {
        completedChunkDeletesSinceReport += completed;
        releasedChunkBytesSinceReport += releasedBytes;
        completedChunkDeletesTotal += completed;
        releasedChunkBytesTotal += releasedBytes;
        int reportEvery = Math.max(1, config.deletion.progressReportChunks);
        while (completedChunkDeletesSinceReport >= reportEvery) {
            long reportBytes = proportionalBytes(
                releasedChunkBytesSinceReport, reportEvery, completedChunkDeletesSinceReport);
            sendChunkDeletionProgress(reportEvery, reportBytes);
            chunkProgressReported = true;
            completedChunkDeletesSinceReport -= reportEvery;
            releasedChunkBytesSinceReport -= reportBytes;
        }
        boolean moreChunkDeletes = jobs.values().stream()
            .anyMatch(job -> job.type == Type.CHUNK_DELETE && job.state != State.FAILED);
        if (!moreChunkDeletes) {
            if (chunkProgressReported && completedChunkDeletesSinceReport > 0) {
                sendChunkDeletionProgress(completedChunkDeletesSinceReport, releasedChunkBytesSinceReport);
            }
            Bukkit.getConsoleSender().sendMessage(messages.deletion.bulkDone.withPlaceholders(
                Components.placeholder("count", String.valueOf(completedChunkDeletesTotal)),
                Components.placeholder("bytes", formatBytes(releasedChunkBytesTotal))));
            resetChunkDeletionProgress();
        }
    }

    private synchronized void resetChunkDeletionProgress() {
        completedChunkDeletesSinceReport = 0;
        releasedChunkBytesSinceReport = 0L;
        completedChunkDeletesTotal = 0L;
        releasedChunkBytesTotal = 0L;
        chunkProgressReported = false;
    }

    private void sendChunkDeletionProgress(int count, long releasedBytes) {
        Bukkit.getConsoleSender().sendMessage(messages.deletion.bulkProgress.withPlaceholders(
            Components.placeholder("count", String.valueOf(count)),
            Components.placeholder("bytes", formatBytes(releasedBytes))));
    }

    private synchronized void recordEmptyRegionProgress(int completed, long releasedBytes) {
        completedEmptyRegionsSinceReport += completed;
        releasedEmptyRegionBytesSinceReport += releasedBytes;
        completedEmptyRegionsTotal += completed;
        releasedEmptyRegionBytesTotal += releasedBytes;
        int reportEvery = Math.max(1, config.deletion.progressReportRegions);
        while (completedEmptyRegionsSinceReport >= reportEvery) {
            long reportBytes = proportionalBytes(
                releasedEmptyRegionBytesSinceReport, reportEvery, completedEmptyRegionsSinceReport);
            sendEmptyRegionProgress(reportEvery, reportBytes);
            emptyRegionProgressReported = true;
            completedEmptyRegionsSinceReport -= reportEvery;
            releasedEmptyRegionBytesSinceReport -= reportBytes;
        }
        boolean moreEmptyRegionPrunes = jobs.values().stream()
            .anyMatch(job -> job.type == Type.EMPTY_REGION_PRUNE && job.state != State.FAILED);
        if (!moreEmptyRegionPrunes) {
            if (emptyRegionProgressReported && completedEmptyRegionsSinceReport > 0) {
                sendEmptyRegionProgress(
                    completedEmptyRegionsSinceReport, releasedEmptyRegionBytesSinceReport);
            }
            Bukkit.getConsoleSender().sendMessage(messages.deletion.bulkEmptyRegionDone.withPlaceholders(
                Components.placeholder("count", String.valueOf(completedEmptyRegionsTotal)),
                Components.placeholder("bytes", formatBytes(releasedEmptyRegionBytesTotal))));
            resetEmptyRegionProgress();
        }
    }

    private void sendEmptyRegionProgress(int count, long releasedBytes) {
        Bukkit.getConsoleSender().sendMessage(messages.deletion.bulkEmptyRegionProgress.withPlaceholders(
            Components.placeholder("count", String.valueOf(count)),
            Components.placeholder("bytes", formatBytes(releasedBytes))));
    }

    private synchronized void resetEmptyRegionProgress() {
        completedEmptyRegionsSinceReport = 0;
        releasedEmptyRegionBytesSinceReport = 0L;
        completedEmptyRegionsTotal = 0L;
        releasedEmptyRegionBytesTotal = 0L;
        emptyRegionProgressReported = false;
    }

    private static long proportionalBytes(long bytes, int part, int whole) {
        if (bytes <= 0L || part >= whole) return bytes;
        return Math.round((double) bytes * part / whole);
    }

    private void loadPersistentJobs() {
        if (persistentJobsLoaded) return;
        persistentJobsLoaded = true;
        if (!config.deletion.resumeOnStartup) {
            repository.deleteAllDeletionJobs();
            return;
        }

        int restoredCount = 0;
        for (DeletionJob record : repository.loadDeletionJobs()) {
            try {
                Type type = Type.valueOf(record.type());
                State storedState = State.valueOf(record.state());
                State restoredState = storedState == State.RUNNING ? State.WAITING_FOR_COLD : storedState;
                Job job = new Job(record.id(), type, record.world(), record.x(), record.z(),
                    Audience.empty(), record.createdAt(), restoredState, true);
                if (restoredState == State.WAITING_FOR_COLD) {
                    job.waitingReason = storedState == State.RUNNING
                        ? messages.text("deletion-restart-cold-check") : messages.text("deletion-restored-waiting");
                }
                jobs.put(job.id, job);
                if (storedState != restoredState) {
                    repository.updateDeletionJobState(job.id, restoredState.name());
                }
                restoredCount++;
            } catch (IllegalArgumentException failure) {
                Logging.logger().severe("Ignoring invalid persistent deletion job " + record.id() + ": " + failure.getMessage());
            }
        }
        if (restoredCount > 0) {
            Logging.logger().info("Restored " + restoredCount + " persistent deletion job(s).");
        }
    }

    private void persist(Collection<Job> toPersist) {
        if (!config.deletion.resumeOnStartup || toPersist.isEmpty()) return;
        repository.saveDeletionJobs(toPersist.stream().map(Job::record).toList());
    }

    private void removeFailedTarget(Type type, String world, int x, int z) {
        jobs.values().stream()
            .filter(job -> job.state == State.FAILED && job.type == type
                && job.world.equals(world) && job.x == x && job.z == z)
            .toList().forEach(this::removeFailedJob);
    }

    private void removeFailedJobs(Collection<Job> failedJobs) {
        failedJobs.forEach(this::removeFailedJob);
    }

    private void removeFailedJob(Job job) {
        if (jobs.remove(job.id, job)) {
            repository.deleteDeletionJob(job.id);
        }
    }

    private record Target(String world, int x, int z) {}

    /** Snapshot index that keeps bulk queueing linear in the number of jobs plus requested targets. */
    private final class BulkQueueIndex {
        private final Set<Target> reserved = new HashSet<>();
        private final Map<Target, List<Job>> failed = new HashMap<>();

        private BulkQueueIndex(Type type) {
            for (Job job : jobs.values()) {
                if (job.type != type) continue;
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
        private final UUID id;
        private final Type type;
        private final String world;
        private final int x;
        private final int z;
        private final DeletionBatchKey batchKey;
        private final Audience requester;
        private final long createdAt;
        private volatile State state;
        private volatile String failure;
        private volatile String waitingReason = messages.text("deletion-not-checked");
        private volatile boolean protectionChecked;
        private volatile int coldChecks;
        private volatile long lastCheckedAt;
        private final boolean restored;

        private Job(UUID id, Type type, String world, int x, int z, Audience requester) {
            this(id, type, world, x, z, requester, System.currentTimeMillis(), State.WAITING_FOR_COLD, false);
        }

        private Job(UUID id, Type type, String world, int x, int z, Audience requester,
                    long createdAt, State state, boolean restored) {
            this.id = id;
            this.type = type;
            this.world = world;
            this.x = x;
            this.z = z;
            this.batchKey = type == Type.CHUNK_DELETE
                ? new DeletionBatchKey(type, world, Math.floorDiv(x, 32), Math.floorDiv(z, 32))
                : new DeletionBatchKey(type, world, x, z);
            this.requester = requester;
            this.createdAt = createdAt;
            this.state = state;
            this.restored = restored;
        }

        private String shortId() { return id.toString().substring(0, 8); }
        private JobSnapshot snapshot() {
            return new JobSnapshot(id, type, state, world, x, z, createdAt, failure, waitingReason, restored);
        }
        private DeletionJob record() {
            return new DeletionJob(id, type.name(), state.name(), world, x, z, createdAt);
        }
    }
}
