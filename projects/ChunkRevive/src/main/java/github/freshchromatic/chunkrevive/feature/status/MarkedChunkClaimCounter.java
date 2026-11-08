package github.freshchromatic.chunkrevive.feature.status;

import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import github.freshchromatic.chunkrevive.integration.protection.LandProtection;
import org.bukkit.World;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

public final class MarkedChunkClaimCounter {

    private static final int CHUNKS_PER_TICK = 64;

    private final LandProtection          landProtection;
    private final Executor                nextTick;
    private final Function<String, World> worldLookup;

    public MarkedChunkClaimCounter(LandProtection landProtection, Executor nextTick,
        Function<String, World> worldLookup) {
        this.landProtection = landProtection;
        this.nextTick = nextTick;
        this.worldLookup = worldLookup;
    }

    public CompletableFuture<Long> count(Collection<MarkedChunk> chunks) {
        var snapshot = List.copyOf(chunks);
        var result = new CompletableFuture<Long>();
        schedule(() -> process(snapshot, 0, 0L, result), result);
        return result;
    }

    private void process(List<MarkedChunk> chunks, int start, long claimed, CompletableFuture<Long> result) {
        try {
            int end = Math.min(start + CHUNKS_PER_TICK, chunks.size());
            long count = claimed;
            for (int index = start; index < end; index++) {
                MarkedChunk chunk = chunks.get(index);
                World world = worldLookup.apply(chunk.world());
                if (world != null && landProtection.hasClaim(world, chunk.cx(), chunk.cz())) {
                    count++;
                }
            }
            if (end == chunks.size()) {
                result.complete(count);
                return;
            }
            long countedClaims = count;
            schedule(() -> process(chunks, end, countedClaims, result), result);
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
        }
    }

    private void schedule(Runnable task, CompletableFuture<Long> result) {
        try {
            nextTick.execute(task);
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
        }
    }
}
