package github.freshchromatic.chunkrevive.feature.regeneration;

import github.freshchromatic.chunkrevive.config.Messages;
import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import github.freshchromatic.freshlib.scheduler.Scheduler;
import github.freshchromatic.freshlib.util.Components;
import github.freshchromatic.freshlib.util.Logging;
import net.kyori.adventure.audience.Audience;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.List;
import java.util.Locale;
import java.util.Queue;

public final class RegenerationQueue {

    private final RegenerationService engine;
    private PluginConfig              config;
    private Messages                  messages;

    private final Queue<List<MarkedChunk>>                             pending   = new ConcurrentLinkedQueue<>();
    private volatile boolean                                           running   = false;
    private volatile boolean                                           cancelled = false;
    private volatile boolean                                           failed    = false;
    private Audience                                                   initiator;
    private int                                                        totalCount;
    private Function<Collection<MarkedChunk>, CompletableFuture<Void>> onBatchComplete;
    private Runnable                                                   onQueueFinished;
    private long                                                       startTime;
    private long                                                       runGeneration;
    private boolean                                                    delayedDispatchScheduled;

    private final AtomicInteger activeTasks     = new AtomicInteger(0);
    private final AtomicInteger completedCount  = new AtomicInteger(0);
    private final AtomicBoolean idleGcRequested = new AtomicBoolean(false);

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

    public boolean hasFailed() {
        return failed;
    }

    public int getCompletedCount() {
        return completedCount.get();
    }

    public synchronized int getTotalCount() {
        return totalCount;
    }

    public int getActiveTasks() {
        return activeTasks.get();
    }

    public synchronized int getPendingCount() {
        int count = 0;
        for (List<MarkedChunk> group : pending) {
            count += group.size();
        }
        return count;
    }

    public synchronized boolean start(Collection<MarkedChunk> chunks, Audience initiator,
        Function<Collection<MarkedChunk>, CompletableFuture<Void>> onBatchComplete) {
        return start(chunks, initiator, onBatchComplete, () -> {
        }, () -> {
        });
    }

    public synchronized boolean start(Collection<MarkedChunk> chunks, Audience initiator,
        Function<Collection<MarkedChunk>, CompletableFuture<Void>> onBatchComplete, Runnable beforeStart) {
        return start(chunks, initiator, onBatchComplete, beforeStart, () -> {
        });
    }

    public synchronized boolean start(Collection<MarkedChunk> chunks, Audience initiator,
        Function<Collection<MarkedChunk>, CompletableFuture<Void>> onBatchComplete, Runnable beforeStart,
        Runnable onQueueFinished) {
        if (running) {
            return false;
        }
        this.running = true;
        this.cancelled = false;
        this.failed = false;
        this.runGeneration++;
        this.delayedDispatchScheduled = false;
        this.initiator = initiator;
        this.totalCount = chunks.size();
        this.onBatchComplete = onBatchComplete;
        this.onQueueFinished = onQueueFinished;
        this.activeTasks.set(0);
        this.completedCount.set(0);
        this.startTime = System.currentTimeMillis();

        NmsTerrainGenerator.beginBulkSession(chunks.size());

        List<List<MarkedChunk>> workTiles = RegenerationBatchPlanner.plan(chunks, config,
            RegenerationMemoryPolicy.effectiveBatchSize(config.regen, Runtime.getRuntime().maxMemory()));
        pending.addAll(workTiles);
        try {
            beforeStart.run();
        } catch (RuntimeException | Error failure) {
            failed = true;
            pending.clear();
            onQueueFinished = null;
            checkFinished();
            throw failure;
        }
        if (!running) {
            return true;
        }

        int smallestTile = workTiles.stream().mapToInt(List::size).min().orElse(0);
        int largestTile = workTiles.stream().mapToInt(List::size).max().orElse(0);
        double averageTile = workTiles.stream().mapToInt(List::size).average().orElse(0.0);
        Logging.logger().info("Bulk regen planned " + workTiles.size() + " spatial work tile(s), size=" + smallestTile
            + "/" + String.format(Locale.ROOT, "%.1f", averageTile) + "/" + largestTile
            + " min/avg/max target chunks, mode=" + config.regen.workTileModeEnum() + ", fixed="
            + config.regen.fixedWorkTileSize + ", concurrency=" + RegenerationMemoryPolicy.effectiveConcurrency(
                config.regen, Runtime.getRuntime().maxMemory()) + ".");

        engine.sendMessage(initiator, messages.regen.batchStart.withPlaceholders(Components.placeholder("count",
            String.valueOf(totalCount))));

        processNext();
        return true;
    }

    /** Removes not-yet-started chunks for a world from the pending queue (resetmark); running batches finish normally. */
    public int removePendingForWorld(String world) {
        return removePendingMatching(chunk -> chunk.world().equals(world));
    }

    /** Removes not-yet-started chunks overlapping a newly-created Residence area. */
    public int removePendingInRange(String world, int minCx, int maxCx, int minCz, int maxCz) {
        return removePendingMatching(chunk -> chunk.world().equals(world) && chunk.cx() >= minCx && chunk.cx() <= maxCx
            && chunk.cz() >= minCz && chunk.cz() <= maxCz);
    }

    private synchronized int removePendingMatching(Predicate<MarkedChunk> shouldRemove) {
        int removed = 0;
        for (List<MarkedChunk> group : List.copyOf(pending)) {
            List<MarkedChunk> filtered = group.stream().filter(shouldRemove.negate()).toList();
            int removedFromGroup = group.size() - filtered.size();
            if (removedFromGroup == 0 || !pending.remove(group)) {
                continue;
            }
            removed += removedFromGroup;
            if (!filtered.isEmpty()) {
                pending.add(filtered);
            }
        }
        if (removed > 0) {
            totalCount = Math.max(0, totalCount - removed);
            checkFinished();
        }
        return removed;
    }

    public synchronized void cancel() {
        cancelled = true;
        pending.clear();
        checkFinished();
    }

    private synchronized void processNext() {
        if (isCancelled()) {
            checkFinished();
            return;
        }

        if (pending.isEmpty()) {
            checkFinished();
            return;
        }

        int concurrency = RegenerationMemoryPolicy.effectiveConcurrency(config.regen, Runtime.getRuntime().maxMemory());
        while (activeTasks.get() < concurrency && !pending.isEmpty()) {
            if (RegenerationMemoryPolicy.isHeapAtHighWatermark(config.regen, Runtime.getRuntime().totalMemory()
                - Runtime.getRuntime().freeMemory(), Runtime.getRuntime().maxMemory())) {
                // Do not admit another ProtoChunk graph while the previous batch still occupies most
                // of the heap. This is intentionally a pause, not a cancellation: completed work is
                // preserved and the queue resumes as soon as GC has reclaimed the prior batch.
                requestIdleGc();
                scheduleNext(20L);
                return;
            }

            List<MarkedChunk> group = pending.poll();
            if (group != null) {
                if (config.regen.batchDelayTicks > 0) {
                    scheduleNext(config.regen.batchDelayTicks);
                    if (failed) {
                        return;
                    }
                }
                activeTasks.incrementAndGet();
                engine.regenChunks(initiator, group, true, this::isCancelled).whenComplete((v, ex) -> {
                    if (ex != null) {
                        Throwable cause = unwrapCompletionException(ex);
                        boolean expectedCancellation = (cancelled || failed) && cause instanceof CancellationException;
                        if (!(cause instanceof SkipChunkException) && !expectedCancellation) {
                            failed = true;
                            pending.clear();
                            Logging.logger().warning("Batch regen failed for group of " + group.size() + " chunks: "
                                + cause.getMessage());
                        }
                        finishBatch();
                        return;
                    }

                    completedCount.addAndGet(group.size());
                    CompletableFuture<Void> postProcessing = cancelled || onBatchComplete == null
                        ? CompletableFuture.completedFuture(null)
                        : CompletableFuture.completedFuture(group).thenCompose(onBatchComplete);
                    postProcessing.whenComplete((ignored, callbackFailure) -> {
                        if (callbackFailure != null) {
                            failed = true;
                            pending.clear();
                        }
                        finishBatch();
                        if (callbackFailure != null) {
                            Throwable cause = unwrapCompletionException(callbackFailure);
                            Logging.logger().severe("Batch completion callback failed for group of " + group.size()
                                + " chunks", cause);
                            engine.sendMessage(initiator, messages.regen.failed.withPlaceholders(Components.placeholder(
                                "cx_cz", "Batch"), Components.placeholder("reason",
                                    "Failed to save post-regeneration state. Check the server log.")));
                        }
                    });
                });
            }

            // Delay next start to spread the load if configured
            if (config.regen.batchDelayTicks > 0) {
                break;
            }
        }
    }

    private synchronized void scheduleNext(long delayTicks) {
        if (delayedDispatchScheduled) {
            return;
        }
        delayedDispatchScheduled = true;
        long scheduledGeneration = runGeneration;
        try {
            Scheduler.runTaskLater(plugin, () -> {
                synchronized (RegenerationQueue.this) {
                    if (scheduledGeneration != runGeneration || !delayedDispatchScheduled) {
                        return;
                    }
                    delayedDispatchScheduled = false;
                    processNext();
                }
            }, delayTicks);
        } catch (RuntimeException failure) {
            delayedDispatchScheduled = false;
            failScheduling(failure);
        }
    }

    private static Throwable unwrapCompletionException(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
            && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private synchronized void finishBatch() {
        int active = activeTasks.decrementAndGet();
        if (!pending.isEmpty() && !isCancelled() && !delayedDispatchScheduled) {
            try {
                Scheduler.runTask(plugin, this::processNext);
            } catch (RuntimeException failure) {
                failScheduling(failure);
            }
        }
        if (pending.isEmpty() && active == 0) {
            checkFinished();
        }
    }

    private void failScheduling(RuntimeException failure) {
        failed = true;
        pending.clear();
        Logging.logger().warning("Bulk regen could not schedule its next batch: " + failure.getMessage());
        checkFinished();
    }

    private synchronized void checkFinished() {
        if (running && pending.isEmpty() && activeTasks.get() == 0) {
            running = false;
            NmsTerrainGenerator.endBulkSession();
            if (!cancelled && !failed) {
                long elapsedMs = System.currentTimeMillis() - startTime;
                engine.sendMessage(initiator, messages.regen.batchDone.withPlaceholders(Components.placeholder("count",
                    String.valueOf(completedCount.get())), Components.placeholder("elapsed", engine.formatDuration(
                        elapsedMs)), Components.placeholder("elapsed_ms", String.valueOf(elapsedMs))));
            }
            Runnable finished = onQueueFinished;
            onQueueFinished = null;
            if (finished != null) {
                finished.run();
            }
        }
    }

    /**
     * At an admission pause there can be no allocation pressure to make HotSpot collect the
     * completed ProtoChunk graphs. Ask for one collection only when no generation task is alive;
     * otherwise this becomes a permanent 0-concurrency wait at an apparently high heap usage.
     */
    private void requestIdleGc() {
        if (activeTasks.get() != 0 || !idleGcRequested.compareAndSet(false, true)) {
            return;
        }
        Logging.logger().info("Bulk regen is waiting for heap recovery; requesting one idle GC before retrying.");
        Thread.ofVirtual()
            .name("cr-idle-gc")
            .start(() -> {
                try {
                    System.gc();
                } finally {
                    idleGcRequested.set(false);
                }
            });
    }

}
