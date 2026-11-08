package github.freshchromatic.chunkrevive.api;

/** Version of the stable ChunkRevive integration contract. */
public record ApiVersion(int major, int minor, int patch) {

    public static final ApiVersion CURRENT = new ApiVersion(1, 3, 0);

    public ApiVersion {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("version components must not be negative");
        }
    }
}
