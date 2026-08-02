package github.freshchromatic.chunkrevive.infrastructure.persistence;

import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import github.freshchromatic.chunkrevive.feature.marking.MarkStore;
import github.freshchromatic.chunkrevive.feature.structure.StructureGroup;
import github.freshchromatic.chunkrevive.feature.structure.StructureStore;
import github.freshchromatic.chunkrevive.feature.reset.DeletionJobStore;
import github.freshchromatic.chunkrevive.feature.reset.DeletionJob;
import github.freshchromatic.chunkrevive.feature.operation.OperationStore;
import github.freshchromatic.chunkrevive.feature.operation.OperationRecord;
import github.freshchromatic.freshlib.database.Database;
import github.freshchromatic.freshlib.util.Logging;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Stable persistence facade used by the application layer.
 *
 * <p>Table-specific SQL lives in focused stores. Cross-table operations remain here so their
 * transaction boundary cannot accidentally be split by callers.</p>
 */
public final class PersistenceRepository implements MarkStore, StructureStore, DeletionJobStore, OperationStore {

    private static final String CREATE_TABLE = """
        CREATE TABLE IF NOT EXISTS marked_chunks (
            id        INTEGER PRIMARY KEY AUTOINCREMENT,
            world     TEXT    NOT NULL,
            chunk_x   INTEGER NOT NULL,
            chunk_z   INTEGER NOT NULL,
            marked_by TEXT    NOT NULL,
            marked_at INTEGER NOT NULL,
            UNIQUE (world, chunk_x, chunk_z)
        )""";

    private static final String ADD_STRUCTURE_GROUP_COLUMN =
        "ALTER TABLE marked_chunks ADD COLUMN structure_group_id TEXT";
    private static final String ADD_BIOME_REGEN_COLUMN =
        "ALTER TABLE marked_chunks ADD COLUMN biome_regen INTEGER NOT NULL DEFAULT 0";

    private static final String CREATE_STRUCTURE_GROUPS_TABLE = """
        CREATE TABLE IF NOT EXISTS structure_groups (
            group_id            TEXT    PRIMARY KEY,
            world               TEXT    NOT NULL,
            structure_id        TEXT    NOT NULL,
            min_chunk_x         INTEGER NOT NULL,
            max_chunk_x         INTEGER NOT NULL,
            min_chunk_z         INTEGER NOT NULL,
            max_chunk_z         INTEGER NOT NULL,
            detected_at         INTEGER NOT NULL,
            next_refresh_at     INTEGER NOT NULL DEFAULT 0,
            protection_ticks    INTEGER NOT NULL DEFAULT 0,
            blocked             INTEGER NOT NULL DEFAULT 0
        )""";

    private static final String CREATE_DELETION_JOBS_TABLE = """
        CREATE TABLE IF NOT EXISTS deletion_jobs (
            job_id      TEXT    PRIMARY KEY,
            type        TEXT    NOT NULL,
            state       TEXT    NOT NULL,
            world       TEXT    NOT NULL,
            target_x    INTEGER NOT NULL,
            target_z    INTEGER NOT NULL,
            created_at  INTEGER NOT NULL,
            UNIQUE (type, world, target_x, target_z)
        )""";
    private static final String CREATE_OPERATIONS_TABLE = """
        CREATE TABLE IF NOT EXISTS operations (
            operation_id TEXT PRIMARY KEY, type TEXT NOT NULL, state TEXT NOT NULL,
            completed INTEGER NOT NULL, total INTEGER NOT NULL,
            created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
            source_plugin TEXT NOT NULL, correlation_id TEXT, failure_code TEXT
        )""";

    private final Database db;
    private final Object txLock = new Object();
    private final SqlMarkStore markedChunks;
    private final SqlDeletionJobStore deletionJobs;
    private final SqlStructureStore structureGroups;

    public PersistenceRepository(Database db) {
        this.db = db;
        this.markedChunks = new SqlMarkStore(db, txLock);
        this.deletionJobs = new SqlDeletionJobStore(db, txLock);
        this.structureGroups = new SqlStructureStore(db);
    }

    public void init() {
        configureSqlite();
        db.executeNonQuery(CREATE_TABLE);
        if (!columnExists("structure_group_id")) {
            db.executeNonQuery(ADD_STRUCTURE_GROUP_COLUMN);
        }
        if (!columnExists("biome_regen")) {
            db.executeNonQuery(ADD_BIOME_REGEN_COLUMN);
        }
        db.executeNonQuery(CREATE_STRUCTURE_GROUPS_TABLE);
        db.executeNonQuery(CREATE_DELETION_JOBS_TABLE);
        db.executeNonQuery(CREATE_OPERATIONS_TABLE);
    }

    private void configureSqlite() {
        try {
            var conn = db.getConnection();
            String driverName = conn.getMetaData().getDriverName();
            if (driverName != null && driverName.toLowerCase().contains("sqlite")) {
                try (var stmt = conn.createStatement()) {
                    stmt.execute("PRAGMA journal_mode=WAL;");
                    stmt.execute("PRAGMA synchronous=NORMAL;");
                }
            }
        } catch (SQLException e) {
            Logging.logger().warning("Failed to configure SQLite PRAGMAs: " + e.getMessage());
        }
    }

    private boolean columnExists(String columnName) {
        try (ResultSet rs = db.getConnection().getMetaData()
                .getColumns(null, null, "marked_chunks", columnName)) {
            return rs != null && rs.next();
        } catch (SQLException e) {
            return false;
        }
    }

    public boolean mark(MarkedChunk chunk) {
        return markedChunks.mark(chunk);
    }

    public void markBatch(Collection<MarkedChunk> chunks) {
        markedChunks.markBatch(chunks);
    }

    public boolean unmark(String world, int cx, int cz) {
        return markedChunks.unmark(world, cx, cz);
    }

    public void unmarkBatch(Collection<MarkedChunk> chunks) {
        markedChunks.unmarkBatch(chunks);
    }

    public List<MarkedChunk> loadAll() {
        return markedChunks.loadAll();
    }

    /** Deletes marks and structure groups atomically; used by {@code /cr resetmark}. */
    public void deleteAllForWorld(String world) {
        synchronized (txLock) {
            try {
                var conn = db.getConnection();
                boolean previousAutoCommit = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try {
                    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM marked_chunks WHERE world=?")) {
                        ps.setString(1, world);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM structure_groups WHERE world=?")) {
                        ps.setString(1, world);
                        ps.executeUpdate();
                    }
                    conn.commit();
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(previousAutoCommit);
                }
            } catch (SQLException e) {
                Logging.logger().severe(
                    "Failed to delete all marks/groups for world " + world + ": " + e.getMessage());
            }
        }
    }

    public void saveDeletionJobs(Collection<DeletionJob> jobs) {
        deletionJobs.save(jobs);
    }

    public void updateDeletionJobState(UUID id, String state) {
        deletionJobs.updateState(id, state);
    }

    public void updateDeletionJobStates(Collection<UUID> ids, String state) {
        deletionJobs.updateStates(ids, state);
    }

    public void deleteDeletionJob(UUID id) {
        deletionJobs.delete(id);
    }

    public void deleteDeletionJobs(Collection<UUID> ids) {
        deletionJobs.delete(ids);
    }

    public void deleteAllDeletionJobs() {
        deletionJobs.deleteAll();
    }

    public List<DeletionJob> loadDeletionJobs() {
        return deletionJobs.loadAll();
    }

    @Override public void save(OperationRecord operation) { writeOperation(operation, true); }
    @Override public void update(OperationRecord operation) { writeOperation(operation, false); }
    private void writeOperation(OperationRecord operation, boolean insert) {
        String sql = insert
            ? "INSERT INTO operations (operation_id,type,state,completed,total,created_at,updated_at,source_plugin,correlation_id,failure_code) VALUES (?,?,?,?,?,?,?,?,?,?)"
            : "UPDATE operations SET state=?,completed=?,total=?,updated_at=?,correlation_id=?,failure_code=? WHERE operation_id=?";
        try (PreparedStatement statement = db.getConnection().prepareStatement(sql)) {
            if (insert) {
                statement.setString(1, operation.id().toString()); statement.setString(2, operation.type());
                statement.setString(3, operation.state()); statement.setInt(4, operation.completed()); statement.setInt(5, operation.total());
                statement.setLong(6, operation.createdAt()); statement.setLong(7, operation.updatedAt()); statement.setString(8, operation.sourcePlugin());
                statement.setString(9, operation.correlationId()); statement.setString(10, operation.failureCode());
            } else {
                statement.setString(1, operation.state()); statement.setInt(2, operation.completed()); statement.setInt(3, operation.total());
                statement.setLong(4, operation.updatedAt()); statement.setString(5, operation.correlationId()); statement.setString(6, operation.failureCode()); statement.setString(7, operation.id().toString());
            }
            statement.executeUpdate();
        } catch (SQLException failure) { Logging.logger().severe("Failed to persist operation " + operation.id() + ": " + failure.getMessage()); }
    }
    @Override public List<OperationRecord> loadOperations() {
        java.util.ArrayList<OperationRecord> result = new java.util.ArrayList<>();
        try (ResultSet rows = db.executeQuery("SELECT * FROM operations")) {
            while (rows != null && rows.next()) result.add(new OperationRecord(UUID.fromString(rows.getString("operation_id")),
                rows.getString("type"), rows.getString("state"), rows.getInt("completed"), rows.getInt("total"),
                rows.getLong("created_at"), rows.getLong("updated_at"), rows.getString("source_plugin"),
                rows.getString("correlation_id"), rows.getString("failure_code")));
        } catch (SQLException failure) { Logging.logger().severe("Failed to load operations: " + failure.getMessage()); }
        return List.copyOf(result);
    }

    public void upsertGroup(StructureGroup group) {
        structureGroups.upsert(group);
    }

    public void updateProtection(UUID groupId, long protectionTicks, boolean blocked) {
        structureGroups.updateProtection(groupId, protectionTicks, blocked);
    }

    public void updateNextRefresh(UUID groupId, long nextRefreshAt) {
        structureGroups.updateNextRefresh(groupId, nextRefreshAt);
    }

    public List<StructureGroup> loadAllGroups() {
        return structureGroups.loadAll();
    }
}
