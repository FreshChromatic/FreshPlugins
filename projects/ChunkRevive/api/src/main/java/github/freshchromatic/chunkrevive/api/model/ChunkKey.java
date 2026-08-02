package github.freshchromatic.chunkrevive.api.model;

public record ChunkKey(String world, int x, int z) {
    public ChunkKey {
        if (world == null || world.isBlank()) throw new IllegalArgumentException("world must not be blank");
    }
}
