package github.freshchromatic.chunkrevive.feature.scanning;

import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.integration.protection.LandProtection;
import github.freshchromatic.chunkrevive.feature.marking.MarkRegistry;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import github.freshchromatic.chunkrevive.feature.structure.StructureRegistry;
import github.freshchromatic.chunkrevive.nms.BiomeMatchMode;
import github.freshchromatic.chunkrevive.nms.ChunkStage;
import github.freshchromatic.chunkrevive.nms.NmsPlatformLoader;
import github.freshchromatic.chunkrevive.nms.WorldScanGateway;
import github.freshchromatic.freshlib.util.Logging;
import org.bukkit.World;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

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

    public record ScanArea(int centerCx, int centerCz, int radiusChunks) {}

    /** Optional procedural biome filter (see BiomeMatcher); chunks that don't match any target biome are skipped. */
    public record BiomeFilter(java.util.Set<String> targets, BiomeMatchMode matchMode) {}

    public record ScanResult(int regionsScanned, int regionsTotal, int chunksFound, int chunksMarked,
                              int chunksSkippedExisting, int chunksSkippedClaimed, int chunksFailedToRead,
                              int chunksSkippedBiomeMismatch) {}

    public interface ProgressListener {
        void onProgress(int regionsScanned, int regionsTotal);
    }

    /** Snapshot of one world's independently running scan. */
    public record ScanProgress(String world, int regionsScanned, int regionsTotal) {}

    private static final class ScanState {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicInteger regionsScanned = new AtomicInteger();
        private volatile int regionsTotal;
    }

    private final MarkRegistry markRegistry;
    private final StructureRegistry structureRegistry;
    private final LandProtection landProtection;
    private final WorldScanGateway worldScan;
    private PluginConfig config;

    // A scan only conflicts with another scan of the same world. Region files and their
    // associated world state are independent across dimensions/worlds, so separate worlds can
    // safely be scanned concurrently.
    private final Map<String, ScanState> activeScans = new java.util.concurrent.ConcurrentHashMap<>();

    public DiskChunkScanner(MarkRegistry markRegistry, StructureRegistry structureRegistry,
                             LandProtection landProtection, PluginConfig config) {
        this.markRegistry = markRegistry;
        this.structureRegistry = structureRegistry;
        this.landProtection = landProtection;
        this.worldScan = NmsPlatformLoader.load().worldScan();
        this.config = config;
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
        if (state == null) return false;
        state.cancelled.set(true);
        return true;
    }

    public boolean isRunning(String worldName) {
        return activeScans.containsKey(worldName);
    }

    public List<ScanProgress> getActiveScans() {
        return activeScans.entrySet().stream()
            .map(entry -> new ScanProgress(entry.getKey(), entry.getValue().regionsScanned.get(), entry.getValue().regionsTotal))
            .sorted(java.util.Comparator.comparing(ScanProgress::world))
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
            return 0;
        }
    }

    public CompletableFuture<ScanResult> scan(World world, ScanArea area, ProgressListener listener) {
        return scan(world, area, null, listener);
    }

    public CompletableFuture<ScanResult> scan(World world, ScanArea area, BiomeFilter biomeFilter, ProgressListener listener) {
        String worldName = world.getName();
        ScanState state = new ScanState();
        if (activeScans.putIfAbsent(worldName, state) != null) {
            return CompletableFuture.failedFuture(new IllegalStateException("A scan is already running for " + worldName));
        }

        CompletableFuture<ScanResult> future = new CompletableFuture<>();
        Thread.ofVirtual().name("cr-disk-scan-" + worldName).start(() -> {
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

    private ScanResult doScan(World world, ScanArea area, BiomeFilter biomeFilter, ProgressListener listener, ScanState state) throws IOException {
        ChunkStage minStatus = parseStatus(config.scan.minPersistedStatus);
        boolean checkClaims = config.scan.checkResidenceClaims && landProtection.isEnabled();
        // Purely procedural (no disk I/O of its own); read-only and safe to share across region workers.
        BiomeMatcher biomeMatcher = biomeFilter != null
            ? new BiomeMatcher(world, config.biome.heightmapTypeEnum())
            : null;

        List<int[]> regionCoords = listRegionFiles(world, area);
        state.regionsTotal = regionCoords.size();

        AtomicInteger chunksFound = new AtomicInteger();
        AtomicInteger chunksSkippedClaimed = new AtomicInteger();
        AtomicInteger chunksFailedToRead = new AtomicInteger();
        AtomicInteger chunksMarkedTotal = new AtomicInteger();
        AtomicInteger chunksSkippedExisting = new AtomicInteger();
        AtomicInteger chunksSkippedBiomeMismatch = new AtomicInteger();

        int parallelism = Math.max(1, config.scan.threadPool.parallelism);
        ExecutorService pool = Executors.newFixedThreadPool(parallelism, r -> {
            Thread t = new Thread(r, "cr-disk-scan-worker");
            t.setDaemon(config.scan.threadPool.daemon);
            t.setPriority(Math.clamp(config.scan.threadPool.priority, 1, 10));
            return t;
        });

        long[] lastNotify = {System.currentTimeMillis()};
        try {
            List<Future<?>> futures = new ArrayList<>(regionCoords.size());
            for (int[] region : regionCoords) {
                futures.add(pool.submit(() -> {
                    if (!state.cancelled.get()) {
                        processRegion(world, region[0], region[1], area, minStatus, checkClaims,
                            biomeFilter, biomeMatcher, chunksFound, chunksSkippedClaimed, chunksFailedToRead,
                            chunksMarkedTotal, chunksSkippedExisting, chunksSkippedBiomeMismatch, state.cancelled);
                    }
                    state.regionsScanned.incrementAndGet();
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException | InterruptedException e) {
                    Logging.logger().warning("[DiskChunkScanner] Region task failed: " + e.getMessage());
                }
                if (listener != null) {
                    long now = System.currentTimeMillis();
                    if (now - lastNotify[0] >= 500 || state.regionsScanned.get() == state.regionsTotal) {
                        lastNotify[0] = now;
                        listener.onProgress(state.regionsScanned.get(), state.regionsTotal);
                    }
                }
            }
        } finally {
            pool.shutdown();
        }

        return new ScanResult(state.regionsScanned.get(), state.regionsTotal, chunksFound.get(), chunksMarkedTotal.get(),
            chunksSkippedExisting.get(), chunksSkippedClaimed.get(), chunksFailedToRead.get(),
            chunksSkippedBiomeMismatch.get());
    }

    private void processRegion(World world, int regionX, int regionZ, ScanArea area,
                               ChunkStage minStatus, boolean checkClaims,
                               BiomeFilter biomeFilter, BiomeMatcher biomeMatcher,
                               AtomicInteger chunksFound, AtomicInteger chunksSkippedClaimed,
                               AtomicInteger chunksFailedToRead, AtomicInteger chunksMarkedTotal,
                               AtomicInteger chunksSkippedExisting, AtomicInteger chunksSkippedBiomeMismatch,
                               AtomicBoolean cancelled) {
        List<MarkedChunk> toMark = new ArrayList<>();
        long now = System.currentTimeMillis();
        try (var disk = worldScan.openDiskSession(world)) {
            var scanned = disk.scanRegion(
                regionX, regionZ, minStatus, config.structure.refresh::isTracked, cancelled::get);
            chunksFailedToRead.addAndGet(scanned.failedReads());
            for (var stored : scanned.chunks()) {
                if (cancelled.get()) return;
                int cx = stored.coordinate().x(), cz = stored.coordinate().z();
                if (area != null) {
                    int distance = Math.max(Math.abs(cx - area.centerCx()), Math.abs(cz - area.centerCz()));
                    if (distance > area.radiusChunks()) continue;
                }
                chunksFound.incrementAndGet();
                if (biomeFilter != null
                    && !biomeMatcher.matches(cx, cz, biomeFilter.targets(), biomeFilter.matchMode())) {
                    chunksSkippedBiomeMismatch.incrementAndGet();
                    continue;
                }

                boolean handledAsStructure = false;
                if (config.structure.enabled && config.structure.mark.expandToFullStructure) {
                    for (var structure : stored.structures()) {
                        var bounds = structure.bounds();
                        int minX = bounds.minX() >> 4, maxX = bounds.maxX() >> 4;
                        int minZ = bounds.minZ() >> 4, maxZ = bounds.maxZ() >> 4;
                        List<int[]> effective = checkClaims
                            ? structureRegistry.resolveEffectiveChunks(world.getName(), minX, maxX, minZ, maxZ)
                            : allChunksIn(minX, maxX, minZ, maxZ);
                        if (effective == null) continue;
                        handledAsStructure = true;
                        if (effective.isEmpty()) {
                            chunksSkippedClaimed.incrementAndGet();
                            continue;
                        }
                        UUID groupId = structureRegistry.findOrCreateGroup(
                            world.getName(), structure.id(), minX, maxX, minZ, maxZ);
                        for (int[] coordinate : effective) {
                            toMark.add(new MarkedChunk(
                                world.getName(), coordinate[0], coordinate[1], SCAN_ACTOR, now, groupId));
                        }
                    }
                }

                if (!handledAsStructure) {
                    if (checkClaims && landProtection.hasClaim(world, cx, cz)) {
                        chunksSkippedClaimed.incrementAndGet();
                        continue;
                    }
                    toMark.add(new MarkedChunk(
                        world.getName(), cx, cz, SCAN_ACTOR, now, null, biomeFilter != null));
                }
            }
        } catch (IOException failure) {
            Logging.logger().warning("[DiskChunkScanner] Failed to scan region r."
                + regionX + "." + regionZ + ".mca: " + failure.getMessage());
            return;
        }

        if (!toMark.isEmpty()) {
            int before = toMark.size();
            List<MarkedChunk> newlyAdded = markRegistry.markChunksBatch(toMark);
            chunksMarkedTotal.addAndGet(newlyAdded.size());
            chunksSkippedExisting.addAndGet(before - newlyAdded.size());
        }
    }
    private static List<int[]> allChunksIn(int minCx, int maxCx, int minCz, int maxCz) {
        List<int[]> list = new ArrayList<>();
        for (int x = minCx; x <= maxCx; x++) {
            for (int z = minCz; z <= maxCz; z++) {
                list.add(new int[]{x, z});
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

    private static List<int[]> listRegionFiles(World world, ScanArea area) throws IOException {
        Path regionDir = regionFolder(world);
        List<int[]> result = new ArrayList<>();
        if (!Files.isDirectory(regionDir)) return result;

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
                if (!matcher.matches()) continue;
                int rx = Integer.parseInt(matcher.group(1));
                int rz = Integer.parseInt(matcher.group(2));
                if (area != null && (rx < minRX || rx > maxRX || rz < minRZ || rz > maxRZ)) continue;
                result.add(new int[]{rx, rz});
            }
        }
        return result;
    }
}
