package github.freshchromatic.chunkrevive.api.integration;

import github.freshchromatic.chunkrevive.api.model.ChunkKey;
import java.util.Collection;
import java.util.Objects;

public record ProtectionQuery(MaintenanceAction action, Collection<ChunkKey> chunks) {

    public ProtectionQuery {
        Objects.requireNonNull(action, "action");
        chunks = ListCopy.copy(chunks);
    }
}
