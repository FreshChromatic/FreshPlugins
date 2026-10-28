package github.freshchromatic.chunkrevive.integration.protection;

import org.ashin.chunkClaimPlugin2.api.ChunkClaimAPI;
import org.bukkit.World;

final class ChunkClaimProtection extends ExternalProtection {
    ChunkClaimProtection() {
        super("CCP");
    }

    @Override
    public boolean hasClaim(World world, int x, int z) {
        var manager = ChunkClaimAPI.getInstance().getChunkManager();
        return manager.getChunkOwner(manager.getChunkKey(world.getName(), x, z)) != null;
    }
}
