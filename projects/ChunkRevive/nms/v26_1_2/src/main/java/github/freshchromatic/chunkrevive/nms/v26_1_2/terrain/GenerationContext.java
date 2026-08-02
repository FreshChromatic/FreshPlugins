package github.freshchromatic.chunkrevive.nms.v26_1_2.terrain;

import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * The immutable generation objects created by the server when a world is loaded.
 *
 * @param generator the active generator, including Paper's Bukkit wrapper when present
 * @param terrainGenerator the generator below CraftEngine's storage-injection wrapper
 * @param noiseGenerator the underlying vanilla noise generator used only by the synchronous fast path
 */
public record GenerationContext(
    ChunkGenerator generator,
    ChunkGenerator terrainGenerator,
    NoiseBasedChunkGenerator noiseGenerator,
    RandomState randomState,
    BiomeSource biomeSource
) {}
