package github.freshchromatic.chunkrevive.nms;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

public final class TerrainGenerationFailure {

    private TerrainGenerationFailure() {
    }

    public static RuntimeException asUnchecked(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
            && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return cause instanceof RuntimeException runtime ? runtime : new RuntimeException(cause);
    }
}
