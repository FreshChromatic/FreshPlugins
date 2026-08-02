package github.freshchromatic.chunkrevive.feature.scanning;

import github.freshchromatic.chunkrevive.nms.BiomeMatchMode;
import github.freshchromatic.chunkrevive.nms.ChunkCoordinate;
import github.freshchromatic.chunkrevive.nms.ChunkStage;
import github.freshchromatic.chunkrevive.nms.DiskChunkSession;
import github.freshchromatic.chunkrevive.nms.NmsPlatformLoader;
import org.bukkit.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Flood-fills the already-generated contiguous portion of a biome. */
public final class BiomeRegionService {
    public record DetectResult(List<ChunkCoordinate> chunks, boolean truncated) {}

    private final DiskChunkSession disk;
    private final ChunkStage minimumStage;
    private final int maxChunks;

    public BiomeRegionService(World world, ChunkStage minimumStage, int maxChunks) {
        this.disk = NmsPlatformLoader.load().worldScan().openDiskSession(world);
        this.minimumStage = minimumStage;
        this.maxChunks = Math.max(1, maxChunks);
    }

    public DetectResult detect(int startX, int startZ, Set<String> targets,
                               BiomeMatcher matcher, BiomeMatchMode mode) {
        try (disk) {
            Set<Long> visited = new HashSet<>();
            List<ChunkCoordinate> result = new ArrayList<>();
            ArrayDeque<ChunkCoordinate> queue = new ArrayDeque<>();
            queue.add(new ChunkCoordinate(startX, startZ));
            visited.add(key(startX, startZ));

            while (!queue.isEmpty()) {
                ChunkCoordinate position = queue.poll();
                // A player can only invoke "here biome" from a fully loaded starting chunk. Treat
                // that seed as existing even if its latest state has not reached the region file yet.
                boolean seed = position.x() == startX && position.z() == startZ;
                if (!seed && !disk.exists(position.x(), position.z(), minimumStage)) continue;
                if (!matcher.matches(position.x(), position.z(), targets, mode)) continue;
                result.add(position);
                if (result.size() >= maxChunks) return new DetectResult(result, true);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        int x = position.x() + dx, z = position.z() + dz;
                        if (visited.add(key(x, z))) queue.add(new ChunkCoordinate(x, z));
                    }
                }
            }
            return new DetectResult(result, false);
        }
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }
}
