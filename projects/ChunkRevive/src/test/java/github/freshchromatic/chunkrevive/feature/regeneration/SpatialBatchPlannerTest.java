package github.freshchromatic.chunkrevive.feature.regeneration;

import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpatialBatchPlannerTest {
    @Test
    void boundsHugeLogicalGroupsAndPreservesEveryTarget() {
        List<MarkedChunk> group = new ArrayList<>();
        for (int x = 0; x < 40; x++) {
            for (int z = 0; z < 20; z++) {
                group.add(new MarkedChunk("world", x, z, new java.util.UUID(0L, 1L), 0L, null, false));
            }
        }

        List<List<MarkedChunk>> tiles = SpatialBatchPlanner.split(List.of(group), 128, 16);

        assertEquals(group.size(), tiles.stream().mapToInt(List::size).sum());
        assertTrue(tiles.stream().allMatch(tile -> tile.size() <= 128));
        assertTrue(tiles.stream().allMatch(tile -> {
            int minX = tile.stream().mapToInt(MarkedChunk::cx).min().orElseThrow();
            int maxX = tile.stream().mapToInt(MarkedChunk::cx).max().orElseThrow();
            int minZ = tile.stream().mapToInt(MarkedChunk::cz).min().orElseThrow();
            int maxZ = tile.stream().mapToInt(MarkedChunk::cz).max().orElseThrow();
            return maxX - minX < 16 && maxZ - minZ < 16;
        }));
    }

    @Test
    void keepsNegativeCoordinatesInsideStableFloorDivTiles() {
        List<MarkedChunk> group = List.of(
            marked(-17, -17), marked(-16, -16), marked(-1, -1), marked(0, 0), marked(15, 15), marked(16, 16));

        List<List<MarkedChunk>> tiles = SpatialBatchPlanner.split(List.of(group), 128, 16);

        assertEquals(1, tiles.size());
        assertEquals(group.size(), tiles.stream().mapToInt(List::size).sum());
    }

    @Test
    void keepsCompactStructureSizedGroupInOneDagAcrossGridBoundary() {
        List<MarkedChunk> group = new ArrayList<>();
        for (int x = 8; x < 19; x++) {
            for (int z = 8; z < 17; z++) group.add(marked(x, z));
        }

        List<List<MarkedChunk>> tiles = SpatialBatchPlanner.split(List.of(group), 128, 16);

        assertEquals(1, tiles.size());
        assertEquals(99, tiles.getFirst().size());
    }

    @Test
    void preservesOversizedStructureGroupWhenFullRangeRegenIsEnabled() {
        java.util.UUID structureId = new java.util.UUID(0L, 42L);
        List<MarkedChunk> structure = new ArrayList<>();
        for (int x = -16; x < 0; x++) {
            for (int z = -15; z < 0; z++) {
                structure.add(new MarkedChunk(
                    "world", x, z, new java.util.UUID(0L, 1L), 0L, structureId, false));
            }
        }

        List<List<MarkedChunk>> tiles = SpatialBatchPlanner.split(
            List.of(structure), 128, 16, PluginConfig.Regen.WorkTileMode.LOGICAL, false, true);

        assertEquals(1, tiles.size());
        assertEquals(240, tiles.getFirst().size());
    }

    @Test
    void preservesStructureGroupEvenInMcaSchedulingMode() {
        java.util.UUID structureId = new java.util.UUID(0L, 43L);
        List<MarkedChunk> structure = new ArrayList<>();
        for (int x = -20; x < 20; x++) {
            for (int z = -4; z < 4; z++) {
                structure.add(new MarkedChunk(
                    "world", x, z, new java.util.UUID(0L, 1L), 0L, structureId, false));
            }
        }

        List<List<MarkedChunk>> tiles = SpatialBatchPlanner.split(
            List.of(structure), 128, 16, PluginConfig.Regen.WorkTileMode.MCA, false, true);

        assertEquals(1, tiles.size());
        assertEquals(320, tiles.getFirst().size());
    }

    @Test
    void mcaModeCombinesLogicalGroupsAndBalancesRemainder() {
        List<MarkedChunk> first = new ArrayList<>();
        List<MarkedChunk> second = new ArrayList<>();
        for (int index = 0; index < 193; index++) {
            MarkedChunk chunk = marked(index / 32, index % 32);
            (index < 80 ? first : second).add(chunk);
        }

        List<List<MarkedChunk>> tiles = SpatialBatchPlanner.split(
            List.of(first, second), 128, 16, PluginConfig.Regen.WorkTileMode.MCA, true);

        assertEquals(List.of(97, 96), tiles.stream().map(List::size).toList());
        assertEquals(193, tiles.stream().mapToInt(List::size).sum());
    }

    @Test
    void fixedSizeBalancesAFullMcaWithoutExceedingCap() {
        List<MarkedChunk> region = new ArrayList<>();
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) region.add(marked(x, z));
        }

        List<List<MarkedChunk>> tiles = SpatialBatchPlanner.split(
            List.of(region), 192, 16, PluginConfig.Regen.WorkTileMode.MCA, true);

        assertEquals(6, tiles.size());
        assertEquals(1024, tiles.stream().mapToInt(List::size).sum());
        assertTrue(tiles.stream().allMatch(tile -> tile.size() >= 170 && tile.size() <= 171));
    }

    @Test
    void mcaModeUsesFloorDivisionAtNegativeRegionBoundaries() {
        List<MarkedChunk> targets = List.of(marked(-33, 0), marked(-32, 0), marked(-1, 0), marked(0, 0));

        List<List<MarkedChunk>> tiles = SpatialBatchPlanner.split(
            List.of(targets), 128, 16, PluginConfig.Regen.WorkTileMode.MCA, true);

        assertEquals(3, tiles.size());
        assertEquals(List.of(1, 2, 1), tiles.stream().map(List::size).toList());
    }

    @Test
    void nonFixedMcaModeRetainsCapThenRemainderBehavior() {
        List<MarkedChunk> targets = new ArrayList<>();
        for (int index = 0; index < 193; index++) targets.add(marked(index / 32, index % 32));

        List<List<MarkedChunk>> tiles = SpatialBatchPlanner.split(
            List.of(targets), 128, 16, PluginConfig.Regen.WorkTileMode.MCA, false);

        assertEquals(List.of(128, 65), tiles.stream().map(List::size).toList());
    }

    private static MarkedChunk marked(int x, int z) {
        return new MarkedChunk("world", x, z, new java.util.UUID(0L, 1L), 0L, null, false);
    }
}
