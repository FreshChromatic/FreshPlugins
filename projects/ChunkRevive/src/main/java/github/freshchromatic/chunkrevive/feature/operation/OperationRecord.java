package github.freshchromatic.chunkrevive.feature.operation;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record OperationRecord(UUID id, String type, String state, int completed, int total, long createdAt,
    long updatedAt, String sourcePlugin, String correlationId, String failureCode, String failureMessage,
    Map<String, String> failureDetails, List<UUID> deletionJobIds) {

    public OperationRecord {
        failureDetails = Map.copyOf(failureDetails);
        deletionJobIds = List.copyOf(deletionJobIds);
    }

    public OperationRecord(UUID id, String type, String state, int completed, int total, long createdAt, long updatedAt,
        String sourcePlugin, String correlationId, String failureCode, List<UUID> deletionJobIds) {
        this(id, type, state, completed, total, createdAt, updatedAt, sourcePlugin, correlationId, failureCode, null,
            Map.of(), deletionJobIds);
    }

    public OperationRecord(UUID id, String type, String state, int completed, int total, long createdAt, long updatedAt,
        String sourcePlugin, String correlationId, String failureCode) {
        this(id, type, state, completed, total, createdAt, updatedAt, sourcePlugin, correlationId, failureCode, null,
            Map.of(), List.of());
    }
}
