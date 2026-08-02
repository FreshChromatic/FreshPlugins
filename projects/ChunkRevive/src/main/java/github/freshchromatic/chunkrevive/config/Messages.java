package github.freshchromatic.chunkrevive.config;

import github.freshchromatic.freshlib.config.Config;
import github.freshchromatic.freshlib.config.Messages.ComponentMessage;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Setting;

import java.util.LinkedHashMap;
import java.util.Map;

@ConfigSerializable
public final class Messages implements Config {

    @Override
    public String getFileName() {
        return "messages.yml";
    }

    public Command command = new Command();
    public Mark mark = new Mark();
    public Unmark unmark = new Unmark();
    public Regen regen = new Regen();
    public Deletion deletion = new Deletion();
    public List list = new List();
    public Keepchunk keepchunk = new Keepchunk();
    public Display display = new Display();
    public Structure structure = new Structure();
    public Scan scan = new Scan();
    public Regions regions = new Regions();
    public Tuning tuning = new Tuning();
    public Descriptions descriptions = new Descriptions();
    public Update update = new Update();
    /**
     * Short plain-text fragments used by command UIs and internal status output.
     * They deliberately live alongside component messages so every player-visible
     * string can be overridden from messages.yml.
     */
    public Map<String, String> text = defaultText();

    public String text(String key, Object... arguments) {
        return text.getOrDefault(key, key).formatted(arguments);
    }

    @ConfigSerializable
    public static class Update {
        @Setting("available")
        public ComponentMessage available = new ComponentMessage(
            "<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#FBFF8B>A new version is available: <#8BFF7B><latest_version> "
                + "<#7A7A7A>(current: <current_version>) <#0094D5><url>");
    }

    private static Map<String, String> defaultText() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("world-not-found", "World not found — %s");
        values.put("console-world-required", "The console must specify a world name.");
        values.put("regen-cancel-requested", "A request to cancel the batch regeneration task has been sent.");
        values.put("scan-cancel-requested", "A request to cancel the scan task has been sent.");
        values.put("no-cancellable-work", "There are currently no running batch regeneration, scan, or pending deletion jobs.");
        values.put("list-unmark-hover", "Unmark this chunk");
        values.put("list-regen-hover", "Regenerate this chunk");
        values.put("duration-seconds", "%s seconds");
        values.put("duration-minutes", "%s minutes");
        values.put("duration-hours", "%s hours");
        values.put("relative-seconds", "%s seconds ago");
        values.put("relative-minutes", "%s minutes ago");
        values.put("relative-hours", "%s hours ago");
        values.put("relative-days", "%s days ago");
        values.put("scan-already-running-world", "A scan is already running — %s");
        values.put("here-biome-requires-player", "To use here, execute the command as a player while standing in %s; otherwise enter an explicit biome ID.");
        values.put("biome-no-markable-chunks", "Biome %s contains no markable chunks (possibly due to claim exclusion or an ungenerated starting point).");
        values.put("biome-marked", "Marked %2$s new chunks in the current biome (%1$s) (detected %3$s%4$s).");
        values.put("biome-mark-truncated", ", stopped early after reaching the safety limit of %s chunks");
        values.put("player-only", "This command can only be executed by a player.");
        values.put("biome-undetermined", "Unable to determine the current biome.");
        values.put("regen-busy", "Batch regeneration is already running.");
        values.put("biome-no-regenerable-chunks", "No existing chunks matching biome %s can be regenerated nearby.");
        values.put("biome-regen-confirm", "You are about to regenerate approximately %2$s chunks in biome %1$s%3$s. Enter /cr regen here biome --confirm within %4$s seconds to confirm.");
        values.put("biome-regen-truncated", "(stopped early after reaching the safety limit of %s chunks)");
        values.put("biome-regen-start", "Detected biome %s with %s chunks. Starting regeneration...");
        values.put("biome-no-deletable-chunks", "The current contiguous biome contains no generated chunks that can be deleted.");
        values.put("biome-delete-confirm", "You are about to delete %2$s complete chunks in the current contiguous biome %1$s%3$s. This does not only delete biome data. Enter /cr delete here biome --confirm within %4$s seconds to confirm.");
        values.put("biome-delete-truncated", "(detection reached the safety limit)");
        values.put("structure-protected", "Protected");
        values.put("structure-scheduled", "Scheduled");
        values.put("structure-refresh-in-days", "Refreshes in %s days");
        values.put("structure-not-found", "Structure group not found.");
        values.put("structure-claim-blocked", "The range contains a Residence claim; forced refresh was aborted.");
        values.put("structure-unblocked", "Protection has been removed from this structure.");
        values.put("structure-blocked", "This structure has been set to protected.");
        values.put("structure-reset", "The structure's status and refresh timer have been reset.");
        values.put("structure-reset-all", "Reset the status and refresh timers of all %s structure groups.");
        values.put("structure-reset-all-confirm", "You are about to reset the protection status and refresh timers of all structures. Enter /cr struct resetall --confirm to confirm.");
        values.put("structure-id-invalid", "Invalid structure group ID.");
        values.put("deletion-nearby-player", "Players are still nearby");
        values.put("deletion-chunk-holder", "ChunkHolder %s,%s still exists");
        values.put("deletion-cold-check", "Cold-area check 1/2");
        values.put("deletion-started", "Processing started");
        values.put("deletion-completed", "Processing completed");
        values.put("deletion-restart-cold-check", "Rechecking cold area after restart");
        values.put("deletion-restored-waiting", "Restored from database; waiting for check");
        values.put("deletion-not-checked", "Not checked yet");
        values.put("deletion-log", "[Reset Log] %s %s — job %s, world %s, %s%s.");
        values.put("deletion-released", ", released approximately %s");
        values.put("regen-duration-minutes-seconds", "%s min %s sec");
        values.put("regen-duration-seconds", "%.1f sec");
        values.put("reset-not-structure", "The current chunk does not belong to any marked structure.");
        values.put("reset-no-deletable", "There are currently no marked chunks that can be deleted.");
        values.put("reset-no-deletable-world", "World %s contains no marked chunks that can be deleted.");
        values.put("reset-delete-marked-confirm", "You are about to schedule deletion of %1$s ChunkRevive-marked chunks. Enter \"%3$s\" within %2$s seconds to confirm.");
        values.put("reset-structure-empty", "This structure no longer contains any marked chunks.");
        values.put("reset-structure-claim-blocked", "This structure range contains Residence-protected chunks; deleting the structure was refused.");
        values.put("reset-structure-confirm", "You are about to delete %2$s chunks in marked structure %1$s. Enter \"/cr delete here struct --confirm\" within %3$s seconds to confirm.");
        values.put("regen-structure-confirm", "You are about to regenerate %1$s chunks in the marked structure. Enter \"/cr regen here struct --confirm\" within %2$s seconds to confirm.");
        values.put("reset-single-confirm", "The reset strategy will perform %4$s on %1$s %2$s,%3$s. Enter \"%6$s\" within %5$s seconds to confirm.");
        values.put("reset-busy", "Batch regeneration is already running; no reset jobs were created.");
        values.put("reset-plan", "Reset strategy — delete %s complete regions, delete %s chunks, reset %s chunks.");
        values.put("reset-regen-busy", "Batch regeneration is already running; no new regeneration jobs were created.");
        values.put("reset-plan-confirm", "Reset plan — delete %1$s complete regions, delete %2$s chunks, regenerate %3$s chunks. Enter \"%5$s\" within %4$s seconds to confirm.");
        values.put("reset-plan-confirm-target", "Reset target: %1$s. Plan — delete %2$s complete regions, delete %3$s chunks, regenerate %4$s chunks. Enter \"%6$s\" within %5$s seconds to confirm.");
        values.put("reset-target-biome", "biome %s");
        values.put("reset-target-structure", "structure %s");
        values.put("status-regen-title", "Batch Regeneration Status");
        values.put("status-deletion-title", "Deletion / Region Cleanup Jobs");
        values.put("status-scan-title", "Scan Queue Status");
        values.put("status-server-title", "Server Runtime Status");
        values.put("status-label", "%s:");
        values.put("status-idle", "Idle");
        values.put("status-running", "Running");
        values.put("status-cancelling", "Cancelling");
        values.put("status-waiting-cold", "Waiting to Become Cold");
        values.put("status-failed", "Failed");
        values.put("status-restored", "Restored After Restart");
        values.put("status-previous", "Previous");
        values.put("status-next", "Next");
        values.put("status-page-info", "Showing jobs %s–%s of %s.");
        values.put("status-cancel-regen", "Cancel Batch Regeneration");
        values.put("status-cancel-regen-hover", "Cancel the currently running batch regeneration task");
        values.put("status-cancel-scan", "Cancel Scan");
        values.put("status-cancel-scan-hover", "Cancel the currently running scan task");
        values.put("status-uptime", "%s days %s hours %s minutes %s seconds");
        values.put("status-unavailable", "Unsupported (N/A)");
        values.put("status-current", "Current Status");
        values.put("status-completed", "Completed");
        values.put("status-scan-world", "Scanning World");
        values.put("status-processors", "Logical Processors");
        values.put("status-group-threads", "Group Threads");
        values.put("status-memory", "Memory Usage");
        values.put("status-players", "Online Players");
        values.put("status-marked-chunks", "Marked Chunks");
        values.put("status-residence-blocked", "Residence-Blocked Regeneration Chunks");
        values.put("status-gen-thread-value", "%s / %s (configured: %s)");
        values.put("status-marked-chunks-value", "%s (individual: %s, structure: %s)");
        values.put("status-concurrency", "Current Concurrency");
        values.put("status-pending", "Pending");
        values.put("status-jvm-threads", "Total JVM Threads");
        values.put("status-gen-threads", "Active Generation Threads");
        values.put("status-cpu", "System CPU Usage");
        values.put("status-uptime-label", "JVM Uptime");
        values.put("status-database", "Database Type");
        values.put("status-marked-structures", "Marked Structures");
        values.put("status-version", "Server Version");
        values.put("status-os", "Operating System");
        values.put("status-memory-value", "%s MB / %s MB (max %s MB)");
        values.put("status-marked-structures-value", "%s (manually protected/blocked: %s)");
        return values;
    }

    @ConfigSerializable
    public static class Regions {
        public ComponentMessage count = new ComponentMessage("<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#8BFF7B>World <#FBFF8B><world><#8BFF7B> contains <#FBFF8B><count><#8BFF7B> region files.");
    }

    @ConfigSerializable
    public static class Tuning {
        public ComponentMessage title = new ComponentMessage("ChunkRevive Auto-Tuning");
        @Setting("hardware-title")
        public ComponentMessage hardwareTitle = new ComponentMessage("Hardware and Runtime Environment");
        @Setting("regen-title")
        public ComponentMessage regenTitle = new ComponentMessage("Chunk Regeneration Tuning");
        @Setting("scan-title")
        public ComponentMessage scanTitle = new ComponentMessage("Disk Scan Tuning");
        @Setting("preview-title")
        public ComponentMessage previewTitle = new ComponentMessage("<mode> Mode Preview");

        @Setting("info-line")
        public ComponentMessage infoLine = new ComponentMessage("<#7A7A7A><label> — <#FBFF8B><value>");
        @Setting("setting-line")
        public ComponentMessage settingLine = new ComponentMessage("<#7A7A7A><label> — <#0094D5><current> <#7A7A7A>→ <#FBFF8B><recommended>");
        @Setting("recommended-mode-line")
        public ComponentMessage recommendedModeLine = new ComponentMessage("<#7A7A7A>Recommended — <#8BFF7B><mode>");

        @Setting("label-processors") public String labelProcessors = "Logical Processors";
        @Setting("label-heap") public String labelHeap = "JVM Heap";
        @Setting("label-system-memory") public String labelSystemMemory = "System-Visible Memory";
        @Setting("label-jvm-threads") public String labelJvmThreads = "JVM Threads";
        @Setting("label-cpu-load") public String labelCpuLoad = "System CPU Usage";
        @Setting("label-environment") public String labelEnvironment = "Server Runtime Environment";

        @Setting("hover-processors")
        public ComponentMessage hoverProcessors = new ComponentMessage("<#0094D5>Logical Processors</#0094D5>\n<#7A7A7A>The number of processors available to the JVM; container CPU limits are usually reflected here as well.\nThis is the primary basis for regeneration and scan thread limits.");
        @Setting("hover-heap")
        public ComponentMessage hoverHeap = new ComponentMessage("<#0094D5>JVM Heap</#0094D5>\n<#7A7A7A>Java memory limited by -Xmx.\nA larger heap can hold more chunks being generated concurrently, but that does not mean all memory should be used.");
        @Setting("hover-system-memory")
        public ComponentMessage hoverSystemMemory = new ComponentMessage("<#0094D5>System-Visible Memory</#0094D5>\n<#7A7A7A>The physical memory currently visible to the JVM or container.\nTuning treats JVM heap as the hard limit; Optimized mode is recommended when available system memory is too low.");
        @Setting("hover-jvm-threads")
        public ComponentMessage hoverJvmThreads = new ComponentMessage("<#0094D5>JVM Threads</#0094D5>\n<#7A7A7A>The total number of live Java threads in the server.\nIt changes with plugins and workload, so it is not used directly in formulas; an unusually high count recommends Optimized mode.");
        @Setting("hover-cpu-load")
        public ComponentMessage hoverCpuLoad = new ComponentMessage("<#0094D5>System CPU Usage</#0094D5>\n<#7A7A7A>A snapshot of system load when the command is executed.\nHigh load recommends Optimized mode but does not change each mode's fixed calculations.");
        @Setting("hover-environment")
        public ComponentMessage hoverEnvironment = new ComponentMessage("<#0094D5>Server Runtime Environment</#0094D5>\n<#7A7A7A>Paper uses the main thread; Folia distributes world work across region threads.\nApplying batches that are too large may cause tick delays on either platform.");

        @Setting("mode-optimized") public ComponentMessage modeOptimized = new ComponentMessage("Optimized");
        @Setting("mode-balanced") public ComponentMessage modeBalanced = new ComponentMessage("Balanced");
        @Setting("mode-performance") public ComponentMessage modePerformance = new ComponentMessage("Performance");
        @Setting("hover-mode-optimized")
        public ComponentMessage hoverModeOptimized = new ComponentMessage("<#8BFF7B>Optimized Mode</#8BFF7B>\n<#7A7A7A>Prioritizes TPS and Folia region-tick stability.\nUses less CPU, disk I/O, and peak memory, but takes longer to finish.\n<#FBFF8B>Click to view the full configuration differences");
        @Setting("hover-mode-balanced")
        public ComponentMessage hoverModeBalanced = new ComponentMessage("<#FBFF8B>Balanced Mode</#FBFF8B>\n<#7A7A7A>Balances server stability and processing speed; suitable for most production servers.\n<#FBFF8B>Click to view the full configuration differences");
        @Setting("hover-mode-performance")
        public ComponentMessage hoverModePerformance = new ComponentMessage("<#FF6048>Performance Mode</#FF6048>\n<#7A7A7A>Uses more CPU, disk I/O, and memory to reduce processing time.\nRecommended only during maintenance windows; memory safety limits remain enabled.\n<#FBFF8B>Click to view the full configuration differences");

        @Setting("label-generation-threads") public String labelGenerationThreads = "Terrain Generation Threads";
        @Setting("label-generation-priority") public String labelGenerationPriority = "Terrain Thread Priority";
        @Setting("label-batch-delay") public String labelBatchDelay = "Batch Delay (ticks)";
        @Setting("label-batch-concurrency") public String labelBatchConcurrency = "Regeneration Batch Concurrency";
        @Setting("label-batch-size") public String labelBatchSize = "Maximum Chunks per Batch";
        @Setting("label-work-tile-size") public String labelWorkTileSize = "Maximum Spatial Work Tile Size";
        @Setting("label-apply-batch-size") public String labelApplyBatchSize = "Apply Batch per Tick";
        @Setting("label-memory-safety") public String labelMemorySafety = "Memory Safety";
        @Setting("label-active-batches") public String labelActiveBatches = "Memory-Safety Active Batches";
        @Setting("label-memory-batch-size") public String labelMemoryBatchSize = "Memory-Safety Batch Limit";
        @Setting("label-generation-limit") public String labelGenerationLimit = "Memory-Safety Thread Limit";
        @Setting("label-heap-watermark") public String labelHeapWatermark = "Heap High-Water Mark";
        @Setting("label-scan-threads") public String labelScanThreads = "Scan Threads";
        @Setting("label-scan-priority") public String labelScanPriority = "Scan Thread Priority";

        @Setting("hover-generation-threads")
        public ComponentMessage hoverGenerationThreads = new ComponentMessage("<#0094D5>Setting: regen.thread-pool.parallelism</#0094D5>\n<#7A7A7A>Increasing this can speed up terrain computation, but raises CPU and short-term memory usage and may affect ticks.\nDecreasing it reduces processor pressure but makes regeneration take longer.\nBased on <processors> processors and <heap> MiB heap, the recommendation is <recommended>.");
        @Setting("hover-generation-priority")
        public ComponentMessage hoverGenerationPriority = new ComponentMessage("<#0094D5>Setting: regen.thread-pool.priority</#0094D5>\n<#7A7A7A>Increasing this makes terrain tasks compete more aggressively for CPU; decreasing it gives server ticks and other plugins higher priority.\nJava's normal priority is 5.");
        @Setting("hover-batch-delay")
        public ComponentMessage hoverBatchDelay = new ComponentMessage("<#0094D5>Setting: regen.batch-delay-ticks</#0094D5>\n<#7A7A7A>Increasing this spreads out load and improves tick stability, but extends total processing time.\nDecreasing it dispatches work faster while increasing sustained CPU and I/O pressure.");
        @Setting("hover-batch-concurrency")
        public ComponentMessage hoverBatchConcurrency = new ComponentMessage("<#0094D5>Setting: regen.batch-concurrency</#0094D5>\n<#7A7A7A>Each terrain DAG already uses the shared generation pool. Running multiple batches concurrently makes them compete for the same workers, duplicates halos, and reduces CARVERS cache hit rate, so auto-tuning fixes this value at 1.");
        @Setting("hover-batch-size")
        public ComponentMessage hoverBatchSize = new ComponentMessage("<#0094D5>Setting: regen.max-chunks-per-batch</#0094D5>\n<#7A7A7A>0 means adjacent generation batches are not split, preserving continuity for cross-chunk decorations such as trees, leaves, and ore veins.\nThe tradeoff is that large contiguous areas use more memory and do not commit partial progress until complete.");
        @Setting("hover-work-tile-size")
        public ComponentMessage hoverWorkTileSize = new ComponentMessage("<#0094D5>Setting: regen.work-tile-size</#0094D5>\n<#7A7A7A>Limits the number of target chunks in each spatial generation DAG. Increasing it based on heap reduces repeated tile halos; decreasing it lowers peak memory used by ProtoChunks and the CARVERS cache.\nBased on <heap> MiB heap, the recommendation is <recommended>.");
        @Setting("hover-apply-batch-size")
        public ComponentMessage hoverApplyBatchSize = new ComponentMessage("<#0094D5>Setting: regen.apply-batch-size</#0094D5>\n<#7A7A7A>Controls how many chunks may be applied per tick. Increasing it finishes loading, lighting, and neighbor updates faster, but is more likely to cause tick spikes.");
        @Setting("hover-memory-safety")
        public ComponentMessage hoverMemorySafety = new ComponentMessage("<#0094D5>Setting: regen.memory-safety.enabled</#0094D5>\n<#7A7A7A>Enables hard limits for heap usage, batch count, batch size, and generation threads. All automatic modes keep this enabled to reduce the risk of OOM errors and server crashes.");
        @Setting("hover-active-batches")
        public ComponentMessage hoverActiveBatches = new ComponentMessage("<#0094D5>Setting: regen.memory-safety.max-active-batches</#0094D5>\n<#7A7A7A>AUTO uses a heap-based automatic limit; CONFIG/IGNORE adds no second-level limit and follows regen.batch-concurrency directly; a positive integer is a fixed hard limit.");
        @Setting("hover-memory-batch-size")
        public ComponentMessage hoverMemoryBatchSize = new ComponentMessage("<#0094D5>Setting: regen.memory-safety.max-chunks-per-batch</#0094D5>\n<#7A7A7A>AUTO splits batches based on heap; CONFIG/IGNORE follows regen.max-chunks-per-batch.\nTuning modes use CONFIG so the upper-level value of 0 remains unlimited, preventing cross-chunk decoration seams.");
        @Setting("hover-generation-limit")
        public ComponentMessage hoverGenerationLimit = new ComponentMessage("<#0094D5>Setting: regen.memory-safety.max-generation-threads</#0094D5>\n<#7A7A7A>AUTO uses an automatic heap/CPU limit; CONFIG/IGNORE follows regen.thread-pool.parallelism directly; a positive integer is a fixed hard limit.");
        @Setting("hover-heap-watermark")
        public ComponentMessage hoverHeapWatermark = new ComponentMessage("<#0094D5>Setting: regen.memory-safety.heap-high-watermark-percent</#0094D5>\n<#7A7A7A>Pauses dispatching new batches when heap usage reaches this percentage. Increasing it can improve throughput but moves closer to OOM; decreasing it is more conservative.");
        @Setting("hover-scan-threads")
        public ComponentMessage hoverScanThreads = new ComponentMessage("<#0094D5>Setting: scan.thread-pool.parallelism</#0094D5>\n<#7A7A7A>The number of workers that read region files concurrently. Increasing it can speed up scans but raises CPU usage and random disk reads; HDDs or shared storage may become slower as a result.");
        @Setting("hover-scan-priority")
        public ComponentMessage hoverScanPriority = new ComponentMessage("<#0094D5>Setting: scan.thread-pool.priority</#0094D5>\n<#7A7A7A>Increasing it makes scanning compete more aggressively for CPU; decreasing it reduces the impact on server ticks, regeneration, and other plugins.");

        @Setting("button-back") public ComponentMessage buttonBack = new ComponentMessage("Back");
        @Setting("button-refresh") public ComponentMessage buttonRefresh = new ComponentMessage("Refresh Detection");
        @Setting("button-apply") public ComponentMessage buttonApply = new ComponentMessage("Apply <mode>");
        @Setting("hover-back") public ComponentMessage hoverBack = new ComponentMessage("Return to hardware detection and mode selection");
        @Setting("hover-refresh") public ComponentMessage hoverRefresh = new ComponentMessage("Recapture current CPU, memory, and JVM status");
        @Setting("hover-apply") public ComponentMessage hoverApply = new ComponentMessage("Prepare to write config.yml; a second confirmation is still required");
        @Setting("button-confirm") public ComponentMessage buttonConfirm = new ComponentMessage("Confirm Apply");
        @Setting("hover-confirm") public ComponentMessage hoverConfirm = new ComponentMessage("Write to config.yml and immediately apply regeneration and scan settings");
        @Setting("button-cancel") public ComponentMessage buttonCancel = new ComponentMessage("Cancel");
        @Setting("hover-cancel") public ComponentMessage hoverCancel = new ComponentMessage("Return to the tuning preview without changing settings");

        @Setting("confirm-required")
        public ComponentMessage confirmRequired = new ComponentMessage("<#FFAA00>You are about to apply <mode> mode. The settings will be backed up to config.yml.bak; confirm within <timeout> seconds.");
        @Setting("confirm-expired")
        public ComponentMessage confirmExpired = new ComponentMessage("<#FF6048>The tuning confirmation does not exist or has expired. Preview and apply the mode again.");
        public ComponentMessage busy = new ComponentMessage("<#FF6048>Chunk regeneration or disk scanning is currently active. Wait for the work to finish before applying tuning.");
        public ComponentMessage applied = new ComponentMessage("<#8BFF7B>Applied <mode> mode and wrote it to config.yml; regeneration and scan settings took effect immediately.");
        @Setting("save-failed")
        public ComponentMessage saveFailed = new ComponentMessage("<#FF6048>Unable to back up or save config.yml. Tuning was not applied; check the server log.");
        @Setting("unknown-profile")
        public ComponentMessage unknownProfile = new ComponentMessage("<#FF6048>Unknown mode — <profile>. Available modes are optimized, balanced, and performance.");

        @Setting("value-unavailable") public String valueUnavailable = "Unsupported";
        @Setting("value-paper") public String valuePaper = "Paper";
        @Setting("value-folia") public String valueFolia = "Folia";
        @Setting("value-mib") public String valueMib = "<used> / <max> MiB";
        @Setting("value-percent") public String valuePercent = "<value>%";
        @Setting("value-system-memory") public String valueSystemMemory = "<free> MiB available / <total> MiB";
    }

    @ConfigSerializable
    public static class Command {
        @Setting("no-permission")
        public ComponentMessage noPermission = new ComponentMessage("<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#FF6048>You do not have permission to execute this command.");

        @Setting("player-only")
        public ComponentMessage playerOnly = new ComponentMessage("<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#FF6048>This command can only be executed by a player.");

        @Setting("reload-success")
        public ComponentMessage reloadSuccess = new ComponentMessage("<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#8BFF7B>Configuration and message files reloaded successfully!");
    }

    @ConfigSerializable
    public static class Mark {
        public ComponentMessage success = new ComponentMessage("<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#8BFF7B>Marked chunk <cx_cz>.");
        public ComponentMessage already = new ComponentMessage("<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#7A7A7A>Chunk <cx_cz> is already in the marked list.");

        @Setting("residence-blocked")
        public ComponentMessage residenceBlocked = new ComponentMessage("<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#FF6048>Chunk <cx_cz> is inside a Residence-protected area and cannot be marked.");

        @Setting("follow-on")
        public ComponentMessage followOn = new ComponentMessage("<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#FBFF8B>Follow-mark mode enabled. Chunks you walk through will be marked automatically.");

        @Setting("follow-off")
        public ComponentMessage followOff = new ComponentMessage("<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#8BFF7B>Follow-mark mode disabled.");
    }

    @ConfigSerializable
    public static class Unmark {
        public ComponentMessage success = new ComponentMessage("<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#8BFF7B>Unmarked chunk <cx_cz>.");

        @Setting("not-marked")
        public ComponentMessage notMarked = new ComponentMessage("<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#7A7A7A>Chunk <cx_cz> is not in the marked list.");

        @Setting("follow-on")
        public ComponentMessage followOn = new ComponentMessage("<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#FBFF8B>Follow-unmark mode enabled. Marked chunks you walk through will be unmarked automatically.");

        @Setting("follow-off")
        public ComponentMessage followOff = new ComponentMessage("<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#8BFF7B>Follow-unmark mode disabled.");
    }

    @ConfigSerializable
    public static class Regen {
        public ComponentMessage start = new ComponentMessage("<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#0094D5>Starting regeneration of chunk <cx_cz>...");
        public ComponentMessage progress = new ComponentMessage("<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#7A7A7A>Regeneration progress for <cx_cz> — <#FBFF8B><percent>%<#7A7A7A> (<stage>)");
        public ComponentMessage done = new ComponentMessage("<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#8BFF7B>Chunk <cx_cz> regenerated successfully (took <#FBFF8B><elapsed><#8BFF7B> / <elapsed_ms> ms).");
        public ComponentMessage failed = new ComponentMessage("<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#FF6048>Failed to regenerate chunk <cx_cz> — <reason>");

        @Setting("batch-start")
        public ComponentMessage batchStart = new ComponentMessage("<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#0094D5>Starting batch regeneration of <count> marked chunks...");

        @Setting("batch-done")
        public ComponentMessage batchDone = new ComponentMessage("<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#8BFF7B>Batch regeneration completed. Regenerated <count> chunks (took <#FBFF8B><elapsed><#8BFF7B> / <elapsed_ms> ms).");

        @Setting("none-marked")
        public ComponentMessage noneMarked = new ComponentMessage("<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#7A7A7A>There are currently no marked chunks.");

        @Setting("residence-blocked")
        public ComponentMessage residenceBlocked = new ComponentMessage("<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#FF6048>Chunk <cx_cz> is inside a Residence-protected area; regeneration skipped.");

        @Setting("confirm-required")
        public ComponentMessage confirmRequired = new ComponentMessage(
            "<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#FFAA00>You are about to batch-regenerate approximately <#FBFF8B><count><#FFAA00> chunks. Enter \"<#FBFF8B>/cr regen all <scope> --confirm<#FFAA00>\" within <timeout> seconds to confirm.");

        @Setting("world-not-allowed")
        public ComponentMessage worldNotAllowed = new ComponentMessage(
            "<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#FF6048>World <#FBFF8B><world><#FF6048> is configured to disallow this operation (worlds list).");
    }

    @ConfigSerializable
    public static class Deletion {
        public ComponentMessage queued = new ComponentMessage(
            "<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#0094D5>Created job <#FBFF8B><job><#0094D5>; it will run automatically after the target becomes cold.");
        @Setting("bulk-queued")
        public ComponentMessage bulkQueued = new ComponentMessage(
            "<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#0094D5>Created <#FBFF8B><count><#0094D5> chunk-deletion jobs; each will run in sequence after its target becomes cold.");
        @Setting("bulk-confirm-required")
        public ComponentMessage bulkConfirmRequired = new ComponentMessage(
            "<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#FFAA00>You are about to schedule deletion of <#FBFF8B><count><#FFAA00> marked chunks. Enter \"<#FBFF8B>/cr delete chunk all --confirm<#FFAA00>\" within <timeout> seconds to confirm.");
        @Setting("bulk-region-queued")
        public ComponentMessage bulkRegionQueued = new ComponentMessage(
            "<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#0094D5>Created <#FBFF8B><count><#0094D5> complete region-cleanup jobs.");
        @Setting("bulk-region-confirm-required")
        public ComponentMessage bulkRegionConfirmRequired = new ComponentMessage(
            "<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#FFAA00>Found <#FBFF8B><count><#FFAA00> complete regions with all 1,024 positions marked. Enter \"<#FBFF8B>/cr prune region all --confirm<#FFAA00>\" within <timeout> seconds to confirm.");
        @Setting("empty-region-scan-started")
        public ComponentMessage emptyRegionScanStarted = new ComponentMessage(
            "<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#0094D5>Scanning world <#FBFF8B><world><#0094D5> for empty Anvil region files...");
        @Setting("empty-region-confirm-required")
        public ComponentMessage emptyRegionConfirmRequired = new ComponentMessage(
            "<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#FFAA00>Found <#FBFF8B><count><#FFAA00> regions containing shrinkable empty files, with an estimated <#FBFF8B><bytes><#FFAA00> recoverable. Enter \"<#FBFF8B>/cr prune empty <world> --confirm<#FFAA00>\" within <timeout> seconds to confirm.");
        @Setting("empty-region-none")
        public ComponentMessage emptyRegionNone = new ComponentMessage(
            "<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#7A7A7A>World <#FBFF8B><world><#7A7A7A> has no shrinkable empty Anvil region files.");
        @Setting("empty-region-scan-failed")
        public ComponentMessage emptyRegionScanFailed = new ComponentMessage(
            "<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#FF6048>Failed to scan empty Anvil regions — <reason>");
        @Setting("bulk-empty-region-queued")
        public ComponentMessage bulkEmptyRegionQueued = new ComponentMessage(
            "<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#0094D5>Created <#FBFF8B><count><#0094D5> empty-region shrink jobs; each will run in sequence after its target becomes cold.");
        @Setting("bulk-empty-region-progress")
        public ComponentMessage bulkEmptyRegionProgress = new ComponentMessage(
            "<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#8BFF7B>Finished shrinking <#FBFF8B><count><#8BFF7B> empty regions, recovering approximately <#FBFF8B><bytes><#8BFF7B> in actual space.");
        @Setting("bulk-empty-region-done")
        public ComponentMessage bulkEmptyRegionDone = new ComponentMessage(
            "<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#8BFF7B>All empty-region shrink jobs in this run are complete — processed <#FBFF8B><count><#8BFF7B> regions and recovered approximately <#FBFF8B><bytes><#8BFF7B> in actual space.");
        @Setting("no-complete-regions")
        public ComponentMessage noCompleteRegions = new ComponentMessage(
            "<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#7A7A7A>No complete region has all 1,024 chunk positions marked. No jobs were created to avoid deleting unmarked areas.");
        public ComponentMessage started = new ComponentMessage(
            "<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#0094D5>Job <#FBFF8B><job><#0094D5> has started.");
        public ComponentMessage done = new ComponentMessage(
            "<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#8BFF7B>Job <#FBFF8B><job><#8BFF7B> completed, recovering approximately <#FBFF8B><bytes><#8BFF7B>.");
        @Setting("bulk-progress")
        public ComponentMessage bulkProgress = new ComponentMessage(
            "<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#8BFF7B>Batch chunk deletion completed for <#FBFF8B><count><#8BFF7B> chunks, recovering approximately <#FBFF8B><bytes><#8BFF7B> of reusable Anvil space (the files may not shrink).");
        @Setting("bulk-done")
        public ComponentMessage bulkDone = new ComponentMessage(
            "<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#8BFF7B>All chunk-deletion jobs in this run are complete — processed <#FBFF8B><count><#8BFF7B> chunks and recovered approximately <#FBFF8B><bytes><#8BFF7B> of reusable Anvil space (the files may not shrink).");
        public ComponentMessage failed = new ComponentMessage(
            "<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#FF6048>Job <#FBFF8B><job><#FF6048> failed — <reason>");
        public ComponentMessage cancelled = new ComponentMessage(
            "<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#8BFF7B>Cancelled <count> pending deletion/cleanup jobs.");
        @Setting("region-confirm-required")
        public ComponentMessage regionConfirmRequired = new ComponentMessage(
            "<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#FFAA00>You are about to schedule cleanup of physical region <#FBFF8B>r.<rx>.<rz><#FFAA00> in world <#FBFF8B><world><#FFAA00> (up to 1,024 chunks). Enter \"<#FBFF8B><command> --confirm<#FFAA00>\" within <timeout> seconds to confirm.");
        public ComponentMessage protectedRegion = new ComponentMessage(
            "<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#FF6048>Region r.<rx>.<rz> contains Residence-protected chunks; cleanup refused.");
        public ComponentMessage noJobs = new ComponentMessage(
            "<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#7A7A7A>There are currently no pending or active deletion/cleanup jobs.");
    }

    @ConfigSerializable
    public static class Scan {
        @Setting("confirm-required")
        public ComponentMessage confirmRequired = new ComponentMessage(
            "<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#FFAA00>You are about to scan world <#FBFF8B><world><#FFAA00> (approximately <region_count> regions). Enter \"<#FBFF8B><command> --confirm<#FFAA00>\" within <timeout> seconds to confirm.");

        @Setting("confirm-expired")
        public ComponentMessage confirmExpired = new ComponentMessage(
            "<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#7A7A7A>Confirmation timed out. The operation was cancelled; run the command again.");

        @Setting("world-not-allowed")
        public ComponentMessage worldNotAllowed = new ComponentMessage(
            "<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#FF6048>World <#FBFF8B><world><#FF6048> is configured to disallow this operation (worlds list).");

        @Setting("radius-too-large")
        public ComponentMessage radiusTooLarge = new ComponentMessage(
            "<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#FF6048>Radius <#FBFF8B><radius><#FF6048> exceeds the limit of <#FBFF8B><max><#FF6048>. Use /cr mark full or reduce the radius.");

        @Setting("scan-started")
        public ComponentMessage scanStarted = new ComponentMessage(
            "<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#0094D5>Scan started for world <#FBFF8B><world><#0094D5>.");

        @Setting("scan-already-running")
        public ComponentMessage scanAlreadyRunning = new ComponentMessage(
            "<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#FF6048>A scan task is already running. Please wait or use /cr cancel to stop it.");

        @Setting("regen-running")
        public ComponentMessage regenRunning = new ComponentMessage(
            "<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#FF6048>Batch regeneration is currently running, and scan.allow-concurrent-with-regen is false. Wait for regeneration to finish before scanning.");

        @Setting("scan-complete")
        public ComponentMessage scanComplete = new ComponentMessage(
            "<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#8BFF7B>Scan complete — found <#FBFF8B><found><#8BFF7B> chunks and added <#FBFF8B><marked><#8BFF7B> marks (skipped <skipped_existing> already marked, <skipped_claimed> claimed, and <skipped_biome_mismatch> with non-matching biomes).");

        @Setting("unknown-biome")
        public ComponentMessage unknownBiome = new ComponentMessage(
            "<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#FF6048>Unrecognized biome IDs — <#FBFF8B><ids><#FF6048>. Check the spelling or namespace (for example, minecraft — desert).");

        @Setting("biome-confirm-required")
        public ComponentMessage biomeConfirmRequired = new ComponentMessage(
            "<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#FFAA00>You are about to scan world <#FBFF8B><world><#FFAA00> for chunks matching biomes \"<#FBFF8B><biomes><#FFAA00>\" (approximately <region_count> regions). Enter \"<#FBFF8B><command> --confirm<#FFAA00>\" within <timeout> seconds to confirm.");

        @Setting("scan-failed")
        public ComponentMessage scanFailed = new ComponentMessage(
            "<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#FF6048>Scan failed — <reason>");

        @Setting("reset-confirm-required")
        public ComponentMessage resetConfirmRequired = new ComponentMessage(
            "<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#FFAA00>You are about to clear all marks and structure records in world <#FBFF8B><world><#FFAA00> (terrain will not be affected). Enter \"<#FBFF8B>/cr mark resetmark <world> --confirm<#FFAA00>\" within <timeout> seconds to confirm.");

        @Setting("reset-complete")
        public ComponentMessage resetComplete = new ComponentMessage(
            "<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#8BFF7B>Cleared <count> marks and <group_count> structure groups from world <#FBFF8B><world><#8BFF7B>.");
    }

    @ConfigSerializable
    public static class List {
        public ComponentMessage title = new ComponentMessage("Marked Chunk List");
        public ComponentMessage empty = new ComponentMessage("<#7A7A7A>There are currently no marked chunks.");
        public ComponentMessage entry = new ComponentMessage("<#0094D5><cx_cz> <#7A7A7A><world> (<marked_by> · <marked_at>)");

        @Setting("page-info")
        public ComponentMessage pageInfo = new ComponentMessage("Page <page> / <total>");

        @Setting("btn-prev")
        public ComponentMessage btnPrev = new ComponentMessage("◀ Previous");

        @Setting("btn-next")
        public ComponentMessage btnNext = new ComponentMessage("Next ▶");

        @Setting("btn-unmark")
        public ComponentMessage btnUnmark = new ComponentMessage("Unmark");

        @Setting("btn-regen")
        public ComponentMessage btnRegen = new ComponentMessage("Regenerate");
    }

    @ConfigSerializable
    public static class Keepchunk {
        public ComponentMessage on = new ComponentMessage("<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#FBFF8B>Chunk-unmark mode enabled. Marked chunks you walk through will be unmarked automatically.");
        public ComponentMessage off = new ComponentMessage("<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#8BFF7B>Chunk-unmark mode disabled.");
    }

    @ConfigSerializable
    public static class Display {
        public ComponentMessage line1 = new ComponentMessage("<#FF6048><bold>Pending Refresh Chunk");
        public ComponentMessage line2 = new ComponentMessage("<#7A7A7A><cx_cz>");
        public ComponentMessage line3 = new ComponentMessage("<#FBFF8B>Enter <white>/keepchunk<#FBFF8B> to cancel the refresh");

        @Setting("structure-line1")
        public ComponentMessage structureLine1 = new ComponentMessage("<#FBFF8B><bold><structure_name>");

        @Setting("structure-line2")
        public ComponentMessage structureLine2 = new ComponentMessage("<#7A7A7A>Refreshes in <#FBFF8B><days> days");
    }

    @ConfigSerializable
    public static class Structure {
        public ComponentMessage detected = new ComponentMessage("<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#8BFF7B>Detected structure <#FBFF8B><structure_name><#8BFF7B>; automatically marked <count> chunks within its range.");

        @Setting("check-inside")
        public ComponentMessage checkInside = new ComponentMessage("<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#0094D5>You are currently inside structure <#FBFF8B><structure_name><#0094D5> at <cx_cz>, range <range>.");

        @Setting("check-outside")
        public ComponentMessage checkOutside = new ComponentMessage("<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#7A7A7A>You are not currently inside any known structure range.");

        @Setting("check-refresh")
        public ComponentMessage checkRefresh = new ComponentMessage("<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#7A7A7A>Next refresh — in <#FBFF8B><days> days <hours> hours<#7A7A7A> (<status>).");

        @Setting("refresh-start")
        public ComponentMessage refreshStart = new ComponentMessage("<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#0094D5>Structure <#FBFF8B><structure_name><#0094D5> has expired. Starting automatic refresh (<count> chunks)...");

        @Setting("refresh-blocked")
        public ComponentMessage refreshBlocked = new ComponentMessage("<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#7A7A7A>Structure <#FBFF8B><structure_name><#7A7A7A> has expired, but it is protected; automatic refresh skipped.");

        @Setting("protection-progress")
        public ComponentMessage protectionProgress = new ComponentMessage("<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#7A7A7A>Protection progress — <#FBFF8B><percent>%<#7A7A7A> (protected in <remaining>)");

        @Setting("protection-entered")
        public ComponentMessage protectionEntered = new ComponentMessage("<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#8BFF7B>Structure <#FBFF8B><structure_name><#8BFF7B> is now protected and will no longer refresh automatically.");

        @Setting("none-nearby")
        public ComponentMessage noneNearby = new ComponentMessage("<#7A7A7A>[<#0094D5>ChunkRevive<#7A7A7A>] <#7A7A7A>There are no tracked structures nearby.");

        public List list = new List();

        @ConfigSerializable
        public static class List {
            public ComponentMessage title = new ComponentMessage("Structure Group List");
            public ComponentMessage empty = new ComponentMessage("<#7A7A7A>There are currently no tracked structure groups.");
            public ComponentMessage entry = new ComponentMessage("<#0094D5><structure_name><#7A7A7A> <range> (<status>)");
        }
    }

    @ConfigSerializable
    public static class Descriptions {
        public String help = "Show command help";
        @Setting("mark-help")
        public String markHelp = "Show help for marking commands";
        @Setting("mark-mark")
        public String markMark = "Mark the chunk the player is currently in";
        @Setting("mark-coords")
        public String markCoords = "Mark a chunk at specified world and coordinates";
        @Setting("mark-follow")
        public String markFollow = "Toggle automatic chunk marking while moving";
        @Setting("mark-list")
        public String markList = "List all manually marked chunks";
        @Setting("mark-resetmark")
        public String markResetmark = "Reset and clear all chunk marks in the specified world";
        @Setting("mark-resetmark-confirm")
        public String markResetmarkConfirm = "Confirm clearing all chunk marks in the specified world";
        @Setting("mark-fullmark")
        public String markFullmark = "Scan all expired chunks in the specified world and mark them";
        @Setting("mark-fullmark-confirm")
        public String markFullmarkConfirm = "Confirm scanning all expired chunks in the specified world and marking them";
        @Setting("mark-radius")
        public String markRadius = "Mark expired chunks within the specified block radius";
        @Setting("mark-biomefull")
        public String markBiomefull = "Scan all expired chunks matching the specified biomes in a world and mark them";
        @Setting("mark-biomefull-confirm")
        public String markBiomefullConfirm = "Confirm scanning all expired chunks matching the specified biomes in a world and marking them";
        @Setting("mark-biomeradius")
        public String markBiomeradius = "Mark expired chunks matching the specified biomes within the specified block radius";
        @Setting("mark-here-biome")
        public String markHereBiome = "Automatically detect and mark the entire biome area the player is currently in (existing expired chunks only)";
        @Setting("mark-here-struct")
        public String markHereStruct = "Detect and mark tracked structures near the player";

        @Setting("unmark-help")
        public String unmarkHelp = "Show help for unmarking commands";
        @Setting("unmark-here")
        public String unmarkHere = "Unmark the current chunk";
        @Setting("unmark-chunk")
        public String unmarkChunk = "Unmark the chunk at the specified coordinates";
        @Setting("unmark-follow")
        public String unmarkFollow = "Toggle automatic unmarking while moving";

        @Setting("regen-help")
        public String regenHelp = "Show help for chunk regeneration commands";
        @Setting("regen-here")
        public String regenHere = "Force-regenerate the current chunk";
        @Setting("regen-chunk")
        public String regenChunk = "Force-regenerate a chunk at the specified world and coordinates";
        @Setting("regen-struct")
        public String regenStruct = "Force-regenerate the complete structure containing the current chunk";
        @Setting("regen-here-struct-confirm")
        public String regenHereStructConfirm = "Confirm force-regenerating the complete structure containing the current chunk";
        @Setting("regen-here-biome")
        public String regenHereBiome = "Automatically detect, mark, and regenerate the entire biome area the player is currently in";
        @Setting("regen-here-biome-confirm")
        public String regenHereBiomeConfirm = "Confirm automatically detecting, marking, and regenerating the entire biome area the player is currently in";
        @Setting("regen-all-chunks")
        public String regenAllChunks = "Batch-regenerate all manually marked standalone chunks";
        @Setting("regen-all-structures")
        public String regenAllStructures = "Batch-regenerate all marked structures";
        @Setting("regen-all-all")
        public String regenAllAll = "Batch-regenerate all marked chunks and structures";
        @Setting("regen-all-chunks-confirm")
        public String regenAllChunksConfirm = "Confirm batch-regenerating all manually marked standalone chunks";
        @Setting("regen-all-structures-confirm")
        public String regenAllStructuresConfirm = "Confirm batch-regenerating all marked structures";
        @Setting("regen-all-all-confirm")
        public String regenAllAllConfirm = "Confirm batch-regenerating all marked chunks and structures";

        @Setting("reset-here")
        public String resetHere = "Reset the current chunk according to reset-strategy";
        @Setting("reset-here-confirm")
        public String resetHereConfirm = "Confirm resetting the current chunk according to reset-strategy";
        @Setting("reset-here-struct")
        public String resetHereStruct = "Reset the marked structure containing the player according to reset-strategy";
        @Setting("reset-here-struct-confirm")
        public String resetHereStructConfirm = "Confirm resetting the marked structure containing the player according to reset-strategy";
        @Setting("reset-here-biome")
        public String resetHereBiome = "Detect and reset the current contiguous biome according to reset-strategy";
        @Setting("reset-here-biome-confirm")
        public String resetHereBiomeConfirm = "Confirm resetting the current contiguous biome according to reset-strategy";
        @Setting("reset-chunk")
        public String resetChunk = "Reset a chunk at the specified world and coordinates according to reset-strategy";
        @Setting("reset-chunk-confirm")
        public String resetChunkConfirm = "Confirm resetting a chunk at the specified world and coordinates according to reset-strategy";
        @Setting("reset-all-chunks")
        public String resetAllChunks = "Reset all manually marked standalone chunks according to reset-strategy";
        @Setting("reset-all-structures")
        public String resetAllStructures = "Reset all marked structures according to reset-strategy";
        @Setting("reset-all-all")
        public String resetAllAll = "Reset all marked chunks and structures according to reset-strategy";
        @Setting("reset-all-chunks-confirm")
        public String resetAllChunksConfirm = "Confirm resetting all manually marked standalone chunks according to reset-strategy";
        @Setting("reset-all-structures-confirm")
        public String resetAllStructuresConfirm = "Confirm resetting all marked structures according to reset-strategy";
        @Setting("reset-all-all-confirm")
        public String resetAllAllConfirm = "Confirm resetting all marked chunks and structures according to reset-strategy";

        @Setting("delete-chunk-here")
        public String deleteChunkHere = "Delete terrain, entities, and POI data from the current chunk after it unloads";
        @Setting("delete-chunk-coords")
        public String deleteChunkCoords = "Delete terrain, entities, and POI data from the specified chunk after it unloads";
        @Setting("delete-chunk-all")
        public String deleteChunkAll = "Delete terrain, entities, and POI data from all marked chunks after they unload";
        @Setting("delete-here-chunk")
        public String deleteHereChunk = "Delete the single chunk the player is currently in after it unloads";
        @Setting("delete-here-struct")
        public String deleteHereStruct = "Delete the marked structure range containing the player after confirmation";
        @Setting("delete-here-biome")
        public String deleteHereBiome = "Detect and delete all complete chunks in the current contiguous biome after confirmation";
        @Setting("delete-marked")
        public String deleteMarked = "Delete all chunks marked by ChunkRevive across all worlds after confirmation";
        @Setting("delete-marked-world")
        public String deleteMarkedWorld = "Delete all chunks marked by ChunkRevive in the specified world after confirmation";
        @Setting("prune-region-here")
        public String pruneRegionHere = "Clear and shrink the current 32x32 Anvil region online after confirmation";
        @Setting("prune-region-coords")
        public String pruneRegionCoords = "Clear and shrink the specified 32x32 Anvil region online after confirmation";
        @Setting("prune-region-chunk")
        public String pruneRegionChunk = "Select and clean the containing 32x32 Anvil region using chunk coordinates";
        @Setting("prune-region-all")
        public String pruneRegionAll = "Clean all complete Anvil regions with all 1,024 positions marked";
        @Setting("prune-empty")
        public String pruneEmpty = "Scan and shrink Anvil region files whose indexes are completely empty in the specified world online";

        public String status = "View paginated progress for batch regeneration, world scans, and deletion jobs";
        public String server = "View server hardware specifications, threads, and database status";
        public String cancel = "Stop the currently running batch regeneration or scan task";
        public String reload = "Reload configuration and language files";
        public String regions = "View the number of regions in the specified world";
        public String tune = "Generate regeneration and scan tuning recommendations based on server processors and memory";

        @Setting("struct-check")
        public String structCheck = "Check the protection status of the structure in the player's current chunk";
        @Setting("struct-list")
        public String structList = "List all detected structure groups";
        @Setting("struct-regen")
        public String structRegen = "Manually force-regenerate the specified structure group";
        @Setting("struct-unblock")
        public String structUnblock = "Remove protection from the specified structure group";
        @Setting("struct-block")
        public String structBlock = "Manually lock and protect the specified structure group";
        @Setting("struct-reset")
        public String structReset = "Reset the protection status and timer of the specified structure group";
        @Setting("struct-resetall")
        public String structResetAll = "Batch-reset the status and timers of all structures";
        @Setting("struct-resetall-confirm")
        public String structResetAllConfirm = "Confirm batch-resetting the status and timers of all structures";
    }
}
