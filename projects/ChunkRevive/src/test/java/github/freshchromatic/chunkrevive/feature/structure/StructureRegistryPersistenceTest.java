package github.freshchromatic.chunkrevive.feature.structure;

import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.infrastructure.persistence.PersistenceRepository;
import github.freshchromatic.chunkrevive.integration.protection.LandProtection;
import github.freshchromatic.freshlib.database.SqliteDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class StructureRegistryPersistenceTest {

    @Test
    void allGroupsReturnsAnImmutableSnapshot() {
        var store = mock(StructureStore.class);
        var original = group();
        when(store.loadAllGroups()).thenReturn(List.of(original));
        var registry = new StructureRegistry(store, new PluginConfig(), mock(LandProtection.class));
        registry.loadFromDatabase();
        var groups = registry.getAllGroups();

        registry.removeGroupsForWorld(original.world());

        assertEquals(List.of(original), List.copyOf(groups));
        assertThrows(UnsupportedOperationException.class, groups::clear);
    }

    @Test
    void failedGroupUpdateRestoresPersistedState() {
        var store = mock(StructureStore.class);
        var original = group();
        when(store.loadAllGroups()).thenReturn(List.of(original));
        doThrow(new IllegalStateException("database failure"))
            .when(store)
            .updateProtection(original.groupId(), 288000L, true);
        var registry = new StructureRegistry(store, new PluginConfig(), mock(LandProtection.class));
        registry.loadFromDatabase();

        assertThrows(CompletionException.class, () -> registry.block(original.groupId()).join());

        assertEquals(original, registry.getGroup(original.groupId()).orElseThrow());
    }

    @Test
    void failedNewGroupWriteRemovesUnpersistedGroup() {
        var store = mock(StructureStore.class);
        doThrow(new IllegalStateException("database failure")).when(store).upsertGroup(any());
        var registry = new StructureRegistry(store, new PluginConfig(), mock(LandProtection.class));

        UUID groupId = registry.findOrCreateGroup("world", "minecraft:village", 0, 1, 0, 1);

        assertThrows(CompletionException.class, () -> registry.persisted(groupId).join());
        assertTrue(registry.getGroup(groupId).isEmpty());
    }

    @Test
    void worldResetWaitsForPendingGroupWritesAndBlocksNewChanges() throws Exception {
        var store = mock(StructureStore.class);
        var original = group();
        when(store.loadAllGroups()).thenReturn(List.of(original));
        var writeStarted = new CountDownLatch(1);
        var releaseWrite = new CountDownLatch(1);
        doAnswer(invocation -> {
            writeStarted.countDown();
            releaseWrite.await();
            return null;
        })
            .when(store)
            .updateProtection(original.groupId(), 288000L, true);
        var registry = new StructureRegistry(store, new PluginConfig(), mock(LandProtection.class));
        registry.loadFromDatabase();

        var mutation = registry.block(original.groupId());
        assertTrue(writeStarted.await(1, TimeUnit.SECONDS));
        var reset = registry.beginWorldReset("world");

        assertFalse(reset.isDone());
        releaseWrite.countDown();
        mutation.join();
        reset.join();
        assertThrows(CompletionException.class, () -> registry.block(original.groupId()).join());
        registry.finishWorldReset("world");
    }

    @Test
    void sqliteFailureRestoresPersistedAndInMemoryGroup(@TempDir Path directory) throws SQLException {
        var database = new SqliteDatabase(directory.resolve("structure-groups.db").toString());
        assertTrue(database.connect());
        try {
            var repository = new PersistenceRepository(database);
            repository.init();
            var registry = new StructureRegistry(repository, new PluginConfig(), mock(LandProtection.class));
            UUID groupId = registry.findOrCreateGroup("world", "minecraft:village", 0, 1, 0, 1);
            registry.persisted(groupId).join();
            var original = registry.getGroup(groupId).orElseThrow();
            try (var statement = database.getConnection().createStatement()) {
                statement.execute("CREATE TRIGGER reject_group_protection BEFORE UPDATE OF blocked "
                    + "ON structure_groups BEGIN SELECT RAISE(ABORT, 'rejected'); END");
            }

            assertThrows(CompletionException.class, () -> registry.block(groupId).join());

            assertEquals(original, registry.getGroup(groupId).orElseThrow());
            assertEquals(List.of(original), repository.loadAllGroups());
        } finally {
            database.close();
        }
    }

    private static StructureGroup group() {
        return new StructureGroup(UUID.randomUUID(), "world", "minecraft:village", 0, 1, 0, 1, 1, 2, 0, false);
    }
}
