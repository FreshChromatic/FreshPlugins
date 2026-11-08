package github.freshchromatic.chunkrevive.infrastructure.persistence;

import github.freshchromatic.chunkrevive.feature.operation.OperationRecord;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import github.freshchromatic.chunkrevive.feature.reset.DeletionJob;
import github.freshchromatic.freshlib.database.SqliteDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationPersistenceTest {

    @Test
    void operationWriteWaitsForAnotherStoreTransaction(@TempDir Path directory) throws Exception {
        var database = new PausingSqliteDatabase(directory.resolve("shared-connection.db").toString());
        assertTrue(database.connect());
        try {
            var repository = new PersistenceRepository(database);
            repository.init();
            try (var statement = database.getConnection().createStatement()) {
                statement.execute("CREATE TRIGGER reject_mark BEFORE INSERT ON marked_chunks "
                    + "WHEN NEW.chunk_x = 2 BEGIN SELECT RAISE(ABORT, 'rejected'); END");
            }

            database.pauseNextTransaction();
            var markBatch = CompletableFuture.runAsync(() -> repository.markBatch(List.of(new MarkedChunk("world", 1, 0,
                UUID.randomUUID(), 1), new MarkedChunk("world", 2, 0, UUID.randomUUID(), 1))));
            assertTrue(database.awaitPausedTransaction());

            var operation = new OperationRecord(UUID.randomUUID(), "RESET", "QUEUED", 0, 1, 100, 100, "test", null,
                null);
            var operationWrite = CompletableFuture.runAsync(() -> repository.save(operation));
            try {
                assertThrows(TimeoutException.class, () -> operationWrite.get(100, TimeUnit.MILLISECONDS));
            } finally {
                database.resumeTransaction();
            }

            assertThrows(java.util.concurrent.CompletionException.class, markBatch::join);
            operationWrite.join();
            assertEquals(List.of(operation), repository.loadOperations());
        } finally {
            database.resumeTransaction();
            database.close();
        }
    }

    @Test
    void databaseCloseWaitsForAnotherStoreTransaction(@TempDir Path directory) throws Exception {
        var database = new PausingSqliteDatabase(directory.resolve("close-connection.db").toString());
        assertTrue(database.connect());
        var repository = new PersistenceRepository(database);
        try {
            repository.init();
            try (var statement = database.getConnection().createStatement()) {
                statement.execute("CREATE TRIGGER reject_mark BEFORE INSERT ON marked_chunks "
                    + "WHEN NEW.chunk_x = 2 BEGIN SELECT RAISE(ABORT, 'rejected'); END");
            }

            database.pauseNextTransaction();
            var markBatch = CompletableFuture.runAsync(() -> repository.markBatch(List.of(new MarkedChunk("world", 1, 0,
                UUID.randomUUID(), 1), new MarkedChunk("world", 2, 0, UUID.randomUUID(), 1))));
            assertTrue(database.awaitPausedTransaction());

            var close = CompletableFuture.supplyAsync(repository::close);
            try {
                assertThrows(TimeoutException.class, () -> close.get(100, TimeUnit.MILLISECONDS));
            } finally {
                database.resumeTransaction();
            }

            assertThrows(java.util.concurrent.CompletionException.class, markBatch::join);
            assertTrue(close.join());
            assertFalse(database.isConnected());
            assertThrows(IllegalStateException.class, repository::loadOperations);
        } finally {
            database.resumeTransaction();
            repository.close();
        }
    }

    @Test
    void asynchronousDatabaseCloseDoesNotBlockItsCaller(@TempDir Path directory) throws Exception {
        var database = new PausingCloseSqliteDatabase(directory.resolve("async-close-connection.db").toString());
        assertTrue(database.connect());
        var repository = new PersistenceRepository(database);
        try {
            repository.init();
            var dispatch = CompletableFuture.supplyAsync(repository::closeAsync);

            var close = dispatch.get(1, TimeUnit.SECONDS);
            assertTrue(database.awaitCloseStarted());
            assertFalse(close.isDone());

            database.resumeClose();

            assertTrue(close.get(5, TimeUnit.SECONDS));
            assertFalse(database.isConnected());
        } finally {
            database.resumeClose();
            repository.close();
        }
    }

    @Test
    void asynchronousDatabaseCloseReturnsWhileStoreTransactionIsBlocked(@TempDir Path directory) throws Exception {
        var database = new PausingSqliteDatabase(directory.resolve("async-close-transaction.db").toString());
        assertTrue(database.connect());
        var repository = new PersistenceRepository(database);
        try {
            repository.init();
            database.pauseNextTransaction();
            var markWrite = CompletableFuture.runAsync(() -> repository.markBatch(List.of(new MarkedChunk("world", 1, 0,
                UUID.randomUUID(), 1))));
            assertTrue(database.awaitPausedTransaction());

            var dispatch = CompletableFuture.supplyAsync(repository::closeAsync);
            var close = dispatch.get(1, TimeUnit.SECONDS);
            assertThrows(TimeoutException.class, () -> close.get(100, TimeUnit.MILLISECONDS));

            database.resumeTransaction();

            markWrite.join();
            assertTrue(close.get(5, TimeUnit.SECONDS));
            assertFalse(database.isConnected());
        } finally {
            database.resumeTransaction();
            repository.close();
        }
    }

    @Test
    void sharedDeletionJobAssociationsSurviveReopeningDatabase(@TempDir Path directory) {
        Path file = directory.resolve("operation-deletion-jobs.db");
        UUID firstOperation = UUID.randomUUID();
        UUID secondOperation = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        var database = new SqliteDatabase(file.toString());
        assertTrue(database.connect());
        try {
            var repository = new PersistenceRepository(database);
            repository.init();
            repository.saveDeletionJobs(List.of(new DeletionJob(jobId, "CHUNK_DELETE", "WAITING_FOR_COLD", "world", 1,
                2, 100)), firstOperation);
            repository.associateOperation(secondOperation, jobId);
            repository.save(new OperationRecord(firstOperation, "RESET", "RUNNING", 0, 1, 100, 100, "test", null,
                null));
            repository.save(new OperationRecord(secondOperation, "RESET", "RUNNING", 0, 1, 100, 100, "test", null,
                null));
        } finally {
            database.close();
        }

        var reopened = new SqliteDatabase(file.toString());
        assertTrue(reopened.connect());
        try {
            var operations = new PersistenceRepository(reopened).loadOperations();

            assertEquals(List.of(jobId), operations.stream()
                .filter(operation -> operation.id().equals(firstOperation))
                .findFirst()
                .orElseThrow()
                .deletionJobIds());
            assertEquals(List.of(jobId), operations.stream()
                .filter(operation -> operation.id().equals(secondOperation))
                .findFirst()
                .orElseThrow()
                .deletionJobIds());
        } finally {
            reopened.close();
        }
    }

    @Test
    void terminalStateSurvivesReopeningDatabase(@TempDir Path directory) {
        Path file = directory.resolve("operations.db");
        UUID id = UUID.randomUUID();
        var queued = new OperationRecord(id, "SCAN_EXISTING_CHUNKS", "QUEUED", 0, 2, 100, 100, "test", null, null);
        var finished = new OperationRecord(id, queued.type(), "FAILED", 1, 2, 100, 200, "test", null, "SCAN_FAILED",
            "Chunk scan failed; check server logs.", Map.of("region", "0,0"), List.of());
        var database = new SqliteDatabase(file.toString());
        assertTrue(database.connect());
        try {
            var repository = new PersistenceRepository(database);
            repository.init();
            repository.save(queued);
            repository.update(finished);
        } finally {
            database.close();
        }
        var reopened = new SqliteDatabase(file.toString());
        assertTrue(reopened.connect());
        try {
            var loaded = new PersistenceRepository(reopened).loadOperations();
            assertEquals(List.of(finished), loaded);
            assertEquals("Chunk scan failed; check server logs.", loaded.getFirst().failureMessage());
            assertEquals(Map.of("region", "0,0"), loaded.getFirst().failureDetails());
        } finally {
            reopened.close();
        }
    }

    @Test
    void addsFailureDetailColumnsToExistingOperationsTable(@TempDir Path directory) throws SQLException {
        var database = new SqliteDatabase(directory.resolve("legacy-operations.db").toString());
        assertTrue(database.connect());
        try {
            try (var statement = database.getConnection().createStatement()) {
                statement.execute("CREATE TABLE operations (operation_id TEXT PRIMARY KEY, type TEXT NOT NULL, "
                    + "state TEXT NOT NULL, completed INTEGER NOT NULL, total INTEGER NOT NULL, created_at INTEGER "
                    + "NOT NULL, updated_at INTEGER NOT NULL, source_plugin TEXT NOT NULL, correlation_id TEXT, "
                    + "failure_code TEXT)");
            }
            var repository = new PersistenceRepository(database);
            repository.init();
            var record = new OperationRecord(UUID.randomUUID(), "RESET", "FAILED", 0, 1, 100, 200, "test", null,
                "DELETION_FAILED", "The target could not be removed.", Map.of("deletionJobId", "job-1"), List.of());

            repository.save(record);

            assertEquals(List.of(record), repository.loadOperations());
        } finally {
            database.close();
        }
    }

    @Test
    void duplicateOperationWriteReportsDatabaseFailure(@TempDir Path directory) {
        var database = new SqliteDatabase(directory.resolve("operations.db").toString());
        assertTrue(database.connect());
        try {
            var repository = new PersistenceRepository(database);
            repository.init();
            var record = new OperationRecord(UUID.randomUUID(), "RESET", "QUEUED", 0, 1, 100, 100, "test", null, null);
            repository.save(record);

            var failure = assertThrows(IllegalStateException.class, () -> repository.save(record));

            assertInstanceOf(SQLException.class, failure.getCause());
        } finally {
            database.close();
        }
    }

    @Test
    void missingOperationsTableReportsDatabaseFailure(@TempDir Path directory) {
        var database = new SqliteDatabase(directory.resolve("operations.db").toString());
        assertTrue(database.connect());
        try {
            var repository = new PersistenceRepository(database);

            var failure = assertThrows(IllegalStateException.class, repository::loadOperations);

            assertInstanceOf(SQLException.class, failure.getCause());
        } finally {
            database.close();
        }
    }

    @Test
    void updatingUnknownOperationReportsMissingRecord(@TempDir Path directory) {
        var database = new SqliteDatabase(directory.resolve("operations.db").toString());
        assertTrue(database.connect());
        try {
            var repository = new PersistenceRepository(database);
            repository.init();
            var record = new OperationRecord(UUID.randomUUID(), "RESET", "FAILED", 0, 1, 100, 200, "test", null,
                "ERROR");

            var failure = assertThrows(IllegalStateException.class, () -> repository.update(record));

            assertTrue(failure.getMessage().contains(record.id().toString()));
        } finally {
            database.close();
        }
    }

    @Test
    void updatingOperationWithUnchangedValuesSucceeds(@TempDir Path directory) {
        var database = new SqliteDatabase(directory.resolve("unchanged-operation.db").toString());
        assertTrue(database.connect());
        try {
            var repository = new PersistenceRepository(database);
            repository.init();
            var record = new OperationRecord(UUID.randomUUID(), "RESET", "QUEUED", 0, 1, 100, 100, "test", null, null);
            repository.save(record);

            repository.update(record);

            assertEquals(List.of(record), repository.loadOperations());
        } finally {
            database.close();
        }
    }

    private static final class PausingSqliteDatabase extends SqliteDatabase {

        private final CountDownLatch transactionPaused    = new CountDownLatch(1);
        private final CountDownLatch resumeTransaction    = new CountDownLatch(1);
        private final AtomicBoolean  pauseNextTransaction = new AtomicBoolean();

        private PausingSqliteDatabase(String path) {
            super(path);
        }

        @Override
        public java.sql.Connection getConnection() {
            var connection = super.getConnection();
            return (java.sql.Connection) Proxy.newProxyInstance(connection.getClass().getClassLoader(), new Class<?>[] {
                java.sql.Connection.class }, (proxy, method, arguments) -> {
                    Object result;
                    try {
                        result = method.invoke(connection, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                    if (method.getName().equals("setAutoCommit") && arguments != null && Boolean.FALSE.equals(
                        arguments[0]) && pauseNextTransaction.compareAndSet(true, false)) {
                        transactionPaused.countDown();
                        if (!resumeTransaction.await(5, TimeUnit.SECONDS)) {
                            throw new SQLException("Timed out waiting to resume the test transaction");
                        }
                    }
                    return result;
                });
        }

        private void pauseNextTransaction() {
            pauseNextTransaction.set(true);
        }

        private boolean awaitPausedTransaction() throws InterruptedException {
            return transactionPaused.await(5, TimeUnit.SECONDS);
        }

        private void resumeTransaction() {
            resumeTransaction.countDown();
        }
    }

    private static final class PausingCloseSqliteDatabase extends SqliteDatabase {

        private final CountDownLatch closeStarted = new CountDownLatch(1);
        private final CountDownLatch resumeClose  = new CountDownLatch(1);

        private PausingCloseSqliteDatabase(String path) {
            super(path);
        }

        @Override
        public boolean close() {
            closeStarted.countDown();
            try {
                if (!resumeClose.await(5, TimeUnit.SECONDS)) {
                    return false;
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                return false;
            }
            return super.close();
        }

        private boolean awaitCloseStarted() throws InterruptedException {
            return closeStarted.await(5, TimeUnit.SECONDS);
        }

        private void resumeClose() {
            resumeClose.countDown();
        }
    }
}
