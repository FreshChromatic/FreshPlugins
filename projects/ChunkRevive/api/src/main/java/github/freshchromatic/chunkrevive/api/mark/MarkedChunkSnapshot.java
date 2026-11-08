package github.freshchromatic.chunkrevive.api.mark;

import github.freshchromatic.chunkrevive.api.model.ChunkKey;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.Objects;

public record MarkedChunkSnapshot(ChunkKey chunk, Optional<UUID> markedBy, Instant markedAt,
    Optional<UUID> structureGroupId, MarkKind kind) {

    public MarkedChunkSnapshot {
        Objects.requireNonNull(chunk, "chunk");
        markedBy = markedBy == null ? Optional.empty() : markedBy;
        Objects.requireNonNull(markedAt, "markedAt");
        structureGroupId = structureGroupId == null ? Optional.empty() : structureGroupId;
        Objects.requireNonNull(kind, "kind");
    }
}
