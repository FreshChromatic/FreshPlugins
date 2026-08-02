package github.freshchromatic.chunkrevive.api.mark;
import github.freshchromatic.chunkrevive.api.model.ApiError;
import github.freshchromatic.chunkrevive.api.model.ChunkKey;
import java.util.Optional;
public record MarkChangeResult(ChunkKey chunk, MarkChangeStatus status, Optional<ApiError> error) { }
