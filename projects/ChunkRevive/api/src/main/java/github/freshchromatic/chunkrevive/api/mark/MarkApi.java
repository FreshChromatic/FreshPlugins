package github.freshchromatic.chunkrevive.api.mark;

import github.freshchromatic.chunkrevive.api.model.ChunkKey;
import github.freshchromatic.chunkrevive.api.model.RequestContext;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

public interface MarkApi {
    Optional<MarkedChunkSnapshot> find(ChunkKey chunk);
    boolean isMarked(ChunkKey chunk);
    CompletionStage<MarkChangeResult> mark(ChunkKey chunk, RequestContext context);
    CompletionStage<MarkChangeResult> unmark(ChunkKey chunk, RequestContext context);
    CompletionStage<BulkMarkResult> markAll(Collection<ChunkKey> chunks, RequestContext context);
    CompletionStage<BulkMarkResult> unmarkAll(Collection<ChunkKey> chunks, RequestContext context);
}
