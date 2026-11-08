package github.freshchromatic.chunkrevive.infrastructure.persistence;

import github.freshchromatic.chunkrevive.feature.operation.OperationRecord;
import github.freshchromatic.freshlib.database.Database;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class SqlOperationStore {

    private static final Gson GSON = new Gson();

    private final Database                  database;
    private final PersistenceConnectionLock connectionLock;

    SqlOperationStore(Database database, PersistenceConnectionLock connectionLock) {
        this.database = database;
        this.connectionLock = connectionLock;
    }

    void save(OperationRecord operation) {
        writeOperation(operation, true);
    }

    void update(OperationRecord operation) {
        writeOperation(operation, false);
    }

    private void writeOperation(OperationRecord operation, boolean insert) {
        synchronized (connectionLock) {
            connectionLock.ensureOpen();
            String sql = insert
                ? "INSERT INTO operations (operation_id,type,state,completed,total,created_at,updated_at,source_plugin,correlation_id,failure_code,failure_message,failure_details) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)"
                : "UPDATE operations SET state=?,completed=?,total=?,updated_at=?,correlation_id=?,failure_code=?,failure_message=?,failure_details=? WHERE operation_id=?";
            try (PreparedStatement statement = database.getConnection().prepareStatement(sql)) {
                if (insert) {
                    statement.setString(1, operation.id().toString());
                    statement.setString(2, operation.type());
                    statement.setString(3, operation.state());
                    statement.setInt(4, operation.completed());
                    statement.setInt(5, operation.total());
                    statement.setLong(6, operation.createdAt());
                    statement.setLong(7, operation.updatedAt());
                    statement.setString(8, operation.sourcePlugin());
                    statement.setString(9, operation.correlationId());
                    statement.setString(10, operation.failureCode());
                    statement.setString(11, operation.failureMessage());
                    statement.setString(12, GSON.toJson(operation.failureDetails()));
                } else {
                    statement.setString(1, operation.state());
                    statement.setInt(2, operation.completed());
                    statement.setInt(3, operation.total());
                    statement.setLong(4, operation.updatedAt());
                    statement.setString(5, operation.correlationId());
                    statement.setString(6, operation.failureCode());
                    statement.setString(7, operation.failureMessage());
                    statement.setString(8, GSON.toJson(operation.failureDetails()));
                    statement.setString(9, operation.id().toString());
                }
                int updated = statement.executeUpdate();
                if (!insert && updated == 0 && operationExists(operation.id())) {
                    return;
                }
                if (updated == 0) {
                    throw new IllegalStateException("Operation record not found: " + operation.id());
                }
            } catch (SQLException failure) {
                throw new IllegalStateException("Failed to persist operation " + operation.id(), failure);
            }
        }
    }

    private boolean operationExists(UUID id) throws SQLException {
        try (PreparedStatement statement = database.getConnection().prepareStatement(
            "SELECT 1 FROM operations WHERE operation_id=?")) {
            statement.setString(1, id.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    List<OperationRecord> loadOperations() {
        synchronized (connectionLock) {
            connectionLock.ensureOpen();
            ArrayList<OperationRecord> result = new ArrayList<>();
            Map<UUID, List<UUID>> deletionJobIds = loadDeletionJobIds();
            try (PreparedStatement statement = database.getConnection().prepareStatement("SELECT * FROM operations");
                ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID operationId = UUID.fromString(rows.getString("operation_id"));
                    result.add(new OperationRecord(operationId, rows.getString("type"), rows.getString("state"),
                        rows.getInt("completed"), rows.getInt("total"), rows.getLong("created_at"), rows.getLong(
                            "updated_at"), rows.getString("source_plugin"), rows.getString("correlation_id"),
                        rows.getString("failure_code"), rows.getString("failure_message"), parseFailureDetails(
                            rows.getString("failure_details")), deletionJobIds.getOrDefault(operationId, List.of())));
                }
            } catch (SQLException failure) {
                throw new IllegalStateException("Failed to load operations", failure);
            }
            return List.copyOf(result);
        }
    }

    private Map<UUID, List<UUID>> loadDeletionJobIds() {
        Map<UUID, List<UUID>> result = new HashMap<>();
        try (PreparedStatement statement = database.getConnection().prepareStatement(
            "SELECT operation_id, job_id FROM operation_deletion_jobs ORDER BY operation_id, job_id");
            ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                UUID operationId = UUID.fromString(rows.getString("operation_id"));
                UUID jobId = UUID.fromString(rows.getString("job_id"));
                result.computeIfAbsent(operationId, ignored -> new ArrayList<>()).add(jobId);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Failed to load operation deletion-job associations", failure);
        }
        return result;
    }

    private static Map<String, String> parseFailureDetails(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Map<String, String> details = GSON.fromJson(json, new TypeToken<Map<String, String>>() {
        }.getType());
        return details == null ? Map.of() : Map.copyOf(details);
    }

}
