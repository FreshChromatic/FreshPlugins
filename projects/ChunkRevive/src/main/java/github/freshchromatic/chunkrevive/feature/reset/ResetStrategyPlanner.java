package github.freshchromatic.chunkrevive.feature.reset;

import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Pure partitioning logic for the configurable reset strategy. */
public final class ResetStrategyPlanner {

    public record RegionTarget(String world, AnvilRegionPos region) {}

    public record Plan(List<RegionTarget> deleteRegions,
                       List<MarkedChunk> deleteChunks,
                       List<MarkedChunk> regenerateChunks) {
        public boolean isEmpty() {
            return deleteRegions.isEmpty() && deleteChunks.isEmpty() && regenerateChunks.isEmpty();
        }
    }

    private ResetStrategyPlanner() {}

    public static Plan plan(Collection<MarkedChunk> targets,
                            ResetMethod defaultMethod,
                            ResetMethod completeRegionMethod,
                            ResetMethod incompleteRegionMethod,
                            Predicate<RegionTarget> safeCompleteRegion) {
        Map<RegionTarget, List<MarkedChunk>> grouped = new LinkedHashMap<>();
        for (MarkedChunk chunk : targets) {
            RegionTarget key = new RegionTarget(chunk.world(), AnvilRegionPos.fromChunk(chunk.cx(), chunk.cz()));
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(chunk);
        }

        List<RegionTarget> regions = new ArrayList<>();
        List<MarkedChunk> deletedChunks = new ArrayList<>();
        List<MarkedChunk> regenerated = new ArrayList<>();
        ResetMethod complete = resolve(completeRegionMethod, defaultMethod, true);
        ResetMethod incomplete = resolve(incompleteRegionMethod, defaultMethod, false);

        for (var entry : grouped.entrySet()) {
            boolean completeAndSafe = coversWholeRegion(entry.getKey().region(), entry.getValue())
                && safeCompleteRegion.test(entry.getKey());
            ResetMethod selected = completeAndSafe ? complete : incomplete;
            if (selected == ResetMethod.DELETE_REGION) {
                regions.add(entry.getKey());
            } else if (selected == ResetMethod.DELETE_CHUNK) {
                deletedChunks.addAll(entry.getValue());
            } else {
                regenerated.addAll(entry.getValue());
            }
        }
        return new Plan(List.copyOf(regions), List.copyOf(deletedChunks), List.copyOf(regenerated));
    }

    public static ResetMethod resolve(ResetMethod configured, ResetMethod defaultMethod, boolean completeRegion) {
        ResetMethod resolved = configured == ResetMethod.DEFAULT ? defaultMethod : configured;
        if (resolved == ResetMethod.DEFAULT) resolved = ResetMethod.REGENERATE;
        return !completeRegion && resolved == ResetMethod.DELETE_REGION
            ? ResetMethod.REGENERATE : resolved;
    }

    private static boolean coversWholeRegion(AnvilRegionPos region, List<MarkedChunk> chunks) {
        if (chunks.size() != AnvilRegionPos.WIDTH * AnvilRegionPos.WIDTH) return false;
        java.util.Set<Long> positions = new java.util.HashSet<>();
        for (MarkedChunk chunk : chunks) {
            if (chunk.cx() < region.minChunkX() || chunk.cx() > region.maxChunkX()
                || chunk.cz() < region.minChunkZ() || chunk.cz() > region.maxChunkZ()) return false;
            positions.add((((long) chunk.cx()) << 32) ^ (chunk.cz() & 0xffffffffL));
        }
        return positions.size() == AnvilRegionPos.WIDTH * AnvilRegionPos.WIDTH;
    }
}
