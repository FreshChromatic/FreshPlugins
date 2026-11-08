package github.freshchromatic.chunkrevive.api.integration;

public record ChunkArea(String world, int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {

    public ChunkArea {
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("world must not be blank");
        }
        if (minChunkX > maxChunkX || minChunkZ > maxChunkZ) {
            throw new IllegalArgumentException("minimum chunk coordinates must not exceed maximum coordinates");
        }
    }
}
