package github.freshchromatic.chunkrevive.feature.marking;

import github.freshchromatic.chunkrevive.feature.marking.MarkRegistry;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import github.freshchromatic.chunkrevive.config.WorldAccessPolicy;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Application use cases for marked chunks, shared by commands and listeners. */
public final class MarkService {
    public enum MarkStatus { SUCCESS, ALREADY_MARKED, CLAIM_BLOCKED, WORLD_NOT_ALLOWED }

    private final MarkRegistry markRegistry;
    private final WorldAccessPolicy worldAccessPolicy;

    public MarkService(MarkRegistry markRegistry, WorldAccessPolicy worldAccessPolicy) {
        this.markRegistry = markRegistry;
        this.worldAccessPolicy = worldAccessPolicy;
    }

    public MarkStatus mark(String world, int cx, int cz, UUID actor) {
        if (!worldAccessPolicy.isAllowed(world, WorldAccessPolicy.Scope.MANUAL_MARK)) {
            return MarkStatus.WORLD_NOT_ALLOWED;
        }
        return switch (markRegistry.mark(world, cx, cz, actor)) {
            case SUCCESS -> MarkStatus.SUCCESS;
            case ALREADY_MARKED -> MarkStatus.ALREADY_MARKED;
            case RESIDENCE_BLOCKED -> MarkStatus.CLAIM_BLOCKED;
        };
    }

    public boolean unmark(String world, int cx, int cz, UUID actor) {
        return markRegistry.unmark(world, cx, cz, actor);
    }

    public boolean toggleFollowMode(UUID player, FollowMode mode) {
        return markRegistry.toggleFollowMode(player, mode);
    }

    public Optional<FollowMode> followMode(UUID player) {
        return markRegistry.getFollowMode(player);
    }

    public void clearFollowMode(UUID player) {
        markRegistry.clearFollowMode(player);
    }

    public boolean isMarked(String world, int cx, int cz) {
        return markRegistry.isMarked(world, cx, cz);
    }

    public int unmarkArea(String world, int minCx, int maxCx, int minCz, int maxCz) {
        return markRegistry.unmarkResidenceArea(world, minCx, maxCx, minCz, maxCz);
    }

    public MarkRegistry.ResetResult resetWorld(String world) {
        return markRegistry.resetMarkForWorld(world);
    }

    public List<MarkedChunk> independentMarksNewestFirst() {
        return markRegistry.getMarkedChunks().stream()
            .filter(chunk -> chunk.structureGroupId() == null)
            .sorted(Comparator.comparingLong(MarkedChunk::markedAt).reversed())
            .toList();
    }
}
