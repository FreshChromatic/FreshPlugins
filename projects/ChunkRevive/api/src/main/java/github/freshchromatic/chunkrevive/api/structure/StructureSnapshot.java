package github.freshchromatic.chunkrevive.api.structure;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
public record StructureSnapshot(UUID groupId, String world, String structureId, int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ, Instant detectedAt, Optional<Instant> nextRefreshAt, boolean blocked) { }
