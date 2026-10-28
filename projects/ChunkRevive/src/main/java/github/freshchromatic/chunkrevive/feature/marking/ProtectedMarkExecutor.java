package github.freshchromatic.chunkrevive.feature.marking;

import github.freshchromatic.chunkrevive.api.integration.MaintenanceAction;
import github.freshchromatic.chunkrevive.api.integration.ProtectionBatchResult;
import github.freshchromatic.chunkrevive.api.integration.ProtectionDecision;
import github.freshchromatic.chunkrevive.api.model.ChunkKey;
import github.freshchromatic.chunkrevive.integration.protection.ProtectionChecks;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class ProtectedMarkExecutor {
    public record BatchResult(List<MarkedChunk> eligible, List<MarkedChunk> added) {}

    private final Consumer<Runnable> schedule;
    private final MarkRegistry       registry;
    private final ProtectionChecks   checks;

    public ProtectedMarkExecutor(Plugin plugin, MarkRegistry registry, ProtectionChecks checks) {
        this(task -> Bukkit.getGlobalRegionScheduler().execute(plugin, task), registry, checks);
    }

    ProtectedMarkExecutor(Consumer<Runnable> schedule, MarkRegistry registry, ProtectionChecks checks) {
        this.schedule = schedule;
        this.registry = registry;
        this.checks = checks;
    }

    public CompletableFuture<MarkResult> mark(String world, int x, int z, UUID actor, boolean persisted) {
        var result = new CompletableFuture<MarkResult>();
        runGlobal(result, () -> {
            long revision = checks.revision();
            List<MarkedChunk> candidates = registry.resolveMark(world, x, z, actor);
            if (candidates.isEmpty()) {
                result.complete(MarkResult.RESIDENCE_BLOCKED);
                return;
            }
            ChunkKey startingChunk = new ChunkKey(world, x, z);
            List<ChunkKey> keys = candidates.stream().map(chunk -> new ChunkKey(chunk.world(), chunk.cx(), chunk.cz())).toList();
            var startCheck = checks.check(MaintenanceAction.MARK, List.of(startingChunk)).toCompletableFuture();
            var rangeCheck = checks.checkExternal(MaintenanceAction.MARK, keys).toCompletableFuture();
            startCheck.thenCombine(rangeCheck, (start, range) ->
                denied(start, List.of(startingChunk)) || denied(range, keys)).whenComplete((blocked, failure) -> {
                    if (failure != null) {
                        result.completeExceptionally(failure);
                        return;
                    }
                    runGlobal(result, () -> {
                        if (blocked || revision != checks.revision()) {
                            result.complete(MarkResult.RESIDENCE_BLOCKED);
                            return;
                        }
                        List<MarkedChunk> current = registry.resolveMark(world, x, z, actor);
                        if (!new HashSet<>(current).equals(new HashSet<>(candidates))) {
                            result.complete(MarkResult.RESIDENCE_BLOCKED);
                            return;
                        }
                        if (persisted) {
                            registry.markResolvedPersisted(current).whenComplete((status, error) -> {
                                if (error == null) result.complete(status);
                                else result.completeExceptionally(error);
                            });
                        } else {
                            result.complete(registry.markResolved(current));
                        }
                    });
                });
        });
        return result;
    }

    public CompletableFuture<BatchResult> markBatch(List<MarkedChunk> chunks, boolean batched) {
        var result = new CompletableFuture<BatchResult>();
        List<MarkedChunk> candidates = List.copyOf(chunks);
        runGlobal(result, () -> {
            long revision = checks.revision();
            List<ChunkKey> keys = candidates.stream()
                .map(chunk -> new ChunkKey(chunk.world(), chunk.cx(), chunk.cz())).toList();
            checks.checkExternal(MaintenanceAction.MARK, keys).whenComplete((decision, failure) -> {
                if (failure != null) {
                    result.completeExceptionally(failure);
                    return;
                }
                runGlobal(result, () -> {
                    if (decision == null || revision != checks.revision()) {
                        throw new IllegalStateException("Protection changed during marking");
                    }
                    List<MarkedChunk> eligible = candidates.stream().filter(chunk ->
                        decision.decisions().getOrDefault(new ChunkKey(chunk.world(), chunk.cx(), chunk.cz()),
                            ProtectionDecision.ABSTAIN) != ProtectionDecision.DENY).toList();
                    List<MarkedChunk> added = batched
                        ? registry.markChunksBatch(eligible) : registry.markChunksDirect(eligible);
                    result.complete(new BatchResult(eligible, added));
                });
            });
        });
        return result;
    }

    private static boolean denied(ProtectionBatchResult result, List<ChunkKey> chunks) {
        return result == null || chunks.stream().anyMatch(chunk ->
            result.decisions().getOrDefault(chunk, ProtectionDecision.ABSTAIN) == ProtectionDecision.DENY);
    }

    private void runGlobal(CompletableFuture<?> result, Runnable task) {
        try {
            schedule.accept(() -> {
                try {
                    task.run();
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
    }
}
