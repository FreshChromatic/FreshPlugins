package github.freshchromatic.chunkrevive.api.integration;

import java.util.Optional;
import java.util.Objects;

public record ProtectionChange(ProtectionChangeType type, Optional<ChunkArea> area) {

    public ProtectionChange {
        Objects.requireNonNull(type, "type");
        area = area == null ? Optional.empty() : area;
    }
}
