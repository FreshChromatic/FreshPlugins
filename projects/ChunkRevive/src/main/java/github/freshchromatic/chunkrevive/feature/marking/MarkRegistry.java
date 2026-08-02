package github.freshchromatic.chunkrevive.feature.marking;

import github.freshchromatic.chunkrevive.config.Messages;
import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.feature.regeneration.RegenerationService;
import github.freshchromatic.chunkrevive.feature.regeneration.RegenerationQueue;
import github.freshchromatic.chunkrevive.integration.protection.LandProtection;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import github.freshchromatic.chunkrevive.feature.structure.StructureRegistry;
import github.freshchromatic.chunkrevive.feature.structure.StructureMarkExpander;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class MarkRegistry {

    // Probe UUID for positional lookups — MarkedChunk.equals()/hashCode() are purely positional
    // (world+cx+cz), so any instance with this filler UUID is "equal" to the real stored chunk.
    private static final UUID PROBE_UUID = new UUID(0L, 0L);

    private final MarkStore repository;
    private final RegenerationService regenerationService;
    private final LandProtection landProtection;
    private StructureMarkExpander structureMarkExpander;
    private StructureRegistry structureRegistry;
    private PluginConfig config;
    private Messages messages;

    // Keyed and valued by the same MarkedChunk so a positional probe (PROBE_UUID, any markedAt/groupId)
    // resolves the real stored instance (with its real metadata) in O(1) — previously this was backed by
    // a plain Set, forcing unmark()/isMarked()/onChunkRegenComplete() into O(n) full-set stream scans,
    // which became a multi-second Folia watchdog stall once thousands of chunks were marked (e.g. via
    // /cr fullmark) and a regen batch finished and needed to look each of its chunks up here.
    private final Map<MarkedChunk, MarkedChunk> markedChunks = new ConcurrentHashMap<>();
    private final Map<UUID, FollowMode> followModes = new ConcurrentHashMap<>();

    private final RegenerationQueue regenerationQueue;
    private MarkDisplay markDisplay;

    public MarkRegistry(MarkStore repository, RegenerationService regenerationService,
                               PluginConfig config, Messages messages, LandProtection landProtection) {
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

    /** Called once a marked chunk finishes regenerating; routes structure chunks to the refresh scheduler instead of unmarking them. */
    public void onChunkRegenComplete(String world, int cx, int cz) {
        MarkedChunk found = markedChunks.get(new MarkedChunk(world, cx, cz, PROBE_UUID, 0));

        UUID groupId = found != null ? found.structureGroupId() : null;
        if (groupId != null) {
            if (structureRegistry != null) structureRegistry.scheduleNextRefresh(groupId);
        } else if (config.regen.autoUnmarkAfterRegen) {
            unmark(world, cx, cz, null);
        }
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
        for (MarkedChunk c : repository.loadAll()) {
            markedChunks.put(c, c);
        }
    }

    public MarkResult mark(String world, int cx, int cz, UUID markedBy) {
        var w = Bukkit.getWorld(world);

        List<MarkedChunk> toMark;
        if (w != null && structureMarkExpander != null) {
            toMark = structureMarkExpander.expand(w, cx, cz, markedBy);
        } else if (w != null && landProtection.hasClaim(w, cx, cz)) {
            toMark = List.of();
        } else {
            toMark = List.of(new MarkedChunk(world, cx, cz, markedBy, System.currentTimeMillis()));
        }

        if (toMark.isEmpty()) return MarkResult.RESIDENCE_BLOCKED;

        List<MarkedChunk> newlyAdded = new ArrayList<>();
        for (MarkedChunk chunk : toMark) {
            if (markedChunks.putIfAbsent(chunk, chunk) == null) newlyAdded.add(chunk);
        }
        if (newlyAdded.isEmpty()) return MarkResult.ALREADY_MARKED;

        CompletableFuture.runAsync(() -> newlyAdded.forEach(repository::mark));
        if (markDisplay != null) newlyAdded.forEach(markDisplay::onChunkMarked);
        return MarkResult.SUCCESS;
    }

    /**
     * API-only variant whose future completes only after every new mark has been persisted.
     * Callers must invoke it from a Bukkit-safe scheduler because claim and structure expansion
     * consult live world state.
     */
    public CompletableFuture<MarkResult> markPersisted(String world, int cx, int cz, UUID markedBy) {
        var w = Bukkit.getWorld(world);
        List<MarkedChunk> toMark;
        if (w != null && structureMarkExpander != null) {
            toMark = structureMarkExpander.expand(w, cx, cz, markedBy);
        } else if (w != null && landProtection.hasClaim(w, cx, cz)) {
            toMark = List.of();
        } else {
            toMark = List.of(new MarkedChunk(world, cx, cz, markedBy, System.currentTimeMillis()));
        }
        if (toMark.isEmpty()) return CompletableFuture.completedFuture(MarkResult.RESIDENCE_BLOCKED);

        List<MarkedChunk> added = new ArrayList<>();
        synchronized (markedChunks) {
            for (MarkedChunk chunk : toMark) {
                if (markedChunks.putIfAbsent(chunk, chunk) == null) added.add(chunk);
            }
        }
        if (added.isEmpty()) return CompletableFuture.completedFuture(MarkResult.ALREADY_MARKED);

        return CompletableFuture.supplyAsync(() -> {
            boolean persisted = added.stream().allMatch(repository::mark);
            if (!persisted) {
                added.forEach(markedChunks::remove);
                throw new IllegalStateException("Failed to persist marked chunks");
            }
            return MarkResult.SUCCESS;
        });
    }

    /** Marks chunks already resolved by the structure subsystem (auto-detect path); skips any already marked. */
    public List<MarkedChunk> markChunksDirect(List<MarkedChunk> chunks) {
        List<MarkedChunk> newlyAdded = new ArrayList<>();
        for (MarkedChunk chunk : chunks) {
            if (markedChunks.putIfAbsent(chunk, chunk) == null) newlyAdded.add(chunk);
        }
        if (!newlyAdded.isEmpty()) {
            CompletableFuture.runAsync(() -> newlyAdded.forEach(repository::mark));
            if (markDisplay != null) newlyAdded.forEach(markDisplay::onChunkMarked);
        }
        return newlyAdded;
    }

    /** Bulk-marks chunks already resolved by DiskChunkScanner, persisting via a single batched transaction per call. */
    public List<MarkedChunk> markChunksBatch(List<MarkedChunk> chunks) {
        List<MarkedChunk> newlyAdded = new ArrayList<>();
        for (MarkedChunk chunk : chunks) {
            if (markedChunks.putIfAbsent(chunk, chunk) == null) newlyAdded.add(chunk);
        }
        if (!newlyAdded.isEmpty()) {
            CompletableFuture.runAsync(() -> repository.markBatch(newlyAdded));
            if (markDisplay != null) newlyAdded.forEach(markDisplay::onChunkMarked);
        }
        return newlyAdded;
    }

    public record ResetResult(int chunkCount, int groupCount, int pendingRemoved) {}

    /** Clears all marks/structure groups for a world (resetmark); does not touch terrain. */
    public ResetResult resetMarkForWorld(String world) {
        List<MarkedChunk> toRemove = markedChunks.values().stream().filter(c -> c.world().equals(world)).toList();
        toRemove.forEach(markedChunks::remove);
        if (markDisplay != null) toRemove.forEach(markDisplay::onChunkUnmarked);

        int groupCount = structureRegistry != null ? structureRegistry.removeGroupsForWorld(world) : 0;
        int pendingRemoved = regenerationQueue.removePendingForWorld(world);

        CompletableFuture.runAsync(() -> repository.deleteAllForWorld(world));
        return new ResetResult(toRemove.size(), groupCount, pendingRemoved);
    }

    public boolean unmark(String world, int cx, int cz, UUID actor) {
        MarkedChunk removed = markedChunks.remove(new MarkedChunk(world, cx, cz, PROBE_UUID, 0));
        if (removed == null) return false;
        CompletableFuture.runAsync(() -> repository.unmark(world, cx, cz));
        if (markDisplay != null) markDisplay.onChunkUnmarked(removed);
        return true;
    }

    /** API-only durable unmark counterpart. Must be called from a Bukkit-safe scheduler. */
    public CompletableFuture<Optional<MarkedChunk>> unmarkPersisted(String world, int cx, int cz) {
        MarkedChunk removed = markedChunks.remove(new MarkedChunk(world, cx, cz, PROBE_UUID, 0));
        if (removed == null) return CompletableFuture.completedFuture(Optional.empty());
        return CompletableFuture.supplyAsync(() -> {
            if (!repository.unmark(world, cx, cz)) {
                markedChunks.putIfAbsent(removed, removed);
                throw new IllegalStateException("Failed to persist mark removal");
            }
            return Optional.of(removed);
        });
    }

    /** Must be invoked from a Bukkit-safe scheduler after a durable API write. */
    public void refreshMarkedDisplay(String world, int cx, int cz) {
        MarkedChunk chunk = markedChunks.get(new MarkedChunk(world, cx, cz, PROBE_UUID, 0));
        if (chunk != null && markDisplay != null) markDisplay.onChunkMarked(chunk);
    }

    /** Must be invoked from a Bukkit-safe scheduler after a durable API delete. */
    public void refreshUnmarkedDisplay(MarkedChunk chunk) {
        if (markDisplay != null) markDisplay.onChunkUnmarked(chunk);
    }

    public void unmarkChunksBatch(java.util.Collection<MarkedChunk> chunks) {
        List<MarkedChunk> removedList = new ArrayList<>();
        for (MarkedChunk c : chunks) {
            MarkedChunk removed = markedChunks.remove(new MarkedChunk(c.world(), c.cx(), c.cz(), PROBE_UUID, 0));
            if (removed != null) {
                removedList.add(removed);
            }
        }
        if (!removedList.isEmpty()) {
            CompletableFuture.runAsync(() -> repository.unmarkBatch(removedList));
            if (markDisplay != null) {
                removedList.forEach(markDisplay::onChunkUnmarked);
            }
        }
    }

    /** Removes marks and queued (not yet running) work covered by a newly-created Residence area. */
    public int unmarkResidenceArea(String world, int minCx, int maxCx, int minCz, int maxCz) {
        List<MarkedChunk> covered = markedChunks.values().stream()
            .filter(c -> c.world().equals(world)
                && c.cx() >= minCx && c.cx() <= maxCx
                && c.cz() >= minCz && c.cz() <= maxCz)
            .toList();
        regenerationQueue.removePendingInRange(world, minCx, maxCx, minCz, maxCz);
        unmarkChunksBatch(covered);
        return covered.size();
    }

    public void onChunksRegenComplete(java.util.Collection<MarkedChunk> chunks) {
        List<MarkedChunk> toUnmark = new ArrayList<>();
        for (MarkedChunk c : chunks) {
            MarkedChunk found = markedChunks.get(new MarkedChunk(c.world(), c.cx(), c.cz(), PROBE_UUID, 0));
            UUID groupId = found != null ? found.structureGroupId() : null;
            if (groupId != null) {
                if (structureRegistry != null) structureRegistry.scheduleNextRefresh(groupId);
            } else if (config.regen.autoUnmarkAfterRegen) {
                toUnmark.add(c);
            }
        }
        if (!toUnmark.isEmpty()) {
            unmarkChunksBatch(toUnmark);
        }
    }

    public boolean isMarked(String world, int cx, int cz) {
        return markedChunks.containsKey(new MarkedChunk(world, cx, cz, PROBE_UUID, 0));
    }

    public List<MarkedChunk> getMarkedChunksNear(String world, int cx, int cz, int radiusChunks) {
        return markedChunks.values().stream()
            .filter(c -> c.world().equals(world)
                && Math.abs(c.cx() - cx) <= radiusChunks
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
        return Collections.unmodifiableCollection(markedChunks.values());
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
