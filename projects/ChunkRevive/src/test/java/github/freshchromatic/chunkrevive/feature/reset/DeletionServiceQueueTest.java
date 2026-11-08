package github.freshchromatic.chunkrevive.feature.reset;

import github.freshchromatic.chunkrevive.api.integration.ProtectionBatchResult;
import github.freshchromatic.chunkrevive.api.model.ChunkKey;
import github.freshchromatic.chunkrevive.bootstrap.ChunkRevivePlugin;
import github.freshchromatic.chunkrevive.config.Messages;
import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.feature.coordination.ChunkMutationCoordinator;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import github.freshchromatic.chunkrevive.feature.marking.MarkRegistry;
import github.freshchromatic.chunkrevive.integration.protection.ProtectionChecks;
import github.freshchromatic.chunkrevive.integration.protection.LandProtection;
import github.freshchromatic.chunkrevive.nms.ChunkStorageGateway;
import github.freshchromatic.chunkrevive.nms.EmptyRegionInfo;
import github.freshchromatic.freshlib.command.PlayerCommander;
import github.freshchromatic.freshlib.scheduler.Scheduler;
import github.freshchromatic.freshlib.scheduler.ScheduledTask;
import github.freshchromatic.freshlib.util.Logging;
import net.kyori.adventure.audience.Audience;
import org.mockito.ArgumentMatchers;
import org.junit.jupiter.api.Test;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeletionServiceQueueTest {

    private static GlobalRegionScheduler immediateGlobalScheduler(ChunkRevivePlugin plugin) {
        var scheduler = mock(GlobalRegionScheduler.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        })
            .when(scheduler)
            .execute(eq(plugin), any(Runnable.class));
        return scheduler;
    }

    @Test
    void bulkQueuesDeduplicateByWorldAndTypeAndPersistNewJobs() {
        var store = mock(DeletionJobStore.class);
        var service = new DeletionService(mock(ChunkRevivePlugin.class), mock(MarkRegistry.class), store,
            new PluginConfig(), new Messages(), mock(ProtectionChecks.class), mock(ChunkStorageGateway.class));
        var audience = Audience.empty();
        var marked = new MarkedChunk("world", 1, -2, UUID.randomUUID(), 0);
        var otherWorld = new MarkedChunk("other", 1, -2, UUID.randomUUID(), 0);

        assertEquals(2, service.queueChunks(audience, List.of(marked, marked, otherWorld)));
        assertEquals(0, service.queueChunks(audience, List.of(marked, otherWorld)));
        assertEquals(2, service.queueRegions(audience, Map.of("world", List.of(new AnvilRegionPos(1, -2),
            new AnvilRegionPos(1, -2)), "other", List.of(new AnvilRegionPos(1, -2)))));
        assertEquals(1, service.queueEmptyRegions(audience, "world", List.of(new EmptyRegionInfo(1, -2, 10),
            new EmptyRegionInfo(1, -2, 20))));
        assertEquals(0, service.queueEmptyRegions(audience, "world", List.of(new EmptyRegionInfo(1, -2, 0))));

        Set<String> targets = service.snapshots()
            .stream()
            .map(job -> job.type() + ":" + job.world() + ":" + job.x()
            + ":" + job.z())
            .collect(Collectors.toSet());
        assertEquals(Set.of("CHUNK_DELETE:world:1:-2", "CHUNK_DELETE:other:1:-2", "REGION_PRUNE:world:1:-2",
            "REGION_PRUNE:other:1:-2", "EMPTY_REGION_PRUNE:world:1:-2"), targets);
        verify(store, times(3)).saveDeletionJobs(any());
    }

    @Test
    void bulkRetryReplacesFailedJobsAndRetainsRestoredWaitingJobs() {
        var store = mock(DeletionJobStore.class);
        UUID failedId = UUID.randomUUID();
        UUID runningId = UUID.randomUUID();
        when(store.loadDeletionJobs()).thenReturn(List.of(new DeletionJob(failedId, "CHUNK_DELETE", "FAILED", "world",
            1, 2, 10), new DeletionJob(runningId, "CHUNK_DELETE", "RUNNING", "world", 3, 4, 11)));
        var service = new DeletionService(mock(ChunkRevivePlugin.class), mock(MarkRegistry.class), store,
            new PluginConfig(), new Messages(), mock(ProtectionChecks.class), mock(ChunkStorageGateway.class));
        try (var scheduler = mockStatic(Scheduler.class); var logging = mockStatic(Logging.class)) {
            logging.when(Logging::logger).thenReturn(mock(Logging.LoggerProxy.class));
            service.start();
            var player = UUID.randomUUID();
            var failedTarget = new MarkedChunk("world", 1, 2, player, 12);
            var waitingTarget = new MarkedChunk("world", 3, 4, player, 12);

            assertEquals(1, service.queueChunks(Audience.empty(), List.of(failedTarget, failedTarget, waitingTarget)));
            assertEquals(2, service.snapshots().size());
            assertEquals(1, service.snapshots().stream().filter(job -> job.id().equals(runningId)).count());
            assertEquals(0, service.snapshots().stream().filter(job -> job.id().equals(failedId)).count());
            verify(store).updateDeletionJobState(runningId, "WAITING_FOR_COLD");
            verify(store).deleteDeletionJob(failedId);
            verify(store).saveDeletionJobs(any());
        }
    }

    @Test
    void singleQueuesReturnExistingJobForTheSameTypeAndTarget() {
        var store = mock(DeletionJobStore.class);
        var service = new DeletionService(mock(ChunkRevivePlugin.class), mock(MarkRegistry.class), store,
            new PluginConfig(), new Messages(), mock(ProtectionChecks.class), mock(ChunkStorageGateway.class));
        UUID chunkId = service.queueChunk(Audience.empty(), "world", 1, 2);
        UUID regionId = service.queueRegion(Audience.empty(), "world", 1, 2);

        assertEquals(chunkId, service.queueChunk(Audience.empty(), "world", 1, 2));
        assertEquals(regionId, service.queueRegion(Audience.empty(), "world", 1, 2));
        assertEquals(2, service.snapshots().size());
        verify(store, times(2)).saveDeletionJobs(any());
    }

    @Test
    void operationQueueDoesNotPublishJobsWhenBatchPersistenceFails() {
        var plugin = mock(ChunkRevivePlugin.class);
        var store = mock(DeletionJobStore.class);
        var workerStarted = new AtomicBoolean();
        doThrow(new IllegalStateException("database unavailable")).when(store).saveDeletionJobs(any(), any(), any());
        var service = new DeletionService(plugin, mock(MarkRegistry.class), store, new PluginConfig(), new Messages(),
            mock(ProtectionChecks.class), mock(ChunkStorageGateway.class), task -> workerStarted.set(true));
        UUID operationId = UUID.randomUUID();

        assertThrows(IllegalStateException.class, () -> service.queueOperation(operationId, List.of(
            new DeletionService.OperationDeletionRequest(DeletionService.Type.REGION_PRUNE, "world", 0, 0),
            new DeletionService.OperationDeletionRequest(DeletionService.Type.CHUNK_DELETE, "world", 4, 5))));

        assertTrue(service.snapshots().isEmpty());
        assertFalse(workerStarted.get());
    }

    @Test
    void operationQueueDeduplicatesExistingAndRepeatedTargetsInOnePersistenceCall() {
        var store = mock(DeletionJobStore.class);
        var service = new DeletionService(mock(ChunkRevivePlugin.class), mock(MarkRegistry.class), store,
            new PluginConfig(), new Messages(), mock(ProtectionChecks.class), mock(ChunkStorageGateway.class));
        UUID existingId = service.queueRegion(Audience.empty(), "world", 0, 0);
        UUID operationId = UUID.randomUUID();
        var requests = List.of(new DeletionService.OperationDeletionRequest(DeletionService.Type.REGION_PRUNE, "world",
            0, 0), new DeletionService.OperationDeletionRequest(DeletionService.Type.CHUNK_DELETE, "world", 4, 5),
            new DeletionService.OperationDeletionRequest(DeletionService.Type.CHUNK_DELETE, "world", 4, 5));

        List<UUID> queuedIds = service.queueOperation(operationId, requests);

        assertEquals(2, queuedIds.size());
        assertEquals(existingId, queuedIds.getFirst());
        assertEquals(2, service.snapshots().size());
        verify(store).saveDeletionJobs(ArgumentMatchers.argThat(jobs -> jobs.size() == 1), eq(operationId), eq(List.of(
            existingId)));
    }

    @Test
    void workerFailureNotificationUsesRequestersEntityScheduler() {
        var plugin = mock(ChunkRevivePlugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        var requester = mock(PlayerCommander.class);
        var player = mock(Player.class);
        when(requester.player()).thenReturn(player);
        var worker = new AtomicReference<Runnable>();
        var service = new DeletionService(plugin, mock(MarkRegistry.class), mock(DeletionJobStore.class),
            new PluginConfig(), new Messages(), mock(ProtectionChecks.class), mock(ChunkStorageGateway.class),
            worker::set);
        var globalScheduler = immediateGlobalScheduler(plugin);

        try (var bukkit = mockStatic(Bukkit.class);
            var scheduler = mockStatic(Scheduler.class);
            var logging = mockStatic(Logging.class)) {
            logging.when(Logging::logger).thenReturn(mock(Logging.LoggerProxy.class));
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(globalScheduler);
            bukkit.when(() -> Bukkit.getWorld("missing")).thenReturn(null);

            service.queueChunk(requester, "missing", 0, 0);
            worker.get().run();

            scheduler.verify(() -> Scheduler.runTask(eq(plugin), ArgumentMatchers.<Runnable>any(),
                ArgumentMatchers.<Runnable>any(), eq(player)));
            verify(requester).sendMessage(any(net.kyori.adventure.text.Component.class));
        }
    }

    @Test
    void rejectedWorkerStartDoesNotLeaveTheDeletionQueueStuck() {
        var plugin = mock(ChunkRevivePlugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        var attempts = new AtomicInteger();
        var service = new DeletionService(plugin, mock(MarkRegistry.class), mock(DeletionJobStore.class),
            new PluginConfig(), new Messages(), mock(ProtectionChecks.class), mock(ChunkStorageGateway.class), task -> {
                attempts.incrementAndGet();
                throw new IllegalStateException("worker executor rejected task");
            });
        var ticker = new AtomicReference<Runnable>();
        var timerTask = mock(ScheduledTask.class);

        try (var scheduler = mockStatic(Scheduler.class); var logging = mockStatic(Logging.class)) {
            logging.when(Logging::logger).thenReturn(mock(Logging.LoggerProxy.class));
            scheduler.when(() -> Scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(),
                anyLong())).thenAnswer(invocation -> {
                    ticker.set(invocation.getArgument(1));
                    return timerTask;
                });

            service.start();
            service.queueChunk(Audience.empty(), "world", 1, 2);
            int attemptsBeforeTicker = attempts.get();

            ticker.get().run();
            ticker.get().run();

            assertEquals(attemptsBeforeTicker + 2, attempts.get());
        }
    }

    @Test
    void deletionWorkerReadsWorldAndOnlinePlayersOnGlobalScheduler() {
        var plugin = mock(ChunkRevivePlugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        var store = mock(DeletionJobStore.class);
        var storage = mock(ChunkStorageGateway.class);
        when(storage.firstHolder(any(), any())).thenReturn(Optional.empty());
        when(storage.deleteChunks(any(), any())).thenReturn(CompletableFuture.completedFuture(0L));
        var world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        var inGlobalCallback = new AtomicBoolean();
        var landProtection = mock(LandProtection.class);
        when(landProtection.hasClaim(any(), anyInt(), anyInt())).thenAnswer(invocation -> {
            assertTrue(inGlobalCallback.get());
            return false;
        });
        var marks = mock(MarkRegistry.class);
        when(marks.getLandProtection()).thenReturn(landProtection);
        var protection = mock(ProtectionChecks.class);
        when(protection.check(any(), any())).thenReturn(CompletableFuture.completedFuture(new ProtectionBatchResult(
            Map.of(), Map.of())));
        var globalScheduler = mock(GlobalRegionScheduler.class);
        doAnswer(invocation -> {
            boolean wasInGlobalCallback = inGlobalCallback.getAndSet(true);
            try {
                invocation.<Runnable>getArgument(1).run();
            } finally {
                inGlobalCallback.set(wasInGlobalCallback);
            }
            return null;
        })
            .when(globalScheduler)
            .execute(eq(plugin), any(Runnable.class));
        var ticker = new AtomicReference<Runnable>();
        var config = new PluginConfig();
        config.deletion.regionBatchesPerCycle = 1;
        var service = new DeletionService(plugin, marks, store, config, new Messages(), protection, storage,
            Runnable::run);

        try (var bukkit = mockStatic(Bukkit.class); var scheduler = mockStatic(Scheduler.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(globalScheduler);
            bukkit.when(() -> Bukkit.getWorld("world")).thenAnswer(invocation -> {
                assertTrue(inGlobalCallback.get());
                return world;
            });
            bukkit.when(Bukkit::getOnlinePlayers).thenAnswer(invocation -> {
                assertTrue(inGlobalCallback.get());
                return List.of();
            });
            scheduler.when(Scheduler::isGlobalTickThread).thenReturn(false);
            scheduler.when(() -> Scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(),
                anyLong())).thenAnswer(invocation -> {
                    ticker.set(invocation.getArgument(1));
                    return mock(ScheduledTask.class);
                });

            service.start();
            service.queueChunk(Audience.empty(), "world", 0, 0);
            ticker.get().run();

            verify(storage).deleteChunks(any(), any());
        }
    }

    @Test
    void protectionChangeDuringRunningStatePersistenceStopsChunkDeletion() {
        var plugin = mock(ChunkRevivePlugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        var store = mock(DeletionJobStore.class);
        var storage = mock(ChunkStorageGateway.class);
        when(storage.firstHolder(any(), any())).thenReturn(Optional.empty());
        var world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        var landProtection = mock(LandProtection.class);
        when(landProtection.hasClaim(any(), anyInt(), anyInt())).thenReturn(false);
        var marks = mock(MarkRegistry.class);
        when(marks.getLandProtection()).thenReturn(landProtection);
        var revision = new AtomicLong();
        var protection = mock(ProtectionChecks.class);
        when(protection.revision()).thenAnswer(invocation -> revision.get());
        when(protection.check(any(), any())).thenReturn(CompletableFuture.completedFuture(new ProtectionBatchResult(
            Map.of(), Map.of())));
        doAnswer(invocation -> {
            revision.incrementAndGet();
            return null;
        })
            .when(store)
            .updateDeletionJobStates(any(), eq("RUNNING"));
        var globalScheduler = immediateGlobalScheduler(plugin);
        var ticker = new AtomicReference<Runnable>();
        var service = new DeletionService(plugin, marks, store, new PluginConfig(), new Messages(), protection, storage,
            Runnable::run);

        try (var bukkit = mockStatic(Bukkit.class);
            var scheduler = mockStatic(Scheduler.class);
            var logging = mockStatic(Logging.class)) {
            logging.when(Logging::logger).thenReturn(mock(Logging.LoggerProxy.class));
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(globalScheduler);
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
            scheduler.when(Scheduler::isGlobalTickThread).thenReturn(false);
            scheduler.when(() -> Scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(),
                anyLong())).thenAnswer(invocation -> {
                    ticker.set(invocation.getArgument(1));
                    return mock(ScheduledTask.class);
                });

            service.start();
            service.queueChunk(Audience.empty(), "world", 0, 0);
            ticker.get().run();

            assertEquals(1L, revision.get(), service.snapshots().toString());
            verify(storage, never()).deleteChunks(any(), any());
            verify(store).updateDeletionJobStates(any(), eq("RUNNING"));
            verify(store).updateDeletionJobState(any(), eq("FAILED"));
        }
    }

    @Test
    void protectionChangeDuringRunningStatePersistenceStopsRegionPrune() {
        var plugin = mock(ChunkRevivePlugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        var store = mock(DeletionJobStore.class);
        var storage = mock(ChunkStorageGateway.class);
        when(storage.firstHolder(any(), any())).thenReturn(Optional.empty());
        var world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        var marks = mock(MarkRegistry.class);
        when(marks.getLandProtection()).thenReturn(mock(LandProtection.class));
        var revision = new AtomicLong();
        var protection = mock(ProtectionChecks.class);
        when(protection.revision()).thenAnswer(invocation -> revision.get());
        when(protection.check(any(), any())).thenReturn(CompletableFuture.completedFuture(new ProtectionBatchResult(
            Map.of(), Map.of())));
        doAnswer(invocation -> {
            revision.incrementAndGet();
            return null;
        })
            .when(store)
            .updateDeletionJobStates(any(), eq("RUNNING"));
        var globalScheduler = immediateGlobalScheduler(plugin);
        var ticker = new AtomicReference<Runnable>();
        var service = new DeletionService(plugin, marks, store, new PluginConfig(), new Messages(), protection, storage,
            Runnable::run);

        try (var bukkit = mockStatic(Bukkit.class);
            var scheduler = mockStatic(Scheduler.class);
            var logging = mockStatic(Logging.class)) {
            logging.when(Logging::logger).thenReturn(mock(Logging.LoggerProxy.class));
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(globalScheduler);
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
            scheduler.when(Scheduler::isGlobalTickThread).thenReturn(false);
            scheduler.when(() -> Scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(),
                anyLong())).thenAnswer(invocation -> {
                    ticker.set(invocation.getArgument(1));
                    return mock(ScheduledTask.class);
                });

            service.start();
            service.queueRegion(Audience.empty(), "world", 0, 0);
            ticker.get().run();

            assertEquals(1L, revision.get(), service.snapshots().toString());
            verify(storage, never()).pruneRegion(any(), anyInt(), anyInt(), anyBoolean());
            verify(store).updateDeletionJobStates(any(), eq("RUNNING"));
            verify(store).updateDeletionJobState(any(), eq("FAILED"));
        }
    }

    @Test
    void concurrentBulkQueuesDoNotPersistDuplicateTargets() throws Exception {
        var store = mock(DeletionJobStore.class);
        var writeStarted = new CountDownLatch(1);
        var secondWriteStarted = new CountDownLatch(1);
        var releaseWrite = new CountDownLatch(1);
        var writeCount = new AtomicInteger();
        doAnswer(invocation -> {
            if (writeCount.incrementAndGet() == 1) {
                writeStarted.countDown();
            } else {
                secondWriteStarted.countDown();
            }
            assertTrue(releaseWrite.await(5, TimeUnit.SECONDS));
            return null;
        })
            .when(store)
            .saveDeletionJobs(any());
        var service = new DeletionService(mock(ChunkRevivePlugin.class), mock(MarkRegistry.class), store,
            new PluginConfig(), new Messages(), mock(ProtectionChecks.class), mock(ChunkStorageGateway.class));
        var chunk = new MarkedChunk("world", 1, 2, UUID.randomUUID(), 0);
        var firstQueue = CompletableFuture.supplyAsync(() -> service.queueChunks(Audience.empty(), List.of(chunk)));
        assertTrue(writeStarted.await(5, TimeUnit.SECONDS));
        var secondStarted = new CountDownLatch(1);
        var secondQueue = CompletableFuture.supplyAsync(() -> {
            secondStarted.countDown();
            return service.queueChunks(Audience.empty(), List.of(chunk));
        });

        assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
        assertFalse(secondWriteStarted.await(250, TimeUnit.MILLISECONDS));
        assertFalse(secondQueue.isDone());
        releaseWrite.countDown();

        assertEquals(1, firstQueue.get(5, TimeUnit.SECONDS));
        assertEquals(0, secondQueue.get(5, TimeUnit.SECONDS));
        verify(store, times(1)).saveDeletionJobs(any());
    }

    @Test
    void operationQueuesAssociateNewAndSharedDeletionJobs() {
        var store = mock(DeletionJobStore.class);
        var service = new DeletionService(mock(ChunkRevivePlugin.class), mock(MarkRegistry.class), store,
            new PluginConfig(), new Messages(), mock(ProtectionChecks.class), mock(ChunkStorageGateway.class));
        UUID firstOperation = UUID.randomUUID();
        UUID secondOperation = UUID.randomUUID();

        UUID jobId = service.queueChunk(firstOperation, Audience.empty(), "world", 1, 2);
        UUID sharedJobId = service.queueChunk(secondOperation, Audience.empty(), "world", 1, 2);

        assertEquals(jobId, sharedJobId);
        verify(store).saveDeletionJobs(any(), eq(firstOperation));
        verify(store).associateOperation(secondOperation, jobId);
    }

    @Test
    void failedRestoreCanBeRetried() {
        var store = mock(DeletionJobStore.class);
        when(store.loadDeletionJobs())
            .thenThrow(new IllegalStateException("database unavailable"))
            .thenReturn(List.of());
        var service = new DeletionService(mock(ChunkRevivePlugin.class), mock(MarkRegistry.class), store,
            new PluginConfig(), new Messages(), mock(ProtectionChecks.class), mock(ChunkStorageGateway.class));
        try (var scheduler = mockStatic(Scheduler.class)) {
            assertThrows(IllegalStateException.class, service::start);
            service.start();
            verify(store, times(2)).loadDeletionJobs();
        }
    }

    @Test
    void failedPersistenceDoesNotQueueSingleOrBulkJobs() {
        var store = mock(DeletionJobStore.class);
        doThrow(new IllegalStateException("database unavailable")).when(store).saveDeletionJobs(any());
        var service = new DeletionService(mock(ChunkRevivePlugin.class), mock(MarkRegistry.class), store,
            new PluginConfig(), new Messages(), mock(ProtectionChecks.class), mock(ChunkStorageGateway.class));
        var marked = new MarkedChunk("world", 1, 2, UUID.randomUUID(), 0);

        assertThrows(IllegalStateException.class, () -> service.queueChunk(Audience.empty(), "world", 1, 2));
        assertThrows(IllegalStateException.class, () -> service.queueChunks(Audience.empty(), List.of(marked)));
        assertEquals(List.of(), service.snapshots());
    }

    @Test
    void failedClearCanBeRetriedWhenResumeIsDisabled() {
        var store = mock(DeletionJobStore.class);
        doThrow(new IllegalStateException("database unavailable")).doNothing().when(store).deleteAllDeletionJobs();
        var config = new PluginConfig();
        config.deletion.resumeOnStartup = false;
        var service = new DeletionService(mock(ChunkRevivePlugin.class), mock(MarkRegistry.class), store, config,
            new Messages(), mock(ProtectionChecks.class), mock(ChunkStorageGateway.class));
        try (var scheduler = mockStatic(Scheduler.class)) {
            assertThrows(IllegalStateException.class, service::start);
            service.start();
            verify(store, times(2)).deleteAllDeletionJobs();
        }
    }

    @Test
    void failedRunningStatePersistenceKeepsJobWaitingAndDoesNotDelete() {
        var plugin = mock(ChunkRevivePlugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        var store = mock(DeletionJobStore.class);
        doThrow(new IllegalStateException("database unavailable")).when(store).updateDeletionJobStates(any(), any());
        var storage = mock(ChunkStorageGateway.class);
        when(storage.firstHolder(any(), any())).thenReturn(Optional.empty());
        var world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        var config = new PluginConfig();
        config.deletion.regionBatchesPerCycle = 1;
        var service = new DeletionService(plugin, mock(MarkRegistry.class), store, config, new Messages(), mock(
            ProtectionChecks.class), storage, Runnable::run);
        var globalScheduler = immediateGlobalScheduler(plugin);
        try (var bukkit = mockStatic(Bukkit.class); var logging = mockStatic(Logging.class)) {
            logging.when(Logging::logger).thenReturn(mock(Logging.LoggerProxy.class));
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(globalScheduler);
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());

            service.queueEmptyRegions(Audience.empty(), "world", List.of(new EmptyRegionInfo(0, 0, 1)));
            service.queueEmptyRegions(Audience.empty(), "world", List.of(new EmptyRegionInfo(1, 0, 1)));

            var first = service.snapshots().stream().filter(job -> job.x() == 0).findFirst().orElseThrow();
            assertEquals(DeletionService.State.WAITING_FOR_COLD, first.state());
            assertEquals("Waiting for deletion job storage", first.waitingReason());
            verify(storage, never()).pruneEmptyRegion(any(), anyInt(), anyInt(), anyBoolean());
        }
    }

    @Test
    void failedBatchStatePersistenceLeavesChunksWaitingWithoutDeleting() {
        var plugin = mock(ChunkRevivePlugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        var store = mock(DeletionJobStore.class);
        doThrow(new IllegalStateException("database unavailable")).when(store).updateDeletionJobStates(any(), any());
        var storage = mock(ChunkStorageGateway.class);
        when(storage.firstHolder(any(), any())).thenReturn(Optional.empty());
        var world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        var marks = mock(MarkRegistry.class);
        when(marks.getLandProtection()).thenReturn(mock(LandProtection.class));
        var protection = mock(ProtectionChecks.class);
        when(protection.check(any(), any())).thenReturn(CompletableFuture.completedFuture(new ProtectionBatchResult(
            Map.of(), Map.of())));
        var scheduler = mock(GlobalRegionScheduler.class);
        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(1);
            task.run();
            return null;
        })
            .when(scheduler)
            .execute(any(), any());
        var config = new PluginConfig();
        config.deletion.regionBatchesPerCycle = 1;
        var service = new DeletionService(plugin, marks, store, config, new Messages(), protection, storage,
            Runnable::run);
        try (var bukkit = mockStatic(Bukkit.class); var logging = mockStatic(Logging.class)) {
            logging.when(Logging::logger).thenReturn(mock(Logging.LoggerProxy.class));
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(scheduler);

            service.queueChunk(Audience.empty(), "world", 0, 0);
            service.queueChunk(Audience.empty(), "world", 1, 0);
            service.queueChunk(Audience.empty(), "world", 32, 0);

            assertEquals(2, service.snapshots()
                .stream()
                .filter(job -> job.x() < 32 && job.waitingReason().equals("Waiting for deletion job storage"))
                .count());
            verify(storage, never()).deleteChunks(any(), any());
        }
    }

    @Test
    void retiredPlayerSnapshotKeepsDeletionWaitingInsteadOfFailingTheWorker() {
        var plugin = mock(ChunkRevivePlugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        var store = mock(DeletionJobStore.class);
        when(store.loadDeletionJobs()).thenReturn(List.of());
        var storage = mock(ChunkStorageGateway.class);
        when(storage.firstHolder(any(), any())).thenReturn(Optional.empty());
        var world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        var player = mock(Player.class);
        var marks = mock(MarkRegistry.class);
        when(marks.getLandProtection()).thenReturn(mock(LandProtection.class));
        var retiredSnapshot = new CompletableFuture<Object>();
        retiredSnapshot.cancel(false);
        var messages = new Messages();
        var config = new PluginConfig();
        config.deletion.regionBatchesPerCycle = 1;
        var service = new DeletionService(plugin, marks, store, config, messages, mock(ProtectionChecks.class), storage,
            Runnable::run);
        var ticker = new AtomicReference<Runnable>();
        var globalScheduler = immediateGlobalScheduler(plugin);

        try (var bukkit = mockStatic(Bukkit.class);
            var scheduler = mockStatic(Scheduler.class);
            var logging = mockStatic(Logging.class)) {
            logging.when(Logging::logger).thenReturn(mock(Logging.LoggerProxy.class));
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(globalScheduler);
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
            scheduler.when(() -> Scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(),
                anyLong())).thenAnswer(invocation -> {
                    ticker.set(invocation.getArgument(1));
                    return mock(ScheduledTask.class);
                });
            scheduler.when(() -> Scheduler.callSyncMethod(eq(plugin), ArgumentMatchers.<Callable<Object>>any(),
                ArgumentMatchers.<Callable<Object>>any(), eq(player))).thenReturn(retiredSnapshot);

            service.start();
            service.queueChunk(Audience.empty(), "world", 1, 2);

            assertDoesNotThrow(() -> ticker.get().run());

            var job = service.snapshots().getFirst();
            assertEquals(DeletionService.State.WAITING_FOR_COLD, job.state());
            assertEquals(messages.text("deletion-nearby-player"), job.waitingReason());
            verify(storage, never()).deleteChunks(any(), any());
        }
    }

    @Test
    void chunkDeletionDoesNotRunWhileRegenerationOwnsSameTarget() {
        var plugin = mock(ChunkRevivePlugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        var storage = mock(ChunkStorageGateway.class);
        when(storage.firstHolder(any(), any())).thenReturn(Optional.empty());
        when(storage.deleteChunks(any(), any())).thenReturn(CompletableFuture.completedFuture(0L));
        var world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        var markRegistry = mock(MarkRegistry.class);
        when(markRegistry.getLandProtection()).thenReturn(mock(LandProtection.class));
        var protection = mock(ProtectionChecks.class);
        when(protection.check(any(), any())).thenReturn(CompletableFuture.completedFuture(new ProtectionBatchResult(
            Map.of(), Map.of())));
        var config = new PluginConfig();
        config.deletion.regionBatchesPerCycle = 1;
        var coordinator = new ChunkMutationCoordinator();
        var generation = new CompletableFuture<Void>();
        var regeneration = coordinator.run(List.of(new ChunkKey("world", 0, 0)), () -> generation);
        var service = new DeletionService(plugin, markRegistry, mock(DeletionJobStore.class), config, new Messages(),
            protection, storage, coordinator, Runnable::run);
        var ticker = new AtomicReference<Runnable>();
        var globalScheduler = immediateGlobalScheduler(plugin);

        try (var bukkit = mockStatic(Bukkit.class);
            var scheduler = mockStatic(Scheduler.class);
            var logging = mockStatic(Logging.class)) {
            logging.when(Logging::logger).thenReturn(mock(Logging.LoggerProxy.class));
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(globalScheduler);
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
            scheduler.when(() -> Scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(),
                anyLong())).thenAnswer(invocation -> {
                    ticker.set(invocation.getArgument(1));
                    return mock(ScheduledTask.class);
                });

            service.start();

            service.queueChunk(Audience.empty(), "world", 0, 0);
            service.queueChunk(Audience.empty(), "world", 1, 0);
            ticker.get().run();
            ticker.get().run();
            ticker.get().run();

            verify(storage, never()).deleteChunks(any(), any());
            assertEquals(2, service.snapshots().size());

            generation.complete(null);
            regeneration.join();
        }
    }

    @Test
    void timedOutShutdownLeavesRunningDeletionForStartupRecovery() throws Exception {
        var plugin = mock(ChunkRevivePlugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        var store = mock(DeletionJobStore.class);
        var storage = mock(ChunkStorageGateway.class);
        when(storage.firstHolder(any(), any())).thenReturn(Optional.empty());
        var deletionStarted = new CountDownLatch(1);
        var deletion = new CompletableFuture<Long>();
        var serviceReference = new AtomicReference<DeletionService>();
        when(storage.deleteChunks(any(), any())).thenAnswer(invocation -> {
            deletionStarted.countDown();
            serviceReference.get().stop(Duration.ZERO);
            CompletableFuture.runAsync(() -> deletion.complete(0L));
            return deletion;
        });
        var world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        var marks = mock(MarkRegistry.class);
        when(marks.getLandProtection()).thenReturn(mock(LandProtection.class));
        var protection = mock(ProtectionChecks.class);
        when(protection.check(any(), any())).thenReturn(CompletableFuture.completedFuture(new ProtectionBatchResult(
            Map.of(), Map.of())));
        var worker = new AtomicReference<Runnable>();
        var ticker = new AtomicReference<Runnable>();
        var config = new PluginConfig();
        config.deletion.regionBatchesPerCycle = 1;
        var service = new DeletionService(plugin, marks, store, config, new Messages(), protection, storage,
            new ChunkMutationCoordinator(), worker::set);
        serviceReference.set(service);
        var globalScheduler = mock(GlobalRegionScheduler.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        })
            .when(globalScheduler)
            .execute(eq(plugin), any(Runnable.class));

        try (var bukkit = mockStatic(Bukkit.class);
            var scheduler = mockStatic(Scheduler.class);
            var logging = mockStatic(Logging.class)) {
            logging.when(Logging::logger).thenReturn(mock(Logging.LoggerProxy.class));
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(globalScheduler);
            scheduler.when(() -> Scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(),
                anyLong())).thenAnswer(invocation -> {
                    ticker.set(invocation.getArgument(1));
                    return mock(ScheduledTask.class);
                });

            service.queueChunk(Audience.empty(), "world", 0, 0);
            worker.getAndSet(null).run();
            service.start();
            ticker.get().run();
            worker.getAndSet(null).run();

            assertEquals(0, deletionStarted.getCount());
            verify(store).updateDeletionJobStates(any(), eq("RUNNING"));
            verify(store, never()).deleteDeletionJob(any());
            verify(store, never()).deleteDeletionJobs(any());
            verify(marks, never()).unmarkAfterDeletion(any());
        }
    }

    @Test
    void failedMarkRemovalKeepsDeletedJobsAsFailed() {
        var plugin = mock(ChunkRevivePlugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        var store = mock(DeletionJobStore.class);
        var storage = mock(ChunkStorageGateway.class);
        when(storage.firstHolder(any(), any())).thenReturn(Optional.empty());
        when(storage.deleteChunks(any(), any())).thenReturn(CompletableFuture.completedFuture(0L));
        var world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        var marks = mock(MarkRegistry.class);
        when(marks.getLandProtection()).thenReturn(mock(LandProtection.class));
        doThrow(new IllegalStateException("mark storage unavailable")).when(marks).unmarkAfterDeletion(any());
        var protection = mock(ProtectionChecks.class);
        when(protection.check(any(), any())).thenReturn(CompletableFuture.completedFuture(new ProtectionBatchResult(
            Map.of(), Map.of())));
        var scheduler = mock(GlobalRegionScheduler.class);
        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(1);
            task.run();
            return null;
        })
            .when(scheduler)
            .execute(any(), any());
        var config = new PluginConfig();
        config.deletion.regionBatchesPerCycle = 1;
        config.deletion.autoUnmarkAfterDelete = true;
        var service = new DeletionService(plugin, marks, store, config, new Messages(), protection, storage,
            Runnable::run);
        try (var bukkit = mockStatic(Bukkit.class); var logging = mockStatic(Logging.class)) {
            logging.when(Logging::logger).thenReturn(mock(Logging.LoggerProxy.class));
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(scheduler);

            service.queueChunk(Audience.empty(), "world", 0, 0);
            service.queueChunk(Audience.empty(), "world", 1, 0);
            service.queueChunk(Audience.empty(), "world", 32, 0);

            assertEquals(2, service.snapshots()
                .stream()
                .filter(job -> job.x() < 32
                && job.state() == DeletionService.State.FAILED)
                .count());
            verify(store, never()).deleteDeletionJobs(any());
        }
    }

    @Test
    void failedCancellationKeepsJobsInQueue() {
        var store = mock(DeletionJobStore.class);
        var service = new DeletionService(mock(ChunkRevivePlugin.class), mock(MarkRegistry.class), store,
            new PluginConfig(), new Messages(), mock(ProtectionChecks.class), mock(ChunkStorageGateway.class));
        UUID first = service.queueChunk(Audience.empty(), "world", 1, 2);
        UUID second = service.queueChunk(Audience.empty(), "world", 3, 4);
        doThrow(new IllegalStateException("database unavailable")).when(store).deleteDeletionJob(first);
        doThrow(new IllegalStateException("database unavailable")).when(store).deleteDeletionJobs(any());

        assertThrows(IllegalStateException.class, () -> service.cancel(first));
        assertThrows(IllegalStateException.class, service::cancelWaiting);
        assertEquals(Set.of(first, second), service.snapshots()
            .stream()
            .map(DeletionService.JobSnapshot::id)
            .collect(Collectors.toSet()));
    }

    @Test
    void batchCancellationKeepsWaitingJobsWhenAnyJobHasAlreadyFailed() {
        var store = mock(DeletionJobStore.class);
        UUID waitingId = UUID.randomUUID();
        UUID failedId = UUID.randomUUID();
        when(store.loadDeletionJobs()).thenReturn(List.of(new DeletionJob(waitingId, "CHUNK_DELETE", "WAITING_FOR_COLD",
            "world", 1, 2, 10), new DeletionJob(failedId, "CHUNK_DELETE", "FAILED", "world", 3, 4, 11)));
        var config = new PluginConfig();
        var service = new DeletionService(mock(ChunkRevivePlugin.class), mock(MarkRegistry.class), store, config,
            new Messages(), mock(ProtectionChecks.class), mock(ChunkStorageGateway.class));

        try (var scheduler = mockStatic(Scheduler.class); var logging = mockStatic(Logging.class)) {
            logging.when(Logging::logger).thenReturn(mock(Logging.LoggerProxy.class));
            service.start();

            assertFalse(service.cancelAll(List.of(waitingId, failedId)));
            assertEquals(Set.of(waitingId, failedId), service.snapshots()
                .stream()
                .map(DeletionService.JobSnapshot::id)
                .collect(Collectors.toSet()));
            verify(store, never()).deleteDeletionJobs(any());
        }
    }

    @Test
    void playerSnapshotDoesNotSuppressFatalErrors() {
        var plugin = mock(ChunkRevivePlugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        var store = mock(DeletionJobStore.class);
        var storage = mock(ChunkStorageGateway.class);
        when(storage.firstHolder(any(), any())).thenReturn(Optional.empty());
        var marks = mock(MarkRegistry.class);
        when(marks.getLandProtection()).thenReturn(mock(LandProtection.class));
        var world = mock(World.class);
        var player = mock(org.bukkit.entity.Player.class);
        var snapshot = new CompletableFuture<>();
        snapshot.completeExceptionally(new AssertionError("fatal scheduler error"));
        var config = new PluginConfig();
        config.deletion.regionBatchesPerCycle = 1;
        var service = new DeletionService(plugin, marks, store, config, new Messages(), mock(ProtectionChecks.class),
            storage, Runnable::run);
        var globalScheduler = immediateGlobalScheduler(plugin);

        try (var bukkit = mockStatic(Bukkit.class);
            var scheduler = mockStatic(Scheduler.class);
            var logging = mockStatic(Logging.class)) {
            logging.when(Logging::logger).thenReturn(mock(Logging.LoggerProxy.class));
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(globalScheduler);
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
            scheduler.when(() -> Scheduler.callSyncMethod(eq(plugin), ArgumentMatchers.<Callable<Object>>any(),
                ArgumentMatchers.<Callable<Object>>any(), eq(player))).thenReturn(snapshot);

            assertThrows(AssertionError.class, () -> service.queueChunk(Audience.empty(), "world", 1, 2));
            assertEquals(DeletionService.State.WAITING_FOR_COLD, service.snapshots().getFirst().state());
            verify(storage, never()).deleteChunk(any(), anyInt(), anyInt());
        }
    }
}
