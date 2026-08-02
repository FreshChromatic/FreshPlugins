package github.freshchromatic.chunkrevive.infrastructure.persistence;

import github.freshchromatic.chunkrevive.feature.reset.DeletionJob;
import github.freshchromatic.freshlib.database.Database;
import github.freshchromatic.freshlib.util.Logging;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** SQL persistence dedicated to restart-safe online deletion jobs. */
final class SqlDeletionJobStore {
    private final Database database;
    private final Object transactionLock;

    SqlDeletionJobStore(Database database, Object transactionLock) {
        this.database = database;
        this.transactionLock = transactionLock;
    }

    void save(Collection<DeletionJob> jobs) {
        if (jobs.isEmpty()) return;
        synchronized (transactionLock) {
            try {
                var connection = database.getConnection();
                boolean autoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
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
                    connection.commit();
                } catch (SQLException failure) {
                    connection.rollback();
                    throw failure;
                } finally {
                    connection.setAutoCommit(autoCommit);
                }
            } catch (SQLException failure) {
                Logging.logger().severe("Failed to save deletion jobs: " + failure.getMessage());
            }
        }
    }

    void updateState(UUID id, String state) {
        updateStates(List.of(id), state);
    }

    void updateStates(Collection<UUID> ids, String state) {
        if (ids.isEmpty()) return;
        synchronized (transactionLock) {
            try {
                var connection = database.getConnection();
                boolean autoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE deletion_jobs SET state=? WHERE job_id=?")) {
                    for (UUID id : ids) {
                        statement.setString(1, state);
                        statement.setString(2, id.toString());
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
                Logging.logger().severe("Failed to update " + ids.size() + " deletion jobs: " + failure.getMessage());
            }
        }
    }

    void delete(UUID id) {
        delete(List.of(id));
    }

    void delete(Collection<UUID> ids) {
        if (ids.isEmpty()) return;
        synchronized (transactionLock) {
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
                Logging.logger().severe("Failed to delete " + ids.size() + " deletion jobs: " + failure.getMessage());
            }
        }
    }

    void deleteAll() {
        synchronized (transactionLock) {
            if (!database.executeNonQuery("DELETE FROM deletion_jobs")) {
                Logging.logger().severe("Failed to clear persistent deletion jobs");
            }
        }
    }

    List<DeletionJob> loadAll() {
        List<DeletionJob> result = new ArrayList<>();
        synchronized (transactionLock) {
            try (ResultSet rows = database.executeQuery("SELECT * FROM deletion_jobs ORDER BY created_at")) {
                while (rows != null && rows.next()) {
                    result.add(new DeletionJob(
                        UUID.fromString(rows.getString("job_id")), rows.getString("type"),
                        rows.getString("state"), rows.getString("world"), rows.getInt("target_x"),
                        rows.getInt("target_z"), rows.getLong("created_at")));
                }
            } catch (SQLException | IllegalArgumentException failure) {
                Logging.logger().severe("Failed to load deletion jobs: " + failure.getMessage());
            }
        }
        return result;
    }
}
