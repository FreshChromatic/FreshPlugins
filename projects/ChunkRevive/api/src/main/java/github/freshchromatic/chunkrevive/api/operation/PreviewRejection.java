package github.freshchromatic.chunkrevive.api.operation;

import github.freshchromatic.chunkrevive.api.model.ChunkKey;
import java.util.Optional;

public record PreviewRejection(Optional<ChunkKey> chunk, String reasonCode, Optional<String> providerId) {

    public PreviewRejection {
        chunk = chunk == null ? Optional.empty() : chunk;
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException("reasonCode must not be blank");
        }
        providerId = providerId == null ? Optional.empty() : providerId;
        providerId.ifPresent(value -> {
            if (value.isBlank()) {
                throw new IllegalArgumentException("providerId must not be blank");
            }
        });
    }
}
