package github.freshchromatic.chunkrevive.feature.regeneration;

import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Splits logical regen groups into bounded, spatially compact execution tiles.
 *
 * <p>A logical structure/biome group is allowed to span several execution tiles. Correct terrain
 * continuity comes from {@code NmsTerrainGenerator}'s deterministic +1/+2/+3 halo, not from retaining
 * the entire logical group in one enormous ProtoChunk graph.
 */
final class SpatialBatchPlanner {
    private SpatialBatchPlanner() {}

    static List<List<MarkedChunk>> split(Collection<? extends List<MarkedChunk>> groups,
                                         int maxTargets, int tileSpan) {
        return split(groups, maxTargets, tileSpan, PluginConfig.Regen.WorkTileMode.LOGICAL, false);
    }

    static List<List<MarkedChunk>> split(Collection<? extends List<MarkedChunk>> groups,
                                         int maxTargets, int tileSpan,
                                         PluginConfig.Regen.WorkTileMode mode,
                                         boolean fixedTileSize) {
        return split(groups, maxTargets, tileSpan, mode, fixedTileSize, false);
    }

    static List<List<MarkedChunk>> split(Collection<? extends List<MarkedChunk>> groups,
                                         int maxTargets, int tileSpan,
                                         PluginConfig.Regen.WorkTileMode mode,
                                         boolean fixedTileSize,
                                         boolean preserveStructureGroups) {
        int cap = Math.max(1, maxTargets);
        int span = Math.max(4, tileSpan);
        List<List<MarkedChunk>> result = new ArrayList<>();
        List<List<MarkedChunk>> splittableGroups = new ArrayList<>();

        for (List<MarkedChunk> group : groups) {
            if (group.isEmpty()) continue;
            if (preserveStructureGroups && isSingleStructureGroup(group)) {
                // Structure decoration is not tile-local: the StructureStart can live outside a
                // target tile and FEATURES may place pieces across several target chunks. Keeping
                // the complete marked structure in one DAG both prevents clipped pieces and avoids
                // rebuilding the same large context halo once per tile.
                result.add(group.stream()
                    .sorted(Comparator.comparing(MarkedChunk::world)
                        .thenComparingInt(MarkedChunk::cx)
                        .thenComparingInt(MarkedChunk::cz))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new)));
            } else {
                splittableGroups.add(group);
            }
        }

        if (mode == PluginConfig.Regen.WorkTileMode.MCA) {
            result.addAll(splitByMca(splittableGroups, cap, fixedTileSize));
            return result;
        }

        for (List<MarkedChunk> group : splittableGroups) {

            List<MarkedChunk> orderedGroup = group.stream()
                .sorted(Comparator.comparing(MarkedChunk::world)
                    .thenComparingInt(MarkedChunk::cx)
                    .thenComparingInt(MarkedChunk::cz))
                .toList();
            // Keep a compact logical group in one graph. Splitting a 99-chunk structure across a
            // fixed grid made two DAGs fight over the same generation pool and light engine. The
            // dilation guard measures the real sparse work set, so this is safe even though context
            // is no longer represented by a bounding rectangle.
            if (orderedGroup.size() <= cap && expandedCountAtMost(orderedGroup, 3, cap * 4) <= cap * 4) {
                result.add(new ArrayList<>(orderedGroup));
                continue;
            }

            Map<TileKey, List<MarkedChunk>> tiles = new LinkedHashMap<>();
            orderedGroup.stream()
                .forEach(chunk -> tiles.computeIfAbsent(
                    new TileKey(chunk.world(), Math.floorDiv(chunk.cx(), span), Math.floorDiv(chunk.cz(), span)),
                    ignored -> new ArrayList<>()).add(chunk));

            for (List<MarkedChunk> tile : tiles.values()) {
                tile.sort(Comparator.comparingInt(MarkedChunk::cx).thenComparingInt(MarkedChunk::cz));
                appendPartitions(result, tile, cap, fixedTileSize);
            }
        }
        return result;
    }

    private static boolean isSingleStructureGroup(List<MarkedChunk> group) {
        java.util.UUID structureGroupId = group.getFirst().structureGroupId();
        return structureGroupId != null
            && group.stream().allMatch(chunk -> structureGroupId.equals(chunk.structureGroupId()));
    }

    /**
     * Uses the same 32x32 coordinate grid as Anvil {@code r.x.z.mca} files. Logical groups are
     * deliberately flattened here: every resulting tile still builds its own deterministic terrain
     * halo, while nearby small structure/biome groups can share one adequately sized DAG.
     */
    private static List<List<MarkedChunk>> splitByMca(
            Collection<? extends List<MarkedChunk>> groups, int cap, boolean fixedTileSize) {
        Comparator<McaKey> keyOrder = Comparator.comparing(McaKey::world)
            .thenComparingInt(McaKey::regionX)
            .thenComparingInt(McaKey::regionZ);
        Map<McaKey, Map<WorldChunkKey, MarkedChunk>> regions = new TreeMap<>(keyOrder);

        for (List<MarkedChunk> group : groups) {
            for (MarkedChunk chunk : group) {
                McaKey key = new McaKey(chunk.world(), Math.floorDiv(chunk.cx(), 32), Math.floorDiv(chunk.cz(), 32));
                regions.computeIfAbsent(key, ignored -> new LinkedHashMap<>())
                    .putIfAbsent(new WorldChunkKey(chunk.world(), chunk.cx(), chunk.cz()), chunk);
            }
        }

        List<List<MarkedChunk>> result = new ArrayList<>();
        for (Map<WorldChunkKey, MarkedChunk> region : regions.values()) {
            List<MarkedChunk> ordered = region.values().stream()
                .sorted(Comparator.comparingInt(MarkedChunk::cx).thenComparingInt(MarkedChunk::cz))
                .toList();
            appendPartitions(result, ordered, cap, fixedTileSize);
        }
        return result;
    }

    private static void appendPartitions(List<List<MarkedChunk>> result, List<MarkedChunk> chunks,
                                         int cap, boolean fixedTileSize) {
        if (chunks.isEmpty()) return;
        if (!fixedTileSize) {
            for (int start = 0; start < chunks.size(); start += cap) {
                result.add(new ArrayList<>(chunks.subList(start, Math.min(start + cap, chunks.size()))));
            }
            return;
        }

        // Use the minimum number of tiles required by the hard cap, then distribute targets evenly.
        // This removes pathological cap+1 splits without padding, duplicating or dropping targets.
        int tileCount = (chunks.size() + cap - 1) / cap;
        int baseSize = chunks.size() / tileCount;
        int largerTiles = chunks.size() % tileCount;
        int start = 0;
        for (int tileIndex = 0; tileIndex < tileCount; tileIndex++) {
            int size = baseSize + (tileIndex < largerTiles ? 1 : 0);
            result.add(new ArrayList<>(chunks.subList(start, start + size)));
            start += size;
        }
    }

    private static int expandedCountAtMost(List<MarkedChunk> chunks, int radius, int stopAfter) {
        java.util.Set<WorldChunkKey> expanded = new java.util.HashSet<>();
        for (MarkedChunk chunk : chunks) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    expanded.add(new WorldChunkKey(chunk.world(), chunk.cx() + dx, chunk.cz() + dz));
                    if (expanded.size() > stopAfter) return expanded.size();
                }
            }
        }
        return expanded.size();
    }

    private record TileKey(String world, int x, int z) {}
    private record McaKey(String world, int regionX, int regionZ) {}
    private record WorldChunkKey(String world, int x, int z) {}
}
