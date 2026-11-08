package github.freshchromatic.chunkrevive.api.structure;

import java.util.Optional;
import github.freshchromatic.chunkrevive.api.model.ApiError;
import java.util.UUID;
import java.util.Objects;

public record StructureChangeResult(UUID groupId, StructureChangeStatus status, Optional<ApiError> error) {

    public StructureChangeResult {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(status, "status");
        error = error == null ? Optional.empty() : error;
    }
}
