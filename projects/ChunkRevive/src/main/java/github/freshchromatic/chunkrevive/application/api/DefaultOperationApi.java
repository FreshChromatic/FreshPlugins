package github.freshchromatic.chunkrevive.application.api;

import github.freshchromatic.chunkrevive.api.model.RequestContext;
import github.freshchromatic.chunkrevive.api.operation.*;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

final class DefaultOperationApi implements OperationApi {
    private final Plugin consumer;
    private final OperationCoordinator coordinator;

    DefaultOperationApi(Plugin consumer, OperationCoordinator coordinator) {
        this.consumer = consumer;
        this.coordinator = coordinator;
    }

    @Override public CompletionStage<OperationPreview> preview(MaintenanceRequest request, RequestContext context) {
        return global(() -> coordinator.preview(consumer, request, context))
            .thenCompose(stage -> stage);
    }

    @Override public CompletionStage<OperationHandle> submit(PreviewToken token, String idempotencyKey) {
        return global(() -> coordinator.submit(consumer, token, idempotencyKey))
            .thenCompose(stage -> stage);
    }
    @Override public Optional<OperationSnapshot> find(OperationId id) { return coordinator.find(id); }
    @Override public OperationPage list(OperationQuery query) { return coordinator.list(query); }
    @Override public CompletionStage<CancelResult> cancel(OperationId id) { return global(() -> coordinator.cancel(id)); }
    private <T> CompletableFuture<T> global(java.util.function.Supplier<T> task) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Bukkit.getGlobalRegionScheduler().execute(consumer, () -> { try { result.complete(task.get()); } catch (Throwable failure) { result.completeExceptionally(failure); } });
        return result;
    }
}
