package github.freshchromatic.chunkrevive.feature.marking;

import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.feature.structure.StructureRegistry;
import github.freshchromatic.chunkrevive.infrastructure.persistence.PersistenceRepository;
import github.freshchromatic.freshlib.database.SqliteDatabase;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MarkRegistryPersistenceTest {

    @Test
    void failedDurableBatchRemovesNewMarksFromMemory() {
        var store = mock(MarkStore.class);
        doThrow(new IllegalStateException("database failure")).when(store).markBatch(any());
        var registry = new MarkRegistry(store, null, null, null, null);
        var actor = UUID.randomUUID();
        var chunks = List.of(new MarkedChunk("world", 1, 0, actor, 1), new MarkedChunk("world", 2, 0, actor, 1));

        assertThrows(CompletionException.class, () -> registry.markResolvedPersisted(chunks).join());
        assertFalse(registry.isMarked("world", 1, 0));
        assertFalse(registry.isMarked("world", 2, 0));
    }

    @Test
    void markedChunksReturnsAnImmutableSnapshot() {
        var store = mock(MarkStore.class);
        var existing = new MarkedChunk("world", 1, 0, UUID.randomUUID(), 1);
        when(store.loadAll()).thenReturn(List.of(existing));
        var registry = new MarkRegistry(store, null, null, null, null);
        registry.loadFromDatabase();
        var snapshot = registry.getMarkedChunks();
        var added = new MarkedChunk("world", 2, 0, UUID.randomUUID(), 2);

        registry.markChunksPersisted(List.of(added)).join();

        assertEquals(List.of(existing), List.copyOf(snapshot));
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
        assertEquals(List.of(existing, added), registry.getMarkedChunks()
            .stream()
            .sorted(Comparator.comparingInt(MarkedChunk::cx))
            .toList());
    }

    @Test
    void duplicateMarkWaitsForPendingWriteAndReceivesItsFailure() throws Exception {
        var store = mock(MarkStore.class);
        var writeStarted = new CountDownLatch(1);
        var releaseWrite = new CountDownLatch(1);
        doAnswer(invocation -> {
            writeStarted.countDown();
            if (!releaseWrite.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to fail the mark");
            }
            throw new IllegalStateException("database unavailable");
        })
            .when(store)
            .markBatch(any());
        var registry = new MarkRegistry(store, null, null, null, null);
        var chunk = new MarkedChunk("world", 1, 0, UUID.randomUUID(), 1);

        var firstWrite = registry.markChunksPersisted(List.of(chunk));
        assertTrue(writeStarted.await(5, TimeUnit.SECONDS));
        var duplicateWrite = registry.markChunksPersisted(List.of(chunk));

        assertFalse(duplicateWrite.isDone());
        releaseWrite.countDown();

        assertThrows(CompletionException.class, firstWrite::join);
        assertThrows(CompletionException.class, duplicateWrite::join);
        assertFalse(registry.isMarked("world", 1, 0));
    }

    @Test
    void failedDurableRemovalRestoresMark() {
        var store = mock(MarkStore.class);
        var chunk = new MarkedChunk("world", 1, 0, UUID.randomUUID(), 1);
        when(store.loadAll()).thenReturn(List.of(chunk));
        when(store.unmark("world", 1, 0)).thenThrow(new IllegalStateException("database failure"));
        var registry = new MarkRegistry(store, null, null, null, null);
        registry.loadFromDatabase();

        assertThrows(CompletionException.class, () -> registry.unmarkPersisted("world", 1, 0).join());
        assertTrue(registry.isMarked("world", 1, 0));
    }

    @Test
    void reMarkWaitsForAnEarlierSingleChunkRemoval() throws Exception {
        var store = mock(MarkStore.class);
        var initial = new MarkedChunk("world", 1, 0, UUID.randomUUID(), 1);
        when(store.loadAll()).thenReturn(List.of(initial));
        var persisted = new AtomicBoolean(true);
        var unmarkStarted = new CountDownLatch(1);
        var allowUnmark = new CountDownLatch(1);
        var markPersisted = new CountDownLatch(1);
        doAnswer(invocation -> {
            unmarkStarted.countDown();
            if (!allowUnmark.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to finish the unmark");
            }
            persisted.set(false);
            return true;
        })
            .when(store)
            .unmark("world", 1, 0);
        doAnswer(invocation -> {
            persisted.set(true);
            markPersisted.countDown();
            return null;
        })
            .when(store)
            .markBatch(any());
        var registry = new MarkRegistry(store, null, null, null, null);
        registry.loadFromDatabase();

        var remove = registry.unmarkPersisted("world", 1, 0);
        assertTrue(unmarkStarted.await(5, TimeUnit.SECONDS));
        var replacement = new MarkedChunk("world", 1, 0, UUID.randomUUID(), 2);
        var add = registry.markChunksPersisted(List.of(replacement));

        try {
            assertFalse(markPersisted.await(250, TimeUnit.MILLISECONDS));
        } finally {
            allowUnmark.countDown();
        }
        remove.join();
        add.join();

        assertTrue(persisted.get());
        assertTrue(registry.isMarked("world", 1, 0));
    }

    @Test
    void reMarkWaitsForAnEarlierBatchRemoval() throws Exception {
        var store = mock(MarkStore.class);
        var initial = new MarkedChunk("world", 1, 0, UUID.randomUUID(), 1);
        when(store.loadAll()).thenReturn(List.of(initial));
        var persisted = new AtomicBoolean(true);
        var unmarkStarted = new CountDownLatch(1);
        var allowUnmark = new CountDownLatch(1);
        var markPersisted = new CountDownLatch(1);
        doAnswer(invocation -> {
            unmarkStarted.countDown();
            if (!allowUnmark.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to finish the batch unmark");
            }
            persisted.set(false);
            return null;
        })
            .when(store)
            .unmarkBatch(any());
        doAnswer(invocation -> {
            persisted.set(true);
            markPersisted.countDown();
            return null;
        })
            .when(store)
            .markBatch(any());
        var registry = new MarkRegistry(store, null, null, null, null);
        registry.loadFromDatabase();

        var remove = registry.unmarkChunksPersisted(List.of(initial));
        assertTrue(unmarkStarted.await(5, TimeUnit.SECONDS));
        var replacement = new MarkedChunk("world", 1, 0, UUID.randomUUID(), 2);
        var add = registry.markChunksPersisted(List.of(replacement));

        try {
            assertFalse(add.isDone());
            assertFalse(markPersisted.await(250, TimeUnit.MILLISECONDS));
        } finally {
            allowUnmark.countDown();
        }
        remove.join();
        add.join();

        assertTrue(persisted.get());
        assertTrue(registry.isMarked("world", 1, 0));
    }

    @Test
    void unmarkAfterFailedMarkDoesNotRestoreAnUnpersistedMark() throws Exception {
        var store = mock(MarkStore.class);
        var markStarted = new CountDownLatch(1);
        var allowMarkFailure = new CountDownLatch(1);
        doAnswer(invocation -> {
            markStarted.countDown();
            if (!allowMarkFailure.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to fail the mark");
            }
            throw new IllegalStateException("database unavailable");
        })
            .when(store)
            .markBatch(any());
        var registry = new MarkRegistry(store, null, null, null, null);
        var chunk = new MarkedChunk("world", 1, 0, UUID.randomUUID(), 1);

        var mark = registry.markChunksPersisted(List.of(chunk));
        assertTrue(markStarted.await(5, TimeUnit.SECONDS));
        var unmark = registry.unmarkPersisted("world", 1, 0);
        allowMarkFailure.countDown();

        assertThrows(CompletionException.class, mark::join);
        assertTrue(unmark.join().isEmpty());
        assertFalse(registry.isMarked("world", 1, 0));
        verify(store, never()).unmark("world", 1, 0);
    }

    @Test
    void failedRemovalAfterDeletionKeepsMarkAndDisplay() {
        var store = mock(MarkStore.class);
        var chunk = new MarkedChunk("world", 1, 0, UUID.randomUUID(), 1);
        when(store.loadAll()).thenReturn(List.of(chunk));
        doThrow(new IllegalStateException("database failure")).when(store).unmarkBatch(any());
        var display = mock(MarkDisplay.class);
        var registry = new MarkRegistry(store, null, null, null, null);
        registry.loadFromDatabase();
        registry.setMarkDisplay(display);

        assertThrows(IllegalStateException.class, () -> registry.unmarkAfterDeletion(List.of(chunk)));
        assertTrue(registry.isMarked("world", 1, 0));
        verifyNoInteractions(display);
    }

    @Test
    void successfulRemovalAfterDeletionUpdatesMarkAndDisplay() {
        var store = mock(MarkStore.class);
        var chunk = new MarkedChunk("world", 1, 0, UUID.randomUUID(), 1);
        when(store.loadAll()).thenReturn(List.of(chunk));
        var display = mock(MarkDisplay.class);
        var registry = new MarkRegistry(store, null, null, null, null);
        registry.loadFromDatabase();
        registry.setMarkDisplay(display);

        registry.unmarkAfterDeletion(List.of(chunk));

        verify(store).unmarkBatch(List.of(chunk));
        assertFalse(registry.isMarked("world", 1, 0));
        verify(display).onChunkUnmarked(chunk);
    }

    @Test
    void failedWorldResetKeepsMarkAndDisplay() {
        var store = mock(MarkStore.class);
        var chunk = new MarkedChunk("world", 1, 0, UUID.randomUUID(), 1);
        when(store.loadAll()).thenReturn(List.of(chunk));
        doThrow(new IllegalStateException("database failure")).when(store).deleteAllForWorld("world");
        var display = mock(MarkDisplay.class);
        var registry = new MarkRegistry(store, null, null, null, null);
        registry.loadFromDatabase();
        registry.setMarkDisplay(display);

        assertThrows(CompletionException.class, () -> registry.resetMarkForWorld("world").join());
        assertTrue(registry.isMarked("world", 1, 0));
        verifyNoInteractions(display);
    }

    @Test
    void successfulWorldResetClearsMarksAfterStorage() {
        var store = mock(MarkStore.class);
        var chunk = new MarkedChunk("world", 1, 0, UUID.randomUUID(), 1);
        when(store.loadAll()).thenReturn(List.of(chunk));
        var display = mock(MarkDisplay.class);
        var registry = new MarkRegistry(store, null, null, null, null);
        registry.loadFromDatabase();
        registry.setMarkDisplay(display);
        doAnswer(invocation -> {
            assertTrue(registry.isMarked("world", 1, 0));
            verifyNoInteractions(display);
            return null;
        })
            .when(store)
            .deleteAllForWorld("world");

        var result = registry.resetMarkForWorld("world").join();

        assertEquals(1, result.chunkCount());
        assertFalse(registry.isMarked("world", 1, 0));
        verify(display).onChunkUnmarked(chunk);
    }

    @Test
    void failedBatchRemovalKeepsMarksAndDisplay() {
        var store = mock(MarkStore.class);
        var chunk = new MarkedChunk("world", 1, 0, UUID.randomUUID(), 1);
        when(store.loadAll()).thenReturn(List.of(chunk));
        doThrow(new IllegalStateException("database failure")).when(store).unmarkBatch(List.of(chunk));
        var display = mock(MarkDisplay.class);
        var registry = new MarkRegistry(store, null, null, null, null);
        registry.loadFromDatabase();
        registry.setMarkDisplay(display);

        assertThrows(CompletionException.class, () -> registry.unmarkChunksPersisted(List.of(chunk)).join());

        assertTrue(registry.isMarked("world", 1, 0));
        verifyNoInteractions(display);
    }

    @Test
    void successfulBatchRemovalUpdatesStateAndDisplayAfterStorage() {
        var store = mock(MarkStore.class);
        var chunk = new MarkedChunk("world", 1, 0, UUID.randomUUID(), 1);
        when(store.loadAll()).thenReturn(List.of(chunk));
        var display = mock(MarkDisplay.class);
        var registry = new MarkRegistry(store, null, null, null, null);
        registry.loadFromDatabase();
        registry.setMarkDisplay(display);
        doAnswer(invocation -> {
            assertTrue(registry.isMarked("world", 1, 0));
            verifyNoInteractions(display);
            return null;
        })
            .when(store)
            .unmarkBatch(List.of(chunk));

        assertEquals(List.of(chunk), registry.unmarkChunksPersisted(List.of(chunk)).join());

        assertFalse(registry.isMarked("world", 1, 0));
        verify(display).onChunkUnmarked(chunk);
    }

    @Test
    void sqliteMarkFailureLeavesDatabaseAndMemoryUnchanged(@TempDir Path directory) throws SQLException {
        var database = new SqliteDatabase(directory.resolve("mark-failure.db").toString());
        assertTrue(database.connect());
        try {
            var repository = new PersistenceRepository(database);
            repository.init();
            try (var statement = database.getConnection().createStatement()) {
                statement.execute("CREATE TRIGGER reject_mark BEFORE INSERT ON marked_chunks "
                    + "BEGIN SELECT RAISE(ABORT, 'rejected'); END");
            }
            var registry = new MarkRegistry(repository, null, new PluginConfig(), null, null);
            var chunk = new MarkedChunk("world", 1, 0, UUID.randomUUID(), 1);

            assertThrows(CompletionException.class, () -> registry.markChunksPersisted(List.of(chunk)).join());

            assertFalse(registry.isMarked("world", 1, 0));
            assertTrue(repository.loadAll().isEmpty());
        } finally {
            database.close();
        }
    }

    @Test
    void sqliteUnmarkFailureLeavesDatabaseAndMemoryUnchanged(@TempDir Path directory) throws SQLException {
        var database = new SqliteDatabase(directory.resolve("unmark-failure.db").toString());
        assertTrue(database.connect());
        try {
            var repository = new PersistenceRepository(database);
            repository.init();
            var chunk = new MarkedChunk("world", 1, 0, UUID.randomUUID(), 1);
            repository.mark(chunk);
            var registry = new MarkRegistry(repository, null, new PluginConfig(), null, null);
            registry.loadFromDatabase();
            try (var statement = database.getConnection().createStatement()) {
                statement.execute("CREATE TRIGGER reject_unmark BEFORE DELETE ON marked_chunks "
                    + "BEGIN SELECT RAISE(ABORT, 'rejected'); END");
            }

            assertThrows(CompletionException.class, () -> registry.unmarkPersisted("world", 1, 0).join());

            assertTrue(registry.isMarked("world", 1, 0));
            assertEquals(List.of(chunk), repository.loadAll());
        } finally {
            database.close();
        }
    }

    @Test
    void regenerationWaitsForStructureRefreshPersistence() {
        var store = mock(MarkStore.class);
        var groupId = UUID.randomUUID();
        var chunk = new MarkedChunk("world", 1, 0, UUID.randomUUID(), 1, groupId);
        when(store.loadAll()).thenReturn(List.of(chunk));
        var structureRegistry = mock(StructureRegistry.class);
        var refreshWrite = new CompletableFuture<Void>();
        when(structureRegistry.scheduleNextRefresh(groupId)).thenReturn(refreshWrite);
        var registry = new MarkRegistry(store, null, new PluginConfig(), null, null);
        registry.setStructureRegistry(structureRegistry);
        registry.loadFromDatabase();

        var completion = registry.onChunkRegenComplete("world", 1, 0, Runnable::run);

        assertFalse(completion.isDone());
        refreshWrite.complete(null);
        completion.join();
    }

    @Test
    void batchRegenerationWaitsForEachStructureGroupOnce() {
        var store = mock(MarkStore.class);
        var firstGroup = UUID.randomUUID();
        var secondGroup = UUID.randomUUID();
        var chunks = List.of(new MarkedChunk("world", 1, 0, UUID.randomUUID(), 1, firstGroup), new MarkedChunk("world",
            2, 0, UUID.randomUUID(), 1, firstGroup), new MarkedChunk("world", 3, 0, UUID.randomUUID(), 1, secondGroup));
        when(store.loadAll()).thenReturn(chunks);
        var structureRegistry = mock(StructureRegistry.class);
        var firstWrite = new CompletableFuture<Void>();
        var secondWrite = new CompletableFuture<Void>();
        when(structureRegistry.scheduleNextRefresh(firstGroup)).thenReturn(firstWrite);
        when(structureRegistry.scheduleNextRefresh(secondGroup)).thenReturn(secondWrite);
        var registry = new MarkRegistry(store, null, new PluginConfig(), null, null);
        registry.setStructureRegistry(structureRegistry);
        registry.loadFromDatabase();

        var completion = registry.onChunksRegenComplete(chunks, Runnable::run);

        assertFalse(completion.isDone());
        firstWrite.complete(null);
        assertFalse(completion.isDone());
        secondWrite.complete(null);
        completion.join();
        verify(structureRegistry).scheduleNextRefresh(firstGroup);
        verify(structureRegistry).scheduleNextRefresh(secondGroup);
    }

    @Test
    void worldResetWaitsForPendingMarkWriteAndRejectsNewMarks() throws Exception {
        var store = mock(MarkStore.class);
        var chunk = new MarkedChunk("world", 1, 0, UUID.randomUUID(), 1);
        var writeStarted = new CountDownLatch(1);
        var releaseWrite = new CountDownLatch(1);
        doAnswer(invocation -> {
            writeStarted.countDown();
            releaseWrite.await();
            return null;
        })
            .when(store)
            .markBatch(List.of(chunk));
        var registry = new MarkRegistry(store, null, null, null, null);

        var markWrite = registry.markChunksPersisted(List.of(chunk));
        assertTrue(writeStarted.await(1, TimeUnit.SECONDS));
        var reset = registry.resetMarkForWorld("world");

        assertFalse(reset.isDone());
        assertThrows(CompletionException.class, () -> registry.markChunksPersisted(List.of(new MarkedChunk("world", 2,
            0, UUID.randomUUID(), 1))).join());
        releaseWrite.countDown();
        markWrite.join();
        assertEquals(1, reset.join().chunkCount());
        assertFalse(registry.isMarked("world", 1, 0));
        var order = inOrder(store);
        order.verify(store).markBatch(List.of(chunk));
        order.verify(store).deleteAllForWorld("world");
    }

    @Test
    void worldResetWaitsForStructureRegistryBeforeDeletingDatabaseRows() {
        var store = mock(MarkStore.class);
        var structureRegistry = mock(StructureRegistry.class);
        var groupWrites = new CompletableFuture<Void>();
        when(structureRegistry.beginWorldReset("world")).thenReturn(groupWrites);
        var registry = new MarkRegistry(store, null, null, null, null);
        registry.setStructureRegistry(structureRegistry);

        var reset = registry.resetMarkForWorld("world");

        assertFalse(reset.isDone());
        verify(store, never()).deleteAllForWorld("world");
        groupWrites.complete(null);
        reset.join();
        verify(store).deleteAllForWorld("world");
        verify(structureRegistry).finishWorldReset("world");
    }

    @Test
    void regenerationCompletionWaitsForWorldResetBeforePersistingCompletion() throws Exception {
        var store = mock(MarkStore.class);
        var chunk = new MarkedChunk("world", 1, 0, UUID.randomUUID(), 1);
        when(store.loadAll()).thenReturn(List.of(chunk));
        var deletionStarted = new CountDownLatch(1);
        var releaseDeletion = new CountDownLatch(1);
        doAnswer(invocation -> {
            deletionStarted.countDown();
            if (!releaseDeletion.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to finish the world reset");
            }
            return null;
        })
            .when(store)
            .deleteAllForWorld("world");
        var config = new PluginConfig();
        config.regen.autoUnmarkAfterRegen = true;
        var registry = new MarkRegistry(store, null, config, null, null);
        registry.loadFromDatabase();

        var reset = registry.resetMarkForWorld("world");
        assertTrue(deletionStarted.await(5, TimeUnit.SECONDS));
        var completion = registry.onChunksRegenComplete(List.of(chunk), Runnable::run);

        assertFalse(completion.isDone());
        releaseDeletion.countDown();
        reset.join();
        completion.join();

        assertFalse(registry.isMarked("world", 1, 0));
        verify(store, never()).unmarkBatch(any());
    }

    @Test
    void regenerationCompletionRetriesWhenWorldResetFails() throws Exception {
        var store = mock(MarkStore.class);
        var chunk = new MarkedChunk("world", 1, 0, UUID.randomUUID(), 1);
        when(store.loadAll()).thenReturn(List.of(chunk));
        var deletionStarted = new CountDownLatch(1);
        var releaseDeletion = new CountDownLatch(1);
        doAnswer(invocation -> {
            deletionStarted.countDown();
            if (!releaseDeletion.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to fail the world reset");
            }
            throw new IllegalStateException("database failure");
        })
            .when(store)
            .deleteAllForWorld("world");
        var config = new PluginConfig();
        config.regen.autoUnmarkAfterRegen = true;
        var registry = new MarkRegistry(store, null, config, null, null);
        registry.loadFromDatabase();

        var reset = registry.resetMarkForWorld("world");
        assertTrue(deletionStarted.await(5, TimeUnit.SECONDS));
        var completion = registry.onChunksRegenComplete(List.of(chunk), Runnable::run);

        assertFalse(completion.isDone());
        releaseDeletion.countDown();
        assertThrows(CompletionException.class, reset::join);
        completion.join();

        assertFalse(registry.isMarked("world", 1, 0));
        verify(store).unmarkBatch(List.of(chunk));
    }

    @Test
    void deletionCompletionWaitsForWorldResetBeforePersistingMarkRemoval() throws Exception {
        var store = mock(MarkStore.class);
        var chunk = new MarkedChunk("world", 1, 0, UUID.randomUUID(), 1);
        when(store.loadAll()).thenReturn(List.of(chunk));
        var deletionStarted = new CountDownLatch(1);
        var releaseDeletion = new CountDownLatch(1);
        doAnswer(invocation -> {
            deletionStarted.countDown();
            if (!releaseDeletion.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to finish the world reset");
            }
            return null;
        })
            .when(store)
            .deleteAllForWorld("world");
        var registry = new MarkRegistry(store, null, new PluginConfig(), null, null);
        registry.loadFromDatabase();

        var reset = registry.resetMarkForWorld("world");
        assertTrue(deletionStarted.await(5, TimeUnit.SECONDS));
        var deletionCompletion = CompletableFuture.runAsync(() -> registry.unmarkAfterDeletion(List.of(chunk)));

        try {
            assertFalse(deletionCompletion.isDone());
            verify(store, never()).unmarkBatch(any());
        } finally {
            releaseDeletion.countDown();
        }
        reset.join();
        deletionCompletion.join();

        assertFalse(registry.isMarked("world", 1, 0));
        verify(store, never()).unmarkBatch(any());
    }
}
