package github.freshchromatic.chunkrevive.config;

import github.freshchromatic.freshlib.config.Config;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;
import org.spongepowered.configurate.objectmapping.meta.Setting;
import github.freshchromatic.chunkrevive.feature.reset.ResetMethod;

import java.util.LinkedHashMap;
import java.util.Map;

@ConfigSerializable
public final class PluginConfig implements Config {

    @Override
    public String getFileName() {
        return "config.yml";
    }

    public Database database = new Database();
    public Regen regen = new Regen();
    @Setting("reset-strategy")
    public ResetStrategy resetStrategy = new ResetStrategy();
    public Deletion deletion = new Deletion();
    public Display display = new Display();
    public Structure structure = new Structure();
    public Worlds worlds = new Worlds();
    public Scan scan = new Scan();
    public Biome biome = new Biome();
    public Safety safety = new Safety();

    @Setting("enable-debug-logs")
    @Comment("Whether to show detailed structure, terrain generation, and deletion diagnostic logs")
    public boolean enableDebugLogs = false;

    @ConfigSerializable
    public static class ResetStrategy {
        @Setting("default-method")
        @Comment("Base method inherited by condition-specific DEFAULT values. Supported: REGENERATE, DELETE_CHUNK, DELETE_REGION.")
        public String defaultMethod = "DELETE_CHUNK";

        @Setting("eligible-region-method")
        @Comment("Method used when all 1024 chunks of an Anvil region are selected and the region has no Residence or protected structure. Supported: DEFAULT, REGENERATE, DELETE_CHUNK, DELETE_REGION.")
        public String eligibleRegionMethod = "DELETE_REGION";

        @Setting("incomplete-region-method")
        @Comment("Method used for targets that do not form a safe complete region. Supported: DEFAULT, REGENERATE, DELETE_CHUNK. DELETE_REGION is never allowed here and safely falls back to REGENERATE.")
        public String incompleteRegionMethod = "DEFAULT";

        public ResetMethod defaultMethodEnum() {
            ResetMethod parsed = ResetMethod.parse(defaultMethod, ResetMethod.REGENERATE);
            return parsed == ResetMethod.DEFAULT ? ResetMethod.REGENERATE : parsed;
        }

        public ResetMethod eligibleRegionMethodEnum() {
            return ResetMethod.parse(eligibleRegionMethod, ResetMethod.DEFAULT);
        }

        public ResetMethod incompleteRegionMethodEnum() {
            return ResetMethod.parse(incompleteRegionMethod, ResetMethod.DEFAULT);
        }
    }

    @ConfigSerializable
    public static class Deletion {
        @Setting("check-interval-ticks")
        @Comment("How often waiting chunk-delete and region-prune jobs are checked for a cold target.")
        public long checkIntervalTicks = 20L;

        @Setting("progress-report-chunks")
        @Comment("Combine normal chunk-deletion completion messages into one progress message per this many chunks.")
        public int progressReportChunks = 10_000;

        @Setting("progress-report-regions")
        @Comment("Combine empty-region pruning completion messages into one progress message per this many regions.")
        public int progressReportRegions = 100;

        @Setting("region-batches-per-cycle")
        @Comment("Maximum number of distinct Anvil regions processed per deletion cycle. Values are clamped to 1-64.")
        public int regionBatchesPerCycle = 4;

        @Setting("chunks-per-region-batch")
        @Comment("Maximum chunk deletions submitted for each selected Anvil region per cycle. "
            + "This is the primary deletion I/O throttle; values are clamped to 1-1024.")
        public int chunksPerRegionBatch = 256;

        @Setting("resume-on-startup")
        @Comment("Persist deletion jobs and resume unfinished work after a server restart.")
        public boolean resumeOnStartup = true;

        @Setting("player-safety-padding-chunks")
        @Comment("Additional cold chunks required around online chunk-delete and region-prune targets before deletion may start.")
        public int playerSafetyPaddingChunks = 12;

        @Setting("force-region-file-to-disk")
        @Comment("Force the empty 8 KiB region header to stable storage after online truncation. Safer, but adds an fsync per file.")
        public boolean forceRegionFileToDisk = true;

        @Setting("auto-unmark-after-delete")
        @Comment("Remove matching ChunkRevive marks after a chunk delete or successful region prune.")
        public boolean autoUnmarkAfterDelete = true;
    }

    @ConfigSerializable
    public static class Database {
        @Comment("Database type: sqlite or mysql")
        public String type = "sqlite";
        public Sqlite sqlite = new Sqlite();
        public Mysql mysql = new Mysql();

        @ConfigSerializable
        public static class Sqlite {
            public String file = "chunkrevive.db";
        }

        @ConfigSerializable
        public static class Mysql {
            public String host = "localhost";
            public int port = 3306;
            public String database = "chunkrevive";
            public String username = "root";
            public String password = "";
        }
    }

    @ConfigSerializable
    public static class Regen {
        @Setting("batch-delay-ticks")
        @Comment("Ticks to wait between chunks during batch regen (20 ticks = 1 second)")
        public int batchDelayTicks = 10;

        @Setting("batch-concurrency")
        @Comment("Number of concurrent chunk regenerations during batch regen")
        public int batchConcurrency = 1;

        @Setting("auto-unmark-after-regen")
        @Comment("Whether to automatically unmark a chunk after it has been regenerated")
        public boolean autoUnmarkAfterRegen = true;

        @Setting("max-chunks-per-batch")
        @Comment("Optional additional cap for a logical adjacency group (0 = no additional cap).\n"
            + "The independent work-tile-size safety bound applies to ordinary and biome work. Complete\n"
            + "structure groups remain atomic when regen-full-structure-range is enabled so cross-chunk\n"
            + "structure decoration cannot be clipped at an artificial batch boundary.")
        public int maxChunksPerBatch = 0;

        @Setting("work-tile-size")
        @Comment("Hard upper bound for the number of target chunks retained by one bulk-generation work tile.\n"
            + "Unlike max-chunks-per-batch this is normally enforced when max-chunks-per-batch is 0.\n"
            + "Complete structure groups are deliberately exempt when regen-full-structure-range is enabled:\n"
            + "splitting their FEATURES graph can clip structure pieces and repeats expensive context work.\n"
            + "Recommended: 128 for a 4 GiB heap.")
        public int workTileSize = 128;

        @Setting("work-tile-mode")
        @Comment("How bulk targets are spatially grouped before they are split into bounded work tiles.\n"
            + "LOGICAL preserves the existing structure/biome/adjacency groups. MCA regroups every target\n"
            + "by its 32x32 Anvil region-file coordinate before splitting, improving locality for world-scale regen.\n"
            + "Terrain halos may still cross MCA boundaries; this option only changes target scheduling.")
        public String workTileMode = "LOGICAL";

        @Setting("fixed-work-tile-size")
        @Comment("Balance partitions within each spatial bucket instead of filling every tile to work-tile-size\n"
            + "and leaving a potentially tiny remainder. For example, 193 targets with a cap of 128 become\n"
            + "97+96 instead of 128+65. Sparse final buckets may still be smaller than the configured cap.")
        public boolean fixedWorkTileSize = false;

        @Setting("work-tile-span")
        @Comment("Width and height, in chunks, of the stable spatial grid used to form work tiles.\n"
            + "Spatial tiling keeps each context halo compact; 16 is a good default for 4-8 GiB heaps.\n"
            + "Ignored when work-tile-mode is MCA, whose span is always the Anvil region width of 32 chunks.")
        public int workTileSpan = 16;

        public enum WorkTileMode { LOGICAL, MCA }

        public WorkTileMode workTileModeEnum() {
            if (workTileMode == null) return WorkTileMode.LOGICAL;
            try {
                return WorkTileMode.valueOf(workTileMode.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return WorkTileMode.LOGICAL;
            }
        }

        @Setting("context-radius")
        @Comment("Radius (in chunks) of context loaded around the regenerated chunks. Default is 2.\n"
            + "Lowering this (e.g. to 2) significantly reduces disk reads and DFU conversion overhead\n"
            + "during generation at the risk of slightly clipping caves/ravines near the boundary of the\n"
            + "regenerated area. Do not set below 2, as some structures (e.g. Ancient Cities) require at least\n"
            + "a radius of 2 during features decoration.")
        public int contextRadius = 2;

        @Setting("apply-batch-size")
        @Comment("How many regenerated chunks' live-apply step (Phase 2c: clear entities, copy blocks into the\n"
            + "loaded chunk, relight, fire neighbor updates) may run per server tick. Every chunk in a batch is\n"
            + "scheduled via the region scheduler at virtually the same instant, and Folia drains everything\n"
            + "already queued for a region in that region's next single tick — so without this cap, a large\n"
            + "structure regen (e.g. 240 chunks all owned by the same region) dumps its entire apply step into\n"
            + "one tick, stalling it for as long as the whole step takes. Lower values spread the cost over\n"
            + "more ticks at the expense of taking longer overall to finish applying.")
        public int applyBatchSize = 8;

        @Setting("thread-pool")
        public ThreadPool threadPool = new ThreadPool();

        /**
         * Guardrails for bulk terrain generation.  A generated chunk retains several ProtoChunk
         * neighbours and their palettes until its disk write has completed, so counting only the
         * centre chunks is not enough to keep a small heap safe.
         */
        @Setting("memory-safety")
        public MemorySafety memorySafety = new MemorySafety();

        @ConfigSerializable
        public static class MemorySafety {
            @Comment("Enable heap-aware limits for bulk regeneration. Keep this enabled unless the server has been profiled with a larger heap.")
            public boolean enabled = true;

            @Setting("max-active-batches")
            @Comment("Hard limit for simultaneously generating batches. Supported values: AUTO (heap-derived cap), CONFIG or IGNORE (use regen.batch-concurrency without an extra safety cap), or a positive integer. Legacy 0 is treated as AUTO.")
            public String maxActiveBatches = "AUTO";

            @Setting("max-chunks-per-batch")
            @Comment("Additional target-count cap for ordinary and biome work tiles. Supported values: AUTO (heap-derived cap), CONFIG or IGNORE (follow regen.max-chunks-per-batch), or a positive integer. Complete structure groups remain atomic when regen-full-structure-range is enabled.")
            public String maxChunksPerBatch = "CONFIG";

            @Setting("max-generation-threads")
            @Comment("Hard limit for terrain-generator workers. Supported values: AUTO (heap/CPU-derived cap), CONFIG or IGNORE (use regen.thread-pool.parallelism without an extra safety cap), or a positive integer. Legacy 0 is treated as AUTO.")
            public String maxGenerationThreads = "AUTO";

            @Setting("heap-high-watermark-percent")
            @Comment("Do not start another batch while used JVM heap is at or above this percentage of -Xmx. Existing batches are allowed to finish.")
            public int heapHighWatermarkPercent = 85;
        }

        @ConfigSerializable
        public static class ThreadPool {
            @Setting("parallelism")
            @Comment("The parallelism level (number of concurrent threads) to use for terrain generation.\n"
                + "Supported values:\n"
                + "  - \"AUTO\": Automatically set to (Available Logical Processors - 1), minimum 2.\n"
                + "  - \"MAX\": Use all available logical processors.\n"
                + "  - \"HALF\": Use half of the available logical processors, minimum 1.\n"
                + "  - A custom number (e.g. \"4\", \"8\"): Specify a fixed thread count between 1 and 256.\n"
                + "Defaults to \"AUTO\".")
            public String parallelism = "AUTO";

            @Setting("priority")
            @Comment("Thread priority for the terrain generation threads.\n"
                + "Acceptable range is 1 (lowest priority) to 10 (highest priority).\n"
                + "Defaults to 5 (NORM_PRIORITY). Modifying this may impact server tick performance.")
            public int priority = 5;

            @Setting("daemon")
            @Comment("Whether to configure these threads as daemon threads.\n"
                + "If true, these threads will not prevent the JVM from exiting when the server stops.\n"
                + "It is highly recommended to keep this set to true.")
            public boolean daemon = true;

            @Setting("async-mode")
            @Comment("The async scheduling mode for the ForkJoinPool.\n"
                + "If false, utilizes LIFO (Last-In-First-Out) queue scheduling, which is optimized for recursive divide-and-conquer tasks.\n"
                + "If true, utilizes FIFO (First-In-First-Out) queue scheduling, which is optimized for independent queue tasks.\n"
                + "Defaults to false.")
            public boolean asyncMode = false;
        }
    }

    @ConfigSerializable
    public static class Display {
        @Comment("Whether marked-chunk floating TextDisplay markers are enabled. Requires PacketEvents.")
        public boolean enabled = true;

        @Setting("render-radius-chunks")
        @Comment("Radius (in chunks) around the player within which markers are rendered")
        public int renderRadiusChunks = 4;

        @Setting("max-visible-chunks")
        @Comment("Hard cap on visible markers per player (sorted by proximity)")
        public int maxVisibleChunks = 20;

        @Setting("y-offset-from-eye")
        @Comment("TextDisplay Y position relative to the player's eye level")
        public double yOffsetFromEye = 0.5;

        @Setting("y-update-threshold")
        @Comment("Minimum Y delta before display positions are updated (reduces packet spam)")
        public double yUpdateThreshold = 0.0;

        @Setting("update-interval-ticks")
        @Comment("Tick interval for Y position update checks")
        public int updateIntervalTicks = 1;
    }

    @ConfigSerializable
    public static class Worlds {
        public enum ListMode { WHITELIST, BLACKLIST }

        @Comment("WHITELIST: list is the only set of worlds allowed. BLACKLIST (default): every world except those listed is allowed.")
        public String mode = ListMode.BLACKLIST.name();

        @Comment("World names this list applies to. Default empty list + BLACKLIST mode changes nothing (fully backward compatible).")
        public java.util.List<String> list = new java.util.ArrayList<>();

        public Scope scope = new Scope();

        public ListMode modeEnum() {
            try {
                return ListMode.valueOf(mode.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return ListMode.BLACKLIST;
            }
        }

        @ConfigSerializable
        public static class Scope {
            @Setting("manual-mark")
            @Comment("/cr mark, /cr unmark, follow mode")
            public boolean manualMark = true;

            @Setting("bulk-mark")
            @Comment("/cr fullmark, /cr radiusmark")
            public boolean bulkMark = true;

            @Setting("structure-auto-detect")
            @Comment("Passive structure auto-detection while players walk")
            public boolean structureAutoDetect = true;

            @Setting("structure-refresh")
            @Comment("StructureRefreshScheduler's periodic refresh")
            public boolean structureRefresh = true;

            @Comment("/cr regen, /cr regenallmark, /cr struct refresh")
            public boolean regen = true;
        }
    }

    @ConfigSerializable
    public static class Scan {
        @Setting("min-persisted-status")
        @Comment("Only chunks whose on-disk persisted status is at or beyond this value count as \"already existing\" for fullmark/radiusmark.\n"
            + "One of: NOISE, BIOMES, STRUCTURE_STARTS, STRUCTURE_REFERENCES, SURFACE, CARVERS, FEATURES, LIGHT, SPAWN, FULL.")
        public String minPersistedStatus = "FULL";

        @Setting("check-residence-claims")
        @Comment("Whether fullmark/radiusmark apply the same Residence policy as structure marking (structure.residence.on-partial-claim)")
        public boolean checkResidenceClaims = true;

        @Setting("radiusmark-max-radius-chunks")
        @Comment("Upper bound on /cr radiusmark's radius (chunks); larger ranges must use /cr fullmark instead")
        public int radiusmarkMaxRadiusChunks = 512;

        @Setting("allow-concurrent-with-regen")
        @Comment("Whether a disk scan may run at the same time as the regen batch queue (both are I/O heavy)")
        public boolean allowConcurrentWithRegen = false;

        @Setting("thread-pool")
        public ThreadPool threadPool = new ThreadPool();

        @ConfigSerializable
        public static class ThreadPool {
            @Comment("Each region task opens its own region file directly (bypassing the server's single-\n"
                + "threaded chunk I/O queue) and only decodes the chunk Status tag and tracked-structure\n"
                + "starts, not a full chunk. This is real concurrent disk I/O, so scale with available cores\n"
                + "(and lean lower on spinning disks, higher on SSD/NVMe).")
            public int parallelism = 4;
            public int priority = 3;
            public boolean daemon = true;
        }
    }

    @ConfigSerializable
    public static class Biome {
        @Setting("match-mode")
        @Comment("Sampling strategy for /cr mark biomeradius/biomefull:\n"
            + "  CENTER    -> sample only the chunk's center column (1 noise-height lookup, fastest, default).\n"
            + "  ANY_OF_16 -> sample all 4x4 quart columns, chunk matches if any one hits (16x the cost,\n"
            + "               catches biome-boundary chunks that CENTER would miss).")
        public String matchMode = "CENTER";

        @Setting("heightmap-type")
        @Comment("Which Heightmap.Types surface to sample the biome at: WORLD_SURFACE (default), OCEAN_FLOOR, or MOTION_BLOCKING.\n"
            + "Underground-only biomes (e.g. deep_dark, lush_caves) are never matched regardless of this setting.")
        public String heightmapType = "WORLD_SURFACE";

        @Setting("biomeradius-max-radius-chunks")
        @Comment("Upper bound on /cr mark biomeradius's radius (chunks); larger ranges must use /cr mark biomefull instead.\n"
            + "Kept independent from scan.radiusmark-max-radius-chunks because biome matching adds real CPU\n"
            + "cost (a noise-column query per chunk) on top of the disk-I/O cost radiusmark already has.")
        public int biomeradiusMaxRadiusChunks = 512;

        public Regen regen = new Regen();

        @ConfigSerializable
        public static class Regen {
            @Setting("regen-full-biome-range")
            @Comment("When a /cr regen all batch contains a contiguous run of biome-matched chunks, regen the whole\n"
                + "run as one atomic batch (ignoring regen.max-chunks-per-batch), same treatment as structure groups.\n"
                + "Biome ranges can be far larger than structures, so this is all-or-nothing if the server crashes\n"
                + "mid-batch — set to false to fall back to the normal adjacency-grouped, capped batching instead.")
            public boolean regenFullBiomeRange = true;

            @Setting("flood-fill-max-chunks")
            @Comment("Safety cap for /cr mark|regen here biome's auto-detected biome patch (BiomeRegionService's\n"
                + "flood fill normally stops at the real biome boundary or the edge of already-generated terrain,\n"
                + "but some biomes are naturally huge/unbounded, e.g. a fully-explored ocean or plains stretch).\n"
                + "Once the detected patch reaches this many chunks, the flood fill stops early and the command\n"
                + "reports the result as truncated.")
            public int floodFillMaxChunks = 4096;
        }

        public github.freshchromatic.chunkrevive.nms.BiomeMatchMode matchModeEnum() {
            try {
                return github.freshchromatic.chunkrevive.nms.BiomeMatchMode.valueOf(
                    matchMode.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return github.freshchromatic.chunkrevive.nms.BiomeMatchMode.CENTER;
            }
        }

        public github.freshchromatic.chunkrevive.nms.HeightmapKind heightmapTypeEnum() {
            try {
                return github.freshchromatic.chunkrevive.nms.HeightmapKind.valueOf(
                    heightmapType.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return github.freshchromatic.chunkrevive.nms.HeightmapKind.WORLD_SURFACE;
            }
        }
    }

    @ConfigSerializable
    public static class Safety {
        @Setting("confirm-timeout-seconds")
        @Comment("How long a pending confirmation (fullmark/resetmark/regenallmark) stays valid")
        public int confirmTimeoutSeconds = 30;

        @Setting("bulk-regen-confirm-threshold-chunks")
        @Comment("/cr regenallmark only requires confirmation when the chunks about to be queued exceed this count")
        public int bulkRegenConfirmThresholdChunks = 50;
    }

    @ConfigSerializable
    public static class Structure {
        @Comment("Master switch for the structure detection/refresh/protection subsystem; when false, nothing is scanned, scheduled, or blocked")
        public boolean enabled = true;

        public Detect detect = new Detect();
        public Mark mark = new Mark();
        public Refresh refresh = new Refresh();
        public Residence residence = new Residence();
        public Protection protection = new Protection();
        @Setting("entity-exemptions")
        public EntityExemptions entityExemptions = new EntityExemptions();
        public Regen regen = new Regen();

        @ConfigSerializable
        public static class Detect {
            @Setting("auto-detect-on-walk")
            @Comment("Whether walking players automatically detect and register nearby structures")
            public boolean autoDetectOnWalk = true;

            @Setting("notify-player-on-detect")
            @Comment("Whether to show the player a message when their movement detects and marks a new structure")
            public boolean notifyPlayerOnDetect = false;

            @Setting("scan-radius-chunks")
            @Comment("Detection scan radius in chunks; 0 only checks the player's current chunk")
            public int scanRadiusChunks = 0;
        }

        @ConfigSerializable
        public static class Mark {
            @Setting("expand-to-full-structure")
            @Comment("When marking a chunk that belongs to a structure, expand the mark to the whole structure's bounding box")
            public boolean expandToFullStructure = true;
        }

        // Plain enums aren't used for config fields directly: Configurate's YAML emitter writes them
        // back out with a "!!fully.qualified.Class$Name" global tag, which SnakeYAML's safe parser
        // then refuses to read back in on the next load. Store as String (like Database.type) and
        // parse on demand instead.
        public enum ListMode { WHITELIST, BLACKLIST }

        @ConfigSerializable
        public static class Refresh {
            @Comment("Set to false to disable automatic structure refreshes.")
            public boolean enabled = true;

            @Setting("list-mode")
            @Comment("BLACKLIST (default): refresh every detected structure except IDs in tracked-structures; uses default-interval-days.\n" +
                "WHITELIST: refresh only IDs in tracked-structures; each entry value is its refresh interval in days.")
            public String listMode = ListMode.BLACKLIST.name();

            @Setting("default-interval-days")
            @Comment("Refresh interval in days for every non-excluded structure in BLACKLIST mode. Ignored in WHITELIST mode.")
            public int defaultIntervalDays = 14;

            @Setting("tracked-structures")
            @Comment("Structure ID -> days map. Leave as {} to refresh all structures in BLACKLIST mode.\n" +
                "BLACKLIST: listed IDs are excluded; their day values are ignored (example: minecraft:trial_chambers: 0).\n" +
                "WHITELIST: only listed IDs refresh; set each value to its interval in days (example: minecraft:trial_chambers: 14).")
            public Map<String, Integer> trackedStructures = new LinkedHashMap<>();

            @Setting("check-interval-ticks")
            @Comment("How often the scheduler checks for due structure refreshes (ticks)")
            public long checkIntervalTicks = 6000L;

            public ListMode listModeEnum() {
                try {
                    return ListMode.valueOf(listMode.trim().toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    return ListMode.WHITELIST;
                }
            }

            /** Whether a real detected structure id counts as a "structure" under the current list mode. */
            public boolean isTracked(String realStructureId) {
                String key = github.freshchromatic.chunkrevive.feature.structure.StructureAliases.canonicalize(realStructureId);
                boolean inList = trackedStructures.containsKey(key);
                return listModeEnum() == ListMode.WHITELIST ? inList : !inList;
            }

            public int getIntervalDays(String realStructureId) {
                String key = github.freshchromatic.chunkrevive.feature.structure.StructureAliases.canonicalize(realStructureId);
                if (listModeEnum() == ListMode.WHITELIST) {
                    return trackedStructures.getOrDefault(key, defaultIntervalDays);
                }
                return defaultIntervalDays;
            }
        }

        public enum PartialClaimPolicy { EXCLUDE_CLAIMED, ABORT_WHOLE, IGNORE_CLAIMS }

        @ConfigSerializable
        public static class Residence {
            @Setting("on-partial-claim")
            @Comment("How to handle Residence claims overlapping a structure range: EXCLUDE_CLAIMED, ABORT_WHOLE, or IGNORE_CLAIMS")
            public String onPartialClaim = PartialClaimPolicy.EXCLUDE_CLAIMED.name();

            public PartialClaimPolicy onPartialClaimEnum() {
                try {
                    return PartialClaimPolicy.valueOf(onPartialClaim.trim().toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    return PartialClaimPolicy.EXCLUDE_CLAIMED;
                }
            }
        }

        @ConfigSerializable
        public static class Protection {
            @Setting("radius-chunks")
            @Comment("Radius (chunks) players must stay within to accumulate protection time")
            public int radiusChunks = 3;

            @Setting("required-ticks")
            @Comment("Cumulative residency ticks required before a structure becomes blocked from refresh (default 4h)")
            public long requiredTicks = 288000L;

            @Setting("flush-interval-ticks")
            @Comment("How often accumulated protection ticks are flushed to the database")
            public long flushIntervalTicks = 200L;

            @Setting("reset-on-leave")
            @Comment("Whether leaving the radius resets accumulated progress instead of pausing it")
            public boolean resetOnLeave = false;
        }

        @ConfigSerializable
        public static class EntityExemptions {
            @Setting("keep-ridden")
            public boolean keepRidden = true;

            @Setting("keep-leashed")
            public boolean keepLeashed = true;

            @Setting("keep-allay-attracted")
            public boolean keepAllayAttracted = true;

            @Setting("keep-tamed-pets")
            public boolean keepTamedPets = true;

            @Setting("tamed-pet-owner-radius")
            public double tamedPetOwnerRadius = 32.0;
        }

        @ConfigSerializable
        public static class Regen {
            @Setting("regen-full-structure-range")
            @Comment("When regenerating a chunk that belongs to a structure group, regenerate the whole group as one batch")
            public boolean regenFullStructureRange = true;
        }
    }
}
