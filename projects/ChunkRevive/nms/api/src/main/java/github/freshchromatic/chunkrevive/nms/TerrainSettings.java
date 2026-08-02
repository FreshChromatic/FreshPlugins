package github.freshchromatic.chunkrevive.nms;

/** Immutable terrain settings so version modules never depend on the plugin configuration classes. */
public record TerrainSettings(
    EntityExemptions entityExemptions,
    ThreadPool threadPool,
    MemorySafety memorySafety,
    int contextRadius,
    int applyBatchSize,
    boolean debugLogging
) {
    public record EntityExemptions(
        boolean keepRidden,
        boolean keepLeashed,
        boolean keepAllayAttracted,
        boolean keepTamedPets,
        double tamedPetOwnerRadius
    ) {}

    public record ThreadPool(String parallelism, int priority, boolean daemon, boolean asyncMode) {}

    public record MemorySafety(boolean enabled, String maxGenerationThreads) {}
}
