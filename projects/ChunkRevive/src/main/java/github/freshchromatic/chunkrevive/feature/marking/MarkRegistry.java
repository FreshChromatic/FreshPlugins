package github.freshchromatic.chunkrevive.feature.marking;

import github.freshchromatic.chunkrevive.config.Messages;
import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.feature.regeneration.RegenerationService;
import github.freshchromatic.chunkrevive.feature.regeneration.RegenerationQueue;
import github.freshchromatic.chunkrevive.feature.coordination.WorldResetInProgressException;
import github.freshchromatic.chunkrevive.integration.protection.LandProtection;
import github.freshchromatic.chunkrevive.feature.structure.StructureRegistry;
import github.freshchromatic.chunkrevive.feature.structure.StructureMarkExpander;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

public final class MarkRegistry {

    // Probe UUID for positional lookups — MarkedChunk.equals()/hashCode() are purely positional
    // (world+cx+cz), so any instance with this filler UUID is "equal" to the real stored chunk.
    private static final UUID PROBE_UUID = new UUID(0L, 0L);

    private record RegenerationCompletion(List<UUID> structureGroupIds, List<MarkedChunk> chunksToUnmark) {}

    private final MarkStore           repository;
    private final RegenerationService regenerationService;
    private final LandProtection      landProtection;
    private StructureMarkExpander     structureMarkExpander;
    private StructureRegistry         structureRegistry;
    private PluginConfig              config;
    private Messages                  messages;

    // Keyed and valued by the same MarkedChunk so a positional probe (PROBE_UUID, any markedAt/groupId)
    // resolves the real stored instance (with its real metadata) in O(1) — previously this was backed by
    // a plain Set, forcing unmark()/isMarked()/onChunkRegenComplete() into O(n) full-set stream scans,
    // which became a multi-second Folia watchdog stall once thousands of chunks were marked (e.g. via
    // /cr fullmark) and a regen batch finished and needed to look each of its chunks up here.
    private final Map<MarkedChunk, MarkedChunk>             markedChunks           = new ConcurrentHashMap<>();
    private final Map<UUID, FollowMode>                     followModes            = new ConcurrentHashMap<>();
    private final Map<MarkedChunk, CompletableFuture<Void>> pendingWritesByChunk   = new HashMap<>();
    private final Map<MarkedChunk, CompletableFuture<Void>> pendingRemovalsByChunk = new HashMap<>();
    private final Map<String, Set<CompletableFuture<Void>>> pendingWritesByWorld   = new HashMap<>();
    private final Set<String>                               resettingWorlds        = new HashSet<>();
    private final Map<String, CompletableFuture<Void>>      worldResetCompletions  = new HashMap<>();

    private final RegenerationQueue regenerationQueue;
    private MarkDisplay             markDisplay;

    public MarkRegistry(MarkStore repository, RegenerationService regenerationService, PluginConfig config,
        Messages messages, LandProtection landProtection) {
        this.repository = repository;
        this.regenerationService = regenerationService;
        this.landProtection = landProtection;
        this.config = config;
        this.messages = messages;
        this.regenerationQueue = new RegenerationQueue(regenerationService, config, messages);
    }

    public LandProtection getLandProtection() {
        return landProtection;
    }

    public void setStructureMarkExpander(StructureMarkExpander structureMarkExpander) {
        this.structureMarkExpander = structureMarkExpander;
    }

    public void setStructureRegistry(StructureRegistry structureRegistry) {
        this.structureRegistry = structureRegistry;
    }

    public void setConfig(PluginConfig config) {
        this.config = config;
        this.regenerationQueue.setConfig(config);
    }

    public void setMessages(Messages messages) {
        this.messages = messages;
        this.regenerationService.setMessages(messages);
        this.regenerationQueue.setMessages(messages);
    }

    public void setMarkDisplay(MarkDisplay markDisplay) {
        this.markDisplay = markDisplay;
    }

    public void loadFromDatabase() {
        List<MarkedChunk> loaded = repository.loadAll();
        synchronized (markedChunks) {
            for (MarkedChunk chunk : loaded) {
                markedChunks.put(chunk, chunk);
            }
        }
    }

    public List<MarkedChunk> resolveMark(String world, int cx, int cz, UUID markedBy) {
        var w = Bukkit.getWorld(world);

        List<MarkedChunk> toMark;
        if (w != null && structureMarkExpander != null) {
            toMark = structureMarkExpander.expand(w, cx, cz, markedBy);
        } else if (w != null && landProtection.hasClaim(w, cx, cz)) {
            toMark = List.of();
        } else {
            toMark = List.of(new MarkedChunk(world, cx, cz, markedBy, System.currentTimeMillis()));
        }

        return toMark;
    }

    /** Resolves a mark from live world state. Call from a Bukkit-safe scheduler. */
    public CompletableFuture<MarkResult> markPersisted(String world, int cx, int cz, UUID markedBy) {
        return markResolvedPersisted(resolveMark(world, cx, cz, markedBy));
    }

    public CompletableFuture<MarkResult> markResolvedPersisted(List<MarkedChunk> toMark) {
        if (toMark.isEmpty()) {
            return CompletableFuture.completedFuture(MarkResult.RESIDENCE_BLOCKED);
        }

        return markChunksPersisted(toMark).thenApply(added -> added.isEmpty() ? MarkResult.ALREADY_MARKED
            : MarkResult.SUCCESS);
    }

    public CompletableFuture<List<MarkedChunk>> markChunksPersisted(List<MarkedChunk> chunks) {
        List<MarkedChunk> added = new ArrayList<>();
        CompletableFuture<Void> pendingWrite = new CompletableFuture<>();
        CompletableFuture<Void> precedingWrites;
        CompletableFuture<Void> existingMarkWrites;
        boolean waitForPendingRemoval;
        synchronized (markedChunks) {
            if (chunks.stream().anyMatch(chunk -> resettingWorlds.contains(chunk.world()))) {
                return CompletableFuture.failedFuture(new WorldResetInProgressException(
                    "Marks are being reset for at least one world"));
            }
            List<CompletableFuture<Void>> pendingRemovals = chunks.stream()
                .map(pendingRemovalsByChunk::get)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
            waitForPendingRemoval = !pendingRemovals.isEmpty();
            precedingWrites = waitForPendingRemoval ? CompletableFuture.allOf(pendingRemovals.toArray(
                CompletableFuture<?>[]::new)) : precedingWritesLocked(chunks);
            existingMarkWrites = CompletableFuture.allOf(chunks.stream()
                .map(pendingWritesByChunk::get)
                .filter(Objects::nonNull)
                .distinct()
                .toArray(CompletableFuture<?>[]::new));
            if (!waitForPendingRemoval) {
                for (MarkedChunk chunk : chunks) {
                    if (markedChunks.putIfAbsent(chunk, chunk) == null) {
                        added.add(chunk);
                    }
                }
                if (!added.isEmpty()) {
                    registerWriteLocked(added, pendingWrite);
                    added.forEach(chunk -> pendingWritesByChunk.put(chunk, pendingWrite));
                }
            }
        }
        if (waitForPendingRemoval) {
            return precedingWrites.handle((ignored, failure) -> null).thenCompose(ignored -> markChunksPersisted(
                chunks));
        }
        if (added.isEmpty()) {
            return existingMarkWrites.thenApply(ignored -> List.of());
        }

        CompletableFuture<?>[] groupWrites = structureRegistry == null ? new CompletableFuture<?>[0]
            : added.stream()
                .map(MarkedChunk::structureGroupId)
                .filter(Objects::nonNull)
                .distinct()
                .map(structureRegistry::persisted)
                .toArray(CompletableFuture<?>[]::new);
        CompletableFuture<List<MarkedChunk>> write = CompletableFuture.allOf(precedingWrites, CompletableFuture.allOf(
            groupWrites)).thenApplyAsync(ignored -> {
                repository.markBatch(added);
                return List.copyOf(added);
            });
        return write.whenComplete((persisted, failure) -> {
            if (failure != null) {
                synchronized (markedChunks) {
                    added.forEach(chunk -> markedChunks.remove(chunk, chunk));
                }
            }
            finishWrite(added, pendingWrite, failure);
        });
    }

    public record ResetResult(int chunkCount, int groupCount, int pendingRemoved) {}

    /** Clears all marks/structure groups for a world (resetmark); does not touch terrain. */
    public CompletableFuture<ResetResult> resetMarkForWorld(String world) {
        CompletableFuture<Void> markWrites = beginWorldReset(world);
        return markWrites.thenCompose(ignored -> {
            CompletableFuture<Void> groupWrites = structureRegistry == null ? CompletableFuture.completedFuture(null)
                : structureRegistry.beginWorldReset(world);
            return groupWrites.thenRunAsync(() -> repository.deleteAllForWorld(world))
                .thenApply(deleted -> clearWorldMarks(world))
                .whenComplete((result, failure) -> {
                    if (structureRegistry != null && !groupWrites.isCompletedExceptionally()) {
                        structureRegistry.finishWorldReset(world);
                    }
                });
        }).whenComplete((result, failure) -> {
            if (!markWrites.isCompletedExceptionally()) {
                finishWorldReset(world);
            }
        });
    }

    private ResetResult clearWorldMarks(String world) {
        List<MarkedChunk> toRemove;
        synchronized (markedChunks) {
            toRemove = markedChunks.values().stream().filter(chunk -> chunk.world().equals(world)).toList();
            toRemove.forEach(markedChunks::remove);
        }
        if (markDisplay != null) {
            toRemove.forEach(markDisplay::onChunkUnmarked);
        }

        int groupCount = structureRegistry != null ? structureRegistry.removeGroupsForWorld(world) : 0;
        int pendingRemoved = regenerationQueue.removePendingForWorld(world);

        return new ResetResult(toRemove.size(), groupCount, pendingRemoved);
    }

    private CompletableFuture<Void> beginWorldReset(String world) {
        synchronized (markedChunks) {
            if (!resettingWorlds.add(world)) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                    "Marks are already being reset for world " + world));
            }
            worldResetCompletions.put(world, new CompletableFuture<>());
            CompletableFuture<?>[] writes = pendingWritesByWorld.getOrDefault(world, Set.of())
                .stream()
                .map(write -> write.handle((ignored, failure) -> null))
                .toArray(CompletableFuture<?>[]::new);
            return CompletableFuture.allOf(writes);
        }
    }

    private void finishWorldReset(String world) {
        CompletableFuture<Void> completion;
        synchronized (markedChunks) {
            resettingWorlds.remove(world);
            completion = worldResetCompletions.remove(world);
        }
        if (completion != null) {
            completion.complete(null);
        }
    }

    private void registerWriteLocked(Collection<MarkedChunk> chunks, CompletableFuture<Void> pendingWrite) {
        chunks.stream()
            .map(MarkedChunk::world)
            .distinct()
            .forEach(world -> pendingWritesByWorld.computeIfAbsent(world, ignored -> new HashSet<>()).add(pendingWrite));
    }

    private CompletableFuture<Void> precedingWritesLocked(Collection<MarkedChunk> chunks) {
        CompletableFuture<?>[] writes = chunks.stream()
            .map(MarkedChunk::world)
            .distinct()
            .map(pendingWritesByWorld::get)
            .filter(Objects::nonNull)
            .flatMap(Set::stream)
            .distinct()
            .map(write -> write.handle((ignored, failure) -> null))
            .toArray(CompletableFuture<?>[]::new);
        return CompletableFuture.allOf(writes);
    }

    private void finishWrite(Collection<MarkedChunk> chunks, CompletableFuture<Void> pendingWrite, Throwable failure) {
        synchronized (markedChunks) {
            chunks.forEach(chunk -> pendingWritesByChunk.remove(chunk, pendingWrite));
            chunks.forEach(chunk -> pendingRemovalsByChunk.remove(chunk, pendingWrite));
            chunks.stream()
                .map(MarkedChunk::world)
                .distinct()
                .forEach(world -> {
                    Set<CompletableFuture<Void>> writes = pendingWritesByWorld.get(world);
                    if (writes != null) {
                        writes.remove(pendingWrite);
                        if (writes.isEmpty()) {
                            pendingWritesByWorld.remove(world);
                        }
                    }
                });
        }
        if (failure == null) {
            pendingWrite.complete(null);
        } else {
            pendingWrite.completeExceptionally(failure);
        }
    }

    /** Must be called from a Bukkit-safe scheduler. */
    public CompletableFuture<Optional<MarkedChunk>> unmarkPersisted(String world, int cx, int cz) {
        MarkedChunk existing;
        CompletableFuture<Void> pendingWrite = new CompletableFuture<>();
        CompletableFuture<Void> precedingWrites;
        synchronized (markedChunks) {
            if (resettingWorlds.contains(world)) {
                return CompletableFuture.failedFuture(new WorldResetInProgressException(
                    "Marks are being reset for world " + world));
            }
            existing = markedChunks.get(new MarkedChunk(world, cx, cz, PROBE_UUID, 0));
            if (existing == null) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            precedingWrites = precedingWritesLocked(List.of(existing));
            registerWriteLocked(List.of(existing), pendingWrite);
            pendingRemovalsByChunk.put(existing, pendingWrite);
        }
        return precedingWrites.thenApplyAsync(ignored -> {
            MarkedChunk removed = markedChunks.remove(new MarkedChunk(world, cx, cz, PROBE_UUID, 0));
            if (removed == null) {
                return Optional.<MarkedChunk>empty();
            }
            try {
                if (!repository.unmark(world, cx, cz)) {
                    throw new IllegalStateException("Marked chunk was not found in storage");
                }
                return Optional.of(removed);
            } catch (RuntimeException failure) {
                markedChunks.putIfAbsent(removed, removed);
                throw failure;
            }
        }).whenComplete((removedChunk, failure) -> finishWrite(List.of(existing), pendingWrite, failure));
    }

    /** Must be invoked from a Bukkit-safe scheduler after a durable API write. */
    public void refreshMarkedDisplay(String world, int cx, int cz) {
        MarkedChunk chunk = markedChunks.get(new MarkedChunk(world, cx, cz, PROBE_UUID, 0));
        if (chunk != null && markDisplay != null) {
            markDisplay.onChunkMarked(chunk);
        }
    }

    /** Must be invoked from a Bukkit-safe scheduler after a durable API delete. */
    public void refreshUnmarkedDisplay(MarkedChunk chunk) {
        if (markDisplay != null) {
            markDisplay.onChunkUnmarked(chunk);
        }
    }

    public CompletableFuture<List<MarkedChunk>> unmarkChunksPersisted(Collection<MarkedChunk> chunks) {
        List<MarkedChunk> existing;
        CompletableFuture<Void> pendingWrite = new CompletableFuture<>();
        CompletableFuture<Void> precedingWrites;
        synchronized (markedChunks) {
            if (chunks.stream().anyMatch(chunk -> resettingWorlds.contains(chunk.world()))) {
                return CompletableFuture.failedFuture(new WorldResetInProgressException(
                    "Marks are being reset for at least one world"));
            }
            existing = chunks.stream().map(markedChunks::get).filter(Objects::nonNull).distinct().toList();
            if (!existing.isEmpty()) {
                precedingWrites = precedingWritesLocked(existing);
                registerWriteLocked(existing, pendingWrite);
                existing.forEach(chunk -> pendingRemovalsByChunk.put(chunk, pendingWrite));
            } else {
                precedingWrites = CompletableFuture.completedFuture(null);
            }
        }
        if (existing.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        return precedingWrites.thenRunAsync(() -> repository.unmarkBatch(existing))
            .thenApply(ignored -> {
                List<MarkedChunk> removed;
                synchronized (markedChunks) {
                    removed = existing.stream().filter(chunk -> markedChunks.remove(chunk, chunk)).toList();
                }
                if (markDisplay != null) {
                    removed.forEach(markDisplay::onChunkUnmarked);
                }
                return removed;
            })
            .whenComplete((removed, failure) -> finishWrite(existing, pendingWrite, failure));
    }

    public void unmarkAfterDeletion(Collection<MarkedChunk> chunks) {
        List<MarkedChunk> targets = List.copyOf(chunks);
        List<String> worlds = targets.stream().map(MarkedChunk::world).distinct().toList();
        try {
            withWorldResetRetry(worlds, () -> CompletableFuture.runAsync(() -> unmarkAfterDeletionNow(targets))).join();
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (failure.getCause() instanceof Error error) {
                throw error;
            }
            throw failure;
        }
    }

    private void unmarkAfterDeletionNow(List<MarkedChunk> chunks) {
        List<MarkedChunk> existing;
        CompletableFuture<Void> pendingWrite = new CompletableFuture<>();
        CompletableFuture<Void> precedingWrites;
        synchronized (markedChunks) {
            if (chunks.stream().anyMatch(chunk -> resettingWorlds.contains(chunk.world()))) {
                throw new WorldResetInProgressException("Marks are being reset for at least one world");
            }
            existing = chunks.stream().map(markedChunks::get).filter(Objects::nonNull).toList();
            if (!existing.isEmpty()) {
                precedingWrites = precedingWritesLocked(existing);
                registerWriteLocked(existing, pendingWrite);
                existing.forEach(chunk -> pendingRemovalsByChunk.put(chunk, pendingWrite));
            } else {
                precedingWrites = CompletableFuture.completedFuture(null);
            }
        }
        if (existing.isEmpty()) {
            return;
        }
        Throwable failure = null;
        try {
            precedingWrites.join();
            repository.unmarkBatch(existing);
            List<MarkedChunk> removed;
            synchronized (markedChunks) {
                removed = existing.stream().filter(chunk -> markedChunks.remove(chunk, chunk)).toList();
            }
            if (markDisplay != null) {
                removed.forEach(markDisplay::onChunkUnmarked);
            }
        } catch (RuntimeException | Error error) {
            failure = error;
            throw error;
        } finally {
            finishWrite(existing, pendingWrite, failure);
        }
    }

    /** Removes marks and queued (not yet running) work covered by a newly-created Residence area. */
    public CompletableFuture<Integer> unmarkResidenceArea(String world, int minCx, int maxCx, int minCz, int maxCz) {
        List<MarkedChunk> covered = getMarkedChunks()
            .stream()
            .filter(c -> c.world().equals(world) && c.cx() >= minCx
            && c.cx() <= maxCx && c.cz() >= minCz && c.cz() <= maxCz)
            .toList();
        regenerationQueue.removePendingInRange(world, minCx, maxCx, minCz, maxCz);
        return unmarkChunksPersisted(covered).thenApply(List::size);
    }

    public CompletableFuture<Void> onChunkRegenComplete(String world, int cx, int cz, Executor stateExecutor) {
        return withWorldResetRetry(List.of(world), () -> CompletableFuture.supplyAsync(() -> markedChunks.get(
            new MarkedChunk(world, cx, cz, PROBE_UUID, 0)), stateExecutor).thenCompose(found -> {
                if (found != null && found.structureGroupId() != null && structureRegistry != null) {
                    return structureRegistry.scheduleNextRefresh(found.structureGroupId());
                }
                if (found == null || found.structureGroupId() != null || !config.regen.autoUnmarkAfterRegen) {
                    return CompletableFuture.completedFuture(null);
                }

                return unmarkPersisted(world, cx, cz).thenAcceptAsync(removed -> removed.ifPresent(
                    this::refreshUnmarkedDisplay), stateExecutor);
            }));
    }

    public CompletableFuture<Void> onChunksRegenComplete(Collection<MarkedChunk> chunks, Executor stateExecutor) {
        List<MarkedChunk> completedChunks = List.copyOf(chunks);
        List<String> worlds = completedChunks.stream().map(MarkedChunk::world).distinct().toList();
        return withWorldResetRetry(worlds, () -> completeChunksRegen(completedChunks, stateExecutor));
    }

    private CompletableFuture<Void> completeChunksRegen(List<MarkedChunk> completedChunks, Executor stateExecutor) {
        return CompletableFuture.supplyAsync(() -> {
            List<MarkedChunk> toUnmark = new ArrayList<>();
            List<UUID> structureGroupIds = new ArrayList<>();
            for (MarkedChunk chunk : completedChunks) {
                MarkedChunk found = markedChunks.get(new MarkedChunk(chunk.world(), chunk.cx(), chunk.cz(), PROBE_UUID,
                    0));
                UUID groupId = found != null ? found.structureGroupId() : null;
                if (groupId != null) {
                    structureGroupIds.add(groupId);
                } else if (found != null && config.regen.autoUnmarkAfterRegen) {
                    toUnmark.add(found);
                }
            }
            return new RegenerationCompletion(structureGroupIds.stream().distinct().toList(), toUnmark);
        }, stateExecutor).thenCompose(completion -> {
            CompletableFuture<Void> groupWrites = structureRegistry == null ? CompletableFuture.completedFuture(null)
                : CompletableFuture.allOf(completion.structureGroupIds()
                    .stream()
                    .map(structureRegistry::scheduleNextRefresh)
                    .toArray(CompletableFuture[]::new));
            CompletableFuture<List<MarkedChunk>> unmark = unmarkChunksPersisted(completion.chunksToUnmark());
            return groupWrites.thenCombine(unmark, (ignored, removed) -> null);
        });
    }

    private CompletableFuture<Void> withWorldResetRetry(List<String> worlds, Supplier<CompletableFuture<Void>> action) {
        return waitForWorldResets(worlds)
            .thenCompose(ignored -> action.get())
            .exceptionallyCompose(failure -> {
                if (!hasWorldResetFailure(failure)) {
                    return CompletableFuture.failedFuture(failure);
                }
                return waitForWorldResets(worlds).thenCompose(ignored -> withWorldResetRetry(worlds, action));
            });
    }

    private CompletableFuture<Void> waitForWorldResets(List<String> worlds) {
        synchronized (markedChunks) {
            CompletableFuture<?>[] resets = worlds.stream()
                .map(worldResetCompletions::get)
                .filter(Objects::nonNull)
                .distinct()
                .toArray(CompletableFuture<?>[]::new);
            return CompletableFuture.allOf(resets);
        }
    }

    private boolean hasWorldResetFailure(Throwable failure) {
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof WorldResetInProgressException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    public boolean isMarked(String world, int cx, int cz) {
        return markedChunks.containsKey(new MarkedChunk(world, cx, cz, PROBE_UUID, 0));
    }

    public List<MarkedChunk> getMarkedChunksNear(String world, int cx, int cz, int radiusChunks) {
        return getMarkedChunks()
            .stream()
            .filter(c -> c.world().equals(world) && Math.abs(c.cx() - cx) <= radiusChunks
            && Math.abs(c.cz() - cz) <= radiusChunks)
            .toList();
    }

    /** Returns true if the mode was activated, false if it was deactivated (toggle). */
    public boolean toggleFollowMode(UUID player, FollowMode mode) {
        if (mode == followModes.get(player)) {
            followModes.remove(player);
            return false;
        }
        followModes.put(player, mode);
        return true;
    }

    public Optional<FollowMode> getFollowMode(UUID player) {
        return Optional.ofNullable(followModes.get(player));
    }

    public void clearFollowMode(UUID player) {
        followModes.remove(player);
    }

    public Collection<MarkedChunk> getMarkedChunks() {
        synchronized (markedChunks) {
            return List.copyOf(markedChunks.values());
        }
    }

    public RegenerationQueue getRegenerationQueue() {
        return regenerationQueue;
    }

    public RegenerationService getRegenerationService() {
        return regenerationService;
    }

    public PluginConfig getConfig() {
        return config;
    }
}
