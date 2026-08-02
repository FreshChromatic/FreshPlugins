package github.freshchromatic.chunkrevive.nms.v26_1_2.terrain;

import github.freshchromatic.chunkrevive.nms.TerrainSettings;
import github.freshchromatic.chunkrevive.nms.CarverStageCachePolicy;
import github.freshchromatic.chunkrevive.nms.TerrainJfr;
import github.freshchromatic.freshlib.scheduler.Scheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.util.StaticCache2D;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.levelgen.BelowZeroRetrogen;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntitySpawnReason;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Generates overworld terrain in-memory via NMS ProtoChunks and writes the
 * result directly to the target world's region files.
 */
final class V26_1_2TerrainEngine {

    private static JavaPlugin plugin;
    private static volatile TerrainSettings.EntityExemptions entityExemptions =
        new TerrainSettings.EntityExemptions(true, true, true, true, 32.0);
    private static volatile boolean debugLogging;

    public static void init(JavaPlugin p) {
        plugin = p;
    }

    public static void setEntityExemptions(TerrainSettings.EntityExemptions cfg) {
        entityExemptions = cfg;
    }

    private static volatile TerrainSettings.ThreadPool threadPoolConfig =
        new TerrainSettings.ThreadPool("AUTO", 5, true, false);

    public static void setThreadPoolConfig(TerrainSettings.ThreadPool cfg) {
        threadPoolConfig = cfg;
    }

    private static volatile TerrainSettings.MemorySafety memorySafetyConfig =
        new TerrainSettings.MemorySafety(true, "AUTO");

    public static void setMemorySafetyConfig(TerrainSettings.MemorySafety cfg) {
        memorySafetyConfig = cfg;
    }

    private static volatile int contextRadius = 4;

    public static void setContextRadius(int radius) {
        contextRadius = Math.max(2, radius);
    }

    public static TerrainSettings.ThreadPool getThreadPoolConfig() {
        return threadPoolConfig;
    }

    private static volatile int applyBatchSize = 8;

    public static void setApplyBatchSize(int size) {
        applyBatchSize = Math.max(1, size);
    }

    public static void setDebugLogging(boolean enabled) {
        debugLogging = enabled;
    }

    private static final java.util.Set<java.util.concurrent.ForkJoinPool> activePools =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static int getActiveGenerationThreads() {
        int count = 0;
        for (var pool : activePools) {
            count += pool.getActiveThreadCount();
        }
        return count;
    }

    private record PoolKey(int parallelism, int priority, boolean daemon, boolean asyncMode) {}

    private static volatile java.util.concurrent.ForkJoinPool sharedPool;
    private static volatile PoolKey sharedPoolKey;

    /**
     * Reuses one ForkJoinPool across regen calls instead of spinning a fresh pool up/down per call —
     * that churn was pure overhead for frequent single-chunk regens. Rebuilds only if the thread-pool
     * config actually changed since the pool was created.
     */
    private static synchronized java.util.concurrent.ForkJoinPool getOrCreatePool(
            int parallelism, int priority, boolean daemon, boolean asyncMode) {
        PoolKey key = new PoolKey(parallelism, priority, daemon, asyncMode);
        if (sharedPool == null || sharedPool.isShutdown() || !key.equals(sharedPoolKey)) {
            if (sharedPool != null) {
                sharedPool.shutdown();
                activePools.remove(sharedPool);
            }
            sharedPool = new java.util.concurrent.ForkJoinPool(
                parallelism,
                fjPool -> {
                    var t = java.util.concurrent.ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(fjPool);
                    t.setDaemon(daemon);
                    t.setPriority(priority);
                    return t;
                },
                null, asyncMode
            );
            sharedPoolKey = key;
            activePools.add(sharedPool);
        }
        return sharedPool;
    }

    /** Call from plugin disable so the shared pool's threads don't outlive the plugin. */
    public static void shutdownPool() {
        synchronized (V26_1_2TerrainEngine.class) {
            if (sharedPool != null) {
                sharedPool.shutdown();
                activePools.remove(sharedPool);
                sharedPool = null;
                sharedPoolKey = null;
            }
        }
        carverStageCache.clear();
        structureStartCache.clear();
        completedCarverScope = null;
        ioExecutor.shutdown();
    }

    /**
     * Dedicated executor for the blocking disk-write step in {@link #serializeOnRegionThread}.
     * That step calls {@code .join()} on region-file IO; running it on the default
     * {@code ForkJoinPool.commonPool()} would tie up a common-pool worker for the duration,
     * which is also what virtual threads (see {@link #generate}) use as their carrier pool.
     */
    private static final java.util.concurrent.ExecutorService ioExecutor =
        java.util.concurrent.Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("cr-terrain-io-", 0).factory());

    /**
     * ChunkMap#read is backed by MoonriseRegionFileIO and is genuinely asynchronous. Keep several
     * reads in flight so region I/O overlaps NBT decoding, while bounding the number of completed
     * raw tags that may wait for a generation worker.
     */
    private static final java.util.concurrent.Semaphore CONTEXT_READ_SLOTS =
        new java.util.concurrent.Semaphore(24);

    private static volatile BulkGenerationSession bulkSession;

    /**
     * A bounded cache of terrain immediately after CARVERS and before FEATURES. Unlike caching a
     * final FULL chunk, these snapshots contain no decoration entities, loot containers or loot
     * minecarts. Every restore still reruns FEATURES, so all mutable gameplay state is recreated.
     */
    private static final CarverStageCache carverStageCache = new CarverStageCache(512);

    /**
     * Pristine structure starts captured immediately after STRUCTURE_STARTS and before FEATURES.
     * Restores always deserialize new pieces, so placement flags and mutable piece state are never
     * shared between regeneration runs.
     */
    private static final StructureStartCache structureStartCache = new StructureStartCache(512);

    /** Last exact generation scope that successfully reached disk and live apply. */
    private static volatile GenerationScope completedCarverScope;

    private record GenerationScope(
            java.util.UUID worldId, long seed, int generatorIdentity,
            int randomStateIdentity, List<Long> targetChunks) {}

    private record CarverStageKey(
            java.util.UUID worldId, long seed, int generatorIdentity,
            int randomStateIdentity, long chunkKey, GenerationScope contextualScope) {}

    private record StructureStartCacheKey(
            java.util.UUID worldId, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
            long seed, int generatorIdentity, int structureStateIdentity,
            int templateManagerIdentity, long chunkKey) {}

    private record CachedStructureStarts(List<CompoundTag> starts) {
        private CachedStructureStarts copy() {
            return new CachedStructureStarts(starts.stream().map(CompoundTag::copy).toList());
        }
    }

    private static final class StructureStartCache {
        private final int maximumSize;
        private final ConcurrentHashMap<StructureStartCacheKey, CachedStructureStarts> entries =
            new ConcurrentHashMap<>();
        private final java.util.concurrent.ConcurrentLinkedQueue<StructureStartCacheKey> insertionOrder =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

        private StructureStartCache(int maximumSize) {
            this.maximumSize = maximumSize;
        }

        CachedStructureStarts get(StructureStartCacheKey key) {
            CachedStructureStarts value = entries.get(key);
            return value == null ? null : value.copy();
        }

        void put(StructureStartCacheKey key, CachedStructureStarts value) {
            if (value.starts().isEmpty()) return;
            if (entries.put(key, value.copy()) == null) insertionOrder.add(key);
            while (entries.size() > maximumSize) {
                StructureStartCacheKey oldest = insertionOrder.poll();
                if (oldest == null) break;
                entries.remove(oldest);
            }
        }

        void remove(StructureStartCacheKey key) {
            entries.remove(key);
        }

        int size() {
            return entries.size();
        }

        void clear() {
            entries.clear();
            insertionOrder.clear();
        }
    }

    private static final class CarverStageCache {
        private final int maximumSize;
        private final ConcurrentHashMap<CarverStageKey, CompoundTag> entries = new ConcurrentHashMap<>();
        private final java.util.concurrent.ConcurrentLinkedQueue<CarverStageKey> insertionOrder =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

        private CarverStageCache(int maximumSize) {
            this.maximumSize = maximumSize;
        }

        CompoundTag get(CarverStageKey key) {
            CompoundTag value = entries.get(key);
            return value == null ? null : value.copy();
        }

        void put(CarverStageKey key, CompoundTag value) {
            if (entries.put(key, value.copy()) == null) insertionOrder.add(key);
            while (entries.size() > maximumSize) {
                CarverStageKey oldest = insertionOrder.poll();
                if (oldest == null) break;
                entries.remove(oldest);
            }
        }

        void remove(CarverStageKey key) {
            entries.remove(key);
        }

        int size() {
            return entries.size();
        }

        void clear() {
            entries.clear();
            insertionOrder.clear();
        }
    }

    /** Starts a cache shared by all spatial tiles in one bulk queue run. */
    public static synchronized void beginBulkSession(int targetCount) {
        bulkSession = new BulkGenerationSession(targetCount <= 512);
    }

    /** Releases all cross-tile read cache entries and reports whether they actually amortised I/O. */
    public static synchronized void endBulkSession() {
        BulkGenerationSession session = bulkSession;
        bulkSession = null;
        if (session != null) {
            Logging.logger().info("[NmsTerrainGenerator] Bulk session context cache: "
                + session.hits.sum() + " hit(s), " + session.misses.sum() + " miss(es), "
                + session.reads.size() + " retained position(s).");
            session.reads.clear();
        }
    }

    private record ContextReadKey(java.util.UUID worldId, long chunkKey) {}

    private static final class BulkGenerationSession {
        private static final int MAX_CONTEXT_ENTRIES = 2_048;
        private final ConcurrentHashMap<ContextReadKey, CompletableFuture<java.util.Optional<CompoundTag>>> reads =
            new ConcurrentHashMap<>();
        private final java.util.concurrent.ConcurrentLinkedQueue<ContextReadKey> insertionOrder =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
        private final java.util.concurrent.atomic.LongAdder hits = new java.util.concurrent.atomic.LongAdder();
        private final java.util.concurrent.atomic.LongAdder misses = new java.util.concurrent.atomic.LongAdder();
        private final boolean retainAllCarverStages;

        private BulkGenerationSession(boolean retainAllCarverStages) {
            this.retainAllCarverStages = retainAllCarverStages;
        }

        CompletableFuture<java.util.Optional<CompoundTag>> read(
                java.util.UUID worldId, ChunkPos pos,
                java.util.function.Supplier<CompletableFuture<java.util.Optional<CompoundTag>>> loader) {
            ContextReadKey key = new ContextReadKey(worldId, pos.pack());
            CompletableFuture<java.util.Optional<CompoundTag>> existing = reads.get(key);
            if (existing != null) {
                hits.increment();
                return existing;
            }
            CompletableFuture<java.util.Optional<CompoundTag>> loaded = loader.get();
            CompletableFuture<java.util.Optional<CompoundTag>> winner = reads.putIfAbsent(key, loaded);
            if (winner != null) {
                hits.increment();
                return winner;
            }
            misses.increment();
            insertionOrder.add(key);
            trim();
            loaded.whenComplete((ignored, failure) -> {
                if (failure != null) reads.remove(key, loaded);
            });
            return loaded;
        }

        void update(java.util.UUID worldId, ChunkPos pos, CompoundTag nbt) {
            ContextReadKey key = new ContextReadKey(worldId, pos.pack());
            reads.put(key,
                CompletableFuture.completedFuture(java.util.Optional.of(nbt.copy())));
            insertionOrder.add(key);
            trim();
        }

        private void trim() {
            while (reads.size() > MAX_CONTEXT_ENTRIES) {
                ContextReadKey oldest = insertionOrder.poll();
                if (oldest == null) return;
                reads.remove(oldest);
            }
        }
    }

    public static int getResolvedParallelism() {
        int available = Runtime.getRuntime().availableProcessors();
        String rawPara = threadPoolConfig.parallelism() != null ? threadPoolConfig.parallelism().trim().toUpperCase() : "AUTO";
        int requested;
        switch (rawPara) {
            case "AUTO":
                requested = Math.max(2, available - 1);
                break;
            case "MAX":
                requested = Math.max(1, available);
                break;
            case "HALF":
                requested = Math.max(1, available / 2);
                break;
            default:
                try {
                    requested = Math.clamp(Integer.parseInt(rawPara), 1, 256);
                } catch (NumberFormatException e) {
                    requested = Math.max(2, available - 1);
                }
                break;
        }
        return Math.min(requested, memorySafeGenerationThreadLimit());
    }

    private static int memorySafeGenerationThreadLimit() {
        var safety = memorySafetyConfig;
        if (!safety.enabled()) return 256;
        long heapGiB = Math.max(1L, Runtime.getRuntime().maxMemory() / (1024L * 1024L * 1024L));
        int available = Runtime.getRuntime().availableProcessors();
        // The retained ProtoChunk graph is bounded separately by active-batch and batch-size
        // admission. Worker count mainly controls short-lived noise/feature scratch allocations,
        // so deriving it directly from heapGiB (the old 4 GB -> 2 workers rule) needlessly left
        // modern CPUs mostly idle. Keep a modest heap-dependent ceiling plus half the logical CPUs.
        int heapCap = heapGiB <= 4 ? 4 : heapGiB <= 8 ? 6 : 8;
        int cpuCap = Math.max(1, available / 2);
        int automatic = Math.max(1, Math.min(heapCap, cpuCap));
        return resolveSafetyCap(safety.maxGenerationThreads(), automatic);
    }

    private static int resolveSafetyCap(String raw, int automaticValue) {
        String value = raw == null ? "AUTO" : raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (value.equals("CONFIG") || value.equals("IGNORE") || value.equals("UNLIMITED")) {
            return Integer.MAX_VALUE;
        }
        if (value.equals("AUTO") || value.isEmpty() || value.equals("0")) {
            return Math.max(1, automaticValue);
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : Math.max(1, automaticValue);
        } catch (NumberFormatException ignored) {
            return Math.max(1, automaticValue);
        }
    }

    /** Structure-aware regen rule: entities ridden/leashed/attracted by players, or tamed pets near their owner, survive the clear. */
    private static boolean isExemptFromClear(org.bukkit.entity.Entity entity) {
        var cfg = entityExemptions;

        if (cfg.keepRidden() && !entity.getPassengers().isEmpty()
            && entity.getPassengers().stream().anyMatch(p -> p instanceof org.bukkit.entity.Player)) {
            return true;
        }

        if (cfg.keepLeashed() && entity instanceof org.bukkit.entity.LivingEntity le && le.isLeashed()
            && le.getLeashHolder() instanceof org.bukkit.entity.Player) {
            return true;
        }

        if (cfg.keepAllayAttracted() && entity instanceof org.bukkit.entity.Allay allay) {
            try {
                var nmsAllay = ((org.bukkit.craftbukkit.entity.CraftAllay) allay).getHandle();
                if (nmsAllay.getBrain().hasMemoryValue(net.minecraft.world.entity.ai.memory.MemoryModuleType.LIKED_PLAYER)) {
                    return true;
                }
            } catch (Exception ignored) {
                // Best-effort: if the NMS brain memory lookup ever breaks across versions, fall through to other checks.
            }
        }

        if (cfg.keepTamedPets() && entity instanceof org.bukkit.entity.Tameable tameable && tameable.isTamed()) {
            var owner = tameable.getOwner();
            if (owner instanceof org.bukkit.entity.Player ownerPlayer && ownerPlayer.isOnline()
                && ownerPlayer.getWorld().equals(entity.getWorld())
                && ownerPlayer.getLocation().distance(entity.getLocation()) <= cfg.tamedPetOwnerRadius()) {
                return true;
            }
        }

        return false;
    }

    // ChunkRevive always regenerates exactly one chunk at a time.
    private static int getMapChunks(World world) {
        return 1;
    }

    private static int getCarverRadius() {
        return contextRadius;
    }

    /** Vanilla/Paper FEATURES may write to the eight immediately-adjacent chunks. */
    private static final int FEATURE_WRITE_RADIUS = 1;
    /** createReferences scans a 17x17 area around every FEATURES source. */
    private static final int STRUCTURE_CONTEXT_RADIUS = FEATURE_WRITE_RADIUS + 8;
    private static final int DECORATION_STRIDE = FEATURE_WRITE_RADIUS * 2 + 1;
    // Some structure features (notably ancient cities) read two chunks away while
    // decorating. A radius of one causes Folia's unsafe-terrain-read guard to log
    // an error for every such lookup and can severely reduce bulk regen throughput.
    private static final int FEATURE_CACHE_RADIUS = 2;
    /** Caps how far an extraContextBounds request (see {@link #generate}) can widen the context window. */
    private static final int MAX_EXTRA_CONTEXT_RADIUS = 48;

    private static final int W_OUTER      = 5;
    private static final int W_CARVER     = 4;
    private static final int W_DECORATION = 60;
    private static final int W_SERIALIZE  = 15;
    private static final int W_LIGHT      = 5;

    private V26_1_2TerrainEngine() {}

    private record GenerationResult(
        ServerLevel level,
        List<ChunkPos> centerChunks,
        List<ChunkPos> outerChunks,
        Map<ChunkPos, ProtoChunk> chunks,
        IntConsumer addWeight,
        IntConsumer onProgress,
        Consumer<String> onStage,
        long seed,
        int originCX,
        int originCZ,
        int gridOriginCX,
        int gridOriginCZ,
        GenerationScope generationScope
    ) {}

    /**
     * Asynchronously generates terrain for the specified centerChunks
     * in {@code world} using {@code seed} and writes the result to the world's region files.
     *
     * <p>The returned future completes after all chunk data has been flushed to disk.
     */
    public static CompletableFuture<Void> generate(World world,
                                                    java.util.Collection<ChunkPos> centerChunksInput,
                                                    long seed,
                                                    IntConsumer onProgress, Consumer<String> onStage,
                                                    java.util.function.BooleanSupplier isCancelled) {
        return generate(world, centerChunksInput, seed, onProgress, onStage, isCancelled, null);
    }

    /**
     * @param extraContextBounds optional [minChunkX, maxChunkX, minChunkZ, maxChunkZ] (inclusive) that
     *                            gets unioned into the read-only context window around centerChunks.
     *                            Used so a single-chunk regen of a chunk that belongs to a known
     *                            structure can still see that structure's StructureStart (which may
     *                            live many chunks away) during STRUCTURE_STARTS/FEATURES, without
     *                            having to regenerate the whole structure. Chunks pulled in this way
     *                            are only ever read from disk for context — never written back.
     */
    public static CompletableFuture<Void> generate(World world,
                                                    java.util.Collection<ChunkPos> centerChunksInput,
                                                    long seed,
                                                    IntConsumer onProgress, Consumer<String> onStage,
                                                    java.util.function.BooleanSupplier isCancelled,
                                                    int[] extraContextBounds) {
        long startTime = System.currentTimeMillis();
        List<ChunkPos> centerChunks = new java.util.ArrayList<>(centerChunksInput);
        if (centerChunks.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        Logging.logger().info("[NmsTerrainGenerator] Start generating terrain with seed=" + seed + " for " + centerChunks.size() + " chunks.");

        CompletableFuture<GenerationResult> phase1Future = new CompletableFuture<>();
        Thread.ofVirtual().name("cr-terrain-gen").start(() -> {
            long startPhase1 = System.currentTimeMillis();
            try {
                if (isCancelled.getAsBoolean()) {
                    throw new java.util.concurrent.CancellationException("Terrain generation cancelled");
                }
                GenerationResult result = runAsyncInternal(
                    world, centerChunks, seed, onProgress, onStage, isCancelled, extraContextBounds);
                Logging.logger().info("[NmsTerrainGenerator] Phase 1 (Async generation) completed in " + (System.currentTimeMillis() - startPhase1) + " ms.");
                phase1Future.complete(result);
            } catch (Throwable t) {
                phase1Future.completeExceptionally(t);
            }
        });

        return phase1Future
            .thenCompose(result -> {
                if (isCancelled.getAsBoolean()) {
                    return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException("Terrain generation cancelled"));
                }
                return serializeOnRegionThread(result, world)
                    .thenRun(() -> {
                        completedCarverScope = result.generationScope();
                        Logging.logger().info("[NmsTerrainGenerator] Total generation & write completed in "
                            + (System.currentTimeMillis() - startTime) + " ms.");
                    });
            });
    }

    private static GenerationResult runAsyncInternal(World world, List<ChunkPos> centerChunks,
                                                     long seed, IntConsumer onProgress, Consumer<String> onStage,
                                                     java.util.function.BooleanSupplier isCancelled,
                                                     int[] extraContextBounds) {
        ServerLevel nmsLevel = ((CraftWorld) world).getHandle();
        int parallelism = getResolvedParallelism();

        int priority = Math.clamp(threadPoolConfig.priority(), 1, 10);
        boolean daemon = threadPoolConfig.daemon();
        boolean asyncMode = threadPoolConfig.asyncMode();

        var pool = getOrCreatePool(parallelism, priority, daemon, asyncMode);
        RegistryAccess reg = nmsLevel.registryAccess();

        long startPreparation = System.currentTimeMillis();
        var preparationJfr = TerrainJfr.beginPhase("preparation", centerChunks.size());
        GcSnapshot preparationGcStarted = gcSnapshot();
        var genCtx = GenerationContextResolver.resolve(nmsLevel, seed);
        final ChunkGenerator generator = genCtx.generator();
        final RandomState randomState = genCtx.randomState();
        final net.minecraft.world.level.biome.BiomeSource biomeSource = genCtx.biomeSource();

        var chunkSource = nmsLevel.getChunkSource();
        var chunkMap = chunkSource.chunkMap;
        // This state is constructed once by ServerChunkCache and has already generated/cached its
        // concentric-ring positions. Rebuilding it for every regen was the multi-second gap before
        // the first timed generation stage on Bukkit-wrapped worlds.
        final ChunkGeneratorStructureState structureState = chunkSource.getGeneratorState();
        long contextResolveMs = System.currentTimeMillis() - startPreparation;

        java.util.Set<ChunkPos> centerChunksSet = new java.util.HashSet<>(centerChunks);

        // A target contains FEATURES contributions originating in its 3x3 neighbourhood. Re-run
        // those source chunks in scratch memory, plus one clean writable ring around them. Only
        // centerChunks are serialized/applied, so the halo never changes live or on-disk neighbours.
        List<ChunkPos> featureSources = expandChunks(centerChunksSet, FEATURE_WRITE_RADIUS);
        java.util.Set<ChunkPos> featureSourceSet = new java.util.HashSet<>(featureSources);
        List<ChunkPos> cleanBaseChunks = expandChunks(featureSourceSet, FEATURE_WRITE_RADIUS);
        java.util.Set<ChunkPos> cleanBaseSet = new java.util.HashSet<>(cleanBaseChunks);

        int carverRad = Math.max(
            Math.max(getCarverRadius(), FEATURE_WRITE_RADIUS + FEATURE_CACHE_RADIUS),
            STRUCTURE_CONTEXT_RADIUS);
        boolean oldNoiseAround = centerChunks.stream().anyMatch(pos ->
            chunkMap.isOldChunkAround(pos, BlendingAnchorPolicy.OLD_NOISE_DISK_CONTEXT_RADIUS));
        int diskContextRadius =
            BlendingAnchorPolicy.requiredDiskContextRadius(carverRad, oldNoiseAround);

        // Never materialise the bounding rectangle of an irregular marked area. A long L-shaped
        // explored region can contain hundreds of thousands of unmarked coordinates inside that
        // rectangle. Worldgen dependencies are local, so the exact morphological dilation of the
        // target set is both sufficient and dramatically smaller.
        java.util.Set<ChunkPos> contextSet =
            new java.util.HashSet<>(expandChunks(centerChunksSet, diskContextRadius));

        if (extraContextBounds != null) {
            int targetMinX = centerChunks.stream().mapToInt(ChunkPos::x).min().orElseThrow();
            int targetMaxX = centerChunks.stream().mapToInt(ChunkPos::x).max().orElseThrow();
            int targetMinZ = centerChunks.stream().mapToInt(ChunkPos::z).min().orElseThrow();
            int targetMaxZ = centerChunks.stream().mapToInt(ChunkPos::z).max().orElseThrow();
            // Explicit structure context is exceptional and may be rectangular, but remains
            // independently clamped around the actual targets.
            int boundMinCX = Math.max(extraContextBounds[0], targetMinX - MAX_EXTRA_CONTEXT_RADIUS);
            int boundMaxCX = Math.min(extraContextBounds[1], targetMaxX + MAX_EXTRA_CONTEXT_RADIUS);
            int boundMinCZ = Math.max(extraContextBounds[2], targetMinZ - MAX_EXTRA_CONTEXT_RADIUS);
            int boundMaxCZ = Math.min(extraContextBounds[3], targetMaxZ + MAX_EXTRA_CONTEXT_RADIUS);
            for (int cx = boundMinCX; cx <= boundMaxCX; cx++) {
                for (int cz = boundMinCZ; cz <= boundMaxCZ; cz++) {
                    contextSet.add(new ChunkPos(cx, cz));
                }
            }
        }

        Map<ChunkPos, ProtoChunk> chunks = new ConcurrentHashMap<>();
        Map<ChunkPos, ProtoChunkHolder> chunkHolders = new ConcurrentHashMap<>();
        Map<RegionCacheKey, StaticCache2D<net.minecraft.server.level.GenerationChunkHolder>> regionCaches =
            new ConcurrentHashMap<>();
        java.util.concurrent.ConcurrentMap<Holder<Biome>, ProtoChunk> carverBiomeContexts =
            new ConcurrentHashMap<>();
        List<ChunkPos> contextPositions = contextSet.stream()
            .sorted(Comparator.comparingInt(ChunkPos::x).thenComparingInt(ChunkPos::z))
            .toList();

        java.util.Set<ChunkPos> carverCacheHits = ConcurrentHashMap.newKeySet();
        int carverCacheMisses = 0;
        int generatorIdentity = System.identityHashCode(generator);
        int randomStateIdentity = System.identityHashCode(randomState);
        GenerationScope generationScope = new GenerationScope(
            world.getUID(), seed, generatorIdentity, randomStateIdentity,
            centerChunks.stream().map(ChunkPos::pack).sorted().toList());
        boolean exactScopeReplay = generationScope.equals(completedCarverScope);
        boolean cacheAdmissionEnabled = centerChunks.size() == 1 || bulkSession != null;
        for (ChunkPos pos : cleanBaseChunks) {
            boolean targetChunk = centerChunksSet.contains(pos);
            if (!CarverStageCachePolicy.mayReuse(
                    cacheAdmissionEnabled, oldNoiseAround, exactScopeReplay, targetChunk)) continue;
            // A target chunk's on-disk structure starts/references are authoritative. Reusing a
            // CARVERS snapshot here can resurrect metadata produced by an earlier regeneration
            // (or by a different server/worldgen version), while the structure group still covers
            // the original bounding box. FEATURES would then place that stale/new layout only
            // inside the old target range, visibly cutting villages at its edge.
            //
            // Target snapshots are authoritative only for an immediately repeated identical scope.
            // Halo chunks remain context-free when no old-noise blending participates.
            CarverStageKey cacheKey = new CarverStageKey(
                world.getUID(), seed, generatorIdentity, randomStateIdentity, pos.pack(),
                oldNoiseAround || targetChunk ? generationScope : null);
            CompoundTag cached = carverStageCache.get(cacheKey);
            if (cached == null) {
                carverCacheMisses++;
                continue;
            }
            try {
                SerializableChunkData data = SerializableChunkData.parse(
                    nmsLevel, nmsLevel.palettedContainerFactory(), cached);
                ChunkAccess loaded = data == null ? null : data.read(
                    nmsLevel, nmsLevel.getPoiManager(), null, pos);
                if (!(loaded instanceof ProtoChunk proto)
                    || !proto.getPersistedStatus().isOrAfter(ChunkStatus.CARVERS)
                    || proto.getPersistedStatus().isOrAfter(ChunkStatus.FEATURES)) {
                    throw new IllegalStateException("snapshot is not exactly a pre-FEATURES terrain chunk");
                }
                chunks.put(pos, proto);
                carverCacheHits.add(pos);
            } catch (Throwable invalidSnapshot) {
                carverStageCache.remove(cacheKey);
                carverCacheMisses++;
                Logging.logger().warning("[NmsTerrainGenerator] Discarded invalid CARVERS cache entry for "
                    + pos + ": " + invalidSnapshot.getMessage());
            }
        }
        final int carverCacheMissCount = carverCacheMisses;
        final String carverCachePreparationSummary = oldNoiseAround && !exactScopeReplay
            ? "warming(old-noise scope)"
            : carverCacheHits.size() + " hit/" + carverCacheMissCount + " miss";

        for (ChunkPos pos : centerChunks) {
            // Only target chunks definitely need a fresh ProtoChunk. Neighbours are loaded first
            // and allocated lazily only when no usable chunk exists on disk.
            chunks.putIfAbsent(pos, newProtoChunk(pos, nmsLevel));
        }

        int totalOuter   = contextPositions.size();
        int centerTotal  = centerChunks.size();
        int totalWeight = totalOuter * W_OUTER
                       + centerTotal * (W_CARVER + W_DECORATION + W_SERIALIZE + W_LIGHT);

        AtomicInteger accumulated = new AtomicInteger(0);
        AtomicInteger lastPercent  = new AtomicInteger(0);

        IntConsumer addWeight = weight -> {
            int current = accumulated.addAndGet(weight);
            int percent = (int) ((long) current * 100 / totalWeight);
            if (percent > 99) percent = 99;
            int last = lastPercent.get();
            while (percent > last) {
                if (lastPercent.compareAndSet(last, percent)) {
                    onProgress.accept(percent);
                    break;
                }
                last = lastPercent.get();
            }
        };

        onStage.accept("structures");
        var structMgr = nmsLevel.structureManager();
        var structTemplateManager = nmsLevel.getStructureManager();
        var dimension = nmsLevel.dimension();

        java.util.Set<ChunkPos> diskLoaded = ConcurrentHashMap.newKeySet();
        java.util.Set<ChunkPos> savedBiomesLoaded = ConcurrentHashMap.newKeySet();
        java.util.Set<ChunkPos> savedBlendingDataLoaded = ConcurrentHashMap.newKeySet();
        java.util.Set<ChunkPos> savedRetrogenLoaded = ConcurrentHashMap.newKeySet();
        Map<ChunkPos, ProtoChunk> originalBlendingChunks = new ConcurrentHashMap<>();
        // Read-only context may retain real decorated terrain. The clean base set is rebuilt through
        // CARVERS in scratch memory while preserving its persisted structure starts/references.
        java.util.Set<ChunkPos> fullTerrainLoaded = ConcurrentHashMap.newKeySet();

        // Read context chunks through the server's chunk I/O path. This is slower than opening
        // RegionFile handles directly, but it is the only path coordinated with a live server's
        // concurrent region writes and guarantees that the returned NBT belongs to this position.
        List<CompletableFuture<Void>> contextReadFutures = new ArrayList<>();
        long contextReadStarted = System.nanoTime();
        var contextDecodeWork = new java.util.concurrent.atomic.LongAdder();
        for (ChunkPos pos : contextPositions) {
            // Read targets too. We discard their blocks below, but preserve their saved structure
            // starts and references so regeneration reproduces the structure that owns the marked
            // range instead of generating a potentially different layout and clipping it to that
            // old range.
            if (carverCacheHits.contains(pos)) continue;
            if (isCancelled.getAsBoolean()) {
                throw new java.util.concurrent.CancellationException("Task cancelled");
            }
            CONTEXT_READ_SLOTS.acquireUninterruptibly();
            BulkGenerationSession session = bulkSession;
            CompletableFuture<java.util.Optional<CompoundTag>> nbtRead = session == null
                ? chunkMap.read(pos)
                : session.read(world.getUID(), pos, () -> chunkMap.read(pos));
            CompletableFuture<Void> readFuture = nbtRead.thenAcceptAsync(nbtOpt -> {
                long decodeStarted = System.nanoTime();
                try {
                    if (nbtOpt.isEmpty()) return;
                    // upgradeChunkTag may rewrite its input. Session entries are shared by several
                    // tiles, so every consumer gets an independent mutable copy.
                    CompoundTag rawNbt = nbtOpt.get().copy();
                    int dataVersion = NbtUtils.getDataVersion(rawNbt);
                    CompoundTag upgradedNbt;
                    if (dataVersion == net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version()) {
                        upgradedNbt = rawNbt;
                    } else {
                        upgradedNbt = chunkMap.upgradeChunkTag(rawNbt);
                    }
                    SerializableChunkData data = SerializableChunkData.parse(
                        nmsLevel, nmsLevel.palettedContainerFactory(), upgradedNbt);
                    if (data != null) {
                        ChunkAccess loaded = data.read(
                            nmsLevel,
                            nmsLevel.getPoiManager(),
                            null, // regionInfo
                            pos
                        );
                        if (loaded instanceof ProtoChunk loadedProto) {
                            if (cleanBaseSet.contains(pos)) {
                                boolean historicalContext =
                                    BlendingAnchorPolicy.preserveHistoricalTerrainMetadata(
                                        centerChunksSet.contains(pos));
                                ProtoChunk pc = historicalContext
                                    ? new ProtoChunk(
                                        pos,
                                        loadedProto.getUpgradeData().copy(),
                                        nmsLevel,
                                        nmsLevel.palettedContainerFactory(),
                                        loadedProto.getBlendingData()
                                    )
                                    : newProtoChunk(pos, nmsLevel);
                                if (historicalContext) {
                                    pc.setBelowZeroRetrogen(loadedProto.getBelowZeroRetrogen());
                                    if (loadedProto.getBlendingData() != null) {
                                        savedBlendingDataLoaded.add(pos);
                                        originalBlendingChunks.put(pos, loadedProto);
                                    }
                                    if (loadedProto.getBelowZeroRetrogen() != null) {
                                        savedRetrogenLoaded.add(pos);
                                    }
                                }
                                pc.setAllStarts(loadedProto.getAllStarts());
                                pc.setAllReferences(loadedProto.getAllReferences());
                                if (historicalContext
                                    && loadedProto.getPersistedStatus().isOrAfter(ChunkStatus.BIOMES)) {
                                    copyBiomePalettes(loadedProto, pc);
                                    savedBiomesLoaded.add(pos);
                                }
                                pc.setPersistedStatus(ChunkStatus.STRUCTURE_REFERENCES);
                                chunks.put(pos, pc);
                            } else if (loadedProto.getPersistedStatus().isOrAfter(ChunkStatus.FEATURES)) {
                                chunks.put(pos, loadedProto);
                                fullTerrainLoaded.add(pos);
                            } else {
                                ProtoChunk pc = newProtoChunk(pos, nmsLevel);
                                pc.setAllStarts(loadedProto.getAllStarts());
                                pc.setAllReferences(loadedProto.getAllReferences());
                                pc.setPersistedStatus(ChunkStatus.STRUCTURE_REFERENCES);
                                chunks.put(pos, pc);
                            }
                            diskLoaded.add(pos);
                        }
                    }
                } catch (Exception ignored) {
                    // Fallback to normal structure generation if read/parse fails
                } finally {
                    contextDecodeWork.add(System.nanoTime() - decodeStarted);
                }
            }, pool).exceptionally(ex -> null).whenComplete((ignored, ex) -> CONTEXT_READ_SLOTS.release());
            contextReadFutures.add(readFuture);
        }
        CompletableFuture.allOf(contextReadFutures.toArray(new CompletableFuture[0])).join();
        long contextReadWallMs = nanosToMillis(System.nanoTime() - contextReadStarted);

        // Missing, unreadable or absent neighbours must still exist in the synthetic generation
        // region. Allocate only those fallbacks after every disk read has had a chance to succeed.
        for (ChunkPos pos : contextPositions) {
            chunks.computeIfAbsent(pos, p -> newProtoChunk(p, nmsLevel));
        }
        List<ProtoChunk> chunksToGen = new ArrayList<>(contextPositions.stream().map(chunks::get).toList());
        long preparationMs = System.currentTimeMillis() - startPreparation;
        Logging.logger().info("[NmsTerrainGenerator] Phase 1 - Preparation completed in " + preparationMs
            + " ms (context resolve/state=" + contextResolveMs + " ms, context=" + contextPositions.size()
            + ", disk-loaded=" + diskLoaded.size() + ", carvers-cache="
            + carverCachePreparationSummary + ", saved-biomes=" + savedBiomesLoaded.size()
            + ", blending-data=" + savedBlendingDataLoaded.size()
            + ", retrogen=" + savedRetrogenLoaded.size()
            + ", context-read=" + contextReadWallMs + " ms wall/"
            + nanosToMillis(contextDecodeWork) + " ms decode-worker across "
            + contextReadFutures.size() + " read(s)).");
        GcSnapshot preparationGcFinished = gcSnapshot();
        TerrainJfr.endPhase(preparationJfr, contextReadFutures.size(), contextDecodeWork.sum(),
            preparationGcFinished.collections() - preparationGcStarted.collections(),
            preparationGcFinished.millis() - preparationGcStarted.millis(), true);

        java.util.Set<ChunkPos> structureStartsToRefresh =
            collectStructureStartsToRefresh(centerChunksSet, featureSources, chunks);

        List<ProtoChunk> needsStructures = chunksToGen.stream()
            .filter(pc -> structureStartsToRefresh.contains(pc.getPos())
                || (!diskLoaded.contains(pc.getPos()) && !carverCacheHits.contains(pc.getPos())))
            .toList();

        long startStructures = System.currentTimeMillis();
        GcSnapshot structureGcStarted = gcSnapshot();
        var structureJfr = TerrainJfr.beginPhase("structure-starts", centerChunks.size());
        var structureWork = new java.util.concurrent.atomic.LongAdder();
        var structureTimings = new java.util.concurrent.ConcurrentLinkedQueue<ChunkTiming>();
        var structureCacheHits = new java.util.concurrent.atomic.LongAdder();
        var structureCacheMisses = new java.util.concurrent.atomic.LongAdder();
        var structureRegistry = reg.lookupOrThrow(Registries.STRUCTURE);
        boolean structureCacheEnabled = generator instanceof net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
        runBatch(needsStructures, pc -> {
            long structureStarted = System.nanoTime();
            long structureCpuStarted = currentThreadCpuNanos();
            String timingDetail = centerChunksSet.contains(pc.getPos())
                ? "target"
                : structureStartsToRefresh.contains(pc.getPos()) ? "referenced-start" : "missing-context";
            var jfrEvent = TerrainJfr.beginTask(
                "STRUCTURE_STARTS", pc.getPos().x(), pc.getPos().z(), timingDetail);
            boolean succeeded = false;
            String structureResult = "";
            try {
                ChunkStatus previousStatus = pc.getPersistedStatus();
                StructureStartCacheKey structureCacheKey = new StructureStartCacheKey(
                    world.getUID(), dimension, seed, System.identityHashCode(generator),
                    System.identityHashCode(structureState), System.identityHashCode(structTemplateManager),
                    pc.getPos().pack());
                if (structureStartsToRefresh.contains(pc.getPos())) {
                    pc.setAllStarts(new HashMap<>());
                    pc.setAllReferences(new HashMap<>());
                }
                boolean structureCacheHit = false;
                if (structureCacheEnabled) {
                    CachedStructureStarts cached = structureStartCache.get(structureCacheKey);
                    if (cached != null) {
                        try {
                            pc.setAllStarts(restoreStructureStarts(
                                cached, nmsLevel, seed, pc.getPos()));
                            pc.setAllReferences(new HashMap<>());
                            structureCacheHits.increment();
                            structureCacheHit = true;
                        } catch (RuntimeException cacheFailure) {
                            structureStartCache.remove(structureCacheKey);
                        }
                    }
                    if (!structureCacheHit) structureCacheMisses.increment();
                }
                if (!structureCacheHit) {
                    generator.createStructures(reg, structureState, structMgr, pc, structTemplateManager, dimension);
                    if (structureCacheEnabled) {
                        structureStartCache.put(structureCacheKey,
                            snapshotStructureStarts(pc, nmsLevel));
                    }
                }
                structureResult = "cache=" + (structureCacheEnabled
                    ? structureCacheHit ? "hit" : "miss" : "disabled") + "; "
                    + summarizeStructureStarts(pc, structureRegistry);
                if (!previousStatus.isOrAfter(ChunkStatus.STRUCTURE_STARTS)) {
                    pc.setPersistedStatus(ChunkStatus.STRUCTURE_STARTS);
                }
                succeeded = true;
            } finally {
                long elapsed = System.nanoTime() - structureStarted;
                long cpuElapsed = elapsedThreadCpuNanos(structureCpuStarted);
                structureWork.add(elapsed);
                String completedDetail = structureResult.isEmpty()
                    ? timingDetail : timingDetail + "; " + structureResult;
                structureTimings.add(new ChunkTiming(pc.getPos(), elapsed, cpuElapsed, completedDetail));
                TerrainJfr.endTask(jfrEvent, cpuElapsed, succeeded, structureResult);
            }
        }, pool, isCancelled);

        {
            int chunksWithStarts = 0, totalStarts = 0;
            StringBuilder structLog = new StringBuilder();
            for (ProtoChunk pc : chunksToGen) {
                var starts = pc.getAllStarts();
                if (starts.isEmpty()) continue;
                chunksWithStarts++;
                totalStarts += starts.size();
                for (var entry : starts.entrySet()) {
                    if (!entry.getValue().isValid()) continue;
                    var key = structureRegistry.getKey(entry.getKey());
                    structLog.append("\n  ").append(key)
                             .append(" @ chunk (").append(pc.getPos().x()).append(", ").append(pc.getPos().z()).append(")");
                }
            }
            Logging.logger().info("[NmsTerrainGenerator] createStructures: " + chunksWithStarts
                + " chunk(s) received " + totalStarts + " structure start(s) total" + structLog);
        }
        Logging.logger().info("[NmsTerrainGenerator] Phase 1 - Step 7a (STRUCTURE_STARTS) completed in "
            + (System.currentTimeMillis() - startStructures) + " ms wall (worker-ms="
            + nanosToMillis(structureWork) + ", tasks=" + needsStructures.size()
            + ", latency=" + timingDistribution(structureTimings)
            + ", structure-start-cache=" + structureCacheHits.sum() + " hit/"
            + structureCacheMisses.sum() + " miss/" + structureStartCache.size() + " retained"
            + ", " + gcDelta(structureGcStarted, gcSnapshot())
            + ", context disk-loaded=" + diskLoaded.size() + ").");
        GcSnapshot structureGcFinished = gcSnapshot();
        TerrainJfr.endPhase(structureJfr, needsStructures.size(), structureWork.sum(),
            structureGcFinished.collections() - structureGcStarted.collections(),
            structureGcFinished.millis() - structureGcStarted.millis(), true);
        logSlowChunkTimings("createStructures", structureTimings, 5, 50);

        long startReferences = System.currentTimeMillis();
        var refStep = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.STRUCTURE_REFERENCES);
        // Only chunks used as FEATURES sources need their own reference map. createReferences
        // scans the wider context for structure starts; running it for every distant context-only
        // chunk was pure O(context * 17²) work on large structure bounds.
        List<ProtoChunk> needsReferences = featureSources.stream()
            .map(chunks::get)
            .filter(java.util.Objects::nonNull)
            .toList();

        runBatch(needsReferences, pc -> {
            ChunkStatus previousStatus = pc.getPersistedStatus();
            pc.setAllReferences(new HashMap<>());
            var region = new SeededWorldGenRegion(
                nmsLevel,
                buildCache(pc.getPos(), 1, chunks, chunkHolders, regionCaches, nmsLevel, biomeSource, randomState),
                refStep, pc,
                seed, biomeSource, randomState, chunks, carverBiomeContexts);
            generator.createReferences(
                region,
                nmsLevel.structureManager().forWorldGenRegion(region),
                pc
            );
            if (!previousStatus.isOrAfter(ChunkStatus.STRUCTURE_REFERENCES)) {
                pc.setPersistedStatus(ChunkStatus.STRUCTURE_REFERENCES);
            }
        }, pool, isCancelled);
        Logging.logger().info("[NmsTerrainGenerator] Phase 1 - Step 3b (STRUCTURE_REFERENCES) completed in " + (System.currentTimeMillis() - startReferences) + " ms.");

        onStage.accept("terrain_pipeline");
        long pipelineStarted = System.currentTimeMillis();
        GcSnapshot pipelineGcStarted = gcSnapshot();
        var pipelineJfr = TerrainJfr.beginPhase("terrain-dag", centerChunks.size());
        var biomesStep = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.BIOMES);
        var noiseStep = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.NOISE);
        var surfaceStep = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.SURFACE);
        var carverStep = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.CARVERS);
        var featureStep = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES);

        var biomeWork = new java.util.concurrent.atomic.LongAdder();
        var noiseWork = new java.util.concurrent.atomic.LongAdder();
        var surfaceWork = new java.util.concurrent.atomic.LongAdder();
        var surfaceCacheWork = new java.util.concurrent.atomic.LongAdder();
        var surfaceGeneratorWork = new java.util.concurrent.atomic.LongAdder();
        var surfaceGeneratorTimings = new java.util.concurrent.ConcurrentLinkedQueue<ChunkTiming>();
        var carverWork = new java.util.concurrent.atomic.LongAdder();
        var featureWork = new java.util.concurrent.atomic.LongAdder();
        Map<ChunkPos, CompletableFuture<Void>> biomeFutures = new HashMap<>();
        Map<ChunkPos, CompletableFuture<Void>> noiseFutures = new HashMap<>();
        Map<ChunkPos, CompletableFuture<Void>> surfaceFutures = new HashMap<>();
        Map<ChunkPos, CompletableFuture<Void>> carverFutures = new HashMap<>();
        CompletableFuture<Void> ready = CompletableFuture.completedFuture(null);

        // NOISE/SURFACE/CARVERS are only required for the clean writable base. BIOMES needs one
        // additional ring because noise and surface biome sampling can cross a quart boundary.
        // Distant structure context remains at STRUCTURE_STARTS/REFERENCES and is still visible to
        // StructureManager during FEATURES without generating irrelevant terrain for it.
        java.util.Set<ChunkPos> biomeWorkSet = new java.util.HashSet<>(expandChunks(cleanBaseSet, 1));

        Map<ChunkPos, ProtoChunk> originalBlendingView = new ConcurrentHashMap<>(chunks);
        originalBlendingView.putAll(originalBlendingChunks);
        Map<ChunkPos, Blender> blendersByPosition = new HashMap<>();
        int activeBlenderCount = 0;
        List<ChunkPos> blenderPositions = biomeWorkSet.stream()
            .sorted(Comparator.comparingInt(ChunkPos::x).thenComparingInt(ChunkPos::z))
            .toList();
        for (ChunkPos pos : blenderPositions) {
            ProtoChunk center = chunks.get(pos);
            if (center == null) continue;
            var blendingRegion = new SeededWorldGenRegion(
                nmsLevel,
                buildCache(pos, 1, chunks, chunkHolders, regionCaches, nmsLevel, biomeSource, randomState),
                biomesStep, center, seed, biomeSource, randomState,
                originalBlendingView, carverBiomeContexts
            );
            Blender blender = Blender.of(blendingRegion);
            blendersByPosition.put(pos, blender);
            if (!blender.isEmpty()) activeBlenderCount++;
        }
        Logging.logger().info("[NmsTerrainGenerator] Prepared " + blendersByPosition.size()
            + " Blender snapshot(s) from " + originalBlendingChunks.size()
            + " original old-noise chunk(s); " + activeBlenderCount + " snapshot(s) active.");
        originalBlendingView.clear();
        originalBlendingChunks.clear();
        if (extraContextBounds == null) {
            trimReadOnlyContext(featureSourceSet, FEATURE_CACHE_RADIUS, chunks, chunkHolders,
                chunksToGen, diskLoaded, fullTerrainLoaded);
        }

        BulkGenerationSession activeBulkSession = bulkSession;
        // Old-noise scopes are written on the first pass but read only by an exact consecutive
        // replay. FEATURES is intentionally absent from the snapshot and always runs again.
        boolean carverCacheEnabled = cacheAdmissionEnabled;
        boolean retainAllCarverStages = centerChunks.size() == 1
            || (activeBulkSession != null && activeBulkSession.retainAllCarverStages);
        int targetMinX = centerChunks.stream().mapToInt(ChunkPos::x).min().orElse(0);
        int targetMaxX = centerChunks.stream().mapToInt(ChunkPos::x).max().orElse(0);
        int targetMinZ = centerChunks.stream().mapToInt(ChunkPos::z).min().orElse(0);
        int targetMaxZ = centerChunks.stream().mapToInt(ChunkPos::z).max().orElse(0);
        AtomicInteger carverCacheStores = new AtomicInteger();
        var carverCacheWriteWarning = new java.util.concurrent.atomic.AtomicBoolean();
        var carverCacheWriteWork = new java.util.concurrent.atomic.LongAdder();
        List<ChunkPos> carverCacheStoreCandidates = cleanBaseChunks.stream()
            .filter(pos -> !carverCacheHits.contains(pos))
            .filter(pos -> CarverStageCachePolicy.shouldRetain(
                carverCacheEnabled, retainAllCarverStages, centerChunksSet.contains(pos),
                pos.x(), pos.z(), targetMinX, targetMaxX, targetMinZ, targetMaxZ))
            .toList();

        // Build the same Paper status dependencies as a per-chunk DAG. This lets a chunk enter
        // NOISE/SURFACE/CARVERS as soon as its own prerequisites are ready instead of waiting for
        // the slowest chunk in every whole-batch barrier.
        for (ProtoChunk pc : chunksToGen) {
            ChunkPos pos = pc.getPos();
            if (!biomeWorkSet.contains(pos)
                || fullTerrainLoaded.contains(pos) || carverCacheHits.contains(pos)) {
                biomeFutures.put(pos, ready);
            } else if (savedBiomesLoaded.contains(pos)) {
                pc.setPersistedStatus(ChunkStatus.BIOMES);
                biomeFutures.put(pos, ready);
            } else {
                biomeFutures.put(pos, stageFuture(ready, pool, isCancelled, biomeWork, () -> {
                    var region = new SeededWorldGenRegion(nmsLevel,
                        buildCache(pos, 1, chunks, chunkHolders, regionCaches, nmsLevel, biomeSource, randomState),
                        biomesStep, pc, seed, biomeSource, randomState, chunks, carverBiomeContexts);
                    generator.createBiomes(
                        randomState,
                        blendersByPosition.getOrDefault(pos, Blender.empty()),
                        nmsLevel.structureManager().forWorldGenRegion(region),
                        pc
                    ).join();
                    pc.setPersistedStatus(ChunkStatus.BIOMES);
                }));
            }
        }

        java.util.Set<ChunkPos> cleanBaseSetActual = new java.util.HashSet<>();
        for (ChunkPos pos : cleanBaseChunks) {
            ProtoChunk pc = chunks.get(pos);
            if (pc == null || fullTerrainLoaded.contains(pos) || carverCacheHits.contains(pos)
                || !cleanBaseSetActual.add(pos)) continue;
            // NOISE/SURFACE can ask BiomeManager for quart positions just across a chunk edge.
            // Waiting only for the center BIOMES task made large graphs nondeterministically race:
            // a neighbour existed in the map but its biome palette was still uninitialised.
            CompletableFuture<Void> dependency = neighborhoodBarrier(biomeFutures, pos, 1, ready);
            noiseFutures.put(pos, stageFuture(dependency, pool, isCancelled, noiseWork, () -> {
                var region = new SeededWorldGenRegion(nmsLevel,
                    buildCache(pos, 1, chunks, chunkHolders, regionCaches, nmsLevel, biomeSource, randomState),
                    noiseStep, pc, seed, biomeSource, randomState, chunks, carverBiomeContexts);
                generator.fillFromNoise(
                    blendersByPosition.getOrDefault(pos, Blender.empty()),
                    randomState,
                    nmsLevel.structureManager().forWorldGenRegion(region),
                    pc
                ).join();
                BelowZeroRetrogen belowZeroRetrogen = pc.getBelowZeroRetrogen();
                if (belowZeroRetrogen != null) {
                    BelowZeroRetrogen.replaceOldBedrock(pc);
                    if (belowZeroRetrogen.hasBedrockHoles()) {
                        belowZeroRetrogen.applyBedrockMask(pc);
                    }
                }
                pc.setPersistedStatus(ChunkStatus.NOISE);
                addWeight.accept(W_OUTER);
            }));
        }

        for (ProtoChunk pc : chunksToGen) {
            ChunkPos pos = pc.getPos();
            if (!cleanBaseSet.contains(pos)
                || fullTerrainLoaded.contains(pos) || carverCacheHits.contains(pos)) continue;
            CompletableFuture<Void> dependency = noiseFutures.get(pos);
            if (dependency == null) {
                dependency = stageFuture(neighborhoodBarrier(biomeFutures, pos, 1, ready), pool, isCancelled,
                    noiseWork, () -> {
                        pc.setPersistedStatus(ChunkStatus.NOISE);
                        addWeight.accept(W_OUTER);
                    });
            }
            CompletableFuture<Void> surfaceDependency = dependency;
            surfaceFutures.put(pos, stageFuture(surfaceDependency, pool, isCancelled, surfaceWork, () -> {
                SeededWorldGenRegion region;
                long cacheStarted = System.nanoTime();
                try {
                    region = new SeededWorldGenRegion(nmsLevel,
                        buildCache(pos, 1, chunks, chunkHolders, regionCaches, nmsLevel, biomeSource, randomState),
                        surfaceStep, pc, seed, biomeSource, randomState, chunks, carverBiomeContexts);
                } finally {
                    surfaceCacheWork.add(System.nanoTime() - cacheStarted);
                }
                long generatorStarted = System.nanoTime();
                long generatorCpuStarted = currentThreadCpuNanos();
                var jfrEvent = TerrainJfr.beginTask("SURFACE", pos.x(), pos.z(), "");
                boolean succeeded = false;
                try {
                    generator.buildSurface(
                        region,
                        nmsLevel.structureManager().forWorldGenRegion(region),
                        randomState,
                        pc
                    );
                    succeeded = true;
                } finally {
                    long elapsed = System.nanoTime() - generatorStarted;
                    long cpuElapsed = elapsedThreadCpuNanos(generatorCpuStarted);
                    surfaceGeneratorWork.add(elapsed);
                    surfaceGeneratorTimings.add(new ChunkTiming(pos, elapsed, cpuElapsed, ""));
                    TerrainJfr.endTask(jfrEvent, cpuElapsed, succeeded);
                }
                pc.setPersistedStatus(ChunkStatus.SURFACE);
            }));
        }

        for (ChunkPos pos : cleanBaseChunks) {
            ProtoChunk pc = chunks.get(pos);
            if (pc == null) continue;
            if (carverCacheHits.contains(pos)) {
                carverFutures.put(pos, ready);
                continue;
            }
            CompletableFuture<Void> dependency = surfaceFutures.getOrDefault(pos,
                noiseFutures.getOrDefault(pos, biomeFutures.getOrDefault(pos, ready)));
            carverFutures.put(pos, stageFuture(dependency, pool, isCancelled, carverWork, () -> {
                var region = new SeededWorldGenRegion(nmsLevel,
                    buildCache(pos, 0, chunks, chunkHolders, regionCaches, nmsLevel, biomeSource, randomState),
                    carverStep, pc, seed, biomeSource, randomState, chunks, carverBiomeContexts);
                Blender.addAroundOldChunksCarvingMaskFilter(region, pc);
                generator.applyCarvers(
                    region,
                    seed,
                    randomState,
                    nmsLevel.getBiomeManager(),
                    nmsLevel.structureManager().forWorldGenRegion(region),
                    pc
                );
                pc.setPersistedStatus(ChunkStatus.CARVERS);
                if (centerChunksSet.contains(pos)) addWeight.accept(W_CARVER);
            }));
        }

        // SerializableChunkData reads Folia's region-local game time. Snapshotting on a generation
        // worker leaves Level#getCurrentWorldData() null, so batch the copies onto the owning region
        // thread. FEATURES futures are deliberately created only after this completes, guaranteeing
        // these CARVERS snapshots cannot observe decoration writes or generated loot/entities.
        if (!carverCacheStoreCandidates.isEmpty() && !isCancelled.getAsBoolean()) {
            CompletableFuture<?>[] cacheDependencies = carverCacheStoreCandidates.stream()
                .map(carverFutures::get)
                .filter(java.util.Objects::nonNull)
                .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(cacheDependencies).join();

            CompletableFuture<Void> snapshotFuture = new CompletableFuture<>();
            ChunkPos cacheAnchor = centerChunks.getFirst();
            Location cacheLocation = new Location(
                world, cacheAnchor.x() * 16 + 8, 64, cacheAnchor.z() * 16 + 8);
            try {
                Scheduler.runTask(plugin, () -> {
                    long cacheWriteStarted = System.nanoTime();
                    try {
                        for (ChunkPos pos : carverCacheStoreCandidates) {
                            ProtoChunk pc = chunks.get(pos);
                            if (pc == null || !pc.getPersistedStatus().isOrAfter(ChunkStatus.CARVERS)
                                || pc.getPersistedStatus().isOrAfter(ChunkStatus.FEATURES)) continue;
                            try {
                                CompoundTag snapshot = SerializableChunkData.copyOf(nmsLevel, pc).write();
                                CarverStageKey cacheKey = new CarverStageKey(
                                    world.getUID(), seed, generatorIdentity, randomStateIdentity, pos.pack(),
                                    oldNoiseAround || centerChunksSet.contains(pos) ? generationScope : null);
                                carverStageCache.put(cacheKey, snapshot);
                                carverCacheStores.incrementAndGet();
                            } catch (Throwable cacheFailure) {
                                if (carverCacheWriteWarning.compareAndSet(false, true)) {
                                    Logging.logger().warning("[NmsTerrainGenerator] CARVERS cache snapshot failed; "
                                        + "generation will continue without that entry: "
                                        + cacheFailure.getClass().getSimpleName() + ": "
                                        + String.valueOf(cacheFailure.getMessage()));
                                }
                            }
                        }
                    } finally {
                        carverCacheWriteWork.add(System.nanoTime() - cacheWriteStarted);
                        snapshotFuture.complete(null);
                    }
                }, cacheLocation);
                snapshotFuture.join();
            } catch (Throwable schedulingFailure) {
                if (carverCacheWriteWarning.compareAndSet(false, true)) {
                    Logging.logger().warning("[NmsTerrainGenerator] Could not schedule CARVERS cache snapshot: "
                        + schedulingFailure.getClass().getSimpleName() + ": "
                        + String.valueOf(schedulingFailure.getMessage()));
                }
            }
        }

        List<ChunkPos> decorationChunks = new ArrayList<>(featureSources);
        decorationChunks.sort(Comparator.comparingInt(ChunkPos::x).thenComparingInt(ChunkPos::z));
        CompletableFuture<Void> stripeBarrier = ready;
        for (int strideX = 0; strideX < DECORATION_STRIDE; strideX++) {
            for (int strideZ = 0; strideZ < DECORATION_STRIDE; strideZ++) {
                final int sx = strideX, sz = strideZ;
                List<CompletableFuture<Void>> stripeFutures = new ArrayList<>();
                for (ChunkPos pos : decorationChunks) {
                    if (Math.floorMod(pos.x(), DECORATION_STRIDE) != sx
                        || Math.floorMod(pos.z(), DECORATION_STRIDE) != sz) continue;
                    List<CompletableFuture<Void>> dependencies = new ArrayList<>();
                    dependencies.add(stripeBarrier);
                    // Paper declares CARVERS radius one, while some structure features perform
                    // legitimate radius-two reads. Await every scratch carver in that read cache
                    // so FEATURES never races a neighbouring chunk still mutating its sections.
                    for (int dx = -FEATURE_CACHE_RADIUS; dx <= FEATURE_CACHE_RADIUS; dx++) {
                        for (int dz = -FEATURE_CACHE_RADIUS; dz <= FEATURE_CACHE_RADIUS; dz++) {
                            CompletableFuture<Void> carver = carverFutures.get(new ChunkPos(pos.x() + dx, pos.z() + dz));
                            if (carver != null) dependencies.add(carver);
                        }
                    }
                    CompletableFuture<Void> dependency = CompletableFuture.allOf(dependencies.toArray(CompletableFuture[]::new));
                    ProtoChunk pc = chunks.get(pos);
                    stripeFutures.add(stageFuture(dependency, pool, isCancelled, featureWork, () -> {
                        Heightmap.primeHeightmaps(pc, java.util.EnumSet.of(
                            Heightmap.Types.MOTION_BLOCKING,
                            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                            Heightmap.Types.OCEAN_FLOOR,
                            Heightmap.Types.WORLD_SURFACE
                        ));
                        var region = new SeededWorldGenRegion(nmsLevel,
                            buildCache(pos, FEATURE_CACHE_RADIUS, chunks, chunkHolders, regionCaches, nmsLevel, biomeSource, randomState),
                            featureStep, pc, seed, biomeSource, randomState, chunks, carverBiomeContexts);
                        generator.applyBiomeDecoration(region, pc,
                            nmsLevel.structureManager().forWorldGenRegion(region));
                        Blender.generateBorderTicks(region, pc);
                        pc.setPersistedStatus(ChunkStatus.FEATURES);
                        if (centerChunksSet.contains(pos)) addWeight.accept(W_DECORATION);
                    }));
                }
                if (!stripeFutures.isEmpty()) {
                    stripeBarrier = CompletableFuture.allOf(stripeFutures.toArray(CompletableFuture[]::new));
                }
            }
        }
        stripeBarrier.join();
        GcSnapshot pipelineGcFinished = gcSnapshot();
        TerrainJfr.endPhase(pipelineJfr, surfaceFutures.size(),
            biomeWork.sum() + noiseWork.sum() + surfaceWork.sum() + carverWork.sum()
                + featureWork.sum() + carverCacheWriteWork.sum(),
            pipelineGcFinished.collections() - pipelineGcStarted.collections(),
            pipelineGcFinished.millis() - pipelineGcStarted.millis(), true);
        Logging.logger().info("[NmsTerrainGenerator] Phase 1 terrain DAG completed in "
            + (System.currentTimeMillis() - pipelineStarted) + " ms (worker-ms: biomes="
            + nanosToMillis(biomeWork) + ", noise=" + nanosToMillis(noiseWork)
            + ", surface=" + nanosToMillis(surfaceWork) + " [tasks=" + surfaceFutures.size()
            + ", cache=" + nanosToMillis(surfaceCacheWork) + ", generator="
            + nanosToMillis(surfaceGeneratorWork) + ", latency="
            + timingDistribution(surfaceGeneratorTimings) + "], carvers=" + nanosToMillis(carverWork)
            + ", features=" + nanosToMillis(featureWork) + ", carvers-cache-write="
            + nanosToMillis(carverCacheWriteWork) + "; cache=" + carverCacheHits.size()
            + " hit/" + carverCacheStores.get() + " stored/" + carverStageCache.size() + " retained; "
            + gcDelta(pipelineGcStarted, gcSnapshot()) + ").");
        logSlowChunkTimings("buildSurface", surfaceGeneratorTimings, 5, 100);

        onStage.accept("heightmaps");
        long startHeightmaps = System.currentTimeMillis();
        java.util.Set<Heightmap.Types> heightmapTypes = java.util.EnumSet.of(
            Heightmap.Types.MOTION_BLOCKING,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            Heightmap.Types.OCEAN_FLOOR,
            Heightmap.Types.WORLD_SURFACE
        );
        for (ChunkPos pos : centerChunks) {
            ProtoChunk pc = chunks.get(pos);
            if (pc != null) {
                Heightmap.primeHeightmaps(pc, heightmapTypes);
            }
        }
        Logging.logger().info("[NmsTerrainGenerator] Phase 1 - Heightmaps primed in " + (System.currentTimeMillis() - startHeightmaps) + " ms.");

        onStage.accept("init_light");
        long startInitLight = System.currentTimeMillis();
        var lightEngine = nmsLevel.getChunkSource().getLightEngine();
        for (ChunkPos pos : centerChunks) {
            ProtoChunk pc = chunks.get(pos);
            LevelChunkSection[] sections = pc.getSections();
            int minSectionY = pc.getMinY() >> 4;
            for (int i = 0; i < sections.length; i++) {
                boolean isEmpty = sections[i] == null || sections[i].hasOnlyAir();
                lightEngine.updateSectionStatus(SectionPos.of(pos, minSectionY + i), isEmpty);
            }
        }
        Logging.logger().info("[NmsTerrainGenerator] Phase 1 - Step 9 (INITIALIZE_LIGHT) completed in " + (System.currentTimeMillis() - startInitLight) + " ms.");

        onStage.accept("light");
        long startLight = System.currentTimeMillis();
        try {
            if (lightEngine instanceof ca.spottedleaf.moonrise.patches.starlight.light.StarLightLightingProvider starlightProvider) {
                ca.spottedleaf.moonrise.patches.starlight.light.StarLightInterface starlightInterface =
                    starlightProvider.starlight$getLightEngine();
                for (ChunkPos pos : centerChunks) {
                    ProtoChunk pc = chunks.get(pos);
                    Boolean[] emptySections = ca.spottedleaf.moonrise.patches.starlight.light.StarLightEngine.getEmptySectionsForChunk(pc);
                    starlightInterface.lightChunk(pc, emptySections);
                }
            } else {
                var threadedLightEngine = (ThreadedLevelLightEngine) lightEngine;
                List<CompletableFuture<ChunkAccess>> lightFutures = new ArrayList<>(centerChunks.size());
                for (ChunkPos pos : centerChunks) {
                    lightFutures.add(threadedLightEngine.lightChunk(chunks.get(pos), false));
                }
                CompletableFuture.allOf(lightFutures.toArray(new CompletableFuture[0])).join();
            }
            centerChunks.forEach(pos -> chunks.get(pos).setLightCorrect(true));
            addWeight.accept(W_LIGHT * centerTotal);
        } catch (Exception e) {
            Logging.logger().severe("[NmsTerrainGenerator] LIGHT phase failed, chunks will be relit on first load", e);
        }
        Logging.logger().info("[NmsTerrainGenerator] Phase 1 - Step 10 (LIGHT) completed in " + (System.currentTimeMillis() - startLight) + " ms.");

        // Terrain/decoration/heightmaps/light are all done at this point — bump the persisted status
        // the rest of the pyramid (FULL) so the chunk reads back as "fully generated" on its very next
        // disk read. Without this, the ProtoChunk's status stays capped at FEATURES (the last status
        // step this pipeline actually runs), which is < the default scan.min-persisted-status (FULL):
        // any subsequent /cr mark fullmark|radiusmark or biome flood fill over this exact chunk would
        // treat it as "not generated yet" and skip it, even moments after regenerating it. The SPAWN
        // step (initial mob population) is intentionally never run by this pipeline either way, so
        // jumping straight to FULL doesn't skip anything this pipeline was actually going to do.
        for (ChunkPos pos : centerChunks) {
            ProtoChunk pc = chunks.get(pos);
            if (pc != null) {
                pc.setPersistedStatus(ChunkStatus.FULL);
            }
        }

        java.util.Set<ChunkPos> centerSet = new java.util.HashSet<>(centerChunks);
        List<ChunkPos> outerChunks = chunksToGen.stream()
            .map(ProtoChunk::getPos)
            .filter(pos -> !centerSet.contains(pos))
            .toList();

        return new GenerationResult(nmsLevel, centerChunks, outerChunks, chunks, addWeight, onProgress, onStage,
            seed, 0, 0, 0, 0, generationScope);
    }

    private static CompletableFuture<Void> serializeOnRegionThread(
            GenerationResult result, World world) {
        result.onStage().accept("serialize");
        long startNbt = System.currentTimeMillis();

        Map<ChunkPos, CompoundTag> serialized = new ConcurrentHashMap<>(
            result.centerChunks().size() + result.outerChunks().size());
        List<ChunkPos> voidChunks = java.util.Collections.synchronizedList(new ArrayList<>());
        Map<ChunkPos, String> chunkIssues = new ConcurrentHashMap<>();

        CompletableFuture<Map<ChunkPos, CompoundTag>> nbtFuture = new CompletableFuture<>();
        ChunkPos first = result.centerChunks().get(0);
        Location regionLoc = new Location(world, first.x() * 16 + 8, 64, first.z() * 16 + 8);
        Scheduler.runTask(plugin, () -> {
                for (ChunkPos pos : result.centerChunks()) {
                    try {
                        ProtoChunk pc = result.chunks().get(pos);
                        Holder<Biome> centerBiome = pc.getNoiseBiome(8 >> 2, 64 >> 2, 8 >> 2);
                        if (centerBiome.is(Biomes.THE_VOID)) voidChunks.add(pos);
                        materializePendingBlockEntities(result.level(), pc);
                        serialized.put(pos, SerializableChunkData.copyOf(result.level(), pc).write());
                        result.addWeight().accept(W_SERIALIZE);
                    } catch (Throwable t) {
                        chunkIssues.put(pos, t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
                        Logging.logger().severe("[NmsTerrainGenerator] Failed to serialize chunk " + pos, t);
                    }
                }

                int voidCount = voidChunks.size();
                Logging.logger().info("[NmsTerrainGenerator] Phase 2a (NBT Serialization) completed in " + (System.currentTimeMillis() - startNbt) + " ms.");
                Logging.logger().info("[NmsTerrainGenerator] Total chunks generated: " + result.centerChunks().size());
                if (voidCount > 0) Logging.logger().warning("[NmsTerrainGenerator] Void chunks: " + voidChunks);
                if (!chunkIssues.isEmpty()) {
                    Logging.logger().severe("[NmsTerrainGenerator] Serialization issues: " + chunkIssues);
                    nbtFuture.completeExceptionally(new IllegalStateException(
                        "Failed to serialize " + chunkIssues.size() + " of "
                            + result.centerChunks().size() + " regenerated chunk(s)"));
                    return;
                }
                nbtFuture.complete(serialized);
            }, regionLoc);

        CompletableFuture<Void> ioFuture = nbtFuture.thenAcceptAsync(serializedMap -> {
            long startIo = System.currentTimeMillis();
            var chunkSrc = result.level().getChunkSource();
            int written = 0;
            var entityDataController = result.level().moonrise$getEntityChunkDataController();
            var entityCache = entityDataController.getCache();
            for (ChunkPos pos : result.centerChunks()) {
                CompoundTag nbt = serializedMap.get(pos);
                if (nbt == null) continue;
                chunkSrc.chunkMap.write(pos, () -> nbt);
                BulkGenerationSession session = bulkSession;
                if (session != null) session.update(world.getUID(), pos, nbt);
                
                ProtoChunk pc = result.chunks().get(pos);
                if (pc != null) {
                    List<CompoundTag> protoEntities = pc.getEntities();
                    if (protoEntities.isEmpty()) {
                        try {
                            entityCache.write(pos, null);
                        } catch (java.io.IOException e) {
                            Logging.logger().warning("Failed to delete entities for chunk " + pos + ": " + e.getMessage());
                        }
                    } else {
                        ListTag entitiesList = new ListTag();
                        entitiesList.addAll(protoEntities);
                        CompoundTag entityNbt = NbtUtils.addCurrentDataVersion(new CompoundTag());
                        entityNbt.put("Entities", entitiesList);
                        entityNbt.store("Position", ChunkPos.CODEC, pos);
                        try {
                            entityCache.write(pos, entityNbt);
                        } catch (java.io.IOException e) {
                            Logging.logger().warning("Failed to write entities for chunk " + pos + ": " + e.getMessage());
                        }
                    }
                }
                
                written++;
            }
            // Moonrise synchronize(false) waits until every scheduled save has reached region
            // storage, but skips forcing every region file to stable storage for every tiny batch.
            // Normal server saves/shutdown still perform the durable flush. Using true here was an
            // avoidable fsync barrier thousands of times during a full-world regeneration.
            chunkSrc.chunkMap.synchronize(false).join();
            result.onProgress().accept(100);
            result.onStage().accept("done");
            Logging.logger().info("[NmsTerrainGenerator] Phase 2b (Disk Write) completed in " + (System.currentTimeMillis() - startIo) + " ms. Wrote " + written + " chunks.");
        }, ioExecutor);

        return ioFuture.thenCompose(ignored -> {
            long startApply = System.currentTimeMillis();
            List<ChunkPos> centerList = result.centerChunks();
            List<CompletableFuture<Void>> applyFutures = new ArrayList<>(centerList.size());
            AtomicInteger loadedChunks = new AtomicInteger();
            AtomicInteger refreshedChunks = new AtomicInteger();
            AtomicInteger changedSections = new AtomicInteger();
            AtomicInteger unchangedSections = new AtomicInteger();
            for (int i = 0; i < centerList.size(); i++) {
                ChunkPos pos = centerList.get(i);
                CompletableFuture<Void> fut = new CompletableFuture<>();
                applyFutures.add(fut);
                Location chunkLoc = new Location(world, pos.x() * 16 + 8, 64, pos.z() * 16 + 8);
                // Staggering by batch index (instead of firing every chunk's apply task in the same instant)
                // matters specifically on Folia: a region drains every tick-task already queued for it in a
                // single tick (see RegionizedTaskQueue.RegionTaskQueueData#drainTasks), so without this delay
                // a large structure regen — whose chunks usually share one region — dumps its whole Phase 2c
                // into one tick instead of spreading it out.
                long delayTicks = i / Math.max(1, applyBatchSize);
                Scheduler.runTaskLater(plugin, () -> {
                    try {
                        // The center chunk's disk data was already written correctly in Phase 2b regardless
                        // of whether anyone has it loaded. This step only exists so a chunk that's *already*
                        // loaded (a player nearby) updates live instead of needing a reload, and so any
                        // decoration-spawned entities appear immediately. If nobody has it loaded, skip —
                        // forcing a load (or, far from any player, a full fresh *generation*) here just to
                        // patch memory nobody is looking at is what was stalling the Folia region tick.
                        if (!world.isChunkLoaded(pos.x(), pos.z())) {
                            return; // finally below still completes fut
                        }

                        // Force load/retrieve live chunk on the regional thread
                        LevelChunk live = ((CraftWorld) world).getHandle().getChunk(pos.x(), pos.z());
                        ProtoChunk pc = result.chunks().get(pos);
                        if (pc != null && live != null) {
                            LiveApplyResult applyResult = applyToLoadedChunk(result.level(), pc, live);
                            loadedChunks.incrementAndGet();
                            changedSections.addAndGet(applyResult.changedSections());
                            unchangedSections.addAndGet(applyResult.unchangedSections());
                            if (applyResult.visualRefreshRequired()) {
                                world.refreshChunk(pos.x(), pos.z());
                                refreshedChunks.incrementAndGet();
                            }
                        }
                    } catch (Throwable t) {
                        Logging.logger().severe("Failed to apply terrain to chunk " + pos, t);
                    } finally {
                        fut.complete(null);
                    }
                }, delayTicks, chunkLoc);
            }
            return CompletableFuture.allOf(applyFutures.toArray(new CompletableFuture[0]))
                .thenRun(() -> Logging.logger().info(
                    "[NmsTerrainGenerator] Phase 2c (Differential live apply) completed in "
                        + (System.currentTimeMillis() - startApply) + " ms. Processed " + result.centerChunks().size()
                        + " chunks (loaded=" + loadedChunks.get() + ", refreshed=" + refreshedChunks.get()
                        + ", sections changed=" + changedSections.get() + ", unchanged=" + unchangedSections.get() + ")."));
        });
    }

    /** Releases full disk-loaded ProtoChunks that later stages cannot observe. */
    private static void trimReadOnlyContext(java.util.Set<ChunkPos> centers, int radius,
                                            Map<ChunkPos, ProtoChunk> chunks,
                                            Map<ChunkPos, ProtoChunkHolder> chunkHolders,
                                            List<ProtoChunk> chunksToGen,
                                            java.util.Set<ChunkPos> diskLoaded,
                                            java.util.Set<ChunkPos> fullTerrainLoaded) {
        java.util.Set<ChunkPos> keep = new java.util.HashSet<>();
        for (ChunkPos center : centers) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    keep.add(new ChunkPos(center.x() + dx, center.z() + dz));
                }
            }
        }
        int before = chunksToGen.size();
        chunks.keySet().removeIf(pos -> !keep.contains(pos));
        chunkHolders.keySet().removeIf(pos -> !keep.contains(pos));
        chunksToGen.removeIf(pc -> !keep.contains(pc.getPos()));
        diskLoaded.retainAll(keep);
        fullTerrainLoaded.retainAll(keep);
        Logging.logger().info("[NmsTerrainGenerator] Released " + (before - chunksToGen.size())
            + " distant read-only context chunks; retaining " + chunksToGen.size() + " for generation.");
    }

    private static List<ChunkPos> expandChunks(java.util.Collection<ChunkPos> chunks, int radius) {
        java.util.Set<ChunkPos> expanded = new java.util.HashSet<>();
        for (ChunkPos center : chunks) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    expanded.add(new ChunkPos(center.x() + dx, center.z() + dz));
                }
            }
        }
        return expanded.stream()
            .sorted(Comparator.comparingInt(ChunkPos::x).thenComparingInt(ChunkPos::z))
            .toList();
    }

    private static <T> void runBatch(List<T> items,
                                     Consumer<T> work,
                                     java.util.concurrent.Executor executor,
                                     java.util.function.BooleanSupplier isCancelled) {
        if (items.isEmpty()) return;

        int parallelism = 1;
        if (executor instanceof java.util.concurrent.ForkJoinPool fjp) {
            parallelism = fjp.getParallelism();
        } else {
            parallelism = Math.max(1, Runtime.getRuntime().availableProcessors());
        }

        int totalItems = items.size();
        int workerCount = Math.min(totalItems, Math.max(1, parallelism * 2));
        if (workerCount <= 1) {
            for (T item : items) {
                if (isCancelled.getAsBoolean()) {
                    throw new java.util.concurrent.CancellationException("Task cancelled");
                }
                work.accept(item);
            }
            return;
        }

        // Dynamic claiming avoids the tail where one static partition receives several expensive
        // structure/features chunks while the other generation workers become idle.
        AtomicInteger nextIndex = new AtomicInteger();
        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] futures = new CompletableFuture[workerCount];
        for (int i = 0; i < workerCount; i++) {
            futures[i] = CompletableFuture.runAsync(() -> {
                int itemIndex;
                while ((itemIndex = nextIndex.getAndIncrement()) < totalItems) {
                    if (isCancelled.getAsBoolean()) {
                        throw new java.util.concurrent.CancellationException("Task cancelled");
                    }
                    work.accept(items.get(itemIndex));
                }
            }, executor);
        }
        try {
            CompletableFuture.allOf(futures).join();
        } catch (java.util.concurrent.CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        }
    }

    private record RegionCacheKey(ChunkPos center, int radius) {}

    private static StaticCache2D<net.minecraft.server.level.GenerationChunkHolder> buildCache(
        ChunkPos center, int radius, Map<ChunkPos, ProtoChunk> chunks,
        Map<ChunkPos, ProtoChunkHolder> chunkHolders,
        Map<RegionCacheKey, StaticCache2D<net.minecraft.server.level.GenerationChunkHolder>> regionCaches,
        ServerLevel nmsLevel,
        net.minecraft.world.level.biome.BiomeSource biomeSource, RandomState randomState
    ) {
        return regionCaches.computeIfAbsent(new RegionCacheKey(center, radius), ignored ->
            StaticCache2D.create(center.x(), center.z(), radius, (x, z) -> {
                ChunkPos pos = new ChunkPos(x, z);
                return chunkHolders.computeIfAbsent(pos, p -> {
                    ProtoChunk pc = chunks.get(p);
                    if (pc == null) {
                        // Third-party decoration generators (notably CraftEngine) can inspect a chunk
                        // just outside the declared vanilla feature cache. An EMPTY fallback makes
                        // ProtoChunk#getNoiseBiome throw "Asking for biomes before we have biomes".
                        // Procedurally filling only its biome palettes is cheap and gives those boundary
                        // reads the same answer as native worldgen without generating/writing the chunk.
                        pc = newBiomeReadyProtoChunk(p, nmsLevel, biomeSource, randomState);
                    }
                    return new ProtoChunkHolder(pc);
                });
            }));
    }

    private static CompletableFuture<Void> stageFuture(
            CompletableFuture<Void> dependency,
            java.util.concurrent.Executor executor,
            java.util.function.BooleanSupplier isCancelled,
            java.util.concurrent.atomic.LongAdder workNanos,
            Runnable work) {
        return dependency.thenRunAsync(() -> {
            if (isCancelled.getAsBoolean()) {
                throw new java.util.concurrent.CancellationException("Task cancelled");
            }
            long started = System.nanoTime();
            try {
                work.run();
            } finally {
                workNanos.add(System.nanoTime() - started);
            }
        }, executor);
    }

    private static CompletableFuture<Void> neighborhoodBarrier(
            Map<ChunkPos, CompletableFuture<Void>> stages,
            ChunkPos center, int radius, CompletableFuture<Void> ready) {
        if (radius == 0) return stages.getOrDefault(center, ready);
        List<CompletableFuture<Void>> dependencies = new ArrayList<>((radius * 2 + 1) * (radius * 2 + 1));
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                CompletableFuture<Void> dependency = stages.get(new ChunkPos(center.x() + dx, center.z() + dz));
                if (dependency != null) dependencies.add(dependency);
            }
        }
        return dependencies.isEmpty()
            ? ready
            : CompletableFuture.allOf(dependencies.toArray(CompletableFuture[]::new));
    }

    private static long nanosToMillis(java.util.concurrent.atomic.LongAdder nanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(nanos.sum());
    }

    private static long nanosToMillis(long nanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(nanos);
    }

    private record ChunkTiming(ChunkPos pos, long wallNanos, long cpuNanos, String detail) {}

    private record GcSnapshot(long collections, long millis) {}

    private static final java.lang.management.ThreadMXBean THREAD_MX_BEAN =
        java.lang.management.ManagementFactory.getThreadMXBean();

    private static long currentThreadCpuNanos() {
        try {
            return THREAD_MX_BEAN.isCurrentThreadCpuTimeSupported() && THREAD_MX_BEAN.isThreadCpuTimeEnabled()
                ? THREAD_MX_BEAN.getCurrentThreadCpuTime()
                : -1L;
        } catch (UnsupportedOperationException | SecurityException ignored) {
            return -1L;
        }
    }

    private static long elapsedThreadCpuNanos(long started) {
        if (started < 0L) return -1L;
        long finished = currentThreadCpuNanos();
        return finished >= started ? finished - started : -1L;
    }

    private static GcSnapshot gcSnapshot() {
        long collections = 0L;
        long millis = 0L;
        for (var collector : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = collector.getCollectionCount();
            long time = collector.getCollectionTime();
            if (count >= 0L) collections += count;
            if (time >= 0L) millis += time;
        }
        return new GcSnapshot(collections, millis);
    }

    private static String gcDelta(GcSnapshot before, GcSnapshot after) {
        return "gc=" + Math.max(0L, after.collections() - before.collections()) + " collection(s)/"
            + Math.max(0L, after.millis() - before.millis()) + " ms";
    }

    private static String timingDistribution(java.util.Collection<ChunkTiming> timings) {
        if (timings.isEmpty()) return "n/a";
        long[] values = timings.stream().mapToLong(ChunkTiming::wallNanos).sorted().toArray();
        long total = 0L;
        for (long value : values) total += value;
        int p95Index = Math.max(0, (int) Math.ceil(values.length * 0.95D) - 1);
        long[] cpuValues = timings.stream().mapToLong(ChunkTiming::cpuNanos)
            .filter(value -> value >= 0L).sorted().toArray();
        String cpuSummary = cpuValues.length == 0 ? "cpu=n/a" : String.format(java.util.Locale.ROOT,
            "cpu-avg=%.2f/p95=%.2f/max=%.2f ms",
            java.util.Arrays.stream(cpuValues).average().orElse(0D) / 1_000_000D,
            cpuValues[Math.max(0, (int) Math.ceil(cpuValues.length * 0.95D) - 1)] / 1_000_000D,
            cpuValues[cpuValues.length - 1] / 1_000_000D);
        return String.format(java.util.Locale.ROOT, "wall-avg=%.2f/p95=%.2f/max=%.2f ms, %s",
            total / (double) values.length / 1_000_000D,
            values[p95Index] / 1_000_000D,
            values[values.length - 1] / 1_000_000D, cpuSummary);
    }

    private static void logSlowChunkTimings(String stage,
            java.util.Collection<ChunkTiming> timings, int limit, long thresholdMs) {
        long thresholdNanos = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(thresholdMs);
        timings.stream()
            .filter(timing -> timing.wallNanos() >= thresholdNanos)
            .sorted(java.util.Comparator.comparingLong(ChunkTiming::wallNanos).reversed())
            .limit(limit)
            .forEach(timing -> Logging.logger().info(String.format(java.util.Locale.ROOT,
                "[NmsTerrainGenerator] Slow %s task: chunk=(%d, %d), wall=%.2f ms, cpu=%s%s",
                stage, timing.pos().x(), timing.pos().z(), timing.wallNanos() / 1_000_000D,
                timing.cpuNanos() < 0L ? "n/a" : String.format(java.util.Locale.ROOT,
                    "%.2f ms", timing.cpuNanos() / 1_000_000D),
                 timing.detail().isEmpty() ? "" : ", reason=" + timing.detail())));
    }

    private static CachedStructureStarts snapshotStructureStarts(ProtoChunk chunk, ServerLevel level) {
        var context = net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext
            .fromLevel(level);
        List<CompoundTag> snapshots = new ArrayList<>();
        for (var start : chunk.getAllStarts().values()) {
            if (start.isValid()) snapshots.add(start.createTag(context, chunk.getPos()));
        }
        return new CachedStructureStarts(List.copyOf(snapshots));
    }

    private static Map<net.minecraft.world.level.levelgen.structure.Structure,
            net.minecraft.world.level.levelgen.structure.StructureStart> restoreStructureStarts(
            CachedStructureStarts cached, ServerLevel level, long seed, ChunkPos startChunk) {
        var context = net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext
            .fromLevel(level);
        Map<net.minecraft.world.level.levelgen.structure.Structure,
            net.minecraft.world.level.levelgen.structure.StructureStart> restored = new HashMap<>();
        for (CompoundTag snapshot : cached.starts()) {
            var start = net.minecraft.world.level.levelgen.structure.StructureStart.loadStaticStart(
                context, snapshot.copy(), seed);
            if (start == null || !start.isValid()) {
                throw new IllegalStateException("Cached StructureStart could not be deserialized");
            }
            var box = start.getBoundingBox();
            var event = new org.bukkit.event.world.AsyncStructureSpawnEvent(
                level.getWorld(),
                org.bukkit.craftbukkit.generator.structure.CraftStructure.minecraftToBukkit(start.getStructure()),
                new org.bukkit.util.BoundingBox(
                    box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()),
                startChunk.x(), startChunk.z());
            org.bukkit.Bukkit.getPluginManager().callEvent(event);
            if (!event.isCancelled()) restored.put(start.getStructure(), start);
        }
        return restored;
    }

    private static String summarizeStructureStarts(
            ProtoChunk chunk, net.minecraft.core.Registry<net.minecraft.world.level.levelgen.structure.Structure> registry) {
        StringBuilder summary = new StringBuilder();
        int validStarts = 0;
        int totalPieces = 0;
        for (var entry : chunk.getAllStarts().entrySet()) {
            var start = entry.getValue();
            if (!start.isValid()) continue;
            if (validStarts++ > 0) summary.append('|');
            int pieces = start.getPieces().size();
            totalPieces += pieces;
            var box = start.getBoundingBox();
            var key = registry.getKey(entry.getKey());
            summary.append(key == null ? "unknown" : key)
                .append("[pieces=").append(pieces)
                .append(",box=").append(box.getXSpan()).append('x')
                .append(box.getYSpan()).append('x').append(box.getZSpan()).append(']');
        }
        return validStarts == 0 ? "starts=0" : "starts=" + validStarts
            + ",pieces=" + totalPieces + ",structures=" + summary;
    }

    private static ProtoChunk newProtoChunk(ChunkPos pos, ServerLevel level) {
        return new ProtoChunk(pos, UpgradeData.EMPTY, level, level.palettedContainerFactory(), null);
    }

    private static ProtoChunk newBiomeReadyProtoChunk(
            ChunkPos pos, ServerLevel level,
            net.minecraft.world.level.biome.BiomeSource biomeSource, RandomState randomState) {
        ProtoChunk chunk = newProtoChunk(pos, level);
        chunk.fillBiomesFromNoise(biomeSource, randomState.sampler());
        chunk.setPersistedStatus(ChunkStatus.BIOMES);
        return chunk;
    }

    private static void copyBiomePalettes(ChunkAccess source, ProtoChunk destination) {
        LevelChunkSection[] sourceSections = source.getSections();
        LevelChunkSection[] destinationSections = destination.getSections();
        int sectionCount = Math.min(sourceSections.length, destinationSections.length);
        for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
            LevelChunkSection sourceSection = sourceSections[sectionIndex];
            LevelChunkSection destinationSection = destinationSections[sectionIndex];
            if (sourceSection == null || destinationSection == null) continue;
            if (!(sourceSection.getBiomes() instanceof PalettedContainer<Holder<Biome>> sourceBiomes)
                || !(destinationSection.getBiomes() instanceof PalettedContainer<Holder<Biome>> destinationBiomes)) {
                continue;
            }
            for (int biomeX = 0; biomeX < 4; biomeX++) {
                for (int biomeY = 0; biomeY < 4; biomeY++) {
                    for (int biomeZ = 0; biomeZ < 4; biomeZ++) {
                        destinationBiomes.set(
                            biomeX, biomeY, biomeZ, sourceBiomes.get(biomeX, biomeY, biomeZ));
                    }
                }
            }
        }
    }

    private static java.util.Set<ChunkPos> collectStructureStartsToRefresh(
            java.util.Set<ChunkPos> targets, List<ChunkPos> featureSources,
            Map<ChunkPos, ProtoChunk> chunks) {
        java.util.Set<ChunkPos> result = new java.util.HashSet<>(targets);
        for (ChunkPos sourcePos : featureSources) {
            ProtoChunk source = chunks.get(sourcePos);
            if (source == null) continue;
            for (it.unimi.dsi.fastutil.longs.LongSet starts : source.getAllReferences().values()) {
                for (long packed : starts) {
                    ChunkPos startPos = ChunkPos.unpack(packed);
                    if (chunks.containsKey(startPos)) result.add(startPos);
                }
            }
        }
        return result;
    }

    private static void materializePendingBlockEntities(ServerLevel level, ProtoChunk chunk) {
        for (Map.Entry<BlockPos, CompoundTag> entry :
                new ArrayList<>(chunk.getBlockEntityNbts().entrySet())) {
            BlockPos pos = entry.getKey();
            CompoundTag tag = entry.getValue();
            // Malformed worldgen data must not make an otherwise valid regeneration batch
            // impossible to persist. In particular, custom/ported structure generators can
            // leave an unresolved pending block entity keyed by null. SerializableChunkData
            // cannot write such an entry either, so discard it and retain the generated block.
            if (pos == null) {
                removePendingBlockEntityWithoutPosition(chunk);
                Logging.logger().warning("[NmsTerrainGenerator] Discarded pending block entity with "
                    + "no position in chunk " + chunk.getPos());
                continue;
            }
            if (tag == null) {
                chunk.removeBlockEntity(pos);
                Logging.logger().warning("[NmsTerrainGenerator] Discarded pending block entity with "
                    + "no NBT at " + pos + " in chunk " + chunk.getPos());
                continue;
            }
            BlockState state = chunk.getBlockState(pos);
            net.minecraft.world.level.block.entity.BlockEntity blockEntity;
            String id = tag.getStringOr("id", "");
            if ("DUMMY".equalsIgnoreCase(id)) {
                if (!state.hasBlockEntity()) {
                    chunk.removeBlockEntity(pos);
                    continue;
                }
                blockEntity = ((net.minecraft.world.level.block.EntityBlock) state.getBlock())
                    .newBlockEntity(pos, state);
            } else {
                blockEntity = net.minecraft.world.level.block.entity.BlockEntity.loadStatic(
                    pos, state, tag, level.registryAccess());
            }
            if (blockEntity != null) chunk.setBlockEntity(blockEntity);
        }
    }

    private static void removePendingBlockEntityWithoutPosition(ProtoChunk chunk) {
        // Paper exposes pendingBlockEntities through an unmodifiable view. Its normal
        // removeBlockEntity(null) path works upstream, but Canvas' block-entity bucket patch
        // dereferences the position before removing the pending entry. Access the backing map
        // directly so malformed third-party worldgen data can be quarantined on both servers.
        Class<?> type = ProtoChunk.class;
        while (type != null) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField("pendingBlockEntities");
                if (!field.trySetAccessible()) break;
                Object value = field.get(chunk);
                if (value instanceof Map<?, ?> pending) {
                    pending.remove(null);
                    return;
                }
                break;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(
                    "Could not access ProtoChunk pending block entities", failure);
            }
        }
        throw new IllegalStateException("Could not locate ProtoChunk pending block entities");
    }

    private record LiveApplyResult(int changedSections, int unchangedSections, boolean visualRefreshRequired) {}

    private static LiveApplyResult applyToLoadedChunk(ServerLevel level, ProtoChunk source, LevelChunk target) {
        ChunkPos chunkPos = target.getPos();
        org.bukkit.Chunk bukkitChunk = level.getWorld().getChunkAt(chunkPos.x(), chunkPos.z());
        if (bukkitChunk != null) {
            clearChunkPersistentData(bukkitChunk);
            for (org.bukkit.entity.Entity entity : bukkitChunk.getEntities()) {
                if (entity instanceof org.bukkit.entity.Player) continue;
                if (isExemptFromClear(entity)) continue;
                entity.remove();
            }
        }

        List<CompoundTag> srcEntities = source.getEntities();
        if (srcEntities != null && !srcEntities.isEmpty()) {
            for (CompoundTag entityTag : srcEntities) {
                try {
                    Entity entity = EntityType.loadEntityRecursive(entityTag, level, EntitySpawnReason.LOAD, e -> e);
                    if (entity != null) {
                        level.tryAddFreshEntityWithPassengers(entity);
                    }
                } catch (Exception e) {
                    Logging.logger().warning("Failed to spawn entity during loaded chunk regen: " + e.getMessage());
                }
            }
        }

        boolean blockEntitiesChanged = !target.getBlockEntitiesPos().isEmpty()
            || !source.getBlockEntities().isEmpty();
        for (BlockPos pos : new java.util.ArrayList<>(target.getBlockEntitiesPos())) {
            target.removeBlockEntity(pos);
        }

        LevelChunkSection[] sourceSections = source.getSections();
        LevelChunkSection[] targetSections = target.getSections();

        // Captures each section's pre-overwrite boundary BlockStates (x==0/15 or z==0/15) so the
        // neighbor-update pass below can skip blocks that didn't actually change. Regen reproduces
        // the same seed/coords, so most of a chunk - especially underground - comes back identical
        // to what was already there; without this, every boundary block (tens of thousands per
        // full-height chunk) fired a BlockPhysicsEvent regardless, which is what stalled the Folia
        // region tick during bulk structure regens (plugins like Nexo hook that event per call).
        BlockState[][] oldEdgeStates = new BlockState[sourceSections.length][];
        boolean[] blockChangedSections = new boolean[sourceSections.length];
        int changedSectionCount = 0;
        int unchangedSectionCount = 0;
        boolean anyBlockChanges = false;
        boolean anyBiomeChanges = false;

        for (int i = 0; i < sourceSections.length; i++) {
            LevelChunkSection srcSec = sourceSections[i];
            LevelChunkSection destSec = targetSections[i];

            if (srcSec == null) {
                if (destSec == null) {
                    unchangedSectionCount++;
                    continue;
                }
                boolean blocksChanged = !destSec.hasOnlyAir();
                if (blocksChanged) {
                    oldEdgeStates[i] = captureEdgeStates(destSec);
                    blockChangedSections[i] = true;
                    anyBlockChanges = true;
                }
                targetSections[i] = null;
                anyBiomeChanges = true;
                changedSectionCount++;
                continue;
            }

            if (destSec == null) {
                destSec = new LevelChunkSection(level.palettedContainerFactory());
                targetSections[i] = destSec;
            }

            boolean blocksChanged = !sectionBlocksEqual(srcSec, destSec);
            boolean biomesChanged = !sectionBiomesEqual(srcSec, destSec);
            if (!blocksChanged && !biomesChanged) {
                unchangedSectionCount++;
                continue;
            }

            changedSectionCount++;
            if (blocksChanged) {
                oldEdgeStates[i] = captureEdgeStates(destSec);
                copySectionBlocks(srcSec, destSec);
                blockChangedSections[i] = true;
                anyBlockChanges = true;
            }
            if (biomesChanged) {
                copySectionBiomes(srcSec, destSec);
                anyBiomeChanges = true;
            }
        }

        for (Map.Entry<BlockPos, net.minecraft.world.level.block.entity.BlockEntity> entry : source.getBlockEntities().entrySet()) {
            // setBlockEntity() alone only populates the chunk's blockEntities map — it never registers a
            // ticker, so spawners (and anything else needing tick()) stay inert in the live chunk until the
            // next full reload. addAndRegisterBlockEntity() also wires up the ticker and game event listener.
            target.addAndRegisterBlockEntity(entry.getValue());
        }

        int minSectionY = target.getMinY() >> 4;
        if (anyBlockChanges) {
        // Re-prime heightmaps for target chunk
        java.util.Set<Heightmap.Types> heightmapTypes = java.util.EnumSet.of(
            Heightmap.Types.MOTION_BLOCKING,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            Heightmap.Types.OCEAN_FLOOR,
            Heightmap.Types.WORLD_SURFACE
        );
        Heightmap.primeHeightmaps(target, heightmapTypes);

        // Relight target chunk
        var lightEngine = level.getChunkSource().getLightEngine();
        for (int i = 0; i < targetSections.length; i++) {
            boolean isEmpty = targetSections[i] == null || targetSections[i].hasOnlyAir();
            lightEngine.updateSectionStatus(SectionPos.of(chunkPos, minSectionY + i), isEmpty);
        }
        try {
            if (lightEngine instanceof ca.spottedleaf.moonrise.patches.starlight.light.StarLightLightingProvider starlightProvider) {
                ca.spottedleaf.moonrise.patches.starlight.light.StarLightInterface starlightInterface =
                    starlightProvider.starlight$getLightEngine();
                Boolean[] emptySections = ca.spottedleaf.moonrise.patches.starlight.light.StarLightEngine.getEmptySectionsForChunk(target);
                starlightInterface.lightChunk(target, emptySections);
            } else {
                var threadedLightEngine = (ThreadedLevelLightEngine) lightEngine;
                threadedLightEngine.lightChunk(target, false).join();
            }
            target.setLightCorrect(true);
        } catch (Exception e) {
            Logging.logger().severe("Failed to relight target chunk: " + e.getMessage(), e);
        }
        }

        // Trigger block updates and fluid ticks for the live chunk
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        for (int sectionIdx = 0; sectionIdx < targetSections.length; sectionIdx++) {
            if (!blockChangedSections[sectionIdx]) {
                continue;
            }
            LevelChunkSection sec = targetSections[sectionIdx];
            BlockState[] oldEdge = oldEdgeStates[sectionIdx];
            int sectionY = (minSectionY + sectionIdx) << 4;
            for (int y = 0; y < 16; y++) {
                int worldY = sectionY + y;
                for (int z = 0; z < 16; z++) {
                    int worldZ = chunkPos.z() * 16 + z;
                    for (int x = 0; x < 16; x++) {
                        int worldX = chunkPos.x() * 16 + x;
                        mutablePos.set(worldX, worldY, worldZ);
                        BlockState state = sec == null
                            ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
                            : sec.getBlockState(x, y, z);

                        // 1. If it's a fluid, schedule a tick
                        if (!state.getFluidState().isEmpty()) {
                            level.scheduleTick(mutablePos, state.getFluidState().getType(), 1);
                        }

                        // 2. If it's on the boundary and actually changed, notify neighbors.
                        // Most boundary blocks come back identical (same seed/coords regenerate
                        // the same vanilla terrain), so skipping unchanged ones avoids firing a
                        // BlockPhysicsEvent for nearly the whole chunk perimeter on every regen.
                        if (x == 0 || x == 15 || z == 0 || z == 15) {
                            BlockState old = oldEdge != null ? oldEdge[(y * 16 + z) * 16 + x] : null;
                            if (old == null || old != state) {
                                level.updateNeighborsAt(mutablePos, state.getBlock());
                            }
                        }
                    }
                }
            }
        }

        target.markUnsaved();
        return new LiveApplyResult(changedSectionCount, unchangedSectionCount,
            anyBlockChanges || anyBiomeChanges || blockEntitiesChanged);
    }

    private static boolean sectionBlocksEqual(LevelChunkSection source, LevelChunkSection target) {
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (source.getBlockState(x, y, z) != target.getBlockState(x, y, z)) return false;
                }
            }
        }
        return true;
    }

    private static boolean sectionBiomesEqual(LevelChunkSection source, LevelChunkSection target) {
        if (!(source.getBiomes() instanceof PalettedContainer<Holder<Biome>> sourceBiomes)
            || !(target.getBiomes() instanceof PalettedContainer<Holder<Biome>> targetBiomes)) {
            return source.getBiomes() == target.getBiomes();
        }
        for (int x = 0; x < 4; x++) for (int y = 0; y < 4; y++) for (int z = 0; z < 4; z++) {
            if (!java.util.Objects.equals(sourceBiomes.get(x, y, z), targetBiomes.get(x, y, z))) return false;
        }
        return true;
    }

    private static void copySectionBlocks(LevelChunkSection source, LevelChunkSection target) {
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            target.setBlockState(x, y, z, source.getBlockState(x, y, z));
        }
    }

    private static void copySectionBiomes(LevelChunkSection source, LevelChunkSection target) {
        if (!(source.getBiomes() instanceof PalettedContainer<Holder<Biome>> sourceBiomes)
            || !(target.getBiomes() instanceof PalettedContainer<Holder<Biome>> targetBiomes)) return;
        for (int x = 0; x < 4; x++) for (int y = 0; y < 4; y++) for (int z = 0; z < 4; z++) {
            targetBiomes.set(x, y, z, sourceBiomes.get(x, y, z));
        }
    }

    private static BlockState[] captureEdgeStates(LevelChunkSection section) {
        BlockState[] states = new BlockState[4096];
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            if (x == 0 || x == 15 || z == 0 || z == 15) {
                states[(y * 16 + z) * 16 + x] = section.getBlockState(x, y, z);
            }
        }
        return states;
    }

    /** A regenerated live chunk starts with no plugin-owned persistent data. */
    private static void clearChunkPersistentData(org.bukkit.Chunk chunk) {
        var pdc = chunk.getPersistentDataContainer();
        for (org.bukkit.NamespacedKey key : new java.util.HashSet<>(pdc.getKeys())) {
            pdc.remove(key);
        }
    }

    private static class Logging {
        private static final github.freshchromatic.freshlib.util.Logging.LoggerProxy actualLogger = github.freshchromatic.freshlib.util.Logging.logger();
        
        public static LoggerWrapper logger() {
            return new LoggerWrapper();
        }
    }
    
    private static class LoggerWrapper {
        public void info(String msg) {
            if (debugLogging) {
                Logging.actualLogger.info(msg);
            }
        }
        public void warning(String msg) {
            Logging.actualLogger.warning(msg);
        }
        public void severe(String msg) {
            Logging.actualLogger.severe(msg);
        }
        public void severe(String msg, Throwable t) {
            Logging.actualLogger.severe(msg, t);
        }
    }
}
