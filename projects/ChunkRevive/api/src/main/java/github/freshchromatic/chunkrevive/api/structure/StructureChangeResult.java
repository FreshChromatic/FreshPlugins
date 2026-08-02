package github.freshchromatic.chunkrevive.api.structure;
import java.util.Optional;
import github.freshchromatic.chunkrevive.api.model.ApiError;
import java.util.UUID;
public record StructureChangeResult(UUID groupId, StructureChangeStatus status, Optional<ApiError> error) { }
