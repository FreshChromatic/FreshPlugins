package github.freshchromatic.chunkrevive.feature.tuning;

public record TuningRecommendation(
    TuningProfile profile,
    int generationThreads,
    int generationPriority,
    int batchDelayTicks,
    int batchConcurrency,
    int maxChunksPerBatch,
    int workTileSize,
    int applyBatchSize,
    String maxActiveBatches,
    String memorySafeMaxChunksPerBatch,
    String maxGenerationThreads,
    int heapHighWatermarkPercent,
    int scanThreads,
    int scanPriority
) {}
