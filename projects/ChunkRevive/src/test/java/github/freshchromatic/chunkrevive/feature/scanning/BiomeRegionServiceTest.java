package github.freshchromatic.chunkrevive.feature.scanning;

import github.freshchromatic.chunkrevive.nms.BiomeMatchMode;
import github.freshchromatic.chunkrevive.nms.BiomeSampler;
import github.freshchromatic.chunkrevive.nms.ChunkCoordinate;
import github.freshchromatic.chunkrevive.nms.ChunkStage;
import github.freshchromatic.chunkrevive.nms.DiskChunkSession;
import github.freshchromatic.chunkrevive.nms.RegionScanResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BiomeRegionServiceTest {

    @Test
    void exactChunkLimitIsNotReportedAsTruncated() {
        var start = new ChunkCoordinate(0, 0);
        var adjacent = new ChunkCoordinate(1, 0);
        var disk = new TestDiskSession(Set.of(adjacent));
        var matcher = matcher(Set.of(start, adjacent));

        var result = new BiomeRegionService(disk, ChunkStage.FULL, 2).detect(0, 0, Set.of("target"), matcher,
            BiomeMatchMode.CENTER);

        assertEquals(2, result.chunks().size());
        assertFalse(result.truncated());
        assertTrue(disk.closed);
    }

    @Test
    void reportsTruncatedOnlyAfterFindingAnotherMatchingChunk() {
        var start = new ChunkCoordinate(0, 0);
        var adjacent = new ChunkCoordinate(1, 0);
        var disk = new TestDiskSession(Set.of(adjacent));
        var matcher = matcher(Set.of(start, adjacent));

        var result = new BiomeRegionService(disk, ChunkStage.FULL, 1).detect(0, 0, Set.of("target"), matcher,
            BiomeMatchMode.CENTER);

        assertEquals(List.of(start), result.chunks());
        assertTrue(result.truncated());
        assertTrue(disk.closed);
    }

    private static BiomeMatcher matcher(Set<ChunkCoordinate> matches) {
        return new BiomeMatcher(new BiomeSampler() {

            @Override
            public boolean matches(int chunkX, int chunkZ, Set<String> biomeIds, BiomeMatchMode mode) {
                return matches.contains(new ChunkCoordinate(chunkX, chunkZ));
            }

            @Override
            public String centerBiome(int chunkX, int chunkZ) {
                return "minecraft:plains";
            }
        });
    }

    private static final class TestDiskSession implements DiskChunkSession {

        private final Set<ChunkCoordinate> existingChunks;
        private boolean                    closed;

        private TestDiskSession(Set<ChunkCoordinate> existingChunks) {
            this.existingChunks = existingChunks;
        }

        @Override
        public Path regionFolder() {
            return Path.of("unused");
        }

        @Override
        public boolean exists(int chunkX, int chunkZ, ChunkStage minimumStage) {
            return existingChunks.contains(new ChunkCoordinate(chunkX, chunkZ));
        }

        @Override
        public RegionScanResult scanRegion(int regionX, int regionZ, ChunkStage minimumStage,
            java.util.function.Predicate<String> structureFilter, java.util.function.BooleanSupplier cancelled) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
