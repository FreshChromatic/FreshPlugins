package github.freshchromatic.freshlib.database;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

public interface Database {

    /**
     * Connects to the database
     *
     * @return true if success, false if failed
     */
    boolean connect();

    /**
     * Closes the database connection
     *
     * @return true if success, false if failed
     */
    boolean close();

    /**
     * @return true if connected, false if not
     */
    boolean isConnected();

    /**
     * @return the connection object, null if not connected
     */
    Connection getConnection();

    /**
     * Executes a statement on the database
     *
     * @return true if success, false if failed
     */
    boolean executeNonQuery(String sql);

    /**
     * Executes a query on the database
     *
     * @return the result or null if failed
     * @deprecated The owning statement cannot be closed through the returned result set. Use the scoped query overload.
     */
    @Deprecated
    ResultSet executeQuery(String query);

    @SuppressWarnings("deprecation")
    default <T> T executeQuery(String query, ResultSetHandler<T> handler) throws SQLException {
        ResultSet resultSet = executeQuery(query);
        if (resultSet == null) {
            throw new SQLException("Database query failed");
        }
        try (resultSet) {
            return handler.handle(resultSet);
        }
    }

    @FunctionalInterface
    interface ResultSetHandler<T> {

        T handle(ResultSet resultSet) throws SQLException;
    }
}
