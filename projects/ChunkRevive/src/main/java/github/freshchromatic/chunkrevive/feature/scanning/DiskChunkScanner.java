package github.freshchromatic.chunkrevive.feature.scanning;

import github.freshchromatic.chunkrevive.api.integration.MaintenanceAction;
import github.freshchromatic.chunkrevive.api.integration.ProtectionDecision;
import github.freshchromatic.chunkrevive.api.model.ChunkKey;
import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import github.freshchromatic.chunkrevive.feature.marking.MarkRegistry;
import github.freshchromatic.chunkrevive.feature.reset.AnvilRegionPos;
import github.freshchromatic.chunkrevive.feature.structure.StructureRegistry;
import github.freshchromatic.chunkrevive.integration.protection.LandProtection;
import github.freshchromatic.chunkrevive.integration.protection.ProtectionChecks;
import github.freshchromatic.chunkrevive.nms.BiomeMatchMode;
import github.freshchromatic.chunkrevive.nms.ChunkCoordinate;
import github.freshchromatic.chunkrevive.nms.ChunkStage;
import github.freshchromatic.chunkrevive.nms.NmsPlatformLoader;
import github.freshchromatic.chunkrevive.nms.WorldScanGateway;
import github.freshchromatic.freshlib.util.Logging;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.World;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.UUID;

/**
 * Scans every chunk already present on disk for a world (or a radius around a point) and marks it,
 * including any tracked structures it belongs to — without forcing generation of anything that
 * doesn't already exist.
 *
 * Reads only the raw NBT "Status" and "structures" tags rather than going through
 * {@code SerializableChunkData}/{@code ChunkAccess} — building a full chunk (block/biome palette
 * containers, light data, heightmaps, entities) is unnecessary for a presence/structure scan and
 * dominates the cost of a full-world scan.
 *
 * Each region is read via its own {@code RegionFile} handle instead of {@code ChunkMap}/{@code IOWorker}:
 * vanilla funnels every chunk load for a dimension through a single one-task-at-a-time queue
 * ({@code AbstractConsecutiveExecutor}), so going through it caps a multi-region scan at strictly
 * serial disk I/O no matter how many scanner threads call it. Opening region files directly lets
 * {@code scan.thread-pool.parallelism} regions be read truly concurrently. The {@code RegionStorageInfo}
 * passed below deliberately omits {@code DataFixTypes.CHUNK}, which keeps {@code RegionFile}'s
 * {@code canRecalcHeader} flag false — without it, a transient header read race against the live
 * server's own IOWorker could make this read-only scan rewrite region file headers on disk.
 */
public final class DiskChunkScanner {

    private static final Pattern REGION_FILE_PATTERN = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    // Synthetic "actor" used as markedBy for chunks discovered by a scan rather than a real player.
    private static final UUID SCAN_ACTOR = new UUID(0L, 0L);

    public record ScanArea(int centerCx, int centerCz, int radiusChunks) {

        public boolean containsChunk(int chunkX, int chunkZ) {
            return Math.abs((long) chunkX - centerCx) <= radiusChunks && Math.abs((long) chunkZ
                - centerCz) <= radiusChunks;
        }
    }

    /** Optional procedural biome filter (see BiomeMatcher); chunks that don't match any target biome are skipped. */
    public record BiomeFilter(Set<String> targets, BiomeMatchMode matchMode) {}

    public record ScanResult(int regionsScanned, int regionsTotal, int chunksFound, int chunksMarked,
        int chunksSkippedExisting, int chunksSkippedClaimed, int chunksFailedToRead, int chunksSkippedBiomeMismatch,
        boolean cancelled) {}

    public interface ProgressListener {

        void onProgress(int regionsScanned, int regionsTotal);
    }

    /** Snapshot of one world's independently running scan. */
    public record ScanProgress(String world, int regionsScanned, int regionsTotal) {}

    private static final class ScanState {

        private final AtomicBoolean cancelled                  = new AtomicBoolean();
        private final AtomicInteger regionsScanned             = new AtomicInteger();
        private final AtomicInteger chunksFound                = new AtomicInteger();
        private final AtomicInteger chunksSkippedClaimed       = new AtomicInteger();
        private final AtomicInteger chunksFailedToRead         = new AtomicInteger();
        private final AtomicInteger chunksMarkedTotal          = new AtomicInteger();
        private final AtomicInteger chunksSkippedExisting      = new AtomicInteger();
        private final AtomicInteger chunksSkippedBiomeMismatch = new AtomicInteger();
        private volatile int        regionsTotal;
    }

    private record RegionScan(World world, ScanArea area, ChunkStage minStatus, boolean checkClaims,
        BiomeFilter biomeFilter, BiomeMatcher biomeMatcher) {}

    private final MarkRegistry       markRegistry;
    private final StructureRegistry  structureRegistry;
    private final LandProtection     landProtection;
    private final ProtectionChecks   protectionChecks;
    private final Consumer<Runnable> globalScheduler;
    private final WorldScanGateway   worldScan;
    private PluginConfig             config;

    // A scan only conflicts with another scan of the same world. Region files and their
    // associated world state are independent across dimensions/worlds, so separate worlds can
    // safely be scanned concurrently.
    private final Map<String, ScanState> activeScans = new ConcurrentHashMap<>();

    public DiskChunkScanner(MarkRegistry markRegistry, StructureRegistry structureRegistry,
        LandProtection landProtection, PluginConfig config, ProtectionChecks protectionChecks, Plugin plugin) {
        this(markRegistry, structureRegistry, landProtection, config, protectionChecks, plugin,
            NmsPlatformLoader.load().worldScan());
    }

    DiskChunkScanner(MarkRegistry markRegistry, StructureRegistry structureRegistry, LandProtection landProtection,
        PluginConfig config, ProtectionChecks protectionChecks, Plugin plugin, WorldScanGateway worldScan) {
        this(markRegistry, structureRegistry, landProtection, config, protectionChecks, worldScan,
            task -> Bukkit.getGlobalRegionScheduler().execute(plugin, task));
    }

    DiskChunkScanner(MarkRegistry markRegistry, StructureRegistry structureRegistry, LandProtection landProtection,
        PluginConfig config, ProtectionChecks protectionChecks, WorldScanGateway worldScan,
        Consumer<Runnable> globalScheduler) {
        this.markRegistry = markRegistry;
        this.structureRegistry = structureRegistry;
        this.landProtection = landProtection;
        this.worldScan = worldScan;
        this.config = config;
        this.protectionChecks = protectionChecks;
        this.globalScheduler = globalScheduler;
    }

    public void setConfig(PluginConfig config) {
        this.config = config;
    }

    public boolean isRunning() {
        return !activeScans.isEmpty();
    }

    public void cancel() {
        activeScans.values().forEach(state -> state.cancelled.set(true));
    }

    /** Cancels only one world's outstanding disk scan. */
    public boolean cancel(String worldName) {
        ScanState state = activeScans.get(worldName);
        if (state == null) {
            return false;
        }
        state.cancelled.set(true);
        return true;
    }

    public boolean isRunning(String worldName) {
        return activeScans.containsKey(worldName);
    }

    public List<ScanProgress> getActiveScans() {
        return activeScans.entrySet()
            .stream()
            .map(entry -> new ScanProgress(entry.getKey(),
            entry.getValue().regionsScanned.get(), entry.getValue().regionsTotal))
            .sorted(Comparator.comparing(ScanProgress::world))
            .toList();
    }

    /** Aggregate progress retained for the status UI. */
    public int getRegionsScanned() {
        return activeScans.values().stream().mapToInt(state -> state.regionsScanned.get()).sum();
    }

    /** Aggregate progress retained for the status UI. */
    public int getRegionsTotal() {
        return activeScans.values().stream().mapToInt(state -> state.regionsTotal).sum();
    }

    /** A comma-separated list of worlds currently being scanned. */
    public String getActiveWorld() {
        return String.join(", ", activeScans.keySet().stream().sorted().toList());
    }

    /** Counts candidate region files without reading any chunk data; used for the fullmark confirmation prompt. */
    public int countRegionFiles(World world, ScanArea area) {
        try {
            return listRegionFiles(world, area).size();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to count region files for " + world.getName(), e);
        }
    }

    public CompletableFuture<Integer> countRegionFilesAsync(World world, ScanArea area) {
        return CompletableFuture.supplyAsync(() -> countRegionFiles(world, area), task ->
            Thread.ofVirtual()
                .name("cr-region-count")
                .start(task));
    }

    public CompletableFuture<ScanResult> scan(World world, ScanArea area, ProgressListener listener) {
        return scan(world, area, null, listener);
    }

    public CompletableFuture<ScanResult> scan(World world, ScanArea area, BiomeFilter biomeFilter,
        ProgressListener listener) {
        String worldName = world.getName();
        ScanState state = new ScanState();
        if (activeScans.putIfAbsent(worldName, state) != null) {
            return CompletableFuture.failedFuture(new IllegalStateException("A scan is already running for "
                + worldName));
        }

        CompletableFuture<ScanResult> future = new CompletableFuture<>();
        Thread.ofVirtual()
            .name("cr-disk-scan-" + worldName)
            .start(() -> {
                try {
                    future.complete(doScan(world, area, biomeFilter, listener, state));
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                } finally {
                    activeScans.remove(worldName, state);
                }
            });
        return future;
    }

    private ScanResult doScan(World world, ScanArea area, BiomeFilter biomeFilter, ProgressListener listener,
        ScanState state) throws IOException {
        ChunkStage minStatus = parseStatus(config.scan.minPersistedStatus);
        boolean checkClaims = config.landProtection.scanClaims;
        // Purely procedural (no disk I/O of its own); read-only and safe to share across region workers.
        BiomeMatcher biomeMatcher = biomeFilter != null ? new BiomeMatcher(world, config.biome.heightmapTypeEnum())
            : null;

        List<AnvilRegionPos> regionCoords = listRegionFiles(world, area);
        state.regionsTotal = regionCoords.size();

        RegionScan scan = new RegionScan(world, area, minStatus, checkClaims, biomeFilter, biomeMatcher);

        int parallelism = Math.max(1, config.scan.threadPool.parallelism);
        ExecutorService pool = Executors.newFixedThreadPool(parallelism, r -> {
            Thread t = new Thread(r, "cr-disk-scan-worker");
            t.setDaemon(config.scan.threadPool.daemon);
            t.setPriority(Math.clamp(config.scan.threadPool.priority, 1, 10));
            return t;
        });

        long lastNotify = System.currentTimeMillis();
        try {
            List<Future<?>> futures = new ArrayList<>(regionCoords.size());
            for (AnvilRegionPos region : regionCoords) {
                futures.add(pool.submit(() -> {
                    if (!state.cancelled.get()) {
                        processRegion(region, scan, state);
                    }
                    state.regionsScanned.incrementAndGet();
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    state.cancelled.set(true);
                    throw new IllegalStateException("Region scan failed: " + e.getCause().getMessage(), e.getCause());
                } catch (InterruptedException e) {
                    state.cancelled.set(true);
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Region scan interrupted", e);
                }
                if (listener != null) {
                    long now = System.currentTimeMillis();
                    if (now - lastNotify >= 500 || state.regionsScanned.get() == state.regionsTotal) {
                        lastNotify = now;
                        listener.onProgress(state.regionsScanned.get(), state.regionsTotal);
                    }
                }
            }
        } finally {
            pool.shutdown();
        }

        return new ScanResult(state.regionsScanned.get(), state.regionsTotal, state.chunksFound.get(),
            state.chunksMarkedTotal.get(), state.chunksSkippedExisting.get(), state.chunksSkippedClaimed.get(),
            state.chunksFailedToRead.get(), state.chunksSkippedBiomeMismatch.get(), state.cancelled.get());
    }

    private void processRegion(AnvilRegionPos region, RegionScan scan, ScanState state) {
        World world = scan.world();
        ScanArea area = scan.area();
        BiomeFilter biomeFilter = scan.biomeFilter();
        List<MarkedChunk> toMark = new ArrayList<>();
        List<MarkedChunk> chunksToCheckClaims = new ArrayList<>();
        long now = System.currentTimeMillis();
        try (var disk = worldScan.openDiskSession(world)) {
            var scanned = disk.scanRegion(region.x(), region.z(), scan.minStatus(), config.structure.refresh::isTracked,
                state.cancelled::get);
            state.chunksFailedToRead.addAndGet(scanned.failedReads());
            for (var stored : scanned.chunks()) {
                if (state.cancelled.get()) {
                    return;
                }
                int cx = stored.coordinate().x(), cz = stored.coordinate().z();
                if (area != null && !area.containsChunk(cx, cz)) {
                    continue;
                }
                state.chunksFound.incrementAndGet();
                if (biomeFilter != null && !scan.biomeMatcher().matches(cx, cz, biomeFilter.targets(),
                    biomeFilter.matchMode())) {
                    state.chunksSkippedBiomeMismatch.incrementAndGet();
                    continue;
                }

                boolean handledAsStructure = false;
                if (config.structure.enabled && config.structure.mark.expandToFullStructure) {
                    for (var structure : stored.structures()) {
                        var bounds = structure.bounds();
                        int minX = bounds.minX() >> 4, maxX = bounds.maxX() >> 4;
                        int minZ = bounds.minZ() >> 4, maxZ = bounds.maxZ() >> 4;
                        List<ChunkCoordinate> effective = scan.checkClaims() ? callGlobal(
                            () -> structureRegistry.resolveEffectiveChunks(world.getName(), minX, maxX, minZ, maxZ))
                            : allChunksIn(minX, maxX, minZ, maxZ);
                        if (effective == null) {
                            continue;
                        }
                        handledAsStructure = true;
                        if (effective.isEmpty()) {
                            state.chunksSkippedClaimed.incrementAndGet();
                            continue;
                        }
                        UUID groupId = structureRegistry.findOrCreateGroup(world.getName(), structure.id(), minX, maxX,
                            minZ, maxZ);
                        for (ChunkCoordinate coordinate : effective) {
                            toMark.add(new MarkedChunk(world.getName(), coordinate.x(), coordinate.z(), SCAN_ACTOR, now,
                                groupId));
                        }
                    }
                }

                if (!handledAsStructure) {
                    MarkedChunk chunk = new MarkedChunk(world.getName(), cx, cz, SCAN_ACTOR, now, null,
                        biomeFilter != null);
                    (scan.checkClaims() ? chunksToCheckClaims : toMark).add(chunk);
                }
            }

            if (!chunksToCheckClaims.isEmpty()) {
                List<MarkedChunk> unclaimed = callGlobal(() ->
                    chunksToCheckClaims.stream()
                        .filter(chunk -> !landProtection.hasClaim(world, chunk.cx(), chunk.cz()))
                        .toList());
                state.chunksSkippedClaimed.addAndGet(chunksToCheckClaims.size() - unclaimed.size());
                toMark.addAll(unclaimed);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Failed to scan region r." + region.x() + "." + region.z() + ".mca",
                failure);
        }

        if (scan.checkClaims() && !toMark.isEmpty()) {
            int before = toMark.size();
            var outcome = markExternalUnclaimed(toMark, state.cancelled);
            if (outcome.cancelled()) {
                return;
            }
            List<MarkedChunk> newlyAdded = await(markPersisted(outcome.eligible()));
            state.chunksSkippedClaimed.addAndGet(before - outcome.eligible().size());
            state.chunksMarkedTotal.addAndGet(newlyAdded.size());
            state.chunksSkippedExisting.addAndGet(outcome.eligible().size() - newlyAdded.size());
        } else if (!toMark.isEmpty()) {
            int before = toMark.size();
            List<MarkedChunk> newlyAdded = await(markPersisted(toMark));
            state.chunksMarkedTotal.addAndGet(newlyAdded.size());
            state.chunksSkippedExisting.addAndGet(before - newlyAdded.size());
        }
    }

    private record MarkOutcome(List<MarkedChunk> eligible, boolean cancelled) {}

    private MarkOutcome markExternalUnclaimed(List<MarkedChunk> chunks, AtomicBoolean cancelled) {
        var result = new CompletableFuture<MarkOutcome>();
        var keys = chunks.stream().map(chunk -> new ChunkKey(chunk.world(), chunk.cx(), chunk.cz())).toList();
        try {
            globalScheduler.accept(() -> {
                long revision = protectionChecks.revision();
                try {
                    protectionChecks.checkExternal(MaintenanceAction.MARK, keys).whenComplete((decision, failure) -> {
                        if (failure != null) {
                            result.completeExceptionally(failure);
                        } else {
                            try {
                                globalScheduler.accept(() -> {
                                    try {
                                        if (result.isDone()) {
                                            return;
                                        }
                                        if (cancelled.get()) {
                                            result.complete(new MarkOutcome(List.of(), true));
                                            return;
                                        }
                                        if (decision == null || revision != protectionChecks.revision()) {
                                            throw new IllegalStateException("Protection changed during scan");
                                        }
                                        var eligible = chunks.stream()
                                            .filter(
                                            chunk -> decision.decisions().getOrDefault(new ChunkKey(chunk.world(),
                                                chunk.cx(), chunk.cz()),
                                                ProtectionDecision.ABSTAIN) != ProtectionDecision.DENY)
                                            .toList();
                                        result.complete(new MarkOutcome(eligible, false));
                                    } catch (Throwable error) {
                                        result.completeExceptionally(error);
                                    }
                                });
                            } catch (Throwable error) {
                                result.completeExceptionally(error);
                            }
                        }
                    });
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
            return result.get(5, TimeUnit.SECONDS);
        } catch (Exception failure) {
            result.completeExceptionally(failure);
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("External protection check failed during scan", failure);
        }
    }

    private CompletableFuture<List<MarkedChunk>> markPersisted(List<MarkedChunk> chunks) {
        return markRegistry.markChunksPersisted(chunks).thenCompose(added -> {
            var result = new CompletableFuture<List<MarkedChunk>>();
            try {
                globalScheduler.accept(() -> {
                    try {
                        added.forEach(chunk -> markRegistry.refreshMarkedDisplay(chunk.world(), chunk.cx(),
                            chunk.cz()));
                        result.complete(added);
                    } catch (Throwable failure) {
                        result.completeExceptionally(failure);
                    }
                });
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
            return result;
        });
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while saving scanned marks", failure);
        } catch (ExecutionException failure) {
            throw new IllegalStateException("Failed to save scanned marks", failure.getCause());
        }
    }

    private <T> T callGlobal(Callable<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            globalScheduler.accept(() -> {
                try {
                    result.complete(action.call());
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
            return result.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a global region task", interrupted);
        } catch (ExecutionException | java.util.concurrent.TimeoutException failure) {
            rethrowFatalFailure(failure);
            throw new IllegalStateException("Global region task did not complete", failure);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to schedule a global region task", failure);
        }
    }

    private static void rethrowFatalFailure(Throwable failure) {
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof Error error) {
                throw error;
            }
            cause = cause.getCause();
        }
    }

    private static List<ChunkCoordinate> allChunksIn(int minCx, int maxCx, int minCz, int maxCz) {
        List<ChunkCoordinate> list = new ArrayList<>();
        for (int x = minCx; x <= maxCx; x++) {
            for (int z = minCz; z <= maxCz; z++) {
                list.add(new ChunkCoordinate(x, z));
            }
        }
        return list;
    }

    public static ChunkStage parseStatus(String raw) {
        return ChunkStage.configured(raw);
    }

    /**
     * Resolves the region directory through vanilla's storage API rather than guessing an on-disk
     * layout. This supports both legacy DIM-1/DIM1 worlds and the modern
     * dimensions/<namespace>/<dimension> layout used by current Paper/Folia.
     */
    public static Path regionFolder(World world) {
        try (var disk = NmsPlatformLoader.load().worldScan().openDiskSession(world)) {
            return disk.regionFolder();
        }
    }

    private List<AnvilRegionPos> listRegionFiles(World world, ScanArea area) throws IOException {
        Path regionDir;
        try (var disk = worldScan.openDiskSession(world)) {
            regionDir = disk.regionFolder();
        }
        List<AnvilRegionPos> result = new ArrayList<>();
        if (Files.notExists(regionDir)) {
            return result;
        }

        Integer minRX = null, maxRX = null, minRZ = null, maxRZ = null;
        if (area != null) {
            int regionRadius = area.radiusChunks() / 32 + 1;
            int centerRX = Math.floorDiv(area.centerCx(), 32);
            int centerRZ = Math.floorDiv(area.centerCz(), 32);
            minRX = centerRX - regionRadius;
            maxRX = centerRX + regionRadius;
            minRZ = centerRZ - regionRadius;
            maxRZ = centerRZ + regionRadius;
        }

        try (var stream = Files.list(regionDir)) {
            for (Path p : stream.toList()) {
                var matcher = REGION_FILE_PATTERN.matcher(p.getFileName().toString());
                if (!matcher.matches()) {
                    continue;
                }
                int rx = Integer.parseInt(matcher.group(1));
                int rz = Integer.parseInt(matcher.group(2));
                if (area != null && (rx < minRX || rx > maxRX || rz < minRZ || rz > maxRZ)) {
                    continue;
                }
                result.add(new AnvilRegionPos(rx, rz));
            }
        }
        return result;
    }
}
