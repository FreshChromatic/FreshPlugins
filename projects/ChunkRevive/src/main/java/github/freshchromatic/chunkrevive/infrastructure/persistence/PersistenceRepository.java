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
import github.freshchromatic.freshlib.database.MySqlDatabase;
import github.freshchromatic.freshlib.database.SqliteDatabase;
import github.freshchromatic.freshlib.util.Logging;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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

    private static final String ADD_STRUCTURE_GROUP_COLUMN           = "ALTER TABLE marked_chunks ADD COLUMN structure_group_id TEXT";
    private static final String ADD_BIOME_REGEN_COLUMN               = "ALTER TABLE marked_chunks ADD COLUMN biome_regen INTEGER NOT NULL DEFAULT 0";
    private static final String ADD_OPERATION_FAILURE_MESSAGE_COLUMN = "ALTER TABLE operations ADD COLUMN failure_message TEXT";
    private static final String ADD_OPERATION_FAILURE_DETAILS_COLUMN = "ALTER TABLE operations ADD COLUMN failure_details TEXT";

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

    private static final String CREATE_DELETION_JOBS_TABLE           = """
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
    private static final String CREATE_OPERATIONS_TABLE              = """
        CREATE TABLE IF NOT EXISTS operations (
            operation_id TEXT PRIMARY KEY, type TEXT NOT NULL, state TEXT NOT NULL,
            completed INTEGER NOT NULL, total INTEGER NOT NULL,
            created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
            source_plugin TEXT NOT NULL, correlation_id TEXT, failure_code TEXT,
            failure_message TEXT, failure_details TEXT
        )""";
    private static final String CREATE_OPERATION_DELETION_JOBS_TABLE = """
        CREATE TABLE IF NOT EXISTS operation_deletion_jobs (
            operation_id TEXT NOT NULL,
            job_id       TEXT NOT NULL,
            PRIMARY KEY (operation_id, job_id)
        )""";

    private final Database                  db;
    private final PersistenceConnectionLock connectionLock = new PersistenceConnectionLock();
    private final SqlMarkStore              markedChunks;
    private final SqlDeletionJobStore       deletionJobs;
    private final SqlStructureStore         structureGroups;
    private final SqlOperationStore         operations;
    private final boolean                   mysql;

    public PersistenceRepository(Database db) {
        this.db = db;
        this.mysql = db instanceof MySqlDatabase && !(db instanceof SqliteDatabase);
        this.markedChunks = new SqlMarkStore(db, connectionLock, mysql);
        this.deletionJobs = new SqlDeletionJobStore(db, connectionLock, mysql);
        this.structureGroups = new SqlStructureStore(db, connectionLock);
        this.operations = new SqlOperationStore(db, connectionLock);
    }

    public boolean close() {
        return connectionLock.close(db);
    }

    public CompletableFuture<Boolean> closeAsync() {
        return CompletableFuture.supplyAsync(this::close, command ->
            Thread.ofVirtual()
                .name("chunkrevive-database-close")
                .start(command));
    }

    public void init() {
        configureSqlite();
        executeSchema(CREATE_TABLE);
        if (!columnExists("marked_chunks", "structure_group_id")) {
            executeSchema(ADD_STRUCTURE_GROUP_COLUMN);
        }
        if (!columnExists("marked_chunks", "biome_regen")) {
            executeSchema(ADD_BIOME_REGEN_COLUMN);
        }
        executeSchema(CREATE_STRUCTURE_GROUPS_TABLE);
        executeSchema(CREATE_DELETION_JOBS_TABLE);
        executeSchema(CREATE_OPERATIONS_TABLE);
        if (!columnExists("operations", "failure_message")) {
            executeSchema(ADD_OPERATION_FAILURE_MESSAGE_COLUMN);
        }
        if (!columnExists("operations", "failure_details")) {
            executeSchema(ADD_OPERATION_FAILURE_DETAILS_COLUMN);
        }
        executeSchema(CREATE_OPERATION_DELETION_JOBS_TABLE);
    }

    private void executeSchema(String sql) {
        if (mysql) {
            sql = sql.replace("INTEGER PRIMARY KEY AUTOINCREMENT", "BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY")
                .replace("INTEGER", "BIGINT")
                .replaceAll(
                    "\\b(world|type|job_id|operation_id|group_id)(\\s+)TEXT",
                    "$1$2VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
        }
        if (!db.executeNonQuery(sql)) {
            throw new IllegalStateException("Failed to initialize database schema");
        }
    }

    private void configureSqlite() {
        try {
            var conn = db.getConnection();
            String driverName = conn.getMetaData().getDriverName();
            if (driverName != null && driverName.toLowerCase(Locale.ROOT).contains("sqlite")) {
                try (var stmt = conn.createStatement()) {
                    stmt.execute("PRAGMA journal_mode=WAL;");
                    stmt.execute("PRAGMA synchronous=NORMAL;");
                }
            }
        } catch (SQLException e) {
            Logging.logger().warning("Failed to configure SQLite PRAGMAs: " + e.getMessage());
        }
    }

    private boolean columnExists(String tableName, String columnName) {
        try (ResultSet rs = db.getConnection().getMetaData().getColumns(null, null, tableName, columnName)) {
            return rs != null && rs.next();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to inspect marked chunk schema", e);
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
        synchronized (connectionLock) {
            connectionLock.ensureOpen();
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
                throw new IllegalStateException("Failed to delete marks and groups for world " + world, e);
            }
        }
    }

    public void saveDeletionJobs(Collection<DeletionJob> jobs) {
        deletionJobs.save(jobs);
    }

    @Override
    public void saveDeletionJobs(Collection<DeletionJob> jobs, UUID operationId) {
        deletionJobs.save(jobs, operationId);
    }

    @Override
    public void associateOperation(UUID operationId, UUID deletionJobId) {
        deletionJobs.associateOperation(operationId, deletionJobId);
    }

    @Override
    public void associateOperation(UUID operationId, Collection<UUID> deletionJobIds) {
        deletionJobs.associateOperation(operationId, deletionJobIds);
    }

    @Override
    public void saveDeletionJobs(Collection<DeletionJob> jobs, UUID operationId, Collection<UUID> associatedJobIds) {
        deletionJobs.save(jobs, operationId, associatedJobIds);
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

    @Override
    public void save(OperationRecord operation) {
        operations.save(operation);
    }

    @Override
    public void update(OperationRecord operation) {
        operations.update(operation);
    }

    @Override
    public List<OperationRecord> loadOperations() {
        return operations.loadOperations();
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
