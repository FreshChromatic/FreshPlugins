package github.freshchromatic.chunkrevive.nms;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

public final class ContextChunkReadPolicy {

    private ContextChunkReadPolicy() {
    }

    public static CompletableFuture<Void> continueAfterNonFatalReadFailure(CompletableFuture<Void> read) {
        return read.exceptionallyCompose(failure -> {
            Throwable cause = failure;
            while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof Error error) {
                return CompletableFuture.failedFuture(error);
            }
            return CompletableFuture.completedFuture(null);
        });
    }
}
