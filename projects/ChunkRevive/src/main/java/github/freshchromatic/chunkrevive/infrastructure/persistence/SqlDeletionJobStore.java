package github.freshchromatic.chunkrevive.infrastructure.persistence;

import github.freshchromatic.chunkrevive.feature.reset.DeletionJob;
import github.freshchromatic.freshlib.database.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** SQL persistence dedicated to restart-safe online deletion jobs. */
final class SqlDeletionJobStore {

    private final Database                  database;
    private final PersistenceConnectionLock connectionLock;
    private final boolean                   mysql;

    SqlDeletionJobStore(Database database, PersistenceConnectionLock connectionLock, boolean mysql) {
        this.database = database;
        this.connectionLock = connectionLock;
        this.mysql = mysql;
    }

    void save(Collection<DeletionJob> jobs) {
        save(jobs, null, List.of());
    }

    void save(Collection<DeletionJob> jobs, UUID operationId) {
        save(jobs, operationId, List.of());
    }

    void save(Collection<DeletionJob> jobs, UUID operationId, Collection<UUID> associatedJobIds) {
        if (jobs.isEmpty() && operationId == null) {
            return;
        }
        synchronized (connectionLock) {
            connectionLock.ensureOpen();
            var connection = database.getConnection();
            try {
                boolean autoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try {
                    if (!jobs.isEmpty()) {
                        try (PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO deletion_jobs
                                (job_id, type, state, world, target_x, target_z, created_at)
                            VALUES (?,?,?,?,?,?,?)
                            """)) {
                            for (DeletionJob job : jobs) {
                                statement.setString(1, job.id().toString());
                                statement.setString(2, job.type());
                                statement.setString(3, job.state());
                                statement.setString(4, job.world());
                                statement.setInt(5, job.x());
                                statement.setInt(6, job.z());
                                statement.setLong(7, job.createdAt());
                                statement.addBatch();
                            }
                            statement.executeBatch();
                        }
                    }
                    if (operationId != null) {
                        List<UUID> allJobIds = new ArrayList<>(jobs.stream().map(DeletionJob::id).toList());
                        allJobIds.addAll(associatedJobIds);
                        associate(connection, operationId, allJobIds);
                    }
                    connection.commit();
                } catch (SQLException failure) {
                    connection.rollback();
                    throw failure;
                } finally {
                    connection.setAutoCommit(autoCommit);
                }
            } catch (SQLException failure) {
                throw new IllegalStateException("Failed to save deletion jobs", failure);
            }
        }
    }

    void associateOperation(UUID operationId, UUID deletionJobId) {
        associateOperation(operationId, List.of(deletionJobId));
    }

    void associateOperation(UUID operationId, Collection<UUID> deletionJobIds) {
        if (deletionJobIds.isEmpty()) {
            return;
        }
        synchronized (connectionLock) {
            connectionLock.ensureOpen();
            try {
                var connection = database.getConnection();
                boolean autoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try {
                    associate(connection, operationId, deletionJobIds);
                    connection.commit();
                } catch (SQLException failure) {
                    connection.rollback();
                    throw failure;
                } finally {
                    connection.setAutoCommit(autoCommit);
                }
            } catch (SQLException failure) {
                throw new IllegalStateException("Failed to associate deletion job with operation", failure);
            }
        }
    }

    private void associate(Connection connection, UUID operationId, Collection<UUID> deletionJobIds)
        throws SQLException {
        String sql = mysql ? "INSERT IGNORE INTO operation_deletion_jobs (operation_id, job_id) VALUES (?,?)"
            : "INSERT OR IGNORE INTO operation_deletion_jobs (operation_id, job_id) VALUES (?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (UUID deletionJobId : deletionJobIds) {
                statement.setString(1, operationId.toString());
                statement.setString(2, deletionJobId.toString());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    void updateState(UUID id, String state) {
        updateStates(List.of(id), state);
    }

    void updateStates(Collection<UUID> ids, String state) {
        if (ids.isEmpty()) {
            return;
        }
        List<UUID> jobIds = List.copyOf(ids);
        synchronized (connectionLock) {
            connectionLock.ensureOpen();
            try {
                var connection = database.getConnection();
                boolean autoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE deletion_jobs SET state=? WHERE job_id=?")) {
                    for (UUID id : jobIds) {
                        statement.setString(1, state);
                        statement.setString(2, id.toString());
                        statement.addBatch();
                    }
                    int[] updated = statement.executeBatch();
                    for (int index = 0; index < updated.length; index++) {
                        if (updated[index] == 0 && !jobExists(connection, jobIds.get(index))) {
                            throw new SQLException("Deletion job record not found");
                        }
                    }
                    connection.commit();
                } catch (SQLException failure) {
                    connection.rollback();
                    throw failure;
                } finally {
                    connection.setAutoCommit(autoCommit);
                }
            } catch (SQLException failure) {
                throw new IllegalStateException("Failed to update deletion jobs", failure);
            }
        }
    }

    private static boolean jobExists(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM deletion_jobs WHERE job_id=?")) {
            statement.setString(1, id.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    void delete(UUID id) {
        delete(List.of(id));
    }

    void delete(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        synchronized (connectionLock) {
            connectionLock.ensureOpen();
            try {
                var connection = database.getConnection();
                boolean autoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM deletion_jobs WHERE job_id=?")) {
                    for (UUID id : ids) {
                        statement.setString(1, id.toString());
                        statement.addBatch();
                    }
                    statement.executeBatch();
                    connection.commit();
                } catch (SQLException failure) {
                    connection.rollback();
                    throw failure;
                } finally {
                    connection.setAutoCommit(autoCommit);
                }
            } catch (SQLException failure) {
                throw new IllegalStateException("Failed to delete persistent deletion jobs", failure);
            }
        }
    }

    void deleteAll() {
        synchronized (connectionLock) {
            connectionLock.ensureOpen();
            try (PreparedStatement statement = database.getConnection().prepareStatement("DELETE FROM deletion_jobs")) {
                statement.executeUpdate();
            } catch (SQLException failure) {
                throw new IllegalStateException("Failed to clear persistent deletion jobs", failure);
            }
        }
    }

    List<DeletionJob> loadAll() {
        List<DeletionJob> result = new ArrayList<>();
        synchronized (connectionLock) {
            connectionLock.ensureOpen();
            try (PreparedStatement statement = database.getConnection().prepareStatement(
                "SELECT * FROM deletion_jobs ORDER BY created_at"); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new DeletionJob(UUID.fromString(rows.getString("job_id")), rows.getString("type"),
                        rows.getString("state"), rows.getString("world"), rows.getInt("target_x"), rows.getInt(
                            "target_z"), rows.getLong("created_at")));
                }
            } catch (SQLException | IllegalArgumentException failure) {
                throw new IllegalStateException("Failed to load deletion jobs", failure);
            }
        }
        return result;
    }
}
