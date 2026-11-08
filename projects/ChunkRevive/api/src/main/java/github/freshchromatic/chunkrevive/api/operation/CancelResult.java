package github.freshchromatic.chunkrevive.api.operation;

import java.util.Objects;

public record CancelResult(OperationId id, boolean cancelled, String reasonCode) {

    public CancelResult {
        Objects.requireNonNull(id, "id");
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException("reasonCode must not be blank");
        }
    }
}
