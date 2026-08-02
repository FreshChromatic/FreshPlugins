package github.freshchromatic.chunkrevive.nms.v1_21_11.terrain;

/**
 * Defines which disk-loaded chunks may remain historical terrain inputs while rebuilding a batch.
 *
 * <p>Paper's normal pipeline cannot load terrain metadata from a deleted target: the new
 * {@code ProtoChunk} has no saved biome palette, blending data or below-zero retrogen. Only chunks
 * that survive around the target can be old-noise anchors for {@code Blender}. Regeneration must
 * preserve that distinction even though it reads target NBT to retain structure metadata.
 */
final class BlendingAnchorPolicy {
    /**
     * Blender scans seven chunks from each BIOMES/NOISE center for height and biome samples.
     * ChunkRevive generates biome dependencies three chunks from a regeneration target (two
     * writable FEATURE rings plus one biome-read ring), so disk context must extend ten chunks
     * from the target whenever old-noise terrain is present.
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
