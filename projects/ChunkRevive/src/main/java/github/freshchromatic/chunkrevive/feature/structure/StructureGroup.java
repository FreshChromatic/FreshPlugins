package github.freshchromatic.chunkrevive.feature.structure;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record StructureGroup(
    UUID groupId,
    String world,
    String structureId,
    int minChunkX, int maxChunkX,
    int minChunkZ, int maxChunkZ,
    long detectedAt,
    long nextRefreshAt,
    long protectionTicks,
    boolean blocked
) {
    public boolean containsChunk(int cx, int cz) {
        return cx >= minChunkX && cx <= maxChunkX && cz >= minChunkZ && cz <= maxChunkZ;
    }

    public List<int[]> allChunkCoords() {
        var list = new ArrayList<int[]>();
        for (int x = minChunkX; x <= maxChunkX; x++)
            for (int z = minChunkZ; z <= maxChunkZ; z++)
                list.add(new int[]{x, z});
        return list;
    }

    public StructureGroup withProtection(long protectionTicks, boolean blocked) {
        return new StructureGroup(groupId, world, structureId, minChunkX, maxChunkX, minChunkZ, maxChunkZ,
            detectedAt, nextRefreshAt, protectionTicks, blocked);
    }

    public StructureGroup withNextRefreshAt(long nextRefreshAt) {
        return new StructureGroup(groupId, world, structureId, minChunkX, maxChunkX, minChunkZ, maxChunkZ,
            detectedAt, nextRefreshAt, protectionTicks, blocked);
    }

    public String rangeDisplay() {
        return "CX %d~%d / CZ %d~%d".formatted(minChunkX, maxChunkX, minChunkZ, maxChunkZ);
    }
}
