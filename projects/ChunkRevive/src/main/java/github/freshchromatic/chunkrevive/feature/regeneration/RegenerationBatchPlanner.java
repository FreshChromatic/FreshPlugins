package github.freshchromatic.chunkrevive.feature.regeneration;

import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

final class RegenerationBatchPlanner {

    private RegenerationBatchPlanner() {
    }

    static List<List<MarkedChunk>> plan(Collection<MarkedChunk> chunks, PluginConfig config, int maxSize) {
        boolean regenFullStructureRange = config.structure.regen.regenFullStructureRange;
        boolean regenFullBiomeRange = config.biome.regen.regenFullBiomeRange;

        Map<UUID, List<MarkedChunk>> byStructureGroup = new LinkedHashMap<>();
        List<MarkedChunk> biomeAtomic = new ArrayList<>();
        List<MarkedChunk> capped = new ArrayList<>();

        for (MarkedChunk c : chunks) {
            if (c.structureGroupId() != null && regenFullStructureRange) {
                byStructureGroup.computeIfAbsent(c.structureGroupId(), k -> new ArrayList<>()).add(c);
            } else if (c.structureGroupId() == null && c.biomeRegen() && regenFullBiomeRange) {
                biomeAtomic.add(c);
            } else {
                capped.add(c);
            }
        }

        // Preserve logical structure/biome boundaries for ordering and completion accounting. Ordinary
        // and biome groups are subsequently divided into bounded spatial work tiles. Complete structure
        // groups remain atomic below so one StructureStart/FEATURES graph owns every marked piece.
        List<List<MarkedChunk>> structureGroups = new ArrayList<>(byStructureGroup.values());
        List<List<MarkedChunk>> biomeGroups = groupAdjacentChunks(biomeAtomic);
        List<List<MarkedChunk>> logicalGroups = new ArrayList<>();
        logicalGroups.addAll(structureGroups);
        logicalGroups.addAll(biomeGroups);
        logicalGroups.addAll(groupAdjacentChunks(capped));

        // Bound ordinary/biome scratch generation graphs. With full-structure-range enabled the planner
        // deliberately exempts complete structure groups: splitting one repeats its halo and can omit the
        // remote StructureStart needed to decorate a tile at the opposite edge.
        int tileCap = Math.max(1, config.regen.workTileSize);
        if (maxSize > 0) {
            tileCap = Math.min(tileCap, maxSize);
        }
        return SpatialBatchPlanner.split(logicalGroups, tileCap, config.regen.workTileSpan,
            config.regen.workTileModeEnum(), config.regen.fixedWorkTileSize, regenFullStructureRange);
    }

    /**
     * BFS-groups chunks by 8-neighbor adjacency using a coordinate-keyed lookup (O(n) overall) instead of
     * scanning the whole chunk list per BFS step (the old O(n^2) approach could take many seconds — long
     * enough to trip the Folia watchdog — once a few thousand chunks are marked, e.g. after /cr fullmark).
     */
    private static List<List<MarkedChunk>> groupAdjacentChunks(Collection<MarkedChunk> chunks) {
        List<List<MarkedChunk>> groups = new ArrayList<>();
        Map<String, Map<Long, MarkedChunk>> byWorld = new HashMap<>();
        for (MarkedChunk c : chunks) {
            byWorld.computeIfAbsent(c.world(), k -> new HashMap<>()).put(chunkKey(c.cx(), c.cz()), c);
        }

        for (Map<Long, MarkedChunk> worldChunks : byWorld.values()) {
            Set<Long> visited = new HashSet<>();
            for (Long startKey : worldChunks.keySet()) {
                if (!visited.add(startKey)) {
                    continue;
                }
                List<MarkedChunk> group = new ArrayList<>();
                Queue<Long> queue = new LinkedList<>();
                queue.add(startKey);
                while (!queue.isEmpty()) {
                    long currKey = queue.poll();
                    MarkedChunk curr = worldChunks.get(currKey);
                    group.add(curr);
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dz == 0) {
                                continue;
                            }
                            long neighborKey = chunkKey(curr.cx() + dx, curr.cz() + dz);
                            if (worldChunks.containsKey(neighborKey) && visited.add(neighborKey)) {
                                queue.add(neighborKey);
                            }
                        }
                    }
                }
                groups.add(group);
            }
        }
        return groups;
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }
}
