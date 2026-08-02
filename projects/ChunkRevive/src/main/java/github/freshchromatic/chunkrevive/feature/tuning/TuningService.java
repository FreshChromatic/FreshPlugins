package github.freshchromatic.chunkrevive.feature.tuning;

import github.freshchromatic.chunkrevive.bootstrap.ChunkRevivePlugin;
import github.freshchromatic.chunkrevive.config.PluginConfig;

public final class TuningService {
    private final ChunkRevivePlugin plugin;

    public TuningService(ChunkRevivePlugin plugin) {
        this.plugin = plugin;
    }

    public ServerResourceSnapshot captureResources() {
        return ServerResourceSnapshot.capture(isFolia());
    }

    public TuningRecommendation calculate(ServerResourceSnapshot resources, TuningProfile profile) {
        return TuningCalculator.calculate(resources, profile);
    }

    public boolean apply(TuningRecommendation recommendation) {
        PluginConfig config = plugin.getPluginConfig();
        Values previous = Values.capture(config);
        Values.from(recommendation).writeTo(config);
        config.regen.memorySafety.enabled = true;

        if (plugin.saveAndApplyConfig()) return true;

        previous.writeTo(config);
        return false;
    }

    private static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer", false,
                TuningService.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    private record Values(
        String generationThreads,
        int generationPriority,
        int batchDelayTicks,
        int batchConcurrency,
        int maxChunksPerBatch,
        int workTileSize,
        int applyBatchSize,
        boolean memorySafetyEnabled,
        String maxActiveBatches,
        String memorySafeBatchSize,
        String maxGenerationThreads,
        int heapHighWatermarkPercent,
        int scanThreads,
        int scanPriority
    ) {
        static Values capture(PluginConfig config) {
            return new Values(
                config.regen.threadPool.parallelism,
                config.regen.threadPool.priority,
                config.regen.batchDelayTicks,
                config.regen.batchConcurrency,
                config.regen.maxChunksPerBatch,
                config.regen.workTileSize,
                config.regen.applyBatchSize,
                config.regen.memorySafety.enabled,
                config.regen.memorySafety.maxActiveBatches,
                config.regen.memorySafety.maxChunksPerBatch,
                config.regen.memorySafety.maxGenerationThreads,
                config.regen.memorySafety.heapHighWatermarkPercent,
                config.scan.threadPool.parallelism,
                config.scan.threadPool.priority
            );
        }

        static Values from(TuningRecommendation recommendation) {
            return new Values(
                Integer.toString(recommendation.generationThreads()),
                recommendation.generationPriority(),
                recommendation.batchDelayTicks(),
                recommendation.batchConcurrency(),
                recommendation.maxChunksPerBatch(),
                recommendation.workTileSize(),
                recommendation.applyBatchSize(),
                true,
                recommendation.maxActiveBatches(),
                recommendation.memorySafeMaxChunksPerBatch(),
                recommendation.maxGenerationThreads(),
                recommendation.heapHighWatermarkPercent(),
                recommendation.scanThreads(),
                recommendation.scanPriority()
            );
        }

        void writeTo(PluginConfig config) {
            config.regen.threadPool.parallelism = generationThreads;
            config.regen.threadPool.priority = generationPriority;
            config.regen.batchDelayTicks = batchDelayTicks;
            config.regen.batchConcurrency = batchConcurrency;
            config.regen.maxChunksPerBatch = maxChunksPerBatch;
            config.regen.workTileSize = workTileSize;
            config.regen.applyBatchSize = applyBatchSize;
            config.regen.memorySafety.enabled = memorySafetyEnabled;
            config.regen.memorySafety.maxActiveBatches = maxActiveBatches;
            config.regen.memorySafety.maxChunksPerBatch = memorySafeBatchSize;
            config.regen.memorySafety.maxGenerationThreads = maxGenerationThreads;
            config.regen.memorySafety.heapHighWatermarkPercent = heapHighWatermarkPercent;
            config.scan.threadPool.parallelism = scanThreads;
            config.scan.threadPool.priority = scanPriority;
        }
    }
}
