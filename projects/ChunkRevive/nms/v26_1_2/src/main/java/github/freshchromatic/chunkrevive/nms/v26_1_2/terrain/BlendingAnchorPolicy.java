package github.freshchromatic.chunkrevive.nms.v26_1_2.terrain;

/**
 * Keeps regeneration targets equivalent to deleted chunks while retaining surviving old-noise
 * neighbours as Blender inputs.
 */
final class BlendingAnchorPolicy {
    /**
     * Blender scans seven chunks from each BIOMES/NOISE center. ChunkRevive generates biome
     * dependencies three chunks from a target, so old-noise disk context must extend ten chunks.
     */
    static final int OLD_NOISE_DISK_CONTEXT_RADIUS = 10;

    private BlendingAnchorPolicy() {}

    static boolean preserveHistoricalTerrainMetadata(boolean regenerationTarget) {
        return !regenerationTarget;
    }

    static int requiredDiskContextRadius(int normalContextRadius, boolean oldNoiseAround) {
        return oldNoiseAround
            ? Math.max(normalContextRadius, OLD_NOISE_DISK_CONTEXT_RADIUS)
            : normalContextRadius;
    }

    static boolean mayUseContextFreeCarverCache(boolean oldNoiseAround) {
        return !oldNoiseAround;
    }
}
