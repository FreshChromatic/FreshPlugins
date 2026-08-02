package github.freshchromatic.chunkrevive.feature.structure;

import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.integration.protection.LandProtection;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import github.freshchromatic.chunkrevive.feature.structure.StructureGroup;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Owns the in-memory + persisted set of {@link StructureGroup}s and the logic to create/update them. */
public final class StructureRegistry {

    public record RegisterResult(StructureGroup group, List<MarkedChunk> chunks, boolean newGroup) {}

    private final StructureStore repository;
    private final LandProtection landProtection;
    private PluginConfig config;

    private final Map<UUID, StructureGroup> groups = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastPersistedProtection = new ConcurrentHashMap<>();

    public StructureRegistry(StructureStore repository, PluginConfig config, LandProtection landProtection) {
        this.repository = repository;
        this.config = config;
        this.landProtection = landProtection;
    }

    public void setConfig(PluginConfig config) {
        this.config = config;
    }

    public void loadFromDatabase() {
        for (StructureGroup group : repository.loadAllGroups()) {
            groups.put(group.groupId(), group);
        }
    }

    public Collection<StructureGroup> getAllGroups() {
        return groups.values();
    }

    public Optional<StructureGroup> getGroup(UUID groupId) {
        return Optional.ofNullable(groups.get(groupId));
    }

    public Optional<StructureGroup> findGroupAt(String world, int cx, int cz) {
        return groups.values().stream()
            .filter(g -> g.world().equals(world) && g.containsChunk(cx, cz))
            .findFirst();
    }

    /** Like {@link #findGroupAt}, but returns every group whose bounding box covers this chunk (overlapping structures). */
    public List<StructureGroup> findAllGroupsAt(String world, int cx, int cz) {
        return groups.values().stream()
            .filter(g -> g.world().equals(world) && g.containsChunk(cx, cz))
            .toList();
    }

    /** Resolves the structure range to its effective (Residence-aware) chunk set, per structure.residence.on-partial-claim. */
    public List<int[]> resolveEffectiveChunks(String world, int minCx, int maxCx, int minCz, int maxCz) {
        return landProtection.resolveEffectiveChunks(world, minCx, maxCx, minCz, maxCz, config.structure.residence.onPartialClaimEnum());
    }

    /** Finds an existing group covering the exact same range, or creates and persists a new one. */
    public synchronized UUID findOrCreateGroup(String world, String structureId,
                                                int minCx, int maxCx, int minCz, int maxCz) {
        for (StructureGroup g : groups.values()) {
            if (g.world().equals(world) && g.structureId().equals(structureId)
                && g.minChunkX() == minCx && g.maxChunkX() == maxCx
                && g.minChunkZ() == minCz && g.maxChunkZ() == maxCz) {
                return g.groupId();
            }
        }

        long now = System.currentTimeMillis();
        long nextRefreshAt = now + config.structure.refresh.getIntervalDays(structureId) * 86_400_000L;
        StructureGroup group = new StructureGroup(UUID.randomUUID(), world, structureId,
            minCx, maxCx, minCz, maxCz, now, nextRefreshAt, 0L, false);
        groups.put(group.groupId(), group);
        CompletableFuture.runAsync(() -> repository.upsertGroup(group));
        return group.groupId();
    }

    /**
     * Auto-detect entry point (via StructureService): resolves the structure's effective chunk set,
     * creates the group if needed, and returns the chunks the caller should mark.
     * Returns empty if the whole range is excluded by the Residence policy.
     */
    public Optional<RegisterResult> registerOrTouch(StructureDetector.Detected detected, String world, UUID markedBy) {
        var bb = detected.boundingBox();
        int minCx = bb.minX() >> 4, maxCx = bb.maxX() >> 4;
        int minCz = bb.minZ() >> 4, maxCz = bb.maxZ() >> 4;

        List<int[]> effective = resolveEffectiveChunks(world, minCx, maxCx, minCz, maxCz);
        if (effective == null || effective.isEmpty()) return Optional.empty();

        boolean isNew = groups.values().stream().noneMatch(g ->
            g.world().equals(world) && g.structureId().equals(detected.structureId())
                && g.minChunkX() == minCx && g.maxChunkX() == maxCx
                && g.minChunkZ() == minCz && g.maxChunkZ() == maxCz);

        UUID groupId = findOrCreateGroup(world, detected.structureId(), minCx, maxCx, minCz, maxCz);
        StructureGroup group = groups.get(groupId);
        if (!isNew && group.nextRefreshAt() <= 0) {
            long now = System.currentTimeMillis();
            long nextRefreshAt = now + config.structure.refresh.getIntervalDays(detected.structureId()) * 86_400_000L;
            updateGroup(group.withNextRefreshAt(nextRefreshAt));
        }

        long now = System.currentTimeMillis();
        List<MarkedChunk> chunks = new ArrayList<>();
        for (int[] coord : effective) {
            chunks.add(new MarkedChunk(world, coord[0], coord[1], markedBy, now, groupId));
        }
        return Optional.of(new RegisterResult(groups.get(groupId), chunks, isNew));
    }

    public void postponeRefresh(UUID groupId, long deltaTicks) {
        StructureGroup g = groups.get(groupId);
        if (g == null) return;
        StructureGroup updated = g.withNextRefreshAt(System.currentTimeMillis() + deltaTicks * 50L);
        updateGroup(updated);
    }

    public void scheduleNextRefresh(UUID groupId) {
        StructureGroup g = groups.get(groupId);
        if (g == null) return;
        long now = System.currentTimeMillis();
        long nextRefreshAt = now + config.structure.refresh.getIntervalDays(g.structureId()) * 86_400_000L;
        StructureGroup updated = new StructureGroup(g.groupId(), g.world(), g.structureId(),
            g.minChunkX(), g.maxChunkX(), g.minChunkZ(), g.maxChunkZ(),
            g.detectedAt(), nextRefreshAt, 0L, false);
        groups.put(groupId, updated);
        lastPersistedProtection.put(groupId, 0L);
        CompletableFuture.runAsync(() -> repository.upsertGroup(updated));
    }

    public void addProtectionTicks(UUID groupId, long ticks) {
        StructureGroup g = groups.get(groupId);
        if (g == null || g.blocked()) return;

        long newTicks = g.protectionTicks() + ticks;
        boolean nowBlocked = newTicks >= config.structure.protection.requiredTicks;
        StructureGroup updated = g.withProtection(newTicks, nowBlocked);
        groups.put(groupId, updated);

        if (nowBlocked) {
            // State transition is critical — flush immediately, no throttling.
            lastPersistedProtection.put(groupId, newTicks);
            CompletableFuture.runAsync(() -> repository.updateProtection(groupId, newTicks, true));
            return;
        }

        long lastPersisted = lastPersistedProtection.getOrDefault(groupId, 0L);
        if (newTicks - lastPersisted >= config.structure.protection.flushIntervalTicks) {
            lastPersistedProtection.put(groupId, newTicks);
            CompletableFuture.runAsync(() -> repository.updateProtection(groupId, newTicks, false));
        }
    }

    public void resetProtectionTicks(UUID groupId) {
        StructureGroup g = groups.get(groupId);
        if (g == null || g.blocked() || g.protectionTicks() == 0) return;
        StructureGroup updated = g.withProtection(0L, false);
        groups.put(groupId, updated);
        lastPersistedProtection.put(groupId, 0L);
        CompletableFuture.runAsync(() -> repository.updateProtection(groupId, 0L, false));
    }

    /** Removes all structure groups for a world (resetmark); returns how many were removed. */
    public int removeGroupsForWorld(String world) {
        List<UUID> ids = groups.values().stream()
            .filter(g -> g.world().equals(world))
            .map(StructureGroup::groupId)
            .toList();
        ids.forEach(groups::remove);
        ids.forEach(lastPersistedProtection::remove);
        return ids.size();
    }

    public boolean unblock(UUID groupId) {
        StructureGroup g = groups.get(groupId);
        if (g == null) return false;
        StructureGroup updated = g.withProtection(0L, false);
        groups.put(groupId, updated);
        lastPersistedProtection.put(groupId, 0L);
        CompletableFuture.runAsync(() -> repository.updateProtection(groupId, 0L, false));
        return true;
    }

    public boolean block(UUID groupId) {
        StructureGroup g = groups.get(groupId);
        if (g == null) return false;
        long required = config.structure.protection.requiredTicks;
        StructureGroup updated = g.withProtection(required, true);
        groups.put(groupId, updated);
        lastPersistedProtection.put(groupId, required);
        CompletableFuture.runAsync(() -> repository.updateProtection(groupId, required, true));
        return true;
    }

    public boolean resetGroup(UUID groupId) {
        StructureGroup g = groups.get(groupId);
        if (g == null) return false;
        long now = System.currentTimeMillis();
        long nextRefreshAt = now + config.structure.refresh.getIntervalDays(g.structureId()) * 86_400_000L;
        StructureGroup updated = new StructureGroup(g.groupId(), g.world(), g.structureId(),
            g.minChunkX(), g.maxChunkX(), g.minChunkZ(), g.maxChunkZ(),
            g.detectedAt(), nextRefreshAt, 0L, false);
        groups.put(groupId, updated);
        lastPersistedProtection.put(groupId, 0L);
        CompletableFuture.runAsync(() -> repository.upsertGroup(updated));
        return true;
    }

    public void resetAllGroups() {
        long now = System.currentTimeMillis();
        for (StructureGroup g : groups.values()) {
            long nextRefreshAt = now + config.structure.refresh.getIntervalDays(g.structureId()) * 86_400_000L;
            StructureGroup updated = new StructureGroup(g.groupId(), g.world(), g.structureId(),
                g.minChunkX(), g.maxChunkX(), g.minChunkZ(), g.maxChunkZ(),
                g.detectedAt(), nextRefreshAt, 0L, false);
            groups.put(g.groupId(), updated);
            lastPersistedProtection.put(g.groupId(), 0L);
            CompletableFuture.runAsync(() -> repository.upsertGroup(updated));
        }
    }

    private void updateGroup(StructureGroup updated) {
        groups.put(updated.groupId(), updated);
        CompletableFuture.runAsync(() -> repository.upsertGroup(updated));
    }

    public static String displayName(String structureId) {
        int colon = structureId.indexOf(':');
        return colon >= 0 ? structureId.substring(colon + 1) : structureId;
    }
}
