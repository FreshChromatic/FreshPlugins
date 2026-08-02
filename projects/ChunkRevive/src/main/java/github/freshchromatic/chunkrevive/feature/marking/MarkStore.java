package github.freshchromatic.chunkrevive.feature.marking;

import java.util.Collection;
import java.util.List;

/** Persistence port for marked chunks. */
public interface MarkStore {
    boolean mark(MarkedChunk chunk);
    void markBatch(Collection<MarkedChunk> chunks);
    boolean unmark(String world, int cx, int cz);
    void unmarkBatch(Collection<MarkedChunk> chunks);
    List<MarkedChunk> loadAll();
    void deleteAllForWorld(String world);
}
