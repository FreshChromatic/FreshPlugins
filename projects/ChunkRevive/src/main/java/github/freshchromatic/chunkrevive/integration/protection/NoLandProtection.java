package github.freshchromatic.chunkrevive.integration.protection;

import github.freshchromatic.chunkrevive.nms.ChunkCoordinate;
import github.freshchromatic.chunkrevive.config.PluginConfig;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;

/** Null-object implementation used when no supported claim plugin is installed. */
public final class NoLandProtection implements LandProtection {

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public boolean hasClaim(World world, int chunkX, int chunkZ) {
        return false;
    }

    @Override
    public List<ChunkCoordinate> resolveEffectiveChunks(String world, int minChunkX, int maxChunkX, int minChunkZ,
        int maxChunkZ, PluginConfig.Structure.PartialClaimPolicy policy) {
        List<ChunkCoordinate> chunks = new ArrayList<>();
        for (int x = minChunkX; x <= maxChunkX; x++) {
            for (int z = minChunkZ; z <= maxChunkZ; z++) {
                chunks.add(new ChunkCoordinate(x, z));
            }
        }
        return chunks;
    }
}
