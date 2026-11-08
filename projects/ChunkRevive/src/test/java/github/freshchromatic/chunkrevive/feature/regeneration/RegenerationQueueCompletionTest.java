package github.freshchromatic.chunkrevive.feature.regeneration;

import github.freshchromatic.chunkrevive.config.Messages;
import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import github.freshchromatic.freshlib.scheduler.Scheduler;
import github.freshchromatic.freshlib.util.Logging;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegenerationQueueCompletionTest {

    @Test
    void queueRemainsRunningUntilPostRegenerationPersistenceCompletes() {
        var engine = mock(RegenerationService.class);
        when(engine.regenChunks(any(), anyCollection(), eq(true), any())).thenReturn(CompletableFuture.completedFuture(
            null));
        when(engine.formatDuration(anyLong())).thenReturn("1s");
        var config = new PluginConfig();
        config.regen.batchDelayTicks = 0;
        var queue = new RegenerationQueue(engine, config, new Messages());
        var persistence = new CompletableFuture<Void>();

        try (MockedStatic<Logging> logging = mockLogging();
            MockedStatic<NmsTerrainGenerator> terrain = mockStatic(NmsTerrainGenerator.class)) {
            assertTrue(queue.start(List.of(markedChunk()), Audience.empty(), chunks -> persistence));
            assertFalse(queue.start(List.of(markedChunk(), markedChunk()), Audience.empty(),
                chunks -> CompletableFuture.failedFuture(new IllegalStateException(
                    "a running batch must not be replaced"))));

            assertTrue(queue.isRunning());
            assertEquals(1, queue.getTotalCount());
            assertFalse(queue.isCancelled());
            persistence.complete(null);

            assertFalse(queue.isRunning());
            assertFalse(queue.isCancelled());
            verify(engine, times(1)).regenChunks(any(), anyCollection(), eq(true), any());
        }
    }

    @Test
    void startPreparationRunsOnlyWhenQueueAdmissionSucceeds() {
        var engine = mock(RegenerationService.class);
        when(engine.regenChunks(any(), anyCollection(), eq(true), any())).thenReturn(new CompletableFuture<>());
        var config = new PluginConfig();
        config.regen.batchDelayTicks = 0;
        var queue = new RegenerationQueue(engine, config, new Messages());
        var preparations = new AtomicBoolean();

        try (MockedStatic<Logging> logging = mockLogging();
            MockedStatic<NmsTerrainGenerator> terrain = mockStatic(NmsTerrainGenerator.class)) {
            assertTrue(queue.start(List.of(markedChunk()), Audience.empty(),
                ignored -> CompletableFuture.completedFuture(null), () -> preparations.set(true)));
            assertFalse(queue.start(List.of(markedChunk()), Audience.empty(),
                ignored -> CompletableFuture.completedFuture(null), () -> preparations.set(false)));

            assertTrue(preparations.get());
            verify(engine, times(1)).regenChunks(any(), anyCollection(), eq(true), any());
        }
    }

    @Test
    void cancellationDuringStartPreparationIsNotOverwritten() {
        var engine = mock(RegenerationService.class);
        var config = new PluginConfig();
        config.regen.batchDelayTicks = 0;
        var queue = new RegenerationQueue(engine, config, new Messages());
        var nestedStartAccepted = new AtomicBoolean(true);

        try (MockedStatic<Logging> logging = mockLogging();
            MockedStatic<NmsTerrainGenerator> terrain = mockStatic(NmsTerrainGenerator.class)) {
            assertTrue(queue.start(List.of(markedChunk()), Audience.empty(),
                ignored -> CompletableFuture.completedFuture(null), () -> {
                    nestedStartAccepted.set(queue.start(List.of(markedChunk()), Audience.empty(),
                        nested -> CompletableFuture.completedFuture(null)));
                    queue.cancel();
                }));

            assertFalse(nestedStartAccepted.get());
            assertFalse(queue.isRunning());
            assertTrue(queue.isCancelled());
            verify(engine, times(0)).regenChunks(any(), anyCollection(), eq(true), any());
        }
    }

    @Test
    void cancellationWaitsForActiveBatchAndSkipsItsMarkRemovalCallback() {
        var engine = mock(RegenerationService.class);
        var generation = new CompletableFuture<Void>();
        when(engine.regenChunks(any(), anyCollection(), eq(true), any())).thenReturn(generation);
        var config = new PluginConfig();
        config.regen.batchDelayTicks = 0;
        var queue = new RegenerationQueue(engine, config, new Messages());
        var markRemovalCalled = new AtomicBoolean();
        var queueFinishedCalled = new AtomicBoolean();

        try (MockedStatic<Logging> logging = mockLogging();
            MockedStatic<NmsTerrainGenerator> terrain = mockStatic(NmsTerrainGenerator.class)) {
            assertTrue(queue.start(List.of(markedChunk()), Audience.empty(), ignored -> {
                markRemovalCalled.set(true);
                return CompletableFuture.completedFuture(null);
            }, () -> {
            }, () -> queueFinishedCalled.set(true)));

            queue.cancel();

            assertTrue(queue.isRunning());
            assertEquals(1, queue.getActiveTasks());
            assertFalse(markRemovalCalled.get());
            assertFalse(queueFinishedCalled.get());

            generation.complete(null);

            assertFalse(queue.isRunning());
            assertTrue(queue.isCancelled());
            assertEquals(1, queue.getCompletedCount());
            assertFalse(markRemovalCalled.get());
            assertTrue(queueFinishedCalled.get());
        }
    }

    @Test
    void failedPostRegenerationPersistenceFailsTheQueue() {
        var engine = mock(RegenerationService.class);
        when(engine.regenChunks(any(), anyCollection(), eq(true), any())).thenReturn(CompletableFuture.completedFuture(
            null));
        var config = new PluginConfig();
        config.regen.batchDelayTicks = 0;
        var queue = new RegenerationQueue(engine, config, new Messages());

        try (MockedStatic<Logging> logging = mockLogging();
            MockedStatic<NmsTerrainGenerator> terrain = mockStatic(NmsTerrainGenerator.class)) {
            queue.start(List.of(markedChunk()), Audience.empty(), chunks -> CompletableFuture.failedFuture(
                new IllegalStateException("database failure")));

            assertFalse(queue.isRunning());
            assertTrue(queue.isCancelled());
        }
    }

    @Test
    void skippedBatchDoesNotRemoveItsMarks() {
        var engine = mock(RegenerationService.class);
        when(engine.regenChunks(any(), anyCollection(), eq(true), any())).thenReturn(CompletableFuture.failedFuture(
            new SkipChunkException("World not found")));
        when(engine.formatDuration(anyLong())).thenReturn("1s");
        var config = new PluginConfig();
        config.regen.batchDelayTicks = 0;
        var queue = new RegenerationQueue(engine, config, new Messages());
        var initiator = mock(Audience.class);
        var removeMarksCalled = new AtomicBoolean();

        try (MockedStatic<Logging> logging = mockLogging();
            MockedStatic<NmsTerrainGenerator> terrain = mockStatic(NmsTerrainGenerator.class)) {
            queue.start(List.of(markedChunk()), initiator, chunks -> {
                removeMarksCalled.set(true);
                return CompletableFuture.completedFuture(null);
            });

            assertFalse(queue.isRunning());
            assertFalse(queue.isCancelled());
            assertEquals(0, queue.getCompletedCount());
            assertFalse(removeMarksCalled.get());
            var sentMessages = ArgumentCaptor.forClass(Component.class);
            verify(engine, times(2)).sendMessage(eq(initiator), sentMessages.capture());
            String completionMessage = PlainTextComponentSerializer.plainText().serialize(
                sentMessages.getAllValues().getLast());
            assertTrue(completionMessage.contains("Regenerated 0 chunks"));
        }
    }

    @Test
    void skippedBatchDoesNotDiscardUnaffectedPendingWork() {
        var engine = mock(RegenerationService.class);
        var batchNumber = new AtomicInteger();
        when(engine.regenChunks(any(), anyCollection(), eq(true), any())).thenAnswer(
            invocation -> batchNumber.getAndIncrement() == 0 ? CompletableFuture.failedFuture(new SkipChunkException(
                "Protection changed")) : CompletableFuture.completedFuture(null));
        when(engine.formatDuration(anyLong())).thenReturn("1s");
        var config = new PluginConfig();
        config.regen.batchDelayTicks = 0;
        var queue = new RegenerationQueue(engine, config, new Messages());
        var plugin = mock(org.bukkit.plugin.Plugin.class);
        queue.setPlugin(plugin);
        var first = markedChunk();
        var second = new MarkedChunk("another-world", 1, 0, UUID.randomUUID(), 1);

        try (MockedStatic<Logging> logging = mockLogging();
            MockedStatic<NmsTerrainGenerator> terrain = mockStatic(NmsTerrainGenerator.class);
            MockedStatic<Scheduler> scheduler = mockStatic(Scheduler.class)) {
            scheduler.when(() -> Scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
                invocation.<Runnable>getArgument(1).run();
                return null;
            });

            queue.start(List.of(first, second), Audience.empty(), ignored -> CompletableFuture.completedFuture(null));

            assertFalse(queue.isRunning());
            assertFalse(queue.isCancelled());
            assertEquals(1, queue.getCompletedCount());
            verify(engine, times(2)).regenChunks(any(), anyCollection(), eq(true), any());
        }
    }

    @Test
    void configuredBatchDelayIsNotBypassedWhenPreviousBatchCompletes() {
        var engine = mock(RegenerationService.class);
        when(engine.regenChunks(any(), anyCollection(), eq(true), any())).thenReturn(CompletableFuture.completedFuture(
            null));
        var config = new PluginConfig();
        config.regen.batchDelayTicks = 5;
        var queue = new RegenerationQueue(engine, config, new Messages());
        var plugin = mock(org.bukkit.plugin.Plugin.class);
        queue.setPlugin(plugin);
        var delayedDispatch = new AtomicReference<Runnable>();
        var chunks = List.of(markedChunk(), new MarkedChunk("another-world", 1, 0, UUID.randomUUID(), 1));

        try (MockedStatic<Logging> logging = mockLogging();
            MockedStatic<NmsTerrainGenerator> terrain = mockStatic(NmsTerrainGenerator.class);
            MockedStatic<Scheduler> scheduler = mockStatic(Scheduler.class)) {
            scheduler.when(() -> Scheduler.runTaskLater(eq(plugin), any(Runnable.class), eq(5L))).thenAnswer(
                invocation -> {
                    delayedDispatch.set(invocation.getArgument(1));
                    return null;
                });

            queue.start(chunks, Audience.empty(), ignored -> CompletableFuture.completedFuture(null));
            verify(engine, times(1)).regenChunks(any(), anyCollection(), eq(true), any());
            assertTrue(delayedDispatch.get() != null);

            scheduler.verify(() -> Scheduler.runTask(eq(plugin), any(Runnable.class)), times(0));
            verify(engine, times(1)).regenChunks(any(), anyCollection(), eq(true), any());

            delayedDispatch.get().run();

            verify(engine, times(2)).regenChunks(any(), anyCollection(), eq(true), any());
            assertFalse(queue.isRunning());
        }
    }

    @Test
    void rejectedDelayedDispatchFinishesTheQueueAsFailed() {
        var engine = mock(RegenerationService.class);
        var config = new PluginConfig();
        config.regen.batchDelayTicks = 5;
        var queue = new RegenerationQueue(engine, config, new Messages());
        var plugin = mock(org.bukkit.plugin.Plugin.class);
        queue.setPlugin(plugin);
        var queueFinished = new AtomicBoolean();

        try (MockedStatic<Logging> logging = mockLogging();
            MockedStatic<NmsTerrainGenerator> terrain = mockStatic(NmsTerrainGenerator.class);
            MockedStatic<Scheduler> scheduler = mockStatic(Scheduler.class)) {
            scheduler.when(() -> Scheduler.runTaskLater(eq(plugin), any(Runnable.class), eq(5L))).thenThrow(
                new IllegalStateException("plugin is stopping"));

            queue.start(List.of(markedChunk(), new MarkedChunk("another-world", 1, 0, UUID.randomUUID(), 1)),
                Audience.empty(), ignored -> CompletableFuture.completedFuture(null), () -> {
                }, () -> queueFinished.set(true));

            assertFalse(queue.isRunning());
            assertTrue(queue.hasFailed());
            assertTrue(queueFinished.get());
            verify(engine, times(0)).regenChunks(any(), anyCollection(), eq(true), any());
        }
    }

    @Test
    void rejectedNextBatchDispatchFinishesTheQueueAsFailed() {
        var engine = mock(RegenerationService.class);
        when(engine.regenChunks(any(), anyCollection(), eq(true), any())).thenReturn(CompletableFuture.completedFuture(
            null));
        var config = new PluginConfig();
        config.regen.batchDelayTicks = 0;
        var queue = new RegenerationQueue(engine, config, new Messages());
        var plugin = mock(org.bukkit.plugin.Plugin.class);
        queue.setPlugin(plugin);
        var queueFinished = new AtomicBoolean();

        try (MockedStatic<Logging> logging = mockLogging();
            MockedStatic<NmsTerrainGenerator> terrain = mockStatic(NmsTerrainGenerator.class);
            MockedStatic<Scheduler> scheduler = mockStatic(Scheduler.class)) {
            scheduler.when(() -> Scheduler.runTask(eq(plugin), any(Runnable.class))).thenThrow(
                new IllegalStateException("plugin is stopping"));

            queue.start(List.of(markedChunk(), new MarkedChunk("another-world", 1, 0, UUID.randomUUID(), 1)),
                Audience.empty(), ignored -> CompletableFuture.completedFuture(null), () -> {
                }, () -> queueFinished.set(true));

            assertFalse(queue.isRunning());
            assertTrue(queue.hasFailed());
            assertTrue(queueFinished.get());
            assertEquals(1, queue.getCompletedCount());
            verify(engine, times(1)).regenChunks(any(), anyCollection(), eq(true), any());
        }
    }

    @Test
    void failedRegenerationDoesNotRemoveItsMarks() {
        var engine = mock(RegenerationService.class);
        when(engine.regenChunks(any(), anyCollection(), eq(true), any())).thenReturn(CompletableFuture.failedFuture(
            new IllegalStateException("live chunk apply failed")));
        var config = new PluginConfig();
        config.regen.batchDelayTicks = 0;
        var queue = new RegenerationQueue(engine, config, new Messages());
        var removeMarksCalled = new AtomicBoolean();

        try (MockedStatic<Logging> logging = mockLogging();
            MockedStatic<NmsTerrainGenerator> terrain = mockStatic(NmsTerrainGenerator.class)) {
            queue.start(List.of(markedChunk()), Audience.empty(), chunks -> {
                removeMarksCalled.set(true);
                return CompletableFuture.completedFuture(null);
            });

            assertFalse(queue.isRunning());
            assertTrue(queue.isCancelled());
            assertEquals(0, queue.getCompletedCount());
            assertFalse(removeMarksCalled.get());
        }
    }

    @Test
    void unexpectedBatchCancellationFailsTheQueue() {
        var engine = mock(RegenerationService.class);
        when(engine.regenChunks(any(), anyCollection(), eq(true), any())).thenReturn(CompletableFuture.failedFuture(
            new CancellationException("external cancellation")));
        var config = new PluginConfig();
        config.regen.batchDelayTicks = 0;
        var queue = new RegenerationQueue(engine, config, new Messages());
        var removeMarksCalled = new AtomicBoolean();

        try (MockedStatic<Logging> logging = mockLogging();
            MockedStatic<NmsTerrainGenerator> terrain = mockStatic(NmsTerrainGenerator.class)) {
            queue.start(List.of(markedChunk()), Audience.empty(), chunks -> {
                removeMarksCalled.set(true);
                return CompletableFuture.completedFuture(null);
            });

            assertFalse(queue.isRunning());
            assertTrue(queue.isCancelled());
            assertEquals(0, queue.getCompletedCount());
            assertFalse(removeMarksCalled.get());
        }
    }

    private static MockedStatic<Logging> mockLogging() {
        var logging = mockStatic(Logging.class);
        logging.when(Logging::logger).thenReturn(new Logging.LoggerProxy(null));
        return logging;
    }

    private static MarkedChunk markedChunk() {
        return new MarkedChunk("world", 1, 0, UUID.randomUUID(), 1);
    }
}
