package github.freshchromatic.chunkrevive.nms.v1_21_11.storage;

import ca.spottedleaf.concurrentutil.util.Priority;
import ca.spottedleaf.moonrise.patches.chunk_system.io.ChunkSystemRegionFileStorage;
import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler;
import github.freshchromatic.chunkrevive.nms.ChunkArea;
import github.freshchromatic.chunkrevive.nms.ChunkCoordinate;
import github.freshchromatic.chunkrevive.nms.ChunkStorageGateway;
import github.freshchromatic.chunkrevive.nms.EmptyRegionInfo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class V1_21_11ChunkStorageGateway implements ChunkStorageGateway {
    private static final long EMPTY_REGION_BYTES = 8192L;
    private static final Pattern REGION_FILE_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    @Override
    public int generationPadding() {
        return ChunkTaskScheduler.getMaxAccessRadius();
    }

    @Override
    public Optional<ChunkCoordinate> firstHolder(World world, ChunkArea area) {
        var level = (ChunkSystemServerLevel) ((CraftWorld) world).getHandle();
        var scheduler = level.moonrise$getChunkTaskScheduler();
        for (int cx = area.minX(); cx <= area.maxX(); cx++) {
            for (int cz = area.minZ(); cz <= area.maxZ(); cz++) {
                if (scheduler.chunkHolderManager.getChunkHolder(cx, cz) != null) {
                    return Optional.of(new ChunkCoordinate(cx, cz));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public CompletableFuture<Long> deleteChunks(World world, Collection<ChunkCoordinate> chunks) {
        if (chunks.isEmpty()) return CompletableFuture.completedFuture(0L);
        var level = (ChunkSystemServerLevel) ((CraftWorld) world).getHandle();
        Map<Long, List<ChunkPos>> byRegion = new LinkedHashMap<>();
        for (ChunkCoordinate chunk : chunks) {
            int regionX = Math.floorDiv(chunk.x(), 32);
            int regionZ = Math.floorDiv(chunk.z(), 32);
            long regionKey = ((long) regionX << 32) ^ (regionZ & 0xffffffffL);
            byRegion.computeIfAbsent(regionKey, ignored -> new ArrayList<>())
                .add(new ChunkPos(chunk.x(), chunk.z()));
        }
        List<CompletableFuture<Long>> operations = new ArrayList<>();
        for (List<ChunkPos> positions : byRegion.values()) {
            for (var type : MoonriseRegionFileIO.RegionFileType.values()) {
                operations.add(clearStorage(level, type, positions, false, false));
            }
        }
        return sum(operations);
    }

    @Override
    public CompletableFuture<Long> pruneRegion(World world, int regionX, int regionZ, boolean forceToDisk) {
        var level = (ChunkSystemServerLevel) ((CraftWorld) world).getHandle();
        int minX = regionX << 5, minZ = regionZ << 5;
        List<ChunkPos> positions = new ArrayList<>(1024);
        for (int cx = minX; cx <= minX + 31; cx++) {
            for (int cz = minZ; cz <= minZ + 31; cz++) positions.add(new ChunkPos(cx, cz));
        }
        List<CompletableFuture<Long>> operations = new ArrayList<>();
        for (var type : MoonriseRegionFileIO.RegionFileType.values()) {
            operations.add(clearStorage(level, type, positions, true, forceToDisk));
        }
        return sum(operations);
    }

    @Override
    public CompletableFuture<List<EmptyRegionInfo>> scanEmptyRegions(World world) {
        Path worldFolder = world.getWorldFolder().toPath();
        return CompletableFuture.supplyAsync(() -> {
            try {
                return scanEmptyRegionFolders(worldFolder);
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        });
    }

    static List<EmptyRegionInfo> scanEmptyRegionFolders(Path worldFolder) throws IOException {
        Map<Long, MutableEmptyRegion> regions = new TreeMap<>();
        for (String directory : List.of("region", "poi", "entities")) {
            scanEmptyRegionDirectory(worldFolder.resolve(directory), regions);
        }
        return regions.values().stream()
            .map(region -> new EmptyRegionInfo(region.x, region.z, region.bytes))
            .toList();
    }

    @Override
    public CompletableFuture<Long> pruneEmptyRegion(World world, int regionX, int regionZ,
                                                     boolean forceToDisk) {
        var level = (ChunkSystemServerLevel) ((CraftWorld) world).getHandle();
        List<CompletableFuture<Long>> operations = new ArrayList<>();
        for (var type : MoonriseRegionFileIO.RegionFileType.values()) {
            operations.add(truncateStorageIfEmpty(level, type, regionX, regionZ, forceToDisk));
        }
        return sum(operations);
    }

    private static void scanEmptyRegionDirectory(Path directory,
                                                 Map<Long, MutableEmptyRegion> regions) throws IOException {
        if (!Files.isDirectory(directory)) return;
        try (var files = Files.newDirectoryStream(directory, "*.mca")) {
            for (Path file : files) {
                try {
                    Matcher matcher = REGION_FILE_NAME.matcher(file.getFileName().toString());
                    if (!matcher.matches()) continue;
                    long size = Files.size(file);
                    if (size <= EMPTY_REGION_BYTES || !hasEmptyLocationTable(file)) continue;
                    int regionX = Integer.parseInt(matcher.group(1));
                    int regionZ = Integer.parseInt(matcher.group(2));
                    long key = ((long) regionX << 32) ^ (regionZ & 0xffffffffL);
                    MutableEmptyRegion region = regions.computeIfAbsent(
                        key, ignored -> new MutableEmptyRegion(regionX, regionZ));
                    region.bytes += size - EMPTY_REGION_BYTES;
                } catch (java.nio.file.NoSuchFileException ignored) {
                    // A concurrently removed file no longer needs pruning.
                } catch (NumberFormatException ignored) {
                    // Ignore filenames whose coordinates do not fit Minecraft's integer range.
                }
            }
        }
    }

    private static boolean hasEmptyLocationTable(Path file) throws IOException {
        ByteBuffer locations = ByteBuffer.allocate(4096);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            while (locations.hasRemaining()) {
                int read = channel.read(locations);
                if (read < 0) return false;
            }
        }
        for (byte value : locations.array()) {
            if (value != 0) return false;
        }
        return true;
    }

    private CompletableFuture<Long> truncateStorageIfEmpty(
            ChunkSystemServerLevel level, MoonriseRegionFileIO.RegionFileType type,
            int regionX, int regionZ, boolean forceToDisk) {
        CompletableFuture<Long> result = new CompletableFuture<>();
        MoonriseRegionFileIO.RegionDataController controller = controller(level, type);
        int chunkX = regionX << 5;
        int chunkZ = regionZ << 5;
        var task = controller.createRegionIoTask(chunkX, chunkZ, () -> {
            try {
                var storage = controller.getCache();
                RegionFile regionFile = ((ChunkSystemRegionFileStorage) storage)
                    .moonrise$getRegionFileIfExists(chunkX, chunkZ);
                if (regionFile == null) {
                    result.complete(0L);
                    return;
                }
                for (int cx = chunkX; cx < chunkX + 32; cx++) {
                    for (int cz = chunkZ; cz < chunkZ + 32; cz++) {
                        if (regionFile.hasChunk(new ChunkPos(cx, cz))) {
                            // The scan is only an estimate. A new write between scan and execution
                            // must turn this operation into a no-op, never a deletion.
                            result.complete(0L);
                            return;
                        }
                    }
                }
                Path path = regionFile.getPath();
                long before = Files.size(path);
                if (before <= EMPTY_REGION_BYTES) {
                    result.complete(0L);
                    return;
                }
                regionFile.flush();
                try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
                    channel.truncate(EMPTY_REGION_BYTES);
                    if (forceToDisk) channel.force(true);
                }
                result.complete(Math.max(0L, before - Files.size(path)));
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        }, Priority.LOWEST);
        if (!task.queue()) {
            result.completeExceptionally(new IllegalStateException("Moonrise region I/O queue rejected the task"));
        }
        return result;
    }

    private static MoonriseRegionFileIO.RegionDataController controller(
            ChunkSystemServerLevel level, MoonriseRegionFileIO.RegionFileType type) {
        return switch (type) {
            case CHUNK_DATA -> level.moonrise$getChunkDataController();
            case POI_DATA -> level.moonrise$getPoiChunkDataController();
            case ENTITY_DATA -> level.moonrise$getEntityChunkDataController();
        };
    }

    private CompletableFuture<Long> clearStorage(ChunkSystemServerLevel level,
                                                  MoonriseRegionFileIO.RegionFileType type,
                                                  Collection<ChunkPos> positions,
                                                  boolean truncate,
                                                  boolean forceToDisk) {
        CompletableFuture<Long> result = new CompletableFuture<>();
        MoonriseRegionFileIO.RegionDataController controller = controller(level, type);
        ChunkPos first = positions.iterator().next();
        var task = controller.createRegionIoTask(first.x, first.z, () -> {
            try {
                var storage = controller.getCache();
                long before = 0L;
                RegionFile regionFile = ((ChunkSystemRegionFileStorage) storage)
                    .moonrise$getRegionFileIfExists(first.x, first.z);
                if (regionFile != null) before = Files.size(regionFile.getPath());
                long reclaimed = regionFile == null
                    ? 0L : allocatedSectorBytes(regionFile.getPath(), positions);
                for (ChunkPos pos : positions) storage.write(pos, null);
                regionFile = ((ChunkSystemRegionFileStorage) storage)
                    .moonrise$getRegionFileIfLoaded(first.x, first.z);
                if (truncate && regionFile != null) {
                    for (ChunkPos pos : positions) {
                        if (regionFile.hasChunk(pos)) {
                            throw new IllegalStateException("Region validation failed; chunk remains at " + pos);
                        }
                    }
                    regionFile.flush();
                    try (FileChannel channel = FileChannel.open(regionFile.getPath(), StandardOpenOption.WRITE)) {
                        channel.truncate(EMPTY_REGION_BYTES);
                        if (forceToDisk) channel.force(true);
                    }
                }
                long after = regionFile != null && Files.exists(regionFile.getPath())
                    ? Files.size(regionFile.getPath()) : 0L;
                // Clearing an individual chunk releases its sectors back to RegionFile's free
                // list but normally does not shrink the .mca file. Report that reusable capacity
                // instead of relying solely on the physical file-length delta (which is 0).
                result.complete(truncate ? Math.max(0L, before - after) : reclaimed);
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        }, Priority.LOWEST);
        if (!task.queue()) {
            result.completeExceptionally(new IllegalStateException("Moonrise region I/O queue rejected the task"));
        }
        return result;
    }

    static long allocatedSectorBytes(Path regionPath, Collection<ChunkPos> positions) throws IOException {
        if (!Files.exists(regionPath) || Files.size(regionPath) < 4096L) return 0L;
        ByteBuffer locations = ByteBuffer.allocate(4096);
        try (FileChannel channel = FileChannel.open(regionPath, StandardOpenOption.READ)) {
            while (locations.hasRemaining() && channel.read(locations) >= 0) {
                // Keep reading until the complete location table is available or EOF is reached.
            }
        }
        if (locations.position() < 4096) return 0L;
        locations.flip();
        long sectors = 0L;
        for (ChunkPos pos : positions) {
            int index = (pos.x & 31) + ((pos.z & 31) << 5);
            int location = locations.getInt(index << 2);
            int sectorOffset = location >>> 8;
            int sectorCount = location & 0xff;
            if (sectorOffset >= 2 && sectorCount > 0) sectors += sectorCount;
        }
        return sectors * 4096L;
    }

    private static CompletableFuture<Long> sum(List<CompletableFuture<Long>> futures) {
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
            .thenApply(ignored -> futures.stream().mapToLong(CompletableFuture::join).sum());
    }

    private static final class MutableEmptyRegion {
        private final int x;
        private final int z;
        private long bytes;

        private MutableEmptyRegion(int x, int z) {
            this.x = x;
            this.z = z;
        }
    }
}

