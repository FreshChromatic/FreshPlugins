package github.freshchromatic.chunkrevive.feature.marking;

/** Presentation port notified when visible mark state changes. */
public interface MarkDisplay {
    void onChunkMarked(MarkedChunk chunk);
    void onChunkUnmarked(MarkedChunk chunk);
}
