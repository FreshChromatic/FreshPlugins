package github.freshchromatic.chunkrevive.api.mark;

import github.freshchromatic.chunkrevive.api.model.ApiError;
import github.freshchromatic.chunkrevive.api.model.ChunkKey;
import java.util.Optional;
import java.util.Objects;

public record MarkChangeResult(ChunkKey chunk, MarkChangeStatus status, Optional<ApiError> error) {

    public MarkChangeResult {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(status, "status");
        error = error == null ? Optional.empty() : error;
    }
}
