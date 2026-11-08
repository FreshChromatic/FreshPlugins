package github.freshchromatic.chunkrevive.api.operation;

public record ChunkRadius(String world, int centerX, int centerZ, int radius) implements TargetSelection {

    public ChunkRadius {
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("world must not be blank");
        }
        if (radius < 0) {
            throw new IllegalArgumentException("radius must not be negative");
        }
    }
}
