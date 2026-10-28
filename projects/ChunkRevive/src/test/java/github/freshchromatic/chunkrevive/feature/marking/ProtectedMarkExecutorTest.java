package github.freshchromatic.chunkrevive.feature.marking;

import github.freshchromatic.chunkrevive.api.integration.MaintenanceAction;
import github.freshchromatic.chunkrevive.api.integration.ProtectionBatchResult;
import github.freshchromatic.chunkrevive.api.integration.ProtectionDecision;
import github.freshchromatic.chunkrevive.api.model.ChunkKey;
import github.freshchromatic.chunkrevive.integration.protection.ProtectionChecks;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.*;

class ProtectedMarkExecutorTest {
    @Test
    void batchWaitsForProviderAndExcludesDeniedChunks() {
        var checks = new PendingChecks();
        var registry = registry();
        var executor = new ProtectedMarkExecutor(Runnable::run, registry, checks);
        var allowed = chunk(1);
        var denied = chunk(2);
        var result = executor.markBatch(List.of(allowed, denied), true);

        assertFalse(result.isDone());
        assertFalse(registry.isMarked("world", 1, 0));
        checks.pending.complete(new ProtectionBatchResult(Map.of(new ChunkKey("world", 2, 0),
            ProtectionDecision.DENY), Map.of()));

        assertEquals(List.of(allowed), result.join().eligible());
        assertTrue(registry.isMarked("world", 1, 0));
        assertFalse(registry.isMarked("world", 2, 0));
    }

    @Test
    void changedProtectionInvalidatesPendingBatch() {
        var checks = new PendingChecks();
        var registry = registry();
        var executor = new ProtectedMarkExecutor(Runnable::run, registry, checks);
        var result = executor.markBatch(List.of(chunk(1)), false);
        checks.revision++;
        checks.pending.complete(new ProtectionBatchResult(Map.of(), Map.of()));

        assertThrows(java.util.concurrent.CompletionException.class, result::join);
        assertFalse(registry.isMarked("world", 1, 0));
    }

    private static MarkedChunk chunk(int x) {
        return new MarkedChunk("world", x, 0, UUID.randomUUID(), 0);
    }

    private static MarkRegistry registry() {
        MarkStore store = new MarkStore() {
            @Override public boolean mark(MarkedChunk chunk) { return true; }
            @Override public void markBatch(Collection<MarkedChunk> chunks) {}
            @Override public boolean unmark(String world, int cx, int cz) { return true; }
            @Override public void unmarkBatch(Collection<MarkedChunk> chunks) {}
            @Override public List<MarkedChunk> loadAll() { return List.of(); }
            @Override public void deleteAllForWorld(String world) {}
        };
        return new MarkRegistry(store, null, null, null, null);
    }

    private static final class PendingChecks implements ProtectionChecks {
        private long revision;
        private final CompletableFuture<ProtectionBatchResult> pending = new CompletableFuture<>();

        @Override public long revision() { return revision; }
        @Override public CompletionStage<ProtectionBatchResult> check(MaintenanceAction action, Collection<ChunkKey> chunks) {
            return pending;
        }
        @Override public CompletionStage<ProtectionBatchResult> checkExternal(MaintenanceAction action, Collection<ChunkKey> chunks) {
            return pending;
        }
    }
}
