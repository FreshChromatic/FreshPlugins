package github.freshchromatic.chunkrevive.feature.reset;

final class DeletionFailurePolicy {

    private DeletionFailurePolicy() {
    }

    static void rethrowFatalFailure(Throwable failure) {
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof Error error) {
                throw error;
            }
            cause = cause.getCause();
        }
    }
}
