package github.freshchromatic.chunkrevive.application.api;

import github.freshchromatic.chunkrevive.api.model.ChunkKey;
import github.freshchromatic.chunkrevive.api.model.RequestContext;
import github.freshchromatic.chunkrevive.api.operation.*;
import github.freshchromatic.chunkrevive.api.structure.*;
import github.freshchromatic.chunkrevive.feature.marking.MarkRegistry;
import github.freshchromatic.chunkrevive.feature.structure.StructureGroup;
import github.freshchromatic.chunkrevive.feature.structure.StructureService;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

final class DefaultStructureApi implements StructureApi {
    private final Plugin consumer;
    private final Plugin plugin;
    private final StructureService structures;
    private final MarkRegistry marks;
    private final OperationCoordinator operations;

    DefaultStructureApi(Plugin plugin, Plugin consumer, StructureService structures,
                        MarkRegistry marks, OperationCoordinator operations) {
        this.plugin = plugin;
        this.consumer = consumer;
        this.structures = structures;
        this.marks = marks;
        this.operations = operations;
    }

    @Override public Optional<StructureSnapshot> find(UUID id) {
        return structures.get(id).map(this::snapshot);
    }

    @Override public List<StructureSnapshot> findAt(ChunkKey chunk) {
        return marks.getMarkedChunks().stream()
            .filter(mark -> mark.world().equals(chunk.world())
                && mark.cx() == chunk.x()
                && mark.cz() == chunk.z()
                && mark.structureGroupId() != null)
            .map(mark -> structures.get(mark.structureGroupId()))
            .flatMap(Optional::stream)
            .distinct()
            .map(this::snapshot)
            .toList();
    }
    @Override public StructurePage list(StructureQuery query) {
        List<StructureSnapshot> all = structures.groupsByNextRefresh().stream()
            .filter(group -> query.world().map(group.world()::equals).orElse(true))
            .filter(group -> query.blocked().map(value -> value == group.blocked()).orElse(true))
            .map(this::snapshot)
            .toList();
        int from = Math.min(query.offset(), all.size());
        int to = Math.min(from + query.limit(), all.size());
        return new StructurePage(all.size(), all.subList(from, to));
    }

    @Override public CompletionStage<StructureChangeResult> block(UUID id, RequestContext context) {
        return global(() -> change(id, true));
    }

    @Override public CompletionStage<StructureChangeResult> unblock(UUID id, RequestContext context) {
        return global(() -> change(id, false));
    }

    @Override public CompletionStage<OperationHandle> regenerate(UUID id, RequestContext context) {
        return global(() -> {
            List<ChunkKey> targets = marks.getMarkedChunks().stream()
                .filter(mark -> id.equals(mark.structureGroupId()))
                .map(mark -> new ChunkKey(mark.world(), mark.cx(), mark.cz()))
                .toList();
            return operations.preview(consumer,
                MaintenanceRequest.regenerate(new ExplicitChunks(targets)), context);
        }).thenCompose(stage -> stage).thenCompose(preview -> {
            if (!preview.executable()) {
                return CompletableFuture.failedFuture(
                    new IllegalStateException("STRUCTURE_REGENERATION_REJECTED"));
            }
            return global(() -> operations.submit(
                consumer, preview.token(), "structure:" + id + ":" + UUID.randomUUID()))
                .thenCompose(stage -> stage);
        });
    }

    private StructureChangeResult change(UUID id, boolean block) {
        Optional<StructureGroup> before = structures.get(id);
        if (before.isEmpty()) return result(id, StructureChangeStatus.NOT_FOUND);
        if (before.get().blocked() == block) {
            return result(id, block ? StructureChangeStatus.ALREADY_BLOCKED : StructureChangeStatus.ALREADY_UNBLOCKED);
        }
        if (block) structures.block(id); else structures.unblock(id);
        return result(id, block ? StructureChangeStatus.BLOCKED : StructureChangeStatus.UNBLOCKED);
    }
    private StructureSnapshot snapshot(StructureGroup group) { return new StructureSnapshot(group.groupId(),group.world(),group.structureId(),group.minChunkX(),group.maxChunkX(),group.minChunkZ(),group.maxChunkZ(),Instant.ofEpochMilli(group.detectedAt()),group.nextRefreshAt()<=0?Optional.empty():Optional.of(Instant.ofEpochMilli(group.nextRefreshAt())),group.blocked()); }
    private static StructureChangeResult result(UUID id, StructureChangeStatus status) {
        return new StructureChangeResult(id, status, Optional.empty());
    }

    private <T> CompletableFuture<T> global(java.util.function.Supplier<T> task) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            try {
                result.complete(task.get());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }
}
