package github.freshchromatic.chunkrevive.feature.tuning;

import java.lang.management.ManagementFactory;

public record ServerResourceSnapshot(
    int logicalProcessors,
    long maxHeapMiB,
    long usedHeapMiB,
    long totalSystemMemoryMiB,
    long freeSystemMemoryMiB,
    int jvmThreadCount,
    double systemCpuLoad,
    boolean folia
) {
    private static final long MIB = 1024L * 1024L;

    public static ServerResourceSnapshot capture(boolean folia) {
        Runtime runtime = Runtime.getRuntime();
        long totalSystem = -1;
        long freeSystem = -1;
        double cpuLoad = -1;
        try {
            var bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean os) {
                totalSystem = os.getTotalMemorySize() / MIB;
                freeSystem = os.getFreeMemorySize() / MIB;
                cpuLoad = os.getCpuLoad();
            }
        } catch (Throwable ignored) {
            // Some JVMs do not expose the extended operating-system bean.
        }

        return new ServerResourceSnapshot(
            runtime.availableProcessors(),
            runtime.maxMemory() / MIB,
            (runtime.totalMemory() - runtime.freeMemory()) / MIB,
            totalSystem,
            freeSystem,
            ManagementFactory.getThreadMXBean().getThreadCount(),
            cpuLoad,
            folia
        );
    }

    public double heapUsageRatio() {
        return maxHeapMiB <= 0 ? 0 : (double) usedHeapMiB / maxHeapMiB;
    }
}
