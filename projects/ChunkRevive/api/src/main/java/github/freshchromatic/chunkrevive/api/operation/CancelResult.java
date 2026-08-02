package github.freshchromatic.chunkrevive.api.operation;
public record CancelResult(OperationId id, boolean cancelled, String reasonCode) { }
