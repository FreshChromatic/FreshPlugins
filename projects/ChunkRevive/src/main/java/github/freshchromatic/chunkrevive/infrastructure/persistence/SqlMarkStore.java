package github.freshchromatic.chunkrevive.infrastructure.persistence;

import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import github.freshchromatic.freshlib.database.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** SQL persistence dedicated to marked chunks. */
final class SqlMarkStore {

    private static final String INSERT = """
        INSERT OR IGNORE INTO marked_chunks
            (world, chunk_x, chunk_z, marked_by, marked_at, structure_group_id, biome_regen)
        VALUES (?,?,?,?,?,?,?)
        """;

    private final Database                  database;
    private final PersistenceConnectionLock connectionLock;
    private final String                    insertSql;

    SqlMarkStore(Database database, PersistenceConnectionLock connectionLock, boolean mysql) {
        this.database = database;
        this.connectionLock = connectionLock;
        this.insertSql = mysql ? INSERT.replace("INSERT OR IGNORE", "INSERT") + " ON DUPLICATE KEY UPDATE id=id"
            : INSERT;
    }

    boolean mark(MarkedChunk chunk) {
        synchronized (connectionLock) {
            connectionLock.ensureOpen();
            try (PreparedStatement statement = database.getConnection().prepareStatement(insertSql)) {
                bind(statement, chunk);
                return statement.executeUpdate() > 0;
            } catch (SQLException failure) {
                throw new IllegalStateException("Failed to mark chunk", failure);
            }
        }
    }

    void markBatch(Collection<MarkedChunk> chunks) {
        if (chunks.isEmpty()) {
            return;
        }
        synchronized (connectionLock) {
            connectionLock.ensureOpen();
            try {
                var connection = database.getConnection();
                boolean autoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
                    for (MarkedChunk chunk : chunks) {
                        bind(statement, chunk);
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
                throw new IllegalStateException("Failed to mark chunk batch", failure);
            }
        }
    }

    boolean unmark(String world, int chunkX, int chunkZ) {
        synchronized (connectionLock) {
            connectionLock.ensureOpen();
            try (PreparedStatement statement = database.getConnection().prepareStatement(
                "DELETE FROM marked_chunks WHERE world=? AND chunk_x=? AND chunk_z=?")) {
                statement.setString(1, world);
                statement.setInt(2, chunkX);
                statement.setInt(3, chunkZ);
                return statement.executeUpdate() > 0;
            } catch (SQLException failure) {
                throw new IllegalStateException("Failed to unmark chunk", failure);
            }
        }
    }

    void unmarkBatch(Collection<MarkedChunk> chunks) {
        if (chunks.isEmpty()) {
            return;
        }
        synchronized (connectionLock) {
            connectionLock.ensureOpen();
            try {
                var connection = database.getConnection();
                boolean autoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM marked_chunks WHERE world=? AND chunk_x=? AND chunk_z=?")) {
                    for (MarkedChunk chunk : chunks) {
                        statement.setString(1, chunk.world());
                        statement.setInt(2, chunk.cx());
                        statement.setInt(3, chunk.cz());
                        statement.addBatch();
                    }
                    int[] deleted = statement.executeBatch();
                    for (int count : deleted) {
                        if (count == 0) {
                            throw new SQLException("Marked chunk record not found");
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
                throw new IllegalStateException("Failed to unmark chunk batch", failure);
            }
        }
    }

    List<MarkedChunk> loadAll() {
        synchronized (connectionLock) {
            connectionLock.ensureOpen();
            List<MarkedChunk> result = new ArrayList<>();
            try (PreparedStatement statement = database.getConnection().prepareStatement("SELECT * FROM marked_chunks");
                ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String groupId = rows.getString("structure_group_id");
                    result.add(new MarkedChunk(rows.getString("world"), rows.getInt("chunk_x"), rows.getInt("chunk_z"),
                        UUID.fromString(rows.getString("marked_by")), rows.getLong("marked_at"), groupId != null
                            ? UUID.fromString(groupId)
                            : null, rows.getInt("biome_regen") != 0));
                }
            } catch (SQLException | IllegalArgumentException failure) {
                throw new IllegalStateException("Failed to load marked chunks", failure);
            }
            return result;
        }
    }

    private static void bind(PreparedStatement statement, MarkedChunk chunk) throws SQLException {
        statement.setString(1, chunk.world());
        statement.setInt(2, chunk.cx());
        statement.setInt(3, chunk.cz());
        statement.setString(4, chunk.markedBy().toString());
        statement.setLong(5, chunk.markedAt());
        statement.setString(6, chunk.structureGroupId() == null ? null : chunk.structureGroupId().toString());
        statement.setInt(7, chunk.biomeRegen() ? 1 : 0);
    }
}
