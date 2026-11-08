package github.freshchromatic.chunkrevive.feature.regeneration;

import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegenerationBatchPlannerTest {

    @Test
    void preservesCompleteStructuresEvenWhenTheyExceedTheBatchLimit() {
        PluginConfig config = new PluginConfig();
        config.structure.regen.regenFullStructureRange = true;
        UUID group = UUID.randomUUID();
        List<MarkedChunk> chunks = List.of(marked("world", 0, 0, group, false), marked("world", 10, 10, group, false),
            marked("world", 20, 20, group, false));

        List<List<MarkedChunk>> batches = RegenerationBatchPlanner.plan(chunks, config, 1);

        assertEquals(1, batches.size());
        assertEquals(Set.copyOf(chunks), Set.copyOf(batches.getFirst()));
    }

    @Test
    void groupsAdjacentBiomeChunksWithinTheirWorldAndKeepsOrdinaryChunksSeparate() {
        PluginConfig config = new PluginConfig();
        config.biome.regen.regenFullBiomeRange = true;
        MarkedChunk first = marked("world", -1, -1, null, true);
        MarkedChunk adjacent = marked("world", 0, 0, null, true);
        MarkedChunk otherWorld = marked("other", 0, 0, null, true);
        MarkedChunk ordinary = marked("world", 1, 1, null, false);

        List<List<MarkedChunk>> batches = RegenerationBatchPlanner.plan(List.of(first, adjacent, otherWorld, ordinary),
            config, 128);
        Set<Set<MarkedChunk>> groups = batches.stream().map(Set::copyOf).collect(Collectors.toSet());

        assertEquals(3, groups.size());
        assertTrue(groups.contains(Set.of(first, adjacent)));
        assertTrue(groups.contains(Set.of(otherWorld)));
        assertTrue(groups.contains(Set.of(ordinary)));
    }

    private static MarkedChunk marked(String world, int x, int z, UUID group, boolean biome) {
        return new MarkedChunk(world, x, z, new UUID(0L, 1L), 0L, group, biome);
    }
}
