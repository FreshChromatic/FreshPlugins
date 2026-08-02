package github.freshchromatic.chunkrevive.nms.v26_2.terrain;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.bukkit.craftbukkit.generator.CustomChunkGenerator;

/**
 * Resolves the server-owned generation context needed to query terrain/biomes without materializing chunks.
 *
 * <p>Shared by terrain regeneration and biome matching so both paths use precisely the same active
 * generator, biome source and cached random state.
 */
public final class GenerationContextResolver {
    private GenerationContextResolver() {}

    public static GenerationContext resolve(ServerLevel nmsLevel, long seed) {
        var chunkSource = nmsLevel.getChunkSource();
        ChunkGenerator activeGenerator = chunkSource.getGenerator();
        ChunkGenerator terrainGenerator = unwrapCraftEngineLayers(activeGenerator);
        NoiseBasedChunkGenerator noiseGenerator = unwrapNoiseGenerator(terrainGenerator);
        if (noiseGenerator == null) {
            throw new IllegalStateException(
                "ChunkRevive cannot safely regenerate world '" + nmsLevel.getWorld().getName()
                    + "': unsupported chunk generator " + activeGenerator.getClass().getName());
        }

        // The seed supplied by callers is the world's own seed. Reusing these objects is both faster
        // and more correct than rebuilding vanilla defaults: Paper already constructed them with the
        // world's datapacks, biome provider, Spigot structure salts and custom-generator wrapper.
        if (seed != nmsLevel.getSeed()) {
            throw new IllegalArgumentException(
                "ChunkRevive regeneration seed does not match world seed for '" + nmsLevel.getWorld().getName() + "'");
        }
        BiomeSource biomeSource = activeGenerator.getBiomeSource();
        return new GenerationContext(activeGenerator, terrainGenerator, noiseGenerator, chunkSource.randomState(), biomeSource);
    }

    private static ChunkGenerator unwrapCraftEngineLayers(ChunkGenerator generator) {
        ChunkGenerator current = generator;
        for (int depth = 0; depth < 4; depth++) {
            ChunkGenerator delegate = unwrapCraftEngineGenerator(current);
            if (delegate == null) return current;
            current = delegate;
        }
        return current;
    }

    private static NoiseBasedChunkGenerator unwrapNoiseGenerator(ChunkGenerator generator) {
        ChunkGenerator current = generator;
        // Paper uses this wrapper whenever a Bukkit ChunkGenerator is configured for a world.
        // Keep the wrapper as the active pipeline generator, but expose its vanilla delegate for
        // the private synchronous noise methods used by the vanilla-only fast path.
        for (int depth = 0; depth < 4; depth++) {
            if (current instanceof NoiseBasedChunkGenerator noise) return noise;
            if (current instanceof CustomChunkGenerator custom) {
                current = custom.getDelegate();
                continue;
            }
            return null;
        }
        return null;
    }

    /**
     * CraftEngine injects a version-specific ChunkGenerator wrapper at runtime. ChunkRevive must not
     * compile against that implementation (its package changes every Minecraft release and belongs
     * to another plugin class loader), but every supported implementation exposes one private
     * ChunkGenerator delegate named {@code target}. Keep the CraftEngine wrapper as the active
     * generator so its custom-block/features hooks still run; reflection is used only to discover
     * the underlying NoiseBasedChunkGenerator needed by our vanilla synchronous fast path.
     */
    private static ChunkGenerator unwrapCraftEngineGenerator(ChunkGenerator generator) {
        Class<?> type = generator.getClass();
        String name = type.getName();
        if (!name.startsWith("net.momirealms.craftengine.bukkit.nms.")
            || !name.endsWith(".worldgen.InjectedCustomChunkGenerator")) {
            return null;
        }

        try {
            java.lang.reflect.Field delegateField;
            try {
                delegateField = type.getDeclaredField("target");
            } catch (NoSuchFieldException missingNamedField) {
                // Be tolerant of a future field rename, but only when the known CraftEngine wrapper
                // still has exactly one field capable of holding a ChunkGenerator.
                java.util.List<java.lang.reflect.Field> candidates = java.util.Arrays.stream(type.getDeclaredFields())
                    .filter(field -> ChunkGenerator.class.isAssignableFrom(field.getType()))
                    .toList();
                if (candidates.size() != 1) return null;
                delegateField = candidates.getFirst();
            }
            delegateField.setAccessible(true);
            Object delegate = delegateField.get(generator);
            return delegate instanceof ChunkGenerator chunkGenerator ? chunkGenerator : null;
        } catch (ReflectiveOperationException | RuntimeException inaccessible) {
            return null;
        }
    }
}
