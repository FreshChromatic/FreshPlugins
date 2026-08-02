package github.freshchromatic.chunkrevive.feature.reset;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class AnvilRegionPosTest {

    @Test
    void mapsPositiveAndNegativeChunkCoordinatesWithFloorSemantics() {
        assertEquals(new AnvilRegionPos(0, 0), AnvilRegionPos.fromChunk(0, 31));
        assertEquals(new AnvilRegionPos(1, 1), AnvilRegionPos.fromChunk(32, 63));
        assertEquals(new AnvilRegionPos(-1, -1), AnvilRegionPos.fromChunk(-1, -32));
        assertEquals(new AnvilRegionPos(-2, -2), AnvilRegionPos.fromChunk(-33, -64));
    }

    @Test
    void exposesInclusiveChunkBounds() {
        AnvilRegionPos region = new AnvilRegionPos(-2, 3);
        assertEquals(-64, region.minChunkX());
        assertEquals(-33, region.maxChunkX());
        assertEquals(96, region.minChunkZ());
        assertEquals(127, region.maxChunkZ());
    }
}
