package github.freshchromatic.chunkrevive.feature.reset;

/** Coordinates of one physical Anvil region file (32x32 chunks). */
public record AnvilRegionPos(int x, int z) {

    public static final int WIDTH = 32;

    public static AnvilRegionPos fromChunk(int chunkX, int chunkZ) {
        return new AnvilRegionPos(chunkX >> 5, chunkZ >> 5);
    }

    public int minChunkX() {
        return x << 5;
    }

    public int minChunkZ() {
        return z << 5;
    }

    public int maxChunkX() {
        return minChunkX() + WIDTH - 1;
    }

    public int maxChunkZ() {
        return minChunkZ() + WIDTH - 1;
    }

    public String display() {
        return "r." + x + "." + z;
    }
}
