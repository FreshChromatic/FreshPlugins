package github.freshchromatic.chunkrevive.feature.scanning;

import github.freshchromatic.chunkrevive.nms.BiomeMatchMode;
import github.freshchromatic.chunkrevive.nms.BiomeSampler;
import github.freshchromatic.chunkrevive.nms.HeightmapKind;
import github.freshchromatic.chunkrevive.nms.NmsPlatformLoader;
import org.bukkit.World;

import java.util.Set;

/** Version-neutral facade for procedural biome matching. */
public final class BiomeMatcher {
    private final BiomeSampler sampler;

    public BiomeMatcher(World world, HeightmapKind heightmap) {
        this.sampler = NmsPlatformLoader.load().worldScan().biomeSampler(world, heightmap);
    }

    public boolean matches(int chunkX, int chunkZ, Set<String> targets, BiomeMatchMode mode) {
        return sampler.matches(chunkX, chunkZ, targets, mode);
    }

    public String centerBiome(int chunkX, int chunkZ) {
        return sampler.centerBiome(chunkX, chunkZ);
    }
}
