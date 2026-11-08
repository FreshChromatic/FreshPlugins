package github.freshchromatic.chunkrevive.feature.regeneration;

import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.config.SafetyLimitPolicy;

final class RegenerationMemoryPolicy {

    private RegenerationMemoryPolicy() {
    }

    static int effectiveConcurrency(PluginConfig.Regen config, long maxHeapBytes) {
        int requested = Math.max(1, config.batchConcurrency);
        PluginConfig.Regen.MemorySafety safety = config.memorySafety;
        if (!safety.enabled) {
            return requested;
        }
        int limit = SafetyLimitPolicy.resolveCap(safety.maxActiveBatches, automaticActiveBatchLimit(maxHeapBytes));
        return Math.min(requested, Math.max(1, limit));
    }

    static int effectiveBatchSize(PluginConfig.Regen config, long maxHeapBytes) {
        int requested = config.maxChunksPerBatch;
        PluginConfig.Regen.MemorySafety safety = config.memorySafety;
        if (!safety.enabled) {
            return requested;
        }
        int limit = SafetyLimitPolicy.resolveCap(safety.maxChunksPerBatch, automaticBatchSizeLimit(maxHeapBytes));
        // CONFIG/IGNORE adds no safety cap, so requested=0 remains unlimited and cross-chunk
        // decoration is generated as one continuous unit.
        if (limit == Integer.MAX_VALUE) {
            return requested;
        }
        return requested <= 0 ? limit : Math.min(requested, limit);
    }

    static boolean isHeapAtHighWatermark(PluginConfig.Regen config, long usedHeapBytes, long maxHeapBytes) {
        PluginConfig.Regen.MemorySafety safety = config.memorySafety;
        if (!safety.enabled) {
            return false;
        }
        int percent = Math.clamp(safety.heapHighWatermarkPercent, 50, 95);
        return usedHeapBytes * 100L >= maxHeapBytes * percent;
    }

    private static int automaticActiveBatchLimit(long maxHeapBytes) {
        long heapGiB = heapGiB(maxHeapBytes);
        if (heapGiB <= 2) {
            return 1;
        }
        if (heapGiB <= 4) {
            return 2;
        }
        if (heapGiB <= 8) {
            return 4;
        }
        if (heapGiB <= 16) {
            return 6;
        }
        return 8;
    }

    private static int automaticBatchSizeLimit(long maxHeapBytes) {
        long heapGiB = heapGiB(maxHeapBytes);
        if (heapGiB <= 4) {
            return 8;
        }
        if (heapGiB <= 8) {
            return 16;
        }
        if (heapGiB <= 16) {
            return 24;
        }
        return 48;
    }

    private static long heapGiB(long maxHeapBytes) {
        return Math.max(1L, maxHeapBytes / (1024L * 1024L * 1024L));
    }

}
