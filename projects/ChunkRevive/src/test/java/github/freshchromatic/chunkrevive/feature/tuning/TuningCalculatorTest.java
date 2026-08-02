package github.freshchromatic.chunkrevive.feature.tuning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TuningCalculatorTest {

    @Test
    void lowResourceServerStaysConservative() {
        var resources = snapshot(2, 2048, 800, 0.20);
        var result = TuningCalculator.calculate(resources, TuningProfile.PERFORMANCE);

        assertEquals(TuningProfile.OPTIMIZED, TuningCalculator.recommendedProfile(resources));
        assertEquals(2, result.generationThreads());
        assertEquals(0, result.maxChunksPerBatch());
        assertEquals(1, result.batchConcurrency());
        assertEquals(48, result.workTileSize());
        assertEquals("CONFIG", result.memorySafeMaxChunksPerBatch());
    }

    @Test
    void balancedProfileUsesBothCpuAndHeapHeadroom() {
        var result = TuningCalculator.calculate(snapshot(16, 12288, 3000, 0.20), TuningProfile.BALANCED);

        assertEquals(11, result.generationThreads());
        assertEquals(1, result.batchConcurrency());
        assertEquals(0, result.maxChunksPerBatch());
        assertEquals(256, result.workTileSize());
        assertEquals("CONFIG", result.maxActiveBatches());
        assertEquals("CONFIG", result.maxGenerationThreads());
        assertEquals(6, result.scanThreads());
    }

    @Test
    void livePressureSelectsOptimizedWithoutChangingFormulaInputs() {
        var busy = snapshot(16, 12288, 10000, 0.90);
        assertEquals(TuningProfile.OPTIMIZED, TuningCalculator.recommendedProfile(busy));

        var idleResult = TuningCalculator.calculate(snapshot(16, 12288, 1000, 0.05), TuningProfile.BALANCED);
        var busyResult = TuningCalculator.calculate(busy, TuningProfile.BALANCED);
        assertEquals(idleResult, busyResult);
    }

    @Test
    void excessiveJvmThreadsSelectOptimizedRecommendation() {
        var resources = new ServerResourceSnapshot(8, 8192, 2000, 16384, 8192, 300, 0.20, false);
        assertEquals(TuningProfile.OPTIMIZED, TuningCalculator.recommendedProfile(resources));
    }

    @Test
    void performanceProfileKeepsOneDagAndScalesWorkersAndTiles() {
        var fourGiB = TuningCalculator.calculate(snapshot(8, 4096, 1000, 0.10), TuningProfile.PERFORMANCE);
        var sixteenGiB = TuningCalculator.calculate(snapshot(32, 16384, 2000, 0.10), TuningProfile.PERFORMANCE);

        assertEquals(1, fourGiB.batchConcurrency());
        assertEquals(6, fourGiB.generationThreads());
        assertEquals(128, fourGiB.workTileSize());
        assertEquals(1, sixteenGiB.batchConcurrency());
        assertEquals(16, sixteenGiB.generationThreads());
        assertEquals(320, sixteenGiB.workTileSize());
        assertEquals(0, sixteenGiB.maxChunksPerBatch());
        assertEquals("CONFIG", sixteenGiB.memorySafeMaxChunksPerBatch());
    }

    @Test
    void sixGiBSixteenThreadPerformanceMatchesSingleDagArchitecture() {
        var result = TuningCalculator.calculate(snapshot(16, 6144, 1000, 0.10), TuningProfile.PERFORMANCE);

        assertEquals(12, result.generationThreads());
        assertEquals(1, result.batchConcurrency());
        assertEquals(192, result.workTileSize());
        assertEquals(24, result.applyBatchSize());
    }

    private static ServerResourceSnapshot snapshot(int processors, long heapMiB, long usedMiB, double cpuLoad) {
        return new ServerResourceSnapshot(processors, heapMiB, usedMiB, 16384, 8192, 100, cpuLoad, true);
    }
}
