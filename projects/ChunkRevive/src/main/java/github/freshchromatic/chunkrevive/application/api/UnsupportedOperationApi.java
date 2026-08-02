package github.freshchromatic.chunkrevive.application.api;

import github.freshchromatic.chunkrevive.api.operation.*;
import github.freshchromatic.chunkrevive.api.model.RequestContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Safe temporary boundary: no destructive action is exposed before the coordinator exists. */
final class UnsupportedOperationApi implements OperationApi {
    @Override public CompletionStage<OperationPreview> preview(MaintenanceRequest request, RequestContext context) {
        return CompletableFuture.completedFuture(new OperationPreview(new PreviewToken(UUID.randomUUID().toString()), Instant.now(), false,
            0, 0, 1, 0, 0, 0, List.of(new PreviewRejection(Optional.empty(), "UNSUPPORTED_OPERATION", Optional.empty()))));
    }
    @Override public CompletionStage<OperationHandle> submit(PreviewToken token, String idempotencyKey) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("UNSUPPORTED_OPERATION"));
    }
    @Override public Optional<OperationSnapshot> find(OperationId id) { return Optional.empty(); }
    @Override public OperationPage list(OperationQuery query) { return new OperationPage(0, List.of()); }
    @Override public CompletionStage<CancelResult> cancel(OperationId id) {
        return CompletableFuture.completedFuture(new CancelResult(id, false, "OPERATION_NOT_FOUND"));
    }
}
