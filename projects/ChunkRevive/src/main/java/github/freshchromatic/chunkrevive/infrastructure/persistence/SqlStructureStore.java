package github.freshchromatic.chunkrevive.infrastructure.persistence;

import github.freshchromatic.chunkrevive.feature.structure.StructureGroup;
import github.freshchromatic.freshlib.database.Database;
import github.freshchromatic.freshlib.util.Logging;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** SQL persistence dedicated to tracked structure groups. */
final class SqlStructureStore {
    private final Database database;

    SqlStructureStore(Database database) {
        this.database = database;
    }

    void upsert(StructureGroup group) {
        try (PreparedStatement update = database.getConnection().prepareStatement("""
                UPDATE structure_groups SET
                    world=?, structure_id=?, min_chunk_x=?, max_chunk_x=?, min_chunk_z=?, max_chunk_z=?,
                    detected_at=?, next_refresh_at=?, protection_ticks=?, blocked=?
                WHERE group_id=?
                """)) {
            bindMutableFields(update, group);
            update.setString(11, group.groupId().toString());
            if (update.executeUpdate() > 0) return;
        } catch (SQLException failure) {
            Logging.logger().severe("Failed to update structure group: " + failure.getMessage());
            return;
        }

        try (PreparedStatement insert = database.getConnection().prepareStatement("""
                INSERT INTO structure_groups
                    (group_id, world, structure_id, min_chunk_x, max_chunk_x, min_chunk_z, max_chunk_z,
                     detected_at, next_refresh_at, protection_ticks, blocked)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            insert.setString(1, group.groupId().toString());
            insert.setString(2, group.world());
            insert.setString(3, group.structureId());
            insert.setInt(4, group.minChunkX());
            insert.setInt(5, group.maxChunkX());
            insert.setInt(6, group.minChunkZ());
            insert.setInt(7, group.maxChunkZ());
            insert.setLong(8, group.detectedAt());
            insert.setLong(9, group.nextRefreshAt());
            insert.setLong(10, group.protectionTicks());
            insert.setInt(11, group.blocked() ? 1 : 0);
            insert.executeUpdate();
        } catch (SQLException failure) {
            Logging.logger().severe("Failed to insert structure group: " + failure.getMessage());
        }
    }

    void updateProtection(UUID groupId, long ticks, boolean blocked) {
        try (PreparedStatement statement = database.getConnection().prepareStatement(
                "UPDATE structure_groups SET protection_ticks=?, blocked=? WHERE group_id=?")) {
            statement.setLong(1, ticks);
            statement.setInt(2, blocked ? 1 : 0);
            statement.setString(3, groupId.toString());
            statement.executeUpdate();
        } catch (SQLException failure) {
            Logging.logger().severe("Failed to update structure group protection: " + failure.getMessage());
        }
    }

    void updateNextRefresh(UUID groupId, long nextRefreshAt) {
        try (PreparedStatement statement = database.getConnection().prepareStatement(
                "UPDATE structure_groups SET next_refresh_at=? WHERE group_id=?")) {
            statement.setLong(1, nextRefreshAt);
            statement.setString(2, groupId.toString());
            statement.executeUpdate();
        } catch (SQLException failure) {
            Logging.logger().severe("Failed to update structure group next refresh: " + failure.getMessage());
        }
    }

    List<StructureGroup> loadAll() {
        List<StructureGroup> result = new ArrayList<>();
        try (ResultSet rows = database.executeQuery("SELECT * FROM structure_groups")) {
            while (rows != null && rows.next()) {
                result.add(new StructureGroup(
                    UUID.fromString(rows.getString("group_id")), rows.getString("world"),
                    rows.getString("structure_id"), rows.getInt("min_chunk_x"),
                    rows.getInt("max_chunk_x"), rows.getInt("min_chunk_z"),
                    rows.getInt("max_chunk_z"), rows.getLong("detected_at"),
                    rows.getLong("next_refresh_at"), rows.getLong("protection_ticks"),
                    rows.getInt("blocked") != 0));
            }
        } catch (SQLException failure) {
            Logging.logger().severe("Failed to load structure groups: " + failure.getMessage());
        }
        return result;
    }

    private static void bindMutableFields(PreparedStatement statement, StructureGroup group) throws SQLException {
        statement.setString(1, group.world());
        statement.setString(2, group.structureId());
        statement.setInt(3, group.minChunkX());
        statement.setInt(4, group.maxChunkX());
        statement.setInt(5, group.minChunkZ());
        statement.setInt(6, group.maxChunkZ());
        statement.setLong(7, group.detectedAt());
        statement.setLong(8, group.nextRefreshAt());
        statement.setLong(9, group.protectionTicks());
        statement.setInt(10, group.blocked() ? 1 : 0);
    }
}
