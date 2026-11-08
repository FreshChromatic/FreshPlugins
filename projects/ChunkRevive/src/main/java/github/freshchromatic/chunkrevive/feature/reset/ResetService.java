package github.freshchromatic.chunkrevive.feature.reset;

import github.freshchromatic.chunkrevive.nms.ChunkArea;

import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.feature.marking.MarkRegistry;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import github.freshchromatic.chunkrevive.feature.structure.StructureGroup;
import github.freshchromatic.chunkrevive.feature.structure.StructureRegistry;
import github.freshchromatic.chunkrevive.config.WorldAccessPolicy;
import github.freshchromatic.freshlib.util.Logging;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Reusable regeneration, deletion-target selection and reset-strategy workflows. */
public final class ResetService {

    public enum QueueStatus {
        QUEUED, EMPTY, REGEN_BUSY, WORLD_NOT_ALLOWED, WORLD_NOT_FOUND, CLAIM_BLOCKED
    }

    public enum RegenScope {
        CHUNKS, STRUCTURES, ALL
    }

    public enum StructureTargetStatus {
        READY, NOT_FOUND, EMPTY, CLAIM_BLOCKED
    }

    public record BulkResetResult(QueueStatus status, ResetStrategyPlanner.Plan plan) {}

    public record StructureDeletionTarget(StructureTargetStatus status, StructureGroup group,
        List<MarkedChunk> chunks) {}

    private static final ResetStrategyPlanner.Plan EMPTY_PLAN = new ResetStrategyPlanner.Plan(List.of(), List.of(),
        List.of());

    private final MarkRegistry           markRegistry;
    private final StructureRegistry      structureRegistry;
    private final WorldAccessPolicy      worldAccessPolicy;
    private final DeletionService        deletionService;
    private final Supplier<PluginConfig> config;
    private final Executor               stateExecutor;

    public ResetService(MarkRegistry markRegistry, StructureRegistry structureRegistry,
        WorldAccessPolicy worldAccessPolicy, DeletionService deletionService, Supplier<PluginConfig> config,
        Executor stateExecutor) {
        this.markRegistry = markRegistry;
        this.structureRegistry = structureRegistry;
        this.worldAccessPolicy = worldAccessPolicy;
        this.deletionService = deletionService;
        this.config = config;
        this.stateExecutor = stateExecutor;
    }

    public boolean isWorldAllowed(String world) {
        return worldAccessPolicy.isAllowed(world, WorldAccessPolicy.Scope.REGEN);
    }

    public int confirmationTimeoutSeconds() {
        return config.get().safety.confirmTimeoutSeconds;
    }

    public boolean regenerationQueueRunning() {
        return markRegistry.getRegenerationQueue().isRunning();
    }

    public boolean bulkResetRequiresConfirmation(ResetStrategyPlanner.Plan plan, int targetCount) {
        return plan.requiresConfirmation(targetCount, config.get().safety.bulkRegenConfirmThresholdChunks);
    }

    public boolean bulkRegenerationRequiresConfirmation(int targetCount) {
        return targetCount > config.get().safety.bulkRegenConfirmThresholdChunks;
    }

    public Optional<UUID> structureGroupAt(String world, int cx, int cz) {
        return markRegistry.getMarkedChunks()
            .stream()
            .filter(chunk -> chunk.world().equals(world) && chunk.cx() == cx
            && chunk.cz() == cz)
            .map(MarkedChunk::structureGroupId)
            .filter(Objects::nonNull)
            .findFirst();
    }

    public Optional<StructureGroup> structureGroup(UUID groupId) {
        return structureRegistry == null ? Optional.empty() : structureRegistry.getGroup(groupId);
    }

    public List<MarkedChunk> markedChunksInStructure(UUID groupId) {
        return markRegistry.getMarkedChunks()
            .stream()
            .filter(chunk -> groupId.equals(chunk.structureGroupId()))
            .toList();
    }

    private ChunkArea structureContextBounds(String world, int cx, int cz) {
        if (structureRegistry == null) {
            return null;
        }
        return structureGroupAt(world, cx, cz)
            .flatMap(structureRegistry::getGroup)
            .map(group -> new ChunkArea(
            group.minChunkX(), group.maxChunkX(), group.minChunkZ(), group.maxChunkZ()))
            .orElse(null);
    }

    public List<MarkedChunk> markedDeletionTargets(String requestedWorld) {
        return markRegistry.getMarkedChunks()
            .stream()
            .filter(chunk -> isWorldAllowed(chunk.world()))
            .filter(chunk -> requestedWorld == null || chunk.world().equals(requestedWorld))
            .toList();
    }

    public StructureDeletionTarget structureDeletionTarget(World world, int cx, int cz) {
        UUID groupId = structureGroupAt(world.getName(), cx, cz).orElse(null);
        StructureGroup group = groupId == null || structureRegistry == null ? null
            : structureRegistry.getGroup(groupId).orElse(null);
        if (group == null) {
            return new StructureDeletionTarget(StructureTargetStatus.NOT_FOUND, null, List.of());
        }
        List<MarkedChunk> chunks = markedChunksInStructure(groupId);
        if (chunks.isEmpty()) {
            return new StructureDeletionTarget(StructureTargetStatus.EMPTY, group, chunks);
        }
        if (chunks.stream().anyMatch(chunk -> markRegistry.getLandProtection().hasClaim(world, chunk.cx(),
            chunk.cz()))) {
            return new StructureDeletionTarget(StructureTargetStatus.CLAIM_BLOCKED, group, chunks);
        }
        return new StructureDeletionTarget(StructureTargetStatus.READY, group, chunks);
    }

    public QueueStatus queueChunkDelete(Audience sender, World world, int cx, int cz) {
        if (!isWorldAllowed(world.getName())) {
            return QueueStatus.WORLD_NOT_ALLOWED;
        }
        if (markRegistry.getLandProtection().hasClaim(world, cx, cz)) {
            return QueueStatus.CLAIM_BLOCKED;
        }
        deletionService.queueChunk(sender, world.getName(), cx, cz);
        return QueueStatus.QUEUED;
    }

    public QueueStatus resetSingle(Audience sender, MarkedChunk chunk) {
        ResetMethod method = resetMethodFor(chunk);
        if (method == ResetMethod.DELETE_REGION) {
            AnvilRegionPos region = AnvilRegionPos.fromChunk(chunk.cx(), chunk.cz());
            deletionService.queueRegion(sender, chunk.world(), region.x(), region.z());
            return QueueStatus.QUEUED;
        }
        if (method == ResetMethod.DELETE_CHUNK) {
            World world = Bukkit.getWorld(chunk.world());
            return world == null ? QueueStatus.WORLD_NOT_FOUND
                : queueChunkDelete(sender, world, chunk.cx(), chunk.cz());
        }
        return regenerateSingle(sender, chunk);
    }

    /** Resolves the configured reset strategy without starting any work. */
    public ResetMethod resetMethodFor(MarkedChunk chunk) {
        var strategy = config.get().resetStrategy;
        AnvilRegionPos region = AnvilRegionPos.fromChunk(chunk.cx(), chunk.cz());
        var regionTarget = new ResetStrategyPlanner.RegionTarget(chunk.world(), region);
        boolean completeAndSafe = hasCompleteMarkedRegion(regionTarget) && isSafeCompleteRegion(regionTarget);
        return ResetStrategyPlanner.resolve(completeAndSafe ? strategy.eligibleRegionMethodEnum()
            : strategy.incompleteRegionMethodEnum(), strategy.defaultMethodEnum(), completeAndSafe);
    }

    /** Regenerates one chunk regardless of reset-strategy configuration. */
    public QueueStatus regenerateSingle(Audience sender, MarkedChunk chunk) {
        ChunkArea structureBounds = structureContextBounds(chunk.world(), chunk.cx(), chunk.cz());
        markRegistry.getRegenerationService()
            .regenChunk(sender, chunk, structureBounds)
            .whenComplete((ignored,
            failure) -> {
                if (failure == null) {
                    markRegistry.onChunkRegenComplete(chunk.world(), chunk.cx(), chunk.cz(), stateExecutor).exceptionally(
                        persistenceFailure -> {
                            Logging.logger().severe("Failed to persist mark removal after regeneration", persistenceFailure);
                            return null;
                        });
                }
            });
        return QueueStatus.QUEUED;
    }

    public ResetStrategyPlanner.Plan previewResetBulk(Collection<MarkedChunk> targets) {
        var strategy = config.get().resetStrategy;
        return ResetStrategyPlanner.plan(targets, strategy.defaultMethodEnum(), strategy.eligibleRegionMethodEnum(),
            strategy.incompleteRegionMethodEnum(), this::isSafeCompleteRegion);
    }

    public BulkResetResult resetBulk(Audience sender, Collection<MarkedChunk> targets) {
        var plan = previewResetBulk(targets);
        return executePlan(sender, plan);
    }

    public BulkResetResult resetBulk(Audience sender, ResetStrategyPlanner.Plan plan) {
        return executePlan(sender, plan);
    }

    /** Regenerates every target regardless of reset-strategy configuration. */
    public BulkResetResult regenerateBulk(Audience sender, Collection<MarkedChunk> targets) {
        List<MarkedChunk> chunks = List.copyOf(targets);
        var plan = new ResetStrategyPlanner.Plan(List.of(), List.of(), chunks);
        return executePlan(sender, plan);
    }

    private BulkResetResult executePlan(Audience sender, ResetStrategyPlanner.Plan plan) {
        if (plan.isEmpty()) {
            return new BulkResetResult(QueueStatus.EMPTY, plan);
        }
        Runnable queueDeletions = () -> queueDeletions(sender, plan);
        if (plan.regenerateChunks().isEmpty()) {
            queueDeletions.run();
            return new BulkResetResult(QueueStatus.QUEUED, plan);
        }
        boolean started = markRegistry.getRegenerationQueue().start(plan.regenerateChunks(), sender,
            completed -> markRegistry.onChunksRegenComplete(completed, stateExecutor).whenComplete((ignored,
                failure) -> {
                    if (failure != null) {
                        Logging.logger().severe("Failed to persist mark removals after regeneration", failure);
                    }
                }), queueDeletions);
        if (!started) {
            return new BulkResetResult(QueueStatus.REGEN_BUSY, plan);
        }
        return new BulkResetResult(QueueStatus.QUEUED, plan);
    }

    private void queueDeletions(Audience sender, ResetStrategyPlanner.Plan plan) {
        if (!plan.deleteRegions().isEmpty()) {
            Map<String, List<AnvilRegionPos>> byWorld = new LinkedHashMap<>();
            for (var region : plan.deleteRegions()) {
                byWorld.computeIfAbsent(region.world(), ignored -> new ArrayList<>()).add(region.region());
            }
            deletionService.queueRegions(sender, byWorld);
        }
        if (!plan.deleteChunks().isEmpty()) {
            deletionService.queueChunks(sender, plan.deleteChunks());
        }
    }

    public Map<String, Set<AnvilRegionPos>> completeMarkedRegions() {
        Map<String, Map<AnvilRegionPos, Long>> counts = markRegistry.getMarkedChunks()
            .stream()
            .filter(chunk -> isWorldAllowed(chunk.world()))
            .collect(Collectors.groupingBy(MarkedChunk::world,
                Collectors.groupingBy(chunk -> AnvilRegionPos.fromChunk(chunk.cx(), chunk.cz()), Collectors.counting())));
        Map<String, Set<AnvilRegionPos>> regions = new HashMap<>();
        counts.forEach((world, regionCounts) -> {
            Set<AnvilRegionPos> complete = regionCounts.entrySet()
                .stream()
                .filter(entry -> entry.getValue() == (long) AnvilRegionPos.WIDTH * AnvilRegionPos.WIDTH)
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
            if (!complete.isEmpty()) {
                regions.put(world, complete);
            }
        });
        return regions;
    }

    public List<MarkedChunk> regenerationTargets(RegenScope scope) {
        return markRegistry.getMarkedChunks()
            .stream()
            .filter(chunk -> matchesScope(chunk, scope))
            .filter(this::isStructureAvailable)
            .filter(chunk -> isWorldAllowed(chunk.world()))
            .toList();
    }

    private boolean matchesScope(MarkedChunk chunk, RegenScope scope) {
        return switch (scope) {
            case CHUNKS -> chunk.structureGroupId() == null;
            case STRUCTURES -> chunk.structureGroupId() != null;
            case ALL -> true;
        };
    }

    private boolean isStructureAvailable(MarkedChunk chunk) {
        if (chunk.structureGroupId() == null || structureRegistry == null) {
            return true;
        }
        return structureRegistry.getGroup(chunk.structureGroupId()).map(group -> !group.blocked()).orElse(true);
    }

    private boolean hasCompleteMarkedRegion(ResetStrategyPlanner.RegionTarget target) {
        AnvilRegionPos region = target.region();
        long count = markRegistry.getMarkedChunks()
            .stream()
            .filter(chunk -> chunk.world().equals(target.world())
            && chunk.cx() >= region.minChunkX() && chunk.cx() <= region.maxChunkX() && chunk.cz() >= region.minChunkZ()
            && chunk.cz() <= region.maxChunkZ())
            .map(chunk -> (((long) chunk.cx()) << 32) ^ (chunk.cz()
                & 0xffffffffL))
            .distinct()
            .count();
        return count == (long) AnvilRegionPos.WIDTH * AnvilRegionPos.WIDTH;
    }

    private boolean isSafeCompleteRegion(ResetStrategyPlanner.RegionTarget target) {
        if (!isWorldAllowed(target.world())) {
            return false;
        }
        World world = Bukkit.getWorld(target.world());
        if (world == null || deletionService.regionHasResidence(world, target.region())) {
            return false;
        }
        if (structureRegistry == null) {
            return true;
        }
        AnvilRegionPos region = target.region();
        return structureRegistry.getAllGroups()
            .stream()
            .noneMatch(group -> group.blocked() && group.world().equals(target.world()) && group.maxChunkX() >= region.minChunkX() && group.minChunkX() <= region.maxChunkX()
            && group.maxChunkZ() >= region.minChunkZ() && group.minChunkZ() <= region.maxChunkZ());
    }
}
