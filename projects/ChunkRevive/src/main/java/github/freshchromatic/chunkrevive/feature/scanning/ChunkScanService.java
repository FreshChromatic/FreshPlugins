package github.freshchromatic.chunkrevive.feature.scanning;

import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.feature.marking.MarkRegistry;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import github.freshchromatic.chunkrevive.nms.BiomeMatchMode;
import github.freshchromatic.chunkrevive.nms.ChunkCoordinate;
import github.freshchromatic.chunkrevive.nms.ChunkStage;
import github.freshchromatic.chunkrevive.nms.WorldScanGateway;
import github.freshchromatic.chunkrevive.feature.scanning.BiomeMatcher;
import github.freshchromatic.chunkrevive.feature.scanning.BiomeRegionService;
import github.freshchromatic.chunkrevive.feature.scanning.DiskChunkScanner;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Reusable disk-scan and biome-detection application workflows. */
public final class ChunkScanService {
    public record BiomeIds(Set<String> resolved, List<String> invalid) {}
    public record BiomeDetection(String biomeId, BiomeRegionService.DetectResult result) {}
    private record BiomeDetectionKey(String world, int cx, int cz, String biomeId,
                                     ChunkStage minimumStage, int maxChunks) {}

    private final MarkRegistry markRegistry;
    private final DiskChunkScanner scanner;
    private final WorldScanGateway worldScan;
    private final Supplier<PluginConfig> config;
    private final ConcurrentHashMap<BiomeDetectionKey, CompletableFuture<BiomeDetection>>
        biomeDetections = new ConcurrentHashMap<>();

    public ChunkScanService(
            MarkRegistry markRegistry,
            DiskChunkScanner scanner,
            WorldScanGateway worldScan,
            Supplier<PluginConfig> config) {
        this.markRegistry = markRegistry;
        this.scanner = scanner;
        this.worldScan = worldScan;
        this.config = config;
    }

    public CompletableFuture<DiskChunkScanner.ScanResult> scan(
            World world,
            DiskChunkScanner.ScanArea area,
            DiskChunkScanner.BiomeFilter biomeFilter) {
        return scanner.scan(world, area, biomeFilter, null);
    }

    public boolean isRunning(String world) {
        return scanner.isRunning(world);
    }

    public boolean cancel(String world) {
        return scanner.cancel(world);
    }

    public int countRegions(World world, DiskChunkScanner.ScanArea area) {
        return scanner.countRegionFiles(world, area);
    }

    public Set<String> biomeIds(World world) {
        return worldScan.biomeIds(world);
    }

    public String centerBiome(World world, int cx, int cz) {
        return new BiomeMatcher(world, config.get().biome.heightmapTypeEnum()).centerBiome(cx, cz);
    }

    public BiomeIds parseBiomeIds(World world, String raw) {
        var known = biomeIds(world);
        var resolved = new HashSet<String>();
        var invalid = new ArrayList<String>();
        for (String token : raw.split(",")) {
            String key = token.trim();
            if (key.isEmpty()) continue;
            String id = key.contains(":") ? key.toLowerCase(Locale.ROOT)
                : "minecraft:" + key.toLowerCase(Locale.ROOT);
            if (known.contains(id)) resolved.add(id);
            else invalid.add(key);
        }
        return new BiomeIds(Set.copyOf(resolved), List.copyOf(invalid));
    }

    public CompletableFuture<BiomeDetection> detectBiomeRegion(
            World world, int cx, int cz, String biomeId) {
        var cfg = config.get();
        ChunkStage minStatus = DiskChunkScanner.parseStatus(cfg.scan.minPersistedStatus);
        int maxChunks = cfg.biome.regen.floodFillMaxChunks;
        var key = new BiomeDetectionKey(
            world.getName(), cx, cz, biomeId, minStatus, maxChunks);
        return biomeDetections.computeIfAbsent(key, ignored -> {
            CompletableFuture<BiomeDetection> created = CompletableFuture.supplyAsync(() -> {
                BiomeMatcher matcher = new BiomeMatcher(world, cfg.biome.heightmapTypeEnum());
                var detector = new BiomeRegionService(world, minStatus, maxChunks);
                var result = detector.detect(
                    cx, cz, Set.of(biomeId), matcher, BiomeMatchMode.ANY_OF_16);
                return new BiomeDetection(biomeId, result);
            }, runnable -> Thread.ofVirtual().name("cr-biome-detect").start(runnable));
            created.whenComplete((result, failure) -> biomeDetections.remove(key, created));
            return created;
        });
    }

    public List<MarkedChunk> buildBiomeCandidates(World world, List<ChunkCoordinate> chunks) {
        var cfg = config.get();
        boolean checkClaims = cfg.scan.checkResidenceClaims && markRegistry.getLandProtection().isEnabled();
        long now = System.currentTimeMillis();
        List<MarkedChunk> result = new ArrayList<>();
        for (ChunkCoordinate pos : chunks) {
            if (checkClaims && markRegistry.getLandProtection().hasClaim(world, pos.x(), pos.z())) continue;
            result.add(new MarkedChunk(world.getName(), pos.x(), pos.z(), UUID.randomUUID(), now, null, true));
        }
        return List.copyOf(result);
    }

    public List<MarkedChunk> markCandidates(List<MarkedChunk> candidates) {
        return markRegistry.markChunksBatch(candidates);
    }

    public List<MarkedChunk> markDirect(List<MarkedChunk> candidates) {
        return markRegistry.markChunksDirect(candidates);
    }

    public boolean isRegenerationRunning() {
        return markRegistry.getRegenerationQueue().isRunning();
    }
}
