package github.freshchromatic.chunkrevive.nms;

import java.io.IOException;
import java.util.concurrent.CompletionException;

public final class RegionIoFailure {

    private RegionIoFailure() {
    }

    public static IOException toIOException(CompletionException failure) {
        Throwable cause = failure.getCause();
        if (cause instanceof Error error) {
            throw error;
        }
        if (cause instanceof IOException io) {
            return io;
        }
        return new IOException("Moonrise region I/O scan failed", cause);
    }
}
