package github.freshchromatic.chunkrevive.api.operation;
import github.freshchromatic.chunkrevive.api.model.ApiError;
import github.freshchromatic.chunkrevive.api.model.RequestContext;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
public interface OperationApi {
    CompletionStage<OperationPreview> preview(MaintenanceRequest request, RequestContext context);
    CompletionStage<OperationHandle> submit(PreviewToken previewToken, String idempotencyKey);
    Optional<OperationSnapshot> find(OperationId id);
    OperationPage list(OperationQuery query);
    CompletionStage<CancelResult> cancel(OperationId id);
}
