package github.freshchromatic.chunkrevive.infrastructure.persistence;

import github.freshchromatic.freshlib.database.Database;

final class PersistenceConnectionLock {

    private boolean closed;
    private boolean closeSucceeded;

    synchronized void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Persistence database is closed");
        }
    }

    synchronized boolean close(Database database) {
        if (closed) {
            return closeSucceeded;
        }
        closed = true;
        closeSucceeded = database.close();
        return closeSucceeded;
    }
}
