package github.freshchromatic.chunkrevive.api.structure;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.Objects;

public record StructureSnapshot(UUID groupId, String world, String structureId, int minChunkX, int maxChunkX,
    int minChunkZ, int maxChunkZ, Instant detectedAt, Optional<Instant> nextRefreshAt, boolean blocked) {

    public StructureSnapshot {
        Objects.requireNonNull(groupId, "groupId");
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("world must not be blank");
        }
        if (structureId == null || structureId.isBlank()) {
            throw new IllegalArgumentException("structureId must not be blank");
        }
        if (minChunkX > maxChunkX || minChunkZ > maxChunkZ) {
            throw new IllegalArgumentException("minimum chunk coordinates must not exceed maximum coordinates");
        }
        Objects.requireNonNull(detectedAt, "detectedAt");
        nextRefreshAt = nextRefreshAt == null ? Optional.empty() : nextRefreshAt;
    }
}
