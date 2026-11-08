package github.freshchromatic.chunkrevive.infrastructure.persistence;

import github.freshchromatic.freshlib.database.Database;
import github.freshchromatic.freshlib.database.SqliteDatabase;
import github.freshchromatic.freshlib.database.MySqlDatabase;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.contains;

class PersistenceInitializationTest {

    @Test
    void reportsSchemaCreationFailure() throws SQLException {
        var database = mock(Database.class);
        var connection = mock(Connection.class);
        var metadata = mock(DatabaseMetaData.class);
        when(database.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDriverName()).thenReturn("test");
        when(database.executeNonQuery(anyString())).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> new PersistenceRepository(database).init());
    }

    @Test
    void initializesMysqlWithCompatibleColumnTypes() throws SQLException {
        var database = mock(MySqlDatabase.class);
        var connection = mock(Connection.class);
        var metadata = mock(DatabaseMetaData.class);
        when(database.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDriverName()).thenReturn("mysql");
        when(database.executeNonQuery(anyString())).thenReturn(true);

        new PersistenceRepository(database).init();

        verify(database).executeNonQuery(contains("AUTO_INCREMENT PRIMARY KEY"));
        verify(database).executeNonQuery(contains(
            "group_id            VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin"));
        verify(database).executeNonQuery(contains("correlation_id TEXT"));
        verify(database).executeNonQuery(contains("CREATE TABLE IF NOT EXISTS operation_deletion_jobs"));
        verify(database).executeNonQuery(contains(
            "operation_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL"));
    }

    @Test
    void missingTablesReportLoadFailures(@TempDir Path directory) {
        var database = new SqliteDatabase(directory.resolve("missing.db").toString());
        assertTrue(database.connect());
        try {
            var repository = new PersistenceRepository(database);
            assertThrows(IllegalStateException.class, repository::loadAll);
            assertThrows(IllegalStateException.class, repository::loadAllGroups);
            assertThrows(IllegalStateException.class, () -> repository.updateProtection(UUID.randomUUID(), 1, true));
            assertThrows(IllegalStateException.class, () -> repository.updateNextRefresh(UUID.randomUUID(), 1));
            assertThrows(IllegalStateException.class, () -> repository.unmark("world", 1, 0));
            var chunk = new MarkedChunk("world", 1, 0, UUID.randomUUID(), 1);
            assertThrows(IllegalStateException.class, () -> repository.mark(chunk));
            assertThrows(IllegalStateException.class, () -> repository.unmarkBatch(List.of(chunk)));
            assertThrows(IllegalStateException.class, () -> repository.deleteAllForWorld("world"));
        } finally {
            database.close();
        }
    }

    @Test
    void failedMarkBatchRollsBackEarlierInserts(@TempDir Path directory) throws SQLException {
        var database = new SqliteDatabase(directory.resolve("marks.db").toString());
        assertTrue(database.connect());
        try {
            var repository = new PersistenceRepository(database);
            repository.init();
            try (var statement = database.getConnection().createStatement()) {
                statement.execute("CREATE TRIGGER reject_second BEFORE INSERT ON marked_chunks "
                    + "WHEN NEW.chunk_x = 2 BEGIN SELECT RAISE(ABORT, 'rejected'); END");
            }
            var actor = UUID.randomUUID();
            var marks = List.of(new MarkedChunk("world", 1, 0, actor, 1), new MarkedChunk("world", 2, 0, actor, 1));

            assertThrows(IllegalStateException.class, () -> repository.markBatch(marks));
            assertTrue(repository.loadAll().isEmpty());
        } finally {
            database.close();
        }
    }

    @Test
    void failedUnmarkBatchRollsBackEarlierDeletes(@TempDir Path directory) throws SQLException {
        var database = new SqliteDatabase(directory.resolve("unmarks.db").toString());
        assertTrue(database.connect());
        try {
            var repository = new PersistenceRepository(database);
            repository.init();
            var actor = UUID.randomUUID();
            var marks = List.of(new MarkedChunk("world", 1, 0, actor, 1), new MarkedChunk("world", 2, 0, actor, 1));
            repository.markBatch(marks);
            try (var statement = database.getConnection().createStatement()) {
                statement.execute("CREATE TRIGGER reject_second_delete BEFORE DELETE ON marked_chunks "
                    + "WHEN OLD.chunk_x = 2 BEGIN SELECT RAISE(ABORT, 'rejected'); END");
            }

            assertThrows(IllegalStateException.class, () -> repository.unmarkBatch(marks));
            assertTrue(repository.loadAll().containsAll(marks));
        } finally {
            database.close();
        }
    }

    @Test
    void unmarkBatchWithMissingRecordRollsBackEarlierDeletes(@TempDir Path directory) {
        var database = new SqliteDatabase(directory.resolve("missing-unmark.db").toString());
        assertTrue(database.connect());
        try {
            var repository = new PersistenceRepository(database);
            repository.init();
            var existing = new MarkedChunk("world", 1, 0, UUID.randomUUID(), 1);
            var missing = new MarkedChunk("world", 2, 0, UUID.randomUUID(), 1);
            repository.mark(existing);

            assertThrows(IllegalStateException.class, () -> repository.unmarkBatch(List.of(existing, missing)));

            assertEquals(List.of(existing), repository.loadAll());
        } finally {
            database.close();
        }
    }

    @Test
    void duplicateMarksKeepOriginalRecord(@TempDir Path directory) {
        var database = new SqliteDatabase(directory.resolve("duplicate-marks.db").toString());
        assertTrue(database.connect());
        try {
            var repository = new PersistenceRepository(database);
            repository.init();
            var original = new MarkedChunk("world", 1, 2, UUID.randomUUID(), 1);
            var duplicate = new MarkedChunk("world", 1, 2, UUID.randomUUID(), 2);

            assertTrue(repository.mark(original));
            assertFalse(repository.mark(duplicate));
            assertTrue(repository.loadAll().getFirst().markedBy().equals(original.markedBy()));
        } finally {
            database.close();
        }
    }

    @Test
    void worldNamesWithDifferentCaseRemainDistinct(@TempDir Path directory) {
        var database = new SqliteDatabase(directory.resolve("world-case.db").toString());
        assertTrue(database.connect());
        try {
            var repository = new PersistenceRepository(database);
            repository.init();
            var actor = UUID.randomUUID();

            assertTrue(repository.mark(new MarkedChunk("World", 1, 2, actor, 1)));
            assertTrue(repository.mark(new MarkedChunk("world", 1, 2, actor, 1)));
            assertEquals(2, repository.loadAll().size());
        } finally {
            database.close();
        }
    }
}
