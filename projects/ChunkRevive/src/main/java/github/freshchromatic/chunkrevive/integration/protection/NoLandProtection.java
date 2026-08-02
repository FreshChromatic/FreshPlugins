package github.freshchromatic.chunkrevive.integration.protection;

import github.freshchromatic.chunkrevive.config.PluginConfig;

import java.util.ArrayList;
import java.util.List;

/** Null-object implementation used when no supported claim plugin is installed. */
public final class NoLandProtection implements LandProtection {
    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public boolean hasClaim(org.bukkit.World world, int chunkX, int chunkZ) {
        return false;
    }

    @Override
    public List<int[]> resolveEffectiveChunks(String world, int minChunkX, int maxChunkX,
                                              int minChunkZ, int maxChunkZ,
                                              PluginConfig.Structure.PartialClaimPolicy policy) {
        List<int[]> chunks = new ArrayList<>();
        for (int x = minChunkX; x <= maxChunkX; x++) {
            for (int z = minChunkZ; z <= maxChunkZ; z++) chunks.add(new int[]{x, z});
        }
        return chunks;
    }
}
