package github.freshchromatic.chunkrevive.api.operation;

import github.freshchromatic.chunkrevive.api.model.ApiError;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record OperationSnapshot(OperationId id, OperationType type, OperationState state, int completed, int total,
    Instant createdAt, Instant updatedAt, Optional<String> waitingReasonCode, Optional<ApiError> failure,
    Optional<String> correlationId, List<UUID> deletionJobIds, OperationPersistenceStatus persistenceStatus) {

    public OperationSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        waitingReasonCode = waitingReasonCode == null ? Optional.empty() : waitingReasonCode;
        failure = failure == null ? Optional.empty() : failure;
        correlationId = correlationId == null ? Optional.empty() : correlationId;
        deletionJobIds = List.copyOf(deletionJobIds);
        Objects.requireNonNull(persistenceStatus, "persistenceStatus");
        if (completed < 0 || total < 0 || completed > total) {
            throw new IllegalArgumentException("completed must be between zero and total");
        }
    }

    public OperationSnapshot(OperationId id, OperationType type, OperationState state, int completed, int total,
        Instant createdAt, Instant updatedAt, Optional<String> waitingReasonCode, Optional<ApiError> failure,
        Optional<String> correlationId) {
        this(id, type, state, completed, total, createdAt, updatedAt, waitingReasonCode, failure, correlationId,
            List.of(), OperationPersistenceStatus.UNKNOWN);
    }

    public OperationSnapshot(OperationId id, OperationType type, OperationState state, int completed, int total,
        Instant createdAt, Instant updatedAt, Optional<String> waitingReasonCode, Optional<ApiError> failure,
        Optional<String> correlationId, List<UUID> deletionJobIds) {
        this(id, type, state, completed, total, createdAt, updatedAt, waitingReasonCode, failure, correlationId,
            deletionJobIds, OperationPersistenceStatus.UNKNOWN);
    }

    public int percent() {
        return total == 0 ? 0 : (int) ((long) completed * 100 / total);
    }
}
