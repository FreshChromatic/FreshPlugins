package github.freshchromatic.chunkrevive.feature.scanning;

import github.freshchromatic.chunkrevive.api.integration.ProtectionBatchResult;
import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.feature.marking.MarkRegistry;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import github.freshchromatic.chunkrevive.feature.structure.StructureRegistry;
import github.freshchromatic.chunkrevive.integration.protection.LandProtection;
import github.freshchromatic.chunkrevive.integration.protection.ProtectionChecks;
import github.freshchromatic.chunkrevive.nms.ChunkCoordinate;
import github.freshchromatic.chunkrevive.nms.DiskChunkSession;
import github.freshchromatic.chunkrevive.nms.RegionScanResult;
import github.freshchromatic.chunkrevive.nms.StoredChunk;
import github.freshchromatic.chunkrevive.nms.WorldScanGateway;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class DiskChunkScannerTest {

    @Test
    void scanAreaUsesLongDistanceAtIntegerCoordinateBounds() {
        var area = new DiskChunkScanner.ScanArea(Integer.MIN_VALUE, 0, Integer.MAX_VALUE);

        assertTrue(area.containsChunk(-1, 0));
        assertFalse(area.containsChunk(31, 0));
    }

    @Test
    void asyncRegionCountRunsOutsideTheCallingThread(@TempDir Path directory) throws Exception {
        Files.createFile(directory.resolve("r.0.0.mca"));
        var world = mock(World.class);
        var session = mock(DiskChunkSession.class);
        when(session.regionFolder()).thenReturn(directory);
        var gateway = mock(WorldScanGateway.class);
        var countRanVirtually = new AtomicBoolean();
        when(gateway.openDiskSession(world)).thenAnswer(invocation -> {
            countRanVirtually.set(Thread.currentThread().isVirtual());
            return session;
        });
        var config = new PluginConfig();
        var scanner = new DiskChunkScanner(mock(MarkRegistry.class), mock(StructureRegistry.class), mock(
            LandProtection.class), config, mock(ProtectionChecks.class), gateway, Runnable::run);

        assertEquals(1, scanner.countRegionFilesAsync(world, null).get(5, TimeUnit.SECONDS));
        assertTrue(countRanVirtually.get());
    }

    @Test
    void diskScanDoesNotIncludeOutOfRadiusChunkWhenDistanceWouldOverflow(@TempDir Path directory) throws Exception {
        Files.createFile(directory.resolve("r.0.0.mca"));
        var world = mock(World.class);
        when(world.getName()).thenReturn("world");
        var session = mock(DiskChunkSession.class);
        when(session.regionFolder()).thenReturn(directory);
        when(session.scanRegion(anyInt(), anyInt(), any(), any(), any())).thenReturn(new RegionScanResult(List.of(chunk(
            31, 0)), 0));
        var gateway = mock(WorldScanGateway.class);
        when(gateway.openDiskSession(world)).thenReturn(session);
        var marks = mock(MarkRegistry.class);
        var config = new PluginConfig();
        config.landProtection.scanClaims = false;
        config.structure.enabled = false;
        var scanner = new DiskChunkScanner(marks, mock(StructureRegistry.class), mock(LandProtection.class), config,
            mock(ProtectionChecks.class), gateway, Runnable::run);

        var result = scanner.scan(world, new DiskChunkScanner.ScanArea(Integer.MIN_VALUE, 0, Integer.MAX_VALUE),
            null).get(5, TimeUnit.SECONDS);

        assertEquals(0, result.chunksFound());
        verify(marks, never()).markChunksPersisted(any());
    }

    @Test
    void scansRegionFilesAndReportsMarkedSkippedAndFailedCounts(@TempDir Path directory) throws Exception {
        Files.createFile(directory.resolve("r.0.0.mca"));
        Files.createFile(directory.resolve("r.-1.0.mca"));
        Files.createFile(directory.resolve("not-a-region.txt"));
        var world = mock(World.class);
        when(world.getName()).thenReturn("world");
        var session = mock(DiskChunkSession.class);
        when(session.regionFolder()).thenReturn(directory);
        when(session.scanRegion(anyInt(), anyInt(), any(), any(), any())).thenAnswer(invocation -> {
            int x = invocation.getArgument(0);
            return x == 0 ? new RegionScanResult(List.of(chunk(0, 0), chunk(1, 0), chunk(9, 0)), 1)
                : new RegionScanResult(List.of(chunk(-1, 0)), 2);
        });
        var gateway = mock(WorldScanGateway.class);
        when(gateway.openDiskSession(world)).thenReturn(session);
        var marks = mock(MarkRegistry.class);
        when(marks.markChunksPersisted(any())).thenAnswer(invocation -> {
            List<MarkedChunk> chunks = invocation.getArgument(0);
            return CompletableFuture.completedFuture(chunks.subList(0, chunks.size() - 1));
        });
        var config = new PluginConfig();
        config.landProtection.scanClaims = false;
        config.structure.enabled = false;
        var scanner = new DiskChunkScanner(marks, mock(StructureRegistry.class), mock(LandProtection.class), config,
            mock(ProtectionChecks.class), gateway, Runnable::run);

        var result = scanner.scan(world, new DiskChunkScanner.ScanArea(0, 0, 3), null).get(5, TimeUnit.SECONDS);

        assertEquals(new DiskChunkScanner.ScanResult(2, 2, 3, 1, 2, 0, 3, 0, false), result);
    }

    @Test
    void diskScanChecksLandProtectionOnGlobalScheduler(@TempDir Path directory) throws Exception {
        Files.createFile(directory.resolve("r.0.0.mca"));
        var world = mock(World.class);
        when(world.getName()).thenReturn("world");
        var session = mock(DiskChunkSession.class);
        when(session.regionFolder()).thenReturn(directory);
        when(session.scanRegion(anyInt(), anyInt(), any(), any(), any())).thenReturn(new RegionScanResult(List.of(chunk(
            0, 0)), 0));
        var gateway = mock(WorldScanGateway.class);
        when(gateway.openDiskSession(world)).thenReturn(session);
        var inGlobalCallback = new AtomicBoolean();
        var landProtection = mock(LandProtection.class);
        when(landProtection.hasClaim(any(), anyInt(), anyInt())).thenAnswer(invocation -> {
            assertTrue(inGlobalCallback.get());
            return false;
        });
        var protection = mock(ProtectionChecks.class);
        when(protection.checkExternal(any(), any())).thenReturn(CompletableFuture.completedFuture(
            new ProtectionBatchResult(Map.of(), Map.of())));
        var marks = mock(MarkRegistry.class);
        when(marks.markChunksPersisted(any())).thenAnswer(invocation -> CompletableFuture.completedFuture(
            invocation.getArgument(0)));
        var config = new PluginConfig();
        config.landProtection.scanClaims = true;
        config.structure.enabled = false;
        var scanner = new DiskChunkScanner(marks, mock(StructureRegistry.class), landProtection, config, protection,
            gateway, task -> {
                boolean wasInGlobalCallback = inGlobalCallback.getAndSet(true);
                try {
                    task.run();
                } finally {
                    inGlobalCallback.set(wasInGlobalCallback);
                }
            });

        var result = scanner.scan(world, null, null).get(5, TimeUnit.SECONDS);

        assertEquals(1, result.chunksMarked());
    }

    @Test
    void regionCountReportsDirectoryReadFailure(@TempDir Path directory) throws Exception {
        var world = mock(World.class);
        when(world.getName()).thenReturn("world");
        var session = mock(DiskChunkSession.class);
        when(session.regionFolder()).thenReturn(directory);
        var gateway = mock(WorldScanGateway.class);
        when(gateway.openDiskSession(world)).thenReturn(session);
        var scanner = new DiskChunkScanner(mock(MarkRegistry.class), mock(StructureRegistry.class), mock(
            LandProtection.class), new PluginConfig(), mock(ProtectionChecks.class), mock(Plugin.class), gateway);
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.list(directory)).thenThrow(new IOException("directory unavailable"));

            var failure = assertThrows(UncheckedIOException.class, () -> scanner.countRegionFiles(world, null));

            assertEquals("directory unavailable", failure.getCause().getMessage());
        }
    }

    @Test
    void distinguishesMissingRegionDirectoryFromAFileAtThatPath(@TempDir Path directory) throws Exception {
        var world = mock(World.class);
        when(world.getName()).thenReturn("world");
        var session = mock(DiskChunkSession.class);
        Path regionPath = directory.resolve("region");
        when(session.regionFolder()).thenReturn(regionPath);
        var gateway = mock(WorldScanGateway.class);
        when(gateway.openDiskSession(world)).thenReturn(session);
        var scanner = new DiskChunkScanner(mock(MarkRegistry.class), mock(StructureRegistry.class), mock(
            LandProtection.class), new PluginConfig(), mock(ProtectionChecks.class), mock(Plugin.class), gateway);

        assertEquals(0, scanner.countRegionFiles(world, null));
        Files.createFile(regionPath);
        assertThrows(UncheckedIOException.class, () -> scanner.countRegionFiles(world, null));
    }

    @Test
    void scanReportsRegionReadFailure(@TempDir Path directory) throws Exception {
        Files.createFile(directory.resolve("r.-1.2.mca"));
        var world = mock(World.class);
        when(world.getName()).thenReturn("world");
        var session = mock(DiskChunkSession.class);
        when(session.regionFolder()).thenReturn(directory);
        when(session.scanRegion(anyInt(), anyInt(), any(), any(), any())).thenThrow(new IOException(
            "disk read failed"));
        var gateway = mock(WorldScanGateway.class);
        when(gateway.openDiskSession(world)).thenReturn(session);
        var scanner = new DiskChunkScanner(mock(MarkRegistry.class), mock(StructureRegistry.class), mock(
            LandProtection.class), new PluginConfig(), mock(ProtectionChecks.class), mock(Plugin.class), gateway);

        var failure = assertThrows(ExecutionException.class, () -> scanner.scan(world, null, null).get(5,
            TimeUnit.SECONDS));

        assertTrue(failure.getCause().getMessage().contains("r.-1.2.mca"));
        assertEquals("disk read failed", failure.getCause().getCause().getCause().getMessage());
    }

    @Test
    void cancelledScanReturnsCancelledStatus(@TempDir Path directory) throws Exception {
        Files.createFile(directory.resolve("r.0.0.mca"));
        var world = mock(World.class);
        when(world.getName()).thenReturn("world");
        var started = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        var session = mock(DiskChunkSession.class);
        when(session.regionFolder()).thenReturn(directory);
        when(session.scanRegion(anyInt(), anyInt(), any(), any(), any())).thenAnswer(invocation -> {
            started.countDown();
            assertTrue(proceed.await(5, TimeUnit.SECONDS));
            return new RegionScanResult(List.of(chunk(0, 0)), 0);
        });
        var gateway = mock(WorldScanGateway.class);
        when(gateway.openDiskSession(world)).thenReturn(session);
        var config = new PluginConfig();
        config.landProtection.scanClaims = false;
        var scanner = new DiskChunkScanner(mock(MarkRegistry.class), mock(StructureRegistry.class), mock(
            LandProtection.class), config, mock(ProtectionChecks.class), mock(Plugin.class), gateway);

        var future = scanner.scan(world, null, null);
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertTrue(scanner.cancel("world"));
        proceed.countDown();
        assertTrue(future.get(5, TimeUnit.SECONDS).cancelled());
    }

    @Test
    void cancellationDuringExternalProtectionCheckReturnsCancelled(@TempDir Path directory) throws Exception {
        Files.createFile(directory.resolve("r.0.0.mca"));
        var world = mock(World.class);
        when(world.getName()).thenReturn("world");
        var session = mock(DiskChunkSession.class);
        when(session.regionFolder()).thenReturn(directory);
        when(session.scanRegion(anyInt(), anyInt(), any(), any(), any())).thenReturn(new RegionScanResult(List.of(chunk(
            0, 0)), 0));
        var gateway = mock(WorldScanGateway.class);
        when(gateway.openDiskSession(world)).thenReturn(session);
        var started = new CountDownLatch(1);
        var decision = new CompletableFuture<ProtectionBatchResult>();
        var protection = mock(ProtectionChecks.class);
        when(protection.checkExternal(any(), any())).thenAnswer(invocation -> {
            started.countDown();
            return decision;
        });
        var config = new PluginConfig();
        config.structure.enabled = false;
        config.landProtection.scanClaims = true;
        var marks = mock(MarkRegistry.class);
        var scanner = new DiskChunkScanner(marks, mock(StructureRegistry.class), mock(LandProtection.class), config,
            protection, gateway, Runnable::run);

        var future = scanner.scan(world, null, null);
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertTrue(scanner.cancel("world"));
        decision.complete(new ProtectionBatchResult(Map.of(), Map.of()));

        var result = future.get(5, TimeUnit.SECONDS);
        assertTrue(result.cancelled());
        assertEquals(0, result.chunksSkippedClaimed());
        verify(marks, never()).markChunksPersisted(any());
    }

    private static StoredChunk chunk(int x, int z) {
        return new StoredChunk(new ChunkCoordinate(x, z), List.of());
    }
}
