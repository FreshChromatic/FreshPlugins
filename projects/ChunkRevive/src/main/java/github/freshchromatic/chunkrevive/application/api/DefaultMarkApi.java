package github.freshchromatic.chunkrevive.application.api;

import github.freshchromatic.chunkrevive.api.mark.*;
import github.freshchromatic.chunkrevive.api.event.ChunkMarkAddedEvent;
import github.freshchromatic.chunkrevive.api.event.ChunkMarkRemovedEvent;
import github.freshchromatic.chunkrevive.api.model.ApiError;
import github.freshchromatic.chunkrevive.api.model.ChunkKey;
import github.freshchromatic.chunkrevive.api.model.RequestContext;
import github.freshchromatic.chunkrevive.config.WorldAccessPolicy;
import github.freshchromatic.chunkrevive.feature.marking.MarkRegistry;
import github.freshchromatic.chunkrevive.feature.marking.MarkResult;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

final class DefaultMarkApi implements MarkApi {
    private static final UUID SYSTEM_MARKER = new UUID(0L, 0L);
    private final Plugin plugin;
    private final Plugin consumer;
    private final MarkRegistry registry;
    private final WorldAccessPolicy worlds;

    DefaultMarkApi(Plugin plugin, Plugin consumer, MarkRegistry registry, WorldAccessPolicy worlds) {
        this.plugin = plugin; this.consumer = consumer; this.registry = registry; this.worlds = worlds;
    }
    @Override public Optional<MarkedChunkSnapshot> find(ChunkKey key) {
        return registry.getMarkedChunks().stream().filter(c -> c.world().equals(key.world()) && c.cx() == key.x() && c.cz() == key.z())
            .findFirst().map(this::snapshot);
    }
    @Override public boolean isMarked(ChunkKey key) { return registry.isMarked(key.world(), key.x(), key.z()); }
    @Override public CompletionStage<MarkChangeResult> mark(ChunkKey key, RequestContext context) {
        if (Bukkit.getWorld(key.world()) == null) return CompletableFuture.completedFuture(result(key, MarkChangeStatus.WORLD_NOT_FOUND));
        if (!worlds.isAllowed(key.world(), WorldAccessPolicy.Scope.MANUAL_MARK)) return CompletableFuture.completedFuture(result(key, MarkChangeStatus.WORLD_NOT_ALLOWED));
        return onGlobal(() -> registry.markPersisted(key.world(), key.x(), key.z(), context.actor().orElse(SYSTEM_MARKER)))
            .thenCompose(stage -> stage).thenCompose(status -> switch (status) {
                case SUCCESS -> onGlobal(() -> {
                    registry.refreshMarkedDisplay(key.world(), key.x(), key.z());
                    MarkChangeResult result = result(key, MarkChangeStatus.ADDED);
                    find(key).ifPresent(mark -> Bukkit.getPluginManager().callEvent(new ChunkMarkAddedEvent(mark, consumer.getName(), context.actor(), context.correlationId())));
                    return result;
                });
                case ALREADY_MARKED -> CompletableFuture.completedFuture(result(key, MarkChangeStatus.ALREADY_MARKED));
                case RESIDENCE_BLOCKED -> CompletableFuture.completedFuture(new MarkChangeResult(key, MarkChangeStatus.PROTECTION_BLOCKED,
                    Optional.of(new ApiError("PROTECTION_BLOCKED", "The chunk is protected", java.util.Map.of()))));
            });
    }
    @Override public CompletionStage<MarkChangeResult> unmark(ChunkKey key, RequestContext context) {
        return onGlobal(() -> registry.unmarkPersisted(key.world(), key.x(), key.z())).thenCompose(stage -> stage)
            .thenCompose(removed -> removed.<CompletionStage<MarkChangeResult>>map(chunk ->
                onGlobal(() -> {
                    registry.refreshUnmarkedDisplay(chunk);
                    MarkedChunkSnapshot snapshot = snapshot(chunk);
                    Bukkit.getPluginManager().callEvent(new ChunkMarkRemovedEvent(snapshot, consumer.getName(), context.actor(), context.correlationId()));
                    return result(key, MarkChangeStatus.REMOVED);
                })
            ).orElseGet(() -> CompletableFuture.completedFuture(result(key, MarkChangeStatus.NOT_MARKED))));
    }
    @Override public CompletionStage<BulkMarkResult> markAll(Collection<ChunkKey> keys, RequestContext context) {
        return sequential(keys, key -> mark(key, context));
    }
    @Override public CompletionStage<BulkMarkResult> unmarkAll(Collection<ChunkKey> keys, RequestContext context) {
        return sequential(keys, key -> unmark(key, context));
    }
    private CompletionStage<BulkMarkResult> sequential(Collection<ChunkKey> keys, java.util.function.Function<ChunkKey, CompletionStage<MarkChangeResult>> operation) {
        List<MarkChangeResult> results = new ArrayList<>();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (ChunkKey key : List.copyOf(keys)) chain = chain.thenCompose(ignored -> operation.apply(key).thenAccept(results::add));
        return chain.thenApply(ignored -> {
            int changed = (int) results.stream().filter(r -> r.status() == MarkChangeStatus.ADDED || r.status() == MarkChangeStatus.REMOVED).count();
            int rejected = (int) results.stream().filter(r -> r.status() == MarkChangeStatus.WORLD_NOT_ALLOWED || r.status() == MarkChangeStatus.WORLD_NOT_FOUND || r.status() == MarkChangeStatus.PROTECTION_BLOCKED).count();
            return new BulkMarkResult(results.size(), changed, results.size() - changed - rejected, rejected, results);
        });
    }
    private <T> CompletableFuture<T> onGlobal(java.util.function.Supplier<T> task) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> { try { result.complete(task.get()); } catch (Throwable failure) { result.completeExceptionally(failure); } });
        return result;
    }
    private MarkedChunkSnapshot snapshot(MarkedChunk c) {
        return new MarkedChunkSnapshot(new ChunkKey(c.world(), c.cx(), c.cz()), Optional.ofNullable(c.markedBy()), Instant.ofEpochMilli(c.markedAt()),
            Optional.ofNullable(c.structureGroupId()), c.structureGroupId() == null ? MarkKind.MANUAL : MarkKind.STRUCTURE);
    }
    private static MarkChangeResult result(ChunkKey key, MarkChangeStatus status) { return new MarkChangeResult(key, status, Optional.empty()); }
}
