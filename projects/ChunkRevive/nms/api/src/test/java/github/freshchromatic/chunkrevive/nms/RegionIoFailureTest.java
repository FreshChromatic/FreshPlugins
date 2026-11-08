package github.freshchromatic.chunkrevive.nms;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RegionIoFailureTest {

    @Test
    void preservesIoFailures() {
        var ioFailure = new IOException("storage failed");

        assertSame(ioFailure, RegionIoFailure.toIOException(new CompletionException(ioFailure)));
    }

    @Test
    void rethrowsFatalErrors() {
        var fatal = new AssertionError("fatal region I/O failure");

        assertSame(fatal, assertThrows(AssertionError.class, () -> RegionIoFailure.toIOException(
            new CompletionException(fatal))));
    }

    @Test
    void wrapsUnexpectedFailuresAsIoExceptions() {
        var failure = new IllegalStateException("unexpected storage failure");

        IOException wrapped = RegionIoFailure.toIOException(new CompletionException(failure));

        assertEquals("Moonrise region I/O scan failed", wrapped.getMessage());
        assertSame(failure, wrapped.getCause());
    }
}
