package github.freshchromatic.chunkrevive.nms;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContextChunkReadPolicyTest {

    @Test
    void fallsBackAfterOrdinaryReadFailure() {
        var read = CompletableFuture.<Void>failedFuture(new IOException("chunk read failed"));

        assertNull(ContextChunkReadPolicy.continueAfterNonFatalReadFailure(read).join());
    }

    @Test
    void propagatesFatalReadErrors() {
        AssertionError fatal = new AssertionError("fatal read failure");
        var read = CompletableFuture.<Void>failedFuture(fatal);

        CompletionException failure = assertThrows(CompletionException.class,
            () -> ContextChunkReadPolicy.continueAfterNonFatalReadFailure(read).join());

        assertSame(fatal, failure.getCause());
    }
}
