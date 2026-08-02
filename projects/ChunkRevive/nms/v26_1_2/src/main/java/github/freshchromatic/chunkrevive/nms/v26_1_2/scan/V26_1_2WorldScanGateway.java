package github.freshchromatic.chunkrevive.nms.v26_1_2.scan;

import com.mojang.serialization.Codec;
import ca.spottedleaf.concurrentutil.util.Priority;
import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel;
import github.freshchromatic.chunkrevive.nms.BiomeMatchMode;
import github.freshchromatic.chunkrevive.nms.BiomeSampler;
import github.freshchromatic.chunkrevive.nms.BlockBounds;
import github.freshchromatic.chunkrevive.nms.ChunkCoordinate;
import github.freshchromatic.chunkrevive.nms.ChunkStage;
import github.freshchromatic.chunkrevive.nms.DiskChunkSession;
import github.freshchromatic.chunkrevive.nms.HeightmapKind;
import github.freshchromatic.chunkrevive.nms.RegionScanResult;
import github.freshchromatic.chunkrevive.nms.StoredChunk;
import github.freshchromatic.chunkrevive.nms.StructureInfo;
import github.freshchromatic.chunkrevive.nms.WorldScanGateway;
import github.freshchromatic.chunkrevive.nms.v26_1_2.terrain.GenerationContext;
import github.freshchromatic.chunkrevive.nms.v26_1_2.terrain.GenerationContextResolver;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

public final class V26_1_2WorldScanGateway implements WorldScanGateway {
    private static final Field LEVEL_STORAGE_ACCESS_FIELD = Arrays.stream(ServerLevel.class.getFields())
        .filter(field -> field.getType().equals(LevelStorageSource.LevelStorageAccess.class))
        .findAny()
        .orElse(null);

    @Override
    public BiomeSampler biomeSampler(World world, HeightmapKind heightmap) {
        ServerLevel level = ((CraftWorld) world).getHandle();
        return new Sampler(level, GenerationContextResolver.resolve(level, level.getSeed()), toNms(heightmap));
    }

    @Override
    public Set<String> biomeIds(World world) {
        ServerLevel level = ((CraftWorld) world).getHandle();
        Set<String> result = new java.util.TreeSet<>();
        level.registryAccess().lookupOrThrow(Registries.BIOME).listElementIds()
            .map(key -> key.identifier().toString())
            .forEach(result::add);
        return result;
    }

    @Override
    public DiskChunkSession openDiskSession(World world) {
        return new Session(((CraftWorld) world).getHandle(), world.getName());
    }

    private static Heightmap.Types toNms(HeightmapKind heightmap) {
        return switch (heightmap) {
            case OCEAN_FLOOR -> Heightmap.Types.OCEAN_FLOOR;
            case MOTION_BLOCKING -> Heightmap.Types.MOTION_BLOCKING;
            case WORLD_SURFACE -> Heightmap.Types.WORLD_SURFACE;
        };
    }

    private record Sampler(ServerLevel level, GenerationContext context,
                           Heightmap.Types heightmap) implements BiomeSampler {
        @Override
        public boolean matches(int chunkX, int chunkZ, Set<String> targets, BiomeMatchMode mode) {
            return switch (mode) {
                case CENTER -> targets.contains(centerBiome(chunkX, chunkZ));
                case ANY_OF_16 -> {
                    boolean found = false;
                    for (int dx = 0; dx < 4 && !found; dx++) {
                        for (int dz = 0; dz < 4; dz++) {
                            if (targets.contains(sample((chunkX << 4) + dx * 4 + 2,
                                                        (chunkZ << 4) + dz * 4 + 2))) {
                                found = true;
                                break;
                            }
                        }
                    }
                    yield found;
                }
            };
        }

        @Override
        public String centerBiome(int chunkX, int chunkZ) {
            return sample((chunkX << 4) + 8, (chunkZ << 4) + 8);
        }

        private String sample(int blockX, int blockZ) {
            int surfaceY = context.generator().getBaseHeight(
                blockX, blockZ, heightmap, level, context.randomState());
            Holder<Biome> biome = context.biomeSource().getNoiseBiome(
                QuartPos.fromBlock(blockX), QuartPos.fromBlock(surfaceY), QuartPos.fromBlock(blockZ),
                context.randomState().sampler());
            return biome.unwrapKey().map(key -> key.identifier().toString()).orElse(null);
        }
    }

    private static final class Session implements DiskChunkSession {
        private final ServerLevel level;
        private final String worldName;
        private final Path regionFolder;
        private final MoonriseRegionFileIO.RegionDataController chunkData;

        private Session(ServerLevel level, String worldName) {
            this.level = level;
            this.worldName = worldName;
            this.regionFolder = levelStorageAccess(level).getDimensionPath(level.dimension()).resolve("region");
            this.chunkData = ((ChunkSystemServerLevel) level).moonrise$getChunkDataController();
        }

        @Override
        public Path regionFolder() {
            return regionFolder;
        }

        @Override
        public boolean exists(int chunkX, int chunkZ, ChunkStage minimumStage) {
            try {
                CompoundTag nbt = readSafely(chunkX, chunkZ);
                if (nbt == null) return false;
                return !persistedStage(nbt).isBefore(minimumStage);
            } catch (Exception ignored) {
                return false;
            }
        }

        @Override
        public RegionScanResult scanRegion(int regionX, int regionZ, ChunkStage minimumStage,
                                           Predicate<String> structureFilter,
                                           BooleanSupplier cancelled) throws IOException {
            return onRegionIo(regionX << 5, regionZ << 5, () -> {
                List<StoredChunk> chunks = new ArrayList<>();
                int failures = 0;
                var storage = chunkData.getCache();
                for (int dx = 0; dx < 32; dx++) {
                    for (int dz = 0; dz < 32; dz++) {
                        if (cancelled.getAsBoolean()) return new RegionScanResult(chunks, failures);
                        int cx = regionX * 32 + dx, cz = regionZ * 32 + dz;
                        CompoundTag nbt;
                        try {
                            nbt = storage.read(new ChunkPos(cx, cz));
                        } catch (Exception failure) {
                            failures++;
                            continue;
                        }
                        if (nbt == null || persistedStage(nbt).isBefore(minimumStage)) continue;
                        chunks.add(new StoredChunk(
                            new ChunkCoordinate(cx, cz), structures(nbt, structureFilter)));
                    }
                }
                return new RegionScanResult(chunks, failures);
            });
        }

        private List<StructureInfo> structures(CompoundTag chunkNbt, Predicate<String> filter) {
            List<StructureInfo> result = new ArrayList<>();
            CompoundTag starts = chunkNbt.getCompoundOrEmpty("structures").getCompoundOrEmpty("starts");
            if (starts.isEmpty()) return result;
            var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
            var context = StructurePieceSerializationContext.fromLevel(level);
            for (String key : starts.keySet()) {
                if (!filter.test(key)) continue;
                Identifier id = Identifier.tryParse(key);
                if (id == null || registry.getValue(id) == null) continue;
                StructureStart start = StructureStart.loadStaticStart(
                    context, starts.getCompoundOrEmpty(key), level.getSeed());
                if (start == null || !start.isValid()) continue;
                var box = start.getBoundingBox();
                result.add(new StructureInfo(key, new BlockBounds(
                    box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())));
            }
            return result;
        }

        private CompoundTag readSafely(int chunkX, int chunkZ) throws IOException {
            return onRegionIo(chunkX, chunkZ,
                () -> chunkData.getCache().read(new ChunkPos(chunkX, chunkZ)));
        }

        @Override
        public void close() {
        }

        private <T> T onRegionIo(int chunkX, int chunkZ, IoOperation<T> operation)
                throws IOException {
            CompletableFuture<T> result = new CompletableFuture<>();
            var task = chunkData.createRegionIoTask(chunkX, chunkZ, () -> {
                try {
                    result.complete(operation.run());
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            }, Priority.LOWEST);
            if (!task.queue()) throw new IOException("Moonrise region I/O queue rejected scan task");
            try {
                return result.join();
            } catch (CompletionException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof IOException io) throw io;
                throw new IOException("Moonrise region I/O scan failed", cause);
            }
        }

        @FunctionalInterface
        private interface IoOperation<T> {
            T run() throws Exception;
        }

        private static ChunkStage persistedStage(CompoundTag nbt) {
            return ChunkStage.persisted(nbt.read("Status", Codec.STRING).orElse(""));
        }

    }

    private static LevelStorageSource.LevelStorageAccess levelStorageAccess(ServerLevel level) {
        if (LEVEL_STORAGE_ACCESS_FIELD == null) return level.getServer().storageSource;
        try {
            return (LevelStorageSource.LevelStorageAccess) LEVEL_STORAGE_ACCESS_FIELD.get(level);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access the world's level storage", e);
        }
    }
}
