package github.freshchromatic.chunkrevive.application.api;

import github.freshchromatic.chunkrevive.api.model.ApiError;
import github.freshchromatic.chunkrevive.api.operation.OperationId;
import github.freshchromatic.chunkrevive.api.operation.OperationPersistenceStatus;
import github.freshchromatic.chunkrevive.api.operation.OperationSnapshot;
import github.freshchromatic.chunkrevive.api.operation.OperationState;
import github.freshchromatic.chunkrevive.api.operation.OperationType;
import github.freshchromatic.chunkrevive.feature.operation.OperationRecord;
import github.freshchromatic.chunkrevive.feature.operation.OperationStore;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

final class OperationHistory {

    private static final String RESTART_FAILURE_CODE = "OPERATION_INTERRUPTED_BY_RESTART";

    private final Map<UUID, OperationSnapshot> snapshots;

    OperationHistory(OperationStore operationStore) {
        Map<UUID, OperationSnapshot> loaded = new HashMap<>();
        for (OperationRecord storedRecord : operationStore.loadOperations()) {
            OperationRecord record = recoverIfInterrupted(storedRecord, operationStore);
            loaded.put(record.id(), snapshot(record));
        }
        this.snapshots = Map.copyOf(loaded);
    }

    Optional<OperationSnapshot> find(UUID id) {
        return Optional.ofNullable(snapshots.get(id));
    }

    boolean contains(UUID id) {
        return snapshots.containsKey(id);
    }

    List<OperationSnapshot> snapshots() {
        return List.copyOf(snapshots.values());
    }

    private static OperationRecord recoverIfInterrupted(OperationRecord record, OperationStore operationStore) {
        OperationState state = OperationState.valueOf(record.state());
        if (state == OperationState.SUCCEEDED || state == OperationState.PARTIALLY_SUCCEEDED
            || state == OperationState.FAILED || state == OperationState.CANCELLED) {
            return record;
        }
        OperationRecord interrupted = new OperationRecord(record.id(), record.type(), OperationState.FAILED.name(),
            record.completed(), record.total(), record.createdAt(), System.currentTimeMillis(), record.sourcePlugin(),
            record.correlationId(), RESTART_FAILURE_CODE, "Operation was interrupted by server restart.", Map.of(),
            record.deletionJobIds());
        operationStore.update(interrupted);
        return interrupted;
    }

    private static OperationSnapshot snapshot(OperationRecord record) {
        return new OperationSnapshot(new OperationId(record.id()), OperationType.valueOf(record.type()),
            OperationState.valueOf(record.state()), record.completed(), record.total(), Instant.ofEpochMilli(
                record.createdAt()), Instant.ofEpochMilli(record.updatedAt()), Optional.empty(), Optional.ofNullable(
                    record.failureCode()).map(code -> new ApiError(code, Objects.requireNonNullElse(
                        record.failureMessage(), code), record.failureDetails())), Optional.ofNullable(
                            record.correlationId()), record.deletionJobIds(), OperationPersistenceStatus.PERSISTED);
    }
}
