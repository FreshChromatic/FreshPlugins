package github.freshchromatic.chunkrevive.nms;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TerrainGenerationFailureTest {

    @Test
    void rethrowsFatalErrorWithoutWrapping() {
        var fatal = new AssertionError("fatal terrain failure");

        assertSame(fatal, assertThrows(AssertionError.class, () -> TerrainGenerationFailure.asUnchecked(
            new CompletionException(fatal))));
    }

    @Test
    void returnsRuntimeFailureWithoutWrapping() {
        var failure = new IllegalStateException("terrain failed");

        assertSame(failure, TerrainGenerationFailure.asUnchecked(new CompletionException(failure)));
    }

    @Test
    void unwrapsNestedExecutionFailures() {
        var failure = new IOException("terrain I/O failed");

        RuntimeException wrapped = TerrainGenerationFailure.asUnchecked(new CompletionException(new ExecutionException(
            failure)));

        assertSame(failure, wrapped.getCause());
    }
}
