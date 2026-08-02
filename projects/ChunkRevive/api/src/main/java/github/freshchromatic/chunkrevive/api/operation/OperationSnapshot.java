package github.freshchromatic.chunkrevive.api.operation;
import github.freshchromatic.chunkrevive.api.model.ApiError;
import java.time.Instant;
import java.util.Optional;
public record OperationSnapshot(OperationId id, OperationType type, OperationState state, int completed, int total,
    Instant createdAt, Instant updatedAt, Optional<String> waitingReasonCode, Optional<ApiError> failure,
    Optional<String> correlationId) { public int percent() { return total == 0 ? 0 : (int) ((long) completed * 100 / total); } }
