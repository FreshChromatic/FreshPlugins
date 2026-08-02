package github.freshchromatic.chunkrevive.feature.reset;

import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ResetStrategyPlannerTest {

    @Test
    void completeSafeRegionCanUseRegionDeletion() {
        var chunks = completeRegion("world", 2, -3);
        var plan = ResetStrategyPlanner.plan(chunks,
            ResetMethod.REGENERATE, ResetMethod.DELETE_REGION, ResetMethod.DELETE_CHUNK,
            ignored -> true);

        assertEquals(1, plan.deleteRegions().size());
        assertTrue(plan.deleteChunks().isEmpty());
        assertTrue(plan.regenerateChunks().isEmpty());
        assertEquals(new AnvilRegionPos(2, -3), plan.deleteRegions().getFirst().region());
    }

    @Test
    void unsafeOrIncompleteRegionUsesChunkFallback() {
        var chunks = completeRegion("world", 0, 0);
        chunks.removeLast();
        var plan = ResetStrategyPlanner.plan(chunks,
            ResetMethod.REGENERATE, ResetMethod.DELETE_REGION, ResetMethod.DELETE_CHUNK,
            ignored -> true);
        assertEquals(1023, plan.deleteChunks().size());
        assertTrue(plan.deleteRegions().isEmpty());

        plan = ResetStrategyPlanner.plan(completeRegion("world", 0, 0),
            ResetMethod.REGENERATE, ResetMethod.DELETE_REGION, ResetMethod.DELETE_CHUNK,
            ignored -> false);
        assertEquals(1024, plan.deleteChunks().size());
        assertTrue(plan.deleteRegions().isEmpty());
    }

    @Test
    void deleteRegionIsNeverAppliedToIncompleteTargets() {
        var one = List.of(chunk("world", 0, 0));
        var plan = ResetStrategyPlanner.plan(one,
            ResetMethod.DELETE_REGION, ResetMethod.DEFAULT, ResetMethod.DEFAULT,
            ignored -> true);
        assertEquals(1, plan.regenerateChunks().size());
        assertTrue(plan.deleteRegions().isEmpty());
    }

    private static ArrayList<MarkedChunk> completeRegion(String world, int rx, int rz) {
        var region = new AnvilRegionPos(rx, rz);
        var result = new ArrayList<MarkedChunk>(1024);
        for (int cx = region.minChunkX(); cx <= region.maxChunkX(); cx++) {
            for (int cz = region.minChunkZ(); cz <= region.maxChunkZ(); cz++) {
                result.add(chunk(world, cx, cz));
            }
        }
        return result;
    }

    private static MarkedChunk chunk(String world, int cx, int cz) {
        return new MarkedChunk(world, cx, cz, UUID.randomUUID(), 1L);
    }
}
