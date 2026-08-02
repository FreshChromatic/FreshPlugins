package github.freshchromatic.chunkrevive.nms.v26_2.terrain;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.levelgen.RandomState;
import org.jspecify.annotations.Nullable;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ProtoChunk;
import java.util.Map;

import java.lang.reflect.Field;

/**
 * {@link WorldGenRegion} subclass that replaces the level-seed fields injected
 * by the parent constructor with per-match values, so that carvers, surface rules
 * and biome queries use the player's seed instead of the shared-grid world's seed.
 *
 * <p>The parent constructor hard-codes {@code this.seed = level.getSeed()},
 * {@code this.biomeManager = new BiomeManager(this, obfuscateSeed(seed))}, and
 * {@code this.random = randomState().getOrCreateRandomFactory(...).at(...)}.
 * All three {@code private final} fields are overwritten after construction via
 * {@code sun.misc.Unsafe} (bypasses {@code final} without requiring
 * {@code --add-opens java.base/java.lang.reflect}).
 */
final class SeededWorldGenRegion extends WorldGenRegion {

    private static final sun.misc.Unsafe UNSAFE = resolveUnsafe();
    private static final long OFFSET_SEED;
    private static final long OFFSET_RANDOM;
    private static final long OFFSET_BIOME_MANAGER;

    static {
        try {
            OFFSET_SEED         = UNSAFE.objectFieldOffset(WorldGenRegion.class.getDeclaredField("seed"));
            OFFSET_RANDOM       = UNSAFE.objectFieldOffset(WorldGenRegion.class.getDeclaredField("random"));
            OFFSET_BIOME_MANAGER = UNSAFE.objectFieldOffset(WorldGenRegion.class.getDeclaredField("biomeManager"));
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(
                "SeededWorldGenRegion: WorldGenRegion field layout changed — " + e.getMessage());
        }
    }

    private static final Identifier WORLDGEN_REGION_RANDOM =
        Identifier.withDefaultNamespace("worldgen_region_random");

    private final BiomeSource customBiomeSource;
    private final RandomState customRandomState;
    private final Map<ChunkPos, ProtoChunk> globalChunks;
    private final ServerLevel level;
    private final boolean structureReferenceStage;
    private final boolean carverStage;
    private final boolean surfaceStage;
    private final @Nullable ProtoChunk emptyStructureContext;
    private final @Nullable ProtoChunk emptySurfaceContext;
    private final java.util.concurrent.ConcurrentMap<Holder<Biome>, ProtoChunk> carverBiomeContexts;

    SeededWorldGenRegion(
        ServerLevel level,
        StaticCache2D<GenerationChunkHolder> cache,
        ChunkStep step,
        ChunkAccess center,
        long playerSeed,
        BiomeSource customBiomeSource,
        RandomState customRandomState,
        Map<ChunkPos, ProtoChunk> globalChunks,
        java.util.concurrent.ConcurrentMap<Holder<Biome>, ProtoChunk> carverBiomeContexts
    ) {
        super(level, cache, step, center);
        this.customBiomeSource = customBiomeSource;
        this.customRandomState = customRandomState;
        this.globalChunks = globalChunks;
        this.carverBiomeContexts = carverBiomeContexts;
        this.level = level;
        this.structureReferenceStage = step.targetStatus() == ChunkStatus.STRUCTURE_REFERENCES;
        this.carverStage = step.targetStatus() == ChunkStatus.CARVERS;
        this.surfaceStage = step.targetStatus() == ChunkStatus.SURFACE;

        // ChunkGenerator#createReferences scans a fixed 17x17 area around every target chunk.
        // Positions outside ChunkRevive's deliberately smaller disk context need only expose an
        // empty structure-start map. Reusing one immutable sentinel avoids allocating thousands of
        // full-height ProtoChunks (and the resulting apparent hang/GC storm) for a compact batch.
        if (this.structureReferenceStage) {
            this.emptyStructureContext = new ProtoChunk(
                center.getPos(),
                net.minecraft.world.level.chunk.UpgradeData.EMPTY,
                this.level,
                this.level.palettedContainerFactory(),
                null
            );
            this.emptyStructureContext.setPersistedStatus(ChunkStatus.STRUCTURE_STARTS);
        } else {
            // This used to allocate a full-height ProtoChunk for every BIOMES/NOISE/SURFACE/
            // CARVERS/FEATURES region even though only createReferences can observe it.
            this.emptyStructureContext = null;
        }

        // NoiseBasedChunkGenerator#buildSurface constructs its own Blender and scans beyond the
        // declared radius-one surface cache. For an absent position Blender only reads
        // ChunkAccess#getBlendingData; generating a procedural biome palette there is wasted work.
        // Several concurrent surface regions commonly request the same boundary position, so the
        // old globalChunks.computeIfAbsent path also held a ConcurrentHashMap reservation while
        // filling biomes and serialised every generation worker behind it.
        if (this.surfaceStage) {
            this.emptySurfaceContext = new ProtoChunk(
                center.getPos(),
                net.minecraft.world.level.chunk.UpgradeData.EMPTY,
                this.level,
                this.level.palettedContainerFactory(),
                null
            );
        } else {
            this.emptySurfaceContext = null;
        }

        UNSAFE.putLong(this, OFFSET_SEED, playerSeed);
        UNSAFE.putObject(this, OFFSET_BIOME_MANAGER,
            new BiomeManager(this, BiomeManager.obfuscateSeed(playerSeed)));
        UNSAFE.putObject(this, OFFSET_RANDOM,
            customRandomState
                .getOrCreateRandomFactory(WORLDGEN_REGION_RANDOM)
                .at(center.getPos().getWorldPosition()));
    }

    /**
     * Paper 26.1.2's {@link WorldGenRegion#getChunk(int, int, ChunkStatus, boolean)} always
     * throws {@link ReportedException} when it cannot satisfy the request — it never returns
     * {@code null} even when {@code loadOrGenerate=false}. This override restores the correct
     * semantics: {@code loadOrGenerate=false} returns the ProtoChunk from our synthetic cache
     * (as {@code ChunkStatus.EMPTY}), or {@code null} if the coordinates are outside the cache radius.
     */
    @Override
    public @Nullable ChunkAccess getChunk(int chunkX, int chunkZ, ChunkStatus targetStatus, boolean loadOrGenerate) {
        ProtoChunk pc = globalChunks.get(new ChunkPos(chunkX, chunkZ));
        if (pc != null) {
            return pc;
        }

        if (this.structureReferenceStage) {
            return java.util.Objects.requireNonNull(this.emptyStructureContext);
        }

        if (this.carverStage) {
            // NoiseBasedChunkGenerator#applyCarvers scans a fixed 17x17 neighborhood and only
            // asks each neighboring ChunkAccess for its cached BiomeGenerationSettings. Building
            // a full-height ProtoChunk for every missing coordinate makes one target allocate up
            // to 289 chunks. A sentinel per biome preserves the generator's carver selection: the
            // first carverBiome(supplier) call seeds it with the settings for exactly that biome.
            Holder<Biome> biome = this.customBiomeSource.getNoiseBiome(
                QuartPos.fromBlock(chunkX << 4),
                0,
                QuartPos.fromBlock(chunkZ << 4),
                this.customRandomState.sampler()
            );
            return this.carverBiomeContexts.computeIfAbsent(biome, ignored -> {
                ProtoChunk fallback = new ProtoChunk(
                    new ChunkPos(chunkX, chunkZ),
                    net.minecraft.world.level.chunk.UpgradeData.EMPTY,
                    this.level,
                    this.level.palettedContainerFactory(),
                    null
                );
                fallback.setPersistedStatus(ChunkStatus.BIOMES);
                return fallback;
            });
        }

        if (this.surfaceStage) {
            return java.util.Objects.requireNonNull(this.emptySurfaceContext);
        }

        // Never delegate an out-of-cache lookup to the live server from an async generation
        // worker. Third-party features may legitimately inspect beyond vanilla's declared radius;
        // cache a procedural-biome/air-terrain boundary chunk for those reads instead.
        ChunkPos requested = new ChunkPos(chunkX, chunkZ);
        // Store read-only procedural boundary chunks in the batch-global map. FEATURES creates one
        // WorldGenRegion per source chunk, so a per-region cache regenerated the same biome palette
        // many times. Writable positions are already present in globalChunks before this path.
        return this.globalChunks.computeIfAbsent(requested, pos -> {
            ProtoChunk fallback = new ProtoChunk(
                pos,
                net.minecraft.world.level.chunk.UpgradeData.EMPTY,
                this.level,
                this.level.palettedContainerFactory(),
                null
            );
            fallback.fillBiomesFromNoise(this.customBiomeSource, this.customRandomState.sampler());
            fallback.setPersistedStatus(ChunkStatus.BIOMES);
            return fallback;
        });
    }

    @Override
    public Holder<Biome> getUncachedNoiseBiome(int quartX, int quartY, int quartZ) {
        return customBiomeSource.getNoiseBiome(quartX, quartY, quartZ, customRandomState.sampler());
    }

    @Override
    public boolean setBlock(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState blockState, int updateFlags, int updateLimit) {
        this.getChunk(pos); // Validate/cache the destination before delegating to WorldGenRegion.
        // NmsTerrainGenerator executes FEATURES in a 3x3 colouring. Tasks in one stripe have
        // disjoint radius-one write sets, and different regen batches own different scratch maps.
        // A monitor acquisition for every generated block therefore provided no mutual exclusion.
        return super.setBlock(pos, blockState, updateFlags, updateLimit);
    }

    @Override
    public boolean addFreshEntity(net.minecraft.world.entity.Entity entity) {
        int xc = net.minecraft.core.SectionPos.blockToSectionCoord(entity.getBlockX());
        int zc = net.minecraft.core.SectionPos.blockToSectionCoord(entity.getBlockZ());
        this.getChunk(xc, zc); // Validate/cache the destination using the same path as block writes.
        return super.addFreshEntity(entity);
    }

    private static sun.misc.Unsafe resolveUnsafe() {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (sun.misc.Unsafe) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError("Cannot access sun.misc.Unsafe: " + e.getMessage());
        }
    }
}
