package github.freshchromatic.chunkrevive.api.operation;

import java.util.Optional;

public record OperationQuery(Optional<OperationType> type, Optional<OperationState> state, int offset, int limit) {
    public OperationQuery {
        type = type == null ? Optional.empty() : type;
        state = state == null ? Optional.empty() : state;
        offset = Math.max(0, offset);
        limit = Math.clamp(limit, 1, 200);
    }
    public static OperationQuery recent() { return new OperationQuery(Optional.empty(), Optional.empty(), 0, 50); }
}
