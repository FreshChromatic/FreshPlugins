package github.freshchromatic.freshlib.database;

import github.freshchromatic.freshlib.util.Logging;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseStatementTest {

    @Test
    void connectionStateReflectsJdbcClosedState() throws Exception {
        var mysql = new MySqlDatabase("localhost", "3306", "database", "user", "password");
        var mysqlConnection = mock(Connection.class);
        mysql.connection = mysqlConnection;

        var postgres = new PostgreSqlDatabase("localhost", "5432", "database", "user", "password");
        var postgresConnection = mock(Connection.class);
        postgres.connection = postgresConnection;

        assertTrue(mysql.isConnected());
        assertTrue(postgres.isConnected());
        when(mysqlConnection.isClosed()).thenReturn(true);
        when(postgresConnection.isClosed()).thenReturn(true);
        assertFalse(mysql.isConnected());
        assertFalse(postgres.isConnected());

        assertTrue(mysql.close());
        assertTrue(postgres.close());
        assertNull(mysql.getConnection());
        assertNull(postgres.getConnection());
        verify(mysqlConnection).close();
        verify(postgresConnection).close();
    }

    @Test
    void connectionStateFailsClosedWhenJdbcCannotReportStatus() throws Exception {
        var mysql = new MySqlDatabase("localhost", "3306", "database", "user", "password");
        var mysqlConnection = mock(Connection.class);
        when(mysqlConnection.isClosed()).thenThrow(new SQLException("connection unavailable"));
        mysql.connection = mysqlConnection;

        var postgres = new PostgreSqlDatabase("localhost", "5432", "database", "user", "password");
        var postgresConnection = mock(Connection.class);
        when(postgresConnection.isClosed()).thenThrow(new SQLException("connection unavailable"));
        postgres.connection = postgresConnection;

        assertFalse(mysql.isConnected());
        assertFalse(postgres.isConnected());
    }

    @Test
    void closesStatementsAfterNonQueryExecution() throws Exception {
        var mysqlConnection = mock(Connection.class);
        var mysqlStatement = mock(Statement.class);
        when(mysqlConnection.createStatement()).thenReturn(mysqlStatement);
        var mysql = new MySqlDatabase("localhost", "3306", "database", "user", "password");
        mysql.connection = mysqlConnection;

        var postgresConnection = mock(Connection.class);
        var postgresStatement = mock(Statement.class);
        when(postgresConnection.createStatement()).thenReturn(postgresStatement);
        var postgres = new PostgreSqlDatabase("localhost", "5432", "database", "user", "password");
        postgres.connection = postgresConnection;

        assertTrue(mysql.executeNonQuery("UPDATE records SET value = 1"));
        assertTrue(postgres.executeNonQuery("UPDATE records SET value = 1"));
        verify(mysqlStatement).close();
        verify(postgresStatement).close();
    }

    @Test
    void databaseFailuresAreLoggedAndKeepLegacyResults() throws Exception {
        var logger = mock(Logging.LoggerProxy.class);
        var mysqlConnection = mock(Connection.class);
        when(mysqlConnection.createStatement()).thenThrow(new SQLException("MySQL unavailable"));
        var mysql = new MySqlDatabase("localhost", "3306", "database", "user", "password");
        mysql.connection = mysqlConnection;

        var postgresConnection = mock(Connection.class);
        when(postgresConnection.createStatement()).thenThrow(new SQLException("PostgreSQL unavailable"));
        var postgres = new PostgreSqlDatabase("localhost", "5432", "database", "user", "password");
        postgres.connection = postgresConnection;

        try (MockedStatic<Logging> logging = mockStatic(Logging.class)) {
            logging.when(Logging::logger).thenReturn(logger);

            assertFalse(mysql.executeNonQuery("UPDATE records SET value = 1"));
            assertFalse(postgres.executeNonQuery("UPDATE records SET value = 1"));

            verify(logger).severe(org.mockito.ArgumentMatchers.eq(
                "Could not execute MySQL statement: MySQL unavailable"), org.mockito.ArgumentMatchers.any(
                    SQLException.class));
            verify(logger).severe(org.mockito.ArgumentMatchers.eq(
                "Could not execute PostgreSQL statement: PostgreSQL unavailable"), org.mockito.ArgumentMatchers.any(
                    SQLException.class));
        }
    }

    @Test
    void scopedQueriesCloseResultsAndStatements() throws Exception {
        var mysql = mockDatabase(true);
        var postgres = mockDatabase(false);

        assertEquals("row", mysql.database().executeQuery("SELECT value", resultSet -> resultSet.getString(1)));
        assertEquals("row", postgres.database().executeQuery("SELECT value", resultSet -> resultSet.getString(1)));

        verify(mysql.resultSet()).close();
        verify(mysql.statement()).close();
        verify(postgres.resultSet()).close();
        verify(postgres.statement()).close();
    }

    @Test
    void scopedQueryClosesResourcesWhenHandlerFails() throws Exception {
        var query = mockDatabase(true);
        var failure = new SQLException("query mapping failed");

        SQLException thrown = assertThrows(SQLException.class, () -> query.database().executeQuery("SELECT value",
            resultSet -> {
                throw failure;
            }));

        assertEquals(failure, thrown);
        verify(query.resultSet()).close();
        verify(query.statement()).close();
    }

    @Test
    void defaultScopedQuerySupportsExistingImplementations() throws Exception {
        var resultSet = mock(ResultSet.class);
        when(resultSet.getString(1)).thenReturn("row");
        Database database = new LegacyDatabase(resultSet);

        assertEquals("row", database.executeQuery("SELECT value", rows -> rows.getString(1)));

        verify(resultSet).close();
    }

    private static MockDatabase mockDatabase(boolean mysql) throws SQLException {
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        var resultSet = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT value")).thenReturn(resultSet);
        when(resultSet.getString(1)).thenReturn("row");
        Database database = mysql ? new MySqlDatabase("localhost", "3306", "database", "user", "password")
            : new PostgreSqlDatabase("localhost", "5432", "database", "user", "password");
        if (database instanceof MySqlDatabase mysqlDatabase) {
            mysqlDatabase.connection = connection;
        } else if (database instanceof PostgreSqlDatabase postgresDatabase) {
            postgresDatabase.connection = connection;
        }
        return new MockDatabase(database, statement, resultSet);
    }

    private record MockDatabase(Database database, Statement statement, ResultSet resultSet) {}

    @SuppressWarnings("deprecation")
    private static final class LegacyDatabase implements Database {

        private final ResultSet resultSet;

        private LegacyDatabase(ResultSet resultSet) {
            this.resultSet = resultSet;
        }

        @Override
        public boolean connect() {
            return false;
        }

        @Override
        public boolean close() {
            return true;
        }

        @Override
        public boolean isConnected() {
            return false;
        }

        @Override
        public Connection getConnection() {
            return null;
        }

        @Override
        public boolean executeNonQuery(String sql) {
            return false;
        }

        @Override
        public ResultSet executeQuery(String query) {
            return resultSet;
        }
    }
}
