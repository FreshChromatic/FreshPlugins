package github.freshchromatic.chunkrevive.nms;

import java.util.Set;

/** Thread-safe procedural biome sampler bound to one live world. */
public interface BiomeSampler {
    boolean matches(int chunkX, int chunkZ, Set<String> biomeIds, BiomeMatchMode mode);

    String centerBiome(int chunkX, int chunkZ);
}
