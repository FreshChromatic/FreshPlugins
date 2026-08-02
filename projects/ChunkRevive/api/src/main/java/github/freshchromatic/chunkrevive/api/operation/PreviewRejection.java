package github.freshchromatic.chunkrevive.api.operation;
import github.freshchromatic.chunkrevive.api.model.ChunkKey;
import java.util.Optional;
public record PreviewRejection(Optional<ChunkKey> chunk, String reasonCode, Optional<String> providerId) { }
