package github.freshchromatic.chunkrevive.feature.regeneration;

/** Signals that a chunk regen was intentionally skipped (e.g. Residence protection). */
public class SkipChunkException extends RuntimeException {
    SkipChunkException(String reason) {
        super(reason, null, true, false);
    }
}
