package github.freshchromatic.chunkrevive.nms.v26_3.terrain;

import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.BelowZeroRetrogen;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

final class TerrainGenerationStep {
    private TerrainGenerationStep() {
    }

    static void generate(
        ChunkGenerator generator,
        ProtoChunk chunk,
        WorldGenRegion region,
        RandomState randomState,
        Blender blender
    ) {
        generator.buildTerrain(
            chunk,
            blender,
            randomState,
            region.getLevel().structureManager().forWorldGenRegion(region),
            region.getBiomeManager(),
            region,
            collectPossibleBiomes(region)
        ).join();

        BelowZeroRetrogen retrogen = chunk.getBelowZeroRetrogen();
        if (retrogen != null) {
            BelowZeroRetrogen.replaceOldBedrock(chunk);
            if (retrogen.hasBedrockHoles()) {
                retrogen.applyBedrockMask(chunk);
            }
        }

        Heightmap.primeHeightmaps(chunk, EnumSet.of(
            Heightmap.Types.MOTION_BLOCKING,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            Heightmap.Types.OCEAN_FLOOR,
            Heightmap.Types.WORLD_SURFACE
        ));
        chunk.setPersistedStatus(ChunkStatus.TERRAIN);
    }

    private static Set<Holder<Biome>> collectPossibleBiomes(WorldGenRegion region) {
        Set<Holder<Biome>> biomes = new HashSet<>();
        ChunkPos center = region.getCenter();
        for (int z = center.z() - 1; z <= center.z() + 1; z++) {
            for (int x = center.x() - 1; x <= center.x() + 1; x++) {
                region.getChunk(x, z).collectBiomesInPalette(biomes);
            }
        }
        return biomes;
    }
}
