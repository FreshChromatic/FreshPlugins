package github.freshchromatic.chunkrevive.feature.operation;

import java.util.UUID;

public record OperationRecord(
    UUID id,
    String type,
    String state,
    int completed,
    int total,
    long createdAt,
    long updatedAt,
    String sourcePlugin,
    String correlationId,
    String failureCode
) { }
