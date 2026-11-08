package github.freshchromatic.chunkrevive.nms;

public record ChunkArea(int minX, int maxX, int minZ, int maxZ) {

    public ChunkArea {
        if (minX > maxX || minZ > maxZ) {
            throw new IllegalArgumentException("minimum chunk coordinates must not exceed maximum coordinates");
        }
    }

    public boolean contains(int chunkX, int chunkZ) {
        return chunkX >= minX && chunkX <= maxX && chunkZ >= minZ && chunkZ <= maxZ;
    }
}
