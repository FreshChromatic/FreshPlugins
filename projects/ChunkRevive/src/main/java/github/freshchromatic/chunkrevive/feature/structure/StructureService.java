package github.freshchromatic.chunkrevive.feature.structure;

import github.freshchromatic.chunkrevive.nms.ChunkCoordinate;
import github.freshchromatic.chunkrevive.feature.marking.MarkRegistry;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import github.freshchromatic.chunkrevive.feature.marking.ProtectedMarkExecutor;
import github.freshchromatic.chunkrevive.config.WorldAccessPolicy;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Structure inspection, detection, state mutation and regeneration preparation use cases. */
public final class StructureService {

    public enum RegenerationStatus {
        READY, REGEN_BUSY, NOT_FOUND, WORLD_NOT_ALLOWED, CLAIM_BLOCKED
    }

    public record RegenerationTarget(RegenerationStatus status, StructureGroup group, List<MarkedChunk> chunks) {}

    public record Detection(String structureId, boolean newGroup, int newlyMarked) {}

    private final MarkRegistry          markRegistry;
    private final StructureRegistry     structureRegistry;
    private final StructureDetector     structureDetector;
    private final WorldAccessPolicy     worldAccessPolicy;
    private final ProtectedMarkExecutor protectedMarks;

    public StructureService(MarkRegistry markRegistry, StructureRegistry structureRegistry,
        StructureDetector structureDetector, WorldAccessPolicy worldAccessPolicy,
        ProtectedMarkExecutor protectedMarks) {
        this.markRegistry = markRegistry;
        this.structureRegistry = structureRegistry;
        this.structureDetector = structureDetector;
        this.worldAccessPolicy = worldAccessPolicy;
        this.protectedMarks = protectedMarks;
    }

    public Optional<StructureGroup> findAt(String world, int cx, int cz) {
        return structureRegistry == null ? Optional.empty() : structureRegistry.findGroupAt(world, cx, cz);
    }

    public boolean canAutoDetect(String world) {
        return worldAccessPolicy.isAllowed(world, WorldAccessPolicy.Scope.STRUCTURE_AUTO_DETECT);
    }

    public Optional<StructureGroup> get(UUID groupId) {
        return structureRegistry == null ? Optional.empty() : structureRegistry.getGroup(groupId);
    }

    public List<StructureGroup> groupsByNextRefresh() {
        if (structureRegistry == null) {
            return List.of();
        }
        return structureRegistry.getAllGroups()
            .stream()
            .sorted(Comparator.comparingLong(StructureGroup::nextRefreshAt))
            .toList();
    }

    public CompletableFuture<RegenerationTarget> prepareRegeneration(UUID groupId) {
        if (markRegistry.getRegenerationQueue().isRunning()) {
            return CompletableFuture.completedFuture(new RegenerationTarget(RegenerationStatus.REGEN_BUSY, null,
                List.of()));
        }
        StructureGroup group = get(groupId).orElse(null);
        if (group == null) {
            return CompletableFuture.completedFuture(new RegenerationTarget(RegenerationStatus.NOT_FOUND, null,
                List.of()));
        }
        if (!worldAccessPolicy.isAllowed(group.world(), WorldAccessPolicy.Scope.REGEN)) {
            return CompletableFuture.completedFuture(new RegenerationTarget(RegenerationStatus.WORLD_NOT_ALLOWED, group,
                List.of()));
        }
        var effective = structureRegistry.resolveEffectiveChunks(group.world(), group.minChunkX(), group.maxChunkX(),
            group.minChunkZ(), group.maxChunkZ());
        if (effective == null || effective.isEmpty()) {
            return CompletableFuture.completedFuture(new RegenerationTarget(RegenerationStatus.CLAIM_BLOCKED, group,
                List.of()));
        }
        long now = System.currentTimeMillis();
        var chunks = new ArrayList<MarkedChunk>();
        for (ChunkCoordinate coordinate : effective) {
            chunks.add(new MarkedChunk(group.world(), coordinate.x(), coordinate.z(), UUID.randomUUID(), now, groupId));
        }
        return protectedMarks.markBatch(chunks).thenApply(outcome -> {
            if (outcome.eligible().isEmpty()) {
                return new RegenerationTarget(RegenerationStatus.CLAIM_BLOCKED, group, List.of());
            }
            if (markRegistry.getRegenerationQueue().isRunning()) {
                return new RegenerationTarget(RegenerationStatus.REGEN_BUSY, group, List.of());
            }
            return new RegenerationTarget(RegenerationStatus.READY, group, outcome.eligible());
        });
    }

    public CompletableFuture<Boolean> unblock(UUID groupId) {
        return structureRegistry == null ? CompletableFuture.completedFuture(false)
            : structureRegistry.unblock(groupId);
    }

    public CompletableFuture<Boolean> block(UUID groupId) {
        return structureRegistry == null ? CompletableFuture.completedFuture(false) : structureRegistry.block(groupId);
    }

    public CompletableFuture<Boolean> reset(UUID groupId) {
        return structureRegistry == null ? CompletableFuture.completedFuture(false)
            : structureRegistry.resetGroup(groupId);
    }

    public CompletableFuture<Integer> resetAll() {
        if (structureRegistry == null) {
            return CompletableFuture.completedFuture(0);
        }
        return structureRegistry.resetAllGroups();
    }

    public CompletableFuture<List<Detection>> detectAndRegister(World world, int blockX, int blockZ, UUID actor) {
        List<CompletableFuture<Detection>> results = new ArrayList<>();
        for (var detected : structureDetector.detectAt(world, blockX, blockZ)) {
            structureRegistry.registerOrTouch(detected, world.getName(), actor).ifPresent(registered -> {
                results.add(protectedMarks.markBatch(registered.chunks()).thenApply(outcome -> new Detection(
                    detected.structureId(), registered.newGroup(), outcome.added().size())));
            });
        }
        return CompletableFuture.allOf(results.toArray(CompletableFuture[]::new)).thenApply(
            ignored -> results.stream().map(CompletableFuture::join).toList());
    }
}
