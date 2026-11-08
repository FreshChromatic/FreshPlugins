package github.freshchromatic.freshlib.database;

import github.freshchromatic.freshlib.util.Logging;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;

public class MySqlDatabase implements Database {

    protected final String host;
    protected final String port;
    protected final String database;
    protected final String username;
    protected final String password;
    protected Connection   connection;

    public MySqlDatabase(String host, String port, String database, String username, String password) {
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
        this.connection = null;
    }

    @Override
    public boolean connect() {
        try {
            if (isConnected()) {
                return true;
            }

            Class.forName("com.mysql.cj.jdbc.Driver");
            connection = DriverManager.getConnection("jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useAffectedRows=true", username, password);
            return true;
        } catch (Exception e) {
            Logging.logger().severe("Could not connect to MySQL: " + e.getMessage(), e);
            return false;
        }
    }

    @Override
    public boolean close() {
        try {
            if (connection == null) {
                return true;
            }

            connection.close();
            connection = null;
            return true;
        } catch (Exception e) {
            Logging.logger().severe("Could not close MySQL connection: " + e.getMessage(), e);
            return false;
        }
    }

    @Override
    public boolean isConnected() {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException failure) {
            return false;
        }
    }

    @Override
    public Connection getConnection() {
        return connection;
    }

    @Override
    public boolean executeNonQuery(String sql) {
        try {
            try (var statement = connection.createStatement()) {
                statement.execute(sql);
            }
            return true;
        } catch (Exception e) {
            Logging.logger().severe("Could not execute MySQL statement: " + e.getMessage(), e);
            return false;
        }
    }

    @Override
    @Deprecated
    public ResultSet executeQuery(String query) {
        try {
            ResultSet resultSet = connection.createStatement().executeQuery(query);
            return resultSet;
        } catch (Exception e) {
            Logging.logger().severe("Could not execute MySQL query: " + e.getMessage(), e);
            return null;
        }
    }

    @Override
    public <T> T executeQuery(String query, ResultSetHandler<T> handler) throws SQLException {
        try (var statement = connection.createStatement(); var resultSet = statement.executeQuery(query)) {
            return handler.handle(resultSet);
        }
    }
}
