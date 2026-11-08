package github.freshchromatic.chunkrevive.feature.regeneration;

import github.freshchromatic.chunkrevive.api.integration.ProtectionBatchResult;
import github.freshchromatic.chunkrevive.config.Messages;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import github.freshchromatic.chunkrevive.integration.protection.LandProtection;
import github.freshchromatic.chunkrevive.integration.protection.ProtectionChecks;
import github.freshchromatic.freshlib.command.PlayerCommander;
import github.freshchromatic.freshlib.scheduler.Scheduler;
import github.freshchromatic.freshlib.util.Logging;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class RegenerationServiceWorldTest {

    @Test
    void singleAndBatchRequestsForSameChunkDoNotGenerateConcurrently() {
        var plugin = mock(Plugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        var world = mock(World.class);
        var scheduler = mock(GlobalRegionScheduler.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        })
            .when(scheduler)
            .execute(eq(plugin), any(Runnable.class));
        var protectionChecks = mock(ProtectionChecks.class);
        when(protectionChecks.revision()).thenReturn(0L);
        when(protectionChecks.check(any(), anyCollection())).thenReturn(CompletableFuture.completedFuture(
            new ProtectionBatchResult(Map.of(), Map.of())));
        var service = new RegenerationService(plugin, new Messages(), mock(LandProtection.class), protectionChecks);
        var chunk = new MarkedChunk("world", 1, 0, UUID.randomUUID(), 1);
        var firstGeneration = new CompletableFuture<Void>();
        var secondGeneration = new CompletableFuture<Void>();
        var generations = List.of(firstGeneration, secondGeneration);
        var generationStarts = new AtomicInteger();

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
            MockedStatic<NmsTerrainGenerator> terrain = mockStatic(NmsTerrainGenerator.class);
            MockedStatic<Scheduler> dispatch = mockStatic(Scheduler.class);
            MockedStatic<Logging> logging = mockStatic(Logging.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(scheduler);
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            terrain.when(() -> NmsTerrainGenerator.generate(eq(world), anyCollection(), eq(0L),
                org.mockito.ArgumentMatchers.<IntConsumer>any(), org.mockito.ArgumentMatchers.<Consumer<String>>any(),
                org.mockito.ArgumentMatchers.<BooleanSupplier>any(), isNull())).thenAnswer(invocation -> {
                    int index = generationStarts.getAndIncrement();
                    return generations.get(index);
                });
            logging.when(Logging::logger).thenReturn(new Logging.LoggerProxy(null));

            var single = service.regenChunk(Audience.empty(), chunk);
            var batch = service.regenChunks(Audience.empty(), List.of(chunk), true);

            assertEquals(1, generationStarts.get());

            firstGeneration.complete(null);

            assertEquals(2, generationStarts.get());

            secondGeneration.complete(null);
            single.join();
            batch.join();
        }
    }

    @Test
    void missingWorldCompletesAsSkippedBatch() {
        var plugin = mock(Plugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        var scheduler = mock(GlobalRegionScheduler.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        })
            .when(scheduler)
            .execute(eq(plugin), any(Runnable.class));
        var protectionChecks = mock(ProtectionChecks.class);
        when(protectionChecks.revision()).thenReturn(0L);
        when(protectionChecks.check(any(), anyCollection())).thenReturn(CompletableFuture.completedFuture(
            new ProtectionBatchResult(Map.of(), Map.of())));
        var service = new RegenerationService(plugin, new Messages(), mock(LandProtection.class), protectionChecks);
        var chunk = new MarkedChunk("world", 1, 0, UUID.randomUUID(), 1);
        CompletableFuture<Void> result;

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
            MockedStatic<Logging> logging = mockStatic(Logging.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(scheduler);
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(null);
            logging.when(Logging::logger).thenReturn(new Logging.LoggerProxy(null));

            result = service.regenChunks(Audience.empty(), List.of(chunk), true);
        }

        var failure = assertThrows(CompletionException.class, result::join);
        assertInstanceOf(SkipChunkException.class, failure.getCause());
    }

    @Test
    void protectionRevisionChangeSkipsCurrentBatchInsteadOfFailingQueueWork() {
        var plugin = mock(Plugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        var world = mock(World.class);
        var scheduler = mock(GlobalRegionScheduler.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        })
            .when(scheduler)
            .execute(eq(plugin), any(Runnable.class));
        var revision = new AtomicLong();
        var protectionChecks = mock(ProtectionChecks.class);
        when(protectionChecks.revision()).thenAnswer(invocation -> revision.get());
        when(protectionChecks.check(any(), anyCollection())).thenReturn(CompletableFuture.completedFuture(
            new ProtectionBatchResult(Map.of(), Map.of())));
        var service = new RegenerationService(plugin, new Messages(), mock(LandProtection.class), protectionChecks);
        var generation = new CompletableFuture<Void>();
        var cancellationCheck = new AtomicReference<BooleanSupplier>();

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
            MockedStatic<NmsTerrainGenerator> terrain = mockStatic(NmsTerrainGenerator.class);
            MockedStatic<Logging> logging = mockStatic(Logging.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(scheduler);
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            terrain.when(() -> NmsTerrainGenerator.generate(eq(world), anyCollection(), eq(0L),
                org.mockito.ArgumentMatchers.<IntConsumer>any(), org.mockito.ArgumentMatchers.<Consumer<String>>any(),
                org.mockito.ArgumentMatchers.<BooleanSupplier>any(), isNull())).thenAnswer(invocation -> {
                    cancellationCheck.set(invocation.getArgument(5));
                    return generation;
                });
            logging.when(Logging::logger).thenReturn(new Logging.LoggerProxy(null));

            var result = service.regenChunks(Audience.empty(), List.of(new MarkedChunk("world", 1, 0, UUID.randomUUID(),
                1)), true);
            revision.incrementAndGet();

            assertTrue(cancellationCheck.get().getAsBoolean());
            generation.completeExceptionally(new CancellationException("Protection changed"));

            var failure = assertThrows(CompletionException.class, result::join);
            assertInstanceOf(SkipChunkException.class, failure.getCause());
        }
    }

    @Test
    void progressAndCompletionMessagesUseRequestersEntityScheduler() {
        var plugin = mock(Plugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        var world = mock(World.class);
        var scheduler = mock(GlobalRegionScheduler.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        })
            .when(scheduler)
            .execute(eq(plugin), any(Runnable.class));
        var protectionChecks = mock(ProtectionChecks.class);
        when(protectionChecks.revision()).thenReturn(0L);
        when(protectionChecks.check(any(), anyCollection())).thenReturn(CompletableFuture.completedFuture(
            new ProtectionBatchResult(Map.of(), Map.of())));
        var landProtection = mock(LandProtection.class);
        var service = new RegenerationService(plugin, new Messages(), landProtection, protectionChecks);
        var requester = mock(PlayerCommander.class);
        var player = mock(Player.class);
        when(requester.player()).thenReturn(player);
        var chunk = new MarkedChunk("world", 1, 0, UUID.randomUUID(), 1);
        var generation = new CompletableFuture<Void>();
        var progress = new AtomicReference<IntConsumer>();

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
            MockedStatic<NmsTerrainGenerator> terrain = mockStatic(NmsTerrainGenerator.class);
            MockedStatic<Scheduler> dispatch = mockStatic(Scheduler.class);
            MockedStatic<Logging> logging = mockStatic(Logging.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(scheduler);
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            terrain.when(() -> NmsTerrainGenerator.generate(eq(world), anyCollection(), eq(0L),
                org.mockito.ArgumentMatchers.<IntConsumer>any(), org.mockito.ArgumentMatchers.<Consumer<String>>any(),
                org.mockito.ArgumentMatchers.<BooleanSupplier>any(), isNull())).thenAnswer(invocation -> {
                    progress.set(invocation.getArgument(3));
                    return generation;
                });
            logging.when(Logging::logger).thenReturn(new Logging.LoggerProxy(null));

            var result = service.regenChunk(requester, chunk);
            progress.get().accept(25);
            generation.completeExceptionally(new IllegalStateException("generation failed"));

            assertThrows(CompletionException.class, result::join);
            dispatch.verify(() -> Scheduler.runTask(eq(plugin), any(Runnable.class), any(Runnable.class), eq(player)),
                org.mockito.Mockito.times(3));
        }
    }
}
