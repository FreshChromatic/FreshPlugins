package github.freshchromatic.chunkrevive.nms;

/** Pure admission policy for bounded pre-FEATURES terrain snapshots. */
public final class CarverStageCachePolicy {
    private static final int TILE_OVERLAP_RADIUS = 2;

    private CarverStageCachePolicy() {}

    /**
     * Context-free halo snapshots are safe in normal terrain. Old-noise/blended snapshots and
     * writable target snapshots are safe only when the last successful operation had the exact
     * same generation scope.
     */
    public static boolean mayReuse(
            boolean cacheEnabled, boolean oldNoiseAround,
            boolean exactScopeReplay, boolean targetChunk) {
        return cacheEnabled
            && (!oldNoiseAround || exactScopeReplay)
            && (!targetChunk || exactScopeReplay);
    }

    public static boolean shouldRetain(
            boolean cacheEnabled, boolean retainAll,
            boolean targetChunk, int chunkX, int chunkZ,
            int targetMinX, int targetMaxX, int targetMinZ, int targetMaxZ) {
        if (!cacheEnabled) return false;
        if (retainAll || !targetChunk) return true;
        return chunkX - targetMinX < TILE_OVERLAP_RADIUS
            || targetMaxX - chunkX < TILE_OVERLAP_RADIUS
            || chunkZ - targetMinZ < TILE_OVERLAP_RADIUS
            || targetMaxZ - chunkZ < TILE_OVERLAP_RADIUS;
    }
}
