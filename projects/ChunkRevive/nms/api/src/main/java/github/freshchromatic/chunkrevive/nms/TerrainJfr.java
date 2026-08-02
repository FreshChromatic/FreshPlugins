package github.freshchromatic.chunkrevive.nms;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Threshold;
import jdk.jfr.Timespan;

/** Custom JFR events that correlate JVM samples with ChunkRevive terrain work. */
public final class TerrainJfr {

    private TerrainJfr() {}

    @Name("github.freshchromatic.chunkrevive.TerrainTask")
    @Label("ChunkRevive Terrain Task")
    @Description("One chunk-local terrain generation task")
    @Category({"ChunkRevive", "Terrain"})
    @Threshold("10 ms")
    @StackTrace(false)
    public static final class TaskEvent extends Event {
        @Label("Stage")
        public String stage;

        @Label("Chunk X")
        public int chunkX;

        @Label("Chunk Z")
        public int chunkZ;

        @Label("Reason")
        public String reason;

        @Label("Result")
        @Description("Structure keys and complexity, or other task-specific result data")
        public String result;

        @Label("Thread CPU Time")
        @Timespan(Timespan.NANOSECONDS)
        public long cpuNanos;

        @Label("Succeeded")
        public boolean succeeded;
    }

    @Name("github.freshchromatic.chunkrevive.TerrainPhase")
    @Label("ChunkRevive Terrain Phase")
    @Description("A complete ChunkRevive terrain generation phase")
    @Category({"ChunkRevive", "Terrain"})
    @Threshold("1 ms")
    @StackTrace(false)
    public static final class PhaseEvent extends Event {
        @Label("Phase")
        public String phase;

        @Label("Target Chunks")
        public int targetChunks;

        @Label("Tasks")
        public int tasks;

        @Label("Worker Time")
        @Timespan(Timespan.NANOSECONDS)
        public long workerNanos;

        @Label("GC Collections")
        public long gcCollections;

        @Label("GC Time")
        @Timespan(Timespan.MILLISECONDS)
        public long gcMillis;

        @Label("Succeeded")
        public boolean succeeded;
    }

    public static TaskEvent beginTask(String stage, int chunkX, int chunkZ, String reason) {
        TaskEvent event = new TaskEvent();
        event.stage = stage;
        event.chunkX = chunkX;
        event.chunkZ = chunkZ;
        event.reason = reason == null ? "" : reason;
        event.result = "";
        event.begin();
        return event;
    }

    public static void endTask(TaskEvent event, long cpuNanos, boolean succeeded) {
        endTask(event, cpuNanos, succeeded, "");
    }

    public static void endTask(TaskEvent event, long cpuNanos, boolean succeeded, String result) {
        event.cpuNanos = Math.max(-1L, cpuNanos);
        event.succeeded = succeeded;
        event.result = result == null ? "" : result;
        event.end();
        if (event.shouldCommit()) event.commit();
    }

    public static PhaseEvent beginPhase(String phase, int targetChunks) {
        PhaseEvent event = new PhaseEvent();
        event.phase = phase;
        event.targetChunks = targetChunks;
        event.begin();
        return event;
    }

    public static void endPhase(PhaseEvent event, int tasks, long workerNanos,
                                long gcCollections, long gcMillis, boolean succeeded) {
        event.tasks = tasks;
        event.workerNanos = Math.max(0L, workerNanos);
        event.gcCollections = Math.max(0L, gcCollections);
        event.gcMillis = Math.max(0L, gcMillis);
        event.succeeded = succeeded;
        event.end();
        if (event.shouldCommit()) event.commit();
    }
}
