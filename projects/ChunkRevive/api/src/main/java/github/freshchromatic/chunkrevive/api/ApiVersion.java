package github.freshchromatic.chunkrevive.api;

/** Version of the stable ChunkRevive integration contract. */
public record ApiVersion(int major, int minor, int patch) {
    public static final ApiVersion CURRENT = new ApiVersion(1, 0, 0);
}
