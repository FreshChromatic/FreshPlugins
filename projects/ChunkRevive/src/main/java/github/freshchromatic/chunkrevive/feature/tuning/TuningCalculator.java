package github.freshchromatic.chunkrevive.feature.tuning;

public final class TuningCalculator {

    private TuningCalculator() {}

    public static TuningProfile recommendedProfile(ServerResourceSnapshot resources) {
        boolean lowSystemMemory = resources.totalSystemMemoryMiB() > 0
            && (double) resources.freeSystemMemoryMiB() / resources.totalSystemMemoryMiB() < 0.15;
        boolean unusuallyHighThreadCount = resources.jvmThreadCount()
            > Math.max(128, resources.logicalProcessors() * 32);
        if (resources.logicalProcessors() <= 4 || resources.maxHeapMiB() <= 4096
                || resources.systemCpuLoad() >= 0.75 || resources.heapUsageRatio() >= 0.75
                || lowSystemMemory || unusuallyHighThreadCount) {
            return TuningProfile.OPTIMIZED;
        }
        return TuningProfile.BALANCED;
    }

    public static TuningRecommendation calculate(ServerResourceSnapshot resources, TuningProfile profile) {
        int processors = Math.max(1, resources.logicalProcessors());
        long heapGiB = Math.max(1, resources.maxHeapMiB() / 1024L);

        // One terrain DAG owns the shared generation pool. CARVERS snapshots are bounded and
        // retained separately, so worker count is governed primarily by short-lived noise/surface
        // allocations rather than the old "4 GiB = 4 workers" estimate.
        int heapThreadCap = heapGiB <= 2 ? 3
            : heapGiB <= 4 ? 8
            : heapGiB <= 8 ? 12
            : heapGiB <= 16 ? 16
            : 24;
        int cpuWorkers = Math.max(1, (int) Math.floor(processors * profile.cpuRatio()));
        int reservedCpuCap = processors <= 2 ? processors : processors - 2;
        int generationThreads = Math.min(Math.max(1, reservedCpuCap), Math.min(cpuWorkers, heapThreadCap));

        // A single per-chunk DAG already saturates the shared ForkJoinPool. Multiple active batches
        // contend for those same workers, duplicate terrain halos and evict each other's CARVERS
        // cache entries; measured throughput is therefore higher with one spatial tile at a time.
        int concurrency = 1;

        int baseTileSize = heapGiB <= 2 ? 48
            : heapGiB <= 4 ? 128
            : heapGiB <= 8 ? 192
            : heapGiB <= 16 ? 256
            : 384;
        int workTileSize = switch (profile) {
            case OPTIMIZED -> Math.min(baseTileSize, 96);
            case BALANCED -> baseTileSize;
            case PERFORMANCE -> Math.min(512, baseTileSize + (heapGiB >= 8 ? 64 : 0));
        };

        int scanThreads = switch (profile) {
            case OPTIMIZED -> Math.max(1, Math.min(3, processors / 3));
            case BALANCED -> Math.max(2, Math.min(6, processors / 2));
            case PERFORMANCE -> Math.max(2, Math.min(12, processors));
        };

        int applyBatchSize = Math.max(1, profile.applyBatchSize());
        return new TuningRecommendation(
            profile,
            generationThreads,
            profile.generationPriority(),
            profile.batchDelayTicks(),
            concurrency,
            0,
            workTileSize,
            applyBatchSize,
            "CONFIG",
            "CONFIG",
            "CONFIG",
            profile.heapHighWatermarkPercent(),
            scanThreads,
            profile.scanPriority()
        );
    }
}
