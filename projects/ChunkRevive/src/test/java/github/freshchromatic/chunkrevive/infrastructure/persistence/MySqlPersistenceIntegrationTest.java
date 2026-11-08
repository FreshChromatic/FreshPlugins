package github.freshchromatic.chunkrevive.infrastructure.persistence;

import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import github.freshchromatic.chunkrevive.feature.operation.OperationRecord;
import github.freshchromatic.freshlib.database.MySqlDatabase;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MySqlPersistenceIntegrationTest {

    @Test
    void mysqlSchemaWritesConnectionFailureAndRecovery() throws Exception {
        String host = System.getenv("CHUNKREVIVE_MYSQL_HOST");
        String port = System.getenv("CHUNKREVIVE_MYSQL_PORT");
        String username = System.getenv("CHUNKREVIVE_MYSQL_USERNAME");
        String password = System.getenv("CHUNKREVIVE_MYSQL_PASSWORD");
        assumeTrue(host != null && port != null && username != null && password != null,
            "MySQL integration credentials are not configured");

        String schema = "chunkrevive_it_" + UUID.randomUUID().toString().replace("-", "");
        var properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        String adminUrl = "jdbc:mysql://" + host + ":" + port + "/?useAffectedRows=true";

        try (var admin = DriverManager.getConnection(adminUrl, properties)) {
            try (var statement = admin.createStatement()) {
                statement.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
            }
            try {
                verifySchemaMigration(host, port, schema, username, password);
                verifyConnectionFailureAndRecovery(admin, host, port, schema, username, password);
            } finally {
                try (var statement = admin.createStatement()) {
                    statement.execute("DROP DATABASE IF EXISTS " + schema);
                }
            }
        }
    }

    private static void verifySchemaMigration(String host, String port, String schema, String username, String password)
        throws SQLException {
        var database = new MySqlDatabase(host, port, schema, username, password);
        assertTrue(database.connect());
        try {
            try (var statement = database.getConnection().createStatement()) {
                statement.execute("CREATE TABLE operations (operation_id VARCHAR(255) PRIMARY KEY, type TEXT NOT NULL, "
                    + "state TEXT NOT NULL, completed BIGINT NOT NULL, total BIGINT NOT NULL, created_at BIGINT "
                    + "NOT NULL, updated_at BIGINT NOT NULL, source_plugin TEXT NOT NULL, correlation_id TEXT, "
                    + "failure_code TEXT)");
            }
            var repository = new PersistenceRepository(database);
            repository.init();

            try (var columns = database.getConnection().getMetaData().getColumns(schema, null, "operations", null)) {
                var migrated = new HashSet<String>();
                while (columns.next()) {
                    migrated.add(columns.getString("COLUMN_NAME"));
                }
                assertTrue(migrated.containsAll(List.of("failure_message", "failure_details")));
            }

            var mark = new MarkedChunk("mysql-integration", -3, 7, UUID.randomUUID(), 100);
            assertTrue(repository.mark(mark));
            assertFalse(repository.mark(mark));
            assertEquals(List.of(mark), repository.loadAll());
            assertTrue(repository.unmark("mysql-integration", -3, 7));
            assertFalse(repository.unmark("mysql-integration", -3, 7));

            var operation = new OperationRecord(UUID.randomUUID(), "RESET", "QUEUED", 0, 1, 100, 100, "integration",
                null, null, null, Map.of(), List.of());
            repository.save(operation);
            repository.update(operation);
            assertEquals(List.of(operation), repository.loadOperations());
            assertThrows(IllegalStateException.class, () -> repository.save(operation));
            assertTrue(database.close());
        } finally {
            if (database.isConnected()) {
                database.close();
            }
        }
    }

    private static void verifyConnectionFailureAndRecovery(Connection admin, String host, String port, String schema,
        String username, String password) throws SQLException {
        var database = new MySqlDatabase(host, port, schema, username, password);
        assertTrue(database.connect());
        var repository = new PersistenceRepository(database);
        repository.init();
        UUID operationId = UUID.randomUUID();
        var operation = new OperationRecord(operationId, "RESET", "RUNNING", 0, 1, 200, 200, "integration", null, null,
            null, Map.of(), List.of());
        repository.save(operation);

        long connectionId;
        try (var statement = database.getConnection().createStatement();
            var result = statement.executeQuery("SELECT CONNECTION_ID()")) {
            assertTrue(result.next());
            connectionId = result.getLong(1);
        }
        try (var statement = admin.createStatement()) {
            statement.execute("KILL CONNECTION " + connectionId);
        }

        var failure = assertThrows(IllegalStateException.class, repository::loadOperations);
        assertInstanceOf(SQLException.class, failure.getCause());
        database.close();

        var recoveredDatabase = new MySqlDatabase(host, port, schema, username, password);
        assertTrue(recoveredDatabase.connect());
        try {
            var recoveredRepository = new PersistenceRepository(recoveredDatabase);
            recoveredRepository.init();
            assertTrue(recoveredRepository.loadOperations().contains(operation));
            assertTrue(recoveredDatabase.close());
        } finally {
            if (recoveredDatabase.isConnected()) {
                recoveredDatabase.close();
            }
        }
    }
}
