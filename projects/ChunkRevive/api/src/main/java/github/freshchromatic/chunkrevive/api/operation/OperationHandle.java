package github.freshchromatic.chunkrevive.api.operation;

import java.util.Objects;

public record OperationHandle(OperationId id) {

    public OperationHandle {
        Objects.requireNonNull(id, "id");
    }
}
