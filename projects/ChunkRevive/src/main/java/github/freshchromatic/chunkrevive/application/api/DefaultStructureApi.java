package github.freshchromatic.chunkrevive.application.api;

import github.freshchromatic.chunkrevive.api.model.ChunkKey;
import github.freshchromatic.chunkrevive.api.model.ApiError;
import github.freshchromatic.chunkrevive.api.model.RequestContext;
import github.freshchromatic.chunkrevive.api.operation.ExplicitChunks;
import github.freshchromatic.chunkrevive.api.operation.MaintenanceRequest;
import github.freshchromatic.chunkrevive.api.operation.OperationHandle;
import github.freshchromatic.chunkrevive.api.operation.PreviewToken;
import github.freshchromatic.chunkrevive.api.structure.StructureApi;
import github.freshchromatic.chunkrevive.api.structure.StructureChangeResult;
import github.freshchromatic.chunkrevive.api.structure.StructureChangeStatus;
import github.freshchromatic.chunkrevive.api.structure.StructurePage;
import github.freshchromatic.chunkrevive.api.structure.StructureQuery;
import github.freshchromatic.chunkrevive.api.structure.StructureSnapshot;
import github.freshchromatic.chunkrevive.feature.marking.MarkRegistry;
import github.freshchromatic.chunkrevive.feature.structure.StructureGroup;
import github.freshchromatic.chunkrevive.feature.structure.StructureService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

final class DefaultStructureApi implements StructureApi {

    private final Plugin               consumer;
    private final Plugin               plugin;
    private final StructureService     structures;
    private final MarkRegistry         marks;
    private final OperationCoordinator operations;

    DefaultStructureApi(Plugin plugin, Plugin consumer, StructureService structures, MarkRegistry marks,
        OperationCoordinator operations) {
        this.plugin = plugin;
        this.consumer = consumer;
        this.structures = structures;
        this.marks = marks;
        this.operations = operations;
    }

    @Override
    public Optional<StructureSnapshot> find(UUID id) {
        return structures.get(id).map(this::snapshot);
    }

    @Override
    public List<StructureSnapshot> findAt(ChunkKey chunk) {
        return marks.getMarkedChunks()
            .stream()
            .filter(mark -> mark.world().equals(chunk.world())
            && mark.cx() == chunk.x() && mark.cz() == chunk.z() && mark.structureGroupId() != null)
            .map(mark -> structures.get(mark.structureGroupId()))
            .flatMap(Optional::stream)
            .distinct()
            .map(this::snapshot)
            .toList();
    }

    @Override
    public StructurePage list(StructureQuery query) {
        List<StructureSnapshot> all = structures.groupsByNextRefresh()
            .stream()
            .filter(group ->
                query.world()
                    .map(group.world()::equals)
                    .orElse(true))
            .filter(group ->
                query.blocked()
                    .map(value -> value == group.blocked())
                    .orElse(true))
            .map(this::snapshot)
            .toList();
        int from = Math.min(query.offset(), all.size());
        int to = Math.min(from + query.limit(), all.size());
        return new StructurePage(all.size(), all.subList(from, to));
    }

    @Override
    public CompletionStage<StructureChangeResult> block(UUID id, RequestContext context) {
        return global(() -> change(id, true)).thenCompose(stage -> stage);
    }

    @Override
    public CompletionStage<StructureChangeResult> unblock(UUID id, RequestContext context) {
        return global(() -> change(id, false)).thenCompose(stage -> stage);
    }

    @Override
    public CompletionStage<OperationHandle> regenerate(UUID id, RequestContext context) {
        return global(() -> {
            List<ChunkKey> targets = marks.getMarkedChunks()
                .stream()
                .filter(mark -> id.equals(mark.structureGroupId()))
                .map(mark -> new ChunkKey(mark.world(), mark.cx(), mark.cz()))
                .toList();
            return operations.preview(consumer, MaintenanceRequest.regenerate(new ExplicitChunks(targets)), context);
        })
            .thenCompose(stage -> stage)
            .thenCompose(preview -> {
                if (!preview.executable()) {
                    return CompletableFuture.failedFuture(new IllegalStateException("STRUCTURE_REGENERATION_REJECTED"));
                }
                return global(() -> operations.submit(consumer, preview.token(), "structure:" + id + ":"
                    + UUID.randomUUID())).thenCompose(stage -> stage);
            });
    }

    private CompletableFuture<StructureChangeResult> change(UUID id, boolean block) {
        Optional<StructureGroup> before = structures.get(id);
        if (before.isEmpty()) {
            return CompletableFuture.completedFuture(result(id, StructureChangeStatus.NOT_FOUND));
        }
        if (before.get().blocked() == block) {
            return CompletableFuture.completedFuture(result(id, block ? StructureChangeStatus.ALREADY_BLOCKED
                : StructureChangeStatus.ALREADY_UNBLOCKED));
        }
        CompletableFuture<Boolean> mutation = block ? structures.block(id) : structures.unblock(id);
        return mutation.thenApply(changed -> {
            if (!changed) {
                return result(id, StructureChangeStatus.NOT_FOUND);
            }
            return result(id, block ? StructureChangeStatus.BLOCKED : StructureChangeStatus.UNBLOCKED);
        }).exceptionallyCompose(failure -> {
            Throwable cause = failure;
            while (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof Error error) {
                return CompletableFuture.failedFuture(error);
            }
            return CompletableFuture.completedFuture(new StructureChangeResult(id,
                StructureChangeStatus.PERSISTENCE_FAILED, Optional.of(new ApiError("STRUCTURE_PERSISTENCE_FAILED",
                    "Failed to persist structure protection state", Map.of()))));
        });
    }

    private StructureSnapshot snapshot(StructureGroup group) {
        return new StructureSnapshot(group.groupId(), group.world(), group.structureId(), group.minChunkX(),
            group.maxChunkX(), group.minChunkZ(), group.maxChunkZ(), Instant.ofEpochMilli(group.detectedAt()),
            group.nextRefreshAt() <= 0 ? Optional.empty() : Optional.of(Instant.ofEpochMilli(group.nextRefreshAt())),
            group.blocked());
    }

    private static StructureChangeResult result(UUID id, StructureChangeStatus status) {
        return new StructureChangeResult(id, status, Optional.empty());
    }

    private <T> CompletableFuture<T> global(Supplier<T> task) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                try {
                    result.complete(task.get());
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }
}
