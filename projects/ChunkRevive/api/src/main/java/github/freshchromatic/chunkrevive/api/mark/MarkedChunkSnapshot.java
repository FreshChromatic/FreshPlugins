package github.freshchromatic.chunkrevive.api.mark;
import github.freshchromatic.chunkrevive.api.model.ChunkKey;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
public record MarkedChunkSnapshot(ChunkKey chunk, Optional<UUID> markedBy, Instant markedAt,
                                  Optional<UUID> structureGroupId, MarkKind kind) { }
