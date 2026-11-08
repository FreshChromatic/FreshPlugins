package github.freshchromatic.chunkrevive.feature.structure;

import github.freshchromatic.chunkrevive.nms.ChunkCoordinate;
import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.integration.protection.LandProtection;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import github.freshchromatic.chunkrevive.feature.coordination.WorldResetInProgressException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Owns the in-memory + persisted set of {@link StructureGroup}s and the logic to create/update them. */
public final class StructureRegistry {

    private static final Logger LOGGER = Logger.getLogger(StructureRegistry.class.getName());

    public record RegisterResult(StructureGroup group, List<MarkedChunk> chunks, boolean newGroup) {}

    private final StructureStore repository;
    private final LandProtection landProtection;
    private PluginConfig         config;

    private final Map<UUID, StructureGroup>          groups                  = new ConcurrentHashMap<>();
    private final Map<UUID, StructureGroup>          persistedGroups         = new ConcurrentHashMap<>();
    private final Map<UUID, Throwable>               persistenceFailures     = new ConcurrentHashMap<>();
    private final Map<UUID, Long>                    lastPersistedProtection = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Void>> persistenceTails        = new ConcurrentHashMap<>();
    private final Set<String>                        resettingWorlds         = new HashSet<>();

    public StructureRegistry(StructureStore repository, PluginConfig config, LandProtection landProtection) {
        this.repository = repository;
        this.config = config;
        this.landProtection = landProtection;
    }

    public void setConfig(PluginConfig config) {
        this.config = config;
    }

    public synchronized void loadFromDatabase() {
        for (StructureGroup group : repository.loadAllGroups()) {
            groups.put(group.groupId(), group);
            persistedGroups.put(group.groupId(), group);
            lastPersistedProtection.put(group.groupId(), group.protectionTicks());
        }
    }

    public CompletableFuture<Void> persisted(UUID groupId) {
        CompletableFuture<Void> tail = persistenceTails.get(groupId);
        if (tail != null) {
            return tail;
        }
        Throwable failure = persistenceFailures.get(groupId);
        if (failure != null) {
            return CompletableFuture.failedFuture(failure);
        }
        return persistedGroups.containsKey(groupId) ? CompletableFuture.completedFuture(null)
            : CompletableFuture.failedFuture(new IllegalStateException("Structure group is not persisted: " + groupId));
    }

    public synchronized Collection<StructureGroup> getAllGroups() {
        return List.copyOf(groups.values());
    }

    public Optional<StructureGroup> getGroup(UUID groupId) {
        return Optional.ofNullable(groups.get(groupId));
    }

    public Optional<StructureGroup> findGroupAt(String world, int cx, int cz) {
        return groups.values().stream().filter(g -> g.world().equals(world) && g.containsChunk(cx, cz)).findFirst();
    }

    /** Like {@link #findGroupAt}, but returns every group whose bounding box covers this chunk (overlapping structures). */
    public List<StructureGroup> findAllGroupsAt(String world, int cx, int cz) {
        return groups.values().stream().filter(g -> g.world().equals(world) && g.containsChunk(cx, cz)).toList();
    }

    public List<ChunkCoordinate> resolveEffectiveChunks(String world, int minCx, int maxCx, int minCz, int maxCz) {
        return landProtection.resolveEffectiveChunks(world, minCx, maxCx, minCz, maxCz,
            config.landProtection.defaultPolicy());
    }

    /** Finds an existing group covering the exact same range, or creates and persists a new one. */
    public synchronized UUID findOrCreateGroup(String world, String structureId, int minCx, int maxCx, int minCz,
        int maxCz) {
        ensureWorldNotResetting(world);
        for (StructureGroup g : groups.values()) {
            if (g.world().equals(world) && g.structureId().equals(structureId) && g.minChunkX() == minCx
                && g.maxChunkX() == maxCx && g.minChunkZ() == minCz && g.maxChunkZ() == maxCz) {
                return g.groupId();
            }
        }

        long now = System.currentTimeMillis();
        long nextRefreshAt = now + config.structure.refresh.getIntervalDays(structureId) * 86_400_000L;
        StructureGroup group = new StructureGroup(UUID.randomUUID(), world, structureId, minCx, maxCx, minCz, maxCz,
            now, nextRefreshAt, 0L, false);
        groups.put(group.groupId(), group);
        persist(group, () -> repository.upsertGroup(group));
        return group.groupId();
    }

    /**
     * Auto-detect entry point (via StructureService): resolves the structure's effective chunk set,
     * creates the group if needed, and returns the chunks the caller should mark.
     * Returns empty if the whole range is excluded by the Residence policy.
     */
    public synchronized Optional<RegisterResult> registerOrTouch(StructureDetector.Detected detected, String world,
        UUID markedBy) {
        ensureWorldNotResetting(world);
        var bb = detected.boundingBox();
        int minCx = bb.minX() >> 4, maxCx = bb.maxX() >> 4;
        int minCz = bb.minZ() >> 4, maxCz = bb.maxZ() >> 4;

        List<ChunkCoordinate> effective = resolveEffectiveChunks(world, minCx, maxCx, minCz, maxCz);
        if (effective == null || effective.isEmpty()) {
            return Optional.empty();
        }

        boolean isNew = groups.values()
            .stream()
            .noneMatch(g -> g.world().equals(world) && g.structureId().equals(detected.structureId()) && g.minChunkX() == minCx && g.maxChunkX() == maxCx && g.minChunkZ() == minCz
            && g.maxChunkZ() == maxCz);

        UUID groupId = findOrCreateGroup(world, detected.structureId(), minCx, maxCx, minCz, maxCz);
        StructureGroup group = groups.get(groupId);
        if (!isNew && group.nextRefreshAt() <= 0) {
            long now = System.currentTimeMillis();
            long nextRefreshAt = now + config.structure.refresh.getIntervalDays(detected.structureId()) * 86_400_000L;
            StructureGroup updated = group.withNextRefreshAt(nextRefreshAt);
            groups.put(groupId, updated);
            persist(updated, () -> repository.upsertGroup(updated));
        }

        long now = System.currentTimeMillis();
        List<MarkedChunk> chunks = new ArrayList<>();
        for (ChunkCoordinate coord : effective) {
            chunks.add(new MarkedChunk(world, coord.x(), coord.z(), markedBy, now, groupId));
        }
        return Optional.of(new RegisterResult(groups.get(groupId), chunks, isNew));
    }

    public synchronized CompletableFuture<Void> postponeRefresh(UUID groupId, long deltaTicks) {
        StructureGroup g = groups.get(groupId);
        if (g == null) {
            return CompletableFuture.completedFuture(null);
        }
        if (resettingWorlds.contains(g.world())) {
            return CompletableFuture.failedFuture(new WorldResetInProgressException(
                "Structure groups are being reset for world " + g.world()));
        }
        StructureGroup updated = g.withNextRefreshAt(System.currentTimeMillis() + deltaTicks * 50L);
        groups.put(groupId, updated);
        return persist(updated, () -> repository.upsertGroup(updated));
    }

    public synchronized CompletableFuture<Void> scheduleNextRefresh(UUID groupId) {
        StructureGroup g = groups.get(groupId);
        if (g == null) {
            return CompletableFuture.completedFuture(null);
        }
        if (resettingWorlds.contains(g.world())) {
            return CompletableFuture.failedFuture(new WorldResetInProgressException(
                "Structure groups are being reset for world " + g.world()));
        }
        long now = System.currentTimeMillis();
        long nextRefreshAt = now + config.structure.refresh.getIntervalDays(g.structureId()) * 86_400_000L;
        StructureGroup updated = new StructureGroup(g.groupId(), g.world(), g.structureId(), g.minChunkX(),
            g.maxChunkX(), g.minChunkZ(), g.maxChunkZ(), g.detectedAt(), nextRefreshAt, 0L, false);
        groups.put(groupId, updated);
        lastPersistedProtection.put(groupId, 0L);
        return persist(updated, () -> repository.upsertGroup(updated));
    }

    public synchronized void addProtectionTicks(UUID groupId, long ticks) {
        StructureGroup g = groups.get(groupId);
        if (g == null || g.blocked() || resettingWorlds.contains(g.world())) {
            return;
        }

        long newTicks = g.protectionTicks() + ticks;
        boolean nowBlocked = newTicks >= config.structure.protection.requiredTicks;
        StructureGroup updated = g.withProtection(newTicks, nowBlocked);
        groups.put(groupId, updated);

        if (nowBlocked) {
            // State transition is critical — flush immediately, no throttling.
            lastPersistedProtection.put(groupId, newTicks);
            persist(updated, () -> repository.updateProtection(groupId, newTicks, true));
            return;
        }

        long lastPersisted = lastPersistedProtection.getOrDefault(groupId, 0L);
        if (newTicks - lastPersisted >= config.structure.protection.flushIntervalTicks) {
            lastPersistedProtection.put(groupId, newTicks);
            persist(updated, () -> repository.updateProtection(groupId, newTicks, false));
        }
    }

    public synchronized void resetProtectionTicks(UUID groupId) {
        StructureGroup g = groups.get(groupId);
        if (g == null || g.blocked() || g.protectionTicks() == 0 || resettingWorlds.contains(g.world())) {
            return;
        }
        StructureGroup updated = g.withProtection(0L, false);
        groups.put(groupId, updated);
        lastPersistedProtection.put(groupId, 0L);
        persist(updated, () -> repository.updateProtection(groupId, 0L, false));
    }

    /** Removes all structure groups for a world (resetmark); returns how many were removed. */
    public synchronized int removeGroupsForWorld(String world) {
        List<UUID> ids = groups.values()
            .stream()
            .filter(g -> g.world().equals(world))
            .map(StructureGroup::groupId)
            .toList();
        ids.forEach(groups::remove);
        ids.forEach(persistedGroups::remove);
        ids.forEach(persistenceFailures::remove);
        ids.forEach(lastPersistedProtection::remove);
        return ids.size();
    }

    public synchronized CompletableFuture<Void> beginWorldReset(String world) {
        if (!resettingWorlds.add(world)) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                "Structure groups are already being reset for world " + world));
        }
        CompletableFuture<?>[] writes = groups.values()
            .stream()
            .filter(group -> group.world().equals(world))
            .map(StructureGroup::groupId)
            .map(persistenceTails::get)
            .filter(Objects::nonNull)
            .map(write -> write.handle((
                ignored, failure) -> null))
            .toArray(CompletableFuture<?>[]::new);
        return CompletableFuture.allOf(writes);
    }

    public synchronized void finishWorldReset(String world) {
        resettingWorlds.remove(world);
    }

    public synchronized CompletableFuture<Boolean> unblock(UUID groupId) {
        StructureGroup g = groups.get(groupId);
        if (g == null) {
            return CompletableFuture.completedFuture(false);
        }
        if (resettingWorlds.contains(g.world())) {
            return CompletableFuture.failedFuture(new WorldResetInProgressException(
                "Structure groups are being reset for world " + g.world()));
        }
        StructureGroup updated = g.withProtection(0L, false);
        groups.put(groupId, updated);
        lastPersistedProtection.put(groupId, 0L);
        return persist(updated, () -> repository.updateProtection(groupId, 0L, false)).thenApply(ignored -> true);
    }

    public synchronized CompletableFuture<Boolean> block(UUID groupId) {
        StructureGroup g = groups.get(groupId);
        if (g == null) {
            return CompletableFuture.completedFuture(false);
        }
        if (resettingWorlds.contains(g.world())) {
            return CompletableFuture.failedFuture(new WorldResetInProgressException(
                "Structure groups are being reset for world " + g.world()));
        }
        long required = config.structure.protection.requiredTicks;
        StructureGroup updated = g.withProtection(required, true);
        groups.put(groupId, updated);
        lastPersistedProtection.put(groupId, required);
        return persist(updated, () -> repository.updateProtection(groupId, required, true)).thenApply(ignored -> true);
    }

    public synchronized CompletableFuture<Boolean> resetGroup(UUID groupId) {
        StructureGroup g = groups.get(groupId);
        if (g == null) {
            return CompletableFuture.completedFuture(false);
        }
        if (resettingWorlds.contains(g.world())) {
            return CompletableFuture.failedFuture(new WorldResetInProgressException(
                "Structure groups are being reset for world " + g.world()));
        }
        long now = System.currentTimeMillis();
        long nextRefreshAt = now + config.structure.refresh.getIntervalDays(g.structureId()) * 86_400_000L;
        StructureGroup updated = new StructureGroup(g.groupId(), g.world(), g.structureId(), g.minChunkX(),
            g.maxChunkX(), g.minChunkZ(), g.maxChunkZ(), g.detectedAt(), nextRefreshAt, 0L, false);
        groups.put(groupId, updated);
        lastPersistedProtection.put(groupId, 0L);
        return persist(updated, () -> repository.upsertGroup(updated)).thenApply(ignored -> true);
    }

    public synchronized CompletableFuture<Integer> resetAllGroups() {
        if (groups.values().stream().anyMatch(group -> resettingWorlds.contains(group.world()))) {
            return CompletableFuture.failedFuture(new WorldResetInProgressException(
                "Structure groups are being reset for at least one world"));
        }
        long now = System.currentTimeMillis();
        List<CompletableFuture<Void>> writes = new ArrayList<>();
        for (StructureGroup g : groups.values()) {
            long nextRefreshAt = now + config.structure.refresh.getIntervalDays(g.structureId()) * 86_400_000L;
            StructureGroup updated = new StructureGroup(g.groupId(), g.world(), g.structureId(), g.minChunkX(),
                g.maxChunkX(), g.minChunkZ(), g.maxChunkZ(), g.detectedAt(), nextRefreshAt, 0L, false);
            groups.put(g.groupId(), updated);
            lastPersistedProtection.put(g.groupId(), 0L);
            writes.add(persist(updated, () -> repository.upsertGroup(updated)));
        }
        return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)).thenApply(ignored -> writes.size());
    }

    public static String displayName(String structureId) {
        int colon = structureId.indexOf(':');
        return colon >= 0 ? structureId.substring(colon + 1) : structureId;
    }

    private void ensureWorldNotResetting(String world) {
        if (resettingWorlds.contains(world)) {
            throw new WorldResetInProgressException("Structure groups are being reset for world " + world);
        }
    }

    private CompletableFuture<Void> persist(StructureGroup updated, Runnable write) {
        UUID groupId = updated.groupId();
        CompletableFuture<Void> current = new CompletableFuture<>();
        AtomicReference<CompletableFuture<Void>> writeFuture = new AtomicReference<>();
        persistenceTails.compute(groupId, (ignored, previous) -> {
            CompletableFuture<Void> preceding = previous != null ? previous : CompletableFuture.completedFuture(null);
            writeFuture.set(preceding.handle((value, failure) -> null).thenRunAsync(write));
            return current;
        });
        writeFuture.get().whenComplete((ignored, failure) -> {
            synchronized (StructureRegistry.this) {
                if (failure == null) {
                    persistedGroups.put(groupId, updated);
                    persistenceFailures.remove(groupId);
                    lastPersistedProtection.put(groupId, updated.protectionTicks());
                }

                if (persistenceTails.get(groupId) == current) {
                    if (failure != null) {
                        StructureGroup persisted = persistedGroups.get(groupId);
                        if (persisted == null) {
                            groups.remove(groupId);
                            lastPersistedProtection.remove(groupId);
                        } else {
                            groups.put(groupId, persisted);
                            lastPersistedProtection.put(groupId, persisted.protectionTicks());
                        }
                        persistenceFailures.put(groupId, failure);
                    }
                    persistenceTails.remove(groupId, current);
                }

            }
            if (failure == null) {
                current.complete(null);
            } else {
                current.completeExceptionally(new CompletionException(failure));
                LOGGER.log(Level.SEVERE, "Failed to persist structure group " + groupId, failure);
            }
        });
        return current;
    }
}
