package github.freshchromatic.chunkrevive.application.api;

import github.freshchromatic.chunkrevive.api.integration.ProtectionBatchResult;
import github.freshchromatic.chunkrevive.api.event.OperationCompletedEvent;
import github.freshchromatic.chunkrevive.api.model.ChunkKey;
import github.freshchromatic.chunkrevive.api.model.RequestContext;
import github.freshchromatic.chunkrevive.api.operation.ChunkRadius;
import github.freshchromatic.chunkrevive.api.operation.ExplicitChunks;
import github.freshchromatic.chunkrevive.api.operation.MaintenanceRequest;
import github.freshchromatic.chunkrevive.api.operation.OperationType;
import github.freshchromatic.chunkrevive.api.operation.OperationId;
import github.freshchromatic.chunkrevive.api.operation.OperationQuery;
import github.freshchromatic.chunkrevive.api.operation.OperationPersistenceStatus;
import github.freshchromatic.chunkrevive.api.operation.OperationState;
import github.freshchromatic.chunkrevive.api.worldgen.WorldGeneratorCompatibilityApi;
import github.freshchromatic.chunkrevive.api.worldgen.GeneratorCapability;
import github.freshchromatic.chunkrevive.api.worldgen.GeneratorCompatibilitySnapshot;
import github.freshchromatic.chunkrevive.api.worldgen.GeneratorSupportLevel;
import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.config.WorldAccessPolicy;
import github.freshchromatic.chunkrevive.feature.marking.MarkRegistry;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import github.freshchromatic.chunkrevive.feature.operation.OperationRecord;
import github.freshchromatic.chunkrevive.feature.operation.OperationStore;
import github.freshchromatic.chunkrevive.feature.reset.DeletionJob;
import github.freshchromatic.chunkrevive.feature.reset.DeletionService;
import github.freshchromatic.chunkrevive.feature.reset.AnvilRegionPos;
import github.freshchromatic.chunkrevive.feature.reset.ResetService;
import github.freshchromatic.chunkrevive.feature.reset.ResetStrategyPlanner;
import github.freshchromatic.chunkrevive.feature.regeneration.RegenerationQueue;
import github.freshchromatic.chunkrevive.feature.scanning.ChunkScanService;
import github.freshchromatic.chunkrevive.feature.scanning.DiskChunkScanner;
import github.freshchromatic.chunkrevive.infrastructure.persistence.PersistenceRepository;
import github.freshchromatic.chunkrevive.integration.protection.LandProtection;
import github.freshchromatic.freshlib.database.SqliteDatabase;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperationCoordinatorTest {

    @Test
    void emptyPreviewDoesNotCountItsGeneralRejectionAsARejectedTarget() {
        var plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("consumer");
        var scheduler = mock(GlobalRegionScheduler.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        })
            .when(scheduler)
            .execute(any(), any());
        var marks = mock(MarkRegistry.class);
        when(marks.getMarkedChunks()).thenReturn(List.of());
        var integration = mock(DefaultIntegrationApi.class);
        when(integration.check(any(), any())).thenReturn(CompletableFuture.completedFuture(new ProtectionBatchResult(
            Map.of(), Map.of())));
        var resets = mock(ResetService.class);
        when(resets.previewResetBulk(any())).thenReturn(new ResetStrategyPlanner.Plan(List.of(), List.of(), List.of()));
        var coordinator = new OperationCoordinator(plugin, marks, resets, mock(DeletionService.class), mock(
            ChunkScanService.class), new WorldAccessPolicy(new PluginConfig()), mock(
                WorldGeneratorCompatibilityApi.class), integration, mock(OperationStore.class));

        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(scheduler);
            var preview = coordinator.preview(plugin, MaintenanceRequest.reset(new ExplicitChunks(List.of())), RequestContext.system())
                .toCompletableFuture()
                .join();

            assertFalse(preview.executable());
            assertEquals(0, preview.requestedTargets());
            assertEquals(0, preview.acceptedTargets());
            assertEquals(0, preview.rejectedTargets());
            assertEquals("NO_ELIGIBLE_TARGETS", preview.rejections().getFirst().reasonCode());
        }
    }

    @Test
    void rejectedPreviewOwnerCannotConsumeAnotherPluginsPreview() throws Exception {
        var owner = mock(Plugin.class);
        when(owner.getName()).thenReturn("owner");
        var otherPlugin = mock(Plugin.class);
        when(otherPlugin.getName()).thenReturn("other");
        var world = mock(World.class);
        var scheduler = mock(GlobalRegionScheduler.class);
        Thread testThread = Thread.currentThread();
        BlockingQueue<Runnable> scheduledTasks = new LinkedBlockingQueue<>();
        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(1);
            if (Thread.currentThread() == testThread) {
                task.run();
            } else {
                scheduledTasks.add(task);
            }
            return null;
        })
            .when(scheduler)
            .execute(any(), any());
        var scans = mock(ChunkScanService.class);
        when(scans.countRegions(any(), any())).thenReturn(1);
        var marks = mock(MarkRegistry.class);
        when(marks.getMarkedChunks()).thenReturn(List.of());
        var integration = mock(DefaultIntegrationApi.class);
        when(integration.check(any(), any())).thenReturn(CompletableFuture.completedFuture(new ProtectionBatchResult(
            Map.of(), Map.of())));
        var store = mock(OperationStore.class);
        var saveStarted = new CountDownLatch(1);
        var allowSave = new CountDownLatch(1);
        doAnswer(invocation -> {
            saveStarted.countDown();
            assertTrue(allowSave.await(5, TimeUnit.SECONDS));
            return null;
        })
            .when(store)
            .save(any());
        var coordinator = new OperationCoordinator(owner, marks, mock(ResetService.class), mock(DeletionService.class),
            scans, new WorldAccessPolicy(new PluginConfig()), mock(WorldGeneratorCompatibilityApi.class), integration,
            store);

        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(scheduler);
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            var request = new MaintenanceRequest(OperationType.SCAN_EXISTING_CHUNKS, new ChunkRadius("world", 0, 0, 1),
                Map.of());
            var preview = coordinator.preview(owner, request, RequestContext.system()).toCompletableFuture().join();
            var ownerMismatch = assertThrows(java.util.concurrent.CompletionException.class, () ->
                coordinator.submit(otherPlugin, preview.token(), "request")
                    .toCompletableFuture()
                    .join());

            assertEquals("PREVIEW_OWNER_MISMATCH", ownerMismatch.getCause().getMessage());
            verify(integration).check(any(), any());

            var handle = coordinator.submit(owner, preview.token(), "request").toCompletableFuture().join();
            var retry = coordinator.submit(owner, preview.token(), "request").toCompletableFuture().join();
            var differentKey = assertThrows(java.util.concurrent.CompletionException.class, () ->
                coordinator.submit(owner, preview.token(), "another-request")
                    .toCompletableFuture()
                    .join());

            assertNotNull(handle);
            assertEquals(handle.id(), retry.id());
            assertEquals("PREVIEW_ALREADY_SUBMITTED", differentKey.getCause().getMessage());
            verify(integration, org.mockito.Mockito.times(2)).check(any(), any());
            assertTrue(saveStarted.await(5, TimeUnit.SECONDS));
            allowSave.countDown();
            assertTrue(coordinator.stopPersistence(Duration.ofSeconds(1)));
            verify(store, org.mockito.Mockito.times(1)).save(any());
        } finally {
            allowSave.countDown();
        }
    }

    @Test
    void operationCancellationIsRejectedWhenDeletionWorkHasStarted() throws Exception {
        var plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("consumer");
        var world = mock(World.class);
        var scheduler = mock(GlobalRegionScheduler.class);
        Thread testThread = Thread.currentThread();
        BlockingQueue<Runnable> scheduledTasks = new LinkedBlockingQueue<>();
        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(1);
            if (Thread.currentThread() == testThread) {
                task.run();
            } else {
                scheduledTasks.add(task);
            }
            return null;
        })
            .when(scheduler)
            .execute(any(), any());
        var target = new MarkedChunk("world", 1, 2, UUID.randomUUID(), 0);
        var marks = mock(MarkRegistry.class);
        when(marks.getMarkedChunks()).thenReturn(List.of(target));
        var landProtection = mock(LandProtection.class);
        when(marks.getLandProtection()).thenReturn(landProtection);
        var integration = mock(DefaultIntegrationApi.class);
        when(integration.check(any(), any())).thenReturn(CompletableFuture.completedFuture(new ProtectionBatchResult(
            Map.of(), Map.of())));
        var resets = mock(ResetService.class);
        when(resets.previewResetBulk(any())).thenReturn(new ResetStrategyPlanner.Plan(List.of(
            new ResetStrategyPlanner.RegionTarget("world", new AnvilRegionPos(0, 0))), List.of(), List.of()));
        var deletions = mock(DeletionService.class);
        var deletionId = UUID.randomUUID();
        when(deletions.queueOperation(any(), any())).thenReturn(List.of(deletionId));
        when(deletions.snapshots()).thenReturn(List.of(new DeletionService.JobSnapshot(deletionId,
            DeletionService.Type.REGION_PRUNE, DeletionService.State.RUNNING, "world", 0, 0, 1, null, null, false)));
        when(deletions.cancelAll(any())).thenReturn(false);
        var store = mock(OperationStore.class);
        var saveStarted = new CountDownLatch(1);
        var allowSave = new CountDownLatch(1);
        doAnswer(invocation -> {
            saveStarted.countDown();
            assertTrue(allowSave.await(5, TimeUnit.SECONDS));
            return null;
        })
            .when(store)
            .save(any());
        var generators = mock(WorldGeneratorCompatibilityApi.class);
        when(generators.inspect("world")).thenReturn(new GeneratorCompatibilitySnapshot("world", "test",
            GeneratorSupportLevel.NATIVE, Set.of(GeneratorCapability.REGENERATION), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), List.of()));
        var coordinator = new OperationCoordinator(plugin, marks, resets, deletions, mock(ChunkScanService.class),
            new WorldAccessPolicy(new PluginConfig()), generators, integration, store);

        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(scheduler);
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            when(landProtection.hasClaim(world, target.cx(), target.cz())).thenReturn(false);
            var request = MaintenanceRequest.reset(new ExplicitChunks(List.of(new ChunkKey("world", 1, 2))));
            var preview = coordinator.preview(plugin, request, RequestContext.system()).toCompletableFuture().join();
            var handle = coordinator.submit(plugin, preview.token(), "request").toCompletableFuture().join();
            assertTrue(saveStarted.await(5, TimeUnit.SECONDS));
            allowSave.countDown();
            Runnable startTask = scheduledTasks.poll(5, TimeUnit.SECONDS);
            assertNotNull(startTask);
            startTask.run();

            var result = coordinator.cancel(handle.id());
            assertFalse(result.cancelled());
            assertEquals("OPERATION_WORK_NOT_CANCELLABLE", result.reasonCode());
            assertEquals(OperationState.RUNNING, coordinator.find(handle.id()).orElseThrow().state());
            verify(deletions).cancelAll(any());
        } finally {
            allowSave.countDown();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void externallyCancelledDeletionJobsProducePartialOperationResult() throws Exception {
        var plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("consumer");
        var world = mock(World.class);
        var scheduler = mock(GlobalRegionScheduler.class);
        Thread testThread = Thread.currentThread();
        BlockingQueue<Runnable> scheduledTasks = new LinkedBlockingQueue<>();
        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(1);
            if (Thread.currentThread() == testThread) {
                task.run();
            } else {
                scheduledTasks.add(task);
            }
            return null;
        })
            .when(scheduler)
            .execute(any(), any());
        var chunks = List.of(new MarkedChunk("world", 1, 2, UUID.randomUUID(), 0), new MarkedChunk("world", 33, 2,
            UUID.randomUUID(), 0));
        var marks = mock(MarkRegistry.class);
        when(marks.getMarkedChunks()).thenReturn(chunks);
        var landProtection = mock(LandProtection.class);
        when(marks.getLandProtection()).thenReturn(landProtection);
        var integration = mock(DefaultIntegrationApi.class);
        when(integration.check(any(), any())).thenReturn(CompletableFuture.completedFuture(new ProtectionBatchResult(
            Map.of(), Map.of())));
        var regions = List.of(new ResetStrategyPlanner.RegionTarget("world", new AnvilRegionPos(0, 0)),
            new ResetStrategyPlanner.RegionTarget("world", new AnvilRegionPos(1, 0)));
        var resets = mock(ResetService.class);
        when(resets.previewResetBulk(any())).thenReturn(new ResetStrategyPlanner.Plan(regions, List.of(), List.of()));
        var deletions = mock(DeletionService.class);
        var cancelledJob = UUID.randomUUID();
        var completedJob = UUID.randomUUID();
        when(deletions.queueOperation(any(), any())).thenReturn(List.of(cancelledJob, completedJob));
        when(deletions.snapshots()).thenReturn(List.of(new DeletionService.JobSnapshot(cancelledJob,
            DeletionService.Type.REGION_PRUNE, DeletionService.State.RUNNING, "world", 0, 0, 1, null, null, false),
            new DeletionService.JobSnapshot(completedJob, DeletionService.Type.REGION_PRUNE,
                DeletionService.State.RUNNING, "world", 1, 0, 1, null, null, false)), List.of());
        var store = mock(OperationStore.class);
        var saveStarted = new CountDownLatch(1);
        var allowSave = new CountDownLatch(1);
        doAnswer(invocation -> {
            saveStarted.countDown();
            assertTrue(allowSave.await(5, TimeUnit.SECONDS));
            return null;
        })
            .when(store)
            .save(any());
        var generators = mock(WorldGeneratorCompatibilityApi.class);
        when(generators.inspect("world")).thenReturn(new GeneratorCompatibilitySnapshot("world", "test",
            GeneratorSupportLevel.NATIVE, Set.of(GeneratorCapability.REGENERATION), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), List.of()));
        var coordinator = new OperationCoordinator(plugin, marks, resets, deletions, mock(ChunkScanService.class),
            new WorldAccessPolicy(new PluginConfig()), generators, integration, store);

        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(scheduler);
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            when(landProtection.hasClaim(any(), anyInt(), anyInt())).thenReturn(false);
            var request = MaintenanceRequest.reset(new ExplicitChunks(List.of(new ChunkKey("world", 1, 2), new ChunkKey(
                "world", 33, 2))));
            var preview = coordinator.preview(plugin, request, RequestContext.system()).toCompletableFuture().join();
            var handle = coordinator.submit(plugin, preview.token(), "request").toCompletableFuture().join();
            assertTrue(saveStarted.await(5, TimeUnit.SECONDS));
            allowSave.countDown();
            Runnable startTask = scheduledTasks.poll(5, TimeUnit.SECONDS);
            assertNotNull(startTask);
            startTask.run();

            var monitorTask = ArgumentCaptor.forClass(Consumer.class);
            verify(scheduler).runDelayed(any(), monitorTask.capture(), eq(20L));
            coordinator.deletionJobsCancelled(List.of(new DeletionService.CancelledDeletionJob(cancelledJob, Set.of(
                handle.id().value()))));
            ((Consumer<ScheduledTask>) monitorTask.getValue()).accept(mock(ScheduledTask.class));

            var operation = coordinator.find(handle.id()).orElseThrow();
            assertEquals(OperationState.PARTIALLY_SUCCEEDED, operation.state());
            assertEquals(1, operation.completed());
            verify(store, timeout(5000)).update(argThat(record -> record.state().equals("PARTIALLY_SUCCEEDED")
                && record.completed() == 1));
        } finally {
            allowSave.countDown();
        }
    }

    @Test
    void regenerationConflictDoesNotQueueDeletions() throws Exception {
        var plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("consumer");
        var world = mock(World.class);
        var scheduler = mock(GlobalRegionScheduler.class);
        Thread testThread = Thread.currentThread();
        BlockingQueue<Runnable> scheduledTasks = new LinkedBlockingQueue<>();
        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(1);
            if (Thread.currentThread() == testThread) {
                task.run();
            } else {
                scheduledTasks.add(task);
            }
            return null;
        })
            .when(scheduler)
            .execute(any(), any());
        var target = new MarkedChunk("world", 1, 2, UUID.randomUUID(), 0);
        var marks = mock(MarkRegistry.class);
        var regenerationQueue = mock(RegenerationQueue.class);
        when(marks.getRegenerationQueue()).thenReturn(regenerationQueue);
        when(regenerationQueue.isRunning()).thenReturn(true);
        when(marks.getMarkedChunks()).thenReturn(List.of(target));
        var landProtection = mock(LandProtection.class);
        when(marks.getLandProtection()).thenReturn(landProtection);
        var integration = mock(DefaultIntegrationApi.class);
        when(integration.check(any(), any())).thenReturn(CompletableFuture.completedFuture(new ProtectionBatchResult(
            Map.of(), Map.of())));
        var resets = mock(ResetService.class);
        when(resets.previewResetBulk(any())).thenReturn(new ResetStrategyPlanner.Plan(List.of(
            new ResetStrategyPlanner.RegionTarget("world", new AnvilRegionPos(0, 0))), List.of(), List.of(target)));
        var deletions = mock(DeletionService.class);
        var store = mock(OperationStore.class);
        var saveStarted = new CountDownLatch(1);
        var allowSave = new CountDownLatch(1);
        doAnswer(invocation -> {
            saveStarted.countDown();
            assertTrue(allowSave.await(5, TimeUnit.SECONDS));
            return null;
        })
            .when(store)
            .save(any());
        var generators = mock(WorldGeneratorCompatibilityApi.class);
        when(generators.inspect("world")).thenReturn(new GeneratorCompatibilitySnapshot("world", "test",
            GeneratorSupportLevel.NATIVE, Set.of(GeneratorCapability.REGENERATION), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), List.of()));
        var coordinator = new OperationCoordinator(plugin, marks, resets, deletions, mock(ChunkScanService.class),
            new WorldAccessPolicy(new PluginConfig()), generators, integration, store);

        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(scheduler);
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            when(landProtection.hasClaim(world, target.cx(), target.cz())).thenReturn(false);
            var request = MaintenanceRequest.reset(new ExplicitChunks(List.of(new ChunkKey("world", 1, 2))));
            var preview = coordinator.preview(plugin, request, RequestContext.system()).toCompletableFuture().join();
            var handle = coordinator.submit(plugin, preview.token(), "request").toCompletableFuture().join();
            assertTrue(saveStarted.await(5, TimeUnit.SECONDS));
            allowSave.countDown();
            Runnable startTask = scheduledTasks.poll(5, TimeUnit.SECONDS);
            assertNotNull(startTask);
            startTask.run();

            verify(store, timeout(5000)).update(argThat(record -> record.state().equals("FAILED")
                && "OPERATION_CONFLICT".equals(record.failureCode())));
            assertEquals(OperationState.FAILED, coordinator.find(handle.id()).orElseThrow().state());
            verify(deletions, never()).queueOperation(any(), any());
        } finally {
            allowSave.countDown();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void regenerationCancellationWaitsForActiveBatchBeforePublishingTerminalState() throws Exception {
        var plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("consumer");
        when(plugin.getLogger()).thenReturn(Logger.getLogger("OperationCoordinatorTest"));
        var world = mock(World.class);
        var scheduler = mock(GlobalRegionScheduler.class);
        Thread testThread = Thread.currentThread();
        BlockingQueue<Runnable> scheduledTasks = new LinkedBlockingQueue<>();
        int[] monitorSchedules = { 0 };
        var queueFinished = new AtomicReference<Runnable>();
        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(1);
            if (Thread.currentThread() == testThread) {
                task.run();
            } else {
                scheduledTasks.add(task);
            }
            return null;
        })
            .when(scheduler)
            .execute(any(), any());
        when(scheduler.runDelayed(any(), any(Consumer.class), eq(20L))).thenAnswer(invocation -> {
            if (monitorSchedules[0]++ > 0) {
                throw new IllegalStateException("scheduler stopped");
            }
            return mock(ScheduledTask.class);
        });
        var target = new MarkedChunk("world", 1, 2, UUID.randomUUID(), 0);
        var marks = mock(MarkRegistry.class);
        var regenerationQueue = mock(RegenerationQueue.class);
        when(marks.getRegenerationQueue()).thenReturn(regenerationQueue);
        when(regenerationQueue.start(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(3).run();
            queueFinished.set(invocation.getArgument(4));
            return true;
        });
        when(regenerationQueue.getActiveTasks()).thenReturn(1);
        when(regenerationQueue.getCompletedCount()).thenReturn(0, 0, 1);
        when(regenerationQueue.isRunning()).thenReturn(true, true, false);
        when(regenerationQueue.hasFailed()).thenReturn(false);
        when(marks.getMarkedChunks()).thenReturn(List.of(target));
        var landProtection = mock(LandProtection.class);
        when(marks.getLandProtection()).thenReturn(landProtection);
        var integration = mock(DefaultIntegrationApi.class);
        when(integration.check(any(), any())).thenReturn(CompletableFuture.completedFuture(new ProtectionBatchResult(
            Map.of(), Map.of())));
        var resets = mock(ResetService.class);
        when(resets.previewResetBulk(any())).thenReturn(new ResetStrategyPlanner.Plan(List.of(), List.of(), List.of(
            target)));
        var store = mock(OperationStore.class);
        var saveStarted = new CountDownLatch(1);
        var allowSave = new CountDownLatch(1);
        doAnswer(invocation -> {
            saveStarted.countDown();
            assertTrue(allowSave.await(5, TimeUnit.SECONDS));
            return null;
        })
            .when(store)
            .save(any());
        var generators = mock(WorldGeneratorCompatibilityApi.class);
        when(generators.inspect("world")).thenReturn(new GeneratorCompatibilitySnapshot("world", "test",
            GeneratorSupportLevel.NATIVE, Set.of(GeneratorCapability.REGENERATION), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), List.of()));
        var coordinator = new OperationCoordinator(plugin, marks, resets, mock(DeletionService.class), mock(
            ChunkScanService.class), new WorldAccessPolicy(new PluginConfig()), generators, integration, store);

        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(scheduler);
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            when(landProtection.hasClaim(world, target.cx(), target.cz())).thenReturn(false);
            var request = MaintenanceRequest.regenerate(new ExplicitChunks(List.of(new ChunkKey("world", 1, 2))));
            var preview = coordinator.preview(plugin, request, RequestContext.system()).toCompletableFuture().join();
            var handle = coordinator.submit(plugin, preview.token(), "request").toCompletableFuture().join();
            assertTrue(saveStarted.await(5, TimeUnit.SECONDS));
            allowSave.countDown();
            Runnable startTask = scheduledTasks.poll(5, TimeUnit.SECONDS);
            assertNotNull(startTask);
            startTask.run();

            var cancelResult = coordinator.cancel(handle.id());

            assertTrue(cancelResult.cancelled());
            assertEquals("CANCELLATION_PENDING", cancelResult.reasonCode());
            assertEquals(OperationState.CANCELLING, coordinator.find(handle.id()).orElseThrow().state());
            verify(regenerationQueue).cancel();
            verify(store, timeout(5000)).update(argThat(record -> record.state().equals("CANCELLING")));

            verify(scheduler, org.mockito.Mockito.times(2)).runDelayed(any(), any(Consumer.class), eq(20L));
            assertEquals(OperationState.CANCELLING, coordinator.find(handle.id()).orElseThrow().state());
            queueFinished.get().run();

            assertEquals(OperationState.CANCELLED, coordinator.find(handle.id()).orElseThrow().state());
            assertEquals(1, coordinator.find(handle.id()).orElseThrow().completed());
            verify(store, timeout(5000)).update(argThat(record -> record.state().equals("CANCELLED")
                && record.completed() == 1));
        } finally {
            allowSave.countDown();
        }
    }

    @Test
    void regenerationCancellationBetweenBatchesFinishesAsCancelledOnce() throws Exception {
        var plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("consumer");
        var world = mock(World.class);
        var scheduler = mock(GlobalRegionScheduler.class);
        Thread testThread = Thread.currentThread();
        BlockingQueue<Runnable> scheduledTasks = new LinkedBlockingQueue<>();
        var queueFinished = new AtomicReference<Runnable>();
        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(1);
            if (Thread.currentThread() == testThread) {
                task.run();
            } else {
                scheduledTasks.add(task);
            }
            return null;
        })
            .when(scheduler)
            .execute(any(), any());
        var target = new MarkedChunk("world", 1, 2, UUID.randomUUID(), 0);
        var marks = mock(MarkRegistry.class);
        var regenerationQueue = mock(RegenerationQueue.class);
        when(marks.getRegenerationQueue()).thenReturn(regenerationQueue);
        when(regenerationQueue.start(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(3).run();
            queueFinished.set(invocation.getArgument(4));
            return true;
        });
        when(regenerationQueue.getCompletedCount()).thenReturn(0);
        when(regenerationQueue.isRunning()).thenReturn(true, false);
        when(regenerationQueue.hasFailed()).thenReturn(false);
        org.mockito.Mockito.doAnswer(invocation -> {
            queueFinished.get().run();
            return null;
        })
            .when(regenerationQueue)
            .cancel();
        when(marks.getMarkedChunks()).thenReturn(List.of(target));
        var landProtection = mock(LandProtection.class);
        when(marks.getLandProtection()).thenReturn(landProtection);
        var integration = mock(DefaultIntegrationApi.class);
        when(integration.check(any(), any())).thenReturn(CompletableFuture.completedFuture(new ProtectionBatchResult(
            Map.of(), Map.of())));
        var resets = mock(ResetService.class);
        when(resets.previewResetBulk(any())).thenReturn(new ResetStrategyPlanner.Plan(List.of(), List.of(), List.of(
            target)));
        var store = mock(OperationStore.class);
        var saveCompleted = new CountDownLatch(1);
        doAnswer(invocation -> {
            saveCompleted.countDown();
            return null;
        })
            .when(store)
            .save(any());
        var generators = mock(WorldGeneratorCompatibilityApi.class);
        when(generators.inspect("world")).thenReturn(new GeneratorCompatibilitySnapshot("world", "test",
            GeneratorSupportLevel.NATIVE, Set.of(GeneratorCapability.REGENERATION), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), List.of()));
        var coordinator = new OperationCoordinator(plugin, marks, resets, mock(DeletionService.class), mock(
            ChunkScanService.class), new WorldAccessPolicy(new PluginConfig()), generators, integration, store);

        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(scheduler);
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            when(landProtection.hasClaim(world, target.cx(), target.cz())).thenReturn(false);
            var request = MaintenanceRequest.regenerate(new ExplicitChunks(List.of(new ChunkKey("world", 1, 2))));
            var preview = coordinator.preview(plugin, request, RequestContext.system()).toCompletableFuture().join();
            var handle = coordinator.submit(plugin, preview.token(), "request").toCompletableFuture().join();
            assertTrue(saveCompleted.await(5, TimeUnit.SECONDS));
            Runnable startTask = scheduledTasks.poll(5, TimeUnit.SECONDS);
            if (startTask != null) {
                startTask.run();
            }
            verify(regenerationQueue, timeout(5000)).start(any(), any(), any(), any(), any());

            var cancelResult = coordinator.cancel(handle.id());

            assertTrue(cancelResult.cancelled());
            assertEquals("CANCELLED", cancelResult.reasonCode());
            assertEquals(OperationState.CANCELLED, coordinator.find(handle.id()).orElseThrow().state());
            verify(store, timeout(5000)).update(argThat(record -> record.state().equals("CANCELLED")));
            verify(store, org.mockito.Mockito.never()).update(argThat(record -> record.state().equals("FAILED")));
            verify(store, org.mockito.Mockito.times(1)).update(argThat(record -> record.state().equals("CANCELLED")));
        }
    }

    @Test
    void completedOperationCanBeQueriedAfterReopen(@TempDir Path directory) {
        var path = directory.resolve("history.db");
        var id = UUID.randomUUID();
        var database = new SqliteDatabase(path.toString());
        assertTrue(database.connect());
        var repository = new PersistenceRepository(database);
        repository.init();
        repository.save(new OperationRecord(id, "RESET", "SUCCEEDED", 2, 2, 1, 2, "test", "correlation", null));
        database.close();

        var reopened = new SqliteDatabase(path.toString());
        assertTrue(reopened.connect());
        try {
            var coordinator = new OperationCoordinator(null, null, null, null, null, null, null, null,
                new PersistenceRepository(reopened));
            var recovered = coordinator.find(new OperationId(id)).orElseThrow();
            assertEquals(OperationState.SUCCEEDED, recovered.state());
            assertEquals(OperationPersistenceStatus.PERSISTED, recovered.persistenceStatus());
            assertEquals(1, coordinator.list(OperationQuery.recent()).total());
        } finally {
            reopened.close();
        }
    }

    @Test
    void interruptedOperationKeepsItsIdAndBecomesFailedAfterReopen(@TempDir Path directory) {
        var path = directory.resolve("interrupted-history.db");
        var id = UUID.randomUUID();
        var database = new SqliteDatabase(path.toString());
        assertTrue(database.connect());
        var repository = new PersistenceRepository(database);
        repository.init();
        var deletionJob = new DeletionJob(UUID.randomUUID(), "CHUNK_DELETE", "WAITING_FOR_COLD", "world", 1, 2, 150);
        repository.saveDeletionJobs(List.of(deletionJob), id);
        repository.save(new OperationRecord(id, "RESET", "RUNNING", 1, 3, 100, 200, "test", "correlation", null));
        database.close();

        var reopened = new SqliteDatabase(path.toString());
        assertTrue(reopened.connect());
        try {
            var reopenedRepository = new PersistenceRepository(reopened);
            var coordinator = new OperationCoordinator(null, null, null, null, null, null, null, null,
                reopenedRepository);
            var recovered = coordinator.find(new OperationId(id)).orElseThrow();

            assertEquals(new OperationId(id), recovered.id());
            assertEquals(OperationState.FAILED, recovered.state());
            assertEquals(OperationPersistenceStatus.PERSISTED, recovered.persistenceStatus());
            assertEquals("OPERATION_INTERRUPTED_BY_RESTART", recovered.failure().orElseThrow().code());
            assertEquals("Operation was interrupted by server restart.", recovered.failure().orElseThrow().message());
            assertEquals(List.of(deletionJob.id()), recovered.deletionJobIds());
            assertEquals("FAILED", reopenedRepository.loadOperations().getFirst().state());
            assertEquals("OPERATION_INTERRUPTED_BY_RESTART",
                reopenedRepository.loadOperations().getFirst().failureCode());
        } finally {
            reopened.close();
        }
    }

    @Test
    void failedOperationDetailsCanBeQueriedAfterReopen(@TempDir Path directory) {
        var path = directory.resolve("failed-operation-details.db");
        var id = UUID.randomUUID();
        var jobId = UUID.randomUUID();
        var database = new SqliteDatabase(path.toString());
        assertTrue(database.connect());
        var repository = new PersistenceRepository(database);
        repository.init();
        repository.saveDeletionJobs(List.of(new DeletionJob(jobId, "CHUNK_DELETE", "WAITING_FOR_COLD", "world", 1, 2,
            150)), id);
        repository.save(new OperationRecord(id, "RESET", "FAILED", 1, 3, 100, 200, "test", "correlation",
            "DELETION_FAILED", "The target could not be removed.", Map.of("deletionJobId", jobId.toString()),
            List.of()));
        database.close();

        var reopened = new SqliteDatabase(path.toString());
        assertTrue(reopened.connect());
        try {
            var coordinator = new OperationCoordinator(null, null, null, null, null, null, null, null,
                new PersistenceRepository(reopened));
            var recovered = coordinator.find(new OperationId(id)).orElseThrow();
            var failure = recovered.failure().orElseThrow();

            assertEquals("DELETION_FAILED", failure.code());
            assertEquals("The target could not be removed.", failure.message());
            assertEquals(Map.of("deletionJobId", jobId.toString()), failure.details());
            assertEquals(List.of(jobId), recovered.deletionJobIds());
        } finally {
            reopened.close();
        }
    }

    @Test
    void successfulScanPersistsInOrder() throws Exception {
        verifyScanPersistence("SUCCEEDED");
    }

    @Test
    void failedScanPersistsInOrder() throws Exception {
        verifyScanPersistence("FAILED");
    }

    @Test
    void cancelledScanPersistsInOrder() throws Exception {
        verifyScanPersistence("CANCELLED");
    }

    @Test
    void completedOperationKeepsItsStateWhenTerminalHistoryWriteFails() throws Exception {
        verifyScanPersistence("SUCCEEDED", true);
    }

    @Test
    void operationDoesNotStartWhenInitialPersistenceFails() throws Exception {
        var plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("consumer");
        var world = mock(World.class);
        var scheduler = mock(GlobalRegionScheduler.class);
        Thread testThread = Thread.currentThread();
        BlockingQueue<Runnable> scheduledTasks = new LinkedBlockingQueue<>();
        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(1);
            if (Thread.currentThread() == testThread) {
                task.run();
            } else {
                scheduledTasks.add(task);
            }
            return null;
        })
            .when(scheduler)
            .execute(any(), any());
        var scans = mock(ChunkScanService.class);
        when(scans.countRegions(any(), any())).thenReturn(1);
        var marks = mock(MarkRegistry.class);
        when(marks.getMarkedChunks()).thenReturn(List.of());
        var integration = mock(DefaultIntegrationApi.class);
        when(integration.check(any(), any())).thenReturn(CompletableFuture.completedFuture(new ProtectionBatchResult(
            Map.of(), Map.of())));
        var store = mock(OperationStore.class);
        doThrow(new IllegalStateException("database unavailable")).when(store).save(any());
        doThrow(new IllegalStateException("operation record missing")).when(store).update(any());
        var coordinator = new OperationCoordinator(plugin, marks, mock(ResetService.class), mock(DeletionService.class),
            scans, new WorldAccessPolicy(new PluginConfig()), mock(WorldGeneratorCompatibilityApi.class), integration,
            store);

        try (var bukkit = mockStatic(Bukkit.class)) {
            var pluginManager = mock(PluginManager.class);
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(scheduler);
            bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            var request = new MaintenanceRequest(OperationType.SCAN_EXISTING_CHUNKS, new ChunkRadius("world", 0, 0, 1),
                Map.of());
            var preview = coordinator.preview(plugin, request, RequestContext.system()).toCompletableFuture().join();
            var handle = coordinator.submit(plugin, preview.token(), "request").toCompletableFuture().join();

            Runnable startTask = scheduledTasks.poll(5, TimeUnit.SECONDS);
            assertNotNull(startTask);
            startTask.run();
            verify(store, timeout(5000)).update(any());
            Runnable completionEventTask = scheduledTasks.poll(5, TimeUnit.SECONDS);
            if (completionEventTask != null) {
                completionEventTask.run();
            }
            verify(pluginManager, timeout(5000)).callEvent(any(OperationCompletedEvent.class));
            var operation = coordinator.find(handle.id()).orElseThrow();
            assertEquals(OperationState.FAILED, operation.state());
            assertEquals("OPERATION_PERSISTENCE_FAILED", operation.failure().orElseThrow().code());
            assertEquals(OperationPersistenceStatus.FAILED, operation.persistenceStatus());
            verify(scans, never()).scan(any(), any(), any());
        }
    }

    @Test
    void cancellationBeforeInitialPersistenceCompletesSkipsQueuedOperation() throws Exception {
        var plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("consumer");
        var world = mock(World.class);
        var scheduler = mock(GlobalRegionScheduler.class);
        Thread testThread = Thread.currentThread();
        BlockingQueue<Runnable> scheduledTasks = new LinkedBlockingQueue<>();
        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(1);
            if (Thread.currentThread() == testThread) {
                task.run();
            } else {
                scheduledTasks.add(task);
            }
            return null;
        })
            .when(scheduler)
            .execute(any(), any());
        var scans = mock(ChunkScanService.class);
        when(scans.countRegions(any(), any())).thenReturn(1);
        when(scans.scan(any(), any(), any())).thenReturn(new CompletableFuture<>());
        var marks = mock(MarkRegistry.class);
        when(marks.getMarkedChunks()).thenReturn(List.of());
        var integration = mock(DefaultIntegrationApi.class);
        when(integration.check(any(), any())).thenReturn(CompletableFuture.completedFuture(new ProtectionBatchResult(
            Map.of(), Map.of())));
        var store = mock(OperationStore.class);
        var saveStarted = new CountDownLatch(1);
        var allowSave = new CountDownLatch(1);
        doAnswer(invocation -> {
            saveStarted.countDown();
            assertTrue(allowSave.await(5, TimeUnit.SECONDS));
            return null;
        })
            .when(store)
            .save(any());
        var coordinator = new OperationCoordinator(plugin, marks, mock(ResetService.class), mock(DeletionService.class),
            scans, new WorldAccessPolicy(new PluginConfig()), mock(WorldGeneratorCompatibilityApi.class), integration,
            store);

        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(scheduler);
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            var request = new MaintenanceRequest(OperationType.SCAN_EXISTING_CHUNKS, new ChunkRadius("world", 0, 0, 1),
                Map.of());
            var preview = coordinator.preview(plugin, request, RequestContext.system()).toCompletableFuture().join();
            var handle = coordinator.submit(plugin, preview.token(), "request").toCompletableFuture().join();
            assertTrue(saveStarted.await(5, TimeUnit.SECONDS));

            var cancellation = coordinator.cancel(handle.id());

            assertTrue(cancellation.cancelled());
            assertEquals(OperationState.CANCELLED, coordinator.find(handle.id()).orElseThrow().state());
            allowSave.countDown();
            verify(store, timeout(5000)).update(argThat(record -> record.state().equals("CANCELLED")));
            Runnable firstQueuedCallback = scheduledTasks.poll(5, TimeUnit.SECONDS);
            Runnable secondQueuedCallback = scheduledTasks.poll(5, TimeUnit.SECONDS);
            assertNotNull(firstQueuedCallback);
            assertNotNull(secondQueuedCallback);
            firstQueuedCallback.run();
            secondQueuedCallback.run();

            verify(scans, never()).scan(any(), any(), any());
            assertEquals(OperationState.CANCELLED, coordinator.find(handle.id()).orElseThrow().state());
        } finally {
            allowSave.countDown();
        }
    }

    @Test
    void shutdownDrainsQueuedWritesAndRejectsLaterPersistence() throws Exception {
        var plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("consumer");
        var world = mock(World.class);
        var scheduler = mock(GlobalRegionScheduler.class);
        Thread testThread = Thread.currentThread();
        BlockingQueue<Runnable> scheduledTasks = new LinkedBlockingQueue<>();
        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(1);
            if (Thread.currentThread() == testThread) {
                task.run();
            } else {
                scheduledTasks.add(task);
            }
            return null;
        })
            .when(scheduler)
            .execute(any(), any());
        var scans = mock(ChunkScanService.class);
        when(scans.countRegions(any(), any())).thenReturn(1);
        when(scans.scan(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(
            new DiskChunkScanner.ScanResult(1, 1, 1, 1, 0, 0, 0, 0, false)));
        var marks = mock(MarkRegistry.class);
        when(marks.getMarkedChunks()).thenReturn(List.of());
        var integration = mock(DefaultIntegrationApi.class);
        when(integration.check(any(), any())).thenReturn(CompletableFuture.completedFuture(new ProtectionBatchResult(
            Map.of(), Map.of())));
        var store = mock(OperationStore.class);
        var saveStarted = new CountDownLatch(1);
        var allowSave = new CountDownLatch(1);
        doAnswer(invocation -> {
            saveStarted.countDown();
            assertTrue(allowSave.await(5, TimeUnit.SECONDS));
            return null;
        })
            .when(store)
            .save(any());
        var coordinator = new OperationCoordinator(plugin, marks, mock(ResetService.class), mock(DeletionService.class),
            scans, new WorldAccessPolicy(new PluginConfig()), mock(WorldGeneratorCompatibilityApi.class), integration,
            store);

        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(scheduler);
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            var request = new MaintenanceRequest(OperationType.SCAN_EXISTING_CHUNKS, new ChunkRadius("world", 0, 0, 1),
                Map.of());
            var preview = coordinator.preview(plugin, request, RequestContext.system()).toCompletableFuture().join();
            coordinator.submit(plugin, preview.token(), "request").toCompletableFuture().join();
            assertTrue(saveStarted.await(5, TimeUnit.SECONDS));
            assertFalse(coordinator.stopPersistence(Duration.ofMillis(10)));

            allowSave.countDown();
            Runnable startTask = scheduledTasks.poll(5, TimeUnit.SECONDS);
            assertNotNull(startTask);
            verify(store, timeout(5000)).save(any());
            assertTrue(coordinator.stopPersistence(Duration.ofSeconds(1)));
            startTask.run();

            verify(scans).scan(any(), any(), any());
            verify(store, never()).update(any());
        } finally {
            allowSave.countDown();
        }
    }

    @Test
    void operationFailsWhenSchedulerRejectsInitialDispatch() throws Exception {
        var plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("consumer");
        var world = mock(World.class);
        var scheduler = mock(GlobalRegionScheduler.class);
        Thread testThread = Thread.currentThread();
        doAnswer(invocation -> {
            if (Thread.currentThread() != testThread) {
                throw new IllegalStateException("plugin disabled");
            }
            invocation.<Runnable>getArgument(1).run();
            return null;
        })
            .when(scheduler)
            .execute(any(), any());
        var scans = mock(ChunkScanService.class);
        when(scans.countRegions(any(), any())).thenReturn(1);
        var marks = mock(MarkRegistry.class);
        when(marks.getMarkedChunks()).thenReturn(List.of());
        var integration = mock(DefaultIntegrationApi.class);
        when(integration.check(any(), any())).thenReturn(CompletableFuture.completedFuture(new ProtectionBatchResult(
            Map.of(), Map.of())));
        var store = mock(OperationStore.class);
        var saveStarted = new CountDownLatch(1);
        var allowSave = new CountDownLatch(1);
        doAnswer(invocation -> {
            saveStarted.countDown();
            assertTrue(allowSave.await(5, TimeUnit.SECONDS));
            return null;
        })
            .when(store)
            .save(any());
        var coordinator = new OperationCoordinator(plugin, marks, mock(ResetService.class), mock(DeletionService.class),
            scans, new WorldAccessPolicy(new PluginConfig()), mock(WorldGeneratorCompatibilityApi.class), integration,
            store);

        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(scheduler);
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            var request = new MaintenanceRequest(OperationType.SCAN_EXISTING_CHUNKS, new ChunkRadius("world", 0, 0, 1),
                Map.of());
            var preview = coordinator.preview(plugin, request, RequestContext.system()).toCompletableFuture().join();
            var handle = coordinator.submit(plugin, preview.token(), "request").toCompletableFuture().join();
            assertTrue(saveStarted.await(5, TimeUnit.SECONDS));
            allowSave.countDown();

            verify(store, timeout(5000)).update(any());
            var operation = coordinator.find(handle.id()).orElseThrow();
            assertEquals(OperationState.FAILED, operation.state());
            assertEquals("OPERATION_SCHEDULER_UNAVAILABLE", operation.failure().orElseThrow().code());
            verify(scans, never()).scan(any(), any(), any());
        } finally {
            allowSave.countDown();
        }
    }

    @Test
    void fatalErrorEscapesOperationExecution() throws Exception {
        var plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("consumer");
        var world = mock(World.class);
        var scheduler = mock(GlobalRegionScheduler.class);
        Thread testThread = Thread.currentThread();
        BlockingQueue<Runnable> scheduledTasks = new LinkedBlockingQueue<>();
        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(1);
            if (Thread.currentThread() == testThread) {
                task.run();
            } else {
                scheduledTasks.add(task);
            }
            return null;
        })
            .when(scheduler)
            .execute(any(), any());
        var scans = mock(ChunkScanService.class);
        when(scans.countRegions(any(), any())).thenReturn(1);
        AssertionError fatal = new AssertionError("fatal scan failure");
        doThrow(fatal).when(scans).scan(any(), any(), any());
        var marks = mock(MarkRegistry.class);
        when(marks.getMarkedChunks()).thenReturn(List.of());
        var integration = mock(DefaultIntegrationApi.class);
        when(integration.check(any(), any())).thenReturn(CompletableFuture.completedFuture(new ProtectionBatchResult(
            Map.of(), Map.of())));
        var store = mock(OperationStore.class);
        var saveStarted = new CountDownLatch(1);
        var allowSave = new CountDownLatch(1);
        doAnswer(invocation -> {
            saveStarted.countDown();
            assertTrue(allowSave.await(5, TimeUnit.SECONDS));
            return null;
        })
            .when(store)
            .save(any());
        var coordinator = new OperationCoordinator(plugin, marks, mock(ResetService.class), mock(DeletionService.class),
            scans, new WorldAccessPolicy(new PluginConfig()), mock(WorldGeneratorCompatibilityApi.class), integration,
            store);

        try (var bukkit = mockStatic(Bukkit.class)) {
            var pluginManager = mock(PluginManager.class);
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(scheduler);
            bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            var request = new MaintenanceRequest(OperationType.SCAN_EXISTING_CHUNKS, new ChunkRadius("world", 0, 0, 1),
                Map.of());
            var preview = coordinator.preview(plugin, request, RequestContext.system()).toCompletableFuture().join();
            var handle = coordinator.submit(plugin, preview.token(), "request").toCompletableFuture().join();
            assertTrue(saveStarted.await(5, TimeUnit.SECONDS));
            allowSave.countDown();

            Runnable startTask = scheduledTasks.poll(5, TimeUnit.SECONDS);
            assertNotNull(startTask);
            assertSame(fatal, assertThrows(AssertionError.class, startTask::run));

            verify(scans).scan(any(), any(), any());
            assertEquals(OperationState.RUNNING, coordinator.find(handle.id()).orElseThrow().state());
            verify(pluginManager, never()).callEvent(any(OperationCompletedEvent.class));
        } finally {
            allowSave.countDown();
        }
    }

    private void verifyScanPersistence(String terminalState) throws Exception {
        verifyScanPersistence(terminalState, false);
    }

    private void verifyScanPersistence(String terminalState, boolean failTerminalWrite) throws Exception {
        var plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("consumer");
        var world = mock(World.class);
        var scheduler = mock(GlobalRegionScheduler.class);
        Thread testThread = Thread.currentThread();
        BlockingQueue<Runnable> scheduledTasks = new LinkedBlockingQueue<>();
        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(1);
            if (Thread.currentThread() == testThread) {
                task.run();
            } else {
                scheduledTasks.add(task);
            }
            return null;
        })
            .when(scheduler)
            .execute(any(), any());
        var scans = mock(ChunkScanService.class);
        when(scans.countRegions(any(), any())).thenReturn(1);
        CompletableFuture<DiskChunkScanner.ScanResult> scanResult = terminalState.equals("FAILED")
            ? CompletableFuture.failedFuture(new IllegalStateException("scan failure"))
            : CompletableFuture.completedFuture(new DiskChunkScanner.ScanResult(1, 1, 1, 1, 0, 0, 0, 0,
                terminalState.equals("CANCELLED")));
        when(scans.scan(any(), any(), any())).thenReturn(scanResult);
        var marks = mock(MarkRegistry.class);
        when(marks.getMarkedChunks()).thenReturn(List.of());
        var integration = mock(DefaultIntegrationApi.class);
        when(integration.check(any(), any())).thenReturn(CompletableFuture.completedFuture(new ProtectionBatchResult(
            Map.of(), Map.of())));
        var store = mock(OperationStore.class);
        var saveStarted = new CountDownLatch(1);
        var allowSave = new CountDownLatch(1);
        doAnswer(invocation -> {
            saveStarted.countDown();
            assertTrue(allowSave.await(5, TimeUnit.SECONDS));
            return null;
        })
            .when(store)
            .save(any());
        if (failTerminalWrite) {
            doAnswer(invocation -> {
                OperationRecord record = invocation.getArgument(0);
                if (record.state().equals(terminalState)) {
                    throw new IllegalStateException("database unavailable");
                }
                return null;
            })
                .when(store)
                .update(any());
        }
        var coordinator = new OperationCoordinator(plugin, marks, mock(ResetService.class), mock(DeletionService.class),
            scans, new WorldAccessPolicy(new PluginConfig()), mock(WorldGeneratorCompatibilityApi.class), integration,
            store);

        try (var bukkit = mockStatic(Bukkit.class)) {
            var pluginManager = mock(PluginManager.class);
            var completionEventPublished = new CountDownLatch(1);
            doAnswer(invocation -> {
                if (invocation.getArgument(0) instanceof OperationCompletedEvent) {
                    completionEventPublished.countDown();
                }
                return null;
            })
                .when(pluginManager)
                .callEvent(any());
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(scheduler);
            bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            var request = new MaintenanceRequest(OperationType.SCAN_EXISTING_CHUNKS, new ChunkRadius("world", 0, 0, 1),
                Map.of());
            var preview = coordinator.preview(plugin, request, RequestContext.system()).toCompletableFuture().join();
            coordinator.submit(plugin, preview.token(), "request").toCompletableFuture().join();
            assertTrue(saveStarted.await(5, TimeUnit.SECONDS));
            allowSave.countDown();
            Runnable startTask = scheduledTasks.poll(5, TimeUnit.SECONDS);
            assertNotNull(startTask);
            startTask.run();
            verify(store, timeout(5000)).update(argThat(record -> record.state().equals(terminalState)));
            var order = inOrder(store);
            order.verify(store).save(any(OperationRecord.class));
            order.verify(store).update(argThat(record -> record.state().equals("RUNNING")));
            order.verify(store).update(argThat(record -> record.state().equals(terminalState)));
            runScheduledTasksUntil(scheduledTasks, completionEventPublished);
            var eventCaptor = ArgumentCaptor.forClass(OperationCompletedEvent.class);
            verify(pluginManager).callEvent(eventCaptor.capture());
            assertEquals(terminalState, eventCaptor.getValue().operation().state().name());
            assertEquals(failTerminalWrite ? OperationPersistenceStatus.FAILED : OperationPersistenceStatus.PERSISTED,
                eventCaptor.getValue().operation().persistenceStatus());
        } finally {
            allowSave.countDown();
        }
    }

    private static void runScheduledTasksUntil(BlockingQueue<Runnable> scheduledTasks,
        CountDownLatch completionEventPublished) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (completionEventPublished.getCount() > 0) {
            long remainingNanos = deadline - System.nanoTime();
            assertTrue(remainingNanos > 0, "Operation completion event was not published");
            Runnable task = scheduledTasks.poll(remainingNanos, TimeUnit.NANOSECONDS);
            assertNotNull(task, "Expected a scheduled completion event task");
            task.run();
        }
    }
}
