package github.freshchromatic.chunkrevive.feature.structure;

import github.freshchromatic.chunkrevive.feature.marking.MarkRegistry;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import github.freshchromatic.chunkrevive.feature.structure.StructureDetector;
import github.freshchromatic.chunkrevive.feature.structure.StructureGroup;
import github.freshchromatic.chunkrevive.feature.structure.StructureRegistry;
import github.freshchromatic.chunkrevive.config.WorldAccessPolicy;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Structure inspection, detection, state mutation and regeneration preparation use cases. */
public final class StructureService {
    public enum RegenerationStatus { READY, REGEN_BUSY, NOT_FOUND, WORLD_NOT_ALLOWED, CLAIM_BLOCKED }

    public record RegenerationTarget(
        RegenerationStatus status, StructureGroup group, List<MarkedChunk> chunks) {}
    public record Detection(String structureId, boolean newGroup, int newlyMarked) {}

    private final MarkRegistry markRegistry;
    private final StructureRegistry structureRegistry;
    private final StructureDetector structureDetector;
    private final WorldAccessPolicy worldAccessPolicy;

    public StructureService(
            MarkRegistry markRegistry,
            StructureRegistry structureRegistry,
            StructureDetector structureDetector,
            WorldAccessPolicy worldAccessPolicy) {
        this.markRegistry = markRegistry;
        this.structureRegistry = structureRegistry;
        this.structureDetector = structureDetector;
        this.worldAccessPolicy = worldAccessPolicy;
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
        if (structureRegistry == null) return List.of();
        return structureRegistry.getAllGroups().stream()
            .sorted(Comparator.comparingLong(StructureGroup::nextRefreshAt))
            .toList();
    }

    public RegenerationTarget prepareRegeneration(UUID groupId) {
        if (markRegistry.getRegenerationQueue().isRunning()) {
            return new RegenerationTarget(RegenerationStatus.REGEN_BUSY, null, List.of());
        }
        StructureGroup group = get(groupId).orElse(null);
        if (group == null) {
            return new RegenerationTarget(RegenerationStatus.NOT_FOUND, null, List.of());
        }
        if (!worldAccessPolicy.isAllowed(group.world(), WorldAccessPolicy.Scope.REGEN)) {
            return new RegenerationTarget(RegenerationStatus.WORLD_NOT_ALLOWED, group, List.of());
        }
        var effective = structureRegistry.resolveEffectiveChunks(
            group.world(), group.minChunkX(), group.maxChunkX(), group.minChunkZ(), group.maxChunkZ());
        if (effective == null || effective.isEmpty()) {
            return new RegenerationTarget(RegenerationStatus.CLAIM_BLOCKED, group, List.of());
        }
        long now = System.currentTimeMillis();
        var chunks = new ArrayList<MarkedChunk>();
        for (int[] coordinate : effective) {
            chunks.add(new MarkedChunk(
                group.world(), coordinate[0], coordinate[1], UUID.randomUUID(), now, groupId));
        }
        markRegistry.markChunksDirect(chunks);
        return new RegenerationTarget(RegenerationStatus.READY, group, List.copyOf(chunks));
    }

    public boolean unblock(UUID groupId) {
        return structureRegistry != null && structureRegistry.unblock(groupId);
    }

    public boolean block(UUID groupId) {
        return structureRegistry != null && structureRegistry.block(groupId);
    }

    public boolean reset(UUID groupId) {
        return structureRegistry != null && structureRegistry.resetGroup(groupId);
    }

    public int resetAll() {
        if (structureRegistry == null) return 0;
        int count = structureRegistry.getAllGroups().size();
        structureRegistry.resetAllGroups();
        return count;
    }

    public List<Detection> detectAndRegister(World world, int blockX, int blockZ, UUID actor) {
        List<Detection> results = new ArrayList<>();
        for (var detected : structureDetector.detectAt(world, blockX, blockZ)) {
            structureRegistry.registerOrTouch(detected, world.getName(), actor).ifPresent(registered -> {
                int newlyMarked = markRegistry.markChunksDirect(registered.chunks()).size();
                results.add(new Detection(detected.structureId(), registered.newGroup(), newlyMarked));
            });
        }
        return List.copyOf(results);
    }
}
