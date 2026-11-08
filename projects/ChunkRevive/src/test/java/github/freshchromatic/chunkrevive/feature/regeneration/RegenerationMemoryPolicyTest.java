package github.freshchromatic.chunkrevive.feature.regeneration;

import github.freshchromatic.chunkrevive.config.PluginConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegenerationMemoryPolicyTest {

    private static final long GIB = 1024L * 1024L * 1024L;

    @Test
    void automaticLimitsFollowHeapSizeAndRequestedConcurrency() {
        PluginConfig.Regen config = new PluginConfig.Regen();
        config.batchConcurrency = 10;
        config.memorySafety.maxChunksPerBatch = "AUTO";

        assertEquals(1, RegenerationMemoryPolicy.effectiveConcurrency(config, 2 * GIB));
        assertEquals(2, RegenerationMemoryPolicy.effectiveConcurrency(config, 4 * GIB));
        assertEquals(4, RegenerationMemoryPolicy.effectiveConcurrency(config, 8 * GIB));
        assertEquals(6, RegenerationMemoryPolicy.effectiveConcurrency(config, 16 * GIB));
        assertEquals(8, RegenerationMemoryPolicy.effectiveConcurrency(config, 32 * GIB));
        assertEquals(8, RegenerationMemoryPolicy.effectiveBatchSize(config, 4 * GIB));
        assertEquals(16, RegenerationMemoryPolicy.effectiveBatchSize(config, 8 * GIB));
        assertEquals(24, RegenerationMemoryPolicy.effectiveBatchSize(config, 16 * GIB));
        assertEquals(48, RegenerationMemoryPolicy.effectiveBatchSize(config, 32 * GIB));
    }

    @Test
    void configuredAndDisabledSafetyPreserveRequestedLimits() {
        PluginConfig.Regen config = new PluginConfig.Regen();
        config.batchConcurrency = 12;
        config.maxChunksPerBatch = 0;
        config.memorySafety.maxActiveBatches = "CONFIG";
        config.memorySafety.maxChunksPerBatch = "CONFIG";

        assertEquals(12, RegenerationMemoryPolicy.effectiveConcurrency(config, 2 * GIB));
        assertEquals(0, RegenerationMemoryPolicy.effectiveBatchSize(config, 2 * GIB));
        config.memorySafety.enabled = false;
        config.memorySafety.maxActiveBatches = "1";
        config.memorySafety.maxChunksPerBatch = "2";
        assertEquals(12, RegenerationMemoryPolicy.effectiveConcurrency(config, 2 * GIB));
        assertEquals(0, RegenerationMemoryPolicy.effectiveBatchSize(config, 2 * GIB));
    }

    @Test
    void highWatermarkUsesClampedThresholdAndHonorsDisabledSafety() {
        PluginConfig.Regen config = new PluginConfig.Regen();
        config.memorySafety.heapHighWatermarkPercent = 10;
        assertFalse(RegenerationMemoryPolicy.isHeapAtHighWatermark(config, 49, 100));
        assertTrue(RegenerationMemoryPolicy.isHeapAtHighWatermark(config, 50, 100));
        config.memorySafety.heapHighWatermarkPercent = 99;
        assertFalse(RegenerationMemoryPolicy.isHeapAtHighWatermark(config, 94, 100));
        assertTrue(RegenerationMemoryPolicy.isHeapAtHighWatermark(config, 95, 100));
        config.memorySafety.enabled = false;
        assertFalse(RegenerationMemoryPolicy.isHeapAtHighWatermark(config, 100, 100));
    }
}
