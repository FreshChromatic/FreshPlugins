package github.freshchromatic.chunkrevive.feature.regeneration;

import github.freshchromatic.chunkrevive.config.Messages;
import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.config.SafetyLimitPolicy;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import github.freshchromatic.freshlib.scheduler.Scheduler;
import github.freshchromatic.freshlib.util.Components;
import github.freshchromatic.freshlib.util.Logging;
import net.kyori.adventure.audience.Audience;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class RegenerationQueue {

    private final RegenerationService engine;
    private PluginConfig config;
    private Messages messages;

    private final Queue<java.util.List<MarkedChunk>> pending = new ConcurrentLinkedQueue<>();
    private volatile boolean running = false;
    private volatile boolean cancelled = false;
    private volatile boolean failed = false;
    private Audience initiator;
    private int totalCount;
    private Consumer<java.util.Collection<MarkedChunk>> onBatchComplete;
    private long startTime;

    private final AtomicInteger activeTasks = new AtomicInteger(0);
    private final AtomicInteger completedCount = new AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicBoolean idleGcRequested = new java.util.concurrent.atomic.AtomicBoolean(false);

    // Set by MarkRegistry once the plugin instance is available
    private Plugin plugin;

    public RegenerationQueue(RegenerationService engine, PluginConfig config, Messages messages) {
        this.engine = engine;
        this.config = config;
        this.messages = messages;
    }

    public void setConfig(PluginConfig config) {
        this.config = config;
    }

    public void setMessages(Messages messages) {
        this.messages = messages;
    }

    public void setPlugin(Plugin plugin) {
        this.plugin = plugin;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isCancelled() {
        return cancelled || failed;
    }

    public int getCompletedCount() {
        return completedCount.get();
    }

    public int getTotalCount() {
        return totalCount;
    }

    public int getActiveTasks() {
        return activeTasks.get();
    }

    public int getPendingCount() {
        int count = 0;
        for (java.util.List<MarkedChunk> group : pending) {
            count += group.size();
        }
        return count;
    }

    public void start(Collection<MarkedChunk> chunks, Audience initiator, Consumer<java.util.Collection<MarkedChunk>> onBatchComplete) {
        if (running) return;
        this.running = true;
        this.cancelled = false;
        this.failed = false;
        this.initiator = initiator;
        this.totalCount = chunks.size();
        this.onBatchComplete = onBatchComplete;
        this.activeTasks.set(0);
        this.completedCount.set(0);
        this.startTime = System.currentTimeMillis();

        github.freshchromatic.chunkrevive.feature.regeneration.NmsTerrainGenerator.beginBulkSession(chunks.size());

        java.util.List<java.util.List<MarkedChunk>> workTiles = groupForBatch(chunks);
        pending.addAll(workTiles);
        int smallestTile = workTiles.stream().mapToInt(java.util.List::size).min().orElse(0);
        int largestTile = workTiles.stream().mapToInt(java.util.List::size).max().orElse(0);
        double averageTile = workTiles.stream().mapToInt(java.util.List::size).average().orElse(0.0);
        Logging.logger().info("Bulk regen planned " + workTiles.size() + " spatial work tile(s), size="
            + smallestTile + "/" + String.format(java.util.Locale.ROOT, "%.1f", averageTile) + "/"
            + largestTile + " min/avg/max target chunks, mode=" + config.regen.workTileModeEnum()
            + ", fixed=" + config.regen.fixedWorkTileSize + ", concurrency=" + effectiveConcurrency() + ".");

        initiator.sendMessage(messages.regen.batchStart.withPlaceholders(
            Components.placeholder("count", String.valueOf(totalCount))));

        processNext();
    }

    /** Removes not-yet-started chunks for a world from the pending queue (resetmark); running batches finish normally. */
    public int removePendingForWorld(String world) {
        return removePendingMatching(chunk -> chunk.world().equals(world));
    }

    /** Removes not-yet-started chunks overlapping a newly-created Residence area. */
    public int removePendingInRange(String world, int minCx, int maxCx, int minCz, int maxCz) {
        return removePendingMatching(chunk -> chunk.world().equals(world)
            && chunk.cx() >= minCx && chunk.cx() <= maxCx
            && chunk.cz() >= minCz && chunk.cz() <= maxCz);
    }

    private int removePendingMatching(Predicate<MarkedChunk> shouldRemove) {
        int removed = 0;
        for (java.util.List<MarkedChunk> group : java.util.List.copyOf(pending)) {
            java.util.List<MarkedChunk> filtered = group.stream().filter(shouldRemove.negate()).toList();
            int removedFromGroup = group.size() - filtered.size();
            if (removedFromGroup == 0 || !pending.remove(group)) continue;
            removed += removedFromGroup;
            if (!filtered.isEmpty()) pending.add(filtered);
        }
        if (removed > 0) {
            totalCount = Math.max(0, totalCount - removed);
            checkFinished();
        }
        return removed;
    }

    public void cancel() {
        cancelled = true;
        pending.clear();
        checkFinished();
    }

    private void processNext() {
        if (isCancelled()) {
            checkFinished();
            return;
        }

        if (pending.isEmpty()) {
            checkFinished();
            return;
        }

        int concurrency = effectiveConcurrency();
        while (activeTasks.get() < concurrency && !pending.isEmpty()) {
            if (isHeapAtHighWatermark()) {
                // Do not admit another ProtoChunk graph while the previous batch still occupies most
                // of the heap. This is intentionally a pause, not a cancellation: completed work is
                // preserved and the queue resumes as soon as GC has reclaimed the prior batch.
                requestIdleGc();
                Scheduler.runTaskLater(plugin, this::processNext, 20L);
                return;
            }

            java.util.List<MarkedChunk> group = pending.poll();
            if (group != null) {
                activeTasks.incrementAndGet();
                engine.regenChunks(initiator, group, true, this::isCancelled).whenComplete((v, ex) -> {
                    try {
                        if (ex != null) {
                            Throwable cause = unwrapCompletionException(ex);
                            if (!(cause instanceof github.freshchromatic.chunkrevive.feature.regeneration.SkipChunkException)
                                && !(cause instanceof java.util.concurrent.CancellationException)) {
                                failed = true;
                                pending.clear();
                                Logging.logger().warning("Batch regen failed for group of " + group.size()
                                    + " chunks: " + cause.getMessage());
                            }
                        // A cancellation can arrive after Phase 2 has begun, when the current batch
                        // must still finish its disk write. Do not invoke completion callbacks after
                        // that point: on plugin shutdown they would schedule database unmarks after
                        // the database has already been closed.
                        } else {
                            completedCount.addAndGet(group.size());
                            if (!cancelled && onBatchComplete != null) {
                                try {
                                    onBatchComplete.accept(group);
                                } catch (Throwable callbackFailure) {
                                    // A display/database bookkeeping failure must never strand the queue
                                    // in Running with no future capable of starting the remaining batches.
                                    Logging.logger().severe("Batch completion callback failed for group of "
                                        + group.size() + " chunks", callbackFailure);
                                }
                            }
                        }
                    } finally {
                        int active = activeTasks.decrementAndGet();
                        if (!pending.isEmpty() && !isCancelled()) {
                            Scheduler.runTask(plugin, this::processNext);
                        }
                        if (pending.isEmpty() && active == 0) {
                            checkFinished();
                        }
                    }
                });
            }

            // Delay next start to spread the load if configured
            if (config.regen.batchDelayTicks > 0) {
                Scheduler.runTaskLater(plugin, this::processNext, config.regen.batchDelayTicks);
                break;
            }
        }
    }

    private static Throwable unwrapCompletionException(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof java.util.concurrent.CompletionException
            || current instanceof java.util.concurrent.ExecutionException)
            && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private void checkFinished() {
        if (running && pending.isEmpty() && activeTasks.get() == 0) {
            running = false;
            github.freshchromatic.chunkrevive.feature.regeneration.NmsTerrainGenerator.endBulkSession();
            if (!cancelled && !failed) {
                long elapsedMs = System.currentTimeMillis() - startTime;
                initiator.sendMessage(messages.regen.batchDone.withPlaceholders(
                    Components.placeholder("count", String.valueOf(totalCount)),
                    Components.placeholder("elapsed", engine.formatDuration(elapsedMs)),
                    Components.placeholder("elapsed_ms", String.valueOf(elapsedMs))));
            }
        }
    }

    private java.util.List<java.util.List<MarkedChunk>> groupForBatch(Collection<MarkedChunk> chunks) {
        int maxSize = effectiveBatchSize();
        boolean regenFullStructureRange = config.structure.regen.regenFullStructureRange;
        boolean regenFullBiomeRange = config.biome.regen.regenFullBiomeRange;

        java.util.Map<java.util.UUID, java.util.List<MarkedChunk>> byStructureGroup = new java.util.LinkedHashMap<>();
        java.util.List<MarkedChunk> biomeAtomic = new java.util.ArrayList<>();
        java.util.List<MarkedChunk> capped = new java.util.ArrayList<>();

        for (MarkedChunk c : chunks) {
            if (c.structureGroupId() != null && regenFullStructureRange) {
                byStructureGroup.computeIfAbsent(c.structureGroupId(), k -> new java.util.ArrayList<>()).add(c);
            } else if (c.structureGroupId() == null && c.biomeRegen() && regenFullBiomeRange) {
                biomeAtomic.add(c);
            } else {
                capped.add(c);
            }
        }

        // Preserve logical structure/biome boundaries for ordering and completion accounting. Ordinary
        // and biome groups are subsequently divided into bounded spatial work tiles. Complete structure
        // groups remain atomic below so one StructureStart/FEATURES graph owns every marked piece.
        java.util.List<java.util.List<MarkedChunk>> structureGroups = new java.util.ArrayList<>(byStructureGroup.values());
        java.util.List<java.util.List<MarkedChunk>> biomeGroups = groupAdjacentChunks(biomeAtomic);
        java.util.List<java.util.List<MarkedChunk>> logicalGroups = new java.util.ArrayList<>();
        logicalGroups.addAll(structureGroups);
        logicalGroups.addAll(biomeGroups);
        logicalGroups.addAll(groupAdjacentChunks(capped));

        // Bound ordinary/biome scratch generation graphs. With full-structure-range enabled the planner
        // deliberately exempts complete structure groups: splitting one repeats its halo and can omit the
        // remote StructureStart needed to decorate a tile at the opposite edge.
        int tileCap = Math.max(1, config.regen.workTileSize);
        if (maxSize > 0) tileCap = Math.min(tileCap, maxSize);
        return SpatialBatchPlanner.split(logicalGroups, tileCap, config.regen.workTileSpan,
            config.regen.workTileModeEnum(), config.regen.fixedWorkTileSize, regenFullStructureRange);
    }

    private int effectiveConcurrency() {
        int requested = Math.max(1, config.regen.batchConcurrency);
        PluginConfig.Regen.MemorySafety safety = config.regen.memorySafety;
        if (!safety.enabled) return requested;
        int limit = SafetyLimitPolicy.resolveCap(safety.maxActiveBatches, automaticActiveBatchLimit());
        return Math.min(requested, Math.max(1, limit));
    }

    private int effectiveBatchSize() {
        int requested = config.regen.maxChunksPerBatch;
        PluginConfig.Regen.MemorySafety safety = config.regen.memorySafety;
        if (!safety.enabled) return requested;
        int limit = SafetyLimitPolicy.resolveCap(safety.maxChunksPerBatch, automaticBatchSizeLimit());
        // CONFIG/IGNORE adds no safety cap, so requested=0 remains unlimited and cross-chunk
        // decoration is generated as one continuous unit.
        if (limit == Integer.MAX_VALUE) return requested;
        return requested <= 0 ? limit : Math.min(requested, limit);
    }

    private boolean isHeapAtHighWatermark() {
        PluginConfig.Regen.MemorySafety safety = config.regen.memorySafety;
        if (!safety.enabled) return false;
        int percent = Math.clamp(safety.heapHighWatermarkPercent, 50, 95);
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        return used * 100L >= runtime.maxMemory() * percent;
    }

    /**
     * At an admission pause there can be no allocation pressure to make HotSpot collect the
     * completed ProtoChunk graphs. Ask for one collection only when no generation task is alive;
     * otherwise this becomes a permanent 0-concurrency wait at an apparently high heap usage.
     */
    private void requestIdleGc() {
        if (activeTasks.get() != 0 || !idleGcRequested.compareAndSet(false, true)) return;
        Logging.logger().info("Bulk regen is waiting for heap recovery; requesting one idle GC before retrying.");
        Thread.ofVirtual().name("cr-idle-gc").start(() -> {
            try {
                System.gc();
            } finally {
                idleGcRequested.set(false);
            }
        });
    }

    private static int automaticActiveBatchLimit() {
        long heapGiB = heapGiB();
        if (heapGiB <= 2) return 1;
        if (heapGiB <= 4) return 2;
        if (heapGiB <= 8) return 4;
        if (heapGiB <= 16) return 6;
        return 8;
    }

    private static int automaticBatchSizeLimit() {
        long heapGiB = heapGiB();
        if (heapGiB <= 4) return 8;
        if (heapGiB <= 8) return 16;
        if (heapGiB <= 16) return 24;
        return 48;
    }

    private static long heapGiB() {
        return Math.max(1L, Runtime.getRuntime().maxMemory() / (1024L * 1024L * 1024L));
    }

    /**
     * BFS-groups chunks by 8-neighbor adjacency using a coordinate-keyed lookup (O(n) overall) instead of
     * scanning the whole chunk list per BFS step (the old O(n^2) approach could take many seconds — long
     * enough to trip the Folia watchdog — once a few thousand chunks are marked, e.g. after /cr fullmark).
     */
    private static java.util.List<java.util.List<MarkedChunk>> groupAdjacentChunks(Collection<MarkedChunk> chunks) {
        java.util.List<java.util.List<MarkedChunk>> groups = new java.util.ArrayList<>();
        java.util.Map<String, java.util.Map<Long, MarkedChunk>> byWorld = new java.util.HashMap<>();
        for (MarkedChunk c : chunks) {
            byWorld.computeIfAbsent(c.world(), k -> new java.util.HashMap<>()).put(chunkKey(c.cx(), c.cz()), c);
        }

        for (java.util.Map<Long, MarkedChunk> worldChunks : byWorld.values()) {
            java.util.Set<Long> visited = new java.util.HashSet<>();
            for (Long startKey : worldChunks.keySet()) {
                if (!visited.add(startKey)) continue;
                java.util.List<MarkedChunk> group = new java.util.ArrayList<>();
                java.util.Queue<Long> queue = new java.util.LinkedList<>();
                queue.add(startKey);
                while (!queue.isEmpty()) {
                    long currKey = queue.poll();
                    MarkedChunk curr = worldChunks.get(currKey);
                    group.add(curr);
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dz == 0) continue;
                            long neighborKey = chunkKey(curr.cx() + dx, curr.cz() + dz);
                            if (worldChunks.containsKey(neighborKey) && visited.add(neighborKey)) {
                                queue.add(neighborKey);
                            }
                        }
                    }
                }
                groups.add(group);
            }
        }
        return groups;
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }
}
