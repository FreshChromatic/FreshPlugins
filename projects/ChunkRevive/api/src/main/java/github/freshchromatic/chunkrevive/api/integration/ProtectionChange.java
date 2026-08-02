package github.freshchromatic.chunkrevive.api.integration;
import java.util.Optional;
public record ProtectionChange(ProtectionChangeType type, Optional<ChunkArea> area) { }
