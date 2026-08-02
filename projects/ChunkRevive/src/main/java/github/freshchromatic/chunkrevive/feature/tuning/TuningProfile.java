package github.freshchromatic.chunkrevive.feature.tuning;

import java.util.Locale;
import java.util.Optional;

public enum TuningProfile {
    OPTIMIZED(0.45, 10, 6, 3, 4, 78),
    BALANCED(0.70, 3, 12, 5, 6, 88),
    PERFORMANCE(1.00, 0, 24, 7, 8, 93);

    private final double cpuRatio;
    private final int batchDelayTicks;
    private final int applyBatchSize;
    private final int scanPriority;
    private final int generationPriority;
    private final int heapHighWatermarkPercent;

    TuningProfile(double cpuRatio, int batchDelayTicks, int applyBatchSize,
                  int scanPriority, int generationPriority, int heapHighWatermarkPercent) {
        this.cpuRatio = cpuRatio;
        this.batchDelayTicks = batchDelayTicks;
        this.applyBatchSize = applyBatchSize;
        this.scanPriority = scanPriority;
        this.generationPriority = generationPriority;
        this.heapHighWatermarkPercent = heapHighWatermarkPercent;
    }

    public double cpuRatio() { return cpuRatio; }
    public int batchDelayTicks() { return batchDelayTicks; }
    public int applyBatchSize() { return applyBatchSize; }
    public int scanPriority() { return scanPriority; }
    public int generationPriority() { return generationPriority; }
    public int heapHighWatermarkPercent() { return heapHighWatermarkPercent; }

    public String commandName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<TuningProfile> parse(String value) {
        if (value == null) return Optional.empty();
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}
